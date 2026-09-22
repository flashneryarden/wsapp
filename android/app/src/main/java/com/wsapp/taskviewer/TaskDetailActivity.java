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
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.firestore.WriteBatch;
import com.wsapp.taskviewer.model.Task;
import com.wsapp.taskviewer.reminder.TaskReminderManager;
import com.wsapp.taskviewer.util.DueDateFormatter;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
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
    private TextView categoryBadge;
    private MaterialButton btnToggleStatus, btnAddNote, btnDelete, btnSkipGroup;
    private MaterialButton btnSetDueDate, btnSetCategory, btnSetReminder;

    private FirebaseFirestore db;
    private ListenerRegistration listenerRegistration;
    private int taskId;
    private Task currentTask;

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

        taskId = getIntent().getIntExtra("task_id", -1);
        if (taskId == -1) {
            finish();
            return;
        }

        setTitle("Task #" + taskId);

        btnToggleStatus.setOnClickListener(v -> toggleStatus());
        btnAddNote.setOnClickListener(v -> showAddNoteDialog());
        btnDelete.setOnClickListener(v -> confirmDelete());
        btnSkipGroup.setOnClickListener(v -> showSkipGroupDialog());
        btnSetDueDate.setOnClickListener(v -> showDueDatePicker());
        btnSetReminder.setOnClickListener(v -> showReminderOptions());
        btnSetCategory.setOnClickListener(v -> showCategoryPicker());

        loadTask(taskId);
    }

    private void loadTask(int taskId) {
        listenerRegistration = db.collection("tasks").document(String.valueOf(taskId))
                .addSnapshotListener((snapshot, error) -> {
                    if (error != null || snapshot == null || !snapshot.exists()) {
                        taskTitle.setText("Task not found");
                        return;
                    }

                    currentTask = snapshot.toObject(Task.class);
                    if (currentTask != null) {
                        displayTask(currentTask);
                    }
                });
    }

    private void toggleStatus() {
        if (currentTask == null) return;
        String docId = String.valueOf(taskId);

        if (currentTask.isPending()) {
            SimpleDateFormat timestamp =
                    new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            timestamp.setTimeZone(TimeZone.getTimeZone("UTC"));
            String now = timestamp.format(new Date());
            db.collection("tasks").document(docId)
                    .update(
                            "status", "done",
                            "completedAt", now,
                            "reminderEnabled", false)
                    .addOnSuccessListener(v -> {
                        TaskReminderManager.cancel(this, taskId);
                        Toast.makeText(this, "Marked done ✅", Toast.LENGTH_SHORT).show();
                    })
                    .addOnFailureListener(e -> Toast.makeText(this, "Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
        } else {
            db.collection("tasks").document(docId)
                    .update("status", "pending", "completedAt", null)
                    .addOnSuccessListener(v -> Toast.makeText(this, "Reopened ⏳", Toast.LENGTH_SHORT).show())
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
                        db.collection("tasks").document(String.valueOf(taskId))
                                .update("notes", FieldValue.arrayUnion(note))
                                .addOnSuccessListener(v -> Toast.makeText(this, "Note added", Toast.LENGTH_SHORT).show())
                                .addOnFailureListener(e -> Toast.makeText(this, "Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle("Delete Task")
                .setMessage("Are you sure you want to delete task #" + taskId + "?")
                .setPositiveButton("Delete", (dialog, which) -> {
                    db.collection("tasks").document(String.valueOf(taskId))
                            .delete()
                            .addOnSuccessListener(v -> {
                                TaskReminderManager.cancel(this, taskId);
                                Toast.makeText(this, "Task deleted", Toast.LENGTH_SHORT).show();
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
                    int count = 0;
                    for (QueryDocumentSnapshot doc : snap) {
                        batch.delete(doc.getReference());
                        count++;
                    }
                    final int deleted = count;
                    batch.commit()
                            .addOnSuccessListener(v -> {
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
        String existing = currentTask.getDueDate();
        if (existing != null && !existing.trim().isEmpty()) {
            try {
                SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                Date d = f.parse(existing.trim());
                if (d != null) cal.setTime(d);
            } catch (ParseException ignored) {
            }
        }
        DatePickerDialog dlg = new DatePickerDialog(this, (view, year, month, day) -> {
            String value = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day);
            saveDueDate(value);
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH));
        dlg.setButton(DatePickerDialog.BUTTON_NEUTRAL, "Clear", (d, w) -> saveDueDate(null));
        dlg.show();
    }

    private void saveDueDate(String value) {
        db.collection("tasks").document(String.valueOf(taskId))
                .update("dueDate", value)
                .addOnSuccessListener(v -> {
                    if (value == null && currentTask != null && currentTask.hasActiveReminder()) {
                        clearReminder();
                    } else if (value != null
                            && currentTask != null
                            && currentTask.hasActiveReminder()) {
                        moveReminderToDueDate(value);
                    }
                    Toast.makeText(this,
                            value != null ? "Due date set: " + value : "Due date cleared",
                            Toast.LENGTH_SHORT).show();
                })
                .addOnFailureListener(e -> Toast.makeText(this,
                        "Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
    }

    private void moveReminderToDueDate(String dueDate) {
        try {
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            Date parsed = dateFormat.parse(dueDate);
            if (parsed == null) return;
            Calendar newReminder = Calendar.getInstance();
            newReminder.setTime(parsed);
            Calendar oldReminder = Calendar.getInstance();
            oldReminder.setTimeInMillis(currentTask.getReminderAt());
            newReminder.set(Calendar.HOUR_OF_DAY, oldReminder.get(Calendar.HOUR_OF_DAY));
            newReminder.set(Calendar.MINUTE, oldReminder.get(Calendar.MINUTE));
            newReminder.set(Calendar.SECOND, 0);
            newReminder.set(Calendar.MILLISECOND, 0);
            if (newReminder.getTimeInMillis() > System.currentTimeMillis()) {
                saveReminder(newReminder.getTimeInMillis());
            } else {
                clearReminder();
            }
        } catch (ParseException ignored) {
        }
    }

    private void showReminderOptions() {
        if (currentTask == null) return;
        if (!currentTask.hasActiveReminder()) {
            showReminderDatePicker();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Task Reminder")
                .setItems(new String[]{"Change reminder", "Remove reminder"}, (dialog, which) -> {
                    if (which == 0) showReminderDatePicker();
                    else clearReminder();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showReminderDatePicker() {
        Calendar calendar = Calendar.getInstance();
        if (currentTask != null && currentTask.getReminderAt() != null) {
            calendar.setTimeInMillis(currentTask.getReminderAt());
        } else {
            calendar.add(Calendar.HOUR_OF_DAY, 1);
        }
        DatePickerDialog datePicker = new DatePickerDialog(
                this,
                (view, year, month, dayOfMonth) -> showReminderTimePicker(
                        calendar,
                        year,
                        month,
                        dayOfMonth),
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH));
        datePicker.getDatePicker().setMinDate(System.currentTimeMillis() - 1000L);
        datePicker.show();
    }

    private void showReminderTimePicker(
            Calendar calendar,
            int year,
            int month,
            int dayOfMonth) {
        new TimePickerDialog(
                this,
                (view, hourOfDay, minute) -> {
                    calendar.set(Calendar.YEAR, year);
                    calendar.set(Calendar.MONTH, month);
                    calendar.set(Calendar.DAY_OF_MONTH, dayOfMonth);
                    calendar.set(Calendar.HOUR_OF_DAY, hourOfDay);
                    calendar.set(Calendar.MINUTE, minute);
                    calendar.set(Calendar.SECOND, 0);
                    calendar.set(Calendar.MILLISECOND, 0);
                    saveReminder(calendar.getTimeInMillis());
                },
                calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE),
                android.text.format.DateFormat.is24HourFormat(this))
                .show();
    }

    private void saveReminder(long reminderAt) {
        if (reminderAt <= System.currentTimeMillis()) {
            Toast.makeText(this, "Choose a future reminder time", Toast.LENGTH_SHORT).show();
            return;
        }
        db.collection("tasks").document(String.valueOf(taskId))
                .update("reminderAt", reminderAt, "reminderEnabled", true)
                .addOnSuccessListener(value -> {
                    String summary = currentTask == null ? "" : currentTask.getSummary();
                    TaskReminderManager.schedule(this, taskId, reminderAt, summary);
                    Toast.makeText(this, "Reminder scheduled", Toast.LENGTH_SHORT).show();
                })
                .addOnFailureListener(error -> Toast.makeText(
                        this,
                        "Failed: " + error.getMessage(),
                        Toast.LENGTH_SHORT).show());
    }

    private void clearReminder() {
        db.collection("tasks").document(String.valueOf(taskId))
                .update("reminderAt", null, "reminderEnabled", false)
                .addOnSuccessListener(value -> {
                    TaskReminderManager.cancel(this, taskId);
                    Toast.makeText(this, "Reminder removed", Toast.LENGTH_SHORT).show();
                })
                .addOnFailureListener(error -> Toast.makeText(
                        this,
                        "Failed: " + error.getMessage(),
                        Toast.LENGTH_SHORT).show());
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
        db.collection("tasks").document(String.valueOf(taskId))
                .update("category", value)
                .addOnSuccessListener(v -> Toast.makeText(this,
                        "Category set: " + com.wsapp.taskviewer.util.Categories.label(value),
                        Toast.LENGTH_SHORT).show())
                .addOnFailureListener(e -> Toast.makeText(this,
                        "Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
    }

    private void displayTask(Task task) {
        statusIcon.setText(task.isDone() ? "✅" : "⏳");
        taskTitle.setText((task.isEffectivelyCritical() ? "🔴 " : "") + "Task #" + task.getId());
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

        String due = DueDateFormatter.format(task.getEffectiveDueDate(), task.getCreatedAt());
        java.time.LocalDate dueDate =
                DueDateFormatter.resolve(task.getEffectiveDueDate(), task.getCreatedAt());
        boolean overdue = task.isPending()
                && dueDate != null
                && dueDate.isBefore(java.time.LocalDate.now());
        dueText.setText((due != null && !due.trim().isEmpty())
                ? (overdue ? "⚠ Overdue: " : "📅 Due: ") + due
                : "No due date");

        if (task.hasActiveReminder()) {
            SimpleDateFormat reminderFormat =
                    new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault());
            reminderText.setText("⏰ Reminder: " + reminderFormat.format(new Date(task.getReminderAt())));
            btnSetReminder.setText("Change");
        } else {
            reminderText.setText("No reminder");
            btnSetReminder.setText("Set Reminder");
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
