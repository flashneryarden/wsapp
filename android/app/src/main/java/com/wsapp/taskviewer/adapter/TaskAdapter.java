package com.wsapp.taskviewer.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.wsapp.taskviewer.R;
import com.wsapp.taskviewer.model.Task;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class TaskAdapter extends RecyclerView.Adapter<TaskAdapter.TaskViewHolder> {

    public interface OnTaskClickListener {
        void onTaskClick(Task task);
    }

    private List<Task> tasks = new ArrayList<>();
    private OnTaskClickListener listener;

    public TaskAdapter(OnTaskClickListener listener) {
        this.listener = listener;
    }

    public void setTasks(List<Task> tasks) {
        this.tasks = tasks;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public TaskViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_task, parent, false);
        return new TaskViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull TaskViewHolder holder, int position) {
        Task task = tasks.get(position);
        holder.bind(task);
    }

    @Override
    public int getItemCount() {
        return tasks.size();
    }

    class TaskViewHolder extends RecyclerView.ViewHolder {
        private final TextView statusIcon;
        private final TextView taskId;
        private final TextView summary;
        private final TextView sender;
        private final TextView date;
        private final TextView actionItemCount;
        private final TextView criticalBadge;

        TaskViewHolder(@NonNull View itemView) {
            super(itemView);
            statusIcon = itemView.findViewById(R.id.statusIcon);
            taskId = itemView.findViewById(R.id.taskId);
            summary = itemView.findViewById(R.id.summary);
            sender = itemView.findViewById(R.id.sender);
            date = itemView.findViewById(R.id.date);
            actionItemCount = itemView.findViewById(R.id.actionItemCount);
            criticalBadge = itemView.findViewById(R.id.criticalBadge);

            itemView.setOnClickListener(v -> {
                int pos = getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && listener != null) {
                    listener.onTaskClick(tasks.get(pos));
                }
            });
        }

        void bind(Task task) {
            statusIcon.setText(task.isDone() ? "✅" : "⏳");
            taskId.setText("#" + task.getId());
            summary.setText(task.getSummary());
            sender.setText(task.getOrigSender() + " (" + task.getOrigChatName() + ")");
            date.setText(formatDate(task.getCreatedAt()));

            criticalBadge.setVisibility(task.isEffectivelyCritical() ? View.VISIBLE : View.GONE);
            int count = task.getActionItems() != null ? task.getActionItems().size() : 0;
            if (count > 0) {
                actionItemCount.setVisibility(View.VISIBLE);
                actionItemCount.setText(count + " action item" + (count > 1 ? "s" : ""));
            } else {
                actionItemCount.setVisibility(View.GONE);
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
    }
}
