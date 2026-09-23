package com.wsapp.taskviewer;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.MetadataChanges;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.firestore.WriteBatch;
import com.wsapp.taskviewer.model.Task;
import com.wsapp.taskviewer.reminder.TaskAlarmManager;
import com.wsapp.taskviewer.reminder.TaskAlarmStore;
import com.wsapp.taskviewer.util.DueDateFormatter;
import com.wsapp.taskviewer.util.ConnectivityLiveData;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

public class TaskDetailActivity extends AppCompatActivity {

    private TextView statusIcon, taskTitle, statusText, senderText, dateText;
    private TextView messageLabel, messageText, summaryLabel, summaryText;
    private LinearLayout actionItemsContainer, notesContainer;
    private TextView actionItemsLabel, notesLabel;
    private TextView completedText;
    private TextView dueText;
    private TextView reminderText;
    private TextView syncStatusText;
    private TextView categoryBadge;
    private MaterialButton btnToggleStatus, btnAddNote, btnDelete, btnSkipGroup;
    private MaterialButton btnSetDueDate, btnSetCategory, btnSetReminder;

    private FirebaseFirestore db;
    private ListenerRegistration listenerRegistration;
    private String taskDocumentId;
    private Task currentTask;
    private boolean openAlarmWhenLoaded;
    private boolean continueAlarmAfterDue;
    private boolean online;
    private boolean taskFromCache;
    private boolean taskHasPendingWrites;
    private String taskLoadError;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_task_detail);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        statusIcon = findViewById(R.id.detailStatusIcon);
        taskTitle = findViewById(R.id.detailTaskTitle);
        statusText = findViewById(R.id.detailStatus);
        senderText = findViewById(R.id.detailSender);
        dateText = findViewById(R.id.detailDate);
        completedText = findViewById(R.id.detailCompleted);
        dueText = findViewById(R.id.detailDue);
        reminderText = findViewById(R.id.detailReminder);
        syncStatusText = findViewById(R.id.detailSyncStatus);
        syncStatusText.setOnClickListener(view -> loadTask(taskDocumentId));
        messageLabel = findViewById(R.id.detailMessageLabel);
        messageText = findViewById(R.id.detailMessage);
        summaryLabel = findViewById(R.id.detailSummaryLabel);
        summaryText = findViewById(R.id.detailSummary);
        actionItemsContainer = findViewById(R.id.actionItemsContainer);
        actionItemsLabel = findViewById(R.id.actionItemsLabel);
        notesContainer = findViewById(R.id.notesContainer);
        notesLabel = findViewById(R.id.notesLabel);
        btnToggleStatus = findViewById(R.id.btnToggleStatus);
        btnAddNote = findViewById(R.id.btnAddNote);
        btnDelete = findViewById(R.id.btnDelete);
        btnSkipGroup = findViewById(R.id.btnSkipGroup);
        btnSetDueDate = findViewById(R.id.btnSetDueDate);
        btnSetReminder = findViewById(R.id.btnSetReminder);
        categoryBadge = findViewById(R.id.detailCategoryBadge);
        btnSetCategory = findViewById(R.id.btnSetCategory);

        db = FirebaseFirestore.getInstance();

        taskDocumentId = getIntent().getStringExtra("task_document_id");
        if (taskDocumentId == null) {
            int legacyTaskId = getIntent().getIntExtra("task_id", -1);
            if (legacyTaskId >= 0) taskDocumentId = String.valueOf(legacyTaskId);
        }
        openAlarmWhenLoaded = getIntent().getBooleanExtra("open_alarm", false);
        if (taskDocumentId == null || taskDocumentId.isEmpty()) {
            finish();
            return;
        }

        setTitle("Task");

        btnToggleStatus.setOnClickListener(v -> toggleStatus());
        btnAddNote.setOnClickListener(v -> showAddNoteDialog());
        btnDelete.setOnClickListener(v -> confirmDelete());
        btnSkipGroup.setOnClickListener(v -> showSkipGroupDialog());
        btnSetDueDate.setOnClickListener(v -> showDueDatePicker());
        btnSetReminder.setOnClickListener(v -> showAlarmOptions());
        btnSetCategory.setOnClickListener(v -> showCategoryPicker());
        ConnectivityLiveData.get(this).observe(this, isOnline -> {
            online = Boolean.TRUE.equals(isOnline);
            updateSyncStatus();
        });

        loadTask(taskDocumentId);
    }

    private void loadTask(String documentId) {
        if (listenerRegistration != null) listenerRegistration.remove();
        listenerRegistration = db.collection("tasks").document(documentId)
                .addSnapshotListener(MetadataChanges.INCLUDE, (snapshot, error) -> {
                    if (error != null) {
                        taskLoadError = error.getMessage();
                        updateSyncStatus();
                        if (currentTask == null) taskTitle.setText("Unable to load task");
                        return;
                    }
                    if (snapshot == null) return;
                    taskLoadError = null;
                    taskFromCache = snapshot.getMetadata().isFromCache();
                    taskHasPendingWrites = snapshot.getMetadata().hasPendingWrites();
                    updateSyncStatus();
                    if (!snapshot.exists()) {
                        taskTitle.setText(taskFromCache && !online
                                ? "Task is not available offline"
                                : "Task not found");
                        return;
                    }

                    currentTask = snapshot.toObject(Task.class);
                    if (currentTask != null) {
                        currentTask.setDocumentId(snapshot.getId());
                        displayTask(currentTask);
                        if (openAlarmWhenLoaded) {
                            openAlarmWhenLoaded = false;
                            showAlarmOptions();
                        }
                    }
                });
    }

    private void updateSyncStatus() {
        if (!online) {
            syncStatusText.setText(
                    "Offline — showing cached data. Changes will sync when connected.");
            syncStatusText.setBackgroundColor(0xFFFFF3E0);
            syncStatusText.setTextColor(0xFFE65100);
            syncStatusText.setVisibility(View.VISIBLE);
        } else if (taskLoadError != null) {
            syncStatusText.setText("Unable to sync — tap to retry: " + taskLoadError);
            syncStatusText.setBackgroundColor(0xFFFFEBEE);
            syncStatusText.setTextColor(0xFFC62828);
            syncStatusText.setVisibility(View.VISIBLE);
        } else if (taskHasPendingWrites) {
            syncStatusText.setText("Saving changes to Firestore…");
            syncStatusText.setBackgroundColor(0xFFE3F2FD);
            syncStatusText.setTextColor(0xFF1565C0);
            syncStatusText.setVisibility(View.VISIBLE);
        } else if (taskFromCache) {
            syncStatusText.setText("Showing cached task while connecting…");
            syncStatusText.setBackgroundColor(0xFFFFF3E0);
            syncStatusText.setTextColor(0xFFE65100);
            syncStatusText.setVisibility(View.VISIBLE);
        } else {
            syncStatusText.setVisibility(View.GONE);
        }
    }

    private void toggleStatus() {
        if (currentTask == null) return;
        String docId = taskDocumentId;

        if (currentTask.isPending()) {
            SimpleDateFormat timestamp =
                    new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            timestamp.setTimeZone(TimeZone.getTimeZone("UTC"));
            String now = timestamp.format(new Date());
            db.collection("tasks").document(docId)
                    .update(
                            "status", "done",
                            "completedAt", now)
                    .addOnSuccessListener(v -> {
                        TaskAlarmManager.cancel(this, taskDocumentId);
                        showWriteSuccess("Marked done ✅");
                    })
                    .addOnFailureListener(e -> Toast.makeText(this, "Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
        } else {
            db.collection("tasks").document(docId)
                    .update("status", "pending", "completedAt", null)
                    .addOnSuccessListener(v -> showWriteSuccess("Reopened ⏳"))
                    .addOnFailureListener(e -> Toast.makeText(this, "Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
        }
    }

    private void showAddNoteDialog() {
        EditText input = new EditText(this);
        input.setHint("Note text");
        input.setPadding(48, 32, 48, 16);

        new AlertDialog.Builder(this)
                .setTitle("Add Note")
                .setView(input)
                .setPositiveButton("Add", (dialog, which) -> {
                    String note = input.getText().toString().trim();
                    if (!note.isEmpty()) {
                        db.collection("tasks").document(taskDocumentId)
                                .update("notes", FieldValue.arrayUnion(note))
                                .addOnSuccessListener(v -> showWriteSuccess("Note added"))
                                .addOnFailureListener(e -> Toast.makeText(this, "Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle("Delete Task")
                .setMessage("Are you sure you want to delete task #"
                        + (currentTask == null ? taskDocumentId : currentTask.getDisplayId()) + "?")
                .setPositiveButton("Delete", (dialog, which) -> {
                    db.collection("tasks").document(taskDocumentId)
                            .delete()
                            .addOnSuccessListener(v -> {
                                TaskAlarmManager.cancel(this, taskDocumentId);
                                showWriteSuccess("Task deleted");
                                finish();
                            })
                            .addOnFailureListener(e -> Toast.makeText(this, "Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showSkipGroupDialog() {
        if (currentTask == null) return;
        final String group = currentTask.getOrigChatName();
        if (group == null || group.isEmpty()) {
            Toast.makeText(this, "This task has no group to skip", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Skip Group")
                .setMessage("Skip \"" + group + "\"?\n\n"
                        + "Future messages from this group will be filtered out and won't create new tasks.\n\n"
                        + "• Skip & Delete: also delete existing tasks from this group.\n"
                        + "• Skip Only: keep existing tasks.")
                .setPositiveButton("Skip & Delete", (dialog, which) -> skipGroup(group, true))
                .setNeutralButton("Skip Only", (dialog, which) -> skipGroup(group, false))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void skipGroup(String group, boolean deleteExisting) {
        db.collection("skippedGroups")
                .whereEqualTo("name", group)
                .limit(1)
                .get()
                .addOnSuccessListener(snap -> {
                    if (snap.isEmpty()) {
                        Map<String, Object> doc = new HashMap<>();
                        doc.put("name", group);
                        SimpleDateFormat fmt =
                                new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
                        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
                        doc.put("skippedAt", fmt.format(new Date()));
                        db.collection("skippedGroups").add(doc)
                                .addOnSuccessListener(ref -> afterSkip(group, deleteExisting))
                                .addOnFailureListener(e -> Toast.makeText(this,
                                        "Failed to skip group: " + e.getMessage(),
                                        Toast.LENGTH_SHORT).show());
                    } else {
                        afterSkip(group, deleteExisting);
                    }
                })
                .addOnFailureListener(e -> Toast.makeText(this,
                        "Failed to skip group: " + e.getMessage(), Toast.LENGTH_SHORT).show());
    }

    private void afterSkip(String group, boolean deleteExisting) {
        if (deleteExisting) {
            deleteTasksForGroup(group);
        } else {
            Toast.makeText(this, "Group skipped: " + group, Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void deleteTasksForGroup(String group) {
        db.collection("tasks")
                .whereEqualTo("origChatName", group)
                .get()
                .addOnSuccessListener(snap -> {
                    if (snap.isEmpty()) {
                        Toast.makeText(this, "Group skipped; no existing tasks to delete",
                                Toast.LENGTH_SHORT).show();
                        finish();
                        return;
                    }
                    WriteBatch batch = db.batch();
                    List<String> deletedTaskIds = new ArrayList<>();
                    int count = 0;
                    for (QueryDocumentSnapshot doc : snap) {
                        batch.delete(doc.getReference());
                        deletedTaskIds.add(doc.getId());
                        count++;
                    }
                    final int deleted = count;
                    batch.commit()
                            .addOnSuccessListener(v -> {
                                for (String deletedTaskId : deletedTaskIds) {
                                    TaskAlarmManager.cancel(this, deletedTaskId);
                                }
                                Toast.makeText(this,
                                        "Group skipped; deleted " + deleted + " task(s)",
                                        Toast.LENGTH_SHORT).show();
                                finish();
                            })
                            .addOnFailureListener(e -> Toast.makeText(this,
                                    "Skipped, but delete failed: " + e.getMessage(),
                                    Toast.LENGTH_SHORT).show());
                })
                .addOnFailureListener(e -> Toast.makeText(this,
                        "Skipped, but delete failed: " + e.getMessage(),
                        Toast.LENGTH_SHORT).show());
    }

    private void showDueDatePicker() {
        if (currentTask == null) return;
        Calendar cal = Calendar.getInstance();
        if (currentTask.getDueAt() != null) {
            cal.setTimeInMillis(currentTask.getDueAt());
        } else {
            String existing = currentTask.getDueDate();
            try {
                if (existing != null && !existing.trim().isEmpty()) {
                    SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                    Date parsed = format.parse(existing.trim());
                    if (parsed != null) cal.setTime(parsed);
                }
            } catch (ParseException ignored) {
            }
        }
        DatePickerDialog dialog = new DatePickerDialog(
                this,
                (view, year, month, day) -> showDueTimePicker(cal, year, month, day),
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH));
        dialog.getDatePicker().setMinDate(System.currentTimeMillis() - 1000L);
        dialog.setButton(
                DatePickerDialog.BUTTON_NEUTRAL,
                "Clear",
                (ignoredDialog, ignoredWhich) -> clearDueDateAndAlarm());
        dialog.show();
    }

    private void showDueTimePicker(Calendar calendar, int year, int month, int day) {
        new TimePickerDialog(
                this,
                (view, hour, minute) -> {
                    calendar.set(Calendar.YEAR, year);
                    calendar.set(Calendar.MONTH, month);
                    calendar.set(Calendar.DAY_OF_MONTH, day);
                    calendar.set(Calendar.HOUR_OF_DAY, hour);
                    calendar.set(Calendar.MINUTE, minute);
                    calendar.set(Calendar.SECOND, 0);
                    calendar.set(Calendar.MILLISECOND, 0);
                    saveDueDateTime(calendar.getTimeInMillis());
                },
                calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE),
                android.text.format.DateFormat.is24HourFormat(this))
                .show();
    }

    private void saveDueDateTime(long dueAt) {
        if (dueAt <= System.currentTimeMillis()) {
            Toast.makeText(this, "Choose a future due time", Toast.LENGTH_SHORT).show();
            return;
        }
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        String dueDate = dateFormat.format(new Date(dueAt));
        Map<String, Object> updates = new HashMap<>();
        updates.put("dueDate", dueDate);
        updates.put("dueAt", dueAt);

        TaskAlarmStore.AlarmConfig alarm = TaskAlarmStore.get(this, taskDocumentId);
        Long movedAlarmAt = alarm == null
                ? null
                : dueAt - alarm.offsetMinutes * 60_000L;

        db.collection("tasks").document(taskDocumentId)
                .update(updates)
                .addOnSuccessListener(value -> {
                    if (alarm != null && movedAlarmAt != null) {
                        if (movedAlarmAt > System.currentTimeMillis()) {
                            TaskAlarmManager.schedule(
                                    this,
                                    taskDocumentId,
                                    movedAlarmAt,
                                    alarm.offsetMinutes,
                                    currentTask == null ? "" : currentTask.getSummary());
                        } else {
                            TaskAlarmManager.cancel(this, taskDocumentId);
                        }
                    }
                    showWriteSuccess("Due date and time updated");
                    if (continueAlarmAfterDue) {
                        continueAlarmAfterDue = false;
                        showAlarmOffsetPicker(dueAt);
                    }
                })
                .addOnFailureListener(error -> Toast.makeText(
                        this,
                        "Failed: " + error.getMessage(),
                        Toast.LENGTH_SHORT).show());
    }

    private void clearDueDateAndAlarm() {
        Map<String, Object> updates = new HashMap<>();
        updates.put("dueDate", null);
        updates.put("dueAt", null);
        db.collection("tasks").document(taskDocumentId)
                .update(updates)
                .addOnSuccessListener(value -> {
                    TaskAlarmManager.cancel(this, taskDocumentId);
                    showWriteSuccess("Due date and alarm cleared");
                })
                .addOnFailureListener(error -> Toast.makeText(
                        this,
                        "Failed: " + error.getMessage(),
                        Toast.LENGTH_SHORT).show());
    }

    private void showAlarmOptions() {
        if (currentTask == null) return;
        if (TaskAlarmStore.get(this, taskDocumentId) == null) {
            showAlarmOffsetPicker();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Task Alarm")
                .setItems(new String[]{"Change alarm", "Remove alarm"}, (dialog, which) -> {
                    if (which == 0) showAlarmOffsetPicker();
                    else clearAlarm();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showAlarmOffsetPicker() {
        if (currentTask == null || currentTask.getDueAt() == null) {
            continueAlarmAfterDue = true;
            Toast.makeText(
                    this,
                    "Set the task due date and time first",
                    Toast.LENGTH_LONG).show();
            showDueDatePicker();
            return;
        }
        showAlarmOffsetPicker(currentTask.getDueAt());
    }

    private void showAlarmOffsetPicker(long dueAt) {
        if (!TaskAlarmManager.canSchedule(this)) {
            Toast.makeText(
                    this,
                    "Allow exact alarms, then press Set Alarm again",
                    Toast.LENGTH_LONG).show();
            TaskAlarmManager.requestExactAlarmPermission(this);
            return;
        }
        if (!TaskAlarmManager.canUseFullScreen(this)) {
            Toast.makeText(
                    this,
                    "Allow full-screen alerts, then press Set Alarm again",
                    Toast.LENGTH_LONG).show();
            TaskAlarmManager.requestFullScreenPermission(this);
            return;
        }
        String[] labels = {"5 minutes before", "1 hour before", "1 day before"};
        long[] offsets = {5L, 60L, 24L * 60L};
        new AlertDialog.Builder(this)
                .setTitle("Ring before the due time")
                .setItems(labels, (dialog, which) -> saveAlarmOffset(offsets[which], dueAt))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void saveAlarmOffset(long offsetMinutes, long dueAt) {
        if (currentTask == null) return;
        long alarmAt = dueAt - offsetMinutes * 60_000L;
        if (alarmAt <= System.currentTimeMillis()) {
            Toast.makeText(
                    this,
                    "That alarm time has already passed",
                    Toast.LENGTH_LONG).show();
            return;
        }
        String summary = currentTask.getSummary();
        if (TaskAlarmManager.schedule(
                this,
                taskDocumentId,
                alarmAt,
                offsetMinutes,
                summary)) {
            Toast.makeText(this, "Alarm scheduled on this phone", Toast.LENGTH_SHORT).show();
            displayTask(currentTask);
        } else {
            Toast.makeText(
                        this,
                        "Unable to schedule an exact alarm",
                        Toast.LENGTH_LONG).show();
        }
    }

    private void clearAlarm() {
        TaskAlarmManager.cancel(this, taskDocumentId);
        Toast.makeText(this, "Alarm removed from this phone", Toast.LENGTH_SHORT).show();
        if (currentTask != null) displayTask(currentTask);
    }

    private void showCategoryPicker() {
        if (currentTask == null) return;
        String[] keys = com.wsapp.taskviewer.util.Categories.KEYS;
        String[] labels = new String[keys.length];
        int selected = -1;
        String current = currentTask.getEffectiveCategory();
        for (int i = 0; i < keys.length; i++) {
            labels[i] = com.wsapp.taskviewer.util.Categories.label(keys[i]);
            if (keys[i].equals(current)) selected = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("Category")
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    saveCategory(keys[which]);
                    dialog.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void saveCategory(String value) {
        db.collection("tasks").document(taskDocumentId)
                .update("category", value)
                .addOnSuccessListener(v -> showWriteSuccess(
                        "Category set: " + com.wsapp.taskviewer.util.Categories.label(value)))
                .addOnFailureListener(e -> Toast.makeText(this,
                        "Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
    }

    private void displayTask(Task task) {
        statusIcon.setText(task.isDone() ? "✅" : "⏳");
        taskTitle.setText((task.isEffectivelyCritical() ? "🔴 " : "")
                + "Task #" + task.getDisplayId());
        setTitle("Task #" + task.getDisplayId());
        statusText.setText("Status: " + task.getStatus());
        senderText.setText("From: " + task.getOrigSender() + " (" + task.getOrigChatName() + ")");
        dateText.setText("Created: " + formatDate(task.getCreatedAt()));

        btnToggleStatus.setText(task.isPending() ? "Mark Done" : "Reopen");

        if (task.getCompletedAt() != null && !task.getCompletedAt().isEmpty()) {
            completedText.setText("Completed: " + formatDate(task.getCompletedAt()));
            completedText.setVisibility(View.VISIBLE);
        } else {
            completedText.setVisibility(View.GONE);
        }

        if (task.getDueAt() != null) {
            SimpleDateFormat dueFormat =
                    new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault());
            boolean overdue = task.isPending() && task.getDueAt() < System.currentTimeMillis();
            dueText.setText((overdue ? "⚠ Overdue: " : "📅 Due: ")
                    + dueFormat.format(new Date(task.getDueAt())));
        } else {
            String due = DueDateFormatter.format(task.getEffectiveDueDate(), task.getCreatedAt());
            java.time.LocalDate dueDate =
                    DueDateFormatter.resolve(task.getEffectiveDueDate(), task.getCreatedAt());
            boolean overdue = task.isPending()
                    && dueDate != null
                    && dueDate.isBefore(java.time.LocalDate.now());
            dueText.setText((due != null && !due.trim().isEmpty())
                    ? (overdue ? "⚠ Overdue: " : "📅 Due: ") + due
                    : "No due date");
        }

        TaskAlarmStore.AlarmConfig alarm = TaskAlarmStore.get(this, taskDocumentId);
        if (alarm != null) {
            SimpleDateFormat reminderFormat =
                    new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault());
            reminderText.setText("⏰ Alarm: "
                    + reminderFormat.format(new Date(alarm.alarmAt))
                    + alarmOffsetSuffix(alarm.offsetMinutes));
            btnSetReminder.setText("Change");
        } else {
            reminderText.setText("No alarm");
            btnSetReminder.setText("Set Alarm");
        }

        String category = task.getEffectiveCategory();
        categoryBadge.setText(com.wsapp.taskviewer.util.Categories.label(category));
        categoryBadge.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                com.wsapp.taskviewer.util.Categories.color(category)));

        messageText.setText(task.getText());

        String summary = task.getSummary();
        if (summary != null && !summary.trim().isEmpty()) {
            summaryText.setText(summary);
            summaryLabel.setVisibility(View.VISIBLE);
            summaryText.setVisibility(View.VISIBLE);
        } else {
            summaryLabel.setVisibility(View.GONE);
            summaryText.setVisibility(View.GONE);
        }

        actionItemsContainer.removeAllViews();
        if (task.getActionItems() != null && !task.getActionItems().isEmpty()) {
            actionItemsLabel.setVisibility(View.VISIBLE);
            actionItemsContainer.setVisibility(View.VISIBLE);
            for (String item : task.getActionItems()) {
                TextView tv = new TextView(this);
                tv.setText("  • " + item);
                tv.setTextSize(14);
                tv.setPadding(0, 4, 0, 4);
                actionItemsContainer.addView(tv);
            }
        } else {
            actionItemsLabel.setVisibility(View.GONE);
            actionItemsContainer.setVisibility(View.GONE);
        }

        notesContainer.removeAllViews();
        if (task.getNotes() != null && !task.getNotes().isEmpty()) {
            notesLabel.setVisibility(View.VISIBLE);
            notesContainer.setVisibility(View.VISIBLE);
            for (String note : task.getNotes()) {
                TextView tv = new TextView(this);
                tv.setText("  - " + note);
                tv.setTextSize(14);
                tv.setPadding(0, 4, 0, 4);
                notesContainer.addView(tv);
            }
        } else {
            notesLabel.setVisibility(View.GONE);
            notesContainer.setVisibility(View.GONE);
        }
    }

    private String alarmOffsetSuffix(Long offsetMinutes) {
        if (offsetMinutes == null) return "";
        if (offsetMinutes == 5L) return " (5 minutes before)";
        if (offsetMinutes == 60L) return " (1 hour before)";
        if (offsetMinutes == 24L * 60L) return " (1 day before)";
        return "";
    }

    private String formatDate(String iso) {
        if (iso == null) return "";
        try {
            SimpleDateFormat isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            isoFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = isoFormat.parse(iso);
            SimpleDateFormat displayFormat = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault());
            return displayFormat.format(d);
        } catch (ParseException e) {
            return iso.substring(0, Math.min(16, iso.length()));
        }
    }

    private void showWriteSuccess(String message) {
        Toast.makeText(
                this,
                online ? message : message + " locally; waiting to sync",
                Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (listenerRegistration != null) {
            listenerRegistration.remove();
        }
    }
}
