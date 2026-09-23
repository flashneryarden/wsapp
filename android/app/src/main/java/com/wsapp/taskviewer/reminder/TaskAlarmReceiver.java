package com.wsapp.taskviewer.reminder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class TaskAlarmReceiver extends BroadcastReceiver {
    public static final String EXTRA_TASK_ID = "task_id";
    public static final String EXTRA_SUMMARY = "summary";

    @Override
    public void onReceive(Context context, Intent intent) {
        String taskId = intent.getStringExtra(EXTRA_TASK_ID);
        if (taskId == null || taskId.isEmpty()) return;
        String summary = intent.getStringExtra(EXTRA_SUMMARY);
        TaskAlarmStore.remove(context, taskId);
        TaskAlarmService.start(context, taskId, summary);
    }
}
