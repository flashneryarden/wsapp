# wsapp — WhatsApp CLI

Interactive command-line tool to connect to WhatsApp, browse chats, read messages, and search conversations using [Baileys](https://github.com/WhiskeySockets/Baileys).

## Setup

```bash
npm install
npm run build
```

## Usage

```bash
npm start
# or
npx tsx src/index.ts
```

On first run, a QR code will appear in the terminal. Scan it with **WhatsApp > Linked Devices > Link a Device** on your phone. Credentials are saved in `auth_info/` for subsequent sessions.

## Commands

| Command | Description |
|---------|-------------|
| `connect` | Connect to WhatsApp (QR code on first use) |
| `chats [limit]` | List chats sorted by recent activity |
| `messages <id> [count]` | Show messages from a chat (use chat # or ID) |
| `msgs <id> [count]` | Alias for `messages` |
| `unread` | Show all unread messages |
| `search <keyword>` | Search cached messages by keyword |
| `info <id>` | Show contact/group details |
| `status` | Show connection and cache stats |
| `help` | Show available commands |
| `quit` | Exit |

Chat IDs look like `1234567890@s.whatsapp.net` (DM) or `...@g.us` (group). You can also use the numeric index shown by `chats`.

## How It Works

- Connects to WhatsApp via the Baileys WebSocket protocol (no browser required)
- Messages and chats are cached in memory as they arrive
- Search operates over cached messages — the longer you stay connected, the more history is available

## Project Structure

```
src/
├── index.ts       # Interactive CLI (REPL)
├── whatsapp.ts    # Baileys client wrapper
├── ai.ts          # Copilot SDK message analysis
└── types.ts       # TypeScript type definitions
```

## Notes

- `auth_info/` contains your WhatsApp session credentials — **do not commit this**
- This uses an unofficial WhatsApp Web protocol; use responsibly
- Messages are cached in memory only (not persisted to disk)
