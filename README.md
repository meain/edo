# Edo

A Claude-Code-style agent app for Android. Edo gives you a chat-driven
coding agent that operates on a folder you pick on your device — read,
write, edit, copy, delete files, grep across them, and call HTTP APIs,
with optional per-tool approval gates.

## Highlights

- **LLM providers**: Anthropic Messages API or any OpenAI-compatible
  Chat Completions endpoint (OpenRouter, Ollama, LM Studio, etc.) with
  streaming + native tool use.
- **Workspace tools**: `read_file`, `write_file`, `edit_file` (string
  replacement), `copy_file`, `delete_file`, `ls`, `grep`, `http_request`,
  `current_datetime`, and `load_skill`.
- **Projects + threads**: each project pins a workspace folder; threads
  are Claude-Code-style chat sessions scoped to a project.
- **Per-project settings**: description and YOLO mode (auto-approve all
  tool calls) live on the project entity, not globally.
- **File browser**: built-in browser for the workspace folder with
  Markdown preview, long-press menu for "Open with…" (system chooser)
  and "Delete".
- **Skills**: drop Markdown files under `.edo/skills/` or
  `.agents/skills/` in a workspace; the agent discovers them and can
  `load_skill` on demand.
- **AGENTS.md**: a top-level `AGENTS.md` in the workspace is included in
  the system prompt.

## Build & install

Toolchain: AGP 8.5.2, Kotlin 2.0.20 (compose-compiler plugin), Gradle
wrapper 8.10.2, JDK 17.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export PATH=$JAVA_HOME/bin:$PATH
export ANDROID_HOME=$HOME/Android/sdk-edo

./gradlew --no-daemon :app:assembleDebug
./gradlew --no-daemon :app:testDebugUnitTest
./gradlew --no-daemon :app:compileDebugKotlin   # fastest sanity check
```

APK lands at `app/build/outputs/apk/debug/app-debug.apk`. Use
`--no-daemon` — long-lived Gradle daemons have caused issues here.

Install over ADB:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.edo.app/com.edo.app.MainActivity
```

## Workspaces

Project workspaces live under `/sdcard/Edo/<name>` on the device; the
SAF folder picker pre-navigates there. The app holds the per-folder
`OpenDocumentTree` grant only — no `MANAGE_EXTERNAL_STORAGE`. Folder
permissions don't always survive reinstalls; re-pick the folder if
that happens.

## Architecture

```
app/src/main/java/com/edo/app/
├── data/        Room DB (projects, threads, messages); encrypted prefs
├── llm/         AnthropicClient, OpenAIClient, shared streaming types
├── agent/       Agent loop, ApprovalGate, tools, Workspace abstractions
└── ui/
    ├── chat/    Main screen + Markdown rendering
    ├── projects/ Project list, create/edit sheets, SAF picker
    ├── threads/ Per-project thread list
    ├── files/   File browser with Markdown preview + actions
    └── settings/ Provider/key/model
```

Threads are scoped to projects, messages to threads.
`AppSettings.activeProjectId`/`activeThreadId` persist the current
selection.

## Status

Active development. See `REQUIREMENTS.md` for the v1 scope and roadmap,
and `CLAUDE.md` for repo-specific notes used by Claude Code.
