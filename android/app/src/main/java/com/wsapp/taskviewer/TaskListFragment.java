package com.wsapp.taskviewer;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.wsapp.taskviewer.adapter.TaskAdapter;
import com.wsapp.taskviewer.logic.TaskFilterSortEngine;
import com.wsapp.taskviewer.model.Task;
import com.wsapp.taskviewer.reminder.TaskReminderManager;

import java.util.List;

public final class TaskListFragment extends Fragment implements TaskAdapter.OnTaskClickListener {
    private static final String ARG_SECTION = "section";

    private TaskFilterSortEngine.Section section;
    private TaskViewModel viewModel;
    private TaskAdapter adapter;
    private SwipeRefreshLayout swipeRefresh;
    private TextView emptyView;

    public static TaskListFragment newInstance(TaskFilterSortEngine.Section section) {
        TaskListFragment fragment = new TaskListFragment();
        Bundle arguments = new Bundle();
        arguments.putString(ARG_SECTION, section.name());
        fragment.setArguments(arguments);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_task_list, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        String name = requireArguments().getString(ARG_SECTION, TaskFilterSortEngine.Section.OPEN.name());
        section = TaskFilterSortEngine.Section.valueOf(name);
        viewModel = new ViewModelProvider(requireActivity()).get(TaskViewModel.class);

        RecyclerView recyclerView = view.findViewById(R.id.recyclerView);
        emptyView = view.findViewById(R.id.emptyView);
        swipeRefresh = view.findViewById(R.id.swipeRefresh);
        adapter = new TaskAdapter(this);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);

        swipeRefresh.setOnRefreshListener(viewModel.getRepository()::listen);
        viewModel.getTasks().observe(getViewLifecycleOwner(), tasks -> render());
        viewModel.getFilterVersion().observe(getViewLifecycleOwner(), ignored -> render());
        viewModel.getError().observe(getViewLifecycleOwner(), error -> {
            if (error != null) {
                swipeRefresh.setRefreshing(false);
                emptyView.setText("Error loading tasks: " + error);
                emptyView.setVisibility(View.VISIBLE);
            }
        });
    }

    private void render() {
        List<Task> visibleTasks = viewModel.tasksFor(section);
        adapter.setTasks(visibleTasks);
        swipeRefresh.setRefreshing(false);
        if (visibleTasks.isEmpty()) {
            emptyView.setText(emptyMessage());
            emptyView.setVisibility(View.VISIBLE);
        } else {
            emptyView.setVisibility(View.GONE);
        }
    }

    private String emptyMessage() {
        if (section == TaskFilterSortEngine.Section.URGENT) return "No urgent or upcoming tasks";
        if (section == TaskFilterSortEngine.Section.COMPLETED) return "No completed tasks";
        return "No open tasks";
    }

    @Override
    public void onTaskClick(Task task) {
        Intent intent = new Intent(requireContext(), TaskDetailActivity.class);
        intent.putExtra("task_id", task.getId());
        startActivity(intent);
    }

    @Override
    public void onTaskDelete(Task task) {
        new AlertDialog.Builder(requireContext())
                .setTitle("Delete Task")
                .setMessage("Delete task #" + task.getId() + "?\n\n" + task.getSummary())
                .setPositiveButton("Delete", (dialog, which) -> {
                    viewModel.getRepository().deleteTask(
                            task,
                            () -> {
                                TaskReminderManager.cancel(requireContext(), task.getId());
                                Toast.makeText(
                                        requireContext(),
                                        "Task deleted",
                                        Toast.LENGTH_SHORT).show();
                            },
                            error -> Toast.makeText(
                                    requireContext(),
                                    "Failed: " + error.getMessage(),
                                    Toast.LENGTH_SHORT).show());
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
