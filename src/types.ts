export interface WAChatInfo {
  id: string;
  name: string;
  isGroup: boolean;
  unreadCount: number;
  lastMessageTimestamp: number | null;
  participantCount?: number;
}

export interface WAMessage {
  id: string;
  chatId: string;
  sender: string;
  senderName: string;
  timestamp: number;
  text: string;
  isFromMe: boolean;
  isGroup: boolean;
  quotedText?: string;
  mediaType?: string;
}

export interface WAContactInfo {
  id: string;
  name: string;
  isGroup: boolean;
  participants?: { id: string; name: string; isAdmin: boolean }[];
  description?: string;
}

export interface Task {
  id: number;
  origSender: string;
  origChatName: string;
  text: string;
  summary: string;
  actionItems: string[];
  createdAt: string;
  status: "pending" | "done";
  completedAt: string | null;
  notes: string[];
  critical: boolean;
  dueDate: string | null;
}

export interface Reminder {
  id: number;
  taskId: number;
  triggerAt: string;
  sent: boolean;
}
