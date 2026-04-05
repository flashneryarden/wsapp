import fs from "fs";
import path from "path";
import cron from "node-cron";
import type { Reminder } from "./types.js";
import { getTask, listTasks, formatTaskList } from "./tasks.js";

const REMINDERS_PATH = path.join(process.cwd(), "config", "reminders.json");

let reminders: Reminder[] = [];
const activeTimers = new Map<number, ReturnType<typeof setTimeout>>();

export function loadReminders(): void {
  try {
    reminders = JSON.parse(fs.readFileSync(REMINDERS_PATH, "utf-8"));
  } catch {
    reminders = [];
  }
}

function saveReminders(): void {
  fs.writeFileSync(REMINDERS_PATH, JSON.stringify(reminders, null, 2));
}

function nextId(): number {
  if (reminders.length === 0) return 1;
  return Math.max(...reminders.map((r) => r.id)) + 1;
}

export function addReminder(taskId: number, triggerAt: Date): Reminder | null {
  const task = getTask(taskId);
  if (!task) return null;
  const reminder: Reminder = {
    id: nextId(),
    taskId,
    triggerAt: triggerAt.toISOString(),
    sent: false,
  };
  reminders.push(reminder);
  saveReminders();
  return reminder;
}

export function deleteReminder(id: number): boolean {
  const idx = reminders.findIndex((r) => r.id === id);
  if (idx === -1) return false;
  const timer = activeTimers.get(id);
  if (timer) {
    clearTimeout(timer);
    activeTimers.delete(id);
  }
  reminders.splice(idx, 1);
  saveReminders();
  return true;
}

export function listPendingReminders(): Reminder[] {
  return reminders.filter((r) => !r.sent);
}

export function formatReminderList(items: Reminder[]): string {
  if (items.length === 0) return "⏰ No pending reminders.";
  const lines = ["⏰ *Pending Reminders*", ""];
  for (const r of items) {
    const task = getTask(r.taskId);
    const taskLabel = task ? task.summary : `(task #${r.taskId} not found)`;
    const time = new Date(r.triggerAt).toLocaleString();
    lines.push(`#${r.id} → Task #${r.taskId}: ${taskLabel}`);
    lines.push(`   Fires at: ${time}`);
    lines.push("");
  }
  return lines.join("\n");
}

// --- Time parsing ---

export function parseTimeSpec(spec: string): Date | null {
  const now = new Date();
  const lower = spec.toLowerCase().trim();

  // Relative: 30m, 2h, 1d
  const relMatch = lower.match(/^(\d+)\s*(m|min|mins|minutes?|h|hr|hrs|hours?|d|days?)$/);
  if (relMatch) {
    const amount = parseInt(relMatch[1]!, 10);
    const unit = relMatch[2]!.charAt(0);
    const ms = unit === "m" ? amount * 60_000
             : unit === "h" ? amount * 3_600_000
             : amount * 86_400_000;
    return new Date(now.getTime() + ms);
  }

  // "tomorrow"
  if (lower === "tomorrow") {
    const d = new Date(now);
    d.setDate(d.getDate() + 1);
    d.setHours(8, 0, 0, 0);
    return d;
  }

  return null;
}

/**
 * Parse `[due: ...]` from action items and return a trigger time.
 * - If a specific time is found (e.g., "14:00"), returns 5 minutes before.
 * - If only a day name/date, returns 08:00 on that day.
 * - Returns null if unparseable.
 */
export function parseDueDate(dueStr: string): Date | null {
  const lower = dueStr.toLowerCase().trim();

  // Try to extract a time like HH:MM
  const timeMatch = lower.match(/(\d{1,2}):(\d{2})/);

  // Day names
  const dayNames = ["sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday"];
  const now = new Date();

  // "today" or "today HH:MM"
  if (lower.startsWith("today")) {
    const d = new Date(now);
    if (timeMatch) {
      d.setHours(parseInt(timeMatch[1]!, 10), parseInt(timeMatch[2]!, 10), 0, 0);
      return new Date(d.getTime() - 5 * 60_000); // 5 min before
    }
    d.setHours(8, 0, 0, 0);
    return d;
  }

  // "tomorrow" or "tomorrow HH:MM"
  if (lower.startsWith("tomorrow")) {
    const d = new Date(now);
    d.setDate(d.getDate() + 1);
    if (timeMatch) {
      d.setHours(parseInt(timeMatch[1]!, 10), parseInt(timeMatch[2]!, 10), 0, 0);
      return new Date(d.getTime() - 5 * 60_000);
    }
    d.setHours(8, 0, 0, 0);
    return d;
  }

  // Day name (e.g., "Friday", "Friday 14:00")
  const dayIdx = dayNames.findIndex((d) => lower.startsWith(d));
  if (dayIdx !== -1) {
    const d = new Date(now);
    const currentDay = d.getDay();
    let daysAhead = dayIdx - currentDay;
    if (daysAhead <= 0) daysAhead += 7;
    d.setDate(d.getDate() + daysAhead);
    if (timeMatch) {
      d.setHours(parseInt(timeMatch[1]!, 10), parseInt(timeMatch[2]!, 10), 0, 0);
      return new Date(d.getTime() - 5 * 60_000);
    }
    d.setHours(8, 0, 0, 0);
    return d;
  }

  // Just a time today (e.g., "14:00")
  if (timeMatch && lower.replace(/\s/g, "").match(/^\d{1,2}:\d{2}$/)) {
    const d = new Date(now);
    d.setHours(parseInt(timeMatch[1]!, 10), parseInt(timeMatch[2]!, 10), 0, 0);
    if (d.getTime() <= now.getTime()) {
      d.setDate(d.getDate() + 1); // if time already passed, use tomorrow
    }
    return new Date(d.getTime() - 5 * 60_000);
  }

  // Try ISO-ish date: "2026-03-25", "2026-03-25 14:00"
  const dateMatch = lower.match(/(\d{4}-\d{2}-\d{2})/);
  if (dateMatch) {
    const d = new Date(dateMatch[1]! + "T08:00:00");
    if (timeMatch) {
      d.setHours(parseInt(timeMatch[1]!, 10), parseInt(timeMatch[2]!, 10), 0, 0);
      return new Date(d.getTime() - 5 * 60_000);
    }
    return d;
  }

  return null;
}

/**
 * Extract due date string from an action item's [due: ...] annotation.
 */
export function extractDueFromActionItem(item: string): string | null {
  const match = item.match(/\[due:\s*([^\]]+)\]/i);
  return match ? match[1]!.trim() : null;
}

// --- Scheduling ---

let sendFn: ((text: string) => Promise<void>) | null = null;
let digestTask: cron.ScheduledTask | null = null;

function scheduleReminder(reminder: Reminder): void {
  const delay = new Date(reminder.triggerAt).getTime() - Date.now();
  const fireDelay = Math.max(delay, 0); // fire immediately if past due

  const timer = setTimeout(async () => {
    activeTimers.delete(reminder.id);
    reminder.sent = true;
    saveReminders();

    const task = getTask(reminder.taskId);
    if (!task || task.status === "done") return;

    const text = [
      "⏰ *Reminder*",
      `Task #${task.id}: ${task.summary}`,
      `From: ${task.origSender} (${task.origChatName})`,
      task.actionItems.length > 0
        ? task.actionItems.map((a) => `  • ${a}`).join("\n")
        : "",
    ].filter(Boolean).join("\n");

    if (sendFn) {
      try {
        await sendFn(text);
        console.log(`\x1b[90m  ⏰ Reminder fired for task #${task.id}\x1b[0m`);
      } catch (err) {
        console.log(`\x1b[90m  ⏰ Reminder send failed: ${(err as Error).message}\x1b[0m`);
      }
    }
  }, fireDelay);

  activeTimers.set(reminder.id, timer);
}

export function startReminderLoop(send: (text: string) => Promise<void>): void {
  sendFn = send;
  for (const r of reminders) {
    if (!r.sent) {
      scheduleReminder(r);
    }
  }
  console.log(`\x1b[90m  ⏰ ${listPendingReminders().length} reminder(s) armed\x1b[0m`);
}

export function startDailyDigest(send: (text: string) => Promise<void>): void {
  digestTask = cron.schedule("0 8 * * *", async () => {
    const pending = listTasks("pending");
    if (pending.length === 0) return;
    const text = formatTaskList(pending, "📋 *Daily Digest — Pending Tasks*");
    try {
      await send(text);
      console.log(`\x1b[90m  📋 Daily digest sent (${pending.length} tasks)\x1b[0m`);
    } catch (err) {
      console.log(`\x1b[90m  📋 Daily digest failed: ${(err as Error).message}\x1b[0m`);
    }
  });
}

export function stopReminders(): void {
  for (const timer of activeTimers.values()) {
    clearTimeout(timer);
  }
  activeTimers.clear();
  if (digestTask) {
    digestTask.stop();
    digestTask = null;
  }
}

/**
 * Schedule a newly created reminder (call after addReminder).
 */
export function scheduleNewReminder(reminder: Reminder): void {
  scheduleReminder(reminder);
}
