package com.wsapp.taskviewer.reminder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class ReminderBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            return;
        }
        for (TaskAlarmStore.AlarmConfig alarm : TaskAlarmStore.getAll(context)) {
            TaskAlarmManager.restore(context, alarm);
        }
    }
}
