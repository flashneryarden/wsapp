package com.wsapp.taskviewer.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Task model matching the Firestore document schema.
 */
public class Task {
    private int id;
    private String origSender;
    private String origChatName;
    private String text;
    private String summary;
    private List<String> actionItems;
    private String createdAt;
    private String status; // "pending" or "done"
    private String completedAt;
    private List<String> notes;

    public Task() {
        actionItems = new ArrayList<>();
        notes = new ArrayList<>();
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getOrigSender() { return origSender; }
    public void setOrigSender(String origSender) { this.origSender = origSender; }

    public String getOrigChatName() { return origChatName; }
    public void setOrigChatName(String origChatName) { this.origChatName = origChatName; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public List<String> getActionItems() { return actionItems; }
    public void setActionItems(List<String> actionItems) { this.actionItems = actionItems; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getCompletedAt() { return completedAt; }
    public void setCompletedAt(String completedAt) { this.completedAt = completedAt; }

    public List<String> getNotes() { return notes; }
    public void setNotes(List<String> notes) { this.notes = notes; }

    public boolean isPending() {
        return "pending".equals(status);
    }

    public boolean isDone() {
        return "done".equals(status);
    }
}
