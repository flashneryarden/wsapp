package com.wsapp.taskviewer;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
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
import com.google.firebase.messaging.FirebaseMessaging;
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

    private RecyclerView recyclerView; // The scrolling UI list that displays task cards.
    private TaskAdapter adapter; // Converts Task objects into views shown by the RecyclerView.
    private SwipeRefreshLayout swipeRefresh; // Detects pull-to-refresh gestures around the task list.
    private TextView emptyView; // Displays a message when no tasks match the current view.
    private TextView filterBanner; // Shows the active filters and lets the user clear them.
    private FirebaseFirestore db; // Provides access to the app's cloud Firestore database.
    private ListenerRegistration listenerRegistration; // Represents the active real-time Firestore listener.

    // Filter: null = all, "pending", "done"
    private String currentFilter = null; // Restricts tasks by status, or null to show every status.

    private enum SortMode { CRITICAL_FIRST, DUE_DATE, NEWEST, GROUP, SENDER, CATEGORY }
    private SortMode currentSort = SortMode.CRITICAL_FIRST; // Stores the currently selected task ordering.
    // null = no group/sender/category restriction
    private String groupFilter = null; // Restricts tasks to one WhatsApp group, or null for all groups.
    private String senderFilter = null; // Restricts tasks to one sender, or null for all senders.
    private String categoryFilter = null; // Restricts tasks to one category, or null for all categories.
    // Most recent tasks from Firestore, used to rebuild the view when sort/filter changes.
    private final List<Task> latestTasks = new ArrayList<>(); // Keeps the unfiltered task snapshot received from Firestore.

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState); // Let AppCompatActivity perform its standard activity initialization.
        setContentView(R.layout.activity_main); // Build this screen from res/layout/activity_main.xml.

        setTitle("Tasks"); // Set the text displayed in the activity's top app bar.

        recyclerView = findViewById(R.id.recyclerView); // Find the scrolling list that displays task cards.
        swipeRefresh = findViewById(R.id.swipeRefresh); // Find the container that handles pull-to-refresh gestures.
        emptyView = findViewById(R.id.emptyView); // Find the message shown when there are no tasks to display.
        filterBanner = findViewById(R.id.filterBanner); // Find the banner that describes the active filters.
        filterBanner.setOnClickListener(v -> { // Clear every active filter when the banner is tapped.
            currentFilter = null; // Remove the pending/done status filter.
            groupFilter = null; // Remove the WhatsApp group filter.
            senderFilter = null; // Remove the sender filter.
            categoryFilter = null; // Remove the task category filter.
            applyView(); // Rebuild the visible list using all loaded tasks.
            Toast.makeText(this, "Filters cleared", Toast.LENGTH_SHORT).show(); // Briefly confirm the action.
        }); // Finish configuring the filter-banner click handler.
        FloatingActionButton fabAdd = findViewById(R.id.fabAdd); // Find the floating button used to create a task.

        adapter = new TaskAdapter(this); // Create the object that converts Task objects into list-item views.
        recyclerView.setLayoutManager(new LinearLayoutManager(this)); // Arrange task cards in a vertical list.
        recyclerView.setAdapter(adapter); // Connect the task adapter to the RecyclerView.

        db = FirebaseFirestore.getInstance(); // Get the shared Firestore database client.
        CriticalTaskNotifications.createChannel(this); // Prepare the high-priority lock-screen notification channel.
        DeviceRegistration.registerCurrentToken(); // Register this exact phone for direct critical-task pushes.
        subscribeToCriticalTaskNotifications(); // Also subscribe without relying on Firestore device-registration rules.
        requestNotificationPermission(); // Ask Android 13+ for permission to display notifications.

        swipeRefresh.setOnRefreshListener(this::attachListener); // Reload the Firestore listener after a swipe.
        fabAdd.setOnClickListener(v -> showAddTaskDialog()); // Open the new-task dialog when the add button is tapped.

        attachListener(); // Start listening for real-time changes to the Firestore tasks collection.
        openTaskFromNotification(getIntent()); // Open a critical task when this activity was launched from its notification.
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
        } catch (NumberFormatException error) {
            Log.w("MainActivity", "Invalid notification task ID: " + taskIdValue, error);
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

    private void subscribeToCriticalTaskNotifications() {
        FirebaseMessaging.getInstance()
                .subscribeToTopic(CriticalTaskNotifications.TOPIC)
                .addOnFailureListener(error ->
                        Log.w("MainActivity", "Critical-task topic subscription failed", error));
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
        if (listenerRegistration != null) { // Check whether an older Firestore listener is still active.
            listenerRegistration.remove(); // Stop the old listener to avoid receiving every update multiple times.
        } // Finish cleaning up the previous listener.

        Query query = db.collection("tasks").orderBy("id", Query.Direction.DESCENDING); // Request all tasks, starting with the highest ID.

        listenerRegistration = query.addSnapshotListener((snapshots, error) -> { // Run this callback now and whenever the tasks collection changes.
            swipeRefresh.setRefreshing(false); // Hide the pull-to-refresh loading indicator.

            if (error != null) { // Check whether Firestore failed to retrieve the task snapshot.
                emptyView.setText("Error loading tasks: " + error.getMessage()); // Put the error message in the empty-state view.
                emptyView.setVisibility(View.VISIBLE); // Make the error message visible to the user.
                return; // Stop because there is no valid task data to process.
            } // Finish handling a Firestore error.

            if (snapshots == null) return; // Stop if Firestore returned neither an error nor a snapshot.

            List<Task> allTasks = new ArrayList<>(); // Create a list for the Task objects built from Firestore documents.
            for (QueryDocumentSnapshot doc : snapshots) { // Visit every document in the current tasks snapshot.
                Task task = doc.toObject(Task.class); // Convert the Firestore document fields into a Task object.
                allTasks.add(task); // Add the converted task to the newly loaded list.
            } // Finish converting all Firestore documents.

            clearPastDueDates(allTasks); // Remove due dates that have already passed.

            latestTasks.clear(); // Remove the previous in-memory Firestore snapshot.
            latestTasks.addAll(allTasks); // Store the newly loaded tasks as the current complete snapshot.
            applyView(); // Apply active filters and sorting, then update the RecyclerView.
        }); // Register the callback and save its registration so it can later be removed.
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
