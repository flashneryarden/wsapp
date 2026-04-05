import { initializeApp, cert, type ServiceAccount } from "firebase-admin/app";
import { getFirestore, type Firestore } from "firebase-admin/firestore";
import fs from "fs";
import path from "path";
import type { Task } from "./types.js";

const SERVICE_ACCOUNT_PATH = path.join(process.cwd(), "config", "firebase-service-account.json");
const COLLECTION = "tasks";

let db: Firestore | null = null;

export function initFirestore(): boolean {
  try {
    if (!fs.existsSync(SERVICE_ACCOUNT_PATH)) {
      console.log("\x1b[33m⚠ Firebase service account not found — Firestore sync disabled\x1b[0m");
      return false;
    }
    const serviceAccount = JSON.parse(fs.readFileSync(SERVICE_ACCOUNT_PATH, "utf-8")) as ServiceAccount;
    initializeApp({ credential: cert(serviceAccount) });
    db = getFirestore();
    console.log("\x1b[32m✓ Firestore connected\x1b[0m");
    return true;
  } catch (err) {
    console.log(`\x1b[33m⚠ Firestore init failed: ${(err as Error).message}\x1b[0m`);
    return false;
  }
}

export function isFirestoreActive(): boolean {
  return db !== null;
}

export async function syncTaskToFirestore(task: Task): Promise<void> {
  if (!db) return;
  try {
    await db.collection(COLLECTION).doc(String(task.id)).set(task);
  } catch (err) {
    console.log(`\x1b[33m⚠ Firestore sync failed for task #${task.id}: ${(err as Error).message}\x1b[0m`);
  }
}

export async function deleteTaskFromFirestore(id: number): Promise<void> {
  if (!db) return;
  try {
    await db.collection(COLLECTION).doc(String(id)).delete();
  } catch (err) {
    console.log(`\x1b[33m⚠ Firestore delete failed for task #${id}: ${(err as Error).message}\x1b[0m`);
  }
}

export async function uploadAllTasks(tasks: Task[]): Promise<number> {
  if (!db) return 0;
  const batch = db.batch();
  for (const task of tasks) {
    batch.set(db.collection(COLLECTION).doc(String(task.id)), task);
  }
  await batch.commit();
  return tasks.length;
}
