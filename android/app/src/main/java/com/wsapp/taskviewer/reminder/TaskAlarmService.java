package com.wsapp.taskviewer.reminder;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.wsapp.taskviewer.TaskDetailActivity;

public final class TaskAlarmService extends Service {
    private static final String CHANNEL_ID = "task_alarms";
    private static final String ACTION_START = "com.wsapp.taskviewer.START_TASK_ALARM";
    private static final String ACTION_STOP = "com.wsapp.taskviewer.STOP_TASK_ALARM";
    private static final String EXTRA_TASK_ID = "task_id";
    private static final String EXTRA_SUMMARY = "summary";

    private MediaPlayer player;
    private Vibrator vibrator;
    private String activeTaskId;
    private static TaskAlarmService activeService;

    public static void start(Context context, String taskId, String summary) {
        Intent intent = new Intent(context, TaskAlarmService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_TASK_ID, taskId);
        intent.putExtra(EXTRA_SUMMARY, summary == null ? "" : summary);
        ContextCompat.startForegroundService(context, intent);
    }

    public static void stop(Context context, String taskId) {
        TaskAlarmService service = activeService;
        if (service != null && (taskId == null || taskId.equals(service.activeTaskId))) {
            service.stopSelf();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        activeService = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_STOP.equals(intent.getAction())) {
            String taskId = intent.getStringExtra(EXTRA_TASK_ID);
            if (activeTaskId == null || activeTaskId.equals(taskId)) stopSelf();
            return START_NOT_STICKY;
        }

        activeTaskId = intent.getStringExtra(EXTRA_TASK_ID);
        if (activeTaskId == null || activeTaskId.isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String summary = intent.getStringExtra(EXTRA_SUMMARY);
        createChannel();
        startForeground(
                notificationId(activeTaskId),
                buildNotification(activeTaskId, summary == null ? "" : summary));
        startSoundAndVibration();
        return START_NOT_STICKY;
    }

    private NotificationCompat.Builder baseNotification(String taskId, String summary) {
        Intent alarmIntent = new Intent(this, TaskAlarmActivity.class);
        alarmIntent.putExtra(EXTRA_TASK_ID, taskId);
        alarmIntent.putExtra(EXTRA_SUMMARY, summary);
        alarmIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent fullScreenIntent = PendingIntent.getActivity(
                this,
                300000 + requestCode(taskId),
                alarmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent dismissIntent = new Intent(this, TaskAlarmService.class);
        dismissIntent.setAction(ACTION_STOP);
        dismissIntent.putExtra(EXTRA_TASK_ID, taskId);
        PendingIntent dismissPendingIntent = PendingIntent.getService(
                this,
                400000 + requestCode(taskId),
                dismissIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent taskIntent = new Intent(this, TaskDetailActivity.class);
        taskIntent.putExtra("task_document_id", taskId);
        PendingIntent taskPendingIntent = PendingIntent.getActivity(
                this,
                500000 + requestCode(taskId),
                taskIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Task alarm")
                .setContentText(summary)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(summary))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setContentIntent(taskPendingIntent)
                .setFullScreenIntent(fullScreenIntent, true)
                .addAction(0, "Dismiss", dismissPendingIntent);
    }

    private android.app.Notification buildNotification(String taskId, String summary) {
        return baseNotification(taskId, summary).build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Task alarms",
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Ringing alarms scheduled before task due times");
        channel.setSound(null, null);
        channel.enableVibration(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private void startSoundAndVibration() {
        stopSoundAndVibration();
        Uri alarmUri = android.media.RingtoneManager.getDefaultUri(
                android.media.RingtoneManager.TYPE_ALARM);
        if (alarmUri == null) alarmUri = Settings.System.DEFAULT_ALARM_ALERT_URI;
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            player.setDataSource(this, alarmUri);
            player.setLooping(true);
            player.prepare();
            player.start();
        } catch (Exception error) {
            stopSoundAndVibration();
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            vibrator = getSystemService(VibratorManager.class).getDefaultVibrator();
        } else {
            vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        }
        if (vibrator != null && vibrator.hasVibrator()) {
            long[] pattern = {0, 700, 500};
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
        }
    }

    private void stopSoundAndVibration() {
        if (player != null) {
            try {
                if (player.isPlaying()) player.stop();
            } catch (IllegalStateException ignored) {
            }
            player.release();
            player = null;
        }
        if (vibrator != null) {
            vibrator.cancel();
            vibrator = null;
        }
    }

    private static int notificationId(String taskId) {
        return 600000 + requestCode(taskId);
    }

    private static int requestCode(String taskId) {
        return taskId.hashCode() & 0x0fffffff;
    }

    @Override
    public void onDestroy() {
        stopSoundAndVibration();
        if (activeTaskId != null) {
            getSystemService(NotificationManager.class).cancel(notificationId(activeTaskId));
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        activeTaskId = null;
        activeService = null;
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
