# Edo

Claude-Code-style agent app for Android. Kotlin + Jetpack Compose, Material 3 with Material You dynamic colors. Talks to Anthropic Messages, OpenAI-compatible Chat Completions or OpenRouter, with streaming + tool use, exposing file/grep/http tools scoped to a workspace folder.

## Architecture

- `app/src/main/java/com/edo/app/`
  - `data/` — Room DB (`ProjectEntity`, `ThreadEntity`, `MessageEntity`, `ScheduleEntity`; currently v8), encrypted prefs (`Settings.kt`) for provider/key/model/OpenRouter options/active IDs
  - `llm/` — `AnthropicClient`, `OpenAIClient` (also used for OpenRouter, with `OpenRouterOptions.requestExtras`), `fetchModelIds` for model autocomplete, shared `LlmClient` + streaming `LlmEvent` types
  - `agent/` — `Agent` loop, `ApprovalGate`, tools, `SafWorkspace` / `FileWorkspace` / `InMemoryWorkspace`
  - `schedule/` — `ScheduleManager` (save/run/re-arm), `Recurrence` (next-run maths), `ScheduleReceiver` (alarm + boot/time broadcasts), `ScheduleNotifier`
  - `ui/SwipePanels.kt` — gives the chat screen a left (chats) and right (files) full-width panel that follow the finger
  - `ui/chat/` — `ChatScreen` + `ChatViewModel`; `ChatSession` / `ChatSessions` hold each chat's conversation and agent run on the app scope; `RunNotifier` (finished-run notification), `Speaker` (text-to-speech)
  - `ui/projects/` — project list as a top card (dialog route) + create/edit sheets (SAF folder picker pre-navigated to `/sdcard/Edo`)
  - `ui/threads/` — chat list (left panel): newest 15 shown, the rest folded; scheduled chats get an alarm icon
  - `ui/files/` — workspace file browser (right panel): Markdown preview, long-press actions, open in another app
  - `ui/schedules/` — per-project schedule list + editor, with Run now
  - `ui/settings/` — provider/key/model with autocomplete, optional weak model for side tasks like chat titles (`AppSettings.forSideTasks`), OpenRouter routing options
  - `ui/OpenExternal.kt` — hands files to other apps via `FileProvider` (copies `file://` and in-memory text into `cacheDir/open/`)
- `MainActivity.kt` — single activity, edge-to-edge, NavHost with `chat` (hosts the swipe panels), `projects` (dialog), `schedules`, `settings`, `share`

Threads are scoped to projects, messages to threads. `AppSettings.activeProjectId`/`activeThreadId` persist the current selection; switching projects clears the active thread.

## Tools

14 tools, all scoped to the project workspace: `read` (file, directory listing, or glob search), `write_file`, `edit_file`, `copy_file`, `delete_file`, `grep`, `fetch_page`, `web_search` (DuckDuckGo Lite), `current_datetime`, `ask_user`, `calculator`, `load_skill`, `run_lua`, `schedule` (not offered to scheduled runs).

`fetch_page` GETs a URL and returns the article as Markdown (Readability4J picks the article, our Jsoup walker converts it; falls back to `article`/`main`/body), `raw: true` for the full HTML, and pages long output with `offset`. There is no general HTTP tool: other methods and headers go through Lua `http.*`.

`run_lua` is a sandboxed Lua 5.2 program, always on. It has `fs.read/ls/exists/write/delete` over the project `Workspace`, and `http.get/post/request` only when the project enables it (`ProjectEntity.luaNetwork`, off by default; DB column `lua_enabled`). Globals are an allowlist (base, table, string, bit32, coroutine, math) — never `JsePlatform.standardGlobals()`, which exposes `luajava` (a bridge to any Java class, so to every project's folder grant) and `debug`.

## Agent loop

- Max turns per run (Settings, default 50); a project can override the model and max turns (`ProjectEntity.modelOverride` / `maxTurnsOverride`, via `AppSettings.withOverrides`)
- Up to 5 retries on failure with exponential backoff (2 s, 4 s, 8 s, 15 s)
- System prompt is augmented with the workspace-root `AGENTS.md` and discovered skills
- `ApprovalGate` prompts for each tool call unless the project's yolo mode (`ProjectEntity.yoloMode`) is on; once approved, a tool auto-approves for the rest of the session
- Dismissing the `ask_user` sheet resolves the call as an error ("dismissed")
- When a run ends with the app in the background (and nothing is queued, not cancelled, not a scheduled run), `RunNotifier` posts a notification that opens the chat. Foreground state comes from `AppContainer.inForeground`.

## Scheduling

- Schedules are per project: ONCE, INTERVAL (min 15 min), DAILY, WEEKLY, MONTHLY (short months run on their last day). One exact alarm (`setExactAndAllowWhileIdle`, inexact fallback without `SCHEDULE_EXACT_ALARM`) always points at the earliest `next_run_at`; `ScheduleManager.rearm()` re-aims it after any change.
- `ScheduleReceiver` handles the alarm (`takeDue()` advances each due schedule, then `run()` executes it) and boot / app-update / clock / time-zone broadcasts (`resync()`). Missed runs are skipped, not replayed. `EdoApp.onCreate` only re-arms (never resyncs), so an alarm-started process doesn't skip its own run.
- Each run is an unattended `ChatSession` (`ChatSessions.unattended`) in a new thread titled "⏰ title · time" (the chat list keys its alarm icon off that prefix): tool calls are denied unless the project is yolo, `ask_user` gets a canned answer, it never steals the on-screen chat, gets no `schedule` tool, and is cut off after 10 minutes. A run for a schedule already running is skipped. The outcome goes through `ScheduleNotifier` (channel `schedule_results`); tapping it opens the thread.
- "Run now" launches on the app scope (leaving the screen must not cancel it) and shows a spinner via `ScheduleManager.running`.

## Cost meter and run history

- Both clients emit `LlmEvent.Usage` (Anthropic from `message_start`/`message_delta`; OpenAI-style needs `stream_options.include_usage`, and OpenRouter also `usage.include` for its own `cost`). `ChatSession.recordUsage` adds each turn to the thread (`ThreadEntity.inputTokens/outputTokens/costUsd/costPartial`), shown under the project name in the chat top bar. Chat-title generation is not counted.
- Cost is the provider-reported figure, else `PriceBook` (LiteLLM `model_prices_and_context_window.json`, cached in `filesDir`, refreshed weekly). A model with no price adds tokens but no cost and sets `costPartial`, shown as `$0.04+`.
- `ScheduleRunEntity` (`schedule_runs`) gets one row per run, including skipped and failed ones, copying the thread totals; the newest 30 per schedule are kept. The history icon on a schedule row lists them and opens the run's chat.

## Skills

Markdown files discovered at startup from `.agents/skills/` in the workspace root, either flat (`<name>.md`) or agentskills.io folders (`<name>/SKILL.md`). The agent loads one on demand via `load_skill`; a built-in `create-skill` skill scaffolds new ones.

## Build & release

Toolchain: AGP 8.5.2, Kotlin 2.0.20, Gradle 8.10.2, JDK 17 from the `flake.nix` dev shell (`nix develop` sets `JAVA_HOME` and `ANDROID_HOME`; the SDK at `~/Android/sdk-edo` is managed outside nix). Run Gradle through it, with `--no-daemon` (long-lived daemons have caused issues):

```bash
nix develop --command bash -c './gradlew --no-daemon :app:assembleDebug'
nix develop --command bash -c './gradlew --no-daemon :app:testDebugUnitTest'
nix develop --command bash -c './gradlew --no-daemon :app:compileDebugKotlin'   # fastest sanity check
```

The release variant is unsigned and not minified. To install one over a debug build without wiping data, zipalign it and sign with `~/.android/debug.keystore` (`apksigner`).

Judge scroll smoothness on a release build: the debug build is visibly jittery on its own.

Releases: bump `versionName` / `versionCode` in `app/build.gradle.kts` in a "Bump version to X" commit, tag it `vX.Y.Z` and push the tag; `.github/workflows/release.yml` builds the APK and publishes a GitHub Release.

## Device testing

The dev device is a Samsung Galaxy S25 over ADB wireless; see the `android-adb-connect` skill. The wireless port changes whenever wireless debugging is toggled, so address the device by its mDNS id (`adb-<serial>._adb-tls-connect._tcp`, from `adb devices`), which survives that. Unit tests in `app/src/test/` cover the agent loop, streaming clients, OpenRouter options and tool boundaries.

```bash
adb -s <id> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <id> shell am force-stop com.edo.app
adb -s <id> shell am start -n com.edo.app/com.edo.app.MainActivity
adb -s <id> exec-out screencap -p > /tmp/edo.png
adb -s <id> shell uiautomator dump /sdcard/window.xml   # then `cat | tr '>' '\n' | grep text=` to find tap coords
adb -s <id> shell dumpsys gfxinfo com.edo.app           # frame stats; `reset` first
```

- **Test in the project named OLA** on the device, not the user's real projects, unless told otherwise.
- **Anything done on the phone goes through a Haiku subagent** (`model: haiku`): it is cheaper, and the main loop only needs its short report, not the screenshots and UI dumps. That covers tapping through screens, typing prompts, scrolling, reading chat output and frame stats. Give it the device id, the exact `adb` commands (nested quoting for `input text`, `%s` for spaces), the project it may touch (OLA), and tell it to change nothing beyond the task. Builds, installs and judging the results stay in the main session.

## Workspace files

All project workspaces live under `/sdcard/Edo/<name>`; the SAF picker pre-navigates there. The app holds only the per-folder `OpenDocumentTree` grant — no `MANAGE_EXTERNAL_STORAGE`. Grants don't always survive reinstalls; the user may need to re-pick the folder.

### Per-project scoping

Each project's agent must only reach its own folder. The OS boundary is the per-folder grant; all grants share one process, so the rest is enforced in code:

- Tool paths are relative to the workspace root; `.`/`..` segments are rejected (`WorkspaceScope.safeSegments`) and `FileWorkspace` compares canonical paths with a separator, so a sibling like `Bubbly-secret` isn't "inside" `Bubbly`.
- A project's folder may not equal, contain, or sit inside another project's folder (`WorkspaceScope.overlaps`, checked in the create/edit sheets). Picking the parent `Edo` folder would hand that project every sibling.
- Grants are released when a project is deleted or its folder changes, and grants no project points at are dropped at startup (`AppContainer.releaseGrant` / `releaseOrphanGrants`).
- `run_lua` has no Java bridge, `io` or `os`. `RunLuaSandboxTest` and `WorkspaceScopeTest` guard these rules.
- The folder picker is registered on `MainActivity` (`AppContainer.launchFolderPicker` / `pickedFolder`), not in the sheets: it stops the activity, which disposes the projects dialog, so a callback registered there is lost. New-project form state lives in `NewProjectForm` (a ViewModel) for the same reason.

## Notes

- `android:windowSoftInputMode="adjustResize"` is required — without it, the IME pushes the topbar off-screen in edge-to-edge mode.
- `ChatViewModel.combine(activeProjectId, activeThreadId).collectLatest` has a `markLoaded` guard so creating a thread from `sendUserMessage` doesn't trigger a reload that races the message insert.
- Orphaned tool_use messages (assistant messages with unanswered tool calls) are stripped on reload to avoid API rejections.
- `SwipePanels` drives both panels from one horizontal `draggable`, so a child that consumes horizontal drags (code blocks, lists) wins. The chats panel stays composed offscreen to avoid a first-frame hitch; the files panel is composed on demand.
- Pulling up past the end of a conversation suggests a new chat; the pull is resisted 2:1 and disabled while the agent runs.
- Message text is selectable through one `SelectionContainer` around the whole list (one per message made scrolling janky). The chat input draft is `rememberSaveable`, so it survives navigation.
- A file sent with a message is inlined as an `<attached_file>` block (`AttachedFiles.kt`); there is no file on disk, so opening it externally writes the text to the cache first.
- Anything handed to another app through `FileProvider` must live under a directory declared in `res/xml/file_paths.xml` (`captures/`, `open/`), or `getUriForFile` throws.
- OpenRouter extras are plain request-body fields (`provider.only/ignore/sort/...`, `models`, `reasoning`) merged over the normal body; the user's raw JSON wins. The field names were written from memory, not checked against OpenRouter's current docs.
- Room uses explicit migrations (3→4 … 7→8) but still `fallbackToDestructiveMigration`, so a missing migration silently wipes data — add one for every schema bump.
