# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
npm install          # Install dependencies
npm run build        # Compile TypeScript → dist/
npm run dev          # Run directly with tsx (no build needed)
npm start            # Run compiled output
```

There are no tests in this project.

## Architecture

**wsapp** is a WhatsApp CLI that connects via the [Baileys](https://github.com/WhiskeySockets/Baileys) WebSocket protocol, auto-analyzes incoming messages with AI, and forwards flagged messages to a designated WhatsApp group.

### Data flow

1. `WhatsAppClient` (`src/whatsapp.ts`) wraps Baileys and maintains in-memory caches:
   - `messageCache`: `Map<chatId, WAMessage[]>` — max 5000 messages per chat
   - `chatCache`: `Map<chatId, WAChatInfo>` — chat metadata
   - `contactNames`: `Map<id, name>` — resolved display names
   - History is populated from Baileys `messaging-history.set` on connect; real-time messages arrive via `messages.upsert` with `type === "notify"`

2. `src/index.ts` is the interactive REPL. It registers a `wa.onMessage()` handler that calls `analyzeMessage()` on every real-time message and forwards important/task messages to the group named `"ירדן החשוב"`.

3. `src/ai.ts` uses `@github/copilot-sdk` to analyze messages. It maintains a **singleton** `CopilotClient` + `CopilotSession`. The session is re-used across calls; call `stopAI()` on exit to clean up.

### Module system

The project uses **ESM** (`"type": "module"`) with TypeScript `NodeNext` module resolution. All internal imports must use `.js` extensions (e.g., `import { ... } from "./whatsapp.js"`).

### Credentials & auth

- `auth_info/` — WhatsApp session credentials (gitignored). Delete this directory and run `reconnect` to pair a new phone.
