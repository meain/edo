package com.edo.app.agent

import com.edo.app.llm.Block
import com.edo.app.llm.ConvMessage
import com.edo.app.llm.LlmClient
import com.edo.app.llm.LlmEvent
import com.edo.app.llm.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow

/**
 * Decision returned by the approval gate for a pending tool call.
 */
enum class ApprovalDecision {
    AllowOnce,
    AllowAlwaysThisSession,
    Deny,
}

/** Callback used to ask the UI for approval. */
fun interface ApprovalGate {
    suspend fun decide(toolName: String, argsJson: String): ApprovalDecision
}

/** Events the UI observes while the agent runs. */
sealed interface AgentEvent {
    data class AssistantTextDelta(val text: String) : AgentEvent
    data class AssistantTextDone(val text: String) : AgentEvent
    data class ToolCallStart(val id: String, val name: String) : AgentEvent
    data class ToolCallArgsDelta(val id: String, val delta: String) : AgentEvent
    data class ToolCallReady(val id: String, val name: String, val argsJson: String) : AgentEvent
    data class ToolCallApproval(val id: String, val decision: ApprovalDecision) : AgentEvent
    data class ToolCallResult(val id: String, val result: ToolResult) : AgentEvent
    data class TurnDone(val stopReason: String?) : AgentEvent
    data class Failure(val message: String) : AgentEvent
    data object Finished : AgentEvent
}

class Agent(
    private val llm: LlmClient,
    private val tools: ToolRegistry,
    private val gate: ApprovalGate,
    private val systemPrompt: String? = DefaultSystemPrompt,
    private val maxTurns: Int = 20,
) {

    private val sessionAllowed = mutableSetOf<String>()

    /** Run the agent against the given conversation and stream events. */
    fun run(conversation: MutableList<ConvMessage>): Flow<AgentEvent> = flow {
        var turn = 0
        while (turn < maxTurns) {
            turn++
            val assistantBlocks = mutableListOf<Block>()
            val pendingToolUses = linkedMapOf<String, ToolUseAccumulator>()
            var assistantText = StringBuilder()
            var stopReason: String? = null
            var failed = false

            llm.stream(systemPrompt, conversation.toList(), tools.specs).collect { ev ->
                when (ev) {
                    is LlmEvent.TextDelta -> {
                        assistantText.append(ev.text)
                        emit(AgentEvent.AssistantTextDelta(ev.text))
                    }
                    is LlmEvent.ToolUseStart -> {
                        pendingToolUses[ev.id] = ToolUseAccumulator(ev.name)
                        emit(AgentEvent.ToolCallStart(ev.id, ev.name))
                    }
                    is LlmEvent.ToolUseArgsDelta -> {
                        pendingToolUses[ev.id]?.args?.append(ev.partialJson)
                        emit(AgentEvent.ToolCallArgsDelta(ev.id, ev.partialJson))
                    }
                    is LlmEvent.ToolUseEnd -> {
                        val acc = pendingToolUses[ev.id] ?: return@collect
                        val argsJson = acc.args.toString().ifEmpty { "{}" }
                        acc.finalArgs = argsJson
                        emit(AgentEvent.ToolCallReady(ev.id, acc.name, argsJson))
                    }
                    is LlmEvent.TurnEnd -> {
                        stopReason = ev.stopReason
                    }
                    is LlmEvent.Failure -> {
                        failed = true
                        emit(AgentEvent.Failure(ev.message))
                    }
                }
            }

            if (failed) {
                emit(AgentEvent.Finished)
                return@flow
            }

            val text = assistantText.toString()
            if (text.isNotEmpty()) {
                assistantBlocks.add(Block.Text(text))
                emit(AgentEvent.AssistantTextDone(text))
            }
            for ((id, acc) in pendingToolUses) {
                val args = acc.finalArgs ?: acc.args.toString().ifEmpty { "{}" }
                assistantBlocks.add(Block.ToolUse(id, acc.name, args))
            }

            if (assistantBlocks.isNotEmpty()) {
                conversation.add(ConvMessage(Role.Assistant, assistantBlocks))
            }
            emit(AgentEvent.TurnDone(stopReason))

            if (pendingToolUses.isEmpty()) {
                emit(AgentEvent.Finished)
                return@flow
            }

            // Approve & run each tool, collecting tool_result blocks for the next turn.
            val resultBlocks = mutableListOf<Block>()
            for ((id, acc) in pendingToolUses) {
                val argsJson = acc.finalArgs ?: "{}"
                val decision = if (sessionAllowed.contains(acc.name)) {
                    ApprovalDecision.AllowOnce
                } else {
                    gate.decide(acc.name, argsJson)
                }
                emit(AgentEvent.ToolCallApproval(id, decision))

                if (decision == ApprovalDecision.AllowAlwaysThisSession) {
                    sessionAllowed.add(acc.name)
                }

                val result = when (decision) {
                    ApprovalDecision.Deny ->
                        ToolResult("denied by user", isError = true)
                    else -> {
                        val tool = tools.get(acc.name)
                        if (tool == null) {
                            ToolResult("unknown tool: ${acc.name}", isError = true)
                        } else {
                            runCatching { tool.invoke(argsJson) }.getOrElse {
                                ToolResult("tool threw: ${it.message}", isError = true)
                            }
                        }
                    }
                }
                emit(AgentEvent.ToolCallResult(id, result))
                resultBlocks.add(Block.ToolResult(id, result.content, result.isError))
            }
            conversation.add(ConvMessage(Role.User, resultBlocks))
            // loop continues — model sees tool results
        }
        emit(AgentEvent.Failure("max turns ($maxTurns) reached"))
        emit(AgentEvent.Finished)
    }

    private class ToolUseAccumulator(val name: String) {
        val args = StringBuilder()
        var finalArgs: String? = null
    }

    companion object {
        const val DefaultSystemPrompt = """You are Edo, a coding assistant running on Android. You have access to tools that let you read, write, edit, list, and grep through a workspace folder the user has selected, plus an HTTP request tool. Use the tools to answer the user's question. Be concise."""
    }
}

/**
 * Assemble the system prompt by combining the default prompt with optional
 * project-specific context: an `AGENTS.md` (or `CLAUDE.md` symlink target)
 * at the workspace root, and a summary of any skills under `.edo/skills/`.
 */
fun buildSystemPrompt(ws: Workspace): String {
    val out = StringBuilder(Agent.DefaultSystemPrompt)
    val agentsMd = ws.read("AGENTS.md") ?: ws.read("CLAUDE.md")
    if (!agentsMd.isNullOrBlank()) {
        out.append("\n\n# Project instructions (AGENTS.md)\n\n")
        out.append(agentsMd.trim())
    }
    return out.toString()
}
