import makeWASocket, {
  useMultiFileAuthState,
  makeCacheableSignalKeyStore,
  DisconnectReason,
  Browsers,
  fetchLatestBaileysVersion,
  extractMessageContent,
  type WASocket,
  type proto,
} from "baileys";
import { Boom } from "@hapi/boom";
import pino from "pino";
import qrcodeTerminal from "qrcode-terminal";
import path from "path";
import type { WAChatInfo, WAMessage, WAContactInfo } from "./types.js";

export type LogFn = (...args: unknown[]) => void;
export type MessageHandler = (msg: WAMessage) => void;

export class WhatsAppClient {
  private socket: WASocket | null = null;
  private authDir: string;
  private connectionStatus: "disconnected" | "connecting" | "open" = "disconnected";
  private messageCache: Map<string, WAMessage[]> = new Map();
  private chatCache: Map<string, WAChatInfo> = new Map();
  private contactNames: Map<string, string> = new Map();
  private reconnectAttempts = 0;
  private readonly maxReconnects = 5;
  private log: LogFn;
  private messageHandlers: MessageHandler[] = [];

  constructor(opts?: { authDir?: string; log?: LogFn }) {
    this.authDir = opts?.authDir ?? path.join(process.cwd(), "auth_info");
    this.log = opts?.log ?? ((...args) => console.error("[whatsapp]", ...args));
  }

  onMessage(handler: MessageHandler): void {
    this.messageHandlers.push(handler);
  }

  get status(): string {
    return this.connectionStatus;
  }

  get cachedChatCount(): number {
    return this.chatCache.size;
  }

  get cachedMessageCount(): number {
    let total = 0;
    for (const msgs of this.messageCache.values()) total += msgs.length;
    return total;
  }

  isConnected(): boolean {
    return this.connectionStatus === "open" && this.socket !== null;
  }

  async connect(): Promise<{ status: string }> {
    if (this.isConnected()) {
      return { status: "already_connected" };
    }

    this.connectionStatus = "connecting";
    const { state, saveCreds } = await useMultiFileAuthState(this.authDir);
    const { version } = await fetchLatestBaileysVersion();

    const logger = pino({ level: "silent" });

    const socket = makeWASocket({
      version,
      auth: {
        creds: state.creds,
        keys: makeCacheableSignalKeyStore(state.keys, logger),
      },
      browser: Browsers.macOS("Desktop"),
      logger,
      syncFullHistory: false,
      shouldSyncHistoryMessage: () => false,
    });

    this.socket = socket;

    // Baileys buffers events — call process() to flush them
    socket.ev.process(async (events) => {
      if (events["connection.update"]) {
        // handled below
      }
    });

    return new Promise((resolve) => {
      socket.ev.on("creds.update", saveCreds);

      socket.ev.on("connection.update", (update) => {
        const { connection, lastDisconnect, qr } = update;

        if (qr) {
          this.log("QR code generated — scan with WhatsApp > Linked Devices > Link a Device");
          qrcodeTerminal.generate(qr, { small: true }, (code: string) => {
            console.log(code);
          });
        }

        if (connection === "open") {
          this.connectionStatus = "open";
          this.reconnectAttempts = 0;
          this.log("Connected to WhatsApp");
          resolve({ status: "connected" });
        }

        if (connection === "close") {
          const statusCode = (lastDisconnect?.error as Boom)?.output?.statusCode;
          const shouldReconnect = statusCode !== DisconnectReason.loggedOut;
          this.connectionStatus = "disconnected";

          if (shouldReconnect && this.reconnectAttempts < this.maxReconnects) {
            this.reconnectAttempts++;
            const delay = Math.min(this.reconnectAttempts * 2000, 10000);
            this.log(`Connection closed, reconnecting in ${delay / 1000}s (attempt ${this.reconnectAttempts}/${this.maxReconnects})...`);
            setTimeout(() => this.connect().then(resolve), delay);
          } else if (!shouldReconnect) {
            this.log("Logged out — delete auth_info/ and reconnect to re-authenticate");
            this.socket = null;
            resolve({ status: "logged_out" });
          } else {
            this.log("Max reconnection attempts reached. Run 'connect' to try again.");
            this.socket = null;
            resolve({ status: "disconnected" });
          }
        }
      });

      // Cache incoming messages and auto-create chat entries
      socket.ev.on("messages.upsert", ({ messages: msgs, type }) => {
        if (type !== "notify") return; // skip history replay
        this.log(`Incoming: ${msgs.length} new message(s)`);
        for (const msg of msgs) {
          const parsed = this.parseMessage(msg);
          if (!parsed) continue;
          this.addMessageToCache(parsed);

          // Auto-create chat entry if we don't have one
          if (!this.chatCache.has(parsed.chatId)) {
            const chatName = this.contactNames.get(parsed.chatId) ?? parsed.senderName ?? parsed.chatId;
            this.log(`New chat discovered: "${chatName}" [${parsed.chatId}]`);
            this.chatCache.set(parsed.chatId, {
              id: parsed.chatId,
              name: chatName,
              isGroup: parsed.isGroup,
              unreadCount: parsed.isFromMe ? 0 : 1,
              lastMessageTimestamp: parsed.timestamp,
            });
          } else {
            const chat = this.chatCache.get(parsed.chatId)!;
            chat.lastMessageTimestamp = parsed.timestamp;
            if (!parsed.isFromMe) chat.unreadCount++;
          }

          for (const handler of this.messageHandlers) {
            handler(parsed);
          }
        }
      });

      // History sync (initial load of chat history)
      socket.ev.on("messaging-history.set", ({ chats, contacts, messages, isLatest }) => {
        this.log(`History loaded: ${chats.length} chats, ${contacts.length} contacts, ${messages.length} messages`);

        for (const chat of chats) {
          const id = chat.id;
          if (!id) continue;
          const chatName = chat.name ?? id;
          this.log(`Chat loaded: "${chatName}" [${id}]`);
          this.chatCache.set(id, {
            id,
            name: chatName,
            isGroup: id.endsWith("@g.us"),
            unreadCount: chat.unreadCount ?? 0,
            lastMessageTimestamp: typeof chat.conversationTimestamp === "number"
              ? chat.conversationTimestamp
              : null,
          });
        }

        for (const contact of contacts) {
          if (contact.id) {
            this.contactNames.set(contact.id, contact.name ?? contact.notify ?? contact.id);
          }
        }

        for (const msg of messages) {
          const parsed = this.parseMessage(msg);
          if (!parsed) continue;
          this.addMessageToCache(parsed);
        }
      });

      // Cache chat updates
      socket.ev.on("chats.upsert", (chats) => {
        this.log(`Chat list updated: ${chats.length} chat(s)`);
        for (const chat of chats) {
          const id = chat.id;
          if (!id) continue;
          const chatName = chat.name ?? id;
          this.log(`New chat added: "${chatName}" [${id}]`);
          this.chatCache.set(id, {
            id,
            name: chatName,
            isGroup: id.endsWith("@g.us"),
            unreadCount: chat.unreadCount ?? 0,
            lastMessageTimestamp: typeof chat.conversationTimestamp === "number"
              ? chat.conversationTimestamp
              : null,
          });
        }
      });

      socket.ev.on("chats.update", (updates) => {
        for (const upd of updates) {
          if (!upd.id) continue;
          const existing = this.chatCache.get(upd.id);
          if (existing) {
            if (upd.name) {
              this.log(`Chat renamed: "${upd.name}" [${upd.id}]`);
              existing.name = upd.name;
            }
            if (upd.unreadCount !== undefined && upd.unreadCount !== null) {
              existing.unreadCount = upd.unreadCount;
            }
            if (upd.conversationTimestamp) {
              existing.lastMessageTimestamp =
                typeof upd.conversationTimestamp === "number"
                  ? upd.conversationTimestamp
                  : null;
            }
          }
        }
      });

      socket.ev.on("contacts.upsert", (contacts) => {
        this.log(`Contacts updated: ${contacts.length} contact(s)`);
        for (const c of contacts) {
          if (c.id) {
            this.contactNames.set(c.id, c.name ?? c.notify ?? c.id);
          }
        }
      });

      socket.ev.on("contacts.update", (contacts) => {
        for (const c of contacts) {
          if (c.id) {
            const name = c.name ?? c.notify;
            if (name) this.contactNames.set(c.id, name);
          }
        }
      });
    });
  }

  async disconnect(): Promise<void> {
    if (this.socket) {
      await this.socket.logout();
      this.socket = null;
      this.connectionStatus = "disconnected";
    }
  }

  async resetAuth(): Promise<void> {
    if (this.socket) {
      this.socket.end(undefined);
      this.socket = null;
      this.connectionStatus = "disconnected";
    }
    // Delete auth files to force new QR code pairing
    const fs = await import("fs");
    if (fs.existsSync(this.authDir)) {
      fs.rmSync(this.authDir, { recursive: true, force: true });
    }
    this.messageCache.clear();
    this.chatCache.clear();
    this.contactNames.clear();
  }

  getChatName(chatId: string): string {
    const chat = this.chatCache.get(chatId);
    if (!chat) return this.contactNames.get(chatId) ?? chatId;
    return chat.name === chat.id ? (this.contactNames.get(chatId) ?? chatId) : chat.name;
  }

  listChats(limit: number = 30): WAChatInfo[] {
    this.ensureConnected();
    const chats = Array.from(this.chatCache.values()).map((c) => ({
      ...c,
      // Resolve contact name if chat name is just the JID
      name: c.name === c.id ? (this.contactNames.get(c.id) ?? c.id) : c.name,
    }));
    chats.sort((a, b) => (b.lastMessageTimestamp ?? 0) - (a.lastMessageTimestamp ?? 0));
    return chats.slice(0, limit);
  }

  findChatByName(name: string): WAChatInfo | undefined {
    const chats = this.listChats(1000);
    const lower = name.toLowerCase();
    return chats.find((c) => c.name.toLowerCase().includes(lower));
  }

  async findChatByNameWithFetch(name: string): Promise<WAChatInfo | undefined> {
    let found = this.findChatByName(name);
    if (!found) {
      this.log(`"${name}" not found in cache, fetching groups...`);
      await this.fetchGroups();
      found = this.findChatByName(name);
    }
    return found;
  }

  async fetchGroups(): Promise<WAChatInfo[]> {
    this.ensureConnected();
    const groups = await this.socket!.groupFetchAllParticipating();
    const result: WAChatInfo[] = [];
    for (const [id, meta] of Object.entries(groups)) {
      const info: WAChatInfo = {
        id,
        name: meta.subject,
        isGroup: true,
        unreadCount: this.chatCache.get(id)?.unreadCount ?? 0,
        lastMessageTimestamp: this.chatCache.get(id)?.lastMessageTimestamp ?? null,
      };
      this.chatCache.set(id, info);
      result.push(info);
    }
    return result;
  }

  async sendMessage(chatId: string, text: string): Promise<string | undefined> {
    this.ensureConnected();
    const sent = await this.socket!.sendMessage(chatId, { text });
    return sent?.key?.id ?? undefined;
  }

  getMessages(chatId: string, count: number = 20): WAMessage[] {
    this.ensureConnected();
    const cached = this.messageCache.get(chatId) ?? [];
    const sorted = [...cached].sort((a, b) => a.timestamp - b.timestamp);
    return sorted.slice(-count);
  }

  getUnreadMessages(): WAMessage[] {
    this.ensureConnected();
    const unreadChats = Array.from(this.chatCache.values()).filter((c) => c.unreadCount > 0);
    const allUnread: WAMessage[] = [];

    for (const chat of unreadChats) {
      const msgs = this.messageCache.get(chat.id) ?? [];
      // Take the last N messages where N = unreadCount
      const recent = msgs.slice(-chat.unreadCount);
      allUnread.push(...recent);
    }

    allUnread.sort((a, b) => a.timestamp - b.timestamp);
    return allUnread;
  }

  searchMessages(keyword: string, chatId?: string): WAMessage[] {
    this.ensureConnected();
    const lowerKeyword = keyword.toLowerCase();
    const results: WAMessage[] = [];

    const chatsToSearch = chatId
      ? [chatId]
      : Array.from(this.messageCache.keys());

    for (const cid of chatsToSearch) {
      const msgs = this.messageCache.get(cid) ?? [];
      for (const msg of msgs) {
        if (msg.text.toLowerCase().includes(lowerKeyword)) {
          results.push(msg);
        }
      }
    }

    results.sort((a, b) => b.timestamp - a.timestamp);
    return results.slice(0, 50);
  }

  async getContactInfo(chatId: string): Promise<WAContactInfo> {
    this.ensureConnected();
    const isGroup = chatId.endsWith("@g.us");

    if (isGroup) {
      const metadata = await this.socket!.groupMetadata(chatId);
      return {
        id: chatId,
        name: metadata.subject,
        isGroup: true,
        description: metadata.desc ?? undefined,
        participants: metadata.participants.map((p) => ({
          id: p.id,
          name: p.id,
          isAdmin: p.admin === "admin" || p.admin === "superadmin",
        })),
      };
    }

    const chatInfo = this.chatCache.get(chatId);
    return {
      id: chatId,
      name: chatInfo?.name ?? chatId,
      isGroup: false,
    };
  }

  private ensureConnected(): void {
    if (!this.isConnected()) {
      throw new Error("Not connected to WhatsApp. Run 'connect' first.");
    }
  }

  private addMessageToCache(msg: WAMessage): void {
    const existing = this.messageCache.get(msg.chatId) ?? [];
    // Deduplicate
    if (existing.some((m) => m.id === msg.id)) return;
    existing.push(msg);
    if (existing.length > 5000) existing.shift();
    this.messageCache.set(msg.chatId, existing);
  }

  private parseMessage(msg: proto.IWebMessageInfo): WAMessage | null {
    if (!msg.key) return null;

    // Extract the actual message content (unwraps protocol buffer wrappers)
    const content = msg.message ? extractMessageContent(msg.message) : null;

    const text =
      content?.conversation ??
      content?.extendedTextMessage?.text ??
      content?.imageMessage?.caption ??
      content?.videoMessage?.caption ??
      content?.buttonsResponseMessage?.selectedDisplayText ??
      content?.listResponseMessage?.title ??
      content?.templateButtonReplyMessage?.selectedDisplayText ??
      "";

    let mediaType: string | undefined;
    if (content?.imageMessage) mediaType = "image";
    else if (content?.videoMessage) mediaType = "video";
    else if (content?.audioMessage) mediaType = "audio";
    else if (content?.documentMessage) mediaType = "document";
    else if (content?.stickerMessage) mediaType = "sticker";
    else if (content?.contactMessage) mediaType = "contact";
    else if (content?.locationMessage) mediaType = "location";

    // Skip messages with no text and no media
    if (!text && !mediaType) return null;

    const chatId = msg.key.remoteJid ?? "";
    const isGroup = chatId.endsWith("@g.us");
    const senderId = msg.key.participant ?? msg.key.remoteJid ?? "";

    const quotedConvo = content?.extendedTextMessage?.contextInfo?.quotedMessage?.conversation;

    return {
      id: msg.key.id ?? "",
      chatId,
      sender: senderId,
      senderName: msg.pushName ?? this.contactNames.get(senderId) ?? senderId,
      timestamp: typeof msg.messageTimestamp === "number"
        ? msg.messageTimestamp
        : Number(msg.messageTimestamp ?? 0),
      text,
      isFromMe: msg.key.fromMe ?? false,
      isGroup,
      quotedText: quotedConvo ?? undefined,
      mediaType,
    };
  }
}
