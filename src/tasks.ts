import fs from "fs";
import path from "path";
import type { Task } from "./types.js";
import type { AnalysisResult } from "./ai.js";
import { syncTaskToFirestore, deleteTaskFromFirestore } from "./firestore.js";

const TASKS_PATH = path.join(process.cwd(), "config", "tasks.json");

let tasks: Task[] = [];

export function loadTasks(): void {
  try {
    tasks = JSON.parse(fs.readFileSync(TASKS_PATH, "utf-8"));
  } catch {
    tasks = [];
  }
}

function saveTasks(): void {
  fs.writeFileSync(TASKS_PATH, JSON.stringify(tasks, null, 2));
}

function nextId(): number {
  if (tasks.length === 0) return 1;
  return Math.max(...tasks.map((t) => t.id)) + 1;
}

export function addTask(
  analysis: AnalysisResult,
  sender: string,
  chatName: string,
  text: string,
): Task {
  const task: Task = {
    id: nextId(),
    origSender: sender,
    origChatName: chatName,
    text,
    summary: analysis.summary,
    actionItems: analysis.actionItems,
    createdAt: new Date().toISOString(),
    status: "pending",
    completedAt: null,
    notes: [],
    critical: analysis.isCritical,
  };
  tasks.push(task);
  saveTasks();
  syncTaskToFirestore(task);
  return task;
}

export function getTask(id: number): Task | undefined {
  return tasks.find((t) => t.id === id);
}

export function listTasks(filter?: "pending" | "done"): Task[] {
  if (filter) return tasks.filter((t) => t.status === filter);
  return [...tasks];
}

export function listTodayTasks(): Task[] {
  const today = new Date().toISOString().slice(0, 10);
  return tasks.filter((t) => t.createdAt.slice(0, 10) === today);
}

export function completeTask(id: number): Task | null {
  const task = tasks.find((t) => t.id === id);
  if (!task) return null;
  task.status = "done";
  task.completedAt = new Date().toISOString();
  saveTasks();
  syncTaskToFirestore(task);
  return task;
}

export function deleteTasks(ids: number[]): { deleted: number[]; notFound: number[] } {
  const deleted: number[] = [];
  const notFound: number[] = [];
  for (const id of ids) {
    const idx = tasks.findIndex((t) => t.id === id);
    if (idx === -1) {
      notFound.push(id);
    } else {
      tasks.splice(idx, 1);
      deleted.push(id);
    }
  }
  if (deleted.length > 0) {
    saveTasks();
    for (const id of deleted) deleteTaskFromFirestore(id);
  }
  return { deleted, notFound };
}

export function addNote(id: number, note: string): Task | null {
  const task = tasks.find((t) => t.id === id);
  if (!task) return null;
  task.notes.push(note);
  saveTasks();
  syncTaskToFirestore(task);
  return task;
}

export function addManualTask(text: string): Task {
  const task: Task = {
    id: nextId(),
    origSender: "manual",
    origChatName: "manual",
    text,
    summary: text,
    actionItems: [],
    createdAt: new Date().toISOString(),
    status: "pending",
    completedAt: null,
    notes: [],
    critical: false,
  };
  tasks.push(task);
  saveTasks();
  syncTaskToFirestore(task);
  return task;
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleString();
}

function statusIcon(status: string): string {
  return status === "done" ? "✅" : "⏳";
}

export function formatTaskList(tasks: Task[], title: string): string {
  if (tasks.length === 0) return `${title}\nNo tasks found.`;
  const lines = [title, ""];
  for (const t of tasks) {
    const icon = statusIcon(t.status);
    const flag = t.critical ? "🔴 " : "";
    lines.push(`${icon} ${flag}#${t.id} — ${t.summary}`);
    lines.push(`   From: ${t.origSender} (${t.origChatName})`);
    lines.push(`   Created: ${formatDate(t.createdAt)}`);
    if (t.actionItems.length > 0) {
      for (const a of t.actionItems) {
        lines.push(`   • ${a}`);
      }
    }
    lines.push("");
  }
  return lines.join("\n");
}

export function formatTaskDetail(task: Task): string {
  const icon = statusIcon(task.status);
  const lines = [
    `${icon} Task #${task.id}`,
    `Status: ${task.status}`,
    `From: ${task.origSender} (${task.origChatName})`,
    `Created: ${formatDate(task.createdAt)}`,
    task.completedAt ? `Completed: ${formatDate(task.completedAt)}` : "",
    ``,
    `*Message:* ${task.text}`,
    `*Summary:* ${task.summary}`,
  ].filter((l) => l !== "");

  if (task.actionItems.length > 0) {
    lines.push("*Action Items:*");
    for (const a of task.actionItems) {
      lines.push(`  • ${a}`);
    }
  }

  if (task.notes.length > 0) {
    lines.push("*Notes:*");
    for (const n of task.notes) {
      lines.push(`  - ${n}`);
    }
  }

  return lines.join("\n");
}
