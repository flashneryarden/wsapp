package com.wsapp.taskviewer;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.firestore.WriteBatch;
import com.wsapp.taskviewer.adapter.TaskAdapter;
import com.wsapp.taskviewer.model.Task;
import com.wsapp.taskviewer.util.DueDateFormatter;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

public class MainActivity extends AppCompatActivity implements TaskAdapter.OnTaskClickListener {

    private RecyclerView recyclerView;
    private TaskAdapter adapter;
    private SwipeRefreshLayout swipeRefresh;
    private TextView emptyView;
    private FirebaseFirestore db;
    private ListenerRegistration listenerRegistration;

    // Filter: null = all, "pending", "done"
    private String currentFilter = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        setTitle("Tasks");

        recyclerView = findViewById(R.id.recyclerView);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        emptyView = findViewById(R.id.emptyView);
        FloatingActionButton fabAdd = findViewById(R.id.fabAdd);

        adapter = new TaskAdapter(this);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        db = FirebaseFirestore.getInstance();

        swipeRefresh.setOnRefreshListener(this::attachListener);
        fabAdd.setOnClickListener(v -> showAddTaskDialog());

        attachListener();
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
                    if (!text.isEmpty()) {
                        createTask(text);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void createTask(String text) {
        db.collection("tasks")
                .orderBy("id", Query.Direction.DESCENDING)
                .limit(1)
                .get()
                .addOnSuccessListener(snapshots -> {
                    int nextId = 1;
                    for (QueryDocumentSnapshot doc : snapshots) {
                        Task last = doc.toObject(Task.class);
                        nextId = last.getId() + 1;
                    }

                    Map<String, Object> task = new HashMap<>();
                    task.put("id", nextId);
                    task.put("origSender", "android");
                    task.put("origChatName", "android");
                    task.put("text", text);
                    task.put("summary", text);
                    task.put("actionItems", new ArrayList<>());
                    task.put("createdAt", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                            java.util.Locale.US).format(new java.util.Date()));
                    task.put("status", "pending");
                    task.put("completedAt", null);
                    task.put("notes", new ArrayList<>());

                    db.collection("tasks").document(String.valueOf(nextId))
                            .set(task)
                            .addOnSuccessListener(v -> Toast.makeText(this, "Task created", Toast.LENGTH_SHORT).show())
                            .addOnFailureListener(e -> Toast.makeText(this, "Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                });
    }

    private void attachListener() {
        if (listenerRegistration != null) {
            listenerRegistration.remove();
        }

        Query query = db.collection("tasks").orderBy("id", Query.Direction.DESCENDING);

        listenerRegistration = query.addSnapshotListener((snapshots, error) -> {
            swipeRefresh.setRefreshing(false);

            if (error != null) {
                emptyView.setText("Error loading tasks: " + error.getMessage());
                emptyView.setVisibility(View.VISIBLE);
                return;
            }

            if (snapshots == null) return;

            List<Task> allTasks = new ArrayList<>();
            for (QueryDocumentSnapshot doc : snapshots) {
                Task task = doc.toObject(Task.class);
                allTasks.add(task);
            }

            clearPastDueDates(allTasks);

            List<Task> filtered = filterTasks(allTasks);
            // Critical tasks first; stable sort preserves the id-desc order within each group.
            java.util.Collections.sort(filtered,
                    (a, b) -> Boolean.compare(b.isEffectivelyCritical(), a.isEffectivelyCritical()));
            adapter.setTasks(filtered);

            if (filtered.isEmpty()) {
                emptyView.setText(currentFilter != null
                        ? "No " + currentFilter + " tasks"
                        : "No tasks yet");
                emptyView.setVisibility(View.VISIBLE);
            } else {
                emptyView.setVisibility(View.GONE);
            }
        });
    }

    /**
     * Removes past due dates from the database for any loaded task: deletes the
     * structured dueDate field when it has passed, and strips any "[due: ...]"
     * marker with a past date out of the action items (the form older/deployed
     * backends use). Local objects are mutated so the change shows immediately.
     */
    private void clearPastDueDates(List<Task> tasks) {
        for (Task task : tasks) {
            DocumentReference ref = null;

            if (DueDateFormatter.isPast(task.getDueDate())) {
                task.setDueDate(null);
                ref = db.collection("tasks").document(String.valueOf(task.getId()));
                ref.update("dueDate", FieldValue.delete());
            }

            List<String> cleaned =
                    DueDateFormatter.stripPastDueMarkers(task.getActionItems(), task.getCreatedAt());
            if (cleaned != null) {
                task.setActionItems(cleaned);
                if (ref == null) {
                    ref = db.collection("tasks").document(String.valueOf(task.getId()));
                }
                ref.update("actionItems", cleaned);
            }
        }
    }

    private List<Task> filterTasks(List<Task> tasks) {        if (currentFilter == null) return tasks;
        List<Task> result = new ArrayList<>();
        for (Task t : tasks) {
            if (currentFilter.equals(t.getStatus())) {
                result.add(t);
            }
        }
        return result;
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
        } else if (id == R.id.filter_all) {
            currentFilter = null;
            setTitle("Tasks — All");
        } else if (id == R.id.filter_pending) {
            currentFilter = "pending";
            setTitle("Tasks — Pending");
        } else if (id == R.id.filter_done) {
            currentFilter = "done";
            setTitle("Tasks — Done");
        } else if (id == R.id.delete_old) {
            showDeleteOldTasksDialog();
            return true;
        } else if (id == R.id.delete_all) {
            confirmDeleteAll();
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
        attachListener();
        return true;
    }

    @Override
    public void onTaskClick(Task task) {
        Intent intent = new Intent(this, TaskDetailActivity.class);
        intent.putExtra("task_id", task.getId());
        startActivity(intent);
    }

    @Override
    public void onTaskDelete(Task task) {
        new AlertDialog.Builder(this)
                .setTitle("Delete Task")
                .setMessage("Delete task #" + task.getId() + "?\n\n" + task.getSummary())
                .setPositiveButton("Delete", (dialog, which) ->
                        db.collection("tasks").document(String.valueOf(task.getId()))
                                .delete()
                                .addOnSuccessListener(v -> Toast.makeText(this,
                                        "Task #" + task.getId() + " deleted", Toast.LENGTH_SHORT).show())
                                .addOnFailureListener(e -> Toast.makeText(this,
                                        "Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDeleteAll() {
        new AlertDialog.Builder(this)
                .setTitle("Delete All Tasks")
                .setMessage("Are you sure you want to delete ALL tasks? This cannot be undone.")
                .setPositiveButton("Delete All", (dialog, which) -> deleteAllTasks())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteAllTasks() {
        db.collection("tasks").get().addOnSuccessListener(snapshots -> {
            if (snapshots.isEmpty()) {
                Toast.makeText(this, "No tasks to delete", Toast.LENGTH_SHORT).show();
                return;
            }
            com.google.firebase.firestore.WriteBatch batch = db.batch();
            for (QueryDocumentSnapshot doc : snapshots) {
                batch.delete(doc.getReference());
            }
            batch.commit()
                    .addOnSuccessListener(v -> Toast.makeText(this,
                            "Deleted " + snapshots.size() + " tasks", Toast.LENGTH_SHORT).show())
                    .addOnFailureListener(e -> Toast.makeText(this,
                            "Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
        });
    }

    private static final int[] AGE_PRESETS = {30, 60, 90};

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
        db.collection("tasks").get().addOnSuccessListener(snapshots -> {
            List<DocumentReference> toDelete = new ArrayList<>();
            for (QueryDocumentSnapshot doc : snapshots) {
                Date created = parseCreatedAt(doc.getString("createdAt"));
                if (created != null && created.getTime() < cutoff) {
                    toDelete.add(doc.getReference());
                }
            }
            if (toDelete.isEmpty()) {
                Toast.makeText(this, "No tasks older than " + days + " days", Toast.LENGTH_SHORT).show();
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("Delete Old Tasks")
                    .setMessage("Delete " + toDelete.size() + " task(s) older than " + days
                            + " days? This cannot be undone.")
                    .setPositiveButton("Delete", (d, w) -> deleteOldTasks(toDelete))
                    .setNegativeButton("Cancel", null)
                    .show();
        }).addOnFailureListener(e ->
                Toast.makeText(this, "Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
    }

    private void deleteOldTasks(List<DocumentReference> refs) {
        final int total = refs.size();
        final int chunkSize = 500; // Firestore batch limit
        final int[] remainingBatches = {(total + chunkSize - 1) / chunkSize};
        final boolean[] failed = {false};

        for (int start = 0; start < total; start += chunkSize) {
            int end = Math.min(start + chunkSize, total);
            WriteBatch batch = db.batch();
            for (int i = start; i < end; i++) {
                batch.delete(refs.get(i));
            }
            batch.commit()
                    .addOnSuccessListener(v -> {
                        remainingBatches[0]--;
                        if (remainingBatches[0] == 0 && !failed[0]) {
                            Toast.makeText(this, "Deleted " + total + " task(s)", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .addOnFailureListener(e -> {
                        if (!failed[0]) {
                            failed[0] = true;
                            Toast.makeText(this, "Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    });
        }
    }

    private Date parseCreatedAt(String value) {
        if (value == null || value.isEmpty()) return null;
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        fmt.setLenient(true);
        try {
            return fmt.parse(value);
        } catch (java.text.ParseException e) {
            return null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (listenerRegistration != null) {
            listenerRegistration.remove();
        }
    }
}
