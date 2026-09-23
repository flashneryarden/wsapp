package com.wsapp.taskviewer;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

public final class CriticalTaskNotifications {
    public static final String TOPIC = "critical_tasks";
    public static final String CHANNEL_ID = "critical_tasks";

    private CriticalTaskNotifications() {
    }

    public static void createChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Critical tasks",
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Urgent tasks detected from WhatsApp messages");
        channel.setLockscreenVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        channel.enableVibration(true);

        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(channel);
    }

    public static void show(Context context, String taskId, String summary, String source) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        createChannel(context);

        Intent intent = new Intent(context, TaskDetailActivity.class);
        intent.putExtra("task_document_id", taskId);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                requestCode(taskId),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Critical task received")
                .setContentText(summary)
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(summary + (source.isEmpty() ? "" : "\nFrom: " + source)))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent);

        NotificationManagerCompat.from(context).notify(requestCode(taskId), notification.build());
    }

    private static int requestCode(String taskId) {
        return taskId.hashCode() & 0x0fffffff;
    }
}
