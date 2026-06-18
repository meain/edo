package com.edo.app.ui.chat

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import com.edo.app.AgentForegroundService
import androidx.lifecycle.viewModelScope
import com.edo.app.AppContainer
import com.edo.app.agent.Agent
import com.edo.app.agent.ApprovalDecision
import com.edo.app.agent.ApprovalGate
import com.edo.app.agent.AskUserQuestionTool
import com.edo.app.agent.CopyFileTool
import com.edo.app.agent.DateTimeTool
import com.edo.app.agent.DeleteFileTool
import com.edo.app.agent.EditFileTool
import com.edo.app.agent.GrepTool
import com.edo.app.agent.HttpRequestTool
import com.edo.app.agent.KtorHttpFetcher
import com.edo.app.agent.LoadSkillTool
import com.edo.app.agent.LsTool
import com.edo.app.agent.ReadFileTool
import com.edo.app.agent.FileWorkspace
import com.edo.app.agent.SafWorkspace
import com.edo.app.agent.ToolRegistry
import com.edo.app.agent.WriteFileTool
import com.edo.app.agent.AgentEvent
import com.edo.app.agent.buildSystemPrompt
import com.edo.app.data.MessageEntity
import com.edo.app.data.ProjectEntity
import com.edo.app.data.Provider
import com.edo.app.data.ThreadEntity
import com.edo.app.llm.AnthropicClient
import com.edo.app.llm.Block
import com.edo.app.llm.ConvMessage
import com.edo.app.llm.LlmClient
import com.edo.app.llm.OpenAIClient
import com.edo.app.llm.Role
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class PendingApproval(
    val id: String,
    val toolName: String,
    val argsJson: String,
    val deferred: CompletableDeferred<ApprovalDecision>,
)

data class PendingQuestion(
    val id: String,
    val question: String,
    val options: List<String>,
    val deferred: CompletableDeferred<String>,
)

data class UiMessage(
    val id: Long,
    val role: Role,
    val blocks: List<Block>,
)

data class PendingToolUi(
    val id: String,
    val name: String,
    val argsJson: String,
    val approved: Boolean? = null,
    val result: String? = null,
    val isError: Boolean = false,
)

data class ChatUiState(
    val currentProject: ProjectEntity? = null,
    val currentThread: ThreadEntity? = null,
    val messages: List<UiMessage> = emptyList(),
    val streamingText: String = "",
    val pendingToolCalls: Map<String, PendingToolUi> = emptyMap(),
    val approval: PendingApproval? = null,
    val pendingQuestion: PendingQuestion? = null,
    val running: Boolean = false,
    val error: String? = null,
    val needsProject: Boolean = false,
    val retryingAttempt: Int? = null,
    val retryingCause: String? = null,
    val canRetry: Boolean = false,
)

class ChatViewModel(app: Application, private val container: AppContainer) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val conversation = mutableListOf<ConvMessage>()
    private var persistedCount = 0
    private var agentJob: Job? = null
    private var titleSet = false

    private var loadedProjectId: Long = -2L
    private var loadedThreadId: Long = -2L

    init {
        viewModelScope.launch {
            combine(container.activeProjectId, container.activeThreadId) { p, t -> p to t }
                .collectLatest { (projectId, threadId) ->
                    // Skip if nothing actually changed — guards against re-emitting when
                    // sendUserMessage calls setActiveThread for a freshly created thread.
                    if (projectId == loadedProjectId && threadId == loadedThreadId) return@collectLatest
                    val projectChanged = projectId != loadedProjectId
                    loadedProjectId = projectId
                    loadedThreadId = threadId
                    if (projectChanged || threadId != _state.value.currentThread?.id) {
                        agentJob?.cancel()
                        agentJob = null
                    }
                    titleSet = false
                    val project = if (projectId > 0) container.db.projects().getById(projectId) else null
                    when {
                        project == null -> {
                            conversation.clear()
                            persistedCount = 0
                            _state.value = ChatUiState(needsProject = true)
                        }
                        threadId <= 0 -> {
                            conversation.clear()
                            persistedCount = 0
                            _state.value = ChatUiState(currentProject = project)
                        }
                        else -> loadThread(projectId, threadId)
                    }
                }
        }
    }

    /** Marks the given (project, thread) as already loaded, so the next combine emission for these IDs is a no-op. */
    private fun markLoaded(projectId: Long, threadId: Long) {
        loadedProjectId = projectId
        loadedThreadId = threadId
    }

    private suspend fun loadThread(projectId: Long, threadId: Long) {
        val project = container.db.projects().getById(projectId) ?: return
        val thread = container.db.threads().getById(threadId)
        if (thread == null || thread.projectId != projectId) {
            conversation.clear()
            persistedCount = 0
            _state.value = ChatUiState(currentProject = project)
            return
        }
        val rows = container.db.messages().listForThread(threadId)
        conversation.clear()
        var loaded = rows.mapNotNull { rowToUi(it) }
        for (m in loaded) conversation.add(ConvMessage(m.role, m.blocks))
        // Strip any trailing assistant message with unanswered tool_use blocks — can
        // happen if the app was killed while the agent was suspended mid-tool-call.
        if (conversation.isNotEmpty() &&
            conversation.last().role == Role.Assistant &&
            conversation.last().blocks.any { it is Block.ToolUse }
        ) {
            conversation.removeAt(conversation.size - 1)
            loaded = loaded.dropLast(1)
        }
        persistedCount = conversation.size
        titleSet = thread.title != "New chat" && rows.isNotEmpty()
        // Keep tool_result-only messages in state so the UI can look up results
        // for each assistant tool_use block. The UI filters them from display.
        _state.value = ChatUiState(currentProject = project, currentThread = thread, messages = loaded)
    }

    /** Clear active thread to show empty "new chat" state. A new ThreadEntity is created on first send.
     *  Any in-flight agent run is cancelled — starting a new chat implies abandoning the old one. */
    fun newChat() {
        agentJob?.cancel()
        agentJob = null
        _state.update { it.copy(running = false, approval = null, pendingQuestion = null, streamingText = "", pendingToolCalls = emptyMap()) }
        container.setActiveThread(-1L)
    }

    /** Cancel the in-flight agent run, if any. */
    fun cancelAgent() {
        agentJob?.cancel()
        agentJob = null
        _state.update { it.copy(running = false, approval = null, pendingQuestion = null) }
    }

    /** Re-run the agent against the current conversation (after all auto-retries exhausted). */
    fun retryAgent() {
        if (_state.value.running) return
        val threadId = _state.value.currentThread?.id ?: return
        _state.update { it.copy(error = null, canRetry = false) }
        agentJob = container.appScope.launch { runAgent(threadId) }
    }

    fun sendUserMessage(text: String, image: Pair<String, String>? = null) {
        if (text.isBlank() && image == null) return
        val projectId = container.activeProjectId.value.takeIf { it > 0 } ?: return
        agentJob?.cancel()
        _state.update { it.copy(approval = null, pendingQuestion = null) }
        val blocks = mutableListOf<Block>()
        if (image != null) blocks.add(Block.Image(image.first, image.second))
        if (text.isNotBlank()) blocks.add(Block.Text(text))
        val convMsg = ConvMessage(Role.User, blocks)
        conversation.add(convMsg)
        // Run on the Application-level scope so the in-flight LLM stream survives
        // ChatViewModel teardown (e.g. navigation away from the chat screen).
        agentJob = container.appScope.launch {
            // Create thread if none active
            var threadId = container.activeThreadId.value
            if (threadId <= 0) {
                val newTitle = text.ifBlank { "Image message" }.take(48)
                val now = System.currentTimeMillis()
                threadId = container.db.threads().insert(
                    ThreadEntity(projectId = projectId, title = newTitle, createdAt = now, lastUpdatedAt = now)
                )
                titleSet = true
                // Mark before setActiveThread so the combine emission is a no-op
                markLoaded(projectId, threadId)
                container.setActiveThread(threadId)
                val thread = container.db.threads().getById(threadId)
                _state.update { it.copy(currentThread = thread) }
            } else if (!titleSet && text.isNotBlank()) {
                val newTitle = text.take(48)
                container.db.threads().updateTitleAndTimestamp(threadId, newTitle, System.currentTimeMillis())
                titleSet = true
                val thread = container.db.threads().getById(threadId)
                _state.update { it.copy(currentThread = thread) }
            } else {
                container.db.threads().touch(threadId)
            }
            val rowId = container.db.messages().insert(uiToRow(convMsg, threadId))
            persistedCount = conversation.size
            _state.update {
                it.copy(messages = it.messages + UiMessage(rowId, Role.User, blocks))
            }
            runAgent(threadId)
        }
    }

    fun approve(decision: ApprovalDecision) {
        _state.value.approval?.deferred?.complete(decision)
    }

    fun answerQuestion(answer: String) {
        _state.value.pendingQuestion?.deferred?.complete(answer)
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    private suspend fun runAgent(threadId: Long) {
        val projectId = container.activeProjectId.value.takeIf { it > 0 } ?: run {
            _state.update { it.copy(error = "No project selected.") }
            return
        }
        val project = container.db.projects().getById(projectId) ?: run {
            _state.update { it.copy(error = "Project not found.") }
            return
        }
        val settings = container.settings.load()
        if (settings.apiKey.isBlank()) {
            _state.update { it.copy(error = "Add your API key in Settings.") }
            return
        }
        val ws = if (project.workspaceUri.startsWith("content://")) {
            SafWorkspace(getApplication(), android.net.Uri.parse(project.workspaceUri))
        } else {
            FileWorkspace(java.io.File(project.workspaceUri))
        }
        val askUser: suspend (String, List<String>) -> String = { question, options ->
            val deferred = CompletableDeferred<String>()
            val id = "question_${System.currentTimeMillis()}"
            _state.update { it.copy(pendingQuestion = PendingQuestion(id, question, options, deferred)) }
            try { deferred.await() } finally {
                _state.update { it.copy(pendingQuestion = null) }
            }
        }
        val tools = ToolRegistry(
            listOf(
                ReadFileTool(ws),
                WriteFileTool(ws),
                EditFileTool(ws),
                DeleteFileTool(ws),
                CopyFileTool(ws),
                LsTool(ws),
                GrepTool(ws),
                HttpRequestTool(KtorHttpFetcher(container.http)),
                DateTimeTool(),
                LoadSkillTool(ws),
                AskUserQuestionTool(askUser),
            )
        )
        val llm: LlmClient = when (settings.provider) {
            Provider.Anthropic -> AnthropicClient(settings.baseUrl, settings.apiKey, settings.model, container.http)
            Provider.OpenAI -> OpenAIClient(settings.baseUrl, settings.apiKey, settings.model, container.http)
        }
        val yolo = project.yoloMode
        val gate = ApprovalGate { name, argsJson ->
            if (yolo) {
                ApprovalDecision.AllowOnce
            } else {
                val deferred = CompletableDeferred<ApprovalDecision>()
                val pendingId = "${name}_${System.currentTimeMillis()}"
                _state.update { it.copy(approval = PendingApproval(pendingId, name, argsJson, deferred)) }
                try { deferred.await() } finally {
                    _state.update { it.copy(approval = null) }
                }
            }
        }
        val systemPrompt = buildSystemPrompt(ws)
        _state.update { it.copy(running = true, error = null) }
        val svcIntent = Intent(getApplication(), AgentForegroundService::class.java)
        getApplication<Application>().startForegroundService(svcIntent)
        try {
            Agent(llm, tools, gate, systemPrompt = systemPrompt).run(conversation).collect { ev -> handle(ev, threadId) }
        } catch (t: Throwable) {
            if (t !is kotlinx.coroutines.CancellationException) {
                _state.update { it.copy(error = t.message ?: "Unknown error") }
            }
        } finally {
            getApplication<Application>().stopService(svcIntent)
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

    private suspend fun handle(ev: AgentEvent, threadId: Long) {
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

            is AgentEvent.TurnDone -> persistNewMessages(threadId)

            is AgentEvent.Failure -> _state.update { it.copy(error = ev.message) }

            is AgentEvent.StreamingReset ->
                _state.update { it.copy(streamingText = "", pendingToolCalls = emptyMap()) }

            is AgentEvent.Retrying ->
                _state.update { it.copy(streamingText = "", pendingToolCalls = emptyMap(), retryingAttempt = ev.attempt, retryingCause = ev.cause) }

            AgentEvent.Finished -> Unit
        }
    }

    private suspend fun persistNewMessages(threadId: Long) {
        val newItems = conversation.drop(persistedCount)
        var uiMsgs = _state.value.messages
        for (msg in newItems) {
            val rowId = container.db.messages().insert(uiToRow(msg, threadId))
            uiMsgs = uiMsgs + UiMessage(rowId, msg.role, msg.blocks)
        }
        persistedCount = conversation.size
        _state.update { it.copy(messages = uiMsgs, streamingText = "", pendingToolCalls = emptyMap()) }
    }

    private fun uiToRow(msg: ConvMessage, threadId: Long): MessageEntity = MessageEntity(
        threadId = threadId,
        role = msg.role.name,
        contentJson = blocksToJson(msg.blocks).toString(),
    )

    private fun rowToUi(row: MessageEntity): UiMessage? {
        val role = runCatching { Role.valueOf(row.role) }.getOrNull() ?: return null
        val arr = runCatching { Json.parseToJsonElement(row.contentJson).jsonArray }.getOrNull() ?: return null
        return UiMessage(row.id, role, jsonToBlocks(arr))
    }

    private fun blocksToJson(blocks: List<Block>): JsonArray = buildJsonArray {
        for (b in blocks) {
            when (b) {
                is Block.Text -> addJsonObject { put("kind", "text"); put("text", b.text) }
                is Block.Image -> addJsonObject { put("kind", "image"); put("media_type", b.mediaType); put("data", b.base64) }
                is Block.ToolUse -> addJsonObject { put("kind", "tool_use"); put("id", b.id); put("name", b.name); put("args", b.argsJson) }
                is Block.ToolResult -> addJsonObject { put("kind", "tool_result"); put("id", b.toolUseId); put("content", b.content); put("is_error", b.isError) }
            }
        }
    }

    private fun jsonToBlocks(arr: JsonArray): List<Block> = arr.mapNotNull { el ->
        val obj = el as? JsonObject ?: return@mapNotNull null
        when (obj["kind"]?.jsonPrimitive?.contentOrNull) {
            "text" -> Block.Text(obj["text"]?.jsonPrimitive?.contentOrNull ?: "")
            "image" -> Block.Image(obj["media_type"]?.jsonPrimitive?.contentOrNull ?: "image/png", obj["data"]?.jsonPrimitive?.contentOrNull ?: "")
            "tool_use" -> Block.ToolUse(obj["id"]?.jsonPrimitive?.contentOrNull ?: "", obj["name"]?.jsonPrimitive?.contentOrNull ?: "", obj["args"]?.jsonPrimitive?.contentOrNull ?: "{}")
            "tool_result" -> Block.ToolResult(obj["id"]?.jsonPrimitive?.contentOrNull ?: "", obj["content"]?.jsonPrimitive?.contentOrNull ?: "", obj["is_error"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false)
            else -> null
        }
    }
}
