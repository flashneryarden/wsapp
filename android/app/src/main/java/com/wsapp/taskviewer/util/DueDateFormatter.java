package com.wsapp.taskviewer.util;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Formats a task's due date for display. The stored value is expected to be a
 * numeric calendar date (ISO yyyy-MM-dd), but older tasks may only carry a free
 * text hint (e.g. "tomorrow", "מחר 08/06/2026"). This helper resolves a concrete
 * {@link LocalDate} when possible and renders it relative to today:
 * Today / Tomorrow / Yesterday / weekday name (within a week) / dd/MM/yyyy.
 */
public final class DueDateFormatter {

    private DueDateFormatter() {}

    private static final Pattern ISO = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");
    private static final Pattern DMY = Pattern.compile("(\\d{1,2})[./](\\d{1,2})[./](\\d{4})");
    private static final Pattern DUE_MARKER =
            Pattern.compile("\\s*\\[\\s*due:\\s*([^\\]]*)\\]", Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter OUT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /**
     * Render the effective due value for display, anchoring relative words
     * (today/tomorrow) to the task's creation date when no explicit date is
     * present. Returns null when there is no due information.
     */
    public static String format(String raw, String createdAtIso) {
        if (raw == null || raw.trim().isEmpty()) return null;
        LocalDate date = parseDate(raw);
        if (date == null) date = resolveRelativeWord(raw, anchorDate(createdAtIso));
        if (date == null) return raw.trim();
        LocalDate today = LocalDate.now();
        if (date.isBefore(today)) return null; // hide past due dates
        return relative(date, today);
    }

    /** True when the value resolves to a concrete date strictly before today. */
    public static boolean isPast(String raw) {
        LocalDate date = parseDate(raw);
        return date != null && date.isBefore(LocalDate.now());
    }

    /**
     * Resolve the effective due value to a concrete {@link LocalDate}, anchoring
     * relative words (today/tomorrow) to the task's creation date. Returns null
     * when no date can be determined (e.g. free text or no due info).
     */
    public static LocalDate resolve(String raw, String createdAtIso) {
        if (raw == null || raw.trim().isEmpty()) return null;
        LocalDate date = parseDate(raw);
        if (date == null) date = resolveRelativeWord(raw, anchorDate(createdAtIso));
        return date;
    }

    /**
     * Returns a copy of the action items with any "[due: ...]" marker whose date
     * has already passed removed, or null if nothing changed. Items that become
     * empty after removal are dropped. Relative words are anchored to the task's
     * creation date.
     */
    public static List<String> stripPastDueMarkers(List<String> items, String createdAtIso) {
        if (items == null) return null;
        LocalDate today = LocalDate.now();
        LocalDate anchor = anchorDate(createdAtIso);
        List<String> out = new ArrayList<>();
        boolean changed = false;
        for (String item : items) {
            if (item == null) {
                out.add(null);
                continue;
            }
            Matcher m = DUE_MARKER.matcher(item);
            StringBuffer sb = new StringBuffer();
            boolean itemChanged = false;
            while (m.find()) {
                String inner = m.group(1);
                LocalDate d = parseDate(inner);
                if (d == null) d = resolveRelativeWord(inner, anchor);
                if (d != null && d.isBefore(today)) {
                    m.appendReplacement(sb, "");
                    itemChanged = true;
                } else {
                    m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
                }
            }
            m.appendTail(sb);
            String cleaned = itemChanged ? sb.toString().replaceAll("\\s{2,}", " ").trim() : item;
            if (itemChanged) changed = true;
            if (!cleaned.isEmpty()) {
                out.add(cleaned);
            } else {
                changed = true; // drop now-empty action item
            }
        }
        return changed ? out : null;
    }

    /** Extract a concrete calendar date from an arbitrary string, or null. */
    public static LocalDate parseDate(String raw) {        if (raw == null) return null;
        Matcher m = ISO.matcher(raw);
        if (m.find()) {
            LocalDate d = safeDate(num(m, 1), num(m, 2), num(m, 3));
            if (d != null) return d;
        }
        m = DMY.matcher(raw);
        if (m.find()) {
            LocalDate d = safeDate(num(m, 3), num(m, 2), num(m, 1));
            if (d != null) return d;
        }
        return null;
    }

    private static String relative(LocalDate date, LocalDate today) {
        long diff = ChronoUnit.DAYS.between(today, date);
        if (diff == 0) return "Today";
        if (diff == 1) return "Tomorrow";
        if (diff == -1) return "Yesterday";
        if (diff > 1 && diff <= 6) {
            return date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.US);
        }
        return date.format(OUT);
    }

    private static LocalDate resolveRelativeWord(String raw, LocalDate anchor) {
        String s = raw.toLowerCase(Locale.ROOT);
        if (s.contains("today") || raw.contains("היום")) return anchor;
        if (s.contains("tomorrow") || raw.contains("מחר")) return anchor.plusDays(1);
        if (s.contains("yesterday") || raw.contains("אתמול")) return anchor.minusDays(1);
        return null;
    }

    private static LocalDate anchorDate(String createdAtIso) {
        if (createdAtIso != null && createdAtIso.length() >= 10) {
            try {
                return LocalDate.parse(createdAtIso.substring(0, 10));
            } catch (Exception ignored) {
                // fall through
            }
        }
        return LocalDate.now();
    }

    private static int num(Matcher m, int group) {
        return Integer.parseInt(m.group(group));
    }

    private static LocalDate safeDate(int year, int month, int day) {
        try {
            return LocalDate.of(year, month, day);
        } catch (Exception e) {
            return null;
        }
    }
}
