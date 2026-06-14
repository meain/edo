package com.edo.app.llm

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicStreamTest {

    @Test
    fun parsesTextDeltasAndStop() = runTest {
        val raw = """
            event: message_start
            data: {"type":"message_start","message":{"id":"m1","content":[],"role":"assistant"}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello "}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"world"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null}}

            event: message_stop
            data: {"type":"message_stop"}

        """.trimIndent()
        val events = AnthropicClient.parseAnthropicStream(linesFlow(raw)).toList()
        val texts = events.filterIsInstance<LlmEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("Hello world", texts)
        val end = events.last() as LlmEvent.TurnEnd
        assertEquals("end_turn", end.stopReason)
    }

    @Test
    fun parsesToolUseBlock() = runTest {
        val raw = """
            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"read_file"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"path\":"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"\"a.txt\"}"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"tool_use"}}

            event: message_stop
            data: {"type":"message_stop"}

        """.trimIndent()
        val events = AnthropicClient.parseAnthropicStream(linesFlow(raw)).toList()
        val starts = events.filterIsInstance<LlmEvent.ToolUseStart>()
        assertEquals(1, starts.size)
        assertEquals("read_file", starts[0].name)
        assertEquals("toolu_1", starts[0].id)
        val args = events.filterIsInstance<LlmEvent.ToolUseArgsDelta>().joinToString("") { it.partialJson }
        assertEquals("{\"path\":\"a.txt\"}", args)
        assertTrue(events.any { it is LlmEvent.ToolUseEnd && it.id == "toolu_1" })
        val end = events.last() as LlmEvent.TurnEnd
        assertEquals("tool_use", end.stopReason)
    }

    @Test
    fun emitsFailureOnErrorEvent() = runTest {
        val raw = """
            event: error
            data: {"type":"error","error":{"type":"overloaded_error","message":"too busy"}}

        """.trimIndent()
        val events = AnthropicClient.parseAnthropicStream(linesFlow(raw)).toList()
        assertEquals(1, events.size)
        val f = events.single() as LlmEvent.Failure
        assertEquals("too busy", f.message)
    }

    @Test
    fun buildsRequestBodyWithToolsAndImage() {
        val convo = listOf(
            ConvMessage(Role.User, listOf(
                Block.Image("image/png", "AAA"),
                Block.Text("hi"),
            )),
        )
        val tools = listOf(ToolSpec("read_file", "reads", """{"type":"object"}"""))
        val body = AnthropicClient.buildRequestJson("m", "sys", convo, tools, 100)
        assertTrue(body.contains("\"system\":\"sys\""))
        assertTrue(body.contains("\"type\":\"image\""))
        assertTrue(body.contains("\"media_type\":\"image/png\""))
        assertTrue(body.contains("\"data\":\"AAA\""))
        assertTrue(body.contains("\"tools\""))
        assertTrue(body.contains("\"input_schema\""))
        assertTrue(body.contains("\"stream\":true"))
    }
}
