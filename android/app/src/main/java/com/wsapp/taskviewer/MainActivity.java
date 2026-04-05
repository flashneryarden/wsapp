package com.wsapp.taskviewer;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.wsapp.taskviewer.adapter.TaskAdapter;
import com.wsapp.taskviewer.model.Task;

import java.util.ArrayList;
import java.util.List;

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

        adapter = new TaskAdapter(this);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        db = FirebaseFirestore.getInstance();

        swipeRefresh.setOnRefreshListener(this::attachListener);

        attachListener();
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

            List<Task> filtered = filterTasks(allTasks);
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

    private List<Task> filterTasks(List<Task> tasks) {
        if (currentFilter == null) return tasks;
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
        if (id == R.id.filter_all) {
            currentFilter = null;
            setTitle("Tasks — All");
        } else if (id == R.id.filter_pending) {
            currentFilter = "pending";
            setTitle("Tasks — Pending");
        } else if (id == R.id.filter_done) {
            currentFilter = "done";
            setTitle("Tasks — Done");
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
    protected void onDestroy() {
        super.onDestroy();
        if (listenerRegistration != null) {
            listenerRegistration.remove();
        }
    }
}
