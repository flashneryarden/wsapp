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

## Android task viewer

The `android/` project displays Firestore tasks in three `ViewPager2`/Fragment
sections:

- **Urgent** — critical, overdue, or due within three days
- **Open** — all pending tasks
- **Completed** — finished tasks

Task details support an exact due date and time plus a locally managed ringing
alarm. The user can schedule the alarm for 5 minutes, 1 hour, or 1 day before the
due time. Alarm selections are stored only on the phone that created them. Alarms
use `AlarmManager`, open a full-screen alarm UI, play the phone's alarm sound until
dismissed, reopen the matching task, cancel when the task is completed or deleted,
and restore after a device reboot or application update.

On recent Android versions, the first alarm setup may open system settings to
grant **Alarms & reminders** and **Full-screen alerts** access. After granting an
access request, return to the task and press **Set Alarm** again.

The task screens continue showing Firestore's cached data while offline. Status
banners distinguish cached data, locally pending writes, synchronization, and
sync failures; failed listeners can be retried by tapping the banner. AI image
analysis is disabled while offline and displays explicit configuration, HTTP,
empty-response, and malformed-response errors.

Firestore document IDs are the authoritative task identity. New Android, iOS,
and CLI tasks use generated document IDs, while legacy numeric document IDs
remain supported. Numeric task numbers are retained only as display/CLI
references; database operations, notification links, and local alarms use the
full document ID.

Build the debug APK with:

```powershell
cd android
.\gradlew.bat assembleDebug
```

The APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.
