package com.wsapp.taskviewer.reminder;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import com.wsapp.taskviewer.TaskDetailActivity;
public final class TaskAlarmManager {
    private TaskAlarmManager() {
    }

    public static boolean canSchedule(Context context) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms();
    }

    public static boolean canUseFullScreen(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        return manager.canUseFullScreenIntent();
    }

    public static void requestExactAlarmPermission(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        Intent intent = new Intent(
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:" + activity.getPackageName()));
        activity.startActivity(intent);
    }

    public static void requestFullScreenPermission(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return;
        Intent intent = new Intent(
                Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                Uri.parse("package:" + activity.getPackageName()));
        activity.startActivity(intent);
    }

    public static boolean schedule(
            Context context,
            String taskId,
            long alarmAt,
            long offsetMinutes,
            String summary) {
        if (alarmAt <= System.currentTimeMillis() || !canSchedule(context)) {
            cancel(context, taskId);
            return false;
        }
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PendingIntent operation =
                alarmPendingIntent(context, taskId, summary, PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent showIntent = taskPendingIntent(context, taskId);
        manager.setAlarmClock(new AlarmManager.AlarmClockInfo(alarmAt, showIntent), operation);
        TaskAlarmStore.save(context, taskId, alarmAt, offsetMinutes, summary);
        return true;
    }

    public static void restore(Context context, TaskAlarmStore.AlarmConfig alarm) {
        if (alarm.alarmAt <= System.currentTimeMillis()) {
            cancel(context, alarm.taskId);
            return;
        }
        schedule(
                context,
                alarm.taskId,
                alarm.alarmAt,
                alarm.offsetMinutes,
                alarm.summary);
    }

    public static void cancel(Context context, String taskId) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PendingIntent intent =
                alarmPendingIntent(context, taskId, "", PendingIntent.FLAG_NO_CREATE);
        if (intent != null) {
            manager.cancel(intent);
            intent.cancel();
        }
        TaskAlarmStore.remove(context, taskId);
        TaskAlarmService.stop(context, taskId);
    }

    private static PendingIntent alarmPendingIntent(
            Context context,
            String taskId,
            String summary,
            int lookupFlag) {
        Intent intent = new Intent(context, TaskAlarmReceiver.class);
        intent.putExtra(TaskAlarmReceiver.EXTRA_TASK_ID, taskId);
        intent.putExtra(TaskAlarmReceiver.EXTRA_SUMMARY, summary == null ? "" : summary);
        return PendingIntent.getBroadcast(
                context,
                requestCode(taskId),
                intent,
                lookupFlag | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent taskPendingIntent(Context context, String taskId) {
        Intent intent = new Intent(context, TaskDetailActivity.class);
        intent.putExtra("task_document_id", taskId);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(
                context,
                200000 + requestCode(taskId),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static int requestCode(String taskId) {
        return taskId.hashCode() & 0x0fffffff;
    }
}
