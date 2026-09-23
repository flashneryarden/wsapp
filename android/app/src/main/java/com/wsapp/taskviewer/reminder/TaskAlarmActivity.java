package com.wsapp.taskviewer.reminder;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.OnBackPressedCallback;

import com.google.android.material.button.MaterialButton;
import com.wsapp.taskviewer.R;
import com.wsapp.taskviewer.TaskDetailActivity;

public final class TaskAlarmActivity extends AppCompatActivity {
    private String taskId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_task_alarm);

        taskId = getIntent().getStringExtra("task_id");
        String summary = getIntent().getStringExtra("summary");
        TextView summaryView = findViewById(R.id.alarmSummary);
        summaryView.setText(summary == null || summary.isEmpty() ? "Task is due soon" : summary);
        MaterialButton dismissButton = findViewById(R.id.btnDismissAlarm);
        dismissButton.setOnClickListener(view -> dismissAlarm());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                dismissAlarm();
            }
        });
    }

    private void dismissAlarm() {
        TaskAlarmService.stop(this, taskId);
        if (taskId != null) {
            Intent intent = new Intent(this, TaskDetailActivity.class);
            intent.putExtra("task_document_id", taskId);
            startActivity(intent);
        }
        finish();
    }
}
