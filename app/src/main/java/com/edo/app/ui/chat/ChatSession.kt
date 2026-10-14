package com.edo.app.ui.chat

import android.app.Application
import com.edo.app.AppContainer
import com.edo.app.agent.Agent
import com.edo.app.agent.AgentEvent
import com.edo.app.agent.ApprovalDecision
import com.edo.app.agent.ApprovalGate
import com.edo.app.agent.AskUserQuestionTool
import com.edo.app.agent.CalculatorTool
import com.edo.app.agent.CopyFileTool
import com.edo.app.agent.DateTimeTool
import com.edo.app.agent.DeleteFileTool
import com.edo.app.agent.EditFileTool
import com.edo.app.agent.FileWorkspace
import com.edo.app.agent.GrepTool
import com.edo.app.agent.HttpRequestTool
import com.edo.app.agent.KtorHttpFetcher
import com.edo.app.agent.LoadSkillTool
import com.edo.app.agent.ReadTool
import com.edo.app.agent.RunLuaTool
import com.edo.app.agent.ScheduleTool
import com.edo.app.agent.SafWorkspace
import com.edo.app.agent.ToolRegistry
import com.edo.app.agent.WebSearchTool
import com.edo.app.agent.Workspace
import com.edo.app.agent.WriteFileTool
import com.edo.app.agent.buildSystemPrompt
import com.edo.app.data.AppSettings
import com.edo.app.data.ProjectEntity
import com.edo.app.data.Provider
import com.edo.app.data.ThreadEntity
import com.edo.app.data.forSideTasks
import com.edo.app.data.withOverrides
import com.edo.app.llm.AnthropicClient
import com.edo.app.llm.Block
import com.edo.app.llm.ConvMessage
import com.edo.app.llm.LlmClient
import com.edo.app.llm.LlmEvent
import com.edo.app.llm.OpenAIClient
import com.edo.app.llm.Role
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One chat's conversation and agent run. Lives on the process-wide scope (not the
 * ViewModel), so a run keeps going when the user switches chats or projects, or leaves
 * the app. [threadId] is -1 for a draft, which gets a real thread on its first message.
 */
class ChatSession internal constructor(
    private val app: Application,
    private val container: AppContainer,
    private val sessions: ChatSessions,
    val projectId: Long,
    threadId: Long,
    initial: ChatUiState,
    private val conversation: MutableList<ConvMessage>,
    private var persistedCount: Int,
    private var titleSet: Boolean,
) {
    var threadId: Long = threadId
        private set

    private val _state = MutableStateFlow(initial)
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var job: Job? = null

    /**
     * Set for scheduled runs, where nobody is watching: tool calls that need approval are denied,
     * `ask_user` gets a canned answer, and the run never steals the on-screen chat.
     */
    internal var unattended = false

    /** Suspends until the current run (and any queued follow-ups) has finished. */
    internal suspend fun awaitIdle() {
        job?.join()
    }

    /** True while a run is active or messages are waiting to be sent. */
    val isBusy: Boolean get() = job?.isActive == true || _state.value.queuedMessages.isNotEmpty()

    /** Run [block] on the process scope, holding the foreground service for its whole duration. */
    private fun launchTracked(block: suspend () -> Unit): Job = container.appScope.launch {
        sessions.runStarted()
        try {
            block()
        } finally {
            sessions.runEnded()
        }
    }

    /** Stop any run and release this session (the chat was deleted). */
    fun discard() {
        job?.cancel()
        job = null
    }

    fun cancelAgent() {
        job?.cancel()
        job = null
        _state.update { it.copy(running = false, approval = null, pendingQuestion = null, queuedMessages = emptyList()) }
    }

    fun queueMessage(text: String, images: List<Pair<String, String>> = emptyList()) {
        _state.update { it.copy(queuedMessages = it.queuedMessages + QueuedMessage(text, images)) }
    }

    fun cancelQueue(index: Int) {
        _state.update { it.copy(queuedMessages = it.queuedMessages.toMutableList().apply { removeAt(index) }) }
    }

    fun approve(decision: ApprovalDecision) {
        _state.value.approval?.deferred?.complete(decision)
    }

    fun answerQuestion(answer: String) {
        _state.value.pendingQuestion?.deferred?.complete(answer)
    }

    /** The user closed the question without answering; the tool call fails and the run continues. */
    fun dismissQuestion() {
        _state.value.pendingQuestion?.deferred?.complete(null)
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    /** Re-run the agent against the current conversation (after all auto-retries exhausted). */
    fun retry() {
        if (_state.value.running || threadId <= 0) return
        _state.update { it.copy(error = null, canRetry = false) }
        job = launchTracked { runAgent() }
    }

    fun send(text: String, images: List<Pair<String, String>> = emptyList()) {
        if (text.isBlank() && images.isEmpty()) return
        job?.cancel()
        _state.update { it.copy(approval = null, pendingQuestion = null) }
        val blocks = mutableListOf<Block>()
        for (img in images) blocks.add(Block.Image(img.first, img.second))
        if (text.isNotBlank()) blocks.add(Block.Text(text))
        val convMsg = ConvMessage(Role.User, blocks)
        conversation.add(convMsg)
        job = launchTracked {
            var needsSmartTitle = false
            if (threadId <= 0) {
                needsSmartTitle = true
                val now = System.currentTimeMillis()
                val id = container.db.threads().insert(
                    ThreadEntity(
                        projectId = projectId,
                        title = text.ifBlank { "Image message" }.take(48),
                        createdAt = now,
                        lastUpdatedAt = now,
                    )
                )
                threadId = id
                titleSet = true
                sessions.promote(this@ChatSession)
                // Show the new chat, unless the user has already navigated elsewhere.
                if (!unattended && container.activeProjectId.value == projectId && container.activeThreadId.value <= 0) {
                    container.setActiveThread(id)
                }
                _state.update { it.copy(currentThread = container.db.threads().getById(id)) }
            } else if (!titleSet && text.isNotBlank()) {
                container.db.threads().updateTitleAndTimestamp(threadId, text.take(48), System.currentTimeMillis())
                titleSet = true
                _state.update { it.copy(currentThread = container.db.threads().getById(threadId)) }
            } else {
                container.db.threads().touch(threadId)
            }
            val rowId = container.db.messages().insert(uiToRow(convMsg, threadId))
            persistedCount = conversation.size
            _state.update { it.copy(messages = it.messages + UiMessage(rowId, Role.User, blocks)) }
            runAgent()
            if (needsSmartTitle && !unattended && _state.value.error == null) {
                container.appScope.launch { generateTitle() }
            }
            drainQueue()
        }
    }

    /**
     * Interrupt the current run right now and send the queued message at [index] in its place,
     * instead of waiting for the run to finish.
     */
    fun sendQueuedNow(index: Int) {
        val queued = _state.value.queuedMessages.getOrNull(index) ?: return
        _state.update { it.copy(queuedMessages = it.queuedMessages.toMutableList().apply { removeAt(index) }) }
        val previous = job
        job = launchTracked {
            // Wait for the old run to stop touching `conversation` before editing it.
            previous?.cancelAndJoin()
            if (threadId <= 0) return@launchTracked
            // The cancelled run may have added messages it hadn't persisted yet.
            persistNewMessages()
            closeOrphanedToolCalls()
            if (!deliverUserMessage(queued)) return@launchTracked
            runAgent()
            drainQueue()
        }
    }

    /** Send queued messages one after another once the current agent run completes. */
    private suspend fun drainQueue() {
        while (true) {
            // runAgent swallows cancellation; don't pop a queued message if we were cancelled.
            kotlin.coroutines.coroutineContext.ensureActive()
            val queued = _state.value.queuedMessages.firstOrNull() ?: break
            _state.update { it.copy(queuedMessages = it.queuedMessages.drop(1)) }
            if (threadId <= 0 || !deliverUserMessage(queued)) break
            runAgent()
        }
    }

    /** Append [queued] to the conversation and persist it. Returns false if it was empty. */
    private suspend fun deliverUserMessage(queued: QueuedMessage): Boolean {
        val blocks = buildList {
            for (img in queued.images) add(Block.Image(img.first, img.second))
            if (queued.text.isNotBlank()) add(Block.Text(queued.text))
        }
        if (blocks.isEmpty()) return false
        val msg = ConvMessage(Role.User, blocks)
        conversation.add(msg)
        val rowId = container.db.messages().insert(uiToRow(msg, threadId))
        persistedCount = conversation.size
        _state.update { it.copy(messages = it.messages + UiMessage(rowId, Role.User, blocks), scrollVersion = it.scrollVersion + 1) }
        return true
    }

    /**
     * If the run was interrupted mid-tool, the last assistant message has tool calls with no
     * results, which the API rejects. Give each an "interrupted" result so history stays valid.
     */
    private suspend fun closeOrphanedToolCalls() {
        val last = conversation.lastOrNull()?.takeIf { it.role == Role.Assistant } ?: return
        val orphaned = last.blocks.filterIsInstance<Block.ToolUse>()
        if (orphaned.isEmpty()) return
        conversation.add(
            ConvMessage(
                Role.User,
                orphaned.map {
                    Block.ToolResult(it.id, "Interrupted: the user sent a new message before this finished; it may not have completed.", true)
                },
            )
        )
        persistNewMessages()
    }

    private fun workspaceFor(project: ProjectEntity): Workspace =
        if (project.workspaceUri.startsWith("content://")) {
            SafWorkspace(app, android.net.Uri.parse(project.workspaceUri))
        } else {
            FileWorkspace(java.io.File(project.workspaceUri))
        }

    private fun buildLlm(settings: AppSettings): LlmClient = when (settings.provider) {
        Provider.Anthropic -> AnthropicClient(settings.baseUrl, settings.apiKey, settings.model, container.http)
        Provider.OpenAI -> OpenAIClient(settings.baseUrl, settings.apiKey, settings.model, container.http)
    }

    /**
     * Replace the placeholder title (first message, truncated) with a short model-written
     * one. Best-effort: any failure just leaves the placeholder in place.
     */
    private suspend fun generateTitle() {
        val settings = container.settings.load().forSideTasks()
        if (settings.apiKey.isBlank()) return
        val id = threadId
        fun firstText(role: Role) = conversation.firstOrNull { it.role == role }
            ?.blocks?.filterIsInstance<Block.Text>()?.joinToString("\n") { it.text }.orEmpty()
        // Don't feed whole attached files to the titler; mention their names instead.
        val (files, userText) = splitAttachedFiles(firstText(Role.User))
        val user = buildString {
            append(userText.take(600))
            if (files.isNotEmpty()) append("\n[attached: ${files.joinToString { it.name }}]")
        }.trim()
        if (user.isBlank()) return
        val prompt = "User: $user\n\nAssistant: ${firstText(Role.Assistant).take(600)}"
        val title = try {
            val sb = StringBuilder()
            buildLlm(settings).stream(
                "You name chat conversations. Reply with only a short title of 2 to 5 words, " +
                    "no quotes, no trailing punctuation.",
                listOf(ConvMessage(Role.User, listOf(Block.Text(prompt)))),
                emptyList(),
            ).collect { ev ->
                when (ev) {
                    is LlmEvent.TextDelta -> sb.append(ev.text)
                    is LlmEvent.Failure -> return@collect
                    else -> Unit
                }
            }
            sb.toString().lineSequence().firstOrNull().orEmpty()
                .trim().trim('"', '\'', '`', '*', '.').take(60)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return
        }
        if (title.isBlank()) return
        container.db.threads().updateTitleAndTimestamp(id, title, System.currentTimeMillis())
        _state.update { it.copy(currentThread = container.db.threads().getById(id)) }
    }

    private suspend fun runAgent() {
        val threadId = threadId
        val project = container.db.projects().getById(projectId) ?: run {
            _state.update { it.copy(error = "Project not found.") }
            return
        }
        val settings = container.settings.load().withOverrides(project)
        if (settings.apiKey.isBlank()) {
            _state.update { it.copy(error = "Add your API key in Settings.") }
            return
        }
        val ws = workspaceFor(project)
        val askUser: suspend (String, List<String>) -> String? = ask@{ question, options ->
            if (unattended) return@ask "No user is available (scheduled run). Make your best judgment and continue."
            val deferred = CompletableDeferred<String?>()
            val id = "question_${System.currentTimeMillis()}"
            _state.update { it.copy(pendingQuestion = PendingQuestion(id, question, options, deferred)) }
            try { deferred.await() } finally {
                _state.update { it.copy(pendingQuestion = null) }
            }
        }
        val tools = ToolRegistry(
            listOfNotNull(
                // Scheduled runs don't get it, so a schedule can't create more schedules.
                if (unattended) null else ScheduleTool(container.scheduler, projectId),
                ReadTool(ws),
                WriteFileTool(ws),
                EditFileTool(ws),
                DeleteFileTool(ws),
                CopyFileTool(ws),
                GrepTool(ws),
                HttpRequestTool(KtorHttpFetcher(container.http)),
                WebSearchTool(KtorHttpFetcher(container.http)),
                DateTimeTool(),
                CalculatorTool(),
                LoadSkillTool(ws),
                AskUserQuestionTool(askUser),
                // Always on, with file access scoped to this project's workspace; network is a per-project opt-in.
                RunLuaTool(ws, KtorHttpFetcher(container.http), project.luaNetwork),
            )
        )
        val llm = buildLlm(settings)
        val yolo = project.yoloMode
        val gate = ApprovalGate { name, argsJson ->
            if (yolo) {
                ApprovalDecision.AllowOnce
            } else if (unattended) {
                ApprovalDecision.Deny
            } else {
                val deferred = CompletableDeferred<ApprovalDecision>()
                val pendingId = "${name}_${System.currentTimeMillis()}"
                _state.update { it.copy(approval = PendingApproval(pendingId, name, argsJson, deferred)) }
                try { deferred.await() } finally {
                    _state.update { it.copy(approval = null) }
                }
            }
        }
        val systemPrompt = buildSystemPrompt(ws) + if (unattended) UNATTENDED_NOTE else ""
        _state.update { it.copy(running = true, error = null) }
        var cancelled = false
        try {
            Agent(llm, tools, gate, systemPrompt = systemPrompt, maxTurns = settings.maxTurns).run(conversation).collect { ev -> handle(ev) }
        } catch (t: Throwable) {
            if (t !is CancellationException) {
                _state.update { it.copy(error = t.message ?: "Unknown error") }
            } else cancelled = true
        } finally {
            if (!cancelled) notifyIfBackgrounded()
            _state.update { s ->
                s.copy(
                    running = false,
                    retryingAttempt = null,
                    retryingCause = null,
                    canRetry = s.error != null && s.currentThread != null,
                )
            }
            container.db.threads().touch(threadId)
        }
    }

    /** Scheduled runs have their own notification; queued messages mean the run isn't really over. */
    private fun notifyIfBackgrounded() {
        if (unattended || container.inForeground || _state.value.queuedMessages.isNotEmpty()) return
        val error = _state.value.error
        val reply = conversation.lastOrNull { it.role == Role.Assistant }
            ?.blocks?.filterIsInstance<Block.Text>()?.joinToString("\n") { it.text }
        RunNotifier.post(
            app, projectId, threadId,
            title = _state.value.currentThread?.title ?: "Chat",
            body = error ?: reply ?: "Done",
            failed = error != null,
        )
    }

    private suspend fun handle(ev: AgentEvent) {
        when (ev) {
            is AgentEvent.AssistantTextDelta ->
                _state.update { it.copy(streamingText = it.streamingText + ev.text) }

            is AgentEvent.AssistantTextDone -> Unit

            is AgentEvent.ToolCallStart ->
                _state.update {
                    it.copy(pendingToolCalls = it.pendingToolCalls + (ev.id to PendingToolUi(ev.id, ev.name, "")))
                }

            is AgentEvent.ToolCallArgsDelta ->
                _state.update {
                    val cur = it.pendingToolCalls[ev.id] ?: return@update it
                    it.copy(pendingToolCalls = it.pendingToolCalls + (ev.id to cur.copy(argsJson = cur.argsJson + ev.delta)))
                }

            is AgentEvent.ToolCallReady ->
                _state.update {
                    val cur = it.pendingToolCalls[ev.id] ?: PendingToolUi(ev.id, ev.name, ev.argsJson)
                    it.copy(pendingToolCalls = it.pendingToolCalls + (ev.id to cur.copy(argsJson = ev.argsJson)))
                }

            is AgentEvent.ToolCallApproval ->
                _state.update {
                    val cur = it.pendingToolCalls[ev.id] ?: return@update it
                    it.copy(pendingToolCalls = it.pendingToolCalls + (ev.id to cur.copy(approved = ev.decision != ApprovalDecision.Deny)))
                }

            is AgentEvent.ToolCallResult ->
                _state.update {
                    val cur = it.pendingToolCalls[ev.id] ?: return@update it
                    it.copy(pendingToolCalls = it.pendingToolCalls + (ev.id to cur.copy(result = ev.result.content, isError = ev.result.isError)))
                }

            is AgentEvent.TurnDone -> persistNewMessages()

            is AgentEvent.Failure -> _state.update { it.copy(error = ev.message) }

            is AgentEvent.StreamingReset ->
                _state.update { it.copy(streamingText = "", pendingToolCalls = emptyMap()) }

            is AgentEvent.Retrying ->
                _state.update { it.copy(streamingText = "", pendingToolCalls = emptyMap(), retryingAttempt = ev.attempt, retryingCause = ev.cause) }

            AgentEvent.Finished -> Unit
        }
    }

    private suspend fun persistNewMessages() {
        val newItems = conversation.drop(persistedCount)
        var uiMsgs = _state.value.messages
        for (msg in newItems) {
            val rowId = container.db.messages().insert(uiToRow(msg, threadId))
            uiMsgs = uiMsgs + UiMessage(rowId, msg.role, msg.blocks)
        }
        persistedCount = conversation.size
        _state.update { it.copy(messages = uiMsgs, streamingText = "", pendingToolCalls = emptyMap(), scrollVersion = it.scrollVersion + 1) }
    }
}

private const val UNATTENDED_NOTE = "\n\n# Scheduled run\n\n" +
    "This is an unattended, scheduled run. Nobody can answer questions or approve actions, so " +
    "don't ask; tools that need approval will be denied unless the project allows them. Keep the " +
    "final reply short, since it is shown in a notification."
