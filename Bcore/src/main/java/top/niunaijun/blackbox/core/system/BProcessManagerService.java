package top.niunaijun.blackbox.core.system;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.IBActivityThread;
import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.core.system.notification.BNotificationManagerService;
import top.niunaijun.blackbox.core.system.pm.BPackageManagerService;
import top.niunaijun.blackbox.core.system.user.BUserHandle;
import top.niunaijun.blackbox.entity.AppConfig;
import top.niunaijun.blackbox.proxy.ProxyManifest;
import top.niunaijun.blackbox.utils.FileUtils;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.ApplicationThreadCompat;
import top.niunaijun.blackbox.utils.compat.BundleCompat;
import top.niunaijun.blackbox.utils.provider.ProviderCall;


public class BProcessManagerService implements ISystemService {
    public static final String TAG = "BProcessManager";

    public static BProcessManagerService sBProcessManagerService = new BProcessManagerService();
    private final Map<Integer, Map<String, ProcessRecord>> mProcessMap = new HashMap<>();
    private final List<ProcessRecord> mPidsSelfLocked = new ArrayList<>();
    private final Object mProcessLock = new Object();
    /** Longest the server will wait for a guest to register before giving up. */
    private static final long PROCESS_INIT_TIMEOUT_MS = 10_000L;

    public static BProcessManagerService get() {
        return sBProcessManagerService;
    }

    public ProcessRecord startProcessLocked(String packageName, String processName, int userId, int bpid, int callingPid) {
        ApplicationInfo info = BPackageManagerService.get().getApplicationInfo(packageName, 0, userId);
        if (info == null)
            return null;
        ProcessRecord app;
        int buid = BUserHandle.getUid(userId, BPackageManagerService.get().getAppId(packageName));
        synchronized (mProcessLock) {
            Map<String, ProcessRecord> bProcess = mProcessMap.get(buid);

            if (bProcess == null) {
                bProcess = new HashMap<>();
            }
            if (bpid == -1) {
                app = bProcess.get(processName);
                if (app != null) {
                    if (app.initLock != null) {
                        // Bounded wait. An unbounded block() parks this thread
                        // forever whenever a guest dies before it registers, and
                        // since this runs in the :black server that shows up as
                        // "Killing com.dualspace.livre:black (adj 905): bg anr".
                        // Losing the server is what strands the running guests
                        // and lets the next launch put a second process on the
                        // same data directory.
                        if (!app.initLock.block(PROCESS_INIT_TIMEOUT_MS)) {
                            Slog.w(TAG, "guest " + processName + " (user " + userId
                                    + ") did not finish init in " + PROCESS_INIT_TIMEOUT_MS
                                    + "ms, treating the record as dead");
                        }
                    }
                    if (app.bActivityThread != null) {
                        return app;
                    }
                    // Init has finished and the guest still never registered, so
                    // this record is dead — but the OS process behind it may not
                    // be. Taking a fresh slot without retiring it leaves two live
                    // processes on one guest data directory, because
                    // getUsingBPidL() deliberately hands out a slot nobody is
                    // using. For an app that keeps a login that is fatal: two
                    // Instagram instances share blackbox/data/user/<id>, both run
                    // their session manager over the same auth files, and each
                    // token refresh invalidates the other one's session, which is
                    // what logs the account out over and over. The physical
                    // Instagram never does this because it only ever runs once.
                    retireStaleProcessLocked(bProcess, app);
                }
                // A previous server run may still have this guest alive in a
                // slot the in-memory records above cannot see. Take that slot
                // over instead of starting a second process beside it — and, as
                // importantly, instead of killing it: this path runs for every
                // service, provider and broadcast the guest starts, so killing
                // here shut down whatever the user had on screen and dropped
                // them back at the launcher.
                bpid = adoptStrandedSlotForGuest(userId, processName);
                if (bpid == -1) {
                    bpid = getUsingBPidL();
                }
                Slog.d(TAG, "init bUid = " + buid + ", bPid = " + bpid);
            }
            if (bpid == -1) {
                throw new RuntimeException("No processes available");
            }
            app = new ProcessRecord(info, processName);
            app.uid = Process.myUid();
            app.bpid = bpid;
            app.buid = BPackageManagerService.get().getAppId(packageName);
            app.callingBUid = getBUidByPidOrPackageName(callingPid, packageName);
            app.userId = userId;

            bProcess.put(processName, app);
            mPidsSelfLocked.add(app);
            // Belt and braces: whatever route got us here — including
            // restartAppProcess(), which passes a concrete bpid and so skips the
            // check above entirely — no other live process may be left holding
            // this guest's data directory.
            retireDuplicatesLocked(userId, app.buid, processName, app);

            synchronized (mProcessMap) {
                mProcessMap.put(buid, bProcess);
            }
            if (!initAppProcessL(app)) {
                
                bProcess.remove(processName);
                mPidsSelfLocked.remove(app);
                app = null;
            } else {
                app.pid = getPid(BlackBoxCore.getContext(), ProxyManifest.getProcessName(app.bpid));
            }
        }
        return app;
    }

    /**
     * Kills the OS process behind a dead {@link ProcessRecord} and forgets it,
     * so a replacement never ends up running alongside it.
     *
     * The recorded pid can be stale or was never filled in, so the slot is
     * resolved again from its proxy process name before killing.
     */
    private void retireStaleProcessLocked(Map<String, ProcessRecord> bProcess, ProcessRecord stale) {
        try {
            int livePid = getPid(BlackBoxCore.getContext(), ProxyManifest.getProcessName(stale.bpid));
            if (livePid > 0) {
                stale.pid = livePid;
            }
            Slog.d(TAG, "retiring stale process " + stale.processName
                    + " (user " + stale.userId + ", bPid " + stale.bpid + ", pid " + stale.pid + ")");
            stale.kill();
        } catch (Throwable error) {
            Slog.w(TAG, "Unable to retire the stale process " + stale.processName, error);
        }
        bProcess.remove(stale.processName);
        synchronized (mPidsSelfLocked) {
            mPidsSelfLocked.remove(stale);
        }
    }

    /**
     * Enforces one live process per (space, process name).
     *
     * The per-name map only ever holds the newest record, so an older one that
     * was overwritten becomes invisible there while its OS process keeps
     * running — two guests on one data directory, which is what was logging
     * Instagram accounts out. {@code mPidsSelfLocked} still remembers those
     * records, so it is the list to sweep.
     */
    private void retireDuplicatesLocked(int userId, int appId, String processName, ProcessRecord keep) {
        List<ProcessRecord> duplicates = new ArrayList<>();
        synchronized (mPidsSelfLocked) {
            for (ProcessRecord record : mPidsSelfLocked) {
                if (record == keep || record.bpid == keep.bpid) {
                    continue;
                }
                // Match on (userId, appId), not on the composite buid: the local
                // `buid` here is BUserHandle.getUid(userId, appId) — 510001 for
                // space 5 — while ProcessRecord.buid only ever stores the bare
                // app id (10001). Comparing the two never matches, which is why
                // an earlier version of this sweep silently did nothing.
                if (record.userId != userId || record.buid != appId) {
                    continue;
                }
                if (processName != null && processName.equals(record.processName)) {
                    duplicates.add(record);
                }
            }
        }
        for (ProcessRecord duplicate : duplicates) {
            Slog.w(TAG, "killing duplicate guest process " + processName
                    + " (user " + duplicate.userId + ", bPid " + duplicate.bpid
                    + ") — it shares a data directory with bPid " + keep.bpid);
            try {
                int livePid = getPid(BlackBoxCore.getContext(), ProxyManifest.getProcessName(duplicate.bpid));
                if (livePid > 0) {
                    duplicate.pid = livePid;
                }
                duplicate.kill();
            } catch (Throwable error) {
                Slog.w(TAG, "Unable to kill the duplicate process " + processName, error);
            }
            synchronized (mPidsSelfLocked) {
                mPidsSelfLocked.remove(duplicate);
            }
        }
    }

    private int getUsingBPidL() {
        ActivityManager manager = (ActivityManager) BlackBoxCore.getContext().getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> runningAppProcesses = manager.getRunningAppProcesses();
        Set<Integer> usingPs = new HashSet<>();
        for (ActivityManager.RunningAppProcessInfo runningAppProcess : runningAppProcesses) {
            int i = parseBPid(runningAppProcess.processName);
            usingPs.add(i);
        }
        for (int i = 0; i < ProxyManifest.FREE_COUNT; i++) {
            if (usingPs.contains(i)) {
                continue;
            }
            return i;
        }
        return -1;
    }

    public void restartAppProcess(String packageName, String processName, int userId) {
        synchronized (mProcessLock) {
            int callingUid = Binder.getCallingUid();
            int callingPid = Binder.getCallingPid();
            ProcessRecord app = findProcessByPid(callingPid);;
            if (app == null) {
                String stubProcessName = getProcessName(BlackBoxCore.getContext(), callingPid);
                int bpid = parseBPid(stubProcessName);
                startProcessLocked(packageName, processName, userId, bpid, callingPid);
            }
        }
    }

    private int parseBPid(String stubProcessName) {
        String prefix;
        if (stubProcessName == null) {
            return -1;
        } else {
            prefix = BlackBoxCore.getHostPkg() + ":p";
        }
        if (stubProcessName.startsWith(prefix)) {
            try {
                return Integer.parseInt(stubProcessName.substring(prefix.length()));
            } catch (NumberFormatException e) {
                
            }
        }
        return -1;
    }

    private boolean initAppProcessL(ProcessRecord record) {
        Log.d(TAG, "initProcess: " + record.processName);
        AppConfig appConfig = record.getClientConfig();
        Bundle bundle = new Bundle();
        bundle.putParcelable(AppConfig.KEY, appConfig);
        Bundle init = ProviderCall.callSafely(record.getProviderAuthority(), "_Black_|_init_process_", null, bundle);
        IBinder appThread = BundleCompat.getBinder(init, "_Black_|_client_");
        if (appThread == null || !appThread.isBinderAlive()) {
            return false;
        }
        attachClientL(record, appThread);

        createProc(record);
        return true;
    }

    private void attachClientL(final ProcessRecord app, final IBinder appThread) {
        IBActivityThread activityThread = IBActivityThread.Stub.asInterface(appThread);
        if (activityThread == null) {
            app.kill();
            return;
        }
        try {
            appThread.linkToDeath(new IBinder.DeathRecipient() {
                @Override
                public void binderDied() {
                    Log.d(TAG, "App Died: " + app.processName);
                    appThread.unlinkToDeath(this, 0);
                    onProcessDie(app);
                }
            }, 0);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        app.bActivityThread = activityThread;
        try {
            app.appThread = ApplicationThreadCompat.asInterface(activityThread.getActivityThread());
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        app.initLock.open();
    }

    public void onProcessDie(ProcessRecord record) {
        synchronized (mProcessLock) {
            record.kill();
            Map<String, ProcessRecord> process = mProcessMap.get(record.buid);
            if (process != null) {
                process.remove(record.processName);
                if (process.isEmpty()) {
                    mProcessMap.remove(record.buid);
                }
            }
            mPidsSelfLocked.remove(record);

            removeProc(record);
            BNotificationManagerService.get().deletePackageNotification(record.getPackageName(), record.userId);
        }
    }

    public ProcessRecord findProcessRecord(String packageName, String processName, int userId) {
        synchronized (mProcessMap) {
            int appId = BPackageManagerService.get().getAppId(packageName);
            int buid = BUserHandle.getUid(userId, appId);
            Map<String, ProcessRecord> processRecordMap = mProcessMap.get(buid);
            if (processRecordMap == null)
                return null;
            return processRecordMap.get(processName);
        }
    }

    public void killAllByPackageName(String packageName) {
        synchronized (mProcessLock) {
            synchronized (mPidsSelfLocked) {
                List<ProcessRecord> tmp = new ArrayList<>(mPidsSelfLocked);
                int appId = BPackageManagerService.get().getAppId(packageName);
                for (ProcessRecord processRecord : mPidsSelfLocked) {
                    int appId1 = BUserHandle.getAppId(processRecord.buid);
                    if (appId == appId1) {
                        mProcessMap.remove(processRecord.buid);
                        tmp.remove(processRecord);
                        processRecord.kill();
                    }
                }
                mPidsSelfLocked.clear();
                mPidsSelfLocked.addAll(tmp);
            }
        }
    }

    public void killPackageAsUser(String packageName, int userId) {
        synchronized (mProcessLock) {
            int buid = BUserHandle.getUid(userId, BPackageManagerService.get().getAppId(packageName));
            Map<String, ProcessRecord> process = mProcessMap.get(buid);
            if (process == null)
                return;
            for (ProcessRecord value : process.values()) {
                value.kill();
                mPidsSelfLocked.remove(value);
            }
            mProcessMap.remove(buid);
        }
    }

    public void killAllByUserId(int userId) {
        synchronized (mProcessLock) {
            synchronized (mPidsSelfLocked) {
                List<ProcessRecord> records = new ArrayList<>(mPidsSelfLocked);
                for (ProcessRecord record : records) {
                    if (record.userId != userId) {
                        continue;
                    }
                    // mProcessMap is keyed by BUserHandle.getUid(userId, appId),
                    // but record.buid holds the bare app id, so looking the map
                    // up with it always missed and the entry survived the kill.
                    // The dead record then stayed visible to startProcessLocked,
                    // which is one way a space ends up with a second process.
                    Map<String, ProcessRecord> userProcesses =
                            mProcessMap.get(BUserHandle.getUid(record.userId, record.buid));
                    if (userProcesses != null) {
                        userProcesses.remove(record.processName);
                        if (userProcesses.isEmpty()) {
                            mProcessMap.remove(BUserHandle.getUid(record.userId, record.buid));
                        }
                    }
                    mPidsSelfLocked.remove(record);
                    record.kill();
                }
            }
        }
    }

    public List<ProcessRecord> getPackageProcessAsUser(String packageName, int userId) {
        synchronized (mProcessMap) {
            int buid = BUserHandle.getUid(userId, BPackageManagerService.get().getAppId(packageName));
            Map<String, ProcessRecord> process = mProcessMap.get(buid);
            if (process == null)
                return new ArrayList<>();
            return new ArrayList<>(process.values());
        }
    }

    public int getBUidByPidOrPackageName(int pid, String packageName) {
        ProcessRecord callingProcess = findProcessByPid(pid);
        if (callingProcess == null) {
            return BPackageManagerService.get().getAppId(packageName);
        }
        return BUserHandle.getAppId(callingProcess.buid);
    }

    public int getUserIdByCallingPid(int callingPid) {
        ProcessRecord callingProcess = findProcessByPid(callingPid);
        if (callingProcess == null) {
            return 0;
        }
        return callingProcess.userId;
    }

    public ProcessRecord findProcessByPid(int pid) {
        synchronized (mPidsSelfLocked) {
            for (ProcessRecord processRecord : mPidsSelfLocked) {
                if (processRecord.pid == pid)
                    return processRecord;
            }
            return null;
        }
    }

    private static String getProcessName(Context context, int pid) {
        String processName = null;
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningAppProcessInfo info : am.getRunningAppProcesses()) {
            if (info.pid == pid) {
                processName = info.processName;
                break;
            }
        }
        if (processName == null) {
            throw new RuntimeException("processName = null");
        }
        return processName;
    }

    public static int getPid(Context context, String processName) {
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> runningAppProcesses = manager.getRunningAppProcesses();
            for (ActivityManager.RunningAppProcessInfo runningAppProcess : runningAppProcesses) {
                if (runningAppProcess.processName.equals(processName)) {
                    return runningAppProcess.pid;
                }
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return -1;
    }

    /** Identifies which space a slot is serving, next to the existing cmdline. */
    private static final String OWNER_FILE = "owner";

    private static void createProc(ProcessRecord record) {
        File cmdline = new File(BEnvironment.getProcDir(record.bpid), "cmdline");
        try {
            FileUtils.writeToFile(record.processName.getBytes(), cmdline);
        } catch (IOException ignored) {
        }
        // The in-memory maps die with the :black server; this does not. It is
        // what lets a later server run tell which space a still-running slot
        // belongs to, instead of starting a second process beside it.
        File owner = new File(BEnvironment.getProcDir(record.bpid), OWNER_FILE);
        try {
            FileUtils.writeToFile(ownerTag(record.userId, record.processName).getBytes(), owner);
        } catch (IOException ignored) {
        }
    }

    private static String ownerTag(int userId, String processName) {
        return userId + ":" + processName;
    }

    private static String readOwnerTag(File slotDir) {
        File owner = new File(slotDir, OWNER_FILE);
        if (!owner.isFile()) {
            return null;
        }
        try {
            byte[] raw = FileUtils.toByteArray(owner);
            if (raw == null || raw.length == 0) {
                return null;
            }
            return new String(raw).trim();
        } catch (Throwable error) {
            return null;
        }
    }

    /**
     * Kills a still-running slot that already serves this guest before a new one
     * is started beside it.
     *
     * After the server is restarted the in-memory records are gone, so
     * {@link #getUsingBPidL()} correctly reports the stranded slot as occupied
     * and hands out a *different* one — which is precisely how a space ends up
     * with two processes on one data directory. The on-disk owner tag survives
     * the restart, so the stranded slot can still be recognised here, at the one
     * moment when killing it is unambiguously right: the user is opening that
     * guest again, so it is about to be replaced anyway.
     */
    private int adoptStrandedSlotForGuest(int userId, String processName) {
        File[] slots = BEnvironment.getProcDir().listFiles();
        if (slots == null) {
            return -1;
        }
        String wanted = ownerTag(userId, processName);
        for (File slot : slots) {
            if (!slot.isDirectory() || !wanted.equals(readOwnerTag(slot))) {
                continue;
            }
            int bpid;
            try {
                bpid = Integer.parseInt(slot.getName());
            } catch (NumberFormatException error) {
                FileUtils.deleteDir(slot);
                continue;
            }
            try {
                int pid = getPid(BlackBoxCore.getContext(), ProxyManifest.getProcessName(bpid));
                if (pid > 0 && pid != Process.myPid()) {
                    Slog.d(TAG, "adopting stranded guest " + processName + " (user " + userId
                            + ") already on bPid " + bpid + ", pid " + pid);
                    return bpid;
                }
            } catch (Throwable error) {
                Slog.w(TAG, "Unable to inspect stranded slot " + bpid, error);
            }
            // The slot's process is gone; the entry is stale.
            FileUtils.deleteDir(slot);
        }
        return -1;
    }

    private static void removeProc(ProcessRecord record) {
        FileUtils.deleteDir(BEnvironment.getProcDir(record.bpid));
    }

    @Override
    public void systemReady() {
        // The proc dir used to be wiped wholesale here, which threw away the one
        // record of who owns a still-running slot exactly when it was needed.
        // Entries whose process is gone are stale and go; the rest are kept so
        // retireStrandedSlotsForGuest can act on them later.
        pruneDeadProcEntries();
        killOrphanedGuestProcesses();
    }

    private void pruneDeadProcEntries() {
        File[] slots = BEnvironment.getProcDir().listFiles();
        if (slots == null) {
            return;
        }
        for (File slot : slots) {
            if (!slot.isDirectory()) {
                continue;
            }
            int bpid;
            try {
                bpid = Integer.parseInt(slot.getName());
            } catch (NumberFormatException error) {
                FileUtils.deleteDir(slot);
                continue;
            }
            try {
                if (getPid(BlackBoxCore.getContext(), ProxyManifest.getProcessName(bpid)) <= 0) {
                    FileUtils.deleteDir(slot);
                }
            } catch (Throwable error) {
                FileUtils.deleteDir(slot);
            }
        }
    }

    /**
     * Kills guest processes left behind by a previous run of this server.
     *
     * {@code mProcessMap} and {@code mPidsSelfLocked} live only in memory, in
     * the {@code :black} process. Guests run in their own {@code :pN} processes,
     * so when the server is restarted — memory pressure on a 3.7 GB phone will
     * do it — the bookkeeping is wiped while every guest keeps running. The
     * server then believes nothing is started, {@link #getUsingBPidL()} hands out
     * slots it can see are free, and the space ends up with a second live
     * process on the same {@code blackbox/data/user/<id>} directory. Two
     * Instagram instances over one set of auth files invalidate each other's
     * session, which is what drops the account "toda hora".
     *
     * Observed directly: reopening the launcher took two guests to four, two per
     * space. The in-memory sweep in {@link #retireDuplicatesLocked} cannot catch
     * this, because the list it walks was wiped along with everything else — the
     * orphans are invisible to it. The running process list is not.
     *
     * At this point the server has no records at all, so any live proxy process
     * is by definition unmanaged: nothing can talk to it, and leaving it running
     * only corrupts the space it still holds open.
     */
    private void killOrphanedGuestProcesses() {
        try {
            ActivityManager manager = (ActivityManager) BlackBoxCore.getContext()
                    .getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> running = manager.getRunningAppProcesses();
            if (running == null) {
                return;
            }
            for (ActivityManager.RunningAppProcessInfo info : running) {
                if (info == null || parseBPid(info.processName) == -1) {
                    continue;
                }
                if (info.pid == Process.myPid() || info.pid <= 0) {
                    continue;
                }
                // Never kill what the user is looking at. The server can be
                // restarted while a guest is on screen, and killing it there
                // closes the app back to the launcher mid-use. A visible orphan
                // is left alone; retireDuplicatesLocked still removes it once it
                // stops being the foreground app and something takes its slot.
                if (info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) {
                    Slog.d(TAG, "leaving foreground guest process " + info.processName
                            + " (pid " + info.pid + ") alone");
                    continue;
                }
                // A slot that still carries its owner tag can be adopted the
                // next time that guest is started, so there is no reason to
                // kill it — only slots from before the tag existed, which
                // nothing can identify, are swept here.
                int slot = parseBPid(info.processName);
                if (readOwnerTag(BEnvironment.getProcDir(slot)) != null) {
                    continue;
                }
                Slog.w(TAG, "killing orphaned guest process " + info.processName
                        + " (pid " + info.pid + ") left over from a previous server run");
                try {
                    Process.killProcess(info.pid);
                } catch (Throwable error) {
                    Slog.w(TAG, "Unable to kill orphaned process " + info.processName, error);
                }
            }
        } catch (Throwable error) {
            Slog.w(TAG, "Unable to sweep orphaned guest processes", error);
        }
    }
}
