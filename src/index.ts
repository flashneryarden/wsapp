import readline from "readline";
import fs from "fs";
import path from "path";
import { WhatsAppClient } from "./whatsapp.js";
import { analyzeMessage, chatWithCopilot, resetChatSession, isAIActive, stopAI } from "./ai.js";
import { loadTasks, addTask, addManualTask, getTask, listTasks, listTodayTasks, completeTask, deleteTasks, addNote, formatTaskList, formatTaskDetail } from "./tasks.js";
import { loadReminders, addReminder, deleteReminder, listPendingReminders, formatReminderList, parseTimeSpec, parseDueDate, extractDueFromActionItem, startReminderLoop, startDailyDigest, stopReminders, scheduleNewReminder } from "./reminders.js";
import { initFirestore, uploadAllTasks, listenSkippedGroups } from "./firestore.js";

const startTime = Date.now();
const MESSAGE_LOG_PATH = path.join(process.cwd(), "messages.log");

function logMessageToFile(msg: { timestamp: number; chatId: string; sender: string; senderName: string; isFromMe: boolean; text: string; isGroup: boolean }) {
  const time = new Date(msg.timestamp * 1000).toLocaleString();
  const sender = msg.isFromMe ? "You" : (msg.senderName || msg.sender);
  const line = `[${time}] ${sender}: ${msg.text}\n`;
  fs.appendFileSync(MESSAGE_LOG_PATH, line);
}

const wa = new WhatsAppClient({
  log: (...args) => {
    // Clear current prompt line, print log, re-show prompt
    process.stderr.write(`\r\x1b[K\x1b[90m${args.map(String).join(" ")}\x1b[0m\n`);
  },
});

// Auto-analyze incoming real-time messages
const FORWARD_GROUP_NAME = "ירדן החשוב";
const FORWARD_GROUP_JID = "120363423446403589@g.us";
let forwardGroupId: string | null = FORWARD_GROUP_JID;
const botSentIds = new Set<string>();
let wsapGroupId: string | null = null;

// App settings (persisted to config/settings.json)
const SETTINGS_PATH = path.join(process.cwd(), "config", "settings.json");

interface AppSettings {
  forwardingEnabled: boolean;
}

function loadSettings(): AppSettings {
  try {
    return JSON.parse(fs.readFileSync(SETTINGS_PATH, "utf-8"));
  } catch {
    const defaults: AppSettings = { forwardingEnabled: false };
    fs.writeFileSync(SETTINGS_PATH, JSON.stringify(defaults, null, 2));
    return defaults;
  }
}

const settings = loadSettings();

// Skipped chat names — messages from these chats skip analysis/forwarding
const SKIP_LIST_PATH = path.join(process.cwd(), "config", "skip_list.json");
const skippedNames = new Set<string>(loadSkipList());

function loadSkipList(): string[] {
  try {
    return JSON.parse(fs.readFileSync(SKIP_LIST_PATH, "utf-8"));
  } catch {
    return [];
  }
}

function saveSkipList() {
  fs.writeFileSync(SKIP_LIST_PATH, JSON.stringify([...skippedNames], null, 2));
}

function isChatSkipped(chatId: string): boolean {
  const name = wa.getChatName(chatId);
  for (const skipped of skippedNames) {
    if (name.toLowerCase() === skipped.toLowerCase()) return true;
  }
  return false;
}

// Handle /skip command from incoming messages
async function handleSkipFromMessage(text: string): Promise<string> {
  const query = text.replace(/^\/skip\s*/i, "").trim();

  // No argument — list skipped chats
  if (!query) {
    if (skippedNames.size === 0) {
      return "No chats are currently skipped.\n\nUsage: /skip <chat name>";
    }
    const lines = ["*Skipped chats:*"];
    let i = 1;
    for (const name of skippedNames) {
      lines.push(`${i}. ${name}`);
      i++;
    }
    lines.push("", "Usage: /unskip <name or #> to remove");
    return lines.join("\n");
  }

  // Try numeric index
  const idx = parseInt(query, 10);
  if (!isNaN(idx) && idx > 0 && String(idx) === query) {
    const chats = wa.listChats(100);
    if (idx > chats.length) {
      return `Chat #${idx} not found. Only ${chats.length} chats available.`;
    }
    const chat = chats[idx - 1]!;
    skippedNames.add(chat.name);
    saveSkipList();
    return `⏭️ Skipping messages from: *${chat.name}*`;
  }

  // Try to resolve name from chat ID
  const chatName = wa.getChatName(query);
  if (chatName !== query) {
    skippedNames.add(chatName);
    saveSkipList();
    return `⏭️ Skipping messages from: *${chatName}*`;
  }

  // Try exact name match first, then substring
  const chats = wa.listChats(1000);
  const lower = query.toLowerCase();
  const exact = chats.find((c) => c.name.toLowerCase() === lower);
  if (exact) {
    skippedNames.add(exact.name);
    saveSkipList();
    return `⏭️ Skipping messages from: *${exact.name}*`;
  }

  const partial = chats.filter((c) => c.name.toLowerCase().includes(lower));
  if (partial.length === 1) {
    skippedNames.add(partial[0]!.name);
    saveSkipList();
    return `⏭️ Skipping messages from: *${partial[0]!.name}*`;
  }
  if (partial.length > 1) {
    const lines = [`Multiple chats match "${query}":`];
    partial.forEach((c, i) => lines.push(`${i + 1}. ${c.name}`));
    lines.push("", "Please use the exact name.");
    return lines.join("\n");
  }

  // Try fetching groups if nothing found in cache
  const found = await wa.findChatByNameWithFetch(query);
  if (found) {
    skippedNames.add(found.name);
    saveSkipList();
    return `⏭️ Skipping messages from: *${found.name}*`;
  }

  return `No chat found matching "${query}".`;
}

// Handle /unskip command from incoming messages
async function handleUnskipFromMessage(text: string): Promise<string> {
  const query = text.replace(/^\/unskip\s*/i, "").trim();

  // No argument — list skipped chats
  if (!query) {
    if (skippedNames.size === 0) {
      return "No chats are currently skipped.";
    }
    const lines = ["*Skipped chats:*"];
    let i = 1;
    for (const name of skippedNames) {
      lines.push(`${i}. ${name}`);
      i++;
    }
    lines.push("", "Usage: /unskip <name, # or all>");
    return lines.join("\n");
  }

  // "unskip all"
  if (query.toLowerCase() === "all") {
    const count = skippedNames.size;
    skippedNames.clear();
    saveSkipList();
    return `✅ Removed all ${count} skip(s)`;
  }

  // Try numeric index into the skipped list
  const idx = parseInt(query, 10);
  if (!isNaN(idx) && idx > 0 && String(idx) === query) {
    const names = [...skippedNames];
    if (idx > names.length) {
      return `Skip #${idx} not found. Only ${names.length} skipped chat(s).`;
    }
    const name = names[idx - 1]!;
    skippedNames.delete(name);
    saveSkipList();
    return `✅ Unskipped: *${name}*`;
  }

  // Try exact match
  if (skippedNames.has(query)) {
    skippedNames.delete(query);
    saveSkipList();
    return `✅ Unskipped: *${query}*`;
  }

  // Try case-insensitive / substring match against skipped names
  const lower = query.toLowerCase();
  for (const name of skippedNames) {
    if (name.toLowerCase().includes(lower)) {
      skippedNames.delete(name);
      saveSkipList();
      return `✅ Unskipped: *${name}*`;
    }
  }

  return `No skipped chat found matching "${query}".`;
}

wa.onMessage(async (msg) => {
  process.stderr.write(`\r\x1b[K\x1b[90m[message] from=${msg.isFromMe ? "me" : msg.senderName} "${msg.text?.slice(0, 30)}"\x1b[0m\n`);
  if (!msg.text) return;
  logMessageToFile(msg);

  // Skip processing for skipped chats
  if (isChatSkipped(msg.chatId)) {
    const chatName = wa.getChatName(msg.chatId);
    const sender = msg.senderName || msg.sender;
    console.log(`\x1b[90m  [skipped] message from ${sender} in ${chatName}\x1b[0m`);
    return;
  }

  // Chat with Copilot in the "wsap" group — respond to all messages, skip analysis
  if (msg.isGroup) {
    if (!wsapGroupId) {
      const found = await wa.findChatByNameWithFetch("wsap");
      if (found) wsapGroupId = found.id;
    }
    if (wsapGroupId && msg.chatId === wsapGroupId) {
      // Skip messages sent by the bot itself (loop prevention)
      if (botSentIds.has(msg.id)) {
        botSentIds.delete(msg.id);
        return;
      }

      // /new command — reset Copilot chat session
      if (msg.text.trim().toLowerCase() === "/new") {
        console.log(`\x1b[90m  [wsap] resetting chat session\x1b[0m`);
        await resetChatSession();
        const sentId = await wa.sendMessage(msg.chatId, "🔄 New session started.");
        if (sentId) botSentIds.add(sentId);
        return;
      }

      // /skip command — skip a chat from analysis/forwarding
      if (msg.text.trim().toLowerCase().startsWith("/skip")) {
        const skipResult = await handleSkipFromMessage(msg.text.trim());
        const sentId = await wa.sendMessage(msg.chatId, skipResult);
        if (sentId) botSentIds.add(sentId);
        return;
      }

      // /unskip command — resume processing a chat
      if (msg.text.trim().toLowerCase().startsWith("/unskip")) {
        const unskipResult = await handleUnskipFromMessage(msg.text.trim());
        const sentId = await wa.sendMessage(msg.chatId, unskipResult);
        if (sentId) botSentIds.add(sentId);
        return;
      }

      // Task management commands from WhatsApp
      const waReply = await handleTaskCommand(msg.text.trim());
      if (waReply !== null) {
        console.log(`\x1b[90m  [wsap] task command from ${msg.senderName ?? msg.sender}\x1b[0m`);
        const sentId = await wa.sendMessage(msg.chatId, waReply);
        if (sentId) botSentIds.add(sentId);
        return;
      }

      // Ping/pong health check
      if (msg.text.trim().toLowerCase() === "ping") {
        console.log(`\x1b[90m  [ping] processing health check from ${msg.senderName ?? msg.sender}...\x1b[0m`);
        const uptimeMs = Date.now() - startTime;
        const uptimeH = Math.floor(uptimeMs / 3_600_000);
        const uptimeM = Math.floor((uptimeMs % 3_600_000) / 60_000);

        let aiStatus = isAIActive() ? "active ✅" : "inactive ❌";
        try {
          const testResult = await analyzeMessage("test ping");
          aiStatus = `working ✅ (${testResult.summary.slice(0, 40) || "ok"})`;
        } catch (err) {
          aiStatus = `error ❌: ${(err as Error).message}`;
        }

        const pong = [
          "🏓 *pong*",
          `Uptime: ${uptimeH}h ${uptimeM}m`,
          `Connection: ${wa.status}`,
          `Chats cached: ${wa.cachedChatCount}`,
          `Messages cached: ${wa.cachedMessageCount}`,
          `AI: ${aiStatus}`,
        ].join("\n");
        try {
          const sentId = await wa.sendMessage(msg.chatId, pong);
          if (sentId) botSentIds.add(sentId);
        } catch (err) {
          console.log(`\x1b[90m  ping reply failed: ${(err as Error).message}\x1b[0m`);
        }
        return;
      }

      console.log(`\x1b[90m  [wsap] processing message from ${msg.senderName ?? msg.sender}: ${msg.text.slice(0, 80)}\x1b[0m`);
      try {
        const reply = await chatWithCopilot(msg.text);
        if (reply) {
          const sentId = await wa.sendMessage(msg.chatId, reply);
          if (sentId) botSentIds.add(sentId);
          console.log(`\x1b[90m  [wsap] replied: ${reply.slice(0, 80)}\x1b[0m`);
        }
      } catch (err) {
        console.log(`\x1b[90m  [wsap] reply failed: ${(err as Error).message}\x1b[0m`);
      }
      return;
    }
  }

  // Handle /skip and /unskip commands from the forward group ("ירדן החשוב")
  if (msg.isGroup && msg.chatId === FORWARD_GROUP_JID && !msg.isFromMe) {
    if (msg.text.trim().toLowerCase().startsWith("/skip")) {
      const skipResult = await handleSkipFromMessage(msg.text.trim());
      const sentId = await wa.sendMessage(msg.chatId, skipResult);
      if (sentId) botSentIds.add(sentId);
      return;
    }
    if (msg.text.trim().toLowerCase().startsWith("/unskip")) {
      const unskipResult = await handleUnskipFromMessage(msg.text.trim());
      const sentId = await wa.sendMessage(msg.chatId, unskipResult);
      if (sentId) botSentIds.add(sentId);
      return;
    }
  }

  const chatName = wa.getChatName(msg.chatId);
  const sender = msg.senderName || msg.sender;
  process.stdout.write(`\r\x1b[K`);
  console.log(`\x1b[90m  ┌ New message from \x1b[33m${sender}\x1b[90m in \x1b[36m${chatName}\x1b[90m: ${msg.text.slice(0, 80)}\x1b[0m`);
  console.log(`\x1b[90m  │ Analyzing...\x1b[0m`);
  try {
    const result = await analyzeMessage(msg.text);
    const taskIcon = result.hasTask ? "\x1b[33m⚠ TASK\x1b[0m" : "";
    const importantIcon = result.isImportant ? "\x1b[31m❗IMPORTANT\x1b[0m" : "";
    const categoryIcon = result.hasTask ? `\x1b[36m🏷 ${result.category}\x1b[0m` : "";
    const tags = [taskIcon, importantIcon, categoryIcon].filter(Boolean).join(" ");
    if (tags) {
      console.log(`\x1b[90m  │ ${tags} — ${result.summary}\x1b[0m`);
      for (const item of result.actionItems) {
        console.log(`\x1b[90m  │   • ${item}\x1b[0m`);
      }

      // Forward important messages or tasks to the designated group
      if (result.hasTask || result.isImportant) {
          // Auto-save tasks
          if (result.hasTask) {
            const saved = addTask(result, sender, chatName, msg.text);
            console.log(`\x1b[90m  │ 💾 Saved as task #${saved.id}\x1b[0m`);

            // Auto-remind on due dates
            for (const item of result.actionItems) {
              const dueStr = extractDueFromActionItem(item);
              if (dueStr) {
                const triggerAt = parseDueDate(dueStr);
                if (triggerAt && triggerAt.getTime() > Date.now()) {
                  const reminder = addReminder(saved.id, triggerAt);
                  if (reminder) {
                    scheduleNewReminder(reminder);
                    console.log(`\x1b[90m  │ ⏰ Auto-reminder set for ${triggerAt.toLocaleString()}\x1b[0m`);
                  }
                }
              }
            }
          }

          const label = result.hasTask && result.isImportant
            ? "📋 *Important task detected*"
            : result.hasTask
              ? "📋 *Task detected*"
              : "❗ *Important message*";
          const actionList = result.actionItems.length > 0
            ? result.actionItems.map((a) => `  • ${a}`).join("\n")
            : "";
          const forwardText = [
            label,
            `From: ${sender} (${chatName})`,
            `Message: ${msg.text}`,
            ``,
            `*Summary:* ${result.summary}`,
            actionList ? `*Action Items:*\n${actionList}` : "",
          ].filter(Boolean).join("\n");

          try {
            if (settings.forwardingEnabled) {
              await wa.sendMessage(FORWARD_GROUP_JID, forwardText);
              console.log(`\x1b[90m  │ 📤 Forwarded to ${FORWARD_GROUP_NAME}\x1b[0m`);
            }
          } catch (fwdErr) {
            console.log(`\x1b[90m  │ ⚠ Forward failed: ${(fwdErr as Error).message}\x1b[0m`);
          }
      }

      console.log(`\x1b[90m  └─\x1b[0m`);
    } else {
      console.log(`\x1b[90m  └ No tasks or important info\x1b[0m`);
    }
  } catch (err) {
    console.log(`\x1b[90m  └ Analysis error: ${(err as Error).message}\x1b[0m`);
  }
});

const rl = readline.createInterface({
  input: process.stdin,
  output: process.stdout,
});

function prompt() {
  const status = wa.isConnected() ? "\x1b[32m●\x1b[0m" : "\x1b[31m●\x1b[0m";
  rl.question(`${status} wsapp> `, async (line) => {
    const trimmed = line.trim();
    if (!trimmed) return prompt();

    const [cmd, ...args] = trimmed.split(/\s+/);
    try {
      await handleCommand(cmd!, args, trimmed);
    } catch (err) {
      console.error(`\x1b[31mError:\x1b[0m ${(err as Error).message}`);
    }
    prompt();
  });
}

async function handleCommand(cmd: string, args: string[], raw: string) {
  switch (cmd.toLowerCase()) {
    case "connect":
      return cmdConnect();
    case "reconnect":
      return cmdReconnect();
    case "chats":
      return cmdChats(args);
    case "messages":
    case "msgs":
      return cmdMessages(args);
    case "unread":
      return cmdUnread();
    case "search":
      return cmdSearch(args, raw);
    case "eval":
      return cmdEval(raw);
    case "info":
      return cmdInfo(args);
    case "status":
      return cmdStatus();
    case "help":
      return cmdHelp();
    case "groups":
      return cmdGroups();
    case "skip":
      return cmdSkip(args, raw);
    case "unskip":
      return cmdUnskip(args, raw);
    case "tasks":
      return cmdTasks(args);
    case "today":
      return cmdToday();
    case "task":
      return cmdTaskDetail(args);
    case "done":
      return cmdDone(args);
    case "delete":
      return cmdDelete(args);
    case "note":
      return cmdNote(args, raw);
    case "add":
      return cmdAdd(raw);
    case "remind":
      return cmdRemind(args, raw);
    case "reminders":
      return cmdReminders();
    case "unremind":
      return cmdUnremind(args);
    case "firebase-upload":
      return cmdFirebaseUpload();
    case "forward":
      return cmdForward(args);
    case "quit":
    case "exit":
      console.log("Shutting down...");
      stopReminders();
      await stopAI();
      console.log("Goodbye!");
      process.exit(0);
    default:
      console.log(`Unknown command: ${cmd}. Type 'help' for available commands.`);
  }
}

async function cmdConnect() {
  console.log("Connecting to WhatsApp...");
  const result = await wa.connect();
  if (result.status === "already_connected") {
    console.log("Already connected.");
  } else if (result.status === "connected") {
    console.log("\x1b[32m✓ Connected to WhatsApp\x1b[0m");
    console.log(`  Chats cached: ${wa.cachedChatCount}`);
    console.log(`  Messages cached: ${wa.cachedMessageCount}`);
  } else {
    console.log(`Connection result: ${result.status}`);
  }
}

async function cmdReconnect() {
  console.log("Clearing saved session and reconnecting...");
  await wa.resetAuth();
  console.log("Session cleared. Scan the QR code with your new phone.");
  return cmdConnect();
}

function cmdChats(args: string[]) {
  const limit = parseInt(args[0] ?? "20", 10);
  const chats = wa.listChats(limit);
  if (chats.length === 0) {
    console.log("No chats cached yet. Messages populate as they arrive after connecting.");
    return;
  }
  console.log(`\n  ${"#".padEnd(4)} ${"Chat".padEnd(35)} ${"Unread".padEnd(8)} Last Activity`);
  console.log(`  ${"─".repeat(75)}`);
  chats.forEach((c, i) => {
    const name = (c.isGroup ? `[G] ${c.name}` : c.name).slice(0, 33);
    const unread = c.unreadCount > 0 ? `\x1b[33m${c.unreadCount}\x1b[0m` : "0";
    const time = c.lastMessageTimestamp
      ? new Date(c.lastMessageTimestamp * 1000).toLocaleString()
      : "—";
    console.log(`  ${String(i + 1).padEnd(4)} ${name.padEnd(35)} ${String(unread).padEnd(8)} ${time}`);
  });
  console.log(`\n  Use 'messages <chatId>' to view messages. Chat IDs from above listing.\n`);
}

function cmdMessages(args: string[]) {
  if (args.length === 0) {
    // If no chatId, show chats with numeric indices and let user pick
    const chats = wa.listChats(20);
    if (chats.length === 0) {
      console.log("No chats available.");
      return;
    }
    console.log("Specify a chat number or ID. Available chats:");
    chats.forEach((c, i) => {
      const name = c.isGroup ? `[G] ${c.name}` : c.name;
      console.log(`  ${i + 1}. ${name}  (${c.id})`);
    });
    return;
  }

  let chatId = args[0]!;
  const count = parseInt(args[1] ?? "20", 10);

  // Allow numeric index
  const idx = parseInt(chatId, 10);
  if (!isNaN(idx) && idx > 0) {
    const chats = wa.listChats(100);
    if (idx > chats.length) {
      console.log(`Chat #${idx} not found. Only ${chats.length} chats available.`);
      return;
    }
    chatId = chats[idx - 1]!.id;
  }

  const messages = wa.getMessages(chatId, count);
  if (messages.length === 0) {
    console.log("No messages cached for this chat.");
    return;
  }

  console.log(`\n  Messages from: ${chatId} (${messages.length} shown)\n`);
  for (const m of messages) {
    const time = new Date(m.timestamp * 1000).toLocaleTimeString();
    const sender = m.isFromMe ? "\x1b[36mYou\x1b[0m" : `\x1b[33m${m.senderName || m.sender}\x1b[0m`;
    const media = m.mediaType ? ` [${m.mediaType}]` : "";
    const quote = m.quotedText ? `\x1b[90m  ↳ "${m.quotedText.slice(0, 60)}"\x1b[0m\n` : "";
    console.log(`  ${time} ${sender}${media}: ${m.text}`);
    if (quote) process.stdout.write(quote);
  }
  console.log();
}

function cmdUnread() {
  const messages = wa.getUnreadMessages();
  if (messages.length === 0) {
    console.log("No unread messages.");
    return;
  }

  console.log(`\n  ${messages.length} unread message(s):\n`);
  let lastChat = "";
  for (const m of messages) {
    if (m.chatId !== lastChat) {
      const chatInfo = wa.listChats(100).find((c) => c.id === m.chatId);
      console.log(`  \x1b[1m── ${chatInfo?.name ?? m.chatId} ──\x1b[0m`);
      lastChat = m.chatId;
    }
    const time = new Date(m.timestamp * 1000).toLocaleTimeString();
    const sender = m.isFromMe ? "\x1b[36mYou\x1b[0m" : `\x1b[33m${m.senderName || m.sender}\x1b[0m`;
    console.log(`    ${time} ${sender}: ${m.text}`);
  }
  console.log();
}

function cmdSearch(args: string[], raw: string) {
  // Extract keyword — everything after "search "
  const keyword = raw.replace(/^search\s+/i, "").trim();
  if (!keyword) {
    console.log("Usage: search <keyword>");
    return;
  }

  const results = wa.searchMessages(keyword);
  if (results.length === 0) {
    console.log(`No messages found matching "${keyword}".`);
    return;
  }

  console.log(`\n  Found ${results.length} message(s) matching "${keyword}":\n`);
  for (const m of results) {
    const time = new Date(m.timestamp * 1000).toLocaleString();
    const chatInfo = wa.listChats(100).find((c) => c.id === m.chatId);
    const chatName = chatInfo?.name ?? m.chatId;
    const sender = m.isFromMe ? "You" : (m.senderName || m.sender);
    console.log(`  [${time}] ${chatName} — ${sender}: ${m.text}`);
  }
  console.log();
}

async function cmdEval(raw: string) {
  const text = raw.replace(/^eval\s+/i, "").trim();
  if (!text) {
    console.log("Usage: eval <message text>");
    return;
  }

  console.log("  Analyzing message...");
  const result = await analyzeMessage(text);

  const taskIcon = result.hasTask ? "\x1b[33m⚠ YES\x1b[0m" : "\x1b[32m✓ No\x1b[0m";
  const importantIcon = result.isImportant ? "\x1b[33m⚠ YES\x1b[0m" : "\x1b[32m✓ No\x1b[0m";

  const criticalIcon = result.isCritical ? "\x1b[31m⚠ YES\x1b[0m" : "\x1b[32m✓ No\x1b[0m";

  console.log(`\n  \x1b[1mAnalysis:\x1b[0m`);
  console.log(`  Has Task:      ${taskIcon}`);
  console.log(`  Important:     ${importantIcon}`);
  console.log(`  Critical:      ${criticalIcon}`);
  console.log(`  Category:      ${result.category}`);
  if (result.dueDate) {
    console.log(`  Due:           ${result.dueDate}`);
  }
  if (result.summary) {
    console.log(`  Summary:       ${result.summary}`);
  }
  if (result.actionItems.length > 0) {
    console.log(`  Action Items:`);
    for (const item of result.actionItems) {
      console.log(`    • ${item}`);
    }
  }
  console.log();
}

async function cmdInfo(args: string[]) {
  if (args.length === 0) {
    console.log("Usage: info <chatId or chat number>");
    return;
  }

  let chatId = args[0]!;
  const idx = parseInt(chatId, 10);
  if (!isNaN(idx) && idx > 0) {
    const chats = wa.listChats(100);
    if (idx > chats.length) {
      console.log(`Chat #${idx} not found.`);
      return;
    }
    chatId = chats[idx - 1]!.id;
  }

  const info = await wa.getContactInfo(chatId);
  console.log(`\n  Name:     ${info.name}`);
  console.log(`  ID:       ${info.id}`);
  console.log(`  Type:     ${info.isGroup ? "Group" : "Direct"}`);
  if (info.description) console.log(`  Desc:     ${info.description}`);
  if (info.participants) {
    console.log(`  Members:  ${info.participants.length}`);
    for (const p of info.participants) {
      const role = p.isAdmin ? " (admin)" : "";
      console.log(`    - ${p.id}${role}`);
    }
  }
  console.log();
}

function cmdStatus() {
  console.log(`\n  Connection: ${wa.status}`);
  console.log(`  Chats cached: ${wa.cachedChatCount}`);
  console.log(`  Messages cached: ${wa.cachedMessageCount}\n`);
}

function cmdHelp() {
  console.log(`
  \x1b[1mwsapp — WhatsApp CLI\x1b[0m

  Commands:
    connect              Connect to WhatsApp (scan QR code on first use)
    reconnect            Clear saved session and pair with a new phone
    chats [limit]        List chats (default: 20)
    messages <id> [n]    Show last n messages from a chat (use # or chat ID)
    msgs <id> [n]        Alias for messages
    unread               Show all unread messages
    search <keyword>     Search cached messages by keyword
    eval <text>          Analyze a message for tasks/important info (uses Copilot)
    groups               List all WhatsApp groups
    skip <name|#|ID>     Skip processing messages from a chat
    unskip <name|#|ID>   Resume processing messages (use 'unskip all' to clear)
    info <id>            Show contact/group details
    status               Show connection status and cache stats

  \x1b[1mTask Management:\x1b[0m
    tasks [pending|done]       List tasks (optionally filter by status)
    today                      List today's tasks
    task <id>                  View task details
    add <text>                 Manually add a task
    done <id>                  Mark a task as completed
    delete <id> [id2...]       Delete task(s) by ID
    note <id> <text>           Add a note to a task

  \x1b[1mReminders:\x1b[0m
    remind <taskId> <time>     Set a reminder (30m, 2h, 1d, tomorrow)
    reminders                  List pending reminders
    unremind <id>              Cancel a reminder

  \x1b[1mFirebase:\x1b[0m
    firebase-upload            Upload all tasks to Firestore
    forward [on|off]           Toggle message forwarding to group (default: off)

    help                 Show this help
    quit / exit          Exit the CLI
`);
}

// --- Task REPL commands ---

function cmdTasks(args: string[]) {
  const filter = args[0]?.toLowerCase() as "pending" | "done" | undefined;
  if (filter && filter !== "pending" && filter !== "done") {
    console.log("Usage: tasks [pending|done]");
    return;
  }
  const items = listTasks(filter);
  const title = filter ? `📋 Tasks (${filter})` : "📋 All Tasks";
  console.log("\n" + formatTaskList(items, title));
}

function cmdToday() {
  const items = listTodayTasks();
  console.log("\n" + formatTaskList(items, "📋 Today's Tasks"));
}

function cmdTaskDetail(args: string[]) {
  if (args.length === 0) {
    console.log("Usage: task <id>");
    return;
  }
  const id = parseInt(args[0]!, 10);
  if (isNaN(id)) {
    console.log("Invalid task ID.");
    return;
  }
  const task = getTask(id);
  if (!task) {
    console.log(`Task #${id} not found.`);
    return;
  }
  console.log("\n" + formatTaskDetail(task) + "\n");
}

function cmdAdd(raw: string) {
  const text = raw.replace(/^add\s+/i, "").trim();
  if (!text) {
    console.log("Usage: add <task description>");
    return;
  }
  const task = addManualTask(text);
  console.log(`\x1b[32m✓ Task #${task.id} created\x1b[0m`);
}

function cmdDone(args: string[]) {
  if (args.length === 0) {
    console.log("Usage: done <id>");
    return;
  }
  const id = parseInt(args[0]!, 10);
  if (isNaN(id)) {
    console.log("Invalid task ID.");
    return;
  }
  const task = completeTask(id);
  if (!task) {
    console.log(`Task #${id} not found.`);
    return;
  }
  console.log(`\x1b[32m✅ Task #${id} marked as done\x1b[0m`);
}

function cmdDelete(args: string[]) {
  if (args.length === 0) {
    console.log("Usage: delete <id> [id2] [id3...]");
    return;
  }
  const ids = args.map((a) => parseInt(a, 10)).filter((n) => !isNaN(n));
  if (ids.length === 0) {
    console.log("Invalid task ID(s).");
    return;
  }
  const result = deleteTasks(ids);
  if (result.deleted.length > 0) {
    console.log(`\x1b[32m✓ Deleted task(s): ${result.deleted.map((id) => `#${id}`).join(", ")}\x1b[0m`);
  }
  if (result.notFound.length > 0) {
    console.log(`\x1b[33mNot found: ${result.notFound.map((id) => `#${id}`).join(", ")}\x1b[0m`);
  }
}

function cmdNote(args: string[], raw: string) {
  if (args.length < 2) {
    console.log("Usage: note <id> <text>");
    return;
  }
  const id = parseInt(args[0]!, 10);
  if (isNaN(id)) {
    console.log("Invalid task ID.");
    return;
  }
  const noteText = raw.replace(/^note\s+\S+\s+/i, "").trim();
  if (!noteText) {
    console.log("Usage: note <id> <text>");
    return;
  }
  const task = addNote(id, noteText);
  if (!task) {
    console.log(`Task #${id} not found.`);
    return;
  }
  console.log(`\x1b[32m✓ Note added to task #${id}\x1b[0m`);
}

// --- Reminder REPL commands ---

function cmdRemind(args: string[], raw: string) {
  if (args.length < 2) {
    console.log("Usage: remind <taskId> <time>  (e.g., remind 3 2h, remind 5 tomorrow)");
    return;
  }
  const taskId = parseInt(args[0]!, 10);
  if (isNaN(taskId)) {
    console.log("Invalid task ID.");
    return;
  }
  const timeSpec = raw.replace(/^remind\s+\S+\s+/i, "").trim();
  const triggerAt = parseTimeSpec(timeSpec);
  if (!triggerAt) {
    console.log("Could not parse time. Use: 30m, 2h, 1d, tomorrow");
    return;
  }
  const reminder = addReminder(taskId, triggerAt);
  if (!reminder) {
    console.log(`Task #${taskId} not found.`);
    return;
  }
  scheduleNewReminder(reminder);
  console.log(`\x1b[32m⏰ Reminder #${reminder.id} set for ${triggerAt.toLocaleString()}\x1b[0m`);
}

function cmdReminders() {
  const items = listPendingReminders();
  console.log("\n" + formatReminderList(items));
}

function cmdUnremind(args: string[]) {
  if (args.length === 0) {
    console.log("Usage: unremind <id>");
    return;
  }
  const id = parseInt(args[0]!, 10);
  if (isNaN(id)) {
    console.log("Invalid reminder ID.");
    return;
  }
  if (deleteReminder(id)) {
    console.log(`\x1b[32m✓ Reminder #${id} cancelled\x1b[0m`);
  } else {
    console.log(`Reminder #${id} not found.`);
  }
}

async function cmdFirebaseUpload() {
  const all = listTasks();
  if (all.length === 0) {
    console.log("No tasks to upload.");
    return;
  }
  try {
    const count = await uploadAllTasks(all);
    console.log(`\x1b[32m✓ Uploaded ${count} tasks to Firestore\x1b[0m`);
  } catch (err) {
    console.log(`\x1b[31m✗ Upload failed: ${(err as Error).message}\x1b[0m`);
  }
}

function cmdForward(args: string[]) {
  const arg = args[0]?.toLowerCase();
  if (arg === "on") {
    settings.forwardingEnabled = true;
    fs.writeFileSync(SETTINGS_PATH, JSON.stringify(settings, null, 2));
    console.log(`\x1b[32m✓ Forwarding enabled — messages will be sent to ${FORWARD_GROUP_NAME}\x1b[0m`);
  } else if (arg === "off") {
    settings.forwardingEnabled = false;
    fs.writeFileSync(SETTINGS_PATH, JSON.stringify(settings, null, 2));
    console.log(`\x1b[32m✓ Forwarding disabled\x1b[0m`);
  } else {
    const status = settings.forwardingEnabled ? "\x1b[32mON\x1b[0m" : "\x1b[31mOFF\x1b[0m";
    console.log(`Forwarding is currently ${status}`);
    console.log("Usage: forward on|off");
  }
}

// --- WhatsApp task command handler (returns reply text or null if not a task command) ---

function handleTaskCommand(text: string): string | null {
  const lower = text.toLowerCase();

  if (lower === "/tasks" || lower.startsWith("/tasks ")) {
    const filter = text.slice(6).trim().toLowerCase() as "pending" | "done" | "";
    if (filter && filter !== "pending" && filter !== "done") {
      return "Usage: /tasks [pending|done]";
    }
    const items = listTasks(filter || undefined);
    const title = filter ? `📋 Tasks (${filter})` : "📋 All Tasks";
    return formatTaskList(items, title);
  }

  if (lower === "/today") {
    const items = listTodayTasks();
    return formatTaskList(items, "📋 Today's Tasks");
  }

  if (lower.startsWith("/task ")) {
    const id = parseInt(text.slice(6).trim(), 10);
    if (isNaN(id)) return "Invalid task ID.";
    const task = getTask(id);
    if (!task) return `Task #${id} not found.`;
    return formatTaskDetail(task);
  }

  if (lower.startsWith("/add ")) {
    const desc = text.slice(5).trim();
    if (!desc) return "Usage: /add <task description>";
    const task = addManualTask(desc);
    return `✅ Task #${task.id} created`;
  }

  if (lower.startsWith("/done ")) {
    const id = parseInt(text.slice(6).trim(), 10);
    if (isNaN(id)) return "Invalid task ID.";
    const task = completeTask(id);
    if (!task) return `Task #${id} not found.`;
    return `✅ Task #${id} marked as done`;
  }

  if (lower.startsWith("/delete ")) {
    const ids = text.slice(8).trim().split(/\s+/).map((s) => parseInt(s, 10)).filter((n) => !isNaN(n));
    if (ids.length === 0) return "Usage: /delete <id> [id2...]";
    const result = deleteTasks(ids);
    const parts: string[] = [];
    if (result.deleted.length > 0) parts.push(`✅ Deleted: ${result.deleted.map((id) => `#${id}`).join(", ")}`);
    if (result.notFound.length > 0) parts.push(`⚠ Not found: ${result.notFound.map((id) => `#${id}`).join(", ")}`);
    return parts.join("\n");
  }

  if (lower.startsWith("/note ")) {
    const rest = text.slice(6).trim();
    const spaceIdx = rest.indexOf(" ");
    if (spaceIdx === -1) return "Usage: /note <id> <text>";
    const id = parseInt(rest.slice(0, spaceIdx), 10);
    if (isNaN(id)) return "Invalid task ID.";
    const noteText = rest.slice(spaceIdx + 1).trim();
    if (!noteText) return "Usage: /note <id> <text>";
    const task = addNote(id, noteText);
    if (!task) return `Task #${id} not found.`;
    return `✅ Note added to task #${id}`;
  }

  if (lower === "/reminders") {
    const items = listPendingReminders();
    return formatReminderList(items);
  }

  if (lower.startsWith("/remind ")) {
    const rest = text.slice(8).trim();
    const spaceIdx = rest.indexOf(" ");
    if (spaceIdx === -1) return "Usage: /remind <taskId> <time>  (e.g., /remind 3 2h)";
    const taskId = parseInt(rest.slice(0, spaceIdx), 10);
    if (isNaN(taskId)) return "Invalid task ID.";
    const timeSpec = rest.slice(spaceIdx + 1).trim();
    const triggerAt = parseTimeSpec(timeSpec);
    if (!triggerAt) return "Could not parse time. Use: 30m, 2h, 1d, tomorrow";
    const reminder = addReminder(taskId, triggerAt);
    if (!reminder) return `Task #${taskId} not found.`;
    scheduleNewReminder(reminder);
    return `⏰ Reminder #${reminder.id} set for ${triggerAt.toLocaleString()}`;
  }

  if (lower.startsWith("/unremind ")) {
    const id = parseInt(text.slice(10).trim(), 10);
    if (isNaN(id)) return "Invalid reminder ID.";
    if (deleteReminder(id)) return `✅ Reminder #${id} cancelled`;
    return `Reminder #${id} not found.`;
  }

  if (lower === "/help") {
    return [
      "📖 *wsapp Commands*",
      "",
      "*Tasks:*",
      "/tasks [pending|done] — List tasks",
      "/today — Today's tasks",
      "/task <id> — View task details",
      "/add <text> — Create a task",
      "/done <id> — Mark task as completed",
      "/delete <id> [id2...] — Delete task(s)",
      "/note <id> <text> — Add a note to a task",
      "",
      "*Reminders:*",
      "/remind <taskId> <time> — Set a reminder (30m, 2h, 1d, tomorrow)",
      "/reminders — List pending reminders",
      "/unremind <id> — Cancel a reminder",
      "",
      "*Other:*",
      "/skip [name] — Skip a chat from analysis",
      "/unskip [name] — Resume processing a chat",
      "/new — Reset chat session",
      "/help — Show this help",
      "ping — Health check",
    ].join("\n");
  }

  return null;
}

async function cmdGroups() {
  console.log("Fetching groups from server...");
  const groups = await wa.fetchGroups();
  if (groups.length === 0) {
    console.log("No groups found.");
    return;
  }
  console.log(`\nGroups (${groups.length}):`);
  for (const g of groups) {
    console.log(`  ${g.name}  →  ${g.id}`);
  }
  console.log();
}

async function cmdSkip(args: string[], raw: string) {
  if (args.length === 0) {
    if (skippedNames.size === 0) {
      console.log("No chats are currently skipped.");
    } else {
      console.log("\n  Skipped chats:");
      for (const name of skippedNames) {
        console.log(`    • ${name}`);
      }
      console.log();
    }
    console.log("Usage: skip <chat name or number>");
    return;
  }

  const query = raw.replace(/^skip\s+/i, "").trim();

  // Try numeric index first
  const idx = parseInt(query, 10);
  if (!isNaN(idx) && idx > 0 && String(idx) === query) {
    const chats = wa.listChats(100);
    if (idx > chats.length) {
      console.log(`Chat #${idx} not found. Only ${chats.length} chats available.`);
      return;
    }
    const chat = chats[idx - 1]!;
    skippedNames.add(chat.name);
    saveSkipList();
    console.log(`\x1b[33m✓ Skipping messages from: ${chat.name}\x1b[0m`);
    return;
  }

  // Try to resolve name from chat ID
  const chatName = wa.getChatName(query);
  if (chatName !== query) {
    skippedNames.add(chatName);
    saveSkipList();
    console.log(`\x1b[33m✓ Skipping messages from: ${chatName}\x1b[0m`);
    return;
  }

  // Try exact name match first, then substring
  const chats = wa.listChats(1000);
  const lower = query.toLowerCase();
  const exact = chats.find((c) => c.name.toLowerCase() === lower);
  if (exact) {
    skippedNames.add(exact.name);
    saveSkipList();
    console.log(`\x1b[33m✓ Skipping messages from: ${exact.name}\x1b[0m`);
    return;
  }

  const partial = chats.filter((c) => c.name.toLowerCase().includes(lower));
  if (partial.length === 1) {
    skippedNames.add(partial[0]!.name);
    saveSkipList();
    console.log(`\x1b[33m✓ Skipping messages from: ${partial[0]!.name}\x1b[0m`);
    return;
  }
  if (partial.length > 1) {
    console.log(`Multiple chats match "${query}":`);
    partial.forEach((c, i) => console.log(`  ${i + 1}. ${c.name}`));
    console.log("\nPlease use the exact name.");
    return;
  }

  // Try fetching groups if nothing found in cache
  const found = await wa.findChatByNameWithFetch(query);
  if (found) {
    skippedNames.add(found.name);
    saveSkipList();
    console.log(`\x1b[33m✓ Skipping messages from: ${found.name}\x1b[0m`);
  } else {
    console.log(`No chat found matching "${query}".`);
  }
}

async function cmdUnskip(args: string[], raw: string) {
  if (args.length === 0) {
    if (skippedNames.size === 0) {
      console.log("No chats are currently skipped.");
      return;
    }
    console.log("\n  Skipped chats:");
    let i = 1;
    for (const name of skippedNames) {
      console.log(`    ${i}. ${name}`);
      i++;
    }
    console.log("\nUsage: unskip <chat name or number>");
    console.log("       unskip all    — remove all skips");
    return;
  }

  const query = raw.replace(/^unskip\s+/i, "").trim();

  // "unskip all" clears everything
  if (query.toLowerCase() === "all") {
    const count = skippedNames.size;
    skippedNames.clear();
    saveSkipList();
    console.log(`\x1b[32m✓ Removed all ${count} skip(s)\x1b[0m`);
    return;
  }

  // Try numeric index into the skipped list
  const idx = parseInt(query, 10);
  if (!isNaN(idx) && idx > 0 && String(idx) === query) {
    const names = [...skippedNames];
    if (idx > names.length) {
      console.log(`Skip #${idx} not found. Only ${names.length} skipped chat(s).`);
      return;
    }
    const name = names[idx - 1]!;
    skippedNames.delete(name);
    saveSkipList();
    console.log(`\x1b[32m✓ Unskipped: ${name}\x1b[0m`);
    return;
  }

  // Try exact match
  if (skippedNames.has(query)) {
    skippedNames.delete(query);
    saveSkipList();
    console.log(`\x1b[32m✓ Unskipped: ${query}\x1b[0m`);
    return;
  }

  // Try case-insensitive / substring match against skipped names
  const lower = query.toLowerCase();
  for (const name of skippedNames) {
    if (name.toLowerCase().includes(lower)) {
      skippedNames.delete(name);
      saveSkipList();
      console.log(`\x1b[32m✓ Unskipped: ${name}\x1b[0m`);
      return;
    }
  }

  console.log(`No skipped chat found matching "${query}".`);
}

// --- Start ---
const headless = process.argv.includes("--headless");

loadTasks();
loadReminders();
initFirestore();

// Merge app-driven skipped groups (Firestore `skippedGroups`) into the local skip list.
listenSkippedGroups((names) => {
  let added = 0;
  for (const name of names) {
    if (!skippedNames.has(name)) {
      skippedNames.add(name);
      added++;
    }
  }
  if (added > 0) {
    saveSkipList();
    console.log(`\x1b[36m⏭️  Synced ${added} skipped group(s) from app\x1b[0m`);
  }
});

console.log(`\x1b[1mwsapp — WhatsApp CLI${headless ? " (headless)" : ""}\x1b[0m`);
console.log(`Connecting to WhatsApp...`);
wa.connect().then(async (result) => {
  if (result.status === "connected") {
    console.log(`\x1b[32m✓ Connected to WhatsApp\x1b[0m`);
    console.log(`  Chats cached: ${wa.cachedChatCount}`);
    console.log(`  Messages cached: ${wa.cachedMessageCount}`);
    // Pre-fetch groups so wsap group is found immediately
    try {
      await wa.fetchGroups();
      const wsap = wa.findChatByName("wsap");
      if (wsap) {
        wsapGroupId = wsap.id;
        console.log(`  wsap group: ${wsap.name} (${wsap.id})`);
      }
    } catch {}
  } else {
    console.log(`Connection result: ${result.status}`);
  }

  // Start reminders and daily digest
  const reminderSend = async (text: string) => {
    if (settings.forwardingEnabled) {
      await wa.sendMessage(FORWARD_GROUP_JID, text);
    }
  };
  startReminderLoop(reminderSend);
  startDailyDigest(reminderSend);

  if (headless) {
    console.log("Running in headless mode — listening for messages...");
    const shutdown = async () => {
      console.log("\nShutting down...");
      stopReminders();
      await stopAI();
      console.log("Goodbye!");
      process.exit(0);
    };
    process.on("SIGINT", shutdown);
    process.on("SIGTERM", shutdown);
  } else {
    console.log(`Type 'help' for commands.\n`);
    prompt();
  }
});
