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
    private Boolean critical;
    private String category;
    private String dueDate;

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

    // Stored criticality flag from the backend AI (may be null for older tasks).
    public Boolean getCritical() { return critical; }
    public void setCritical(Boolean critical) { this.critical = critical; }

    public String getDueDate() { return dueDate; }
    public void setDueDate(String dueDate) { this.dueDate = dueDate; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    /**
     * Category for display/filtering. Uses the stored category when present;
     * otherwise falls back to a heuristic over the group, sender and content so
     * tasks created before this feature still get a sensible category.
     */
    public String getEffectiveCategory() {
        String c = com.wsapp.taskviewer.util.Categories.normalize(category);
        if (c != null) return c;
        StringBuilder items = new StringBuilder();
        if (actionItems != null) {
            for (String a : actionItems) items.append(a).append(' ');
        }
        return com.wsapp.taskviewer.util.Categories.classify(
                origChatName, origSender, summary, text, items.toString());
    }

    private static final java.util.regex.Pattern DUE_PATTERN =
            java.util.regex.Pattern.compile("due:\\s*([^\\]\\n]+)", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * Due date for display. Uses the structured dueDate field when set; otherwise
     * falls back to a "[due: ...]" hint embedded in an action item (as produced by
     * older backend versions), so existing tasks still show a due date.
     */
    public String getEffectiveDueDate() {
        if (dueDate != null && !dueDate.trim().isEmpty()) return dueDate;
        if (actionItems != null) {
            for (String item : actionItems) {
                if (item == null) continue;
                java.util.regex.Matcher m = DUE_PATTERN.matcher(item);
                if (m.find()) {
                    String due = m.group(1).trim();
                    if (due.endsWith("]")) due = due.substring(0, due.length() - 1).trim();
                    if (!due.isEmpty()) return due;
                }
            }
        }
        return null;
    }

    /**
     * Effective criticality used for sorting and display. Uses the stored flag
     * when present; otherwise falls back to a keyword heuristic so tasks created
     * before this feature still get classified.
     */
    public boolean isEffectivelyCritical() {
        if (critical != null) return critical;
        return matchesCriticalKeywords();
    }

    private static final String[] CRITICAL_KEYWORDS = {
        // English
        "urgent", "asap", "immediately", "emergency", "critical", "deadline",
        "right now", "important!", "!!!",
        // Hebrew
        "דחוף", "מיידי", "מיד", "חירום", "קריטי", "סכנה", "עכשיו", "חשוב מאוד",
        "בהול", "אסון"
    };

    private boolean matchesCriticalKeywords() {
        StringBuilder sb = new StringBuilder();
        if (summary != null) sb.append(summary).append(' ');
        if (text != null) sb.append(text).append(' ');
        if (actionItems != null) {
            for (String a : actionItems) sb.append(a).append(' ');
        }
        String haystack = sb.toString().toLowerCase();
        for (String kw : CRITICAL_KEYWORDS) {
            if (haystack.contains(kw.toLowerCase())) return true;
        }
        return false;
    }

    public boolean isPending() {
        return "pending".equals(status);
    }

    public boolean isDone() {
        return "done".equals(status);
    }
}
