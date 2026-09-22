package com.wsapp.taskviewer.reminder;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import com.wsapp.taskviewer.model.Task;

public final class TaskReminderManager {
    private TaskReminderManager() {
    }

    public static void schedule(Context context, Task task) {
        if (!task.hasActiveReminder() || task.isDone()) {
            cancel(context, task.getId());
            return;
        }
        schedule(context, task.getId(), task.getReminderAt(), task.getSummary());
    }

    public static void schedule(Context context, int taskId, long reminderAt, String summary) {
        if (reminderAt <= System.currentTimeMillis()) {
            cancel(context, taskId);
            return;
        }
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        manager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                reminderAt,
                pendingIntent(context, taskId, summary, PendingIntent.FLAG_UPDATE_CURRENT));
    }

    public static void cancel(Context context, int taskId) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PendingIntent intent = pendingIntent(context, taskId, "", PendingIntent.FLAG_NO_CREATE);
        if (intent != null) {
            manager.cancel(intent);
            intent.cancel();
        }
    }

    private static PendingIntent pendingIntent(
            Context context,
            int taskId,
            String summary,
            int lookupFlag) {
        Intent intent = new Intent(context, TaskReminderReceiver.class);
        intent.putExtra(TaskReminderReceiver.EXTRA_TASK_ID, taskId);
        intent.putExtra(TaskReminderReceiver.EXTRA_SUMMARY, summary == null ? "" : summary);
        return PendingIntent.getBroadcast(
                context,
                taskId,
                intent,
                lookupFlag | PendingIntent.FLAG_IMMUTABLE);
    }
}
