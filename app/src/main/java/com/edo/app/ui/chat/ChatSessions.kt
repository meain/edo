package com.edo.app.ui.chat

import android.app.Application
import android.content.Intent
import com.edo.app.AgentForegroundService
import com.edo.app.AppContainer
import com.edo.app.llm.Block
import com.edo.app.llm.ConvMessage

/**
 * Process-wide registry of [ChatSession]s, so runs outlive whichever chat is on screen.
 * Everything here runs on the main thread (the app scope and viewModelScope are both Main).
 */
class ChatSessions(private val app: Application, private val container: AppContainer) {
    private val byThread = mutableMapOf<Long, ChatSession>()
    private val drafts = mutableMapOf<Long, ChatSession>()
    private var activeRuns = 0

    /** The session for an existing chat, loading it from the database if needed. Null if it's gone. */
    suspend fun forThread(projectId: Long, threadId: Long): ChatSession? {
        byThread[threadId]?.let { return it }
        val loaded = load(projectId, threadId) ?: return null
        // Another caller may have registered it while we were loading.
        val session = byThread.getOrPut(threadId) { loaded }
        evictIdleExcept(session)
        return session
    }

    /** The empty "new chat" session for a project; becomes a real chat on its first message. */
    suspend fun draft(projectId: Long): ChatSession {
        val existing = drafts[projectId]
        if (existing != null) return existing
        val project = container.db.projects().getById(projectId)
        val session = ChatSession(
            app, container, this, projectId, -1L,
            ChatUiState(currentProject = project),
            mutableListOf(), 0, false,
        )
        val registered = drafts.getOrPut(projectId) { session }
        evictIdleExcept(registered)
        return registered
    }

    /**
     * A fresh unattended session for a scheduled run. Kept apart from [draft] so it never
     * touches the user's own empty chat; it registers under its thread id on first send.
     */
    suspend fun unattended(projectId: Long): ChatSession? {
        val project = container.db.projects().getById(projectId) ?: return null
        return ChatSession(
            app, container, this, projectId, -1L,
            ChatUiState(currentProject = project),
            mutableListOf(), 0, false,
        ).also { it.unattended = true }
    }

    /** A draft just got a thread: register it under its real id. */
    internal fun promote(session: ChatSession) {
        if (drafts[session.projectId] === session) drafts.remove(session.projectId)
        byThread[session.threadId] = session
    }

    /** The chat was deleted: stop its run and forget it. */
    fun discard(threadId: Long) {
        byThread.remove(threadId)?.discard()
    }

    private fun evictIdleExcept(keep: ChatSession) {
        byThread.values.removeAll { it !== keep && !it.isBusy }
        drafts.values.removeAll { it !== keep && !it.isBusy }
    }

    private suspend fun load(projectId: Long, threadId: Long): ChatSession? {
        val project = container.db.projects().getById(projectId) ?: return null
        val thread = container.db.threads().getById(threadId)
        if (thread == null || thread.projectId != projectId) return null
        val rows = container.db.messages().listForThread(threadId)
        val conversation = mutableListOf<ConvMessage>()
        var loaded = rows.mapNotNull { rowToUi(it) }
        for (m in loaded) conversation.add(ConvMessage(m.role, m.blocks))
        // Strip any trailing assistant message with unanswered tool_use blocks — can
        // happen if the app was killed while the agent was suspended mid-tool-call.
        if (conversation.isNotEmpty() &&
            conversation.last().role == com.edo.app.llm.Role.Assistant &&
            conversation.last().blocks.any { it is Block.ToolUse }
        ) {
            conversation.removeAt(conversation.size - 1)
            loaded = loaded.dropLast(1)
        }
        // Tool_result-only messages stay in state so the UI can look up results for each
        // assistant tool_use block; the UI filters them from display.
        return ChatSession(
            app, container, this, projectId, threadId,
            ChatUiState(currentProject = project, currentThread = thread, messages = loaded),
            conversation, conversation.size,
            titleSet = thread.title != "New chat" && rows.isNotEmpty(),
        )
    }

    /**
     * Keep the foreground service up while any session has work in flight. Starting it from
     * the background isn't allowed, so it stays up across back-to-back runs (queued messages).
     */
    internal fun runStarted() {
        if (activeRuns++ == 0) {
            runCatching { app.startForegroundService(Intent(app, AgentForegroundService::class.java)) }
        }
    }

    internal fun runEnded() {
        if (--activeRuns <= 0) {
            activeRuns = 0
            app.stopService(Intent(app, AgentForegroundService::class.java))
        }
    }
}
