package com.edo.app.llm

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SseTest {

    @Test
    fun parsesNamedEventsWithSpaceAfterColon() = runTest {
        val raw = """
            event: foo
            data: {"a":1}

            event: bar
            data: hello

        """.trimIndent()
        val out = parseSse(linesFlow(raw)).toList()
        assertEquals(2, out.size)
        assertEquals(SseEvent("foo", "{\"a\":1}"), out[0])
        assertEquals(SseEvent("bar", "hello"), out[1])
    }

    @Test
    fun joinsMultipleDataLines() = runTest {
        val raw = "data: line1\ndata: line2\n\n"
        val out = parseSse(linesFlow(raw)).toList()
        assertEquals(1, out.size)
        assertEquals("line1\nline2", out[0].data)
    }

    @Test
    fun ignoresCommentLines() = runTest {
        val raw = ": ping\ndata: ok\n\n"
        val out = parseSse(linesFlow(raw)).toList()
        assertEquals(1, out.size)
        assertEquals("ok", out[0].data)
    }

    @Test
    fun handlesCrlf() = runTest {
        val raw = "event: x\r\ndata: 1\r\n\r\n"
        val out = parseSse(linesFlow(raw)).toList()
        assertEquals(1, out.size)
        assertEquals("x", out[0].event)
        assertEquals("1", out[0].data)
    }
}
