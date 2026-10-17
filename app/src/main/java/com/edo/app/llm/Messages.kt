package com.edo.app.llm

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Provider-neutral conversation model. Each provider client translates
 * Conversation <-> wire format.
 */

enum class Role { System, User, Assistant }

sealed interface Block {
    data class Text(val text: String) : Block

    /** Image as raw base64 data — sufficient for both Anthropic & OpenAI. */
    data class Image(val mediaType: String, val base64: String) : Block

    data class ToolUse(
        val id: String,
        val name: String,
        val argsJson: String,
    ) : Block

    data class ToolResult(
        val toolUseId: String,
        val content: String,
        val isError: Boolean = false,
    ) : Block
}

data class ConvMessage(
    val role: Role,
    val blocks: List<Block>,
)

/**
 * Give every assistant tool_use a matching tool_result in the very next message, which the APIs
 * require. A run cancelled mid-tool and followed by a plain user message leaves such a gap in the
 * middle of history, where reload's trailing-only strip can't reach it.
 */
fun withToolResultsRepaired(messages: List<ConvMessage>): List<ConvMessage> {
    val out = ArrayList<ConvMessage>(messages.size)
    for ((i, m) in messages.withIndex()) {
        out.add(m)
        if (m.role != Role.Assistant) continue
        val uses = m.blocks.filterIsInstance<Block.ToolUse>()
        if (uses.isEmpty()) continue
        val next = messages.getOrNull(i + 1)
        val answered = next?.takeIf { it.role == Role.User }
            ?.blocks?.filterIsInstance<Block.ToolResult>()?.map { it.toolUseId }?.toSet().orEmpty()
        val missing = uses.filter { it.id !in answered }.map {
            Block.ToolResult(it.id, "Interrupted before this finished; it may not have completed.", true)
        }
        if (missing.isEmpty()) continue
        if (next != null && next.role == Role.User && answered.isNotEmpty()) {
            // Partially answered: top up the existing results message below.
            out.add(ConvMessage(Role.User, next.blocks + missing))
            // Skip the original next message by marking it consumed.
            return out + withToolResultsRepaired(messages.drop(i + 2))
        }
        out.add(ConvMessage(Role.User, missing))
    }
    return out
}

data class ToolSpec(
    val name: String,
    val description: String,
    /** JSON Schema as a raw JSON string. */
    val parametersJson: String,
)

/** Streaming event emitted by an LLM client. */
sealed interface LlmEvent {
    data class TextDelta(val text: String) : LlmEvent

    /** A new tool-use block is starting. */
    data class ToolUseStart(val id: String, val name: String) : LlmEvent

    /** A chunk of the tool's JSON arguments. Clients should accumulate. */
    data class ToolUseArgsDelta(val id: String, val partialJson: String) : LlmEvent

    /** The current tool-use block is complete. */
    data class ToolUseEnd(val id: String) : LlmEvent

    /** End of the assistant turn (no more deltas this turn). */
    data class TurnEnd(val stopReason: String?) : LlmEvent

    /** Token usage for the response that just streamed; may arrive anywhere in the stream. */
    data class Usage(val usage: TokenUsage) : LlmEvent

    data class Failure(val message: String) : LlmEvent
}

/**
 * Token counts for one model response. [inputTokens] excludes cache reads and writes, which are
 * counted separately; [costUsd] is set only when the provider reports the price itself.
 */
data class TokenUsage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val costUsd: Double? = null,
)

internal fun JsonObject.long(key: String): Long = this[key]?.jsonPrimitive?.longOrNull ?: 0L
