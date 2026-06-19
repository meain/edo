package com.edo.app.agent

import com.edo.app.llm.Block
import com.edo.app.llm.ConvMessage
import com.edo.app.llm.LlmClient
import com.edo.app.llm.LlmEvent
import com.edo.app.llm.Role
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
    data class Retrying(val attempt: Int, val maxAttempts: Int, val cause: String? = null, val delayMs: Long = 2000L) : AgentEvent
    data object StreamingReset : AgentEvent
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
            var assistantBlocks = mutableListOf<Block>()
            var pendingToolUses = linkedMapOf<String, ToolUseAccumulator>()
            var assistantText = StringBuilder()
            var stopReason: String? = null
            var failed = false
            var lastError: String? = null

            for (attempt in 0..MAX_LLM_RETRIES) {
                if (attempt > 0) {
                    val delayMs = minOf(1000L shl attempt, 15_000L) // 2s, 4s, 8s, 15s cap
                    emit(AgentEvent.StreamingReset)
                    emit(AgentEvent.Retrying(attempt, MAX_LLM_RETRIES, lastError, delayMs))
                    delay(delayMs)
                    assistantBlocks = mutableListOf()
                    pendingToolUses = linkedMapOf()
                    assistantText = StringBuilder()
                    stopReason = null
                }
                failed = false

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
                            lastError = ev.message
                        }
                    }
                }

                if (!failed) break
            }

            if (failed) {
                emit(AgentEvent.Failure(lastError ?: "Unknown error"))
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
                                if (it is kotlinx.coroutines.CancellationException) throw it
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
        const val DefaultSystemPrompt = """You are an assistant running on Android. You have access to tools that let you read, write, edit, list, and grep through a workspace folder the user has selected, plus an HTTP request tool. Use the tools to answer the user's question. Be concise.

The workspace root may contain an AGENTS.md with project-specific instructions — if present below, follow them. Skills are reusable playbooks; call load_skill with the skill name when a task matches."""
        const val MAX_LLM_RETRIES = 5
    }
}

/**
 * Assemble the system prompt by combining the default prompt with optional
 * project-specific context: an `AGENTS.md` at the workspace root, and a summary
 * of any skills under `.agents/skills/`.
 */
fun buildSystemPrompt(ws: Workspace): String {
    val out = StringBuilder(Agent.DefaultSystemPrompt)
    val agentsMd = ws.read("AGENTS.md")
    if (!agentsMd.isNullOrBlank()) {
        out.append("\n\n# Project instructions (AGENTS.md)\n\n")
        out.append(agentsMd.trim())
    }
    out.append("\n\n# Skills\n\n")
    out.append("Skills are reusable instruction playbooks. Call `load_skill` with the skill name when a task matches.\n")
    out.append("\n## Built-in skills\n\n")
    for ((name, entry) in BuiltinSkills.all) {
        out.append("- **$name** — ${entry.description}\n")
    }
    val skills = discoverSkills(ws)
    if (skills.isNotEmpty()) {
        out.append("\n## Project skills\n\n")
        for (s in skills) {
            out.append("- **${s.name}** (`${s.path}`) — ${s.description}\n")
        }
    }
    return out.toString()
}

data class SkillInfo(val name: String, val description: String, val path: String)

/** Discover skills under .agents/skills/.
 *  Supports folder-based skills (agentskills.io format: <name>/SKILL.md)
 *  and legacy flat .md files side-by-side. */
fun discoverSkills(ws: Workspace): List<SkillInfo> {
    val roots = listOf(".agents/skills")
    val seen = mutableSetOf<String>()
    val out = mutableListOf<SkillInfo>()
    for (root in roots) {
        val entries = ws.ls(root) ?: continue
        for (e in entries) {
            if (e.isDir) {
                // Folder-based skill: <name>/SKILL.md
                val skillPath = "$root/${e.name}/SKILL.md"
                val content = ws.read(skillPath) ?: continue
                val name = e.name
                if (seen.add(name)) {
                    val description = extractSkillDescription(content) ?: "(no description)"
                    out.add(SkillInfo(name = name, description = description, path = skillPath))
                }
            } else if (e.name.endsWith(".md", ignoreCase = true)) {
                // Legacy flat .md file
                val name = e.name.removeSuffix(".md").removeSuffix(".MD")
                if (seen.add(name)) {
                    val path = "$root/${e.name}"
                    val content = ws.read(path) ?: continue
                    val description = extractSkillDescription(content) ?: "(no description)"
                    out.add(SkillInfo(name = name, description = description, path = path))
                }
            }
        }
    }
    return out.sortedBy { it.name }
}

/** Extract a one-line description from a skill file. Looks for YAML frontmatter
 *  `description:` first, then the first non-heading paragraph. */
fun extractSkillDescription(content: String): String? {
    val lines = content.lineSequence().toList()
    if (lines.firstOrNull()?.trim() == "---") {
        var i = 1
        while (i < lines.size && lines[i].trim() != "---") {
            val line = lines[i]
            val idx = line.indexOf(':')
            if (idx > 0) {
                val key = line.substring(0, idx).trim()
                if (key.equals("description", ignoreCase = true)) {
                    val v = line.substring(idx + 1).trim().trim('"', '\'')
                    if (v.isNotBlank()) return v.take(240)
                }
            }
            i++
        }
    }
    for (line in lines) {
        val t = line.trim()
        if (t.isEmpty() || t.startsWith("#") || t.startsWith("---")) continue
        return t.take(240)
    }
    return null
}

/** Tool that loads the full content of a discovered skill by name. */
class LoadSkillTool(private val ws: Workspace) : Tool {
    override val spec = com.edo.app.llm.ToolSpec(
        name = "load_skill",
        description = "Read the full instructions for a skill listed in the system prompt. Provide the skill's name (without .md extension).",
        parametersJson = """{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}""",
    )

    override suspend fun invoke(argsJson: String): ToolResult {
        val obj = parseArgs(argsJson) ?: return argsParseError(argsJson)
        val name = obj["name"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult("missing 'name'", isError = true)
        BuiltinSkills.all[name]?.let { return ToolResult(it.content) }
        val skills = discoverSkills(ws)
        val match = skills.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: return ToolResult(
                "skill '$name' not found. Available: ${(BuiltinSkills.all.keys + skills.map { it.name }).joinToString(", ")}",
                isError = true,
            )
        val content = ws.read(match.path)
            ?: return ToolResult("could not read ${match.path}", isError = true)
        return ToolResult(content)
    }
}
