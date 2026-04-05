package com.wsapp.taskviewer;

import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.firestore.FirebaseFirestore;
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

    private FirebaseFirestore db;

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

        db = FirebaseFirestore.getInstance();

        int taskId = getIntent().getIntExtra("task_id", -1);
        if (taskId == -1) {
            finish();
            return;
        }

        setTitle("Task #" + taskId);
        loadTask(taskId);
    }

    private void loadTask(int taskId) {
        db.collection("tasks").document(String.valueOf(taskId))
                .addSnapshotListener((snapshot, error) -> {
                    if (error != null || snapshot == null || !snapshot.exists()) {
                        taskTitle.setText("Task not found");
                        return;
                    }

                    Task task = snapshot.toObject(Task.class);
                    if (task != null) {
                        displayTask(task);
                    }
                });
    }

    private void displayTask(Task task) {
        statusIcon.setText(task.isDone() ? "✅" : "⏳");
        taskTitle.setText("Task #" + task.getId());
        statusText.setText("Status: " + task.getStatus());
        senderText.setText("From: " + task.getOrigSender() + " (" + task.getOrigChatName() + ")");
        dateText.setText("Created: " + formatDate(task.getCreatedAt()));

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
}
