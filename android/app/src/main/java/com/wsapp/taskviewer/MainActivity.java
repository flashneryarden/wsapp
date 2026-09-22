package com.wsapp.taskviewer;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.firestore.WriteBatch;
import com.wsapp.taskviewer.logic.TaskFilterSortEngine;
import com.wsapp.taskviewer.model.Task;
import com.wsapp.taskviewer.reminder.TaskReminderManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.TreeSet;

public class MainActivity extends AppCompatActivity {
    private static final int[] AGE_PRESETS = {30, 60, 90};

    private TaskViewModel viewModel;
    private TextView filterBanner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setTitle("Tasks");

        viewModel = new ViewModelProvider(this).get(TaskViewModel.class);
        filterBanner = findViewById(R.id.filterBanner);
        filterBanner.setOnClickListener(view -> {
            viewModel.clearFilters();
            Toast.makeText(this, "Filters cleared", Toast.LENGTH_SHORT).show();
        });
        viewModel.getFilterVersion().observe(this, ignored -> updateFilterBanner());

        ViewPager2 pager = findViewById(R.id.taskPager);
        pager.setAdapter(new TaskPagerAdapter(this));
        TabLayout tabs = findViewById(R.id.taskTabs);
        new TabLayoutMediator(tabs, pager, (tab, position) -> {
            if (position == 0) tab.setText("Urgent");
            else if (position == 1) tab.setText("Open");
            else tab.setText("Completed");
        }).attach();

        FloatingActionButton addButton = findViewById(R.id.fabAdd);
        addButton.setOnClickListener(view -> showAddTaskDialog());

        CriticalTaskNotifications.createChannel(this);
        DeviceRegistration.registerCurrentToken();
        requestNotificationPermission();
        openTaskFromNotification(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        openTaskFromNotification(intent);
    }

    private void openTaskFromNotification(Intent intent) {
        if (intent == null) return;
        String taskIdValue = intent.getStringExtra("taskId");
        if (taskIdValue == null) return;
        intent.removeExtra("taskId");
        try {
            Intent detailIntent = new Intent(this, TaskDetailActivity.class);
            detailIntent.putExtra("task_id", Integer.parseInt(taskIdValue));
            startActivity(detailIntent);
        } catch (NumberFormatException ignored) {
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    1001);
        }
    }

    private void showAddTaskDialog() {
        EditText input = new EditText(this);
        input.setHint("Task description");
        input.setPadding(48, 32, 48, 16);
        new AlertDialog.Builder(this)
                .setTitle("New Task")
                .setView(input)
                .setPositiveButton("Add", (dialog, which) -> {
                    String text = input.getText().toString().trim();
                    if (text.isEmpty()) return;
                    viewModel.getRepository().createTask(
                            text,
                            () -> Toast.makeText(this, "Task created", Toast.LENGTH_SHORT).show(),
                            error -> Toast.makeText(
                                    this,
                                    "Failed: " + error.getMessage(),
                                    Toast.LENGTH_SHORT).show());
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.pill_box) {
            startActivity(new Intent(this, PillBoxActivity.class));
            return true;
        }
        if (id == R.id.sort_critical) {
            item.setChecked(true);
            viewModel.setSortMode(TaskFilterSortEngine.SortMode.CRITICAL_FIRST);
        } else if (id == R.id.sort_due) {
            item.setChecked(true);
            viewModel.setSortMode(TaskFilterSortEngine.SortMode.DUE_DATE);
        } else if (id == R.id.sort_newest) {
            item.setChecked(true);
            viewModel.setSortMode(TaskFilterSortEngine.SortMode.NEWEST);
        } else if (id == R.id.sort_by_group) {
            item.setChecked(true);
            viewModel.setSortMode(TaskFilterSortEngine.SortMode.GROUP);
        } else if (id == R.id.sort_by_sender) {
            item.setChecked(true);
            viewModel.setSortMode(TaskFilterSortEngine.SortMode.SENDER);
        } else if (id == R.id.sort_by_category) {
            item.setChecked(true);
            viewModel.setSortMode(TaskFilterSortEngine.SortMode.CATEGORY);
        } else if (id == R.id.filter_by_group) {
            showValueFilterDialog(true);
            return true;
        } else if (id == R.id.filter_by_sender) {
            showValueFilterDialog(false);
            return true;
        } else if (id == R.id.filter_by_category) {
            showCategoryFilterDialog();
            return true;
        } else if (id == R.id.clear_filters) {
            viewModel.clearFilters();
        } else if (id == R.id.delete_old) {
            showDeleteOldTasksDialog();
            return true;
        } else if (id == R.id.delete_all) {
            confirmDeleteAll();
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
        return true;
    }

    private void updateFilterBanner() {
        if (!viewModel.hasFilters()) {
            filterBanner.setVisibility(View.GONE);
            return;
        }
        filterBanner.setText("Filtered · " + viewModel.describeFilters() + " — tap to clear");
        filterBanner.setVisibility(View.VISIBLE);
    }

    private void showCategoryFilterDialog() {
        String[] keys = com.wsapp.taskviewer.util.Categories.KEYS;
        List<String> values = new ArrayList<>();
        values.add(null);
        String[] labels = new String[keys.length + 1];
        labels[0] = "All Categories";
        for (int i = 0; i < keys.length; i++) {
            values.add(keys[i]);
            labels[i + 1] = com.wsapp.taskviewer.util.Categories.label(keys[i]);
        }
        new AlertDialog.Builder(this)
                .setTitle("Filter by Category")
                .setItems(labels, (dialog, which) -> viewModel.setCategoryFilter(values.get(which)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showValueFilterDialog(boolean byGroup) {
        TreeSet<String> values = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        List<Task> tasks = viewModel.getTasks().getValue();
        if (tasks != null) {
            for (Task task : tasks) {
                String value = byGroup ? task.getOrigChatName() : task.getOrigSender();
                if (value != null && !value.trim().isEmpty()) values.add(value.trim());
            }
        }
        if (values.isEmpty()) {
            Toast.makeText(
                    this,
                    "No " + (byGroup ? "groups" : "senders") + " available",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        List<String> options = new ArrayList<>();
        options.add(byGroup ? "All Groups" : "All Senders");
        options.addAll(values);
        new AlertDialog.Builder(this)
                .setTitle(byGroup ? "Filter by Group" : "Filter by Sender")
                .setItems(options.toArray(new String[0]), (dialog, which) -> {
                    String selected = which == 0 ? null : options.get(which);
                    if (byGroup) viewModel.setGroupFilter(selected);
                    else viewModel.setSenderFilter(selected);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDeleteAll() {
        new AlertDialog.Builder(this)
                .setTitle("Delete All Tasks")
                .setMessage("Are you sure you want to delete ALL tasks? This cannot be undone.")
                .setPositiveButton("Delete All", (dialog, which) -> {
                    List<Integer> taskIds = knownTaskIds();
                    viewModel.getRepository().deleteAll(
                            () -> {
                                for (int taskId : taskIds) {
                                    TaskReminderManager.cancel(this, taskId);
                                }
                                Toast.makeText(
                                        this,
                                        "All tasks deleted",
                                        Toast.LENGTH_SHORT).show();
                            },
                            error -> Toast.makeText(
                                    this,
                                    "Failed: " + error.getMessage(),
                                    Toast.LENGTH_SHORT).show());
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private List<Integer> knownTaskIds() {
        List<Integer> ids = new ArrayList<>();
        List<Task> tasks = viewModel.getTasks().getValue();
        if (tasks == null) return ids;
        for (Task task : tasks) ids.add(task.getId());
        return ids;
    }

    private void showDeleteOldTasksDialog() {
        String[] labels = new String[AGE_PRESETS.length];
        for (int i = 0; i < AGE_PRESETS.length; i++) {
            labels[i] = "Older than " + AGE_PRESETS[i] + " days";
        }
        new AlertDialog.Builder(this)
                .setTitle("Delete Old Tasks")
                .setItems(labels, (dialog, which) -> findAndConfirmDeleteOld(AGE_PRESETS[which]))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void findAndConfirmDeleteOld(int days) {
        long cutoff = System.currentTimeMillis() - (long) days * 24L * 60L * 60L * 1000L;
        viewModel.getRepository().getDatabase().collection("tasks").get()
                .addOnSuccessListener(snapshots -> {
                    List<DocumentReference> references = new ArrayList<>();
                    List<Integer> taskIds = new ArrayList<>();
                    for (QueryDocumentSnapshot document : snapshots) {
                        Date created = parseCreatedAt(document.getString("createdAt"));
                        if (created != null && created.getTime() < cutoff) {
                            references.add(document.getReference());
                            Long id = document.getLong("id");
                            if (id != null) taskIds.add(id.intValue());
                        }
                    }
                    if (references.isEmpty()) {
                        Toast.makeText(this, "No old tasks found", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    new AlertDialog.Builder(this)
                            .setTitle("Delete Old Tasks")
                            .setMessage("Delete " + references.size() + " task(s) older than "
                                    + days + " days? This cannot be undone.")
                            .setPositiveButton("Delete", (dialog, which) ->
                                    deleteOldTasks(references, taskIds))
                            .setNegativeButton("Cancel", null)
                            .show();
                })
                .addOnFailureListener(error -> Toast.makeText(
                        this,
                        "Failed: " + error.getMessage(),
                        Toast.LENGTH_SHORT).show());
    }

    private void deleteOldTasks(List<DocumentReference> references, List<Integer> taskIds) {
        for (int taskId : taskIds) TaskReminderManager.cancel(this, taskId);
        int chunkSize = 500;
        int[] remaining = {(references.size() + chunkSize - 1) / chunkSize};
        boolean[] failed = {false};
        for (int start = 0; start < references.size(); start += chunkSize) {
            int end = Math.min(start + chunkSize, references.size());
            WriteBatch batch = viewModel.getRepository().getDatabase().batch();
            for (int i = start; i < end; i++) batch.delete(references.get(i));
            batch.commit()
                    .addOnSuccessListener(value -> {
                        remaining[0]--;
                        if (remaining[0] == 0 && !failed[0]) {
                            Toast.makeText(this, "Old tasks deleted", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .addOnFailureListener(error -> {
                        if (!failed[0]) {
                            failed[0] = true;
                            Toast.makeText(
                                    this,
                                    "Failed: " + error.getMessage(),
                                    Toast.LENGTH_SHORT).show();
                        }
                    });
        }
    }

    private Date parseCreatedAt(String value) {
        if (value == null || value.isEmpty()) return null;
        SimpleDateFormat format =
                new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        try {
            return format.parse(value);
        } catch (java.text.ParseException ignored) {
            return null;
        }
    }
}
