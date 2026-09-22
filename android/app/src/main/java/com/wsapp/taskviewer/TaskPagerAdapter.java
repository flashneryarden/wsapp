package com.wsapp.taskviewer;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import com.wsapp.taskviewer.logic.TaskFilterSortEngine;

public final class TaskPagerAdapter extends FragmentStateAdapter {
    public TaskPagerAdapter(@NonNull FragmentActivity activity) {
        super(activity);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        if (position == 0) return TaskListFragment.newInstance(TaskFilterSortEngine.Section.URGENT);
        if (position == 1) return TaskListFragment.newInstance(TaskFilterSortEngine.Section.OPEN);
        return TaskListFragment.newInstance(TaskFilterSortEngine.Section.COMPLETED);
    }

    @Override
    public int getItemCount() {
        return 3;
    }
}
