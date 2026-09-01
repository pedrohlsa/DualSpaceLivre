package top.niunaijun.blackbox.core.system.am;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.system.BProcessManagerService;
import top.niunaijun.blackbox.core.system.ISystemService;
import top.niunaijun.blackbox.core.system.ProcessRecord;
import top.niunaijun.blackbox.core.system.pm.BPackageManagerService;
import top.niunaijun.blackbox.entity.AppConfig;
import top.niunaijun.blackbox.entity.UnbindRecord;
import top.niunaijun.blackbox.entity.am.PendingResultData;
import top.niunaijun.blackbox.entity.am.ReceiverData;
import top.niunaijun.blackbox.entity.am.RunningAppProcessInfo;
import top.niunaijun.blackbox.entity.am.RunningServiceInfo;
import top.niunaijun.blackbox.utils.Slog;

import static android.content.pm.PackageManager.GET_META_DATA;


public class BActivityManagerService extends IBActivityManagerService.Stub implements ISystemService {
    public static final String TAG = "BActivityManagerService";
    private static final BActivityManagerService sService = new BActivityManagerService();
    private final Map<Integer, UserSpace> mUserSpace = new HashMap<>();
    private final BPackageManagerService mPms = BPackageManagerService.get();
    private final BroadcastManager mBroadcastManager;
    private final ExecutorService mBroadcastExecutor = Executors.newCachedThreadPool();
    private static final long BACKGROUND_TASK_GRACE_MS = 3_000L;
    private static final long PROCESS_FLUSH_GRACE_MS = 2_000L;
    private final ScheduledExecutorService mUserSwitchExecutor =
            Executors.newSingleThreadScheduledExecutor();
    private final AtomicLong mForegroundGeneration = new AtomicLong();
    private volatile int mForegroundUserId = -1;
    /**
     * How long an explicit launch outranks activity resumes. Long enough for the
     * space being retired to finish resuming and destroying its own activities,
     * short enough that returning to a guest straight from Recents — which never
     * passes through the launcher — still takes effect.
     */
    private static final long EXPLICIT_SELECTION_HOLD_MS = 10_000L;
    private volatile long mExplicitSelectionAt = 0L;

    public static BActivityManagerService get() {
        return sService;
    }

    public BActivityManagerService() {
        mBroadcastManager = BroadcastManager.startSystem(this, mPms);
    }

    @Override
    public void stopUser(int userId) {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mStack) {
            userSpace.mStack.clearAllTasks();
        }
        synchronized (userSpace.mActiveServices) {
            userSpace.mActiveServices.stopAll(userId);
        }
        BProcessManagerService.get().killAllByUserId(userId);
    }

    @Override
    public ComponentName startService(Intent intent, String resolvedType, boolean requireForeground, int userId) {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mActiveServices) {
            userSpace.mActiveServices.startService(intent, resolvedType, requireForeground, userId);
        }
        return null;
    }

    @Override
    public IBinder acquireContentProviderClient(ProviderInfo providerInfo) throws RemoteException {
        int callingPid = Binder.getCallingPid();
        ProcessRecord processRecord = BProcessManagerService.get().startProcessLocked(providerInfo.packageName,
                providerInfo.processName,
                BProcessManagerService.get().getUserIdByCallingPid(callingPid),
                -1,
                Binder.getCallingPid());
        if (processRecord == null) {
            throw new RuntimeException("Unable to create process " + providerInfo.name);
        }
        try {
            return processRecord.bActivityThread.acquireContentProviderClient(providerInfo);
        } catch (Throwable t) {
            t.printStackTrace();
            return null;
        }
    }

    @Override
    public Intent sendBroadcast(Intent intent, String resolvedType, int userId) throws RemoteException {
        List<ResolveInfo> resolves = BPackageManagerService.get().queryBroadcastReceivers(intent, GET_META_DATA, resolvedType, userId);

        for (ResolveInfo resolve : resolves) {
            ProcessRecord processRecord = BProcessManagerService.get().findProcessRecord(resolve.activityInfo.packageName, resolve.activityInfo.processName, userId);
            if (processRecord == null) {
                continue;
            }
            if (processRecord.bActivityThread == null) {
                continue;
            }
            try {
                processRecord.bActivityThread.bindApplication();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
        Intent shadow = new Intent();
        shadow.setPackage(BlackBoxCore.getHostPkg());
        shadow.setComponent(null);
        shadow.setAction(intent.getAction());
        return shadow;
    }

    @Override
    public IBinder peekService(Intent intent, String resolvedType, int userId) throws RemoteException {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mActiveServices) {
            return userSpace.mActiveServices.peekService(intent, resolvedType, userId);
        }
    }

    @Override
    public void onActivityCreated(int taskId, IBinder token, IBinder activityRecord) throws RemoteException {
        int callingPid = Binder.getCallingPid();
        ProcessRecord process = BProcessManagerService.get().findProcessByPid(callingPid);
        if (process == null) {
            return;
        }
        ActivityRecord record = (ActivityRecord) activityRecord;
        UserSpace userSpace = getOrCreateSpaceLocked(process.userId);
        synchronized (userSpace.mStack) {
            userSpace.mStack.onActivityCreated(process, taskId, token, record);
        }
    }

    @Override
    public void onActivityResumed(IBinder token) throws RemoteException {
        int callingPid = Binder.getCallingPid();
        ProcessRecord process = BProcessManagerService.get().findProcessByPid(callingPid);
        if (process == null) {
            return;
        }
        UserSpace userSpace = getOrCreateSpaceLocked(process.userId);
        synchronized (userSpace.mStack) {
            userSpace.mStack.onActivityResumed(process.userId, token);
        }
        selectForegroundUser(process.userId, false);
    }

    /**
     * One source of truth for which virtual user is in front.
     *
     * A guest that is being retired keeps resuming activities while it winds
     * down, and Instagram resumes plenty of its own on the way out. Treating any
     * resume as a switch let the outgoing space reclaim the foreground and
     * retire the incoming one instead — observed on device as two live guests
     * and a retire pass aimed at a space that was not even running.
     */
    private void selectForegroundUser(int userId, boolean explicit) {
        if (explicit) {
            mExplicitSelectionAt = android.os.SystemClock.uptimeMillis();
        } else if (mForegroundUserId != userId
                && android.os.SystemClock.uptimeMillis() - mExplicitSelectionAt
                        < EXPLICIT_SELECTION_HOLD_MS) {
            Slog.i(TAG, "ignoring resume from user " + userId
                    + "; user " + mForegroundUserId + " was explicitly selected");
            return;
        }
        scheduleSingleActiveUser(userId);
    }

    /**
     * Keeps one virtual user resident at a time without killing a guest while
     * it is still writing its session. The newly resumed user wins. After a
     * short debounce, tasks and proxy services from other users are stopped;
     * their PIDs are killed only after a second grace period. A rapid switch
     * back invalidates both phases through the generation check.
     */
    private void scheduleSingleActiveUser(int userId) {
        // Only a change of space is a switch. onActivityResumed fires for every
        // activity a guest resumes, and Instagram resumes them constantly — the
        // camera, the editor, the chooser, the permission dialogs. Rescheduling
        // on each one queued a retire pass per resume on a single-threaded
        // executor, where all but the last expired doing nothing, and left the
        // window in which an incidental resume could redefine which space is in
        // front. Re-entering the space that is already resident is not a switch
        // and has nothing to retire.
        if (mForegroundUserId == userId) {
            return;
        }
        mForegroundUserId = userId;
        final long generation = mForegroundGeneration.incrementAndGet();
        mUserSwitchExecutor.schedule(
                () -> prepareBackgroundUsers(userId, generation),
                BACKGROUND_TASK_GRACE_MS,
                TimeUnit.MILLISECONDS);
    }

    private boolean isCurrentForeground(int userId, long generation) {
        return mForegroundUserId == userId
                && mForegroundGeneration.get() == generation;
    }

    private void prepareBackgroundUsers(int foregroundUserId, long generation) {
        if (!isCurrentForeground(foregroundUserId, generation)) {
            return;
        }
        Set<Integer> backgroundUsers = new HashSet<>(
                BProcessManagerService.get().getRunningUserIds());
        synchronized (mUserSpace) {
            backgroundUsers.addAll(mUserSpace.keySet());
        }
        backgroundUsers.remove(foregroundUserId);
        if (backgroundUsers.isEmpty()) {
            return;
        }

        for (int userId : backgroundUsers) {
            if (!isCurrentForeground(foregroundUserId, generation)) {
                return;
            }
            UserSpace userSpace;
            synchronized (mUserSpace) {
                userSpace = mUserSpace.get(userId);
            }
            if (userSpace == null) {
                continue;
            }
            long startedAt = android.os.SystemClock.uptimeMillis();
            synchronized (userSpace.mStack) {
                userSpace.mStack.clearAllTasks();
            }
            synchronized (userSpace.mActiveServices) {
                userSpace.mActiveServices.stopAll(userId);
            }
            Slog.i(TAG, "retired background user " + userId + " after user "
                    + foregroundUserId + " resumed, in "
                    + (android.os.SystemClock.uptimeMillis() - startedAt) + "ms");
        }

        mUserSwitchExecutor.schedule(() -> {
            if (!isCurrentForeground(foregroundUserId, generation)) {
                return;
            }
            for (int userId : backgroundUsers) {
                if (userId != foregroundUserId) {
                    BProcessManagerService.get().killAllByUserId(userId);
                }
            }
        }, PROCESS_FLUSH_GRACE_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public void onActivityDestroyed(IBinder token) throws RemoteException {
        int callingPid = Binder.getCallingPid();
        ProcessRecord process = BProcessManagerService.get().findProcessByPid(callingPid);
        if (process == null) {
            return;
        }
        UserSpace userSpace = getOrCreateSpaceLocked(process.userId);
        synchronized (userSpace.mStack) {
            userSpace.mStack.onActivityDestroyed(process.userId, token);
        }
    }

    @Override
    public void onFinishActivity(IBinder token) throws RemoteException {
        int callingPid = Binder.getCallingPid();
        ProcessRecord process = BProcessManagerService.get().findProcessByPid(callingPid);
        if (process == null) {
            return;
        }
        UserSpace userSpace = getOrCreateSpaceLocked(process.userId);
        synchronized (userSpace.mStack) {
            userSpace.mStack.onFinishActivity(process.userId, token);
        }
    }

    @Override
    public RunningAppProcessInfo getRunningAppProcesses(String callerPackage, int userId) throws RemoteException {
        ActivityManager manager = (ActivityManager)
                BlackBoxCore.getContext().getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> runningAppProcesses = manager.getRunningAppProcesses();
        Map<Integer, ActivityManager.RunningAppProcessInfo> runningProcessMap = new HashMap<>();
        for (ActivityManager.RunningAppProcessInfo runningProcess : runningAppProcesses) {
            runningProcessMap.put(runningProcess.pid, runningProcess);
        }
        List<ProcessRecord> packageProcessAsUser = BProcessManagerService.get().getPackageProcessAsUser(callerPackage, userId);

        RunningAppProcessInfo appProcessInfo = new RunningAppProcessInfo();
        for (ProcessRecord processRecord : packageProcessAsUser) {
            ActivityManager.RunningAppProcessInfo runningAppProcessInfo = runningProcessMap.get(processRecord.pid);
            if (runningAppProcessInfo != null) {
                runningAppProcessInfo.processName = processRecord.processName;
                appProcessInfo.mAppProcessInfoList.add(runningAppProcessInfo);
            }
        }
        return appProcessInfo;
    }

    @Override
    public RunningServiceInfo getRunningServices(String callerPackage, int userId) throws RemoteException {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        return userSpace.mActiveServices.getRunningServiceInfo(callerPackage, userId);
    }

    @Override
    public void scheduleBroadcastReceiver(Intent intent, PendingResultData pendingResultData, int userId) throws RemoteException {
        List<ResolveInfo> resolves = BPackageManagerService.get().queryBroadcastReceivers(intent, GET_META_DATA, null, userId);

        if (resolves.isEmpty()) {
            pendingResultData.build().finish();
            Slog.d(TAG, "scheduleBroadcastReceiver empty");
            return;
        }
        mBroadcastManager.sendBroadcast(pendingResultData);
        for (ResolveInfo resolve : resolves) {
            ProcessRecord processRecord = BProcessManagerService.get().findProcessRecord(resolve.activityInfo.packageName, resolve.activityInfo.processName, userId);
            if (processRecord != null && processRecord.bActivityThread != null) {
                ReceiverData data = new ReceiverData();
                data.intent = intent;
                data.activityInfo = resolve.activityInfo;
                data.data = pendingResultData;
                mBroadcastExecutor.execute(() -> {
                    try {
                        if (processRecord.bActivityThread != null) {
                            processRecord.bActivityThread.scheduleReceiver(data);
                        }
                    } catch (Throwable error) {
                        Slog.w(TAG, "Unable to deliver guest broadcast to "
                                + resolve.activityInfo.packageName + ": " + error.getMessage());
                    }
                });
            }
        }
    }

    @Override
    public void finishBroadcast(PendingResultData data) throws RemoteException {
        mBroadcastManager.finishBroadcast(data);
    }

    @Override
    public String getCallingPackage(IBinder token, int userId) throws RemoteException {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mStack) {
            return userSpace.mStack.getCallingPackage(token, userId);
        }
    }

    @Override
    public ComponentName getCallingActivity(IBinder token, int userId) throws RemoteException {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mStack) {
            return userSpace.mStack.getCallingActivity(token, userId);
        }
    }

    @Override
    public void getIntentSender(IBinder target, String packageName, int uid, int userId) {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mIntentSenderRecords) {
            PendingIntentRecord record = new PendingIntentRecord();
            record.uid = uid;
            record.packageName = packageName;
            userSpace.mIntentSenderRecords.put(target, record);
        }
    }

    @Override
    public String getPackageForIntentSender(IBinder target, int userId) throws RemoteException {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mIntentSenderRecords) {
            PendingIntentRecord record = userSpace.mIntentSenderRecords.get(target);
            if (record != null) {
                return record.packageName;
            }
        }
        return null;
    }

    @Override
    public int getUidForIntentSender(IBinder target, int userId) throws RemoteException {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mIntentSenderRecords) {
            PendingIntentRecord record = userSpace.mIntentSenderRecords.get(target);
            if (record != null) {
                return record.uid;
            }
        }
        return -1;
    }

    @Override
    public void onStartCommand(Intent intent, int userId) throws RemoteException {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mActiveServices) {
            userSpace.mActiveServices.onStartCommand(intent, userId);
        }
    }

    @Override
    public UnbindRecord onServiceUnbind(Intent proxyIntent, int userId) throws RemoteException {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mActiveServices) {
            return userSpace.mActiveServices.onServiceUnbind(proxyIntent, userId);
        }
    }

    @Override
    public void onServiceDestroy(Intent proxyIntent, int userId) throws RemoteException {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mActiveServices) {
            userSpace.mActiveServices.onServiceDestroy(proxyIntent, userId);
        }
    }

    @Override
    public int stopService(Intent intent, String resolvedType, int userId) {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mActiveServices) {
            return userSpace.mActiveServices.stopService(intent, resolvedType, userId);
        }
    }

    @Override
    public Intent bindService(Intent service, IBinder binder, String resolvedType, int userId) throws RemoteException {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mActiveServices) {
            return userSpace.mActiveServices.bindService(service, binder, resolvedType, userId);
        }
    }

    @Override
    public void unbindService(IBinder binder, int userId) throws RemoteException {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mActiveServices) {
            userSpace.mActiveServices.unbindService(binder, userId);
        }
    }

    @Override
    public void stopServiceToken(ComponentName className, IBinder token, int userId) throws RemoteException {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mActiveServices) {
            userSpace.mActiveServices.stopServiceToken(className, token, userId);
        }
    }

    @Override
    public AppConfig initProcess(String packageName, String processName, int userId) throws RemoteException {
        ProcessRecord processRecord = BProcessManagerService.get().startProcessLocked(packageName, processName, userId, -1, Binder.getCallingPid());
        if (processRecord == null)
            return null;
        return processRecord.getClientConfig();
    }

    @Override
    public void restartProcess(String packageName, String processName, int userId) throws RemoteException {
        BProcessManagerService.get().restartAppProcess(packageName, processName, userId);
    }

    @Override
    public void startActivity(Intent intent, int userId) {
        // The launcher asking for an app in a named space is the only
        // unambiguous statement of which space the user chose. Everything else
        // is inference.
        selectForegroundUser(userId, true);
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mStack) {
            userSpace.mStack.startActivityLocked(userId, intent, null, null, null, -1, -1, null);
        }
    }

    @Override
    public int startActivityAms(int userId, Intent intent, String resolvedType, IBinder resultTo, String resultWho, int requestCode, int flags, Bundle options) throws RemoteException {
        UserSpace space = getOrCreateSpaceLocked(userId);
        synchronized (space.mStack) {
            return space.mStack.startActivityLocked(userId, intent, resolvedType, resultTo, resultWho, requestCode, flags, options);
        }
    }

    @Override
    public int startActivities(int userId, Intent[] intent, String[] resolvedType, IBinder resultTo, Bundle options) throws RemoteException {
        UserSpace space = getOrCreateSpaceLocked(userId);
        synchronized (space.mStack) {
            return space.mStack.startActivitiesLocked(userId, intent, resolvedType, resultTo, options);
        }
    }

    private UserSpace getOrCreateSpaceLocked(int userId) {
        synchronized (mUserSpace) {
            UserSpace userSpace = mUserSpace.get(userId);
            if (userSpace != null)
                return userSpace;
            userSpace = new UserSpace();
            mUserSpace.put(userId, userSpace);
            return userSpace;
        }
    }

    @Override
    public void systemReady() {
        mBroadcastManager.startup();
    }
}
