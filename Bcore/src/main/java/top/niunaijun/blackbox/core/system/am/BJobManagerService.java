package top.niunaijun.blackbox.core.system.am;

import android.app.job.JobScheduler;
import android.app.job.JobInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.RemoteException;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import black.android.app.job.BRJobInfo;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.system.BProcessManagerService;
import top.niunaijun.blackbox.core.system.ISystemService;
import top.niunaijun.blackbox.core.system.ProcessRecord;
import top.niunaijun.blackbox.core.system.pm.BPackageManagerService;
import top.niunaijun.blackbox.entity.JobRecord;
import top.niunaijun.blackbox.proxy.ProxyManifest;
import top.niunaijun.blackbox.proxy.ProxyJobService;


public class BJobManagerService extends IBJobManagerService.Stub implements ISystemService {
    private static final BJobManagerService sService = new BJobManagerService();
    private static final int MAX_PROXY_JOBS = 64;

    private final Map<String, JobRecord> mJobRecords = new LinkedHashMap<>();

    public static BJobManagerService get() {
        return sService;
    }

    @Override
    public JobInfo schedule(JobInfo info, int userId) throws RemoteException {
        ComponentName componentName = info.getService();
        Intent intent = new Intent();
        intent.setComponent(componentName);
        ResolveInfo resolveInfo = BPackageManagerService.get().resolveService(intent, PackageManager.GET_META_DATA, null, userId);
        if (resolveInfo == null) {
            return info;
        }
        ServiceInfo serviceInfo = resolveInfo.serviceInfo;
        ProcessRecord processRecord = BProcessManagerService.get().findProcessRecord(serviceInfo.packageName, serviceInfo.processName, userId);
        if (processRecord == null) {
            processRecord = BProcessManagerService.get().
                    startProcessLocked(serviceInfo.packageName, serviceInfo.processName, userId, -1, Binder.getCallingPid());
            if (processRecord == null) {
                throw new RuntimeException(
                        "Unable to create Process " + serviceInfo.processName);
            }
        }
        return scheduleJob(processRecord, info, serviceInfo, userId);
    }

    @Override
    public JobRecord queryJobRecord(String processName, int jobId, int userId) throws RemoteException {
        synchronized (mJobRecords) {
            return mJobRecords.get(formatKey(userId, processName, jobId));
        }
    }

    public JobInfo scheduleJob(ProcessRecord processRecord, JobInfo info, ServiceInfo serviceInfo, int userId) {
        JobRecord jobRecord = new JobRecord();
        jobRecord.mJobInfo = info;
        jobRecord.mServiceInfo = serviceInfo;

        String key = formatKey(userId, processRecord.processName, info.getId());
        synchronized (mJobRecords) {
            if (!mJobRecords.containsKey(key) && mJobRecords.size() >= MAX_PROXY_JOBS) {
                return null;
            }
            mJobRecords.put(key, jobRecord);
        }
        BRJobInfo.get(info)._set_service(new ComponentName(BlackBoxCore.getHostPkg(), ProxyManifest.getProxyJobService(processRecord.bpid)));
        return info;
    }

    @Override
    public void cancelAll(String processName, int userId) throws RemoteException {
        if (TextUtils.isEmpty(processName)) return;
        String prefix = userId + "_" + processName + "_";
        JobScheduler scheduler = getJobScheduler();
        synchronized (mJobRecords) {
            Iterator<Map.Entry<String, JobRecord>> iterator = mJobRecords.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, JobRecord> entry = iterator.next();
                if (entry.getKey().startsWith(prefix)) {
                    if (scheduler != null && entry.getValue().mJobInfo != null) {
                        scheduler.cancel(entry.getValue().mJobInfo.getId());
                    }
                    iterator.remove();
                }
            }
        }
    }

    @Override
    public int cancel(String processName, int jobId, int userId) throws RemoteException {
        synchronized (mJobRecords) {
            mJobRecords.remove(formatKey(userId, processName, jobId));
        }
        return jobId;
    }

    private String formatKey(int userId, String processName, int jobId) {
        return userId + "_" + processName + "_" + jobId;
    }

    private JobScheduler getJobScheduler() {
        return (JobScheduler) BlackBoxCore.getContext().getSystemService(Context.JOB_SCHEDULER_SERVICE);
    }

    @Override
    public void systemReady() {
        JobScheduler scheduler = getJobScheduler();
        if (scheduler == null) return;
        for (JobInfo pendingJob : new ArrayList<>(scheduler.getAllPendingJobs())) {
            ComponentName service = pendingJob.getService();
            if (service != null
                    && BlackBoxCore.getHostPkg().equals(service.getPackageName())
                    && service.getClassName().startsWith(ProxyJobService.class.getName())) {
                scheduler.cancel(pendingJob.getId());
            }
        }
        synchronized (mJobRecords) {
            mJobRecords.clear();
        }
    }
}
