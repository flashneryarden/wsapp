import { CopilotClient, CopilotSession } from "@github/copilot-sdk";

const SYSTEM_PROMPT = `You are a message analyzer. Given a WhatsApp message, determine:
1. Does it contain a task or action item someone needs to do?
2. Does it contain important information (deadlines, decisions, announcements, urgent matters)?

Respond in this exact format:
TASK: yes/no
IMPORTANT: yes/no
SUMMARY: <one-line summary of the message>
ACTION_ITEMS:
- <action item 1> [due: <date if mentioned>] (if any)
- <action item 2> [due: <date if mentioned>] (if any)

If there are no action items, write "none" after ACTION_ITEMS.
If an action item has a due date or deadline mentioned in the message, include it in [due: ...] format.
IMPORTANT: The SUMMARY and ACTION_ITEMS must be written in the same language as the input message.
Be concise. Analyze the message regardless of language.`;

export interface AnalysisResult {
  hasTask: boolean;
  isImportant: boolean;
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

export async function analyzeMessage(text: string): Promise<AnalysisResult> {
  const raw = await sendWithRetry(`Message to analyze:\n"${text}"`);

  const hasTask = /TASK:\s*yes/i.test(raw);
  const isImportant = /IMPORTANT:\s*yes/i.test(raw);

  const summaryMatch = raw.match(/SUMMARY:\s*(.+)/i);
  const summary = summaryMatch?.[1]?.trim() ?? "";

  const actionItems: string[] = [];
  const actionSection = raw.split(/ACTION_ITEMS:/i)[1] ?? "";
  for (const line of actionSection.split("\n")) {
    const trimmed = line.trim();
    if (trimmed.startsWith("- ") && !/^-\s*none$/i.test(trimmed)) {
      actionItems.push(trimmed.slice(2));
    }
  }

  return { hasTask, isImportant, summary, actionItems, raw };
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
