package com.wsapp.taskviewer.reminder;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class TaskAlarmStore {
    private static final String PREFERENCES = "task_alarms";
    private static final String TASK_IDS = "task_ids";

    private TaskAlarmStore() {
    }

    public static void save(
            Context context,
            String taskId,
            long alarmAt,
            long offsetMinutes,
            String summary) {
        SharedPreferences preferences = preferences(context);
        Set<String> taskIds =
                new HashSet<>(preferences.getStringSet(TASK_IDS, new HashSet<>()));
        taskIds.add(taskId);
        preferences.edit()
                .putStringSet(TASK_IDS, taskIds)
                .putLong(key("alarm_at", taskId), alarmAt)
                .putLong(key("offset", taskId), offsetMinutes)
                .putString(key("summary", taskId), summary == null ? "" : summary)
                .apply();
    }

    public static AlarmConfig get(Context context, String taskId) {
        SharedPreferences preferences = preferences(context);
        String alarmKey = key("alarm_at", taskId);
        if (!preferences.contains(alarmKey)) return null;
        return new AlarmConfig(
                taskId,
                preferences.getLong(alarmKey, 0L),
                preferences.getLong(key("offset", taskId), 0L),
                preferences.getString(key("summary", taskId), ""));
    }

    public static List<AlarmConfig> getAll(Context context) {
        SharedPreferences preferences = preferences(context);
        Set<String> taskIds = preferences.getStringSet(TASK_IDS, new HashSet<>());
        List<AlarmConfig> alarms = new ArrayList<>();
        for (String value : taskIds) {
            AlarmConfig alarm = get(context, value);
            if (alarm != null) alarms.add(alarm);
        }
        return alarms;
    }

    public static void remove(Context context, String taskId) {
        SharedPreferences preferences = preferences(context);
        Set<String> taskIds =
                new HashSet<>(preferences.getStringSet(TASK_IDS, new HashSet<>()));
        taskIds.remove(taskId);
        preferences.edit()
                .putStringSet(TASK_IDS, taskIds)
                .remove(key("alarm_at", taskId))
                .remove(key("offset", taskId))
                .remove(key("summary", taskId))
                .apply();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private static String key(String prefix, String taskId) {
        return prefix + "_" + taskId;
    }

    public static final class AlarmConfig {
        public final String taskId;
        public final long alarmAt;
        public final long offsetMinutes;
        public final String summary;

        private AlarmConfig(String taskId, long alarmAt, long offsetMinutes, String summary) {
            this.taskId = taskId;
            this.alarmAt = alarmAt;
            this.offsetMinutes = offsetMinutes;
            this.summary = summary;
        }
    }
}
