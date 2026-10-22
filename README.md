# Edo

A Claude Code-style agent app for Android. Edo gives you a chat-driven coding agent that operates on a folder you pick on your device — read, write, edit, grep files, call HTTP APIs, and more, with optional per-tool approval gates.

<table align="center">
  <tr>
    <th align="center">Chat</th>
    <th align="center">Chats</th>
    <th align="center">Files</th>
  </tr>
  <tr>
    <td><img src="https://github.com/user-attachments/assets/d792db43-0a2b-4a29-85f7-3ffe170ca1be" width="220" alt="Edo chat screen with an assistant reply rendered as Markdown headings and bullet lists" /></td>
    <td><img src="https://github.com/user-attachments/assets/ac5b22bb-cb46-438a-a84e-1de9aadac6e8" width="220" alt="Chat list panel with the project card, a New chat button and recent chats grouped by day, with Stats and Settings icons in the header" /></td>
    <td><img src="https://github.com/user-attachments/assets/28d532e3-e182-4ffb-8ce4-db91d00baa7e" width="220" alt="File browser panel for the project workspace with a breadcrumb row and a short list of files and folders" /></td>
  </tr>
  <tr>
    <th align="center">Schedule</th>
    <th align="center">Project settings</th>
    <th align="center">Stats</th>
  </tr>
  <tr>
    <td><img src="https://github.com/user-attachments/assets/1bed98c4-a58f-4588-ae48-5fa1e8c2eb49" width="220" alt="Edit schedule sheet for a daily 08:00 schedule with its prompt filled in" /></td>
    <td><img src="https://github.com/user-attachments/assets/fdaba801-4cc6-4059-abef-e0c1d2a01f57" width="220" alt="Project settings sheet with name, description, workspace folder and toggles for YOLO mode, Lua network access and override values" /></td>
    <td><img src="https://github.com/user-attachments/assets/fd4bcbb4-d524-4caf-8b0d-ffd251427c98" width="220" alt="Stats page with all-time, 7-day and 30-day spend and token totals, followed by spend-over-time and messages-over-time bar charts" /></td>
  </tr>
</table>

## Features

- **LLM providers** — Anthropic Messages API, any OpenAI-compatible Chat Completions endpoint (Ollama, LM Studio, etc.) or OpenRouter (with its routing options), all with streaming and native tool use. Save connections as profiles, reorder them by long-press and drag, and let a project override the profile
- **Workspace tools** — `read` (file, directory listing or glob), `write_file`, `edit_file` (string replacement), `copy_file`, `delete_file`, `grep`, `fetch_page` (article as Markdown), `web_search`, `current_datetime`, `ask_user`, `calculator`, `load_skill`, `run_lua`, `schedule`
- **Lua** — `run_lua` runs a sandboxed Lua 5.2 program with workspace file access, and HTTP when the project allows it
- **Projects & threads** — each project pins a workspace folder; threads are chat sessions scoped to a project. Tap the project card in the chat list to switch projects
- **Per-project settings** — description, YOLO mode (auto-approve all tool calls), Lua network access and model overrides on the project, not globally
- **Sending** — tap to send now (interrupting a running turn), or long-press for "Send at end of turn"; queued messages can be edited, sent now or cancelled
- **Compact a chat** — summarise a chat into a new one from the overflow menu. Each compaction leaves an inline link in the original at that point, and the new chat links back to it. You can compact the same chat at several points
- **Tool call details** — expand any tool call, or tap its icon or long-press it, for a full-screen view of every argument and the whole output, with Lua syntax highlighting
- **Schedules** — run a prompt once, on an interval, daily, weekly or monthly as an unattended chat that notifies you with the result. Cards show the kind, cadence and next run, expand into the prompt and run history, and reorder by long-press and drag. A scheduled chat can address its own schedule as `me`
- **Stats and cost** — token and cost totals per chat, project and model, spend and message charts over time, tool counts, and each chat's current context size. Chat details (the info button in the chat list) show counts, models and tools for one chat
- **File browser** — built-in browser for the workspace folder with Markdown preview and long-press actions (open with system chooser, delete)
- **Image input** — attach photos or screenshots from the gallery; sent as multimodal content to the model
- **Skills** — drop Markdown files under `.agents/skills/` in a workspace (flat `.md` or [agentskills.io](https://agentskills.io) folder format with `SKILL.md`); the agent discovers and loads them on demand
- **AGENTS.md** — a top-level `AGENTS.md` (or `CLAUDE.md`) in the workspace is included in the system prompt
- **Background execution** — foreground service keeps the agent running with a persistent notification while a turn is in progress

## Build

Toolchain: AGP 8.5.2, Kotlin 2.0.20 (compose-compiler plugin), Gradle wrapper 8.10.2, JDK 17. `flake.nix` provides the JDK and sets `ANDROID_HOME` (the SDK at `~/Android/sdk-edo` is managed outside nix), so run Gradle through `nix develop`:

```bash
nix develop --command bash -c './gradlew --no-daemon :app:assembleDebug'
```

APK lands at `app/build/outputs/apk/debug/app-debug.apk`. Use `--no-daemon` — long-lived Gradle daemons cause issues here.

Other useful tasks:

```bash
./gradlew --no-daemon :app:compileDebugKotlin   # fastest sanity check
./gradlew --no-daemon :app:testDebugUnitTest    # unit tests
```

The `Makefile` wraps these; run `make` to list targets. With a device connected over ADB (wireless debugging), `make install-debug` builds and installs the debug APK, and `make install-release` builds the release variant, signs it with `~/.android/debug.keystore` and installs it over a debug build without wiping data. Override the device with `DEVICE=<id>`. Judge scroll smoothness on the release build; the debug build is jittery on its own.

Install by hand:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.edo.app/com.edo.app.MainActivity
```

Releases: bump `versionName` / `versionCode` in `app/build.gradle.kts`, tag `vX.Y.Z` and push the tag; the release workflow builds the APK and publishes a GitHub Release.

## Workspaces

Project workspaces live under `/sdcard/Edo/<name>` on the device; the SAF folder picker pre-navigates there. The app holds the per-folder `OpenDocumentTree` grant only — no `MANAGE_EXTERNAL_STORAGE`. Folder permissions don't always survive reinstalls; re-pick the folder if that happens.

## Architecture

```
app/src/main/java/com/edo/app/
├── data/        Room DB (projects, threads, messages, schedules, usage, compactions); encrypted prefs
├── llm/         AnthropicClient, OpenAIClient, shared streaming types, price book
├── agent/       Agent loop, ApprovalGate, tools, Workspace abstractions
├── schedule/    ScheduleManager, recurrence maths, alarm receiver, notifications
└── ui/
    ├── chat/    Chat screen, sessions that outlive the screen, Markdown, tool details, compaction
    ├── projects/ Project list, create/edit sheets, SAF picker
    ├── threads/ Chat list, project switcher, chat details
    ├── files/   File browser with Markdown preview + actions
    ├── schedules/ Schedule cards, editor and run history
    ├── stats/   Usage, cost and tool charts
    └── settings/ Provider/key/model, profiles
```

Threads are scoped to projects, messages to threads. `AppSettings.activeProjectId`/`activeThreadId` persist the current selection. More detail, and the conventions to follow, are in `AGENTS.md` (`CLAUDE.md` is a symlink to it).
