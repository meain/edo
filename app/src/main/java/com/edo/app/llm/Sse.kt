package com.edo.app.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * One Server-Sent-Events frame. `event` is optional (used by Anthropic).
 * `data` is the joined payload of all `data:` lines in the frame, minus
 * the prefix and one optional leading space.
 */
data class SseEvent(val event: String?, val data: String)

/**
 * Parse a stream of raw lines into SSE events. Blank line terminates a frame.
 * Lines starting with ':' are comments and ignored. Robust to "\r\n" line endings
 * because callers should strip CR before feeding lines in.
 */
fun parseSse(lines: Flow<String>): Flow<SseEvent> = flow {
    var event: String? = null
    val data = StringBuilder()
    var hasContent = false

    suspend fun flushIfPending() {
        if (hasContent) {
            emit(SseEvent(event, data.toString()))
        }
        event = null
        data.setLength(0)
        hasContent = false
    }

    lines.collect { raw ->
        val line = raw.trimEnd('\r')
        if (line.isEmpty()) {
            flushIfPending()
        } else if (line.startsWith(":")) {
            // comment — ignore
        } else if (line.startsWith("event:")) {
            event = line.substring(6).trimStart()
            hasContent = true
        } else if (line.startsWith("data:")) {
            if (data.isNotEmpty()) data.append('\n')
            data.append(line.substring(5).let { if (it.startsWith(" ")) it.substring(1) else it })
            hasContent = true
        }
    }
    flushIfPending()
}

/** Split a single raw string into SSE-style lines. Useful for tests. */
fun linesFlow(text: String): Flow<String> = flow {
    var start = 0
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '\n') {
            emit(text.substring(start, i))
            start = i + 1
        }
        i++
    }
    if (start < text.length) emit(text.substring(start))
}
