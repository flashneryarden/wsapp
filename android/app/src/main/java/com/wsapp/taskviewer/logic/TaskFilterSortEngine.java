package com.wsapp.taskviewer.logic;

import com.wsapp.taskviewer.model.Task;
import com.wsapp.taskviewer.util.DueDateFormatter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.time.Instant;

public final class TaskFilterSortEngine {
    public enum Section { URGENT, OPEN, COMPLETED }
    public enum SortMode { CRITICAL_FIRST, DUE_DATE, NEWEST, GROUP, SENDER, CATEGORY }

    private TaskFilterSortEngine() {
    }

    public static List<Task> apply(
            List<Task> source,
            Section section,
            SortMode sortMode,
            String group,
            String sender,
            String category) {
        List<Task> result = new ArrayList<>();
        if (source == null) return result;

        for (Task task : source) {
            if (!belongsToSection(task, section)) continue;
            if (group != null && !group.equals(safe(task.getOrigChatName()))) continue;
            if (sender != null && !sender.equals(safe(task.getOrigSender()))) continue;
            if (category != null && !category.equals(task.getEffectiveCategory())) continue;
            result.add(task);
        }
        sort(result, sortMode);
        return result;
    }

    private static boolean belongsToSection(Task task, Section section) {
        if (section == Section.COMPLETED) return task.isDone();
        if (!task.isPending()) return false;
        if (section == Section.OPEN) return true;

        LocalDate due = DueDateFormatter.resolve(task.getEffectiveDueDate(), task.getCreatedAt());
        return task.isEffectivelyCritical()
                || (due != null && !due.isAfter(LocalDate.now().plusDays(3)));
    }

    private static void sort(List<Task> tasks, SortMode sortMode) {
        switch (sortMode) {
            case DUE_DATE:
                Collections.sort(tasks, (a, b) -> {
                    LocalDate first = DueDateFormatter.resolve(a.getEffectiveDueDate(), a.getCreatedAt());
                    LocalDate second = DueDateFormatter.resolve(b.getEffectiveDueDate(), b.getCreatedAt());
                    if (first == null && second == null) return compareNewest(a, b);
                    if (first == null) return 1;
                    if (second == null) return -1;
                    return first.compareTo(second);
                });
                break;
            case GROUP:
                Collections.sort(tasks, (a, b) -> compareThenId(
                        safe(a.getOrigChatName()), safe(b.getOrigChatName()), a, b));
                break;
            case SENDER:
                Collections.sort(tasks, (a, b) -> compareThenId(
                        safe(a.getOrigSender()), safe(b.getOrigSender()), a, b));
                break;
            case CATEGORY:
                Collections.sort(tasks, (a, b) -> compareThenId(
                        a.getEffectiveCategory(), b.getEffectiveCategory(), a, b));
                break;
            case NEWEST:
                Collections.sort(tasks, TaskFilterSortEngine::compareNewest);
                break;
            case CRITICAL_FIRST:
            default:
                Collections.sort(tasks, TaskFilterSortEngine::compareNewest);
                Collections.sort(tasks,
                        (a, b) -> Boolean.compare(b.isEffectivelyCritical(), a.isEffectivelyCritical()));
                break;
        }
    }

    private static int compareThenId(String first, String second, Task a, Task b) {
        int comparison = first.compareToIgnoreCase(second);
        return comparison != 0 ? comparison : compareNewest(a, b);
    }

    private static int compareNewest(Task first, Task second) {
        Instant firstTime = parseInstant(first.getCreatedAt());
        Instant secondTime = parseInstant(second.getCreatedAt());
        int comparison = secondTime.compareTo(firstTime);
        if (comparison != 0) return comparison;
        return safe(second.getDocumentId()).compareTo(safe(first.getDocumentId()));
    }

    private static Instant parseInstant(String value) {
        try {
            return value == null ? Instant.EPOCH : Instant.parse(value);
        } catch (java.time.format.DateTimeParseException ignored) {
            return Instant.EPOCH;
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
