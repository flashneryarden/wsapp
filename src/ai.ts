import { CopilotClient, CopilotSession } from "@github/copilot-sdk";

const SYSTEM_PROMPT = `You are a message analyzer. Given a WhatsApp message, determine:
1. Does it contain a task or action item someone needs to do?
2. Does it contain important information (deadlines, decisions, announcements, urgent matters)?
3. How critical is it? Mark CRITICAL: yes only when the message is urgent, time-sensitive, or high-impact (e.g. emergencies, imminent deadlines, safety issues, money/health matters, "ASAP"/"now"). Otherwise CRITICAL: no.

Respond in this exact format:
TASK: yes/no
IMPORTANT: yes/no
CRITICAL: yes/no
DUE: <due date in YYYY-MM-DD if a deadline/date is mentioned, otherwise none>
SUMMARY: <one-line summary of the message>
ACTION_ITEMS:
- <action item 1> [due: <date if mentioned>] (if any)
- <action item 2> [due: <date if mentioned>] (if any)

If there are no action items, write "none" after ACTION_ITEMS.
If an action item has a due date or deadline mentioned in the message, include it in [due: ...] format.
For the DUE field, resolve relative dates (e.g. "tomorrow", "מחר") to an absolute YYYY-MM-DD date when possible; if no date is mentioned, write "none".
IMPORTANT: The SUMMARY and ACTION_ITEMS must be written in the same language as the input message.
Be concise. Analyze the message regardless of language.`;

export interface AnalysisResult {
  hasTask: boolean;
  isImportant: boolean;
  isCritical: boolean;
  dueDate: string | null;
  summary: string;
  actionItems: string[];
  raw: string;
}

let client: CopilotClient | null = null;
let chatSession: CopilotSession | null = null;

const CHAT_SYSTEM_PROMPT = `You are a helpful assistant in a WhatsApp group chat. Respond concisely and naturally. Match the language of the user's message.`;

async function ensureClient(): Promise<CopilotClient> {
  if (!client) {
    client = new CopilotClient();
    await client.start();
  }
  return client;
}

async function createAnalysisSession(): Promise<CopilotSession> {
  await ensureClient();
  return client!.createSession({
    systemMessage: { mode: "replace", content: SYSTEM_PROMPT },
  });
}

async function getChatSession(): Promise<CopilotSession> {
  await ensureClient();
  if (!chatSession) {
    chatSession = await client!.createSession({
      systemMessage: { mode: "replace", content: CHAT_SYSTEM_PROMPT },
    });
  }
  return chatSession;
}

async function sendWithRetry(prompt: string): Promise<string> {
  let sess: CopilotSession | null = null;
  try {
    sess = await createAnalysisSession();
    const response = await sess.sendAndWait({ prompt });
    return response?.data.content ?? "";
  } catch (err) {
    console.log(`\x1b[90m  [ai] analysis failed, retrying: ${(err as Error).message}\x1b[0m`);
    sess?.destroy().catch(() => {});
    sess = await createAnalysisSession();
    const response = await sess.sendAndWait({ prompt });
    return response?.data.content ?? "";
  } finally {
    sess?.destroy().catch(() => {});
  }
}

/** True when the string contains a concrete calendar date strictly before today. */
function isPastDateString(s: string): boolean {
  let y: number, m: number, d: number;
  const iso = s.match(/(\d{4})-(\d{2})-(\d{2})/);
  if (iso) {
    y = +iso[1]; m = +iso[2]; d = +iso[3];
  } else {
    const dmy = s.match(/(\d{1,2})\/(\d{1,2})\/(\d{4})/);
    if (!dmy) return false;
    d = +dmy[1]; m = +dmy[2]; y = +dmy[3];
  }
  const due = new Date(y, m - 1, d);
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  return due.getTime() < today.getTime();
}

/**
 * Normalize a model-provided due date to a strict ISO calendar date
 * (YYYY-MM-DD) so the stored field is always numeric. Accepts ISO or
 * DD/MM/YYYY input; rejects relative/free text and dates already in the
 * past (returns null) so the dueDate field never holds ambiguous strings
 * like "tomorrow" or a deadline that has already elapsed.
 */
function normalizeDueDate(raw: string | undefined): string | null {
  if (!raw) return null;
  const s = raw.trim();
  if (s === "" || /^none$/i.test(s)) return null;

  let iso: string | null = null;
  const isoMatch = s.match(/(\d{4})-(\d{2})-(\d{2})/);
  if (isoMatch) {
    iso = `${isoMatch[1]}-${isoMatch[2]}-${isoMatch[3]}`;
  } else {
    const dmy = s.match(/(\d{1,2})\/(\d{1,2})\/(\d{4})/);
    if (dmy) {
      const d = dmy[1].padStart(2, "0");
      const m = dmy[2].padStart(2, "0");
      iso = `${dmy[3]}-${m}-${d}`;
    }
  }

  if (!iso || isPastDateString(iso)) return null;
  return iso;
}

/** Remove "[due: ...]" markers with an already-passed date from an action item. */
function stripPastDueMarker(item: string): string {
  return item
    .replace(/\s*\[\s*due:\s*([^\]]*)\]/gi, (full, inner) =>
      isPastDateString(inner) ? "" : full,
    )
    .replace(/\s{2,}/g, " ")
    .trim();
}

export async function analyzeMessage(text: string): Promise<AnalysisResult> {
  const raw = await sendWithRetry(`Message to analyze:\n"${text}"`);

  const hasTask = /TASK:\s*yes/i.test(raw);
  const isImportant = /IMPORTANT:\s*yes/i.test(raw);
  // Fall back to importance when the model omits the CRITICAL line.
  const isCritical = /CRITICAL:/i.test(raw) ? /CRITICAL:\s*yes/i.test(raw) : isImportant;

  const summaryMatch = raw.match(/SUMMARY:\s*(.+)/i);
  const summary = summaryMatch?.[1]?.trim() ?? "";

  const dueMatch = raw.match(/DUE:\s*(.+)/i);
  const dueDate = normalizeDueDate(dueMatch?.[1]?.trim());

  const actionItems: string[] = [];
  const actionSection = raw.split(/ACTION_ITEMS:/i)[1] ?? "";
  for (const line of actionSection.split("\n")) {
    const trimmed = line.trim();
    if (trimmed.startsWith("- ") && !/^-\s*none$/i.test(trimmed)) {
      const cleaned = stripPastDueMarker(trimmed.slice(2));
      if (cleaned) actionItems.push(cleaned);
    }
  }

  return { hasTask, isImportant, isCritical, dueDate, summary, actionItems, raw };
}

export async function resetChatSession(): Promise<void> {
  if (chatSession) {
    await chatSession.destroy();
    chatSession = null;
  }
}

export async function chatWithCopilot(text: string): Promise<string> {
  try {
    const sess = await getChatSession();
    const response = await sess.sendAndWait({ prompt: text });
    return response?.data.content ?? "";
  } catch (err) {
    console.log(`\x1b[90m  [ai] chat session expired, recreating: ${(err as Error).message}\x1b[0m`);
    chatSession = null;
    const sess = await getChatSession();
    const response = await sess.sendAndWait({ prompt: text });
    return response?.data.content ?? "";
  }
}

export function isAIActive(): boolean {
  return client !== null;
}

export async function stopAI(): Promise<void> {
  if (chatSession) {
    await chatSession.destroy();
    chatSession = null;
  }
  if (client) {
    await client.stop();
    client = null;
  }
}
