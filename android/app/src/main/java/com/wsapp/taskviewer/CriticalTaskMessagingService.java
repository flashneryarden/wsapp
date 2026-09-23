package com.wsapp.taskviewer;

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
        String taskDocumentId = message.getData().get("taskDocumentId");
        if (taskDocumentId == null) taskDocumentId = message.getData().get("taskId");
        String summary = message.getData().get("summary");
        String source = message.getData().get("source");

        if (taskDocumentId == null || summary == null) return;
        CriticalTaskNotifications.show(
                this,
                taskDocumentId,
                summary,
                source == null ? "" : source);
    }
}
