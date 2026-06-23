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
    private TextView filterBanner;
    private FirebaseFirestore db;
    private ListenerRegistration listenerRegistration;

    // Filter: null = all, "pending", "done"
    private String currentFilter = null;

    private enum SortMode { CRITICAL_FIRST, DUE_DATE, NEWEST, GROUP, SENDER, CATEGORY }
    private SortMode currentSort = SortMode.CRITICAL_FIRST;
    // null = no group/sender/category restriction
    private String groupFilter = null;
    private String senderFilter = null;
    private String categoryFilter = null;
    // Most recent tasks from Firestore, used to rebuild the view when sort/filter changes.
    private final List<Task> latestTasks = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        setTitle("Tasks");

        recyclerView = findViewById(R.id.recyclerView);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        emptyView = findViewById(R.id.emptyView);
        filterBanner = findViewById(R.id.filterBanner);
        filterBanner.setOnClickListener(v -> {
            currentFilter = null;
            groupFilter = null;
            senderFilter = null;
            categoryFilter = null;
            applyView();
            Toast.makeText(this, "Filters cleared", Toast.LENGTH_SHORT).show();
        });
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

            latestTasks.clear();
            latestTasks.addAll(allTasks);
            applyView();
        });
    }

    /**
     * Applies the active status/group/sender filters and the selected sort order
     * to {@link #latestTasks}, then updates the adapter, title and empty state.
     */
    private void applyView() {
        List<Task> filtered = filterTasks(latestTasks);
        sortTasks(filtered);
        adapter.setTasks(filtered);

        setTitle(buildTitle());
        updateFilterBanner(latestTasks.size() - filtered.size());

        if (filtered.isEmpty()) {
            emptyView.setText(hasActiveFilter() ? "No matching tasks" : "No tasks yet");
            emptyView.setVisibility(View.VISIBLE);
        } else {
            emptyView.setVisibility(View.GONE);
        }
    }

    /**
     * Shows or hides the filter banner. When a filter is active it explains what is
     * filtered and how many tasks are hidden, and tapping it clears all filters.
     */
    private void updateFilterBanner(int hiddenCount) {
        if (!hasActiveFilter()) {
            filterBanner.setVisibility(View.GONE);
            return;
        }
        List<String> parts = new ArrayList<>();
        if (categoryFilter != null) {
            parts.add("Category: " + com.wsapp.taskviewer.util.Categories.label(categoryFilter));
        }
        if (groupFilter != null) parts.add("Group: " + groupFilter);
        if (senderFilter != null) parts.add("Sender: " + senderFilter);
        if (currentFilter != null) {
            parts.add("Status: " + ("pending".equals(currentFilter) ? "Pending" : "Done"));
        }
        String text = "Filtered · " + android.text.TextUtils.join(", ", parts);
        if (hiddenCount > 0) {
            text += "  (" + hiddenCount + " hidden)";
        }
        text += "  —  tap to clear";
        filterBanner.setText(text);
        filterBanner.setVisibility(View.VISIBLE);
    }

    private boolean hasActiveFilter() {
        return currentFilter != null || groupFilter != null || senderFilter != null
                || categoryFilter != null;
    }

    private String buildTitle() {
        StringBuilder sb = new StringBuilder("Tasks");
        if (categoryFilter != null) {
            sb.append(" · ").append(com.wsapp.taskviewer.util.Categories.label(categoryFilter));
        } else if (groupFilter != null) {
            sb.append(" · ").append(groupFilter);
        } else if (senderFilter != null) {
            sb.append(" · ").append(senderFilter);
        } else if (currentFilter != null) {
            sb.append(" · ").append("pending".equals(currentFilter) ? "Pending" : "Done");
        }
        return sb.toString();
    }

    private void sortTasks(List<Task> tasks) {
        switch (currentSort) {
            case DUE_DATE:
                // Tasks with a resolvable due date first, soonest at the top; undated last.
                java.util.Collections.sort(tasks, (a, b) -> {
                    java.time.LocalDate da = DueDateFormatter.resolve(a.getEffectiveDueDate(), a.getCreatedAt());
                    java.time.LocalDate db2 = DueDateFormatter.resolve(b.getEffectiveDueDate(), b.getCreatedAt());
                    if (da == null && db2 == null) return Integer.compare(b.getId(), a.getId());
                    if (da == null) return 1;
                    if (db2 == null) return -1;
                    return da.compareTo(db2);
                });
                break;
            case NEWEST:
                java.util.Collections.sort(tasks, (a, b) -> Integer.compare(b.getId(), a.getId()));
                break;
            case GROUP:
                java.util.Collections.sort(tasks, (a, b) -> {
                    int c = safe(a.getOrigChatName()).compareToIgnoreCase(safe(b.getOrigChatName()));
                    return c != 0 ? c : Integer.compare(b.getId(), a.getId());
                });
                break;
            case SENDER:
                java.util.Collections.sort(tasks, (a, b) -> {
                    int c = safe(a.getOrigSender()).compareToIgnoreCase(safe(b.getOrigSender()));
                    return c != 0 ? c : Integer.compare(b.getId(), a.getId());
                });
                break;
            case CATEGORY:
                java.util.Collections.sort(tasks, (a, b) -> {
                    int c = a.getEffectiveCategory().compareToIgnoreCase(b.getEffectiveCategory());
                    return c != 0 ? c : Integer.compare(b.getId(), a.getId());
                });
                break;
            case CRITICAL_FIRST:
            default:
                // Newest first, then float critical tasks to the top (stable sort).
                java.util.Collections.sort(tasks, (a, b) -> Integer.compare(b.getId(), a.getId()));
                java.util.Collections.sort(tasks,
                        (a, b) -> Boolean.compare(b.isEffectivelyCritical(), a.isEffectivelyCritical()));
                break;
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
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

    private List<Task> filterTasks(List<Task> tasks) {
        List<Task> result = new ArrayList<>();
        for (Task t : tasks) {
            if (currentFilter != null && !currentFilter.equals(t.getStatus())) continue;
            if (groupFilter != null && !groupFilter.equals(safe(t.getOrigChatName()))) continue;
            if (senderFilter != null && !senderFilter.equals(safe(t.getOrigSender()))) continue;
            if (categoryFilter != null && !categoryFilter.equals(t.getEffectiveCategory())) continue;
            result.add(t);
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
            return true;
        } else if (id == R.id.sort_critical) {
            item.setChecked(true);
            currentSort = SortMode.CRITICAL_FIRST;
        } else if (id == R.id.sort_due) {
            item.setChecked(true);
            currentSort = SortMode.DUE_DATE;
        } else if (id == R.id.sort_newest) {
            item.setChecked(true);
            currentSort = SortMode.NEWEST;
        } else if (id == R.id.sort_by_group) {
            item.setChecked(true);
            currentSort = SortMode.GROUP;
        } else if (id == R.id.sort_by_sender) {
            item.setChecked(true);
            currentSort = SortMode.SENDER;
        } else if (id == R.id.sort_by_category) {
            item.setChecked(true);
            currentSort = SortMode.CATEGORY;
        } else if (id == R.id.filter_all) {
            item.setChecked(true);
            currentFilter = null;
        } else if (id == R.id.filter_pending) {
            item.setChecked(true);
            currentFilter = "pending";
        } else if (id == R.id.filter_done) {
            item.setChecked(true);
            currentFilter = "done";
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
            currentFilter = null;
            groupFilter = null;
            senderFilter = null;
            categoryFilter = null;
        } else if (id == R.id.delete_old) {
            showDeleteOldTasksDialog();
            return true;
        } else if (id == R.id.delete_all) {
            confirmDeleteAll();
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
        applyView();
        return true;
    }

    /** Lets the user filter by one of the fixed task categories (or all). */
    private void showCategoryFilterDialog() {
        String[] keys = com.wsapp.taskviewer.util.Categories.KEYS;
        final List<String> options = new ArrayList<>();
        options.add(null); // "All Categories"
        String[] labels = new String[keys.length + 1];
        labels[0] = "All Categories";
        for (int i = 0; i < keys.length; i++) {
            options.add(keys[i]);
            labels[i + 1] = com.wsapp.taskviewer.util.Categories.label(keys[i]);
        }
        new AlertDialog.Builder(this)
                .setTitle("Filter by Category")
                .setItems(labels, (dialog, which) -> {
                    categoryFilter = options.get(which);
                    applyView();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Shows a single-choice dialog of the distinct groups (or senders) present in
     * the loaded tasks and applies the chosen value as a filter. Selecting a group
     * clears any sender filter and vice versa, so only one is active at a time.
     */
    private void showValueFilterDialog(boolean byGroup) {
        java.util.TreeSet<String> values = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Task t : latestTasks) {
            String v = byGroup ? t.getOrigChatName() : t.getOrigSender();
            if (v != null && !v.trim().isEmpty()) values.add(v.trim());
        }
        if (values.isEmpty()) {
            Toast.makeText(this, "No " + (byGroup ? "groups" : "senders") + " available",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        final List<String> options = new ArrayList<>();
        options.add(byGroup ? "All Groups" : "All Senders");
        options.addAll(values);
        String[] labels = options.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle(byGroup ? "Filter by Group" : "Filter by Sender")
                .setItems(labels, (dialog, which) -> {
                    String chosen = which == 0 ? null : options.get(which);
                    if (byGroup) {
                        groupFilter = chosen;
                        senderFilter = null;
                    } else {
                        senderFilter = chosen;
                        groupFilter = null;
                    }
                    applyView();
                })
                .setNegativeButton("Cancel", null)
                .show();
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
