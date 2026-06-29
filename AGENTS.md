# Edo

Claude-Code-style agent app for Android. Kotlin + Jetpack Compose, Material 3 with Material You dynamic colors. Talks to Anthropic Messages API or OpenAI-compatible Chat Completions with streaming + tool use, exposing file/grep/http tools scoped to a workspace folder.

## Architecture

- `app/src/main/java/com/edo/app/`
  - `data/` — Room DB (`ProjectEntity`, `ThreadEntity`, `MessageEntity`), encrypted prefs for API key/provider/model/active IDs
  - `llm/` — `AnthropicClient`, `OpenAIClient`, shared `LlmClient` + streaming `LlmEvent` types
  - `agent/` — `Agent` loop, `ApprovalGate`, tool implementations (see Tools below), `SafWorkspace` / `FileWorkspace` / `InMemoryWorkspace`
  - `ui/chat/` — `ChatScreen` + `ChatViewModel` (the main screen)
  - `ui/projects/` — project list + create/edit sheets (SAF folder picker pre-navigated to `/sdcard/Edo`)
  - `ui/threads/` — per-project thread list (Claude Code-style chat sessions)
  - `ui/files/` — workspace file browser with Markdown preview and long-press actions
  - `ui/settings/` — provider/key/model
- `MainActivity.kt` — single activity, edge-to-edge, NavHost with `chat`/`projects`/`threads`/`settings`/`files` destinations

Threads are scoped to projects, messages to threads. `AppSettings.activeProjectId`/`activeThreadId` persist the current selection. Switching projects clears the active thread.

## Tools

12 tools total, all scoped to the project workspace:

| Tool | Description |
|------|-------------|
| `read_file` | Read text files (max 256 KB, truncates with warning) |
| `write_file` | Create or overwrite an entire file |
| `edit_file` | Replace exact text in a file (`replace_all` flag available) |
| `copy_file` | Copy a file within the workspace |
| `delete_file` | Delete a file or directory recursively |
| `ls` | List directory contents with sizes |
| `grep` | Regex search across text files (max 500 matches) |
| `http_request` | Make HTTP requests (any method, custom headers) |
| `current_datetime` | Current date/time with zone, epoch, week info |
| `ask_user` | Ask the user a question with predefined options or freetext |
| `calculator` | Evaluate math expressions (supports +, -, *, /, ^, sqrt, sin, cos, tan, log, ln, exp, etc.) |
| `load_skill` | Load a skill Markdown file from `.agents/skills/` |

## Agent loop

- Max 20 turns per conversation
- Up to 5 retries on failure with exponential backoff (2 s, 4 s, 8 s, 15 s)
- System prompt is dynamically augmented with `AGENTS.md` from workspace root + discovered skills
- `ApprovalGate` prompts the user for each tool call unless yolo mode is on; once approved, that tool auto-approves for the rest of the session

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

The wireless port changes every time wireless debugging is toggled; expect to re-discover via `adb mdns services` or fall back to USB pairing.

## Workspace files

All project workspaces live under `/sdcard/Edo/<name>` on the device. The SAF picker pre-navigates there. The app holds only the per-folder `OpenDocumentTree` grant — no `MANAGE_EXTERNAL_STORAGE`. Permissions don't always survive reinstalls; the user may need to re-pick the folder.

## Notes

- `android:windowSoftInputMode="adjustResize"` is required — without it, the IME pushes the topbar off-screen in edge-to-edge mode.
- `ChatViewModel.combine(activeProjectId, activeThreadId).collectLatest` has a `markLoaded` guard so that creating a thread from `sendUserMessage` doesn't trigger a redundant reload that races the message insert.
- Yolo mode is per-project (`ProjectEntity.yoloMode`), not global — it bypasses the approval bottom sheet so every tool call auto-approves for that project.
- Orphaned tool_use messages (assistant messages with unanswered tool calls) are stripped from the conversation on reload to avoid API rejections.
- `InMemoryWorkspace` is a HashMap-backed workspace used exclusively in unit tests — it is not exposed in the UI.
