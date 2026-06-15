package com.edo.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolHeaderTest {

    @Test
    fun toolHeaderShowsPathOnComplete() {
        val (label, detail) = toolHeader("write_file", """{"path":"index.html","content":"<h1>hi</h1>"}""")
        assertEquals("Write", label)
        assertEquals("index.html", detail)
    }

    @Test
    fun toolHeaderShowsPathOnPartialJsonDuringStreaming() {
        // Streaming has emitted the path key but content is still mid-stream.
        val partial = """{"path":"app/index.html","content":"<html"""
        val (label, detail) = toolHeader("write_file", partial)
        assertEquals("Write", label)
        assertEquals("app/index.html", detail)
    }

    @Test
    fun toolHeaderEmptyArgsFallsBackToBlank() {
        val (label, detail) = toolHeader("write_file", "")
        assertEquals("Write", label)
        assertEquals("", detail)
    }

    @Test
    fun extractPartialUnescapesCommonSequences() {
        val src = """{"path":"a/b\nc.txt","other":1"""
        assertEquals("a/b\nc.txt", extractPartialString(src, "path"))
    }

    @Test
    fun extractPartialReturnsNullForMissingField() {
        assertNull(extractPartialString("""{"path":"x"}""", "name"))
    }
}
