import assert from "node:assert/strict";
import { beforeEach, mock, test } from "node:test";

let registrations;
let writes;
let updates;
let deleted;
let sends;
let topicSends;
let responses;
let failUpdates;

const db = {
  collection(name) {
    return {
      async get() {
        assert.equal(name, "devices");
        return { docs: registrations };
      },
      doc(id) {
        return {
          async set(data, options) {
            writes.push({ name, id, data, options });
          },
          async update(data) {
            if (failUpdates) throw new Error("Link write failed");
            updates.push({ name, id, data });
          },
        };
      },
    };
  },
  batch() {
    return {
      set(ref, data, options) {
        writes.push({ data, options });
      },
      async commit() {},
    };
  },
};

mock.module("firebase-admin/app", {
  namedExports: { initializeApp() {}, cert: (value) => value },
});
mock.module("firebase-admin/firestore", {
  namedExports: {
    getFirestore: () => db,
    FieldValue: { arrayUnion: (...ids) => ({ arrayUnion: ids }) },
  },
});
mock.module("firebase-admin/messaging", {
  namedExports: {
    getMessaging: () => ({
      async sendEachForMulticast(message) {
        sends.push(message);
        const results = responses.splice(0, message.tokens.length);
        return {
          responses: results,
          failureCount: results.filter((result) => !result.success).length,
        };
      },
      async send(message) {
        topicSends.push(message);
      },
    }),
  },
});
mock.module("fs", {
  defaultExport: {
    existsSync: () => true,
    readFileSync: () => "{}",
  },
});

const { initFirestore, publishNewTaskToFirestore, syncTaskToFirestore, uploadAllTasks } =
  await import("../dist/firestore.js");

const task = {
  firestoreId: "task-uuid",
  id: 1,
  origSender: "Sender",
  origChatName: "Group",
  summary: "Task",
  text: "Task text",
  critical: true,
};

function device(id, token = `token-${id}`) {
  return {
    id,
    get: () => token,
    ref: { async delete() { deleted.push(id); } },
  };
}

beforeEach(() => {
  registrations = [];
  writes = [];
  updates = [];
  deleted = [];
  sends = [];
  topicSends = [];
  responses = [];
  failUpdates = false;
  initFirestore();
});

test("records only FCM-accepted device IDs and still removes invalid registrations", async () => {
  registrations = [device("one"), device("two"), device("three"), device("empty", "")];
  responses = [
    { success: true },
    { success: false, error: { code: "messaging/invalid-registration-token", message: "Invalid" } },
    { success: false, error: { code: "messaging/internal-error", message: "Retry" } },
  ];

  await publishNewTaskToFirestore(task);

  assert.deepEqual(sends[0].tokens, ["token-one", "token-two", "token-three"]);
  assert.deepEqual(updates, [{
    name: "tasks",
    id: task.firestoreId,
    data: { notifiedDeviceIds: { arrayUnion: ["one"] } },
  }]);
  assert.deepEqual(deleted, ["two"]);
  assert.equal(topicSends.length, 0);
});

test("records links for each multicast batch without changing the broadcast audience", async () => {
  registrations = Array.from({ length: 501 }, (_, i) => device(`device-${i}`));
  responses = registrations.map(() => ({ success: true }));

  await publishNewTaskToFirestore(task);

  assert.deepEqual(sends.map((message) => message.tokens.length), [500, 1]);
  assert.equal(updates[0].data.notifiedDeviceIds.arrayUnion.length, 500);
  assert.deepEqual(updates[1].data.notifiedDeviceIds.arrayUnion, ["device-500"]);
});

test("topic fallback and non-critical tasks do not invent device links", async () => {
  await publishNewTaskToFirestore(task);
  assert.equal(topicSends.length, 1);
  assert.deepEqual(updates, []);

  registrations = [device("one")];
  await publishNewTaskToFirestore({ ...task, critical: false });
  assert.equal(sends.length, 0);
  assert.deepEqual(updates, []);
});

test("task writes merge so later sync and bulk uploads preserve relationship fields", async () => {
  await publishNewTaskToFirestore({ ...task, critical: false });
  await syncTaskToFirestore(task);
  assert.equal(await uploadAllTasks([task]), 1);

  assert.equal(writes.length, 3);
  for (const write of writes) {
    assert.deepEqual(write.options, { merge: true });
    assert.equal("notifiedDeviceIds" in write.data, false);
    assert.equal("firestoreId" in write.data, false);
  }
});

test("link-write failures are reported without preventing stale-device cleanup", async () => {
  registrations = [device("one"), device("two")];
  responses = [
    { success: true },
    { success: false, error: { code: "messaging/invalid-registration-token", message: "Invalid" } },
  ];
  failUpdates = true;
  const log = mock.method(console, "log", () => {});
  try {
    await publishNewTaskToFirestore(task);
    assert.ok(log.mock.calls.some(({ arguments: args }) =>
      args[0].includes("Notification-device links failed")));
    assert.deepEqual(deleted, ["two"]);
  } finally {
    log.mock.restore();
  }
});
