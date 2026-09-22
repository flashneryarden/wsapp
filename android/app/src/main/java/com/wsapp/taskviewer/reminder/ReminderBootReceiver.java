package com.wsapp.taskviewer.reminder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.google.firebase.firestore.FirebaseFirestore;
import com.wsapp.taskviewer.model.Task;

public final class ReminderBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            return;
        }
        PendingResult pendingResult = goAsync();
        FirebaseFirestore.getInstance()
                .collection("tasks")
                .whereEqualTo("reminderEnabled", true)
                .get()
                .addOnCompleteListener(result -> {
                    if (result.isSuccessful() && result.getResult() != null) {
                        result.getResult().forEach(document ->
                                TaskReminderManager.schedule(
                                        context,
                                        document.toObject(Task.class)));
                    }
                    pendingResult.finish();
                });
    }
}
