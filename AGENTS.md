# Edo

Claude-Code-style agent app for Android. Kotlin + Jetpack Compose, Material 3 with Material You dynamic colors. Talks to Anthropic Messages API or OpenAI-compatible Chat Completions with streaming + tool use, exposing file/grep/http tools scoped to a workspace folder.

## Architecture

- `app/src/main/java/com/edo/app/`
  - `data/` — Room DB (`ProjectEntity`, `ThreadEntity`, `MessageEntity`, `ScheduleEntity`; currently v7), encrypted prefs for API key/provider/model/active IDs
  - `llm/` — `AnthropicClient`, `OpenAIClient`, shared `LlmClient` + streaming `LlmEvent` types
  - `agent/` — `Agent` loop, `ApprovalGate`, tool implementations (see Tools below), `SafWorkspace` / `FileWorkspace` / `InMemoryWorkspace`
  - `schedule/` — scheduled prompts: `ScheduleManager` (save/run/re-arm), `Recurrence` (next-run maths), `ScheduleReceiver` (alarm + boot/time broadcasts), `ScheduleNotifier`
  - `ui/SwipePanels.kt` — container giving the chat screen a left (chats) and right (files) full-width panel that follow the finger
  - `ui/chat/` — `ChatScreen` + `ChatViewModel` (the main screen); `ChatSession` / `ChatSessions` hold each chat's conversation and agent run on the app scope
  - `ui/projects/` — project list as a top card (dialog route) + create/edit sheets (SAF folder picker pre-navigated to `/sdcard/Edo`)
  - `ui/threads/` — per-project chat list, shown as the left panel of the chat screen
  - `ui/files/` — workspace file browser, shown as the right panel; Markdown preview and long-press actions
  - `ui/schedules/` — per-project schedule list + editor, with Run now
  - `ui/settings/` — provider/key/model, plus an optional weak model for side tasks like chat titles (`AppSettings.forSideTasks`, blank falls back to the main model)
- `MainActivity.kt` — single activity, edge-to-edge, NavHost with `chat` (hosts the swipe panels), `projects` (dialog), `schedules`, `settings`, `share` destinations

Threads are scoped to projects, messages to threads. `AppSettings.activeProjectId`/`activeThreadId` persist the current selection. Switching projects clears the active thread.

## Tools

14 tools total, all scoped to the project workspace:

| Tool | Description |
|------|-------------|
| `read` | Read a path: a file returns text (`offset`/`limit` lines, max 256 KB, truncates with warning); a directory lists entries; a directory plus `pattern` (glob) searches the tree for matching files (max 500) |
| `write_file` | Create or overwrite an entire file |
| `edit_file` | Replace exact text in a file (`replace_all` flag available) |
| `copy_file` | Copy a file within the workspace |
| `delete_file` | Delete a file or directory recursively |
| `grep` | Regex search across text files (max 500 matches) |
| `http_request` | Make HTTP requests (any method, custom headers) |
| `web_search` | DuckDuckGo Lite search (lynx user agent) returning titles, URLs, snippets |
| `current_datetime` | Current date/time with zone, epoch, week info |
| `ask_user` | Ask the user a question with predefined options or freetext |
| `calculator` | Evaluate math expressions (supports +, -, *, /, ^, sqrt, sin, cos, tan, log, ln, exp, etc.) |
| `load_skill` | Load a skill Markdown file from `.agents/skills/` |
| `run_lua` | Run a sandboxed Lua 5.2 program. Always on. Besides computation it has `fs.read/ls/exists/write/delete` backed by the project `Workspace` (so scoped exactly like the file tools) and, only when the project enables it (`ProjectEntity.luaNetwork`, off by default; DB column `lua_enabled`; toggle in Edit project), `http.get/post/request`. Globals are an allowlist (base, table, string, bit32, coroutine, math) — never `JsePlatform.standardGlobals()`, which exposes `luajava` (a bridge to any Java class, so to every project's folder grant) and `debug` |
| `schedule` | Create, list, get, update, delete, enable, disable scheduled prompts for the current project (not offered to scheduled runs) |

## Agent loop

- Max turns per run (Settings, default 50); a project can override the model and max turns (`ProjectEntity.modelOverride` / `maxTurnsOverride`, applied via `AppSettings.withOverrides`)
- Up to 5 retries on failure with exponential backoff (2 s, 4 s, 8 s, 15 s)
- System prompt is dynamically augmented with `AGENTS.md` from workspace root + discovered skills
- `ApprovalGate` prompts the user for each tool call unless yolo mode is on; once approved, that tool auto-approves for the rest of the session
- Dismissing the `ask_user` sheet resolves the tool call as an error ("dismissed") rather than leaving the run waiting

## Scheduling

- Schedules are per project: ONCE, INTERVAL (min 15 min), DAILY, WEEKLY. One exact alarm (`setExactAndAllowWhileIdle`, inexact fallback without `SCHEDULE_EXACT_ALARM`) always points at the earliest `next_run_at`; `ScheduleManager.rearm()` re-aims it after any change.
- `ScheduleReceiver` handles the alarm (`takeDue()` advances each due schedule, then `run()` executes it) and boot / app-update / clock / time-zone broadcasts (`resync()`). Runs missed while the phone was off are skipped, not replayed. `EdoApp.onCreate` only re-arms (never resyncs), so an alarm-started process doesn't skip its own run.
- Each run is an unattended `ChatSession` (`ChatSessions.unattended`) in a new thread titled "⏰ title · time": tool calls are denied unless the project is yolo, `ask_user` gets a canned answer, it never steals the on-screen chat, it gets no `schedule` tool, and it is cut off after 10 minutes. A run for a schedule already running is skipped. The outcome is posted via `ScheduleNotifier` (channel `schedule_results`); tapping it opens the thread.
- "Run now" in the schedules list launches on the app scope (leaving the screen must not cancel it) and shows a spinner via `ScheduleManager.running`.

## Skills

Skills are Markdown files discovered at startup from `.agents/skills/` in the workspace root. Supported formats:

- Flat file: `.agents/skills/<name>.md`
- agentskills.io folder format: `.agents/skills/<name>/SKILL.md`

The agent loads a skill's content on demand via `load_skill`. A built-in `create-skill` skill helps scaffold new ones.

## Build & install

Toolchain: AGP 8.5.2, Kotlin 2.0.20 (with compose-compiler plugin), Gradle wrapper 8.10.2, JDK 17. Android SDK at `~/Android/sdk-edo` (path is in `local.properties`).

The JDK 17 toolchain comes from the `flake.nix` dev shell (`nix develop` sets `JAVA_HOME` and `ANDROID_HOME`). The Android SDK is managed outside nix. Run Gradle through the shell:

```bash
nix develop --command bash -c './gradlew --no-daemon :app:assembleDebug'
nix develop --command bash -c './gradlew --no-daemon :app:testDebugUnitTest'
nix develop --command bash -c './gradlew --no-daemon :app:compileDebugKotlin'   # fastest sanity check
```

APK lands at `app/build/outputs/apk/debug/app-debug.apk`. Use `--no-daemon` — long-lived Gradle daemons have caused issues here.

## Device testing

The dev device is a Samsung Galaxy S25 paired over ADB wireless. See the `android-adb-connect` skill for connection details and `app/src/test/` for unit tests covering the agent loop, streaming clients, and tool boundaries.

```bash
adb -s 192.168.1.3:38181 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 192.168.1.3:38181 shell am force-stop com.edo.app
adb -s 192.168.1.3:38181 shell am start -n com.edo.app/com.edo.app.MainActivity
adb -s 192.168.1.3:38181 exec-out screencap -p > /tmp/edo.png
adb -s 192.168.1.3:38181 shell uiautomator dump /sdcard/window.xml   # then `cat | tr '>' '\n' | grep text=` to find tap coords
```

**Use Haiku subagents (`model: haiku`) for testing.** Run the test steps through one, and keep builds, installs and judging the results in the main session.

Delegate on-device navigation (tapping through screens, typing a prompt, scrolling a chat and reading tool results, screenshots) to a Haiku subagent (`model: haiku`). It has handled both reading a long chat transcript and launching, typing, sending and reading results. Give it the device id, the exact `adb` commands (nested quoting for `input text`, `%s` for spaces), the project it may touch, and tell it not to modify anything beyond the task. Keep builds, installs and judging the results in the main session.

The wireless port changes every time wireless debugging is toggled; expect to re-discover via `adb mdns services` or fall back to USB pairing. The device also shows up under its mDNS id (`adb-<serial>._adb-tls-connect._tcp`, see `adb devices`), which keeps working when the IP:port rotates.

## Workspace files

All project workspaces live under `/sdcard/Edo/<name>` on the device. The SAF picker pre-navigates there. The app holds only the per-folder `OpenDocumentTree` grant — no `MANAGE_EXTERNAL_STORAGE`. Permissions don't always survive reinstalls; the user may need to re-pick the folder.

### Per-project scoping

Each project's agent must only reach its own folder. The OS boundary is the per-folder SAF grant (no broad storage permission); inside the app all grants share one process, so the rest is enforced in code:

- Tool paths are relative to the workspace root; `.`/`..` segments are rejected (`WorkspaceScope.safeSegments`) and `FileWorkspace` compares canonical paths with a separator so a sibling like `Bubbly-secret` isn't "inside" `Bubbly`.
- A project's folder may not equal, contain, or sit inside another project's folder (`WorkspaceScope.overlaps`, checked when picking in the create/edit sheets). Picking the parent `Edo` folder would hand that project every sibling.
- Grants are released when a project is deleted or its folder changes, and grants no project points at are dropped at startup (`AppContainer.releaseGrant` / `releaseOrphanGrants`).
- `run_lua` has no Java bridge, `io` or `os`; its only file access is `fs.*` over the project's `Workspace`, and network is a per-project opt-in (see Tools). `RunLuaSandboxTest` and `WorkspaceScopeTest` guard these.
- The folder picker is registered on `MainActivity` (`AppContainer.launchFolderPicker` / `pickedFolder`), not in the sheets: it stops the activity, which disposes the projects dialog, so a callback registered there is lost. New-project form state lives in `NewProjectForm` (a ViewModel) for the same reason.

## Notes

- `android:windowSoftInputMode="adjustResize"` is required — without it, the IME pushes the topbar off-screen in edge-to-edge mode.
- `ChatViewModel.combine(activeProjectId, activeThreadId).collectLatest` has a `markLoaded` guard so that creating a thread from `sendUserMessage` doesn't trigger a redundant reload that races the message insert.
- Yolo mode is per-project (`ProjectEntity.yoloMode`), not global — it bypasses the approval bottom sheet so every tool call auto-approves for that project.
- Orphaned tool_use messages (assistant messages with unanswered tool calls) are stripped from the conversation on reload to avoid API rejections.
- `InMemoryWorkspace` is a HashMap-backed workspace used exclusively in unit tests — it is not exposed in the UI.
- `SwipePanels` drives both edge panels from one horizontal `draggable` on the chat route, so a child that consumes horizontal drags (code blocks, lists) wins. The chats panel stays composed offscreen to avoid a first-frame hitch; the files panel is composed on demand. The panels replaced the old `threads` / `files` nav routes.
- Pulling up past the end of a conversation (`ChatScreen`, nested scroll) suggests a new chat; the pull is resisted 2:1 and disabled while the agent runs.
- Bare URLs are linkified by commonmark's autolink extension, then rendered like `[text](url)` links.
- Room uses explicit migrations (3→4 … 6→7) but still `fallbackToDestructiveMigration`, so a missing migration silently wipes data — add one for every schema bump.
