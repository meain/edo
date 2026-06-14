package com.edo.app.llm

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAIStreamTest {

    @Test
    fun parsesTextDeltasAndDone() = runTest {
        val raw = """
            data: {"choices":[{"delta":{"content":"Hello "},"index":0}]}

            data: {"choices":[{"delta":{"content":"world"},"index":0}]}

            data: {"choices":[{"delta":{},"finish_reason":"stop","index":0}]}

            data: [DONE]

        """.trimIndent()
        val events = OpenAIClient.parseOpenAIStream(linesFlow(raw)).toList()
        val text = events.filterIsInstance<LlmEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("Hello world", text)
        val end = events.last() as LlmEvent.TurnEnd
        assertEquals("stop", end.stopReason)
    }

    @Test
    fun parsesToolCallsAndArgChunks() = runTest {
        val raw = """
            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"read_file","arguments":""}}]},"index":0}]}

            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"path\":"}}]},"index":0}]}

            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"a.txt\"}"}}]},"index":0}]}

            data: {"choices":[{"delta":{},"finish_reason":"tool_calls","index":0}]}

            data: [DONE]

        """.trimIndent()
        val events = OpenAIClient.parseOpenAIStream(linesFlow(raw)).toList()
        val starts = events.filterIsInstance<LlmEvent.ToolUseStart>()
        assertEquals(1, starts.size)
        assertEquals("read_file", starts[0].name)
        assertEquals("call_1", starts[0].id)
        val args = events.filterIsInstance<LlmEvent.ToolUseArgsDelta>().joinToString("") { it.partialJson }
        assertEquals("{\"path\":\"a.txt\"}", args)
        assertTrue(events.any { it is LlmEvent.ToolUseEnd })
        val end = events.last() as LlmEvent.TurnEnd
        assertEquals("tool_calls", end.stopReason)
    }

    @Test
    fun buildsRequestBodyWithToolResults() {
        val convo = listOf(
            ConvMessage(Role.User, listOf(Block.Text("hi"))),
            ConvMessage(Role.Assistant, listOf(Block.ToolUse("c1", "read_file", "{\"path\":\"a.txt\"}"))),
            ConvMessage(Role.User, listOf(Block.ToolResult("c1", "contents", false))),
        )
        val tools = listOf(ToolSpec("read_file", "reads", """{"type":"object"}"""))
        val body = OpenAIClient.buildRequestJson("m", "sys", convo, tools)
        assertTrue("has system message", body.contains("\"role\":\"system\""))
        assertTrue("has user", body.contains("\"role\":\"user\""))
        assertTrue("has tool_calls", body.contains("\"tool_calls\""))
        assertTrue("has tool role", body.contains("\"role\":\"tool\""))
        assertTrue("includes tool_call_id", body.contains("\"tool_call_id\":\"c1\""))
        assertTrue("includes function tool spec", body.contains("\"type\":\"function\""))
    }

    @Test
    fun emitsFailureOnError() = runTest {
        val raw = """data: {"error":{"message":"nope"}}

"""
        val events = OpenAIClient.parseOpenAIStream(linesFlow(raw)).toList()
        val f = events.single() as LlmEvent.Failure
        assertEquals("nope", f.message)
    }

    @Test
    fun userImageMessageBecomesArrayContent() {
        val convo = listOf(
            ConvMessage(Role.User, listOf(Block.Image("image/png", "B64"), Block.Text("describe"))),
        )
        val body = OpenAIClient.buildRequestJson("m", null, convo, emptyList())
        assertTrue(body.contains("\"type\":\"image_url\""))
        assertTrue(body.contains("data:image/png;base64,B64"))
        assertTrue(body.contains("\"type\":\"text\""))
    }
}
