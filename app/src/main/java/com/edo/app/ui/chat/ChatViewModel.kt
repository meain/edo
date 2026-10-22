package com.edo.app.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.edo.app.AppContainer
import com.edo.app.agent.ApprovalDecision
import com.edo.app.agent.BuiltinSkills
import com.edo.app.agent.FileWorkspace
import com.edo.app.agent.SafWorkspace
import com.edo.app.agent.SkillInfo
import com.edo.app.agent.Workspace
import com.edo.app.agent.discoverSkills
import com.edo.app.data.ProjectEntity
import com.edo.app.data.ThreadEntity
import com.edo.app.llm.Block
import com.edo.app.llm.Role
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
    /** Completes with the answer, or null if the user dismissed the question. */
    val deferred: CompletableDeferred<String?>,
)

data class QueuedMessage(
    val text: String,
    val images: List<Pair<String, String>> = emptyList(),
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
    val scrollVersion: Int = 0,
    val queuedMessages: List<QueuedMessage> = emptyList(),
    /** A summary of this chat is being written for a new one. */
    val compacting: Boolean = false,
)

/**
 * Screen-facing view of whichever chat is active. The conversation and any running agent
 * live in [ChatSession]s owned by the app container, so switching chat/project here only
 * changes which session is shown — it never cancels a run.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(app: Application, private val container: AppContainer) : AndroidViewModel(app) {

    private val sessions = container.chatSessions
    private var active: ChatSession? = null
    private val activeState = MutableStateFlow<Flow<ChatUiState>>(flowOf(ChatUiState()))

    val state: StateFlow<ChatUiState> = activeState
        .flatMapLatest { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ChatUiState())

    init {
        viewModelScope.launch {
            combine(container.activeProjectId, container.activeThreadId) { p, t -> p to t }
                .collectLatest { (projectId, threadId) ->
                    val projectExists = projectId > 0 && container.db.projects().getById(projectId) != null
                    if (!projectExists) {
                        active = null
                        activeState.value = flowOf(ChatUiState(needsProject = true))
                        return@collectLatest
                    }
                    val session = (if (threadId > 0) sessions.forThread(projectId, threadId) else null)
                        ?: sessions.draft(projectId)
                    active = session
                    activeState.value = session.state
                }
        }
    }

    /** Show the empty "new chat" state. Any run in the chat being left keeps going in the background. */
    fun newChat() {
        container.setActiveThread(-1L)
    }

    /** Cancel the in-flight agent run, if any. */
    fun cancelAgent() = active?.cancelAgent()

    /** Queue a message to send after the current agent run completes. */
    fun queueMessage(text: String, images: List<Pair<String, String>> = emptyList()) {
        active?.queueMessage(text, images)
    }

    /** Interrupt the run and send this message right away. */
    fun sendNow(text: String, images: List<Pair<String, String>> = emptyList()) {
        active?.sendNow(text, images)
    }

    /** Discard a queued message by index. */
    fun cancelQueue(index: Int) = active?.cancelQueue(index)

    /** Interrupt the run and send the queued message at [index] now. */
    fun sendQueuedNow(index: Int) = active?.sendQueuedNow(index)

    /** Summarise this chat into a new one and switch to it. Runs on the app scope, so leaving doesn't cancel it. */
    fun compactChat() {
        val session = active ?: return
        container.appScope.launch { session.compact() }
    }

    /** Re-run the agent against the current conversation (after all auto-retries exhausted). */
    fun retryAgent() = active?.retry()

    fun sendUserMessage(text: String, images: List<Pair<String, String>> = emptyList()) {
        active?.send(text, images)
    }

    fun approve(decision: ApprovalDecision) = active?.approve(decision)

    fun answerQuestion(answer: String) = active?.answerQuestion(answer)

    fun dismissQuestion() = active?.dismissQuestion()

    fun dismissError() = active?.dismissError()

    private val _skills = MutableStateFlow<List<SkillInfo>>(emptyList())
    /** Built-in plus project skills, for the `/` picker in the input box. */
    val skills: StateFlow<List<SkillInfo>> = _skills.asStateFlow()

    /** Re-scan the project's skills folder (called when the user starts typing `/`). */
    fun refreshSkills() {
        val project = state.value.currentProject ?: return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val builtin = BuiltinSkills.all.map { (name, e) -> SkillInfo(name, e.description, "built-in") }
            val fromProject = runCatching { discoverSkills(workspaceFor(project)) }.getOrDefault(emptyList())
            _skills.value = (builtin + fromProject).distinctBy { it.name }
        }
    }

    private fun workspaceFor(project: ProjectEntity): Workspace =
        if (project.workspaceUri.startsWith("content://")) {
            SafWorkspace(getApplication(), android.net.Uri.parse(project.workspaceUri))
        } else {
            FileWorkspace(java.io.File(project.workspaceUri))
        }
}
