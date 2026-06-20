package com.wsapp.taskviewer.util;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
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
    private static final Pattern DMY = Pattern.compile("(\\d{1,2})/(\\d{1,2})/(\\d{4})");
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
        return relative(date, LocalDate.now());
    }

    /** Extract a concrete calendar date from an arbitrary string, or null. */
    public static LocalDate parseDate(String raw) {
        if (raw == null) return null;
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
