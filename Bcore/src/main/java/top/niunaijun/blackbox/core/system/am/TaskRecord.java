package top.niunaijun.blackbox.core.system.am;

import android.content.Intent;

import java.util.LinkedList;
import java.util.List;


public class TaskRecord {
    public int id;
    public int userId;
    public String taskAffinity;
    public Intent rootIntent;
    public final List<ActivityRecord> activities = new LinkedList<>();

    public TaskRecord(int id, int userId, String taskAffinity) {
        this.id = id;
        this.userId = userId;
        this.taskAffinity = taskAffinity;
    }

    public boolean needNewTask() {
        for (ActivityRecord activity : activities) {
            boolean processAlive = activity.processRecord != null
                    && activity.processRecord.bActivityThread != null
                    && activity.processRecord.bActivityThread.asBinder().isBinderAlive();
            if (!activity.finished && processAlive) {
                return false;
            }
            if (!processAlive) {
                activity.finished = true;
            }
        }
        return true;
    }

    public void addTopActivity(ActivityRecord record) {
        activities.add(record);
    }

    public void removeActivity(ActivityRecord record) {
        activities.remove(record);
    }

    public ActivityRecord getTopActivityRecord() {
        for (int i = activities.size() - 1; i >= 0; i--) {
            ActivityRecord activityRecord = activities.get(i);
            if (!activityRecord.finished) {
                return activityRecord;
            }
        }
        return null;
    }
}
