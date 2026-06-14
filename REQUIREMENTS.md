# EDO — Claude Code for your phone

An Android app that gives you a Claude-Code-style agent on your phone: a chat
interface backed by an LLM that can read/write files in a folder you choose,
make HTTP requests, and grep through your stuff — with approve-before-execute
gates on every tool call.

## Goal

Reproduce the core Claude Code experience on Android: send a message, watch
the model think and call tools (streamed), approve or reject each tool call,
see the results in line, continue the conversation.

## Scope (v1)

### Platform
- **Android only** (Kotlin + Jetpack Compose)
- **Min SDK**: 31 (Android 12)
- **Target SDK**: 34
- Deliverable: full Gradle project, buildable in Android Studio

### LLM providers
The app talks to a remote LLM — no on-device inference. Configured in a
settings screen by **base URL + API key + model name**. Two protocols
supported:
- **Anthropic native** — `POST /v1/messages` with streaming + native tool use
- **OpenAI-compatible** — Chat Completions endpoint (`/v1/chat/completions`)
  with streaming + tool calls. Works with OpenAI, OpenRouter, Ollama,
  LM Studio, etc.

Provider is selected per-session; credentials are persisted via
EncryptedSharedPreferences (or DataStore with crypto).

### Agent loop
- Streaming responses (token-by-token rendering)
- Native tool-use protocol of the chosen provider
- Multi-turn: the model can call tools, see results, and call again until done
- Image input: user can attach screenshots / photos; sent as multimodal content
- Conversation history persisted in Room (one chat per app for v1)

### Tools available to the agent
All tools are scoped to a single user-picked root folder (Storage Access
Framework / `DocumentFile`).

- `read_file(path)` — read a text file under the workspace
- `write_file(path, content)` — write/overwrite a text file under the workspace
- `ls(path)` — list directory contents
- `grep(pattern, path)` — regex search across files under `path`
- `http_request(url, method, headers, body)` — make HTTP requests

**Approve-before-execute**: every tool call opens a confirmation sheet showing
the tool, args, and a preview where relevant. User can approve once, deny, or
"always allow this tool this session".

### UI surface
- **Chat screen** — message list, input box, attach-image button, streaming
  output, inline tool-call cards (collapsed by default, expandable to see
  args/result), approval sheets
- **Settings screen** — provider (Anthropic | OpenAI-compatible), base URL,
  API key, model name, "pick workspace folder" button (SAF), reset session

### Storage
- **Workspace** — user-picked SAF folder (`DocumentFile` API). The agent's
  filesystem tools operate only inside this tree.
- **App data** — Room database for chat history; EncryptedSharedPreferences
  (or equivalent) for API key and config.

## Out of scope (v1)

These were in the earlier draft but are explicitly deferred:

- Multi-agent personas / "products" system — single agent only
- On-device LLM inference (GGUF / Gemma / Llama / Phi) — remote API only
- Lua scripting / sandbox interpreter — replaced by direct tool calls
- Shell `execute()` tool — no
- Cloud sync, marketplace, agent sharing
- Voice input/output
- Background execution / scheduling
- Multi-device support

## Known trade-offs

- **SAF performance**: `DocumentFile` walks the tree for each path lookup, so
  large workspaces will feel slow on tools like `grep`. Acceptable for v1; can
  swap to app-private storage or cache resolved URIs later.
- **No on-device fallback**: requires internet + a working API key. By design.

## Tech stack

- **Language**: Kotlin
- **UI**: Jetpack Compose + Material 3
- **Async**: Kotlin Coroutines + Flow
- **HTTP**: Ktor client (CIO engine) with SSE for streaming
- **JSON**: kotlinx.serialization
- **Persistence**: Room (chat history), EncryptedSharedPreferences (secrets)
- **File access**: AndroidX `DocumentFile` (SAF)
- **DI**: manual (no Hilt for v1 — keep deps small)

## Project layout

```
edo/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/libs.versions.toml
├── gradle/wrapper/...
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/...
        └── java/com/edo/app/
            ├── EdoApp.kt           # Application
            ├── MainActivity.kt     # Compose entry
            ├── ui/                 # screens, theme, components
            ├── data/               # Room, settings store
            ├── llm/                # Anthropic + OpenAI clients
            └── agent/              # agent loop, tools, executor
```

## Success criteria

1. App builds with `./gradlew assembleDebug` and installs on a phone
2. From Settings, user can configure either Anthropic or an OpenAI-compatible
   provider with a base URL, key, and model
3. User picks a workspace folder (SAF) and the agent can read/write/list/grep
   inside it
4. Sending a message produces a streamed response; tool calls trigger an
   approval sheet; approved calls execute and the result flows back to the
   model in the same turn
5. Conversation persists across app restarts
6. Image attached from gallery flows through as multimodal content

## Future enhancements (deferred)

- Multi-agent personas with per-agent workspaces and system prompts
- On-device GGUF model option (MediaPipe / llama.cpp)
- Lua scripting escape hatch for batch operations
- Background tasks / Android intents integration
- Voice in/out
- Shell execute tool (sandboxed)
