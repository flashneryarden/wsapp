package com.wsapp.taskviewer;

import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

public class CriticalTaskMessagingService extends FirebaseMessagingService {
    @Override
    public void onNewToken(@NonNull String token) {
        DeviceRegistration.saveToken(token);
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage message) {
        String taskIdValue = message.getData().get("taskId");
        String summary = message.getData().get("summary");
        String source = message.getData().get("source");

        if (taskIdValue == null || summary == null) return;

        try {
            int taskId = Integer.parseInt(taskIdValue);
            CriticalTaskNotifications.show(
                    this,
                    taskId,
                    summary,
                    source == null ? "" : source);
        } catch (NumberFormatException error) {
            Log.w("CriticalTaskService", "Invalid critical-task ID: " + taskIdValue, error);
        }
    }
}
