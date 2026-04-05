package com.wsapp.taskviewer;

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
import com.wsapp.taskviewer.model.Task;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class TaskDetailActivity extends AppCompatActivity {

    private TextView statusIcon, taskTitle, statusText, senderText, dateText;
    private TextView messageLabel, messageText, summaryLabel, summaryText;
    private LinearLayout actionItemsContainer, notesContainer;
    private TextView actionItemsLabel, notesLabel;
    private TextView completedText;
    private MaterialButton btnToggleStatus, btnAddNote, btnDelete;

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
            String now = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(new Date());
            db.collection("tasks").document(docId)
                    .update("status", "done", "completedAt", now)
                    .addOnSuccessListener(v -> Toast.makeText(this, "Marked done ✅", Toast.LENGTH_SHORT).show())
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
                                Toast.makeText(this, "Task deleted", Toast.LENGTH_SHORT).show();
                                finish();
                            })
                            .addOnFailureListener(e -> Toast.makeText(this, "Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void displayTask(Task task) {
        statusIcon.setText(task.isDone() ? "✅" : "⏳");
        taskTitle.setText("Task #" + task.getId());
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

        messageText.setText(task.getText());
        summaryText.setText(task.getSummary());

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
