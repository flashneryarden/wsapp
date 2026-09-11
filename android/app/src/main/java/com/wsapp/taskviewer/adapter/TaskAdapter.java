package com.wsapp.taskviewer.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.wsapp.taskviewer.R;
import com.wsapp.taskviewer.model.Task;
import com.wsapp.taskviewer.util.DueDateFormatter;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class TaskAdapter extends RecyclerView.Adapter<TaskAdapter.TaskViewHolder> { // Adapts Task objects into reusable RecyclerView cards.

    public interface OnTaskClickListener { // Defines actions that the containing activity must handle.
        void onTaskClick(Task task); // Called when the user taps a task card.
        void onTaskDelete(Task task); // Called when the user taps a task card's delete button.
    } // End of the task-action listener interface.

    private List<Task> tasks = new ArrayList<>(); // Stores the tasks currently displayed by the adapter.
    private OnTaskClickListener listener; // Receives task click and delete events from the adapter.

    public TaskAdapter(OnTaskClickListener listener) { // Creates an adapter with an object that handles user actions.
        this.listener = listener; // Save the supplied listener for later click callbacks.
    } // End of the constructor.

    public void setTasks(List<Task> tasks) { // Replaces the list of tasks displayed by the RecyclerView.
        this.tasks = tasks; // Store the newly supplied task list.
        notifyDataSetChanged(); // Tell RecyclerView to redraw all visible task cards.
    } // End of setTasks().

    @NonNull // The returned ViewHolder is guaranteed not to be null.
    @Override // Implements RecyclerView.Adapter's card-creation method.
    public TaskViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) { // Creates a new task card when RecyclerView needs one.
        View view = LayoutInflater.from(parent.getContext()) // Get an inflater using the RecyclerView's Android context.
                .inflate(R.layout.item_task, parent, false); // Turn item_task.xml into a View without attaching it yet.
        return new TaskViewHolder(view); // Wrap the new card View in a holder and return it.
    } // End of onCreateViewHolder().

    @Override // Implements RecyclerView.Adapter's data-binding method.
    public void onBindViewHolder(@NonNull TaskViewHolder holder, int position) { // Fills one reusable card with data for a list position.
        Task task = tasks.get(position); // Get the task that belongs at this position.
        holder.bind(task); // Copy the task's information into the card's views.
    } // End of onBindViewHolder().

    @Override // Implements RecyclerView.Adapter's item-count method.
    public int getItemCount() { // Reports how many task cards the RecyclerView can display.
        return tasks.size(); // Return the number of tasks in the adapter's list.
    } // End of getItemCount().

    class TaskViewHolder extends RecyclerView.ViewHolder { // Holds the views belonging to one reusable task card.
        private final TextView statusIcon; // Shows whether the task is pending or completed.
        private final TextView taskId; // Shows the task's numeric ID.
        private final TextView summary; // Shows the short AI-generated or manual task summary.
        private final TextView sender; // Shows the original sender and WhatsApp chat.
        private final TextView date; // Shows when the task was created.
        private final TextView actionItemCount; // Shows how many action items the task contains.
        private final TextView criticalBadge; // Highlights tasks classified as critical.
        private final TextView categoryBadge; // Shows the task's category and category color.
        private final TextView dueText; // Shows the task's formatted due date.
        private final ImageButton btnDeleteTask; // Lets the user request deletion of this task.

        TaskViewHolder(@NonNull View itemView) { // Initializes a holder for one inflated item_task.xml card.
            super(itemView); // Give the complete card View to RecyclerView.ViewHolder.
            statusIcon = itemView.findViewById(R.id.statusIcon); // Find the status icon inside this card.
            taskId = itemView.findViewById(R.id.taskId); // Find the task ID label inside this card.
            summary = itemView.findViewById(R.id.summary); // Find the summary text inside this card.
            sender = itemView.findViewById(R.id.sender); // Find the sender text inside this card.
            date = itemView.findViewById(R.id.date); // Find the creation-date text inside this card.
            actionItemCount = itemView.findViewById(R.id.actionItemCount); // Find the action-item count label.
            criticalBadge = itemView.findViewById(R.id.criticalBadge); // Find the critical-task badge.
            categoryBadge = itemView.findViewById(R.id.categoryBadge); // Find the category badge.
            dueText = itemView.findViewById(R.id.dueText); // Find the due-date text.
            btnDeleteTask = itemView.findViewById(R.id.btnDeleteTask); // Find the task's delete button.

            itemView.setOnClickListener(v -> { // React when the user taps anywhere on the task card.
                int pos = getAdapterPosition(); // Get the card's current position because cards can be recycled or moved.
                if (pos != RecyclerView.NO_POSITION && listener != null) { // Continue only if the card still has a valid task and listener.
                    listener.onTaskClick(tasks.get(pos)); // Ask the activity to open the selected task.
                } // End of the valid-position check.
            }); // Finish configuring the task-card click handler.

            btnDeleteTask.setOnClickListener(v -> { // React when the delete button inside the card is tapped.
                int pos = getAdapterPosition(); // Get the card's current position at the time of the click.
                if (pos != RecyclerView.NO_POSITION && listener != null) { // Continue only if this card still represents a valid task.
                    listener.onTaskDelete(tasks.get(pos)); // Ask the activity to handle deletion of the selected task.
                } // End of the valid-position check.
            }); // Finish configuring the delete-button click handler.
        } // End of the TaskViewHolder constructor.

        void bind(Task task) { // Copies one Task object's values into this card's views.
            statusIcon.setText(task.isDone() ? "✅" : "⏳"); // Show a check mark for done tasks or an hourglass for pending tasks.
            taskId.setText("#" + task.getId()); // Display the task ID with a leading hash sign.
            summary.setText(task.getSummary()); // Display the task's summary.
            sender.setText(task.getOrigSender() + " (" + task.getOrigChatName() + ")"); // Display who sent the message and in which chat.
            date.setText(formatDate(task.getCreatedAt())); // Convert and display the task's creation timestamp.

            criticalBadge.setVisibility(task.isEffectivelyCritical() ? View.VISIBLE : View.GONE); // Show the critical badge only for critical tasks.

            String category = task.getEffectiveCategory(); // Get the stored or automatically inferred task category.
            categoryBadge.setText(com.wsapp.taskviewer.util.Categories.label(category).toUpperCase()); // Display the category's readable label.
            categoryBadge.setBackgroundTintList(android.content.res.ColorStateList.valueOf( // Create and apply the category badge's background color.
                    com.wsapp.taskviewer.util.Categories.color(category))); // Look up the color assigned to this category.

            String due = DueDateFormatter.format(task.getEffectiveDueDate(), task.getCreatedAt()); // Convert the task's due date into display text.
            if (due != null && !due.trim().isEmpty()) { // Check whether the task has a usable due date.
                dueText.setVisibility(View.VISIBLE); // Make the due-date label visible.
                dueText.setText("📅 Due: " + due); // Display the formatted due date.
            } else { // Handle tasks without a due date.
                dueText.setVisibility(View.GONE); // Hide the unused due-date label.
            } // End of the due-date visibility decision.
            int count = task.getActionItems() != null ? task.getActionItems().size() : 0; // Count action items safely, using zero for a null list.
            if (count > 0) { // Check whether there are action items to report.
                actionItemCount.setVisibility(View.VISIBLE); // Show the action-item count label.
                actionItemCount.setText(count + " action item" + (count > 1 ? "s" : "")); // Display the count with correct singular or plural wording.
            } else { // Handle tasks without action items.
                actionItemCount.setVisibility(View.GONE); // Hide the unused action-item count label.
            } // End of the action-item visibility decision.
        } // End of bind().

        private String formatDate(String iso) { // Converts a stored ISO timestamp into a shorter local date and time.
            if (iso == null) return ""; // Return blank text when the task has no creation timestamp.
            try { // Attempt to parse the timestamp using the backend's expected format.
                SimpleDateFormat isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US); // Define the stored timestamp pattern.
                isoFormat.setTimeZone(TimeZone.getTimeZone("UTC")); // Interpret the stored timestamp as UTC.
                Date d = isoFormat.parse(iso); // Convert the timestamp string into a Date object.
                SimpleDateFormat displayFormat = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()); // Define the shorter user-facing format.
                return displayFormat.format(d); // Format the date using the device's local time zone.
            } catch (ParseException e) { // Handle timestamps that do not match the expected format.
                return iso.substring(0, Math.min(16, iso.length())); // Show a safe shortened version of the original value.
            } // End of timestamp parsing.
        } // End of formatDate().
    } // End of TaskViewHolder.
} // End of TaskAdapter.
