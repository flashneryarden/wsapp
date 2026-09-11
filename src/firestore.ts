import { initializeApp, cert, type ServiceAccount } from "firebase-admin/app";
import { getFirestore, type Firestore } from "firebase-admin/firestore";
import { getMessaging } from "firebase-admin/messaging";
import fs from "fs";
import path from "path";
import type { Task } from "./types.js";

const SERVICE_ACCOUNT_PATH = path.join(process.cwd(), "config", "firebase-service-account.json");
const COLLECTION = "tasks";
const SKIPPED_GROUPS_COLLECTION = "skippedGroups";
const DEVICES_COLLECTION = "devices";
const CRITICAL_TASKS_TOPIC = "critical_tasks";

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

export async function publishNewTaskToFirestore(task: Task): Promise<void> {
  if (!db) return;

  try {
    await db.collection(COLLECTION).doc(String(task.id)).set(task);
  } catch (err) {
    console.log(`\x1b[33m⚠ Firestore sync failed for task #${task.id}: ${(err as Error).message}\x1b[0m`);
    return;
  }

  if (!task.critical) return;

  try {
    const devices = await db.collection(DEVICES_COLLECTION).get();
    const registrations = devices.docs
      .map((doc) => ({ doc, token: doc.get("token") }))
      .filter((entry): entry is { doc: FirebaseFirestore.QueryDocumentSnapshot; token: string } =>
        typeof entry.token === "string" && entry.token.length > 0,
      );

    for (let start = 0; start < registrations.length; start += 500) {
      const batch = registrations.slice(start, start + 500);
      const response = await getMessaging().sendEachForMulticast({
        tokens: batch.map((entry) => entry.token),
        notification: {
          title: "Critical task received",
          body: task.summary || task.text,
        },
        data: {
          taskId: String(task.id),
          summary: task.summary || task.text,
          source: `${task.origSender} (${task.origChatName})`,
        },
        android: {
          priority: "high",
          notification: {
            channelId: "critical_tasks",
            visibility: "public",
            sound: "default",
            defaultVibrateTimings: true,
          },
        },
        apns: {
          headers: {
            "apns-priority": "10",
          },
          payload: {
            aps: {
              sound: "default",
              interruptionLevel: "time-sensitive",
            },
          },
        },
      });

      const staleDocs = response.responses
        .map((result, index) => ({ result, doc: batch[index]!.doc }))
        .filter(({ result }) =>
          result.error?.code === "messaging/registration-token-not-registered"
          || result.error?.code === "messaging/invalid-registration-token",
        )
        .map(({ doc }) => doc.ref.delete());

      await Promise.all(staleDocs);

      if (response.failureCount > 0) {
        const errors = response.responses
          .filter((result) => !result.success)
          .map((result) => result.error?.message ?? "unknown FCM error");
        console.log(`\x1b[33m⚠ Critical-task notification had ${response.failureCount} failure(s): ${errors.join("; ")}\x1b[0m`);
      }
    }

    if (registrations.length === 0) {
      await getMessaging().send({
        topic: CRITICAL_TASKS_TOPIC,
        notification: {
          title: "Critical task received",
          body: task.summary || task.text,
        },
        data: {
          taskId: String(task.id),
          summary: task.summary || task.text,
          source: `${task.origSender} (${task.origChatName})`,
        },
        android: {
          priority: "high",
          notification: {
            channelId: "critical_tasks",
            visibility: "public",
            sound: "default",
            defaultVibrateTimings: true,
          },
        },
        apns: {
          headers: {
            "apns-priority": "10",
          },
          payload: {
            aps: {
              sound: "default",
              interruptionLevel: "time-sensitive",
            },
          },
        },
      });
    }
  } catch (err) {
    console.log(`\x1b[33m⚠ Critical-task notification failed for task #${task.id}: ${(err as Error).message}\x1b[0m`);
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

/**
 * Listen for changes to the `skippedGroups` collection (populated by the Android
 * app). Invokes `onChange` with the current list of skipped group names whenever
 * the collection changes (including once on startup). One-way Firestore → backend.
 */
export function listenSkippedGroups(onChange: (names: string[]) => void): void {
  if (!db) return;
  db.collection(SKIPPED_GROUPS_COLLECTION).onSnapshot(
    (snap) => {
      const names = snap.docs
        .map((d) => (d.data() as { name?: string }).name)
        .filter((n): n is string => typeof n === "string" && n.length > 0);
      onChange(names);
    },
    (err) => {
      console.log(`\x1b[33m⚠ skippedGroups listener error: ${err.message}\x1b[0m`);
    },
  );
}
