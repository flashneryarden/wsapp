package com.wsapp.taskviewer;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.wsapp.taskviewer.data.TaskRepository;
import com.wsapp.taskviewer.logic.TaskFilterSortEngine;
import com.wsapp.taskviewer.model.Task;

import java.util.List;

public final class TaskViewModel extends ViewModel {
    private final TaskRepository repository = new TaskRepository();
    private final MutableLiveData<Integer> filterVersion = new MutableLiveData<>(0);
    private TaskFilterSortEngine.SortMode sortMode = TaskFilterSortEngine.SortMode.CRITICAL_FIRST;
    private String groupFilter;
    private String senderFilter;
    private String categoryFilter;

    public LiveData<List<Task>> getTasks() {
        return repository.getTasks();
    }

    public LiveData<String> getError() {
        return repository.getError();
    }

    public LiveData<TaskRepository.SyncState> getSyncState() {
        return repository.getSyncState();
    }

    public LiveData<Integer> getFilterVersion() {
        return filterVersion;
    }

    public TaskRepository getRepository() {
        return repository;
    }

    public List<Task> tasksFor(TaskFilterSortEngine.Section section) {
        return TaskFilterSortEngine.apply(
                repository.getTasks().getValue(),
                section,
                sortMode,
                groupFilter,
                senderFilter,
                categoryFilter);
    }

    public void setSortMode(TaskFilterSortEngine.SortMode value) {
        sortMode = value;
        notifyFilterChanged();
    }

    public void setGroupFilter(String value) {
        groupFilter = value;
        senderFilter = null;
        notifyFilterChanged();
    }

    public void setSenderFilter(String value) {
        senderFilter = value;
        groupFilter = null;
        notifyFilterChanged();
    }

    public void setCategoryFilter(String value) {
        categoryFilter = value;
        notifyFilterChanged();
    }

    public void clearFilters() {
        groupFilter = null;
        senderFilter = null;
        categoryFilter = null;
        notifyFilterChanged();
    }

    public boolean hasFilters() {
        return groupFilter != null || senderFilter != null || categoryFilter != null;
    }

    public String describeFilters() {
        if (categoryFilter != null) {
            return "Category: " + com.wsapp.taskviewer.util.Categories.label(categoryFilter);
        }
        if (groupFilter != null) return "Group: " + groupFilter;
        if (senderFilter != null) return "Sender: " + senderFilter;
        return "";
    }

    private void notifyFilterChanged() {
        Integer current = filterVersion.getValue();
        filterVersion.setValue(current == null ? 1 : current + 1);
    }

    @Override
    protected void onCleared() {
        repository.close();
    }
}
