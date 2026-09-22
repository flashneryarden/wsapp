package com.wsapp.taskviewer.reminder;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.firestore.FirebaseFirestore;
import com.wsapp.taskviewer.TaskDetailActivity;

public final class TaskReminderReceiver extends BroadcastReceiver {
    public static final String EXTRA_TASK_ID = "task_id";
    public static final String EXTRA_SUMMARY = "summary";
    private static final String CHANNEL_ID = "task_reminders";

    @Override
    public void onReceive(Context context, Intent intent) {
        int taskId = intent.getIntExtra(EXTRA_TASK_ID, -1);
        if (taskId < 0) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        createChannel(context);
        String summary = intent.getStringExtra(EXTRA_SUMMARY);

        Intent detailIntent = new Intent(context, TaskDetailActivity.class);
        detailIntent.putExtra("task_id", taskId);
        detailIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                context,
                taskId,
                detailIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder notification =
                new NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_popup_reminder)
                        .setContentTitle("Task reminder")
                        .setContentText(summary)
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(summary))
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setCategory(NotificationCompat.CATEGORY_REMINDER)
                        .setAutoCancel(true)
                        .setContentIntent(contentIntent);
        NotificationManagerCompat.from(context).notify(100000 + taskId, notification.build());
        FirebaseFirestore.getInstance()
                .collection("tasks")
                .document(String.valueOf(taskId))
                .update("reminderEnabled", false);
    }

    private static void createChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Task reminders",
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Scheduled reminders for task due dates");
        context.getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
}
