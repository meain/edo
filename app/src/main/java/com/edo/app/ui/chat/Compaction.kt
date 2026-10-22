package com.edo.app.ui.chat

import com.edo.app.llm.Block
import com.edo.app.llm.ConvMessage
import com.edo.app.llm.Role

internal const val COMPACT_SYSTEM =
    "You compact a conversation between a user and an AI assistant so the work can continue in a " +
        "fresh chat. Write a summary the assistant can carry on from without seeing the original: " +
        "the user's goals and preferences, decisions made and why, key findings, the current state " +
        "of the work (files created or changed with their paths, commands run and what mattered in " +
        "their output), and open questions or next steps. Keep exact names, paths, numbers and URLs. " +
        "Be specific and compact. Reply with only the summary, no preamble."

private const val MAX_TOOL_ARGS = 400
private const val MAX_TOOL_RESULT = 800
private const val MAX_ATTACHED_FILE = 1500

/**
 * The conversation as plain text for the compaction prompt. Tool calls and results are cut short
 * and attached files trimmed, so a long tool-heavy chat still fits in one request.
 */
internal fun compactionTranscript(conversation: List<ConvMessage>): String = buildString {
    for (msg in conversation) {
        for (b in msg.blocks) {
            when (b) {
                is Block.Text -> if (msg.role == Role.User) {
                    val (files, text) = splitAttachedFiles(b.text)
                    for (f in files) {
                        appendLine("User attached ${f.name}:")
                        appendLine(f.content.take(MAX_ATTACHED_FILE) + if (f.chars > MAX_ATTACHED_FILE) " …[trimmed]" else "")
                    }
                    if (text.isNotEmpty()) appendLine("User: $text")
                } else {
                    appendLine("Assistant: ${b.text}")
                }
                is Block.Image -> appendLine("User: [image]")
                is Block.ToolUse -> appendLine("Assistant called ${b.name}(${b.argsJson.take(MAX_TOOL_ARGS)})")
                is Block.ToolResult -> appendLine(
                    "Tool result${if (b.isError) " (error)" else ""}: " +
                        b.content.take(MAX_TOOL_RESULT) + if (b.content.length > MAX_TOOL_RESULT) " …[trimmed]" else ""
                )
            }
        }
        appendLine()
    }
}.trim()

/** First message of a compacted chat: the summary, framed so the model knows where it came from. */
internal fun compactedOpening(summary: String): String =
    "This chat continues an earlier conversation. Here is a summary of it:\n\n$summary"

internal const val COMPACTED_ACK = "Understood, I have the context from the earlier chat. What would you like to do next?"
