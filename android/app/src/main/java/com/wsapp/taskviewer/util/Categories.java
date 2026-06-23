package com.wsapp.taskviewer.util;

import java.util.Locale;

/**
 * Task categories and helpers. Categories are stored on the task as a lowercase
 * string key ({@link #SCHOOL}, {@link #FAMILY}, {@link #FRIENDS}, {@link #OTHER}).
 * When a task has no stored category (e.g. created by an older backend), a light
 * heuristic over the group name, sender and text suggests one.
 */
public final class Categories {

    private Categories() {}

    public static final String SCHOOL = "school";
    public static final String FAMILY = "family";
    public static final String FRIENDS = "friends";
    public static final String OTHER = "other";

    /** Canonical keys in display order. */
    public static final String[] KEYS = {SCHOOL, FAMILY, FRIENDS, OTHER};

    /** Human-readable label for a category key. */
    public static String label(String key) {
        if (key == null) return "Other";
        switch (key) {
            case SCHOOL: return "School";
            case FAMILY: return "Family";
            case FRIENDS: return "Friends";
            default: return "Other";
        }
    }

    /** Badge/accent color (ARGB int) for a category key. */
    public static int color(String key) {
        if (key == null) return 0xFF757575;
        switch (key) {
            case SCHOOL: return 0xFF1976D2;  // blue
            case FAMILY: return 0xFF388E3C;  // green
            case FRIENDS: return 0xFFF57C00; // orange
            default: return 0xFF757575;      // gray
        }
    }

    /**
     * Coerce an arbitrary stored value to a known category key, or null when the
     * value is blank/missing (so callers can fall back to the heuristic).
     */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return null;
        if (s.contains("school") || s.contains("study") || s.contains("studies")) return SCHOOL;
        if (s.contains("family")) return FAMILY;
        if (s.contains("friend")) return FRIENDS;
        if (s.equals(OTHER)) return OTHER;
        return OTHER;
    }

    private static final String[] SCHOOL_HINTS = {
        "school", "study", "studies", "homework", "exam", "class", "teacher", "lesson",
        "בית ספר", "ביה\"ס", "שיעור", "מבחן", "בחינה", "כיתה", "מורה", "תלמיד", "לימוד",
        "שיעורי בית", "מטלה", "מבחנים", "לימודים", "אוניברסיטה", "מטלות"
    };
    private static final String[] FAMILY_HINTS = {
        "family", "mom", "dad", "grandma", "grandpa", "home",
        "משפח", "אמא", "אבא", "סבא", "סבתא", "אח ", "אחות", "דוד", "דודה", "בית"
    };
    private static final String[] FRIENDS_HINTS = {
        "friend", "party", "hangout", "buddy",
        "חבר", "חברים", "חברה", "מסיבה", "בילוי", "ביחד"
    };

    /**
     * Best-effort category guess from a task's metadata and content. Used only
     * when the task has no stored category. Falls back to {@link #OTHER}.
     */
    public static String classify(String... fields) {
        StringBuilder sb = new StringBuilder();
        for (String f : fields) {
            if (f != null) sb.append(f).append(' ');
        }
        String hay = sb.toString().toLowerCase(Locale.ROOT);
        if (containsAny(hay, SCHOOL_HINTS)) return SCHOOL;
        if (containsAny(hay, FAMILY_HINTS)) return FAMILY;
        if (containsAny(hay, FRIENDS_HINTS)) return FRIENDS;
        return OTHER;
    }

    private static boolean containsAny(String hay, String[] needles) {
        for (String n : needles) {
            if (hay.contains(n.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }
}
