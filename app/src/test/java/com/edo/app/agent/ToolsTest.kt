package com.edo.app.agent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolsTest {

    @Test
    fun readFileReturnsContent() = runTest {
        val ws = InMemoryWorkspace(mapOf("a.txt" to "hello"))
        val r = ReadFileTool(ws).invoke("""{"path":"a.txt"}""")
        assertFalse(r.isError)
        assertEquals("hello", r.content)
    }

    @Test
    fun readFileMissingPathIsError() = runTest {
        val ws = InMemoryWorkspace()
        val r = ReadFileTool(ws).invoke("""{}""")
        assertTrue(r.isError)
    }

    @Test
    fun writeFilePersists() = runTest {
        val ws = InMemoryWorkspace()
        val r = WriteFileTool(ws).invoke("""{"path":"dir/b.txt","content":"yo"}""")
        assertFalse(r.isError)
        assertEquals("yo", ws.read("dir/b.txt"))
    }

    @Test
    fun lsListsTopLevel() = runTest {
        val ws = InMemoryWorkspace(mapOf("a.txt" to "x", "sub/b.txt" to "y"))
        val r = LsTool(ws).invoke("""{}""")
        assertFalse(r.isError)
        assertTrue(r.content.contains("sub/"))
        assertTrue(r.content.contains("a.txt"))
    }

    @Test
    fun grepFindsMatches() = runTest {
        val ws = InMemoryWorkspace(mapOf(
            "a.txt" to "alpha\nbeta\nGamma",
            "b.txt" to "delta",
        ))
        val r = GrepTool(ws).invoke("""{"pattern":"^[a-z]"}""")
        assertFalse(r.isError)
        assertTrue(r.content.contains("a.txt:1:"))
        assertTrue(r.content.contains("alpha"))
        assertTrue(r.content.contains("b.txt:1:"))
        assertFalse("Gamma should not match lowercase pattern", r.content.contains("Gamma"))
    }

    @Test
    fun grepInvalidRegexIsError() = runTest {
        val ws = InMemoryWorkspace(mapOf("a.txt" to "x"))
        val r = GrepTool(ws).invoke("""{"pattern":"(unclosed"}""")
        assertTrue(r.isError)
    }

    @Test
    fun grepNoMatchesReturnsEmptyMessage() = runTest {
        val ws = InMemoryWorkspace(mapOf("a.txt" to "x"))
        val r = GrepTool(ws).invoke("""{"pattern":"zzz"}""")
        assertFalse(r.isError)
        assertEquals("(no matches)", r.content)
    }

    @Test
    fun httpRequestFetcherReturnsBody() = runTest {
        val fake = object : HttpFetcher {
            override suspend fun fetch(url: String, method: String, headers: Map<String, String>, body: String?): String {
                return "HTTP 200\n$url $method ${body ?: ""}"
            }
        }
        val r = HttpRequestTool(fake).invoke("""{"url":"https://x","method":"POST","body":"hi"}""")
        assertFalse(r.isError)
        assertTrue(r.content.contains("https://x"))
        assertTrue(r.content.contains("POST"))
        assertTrue(r.content.contains("hi"))
    }

    @Test
    fun httpRequestPropagatesFetcherErrors() = runTest {
        val fake = object : HttpFetcher {
            override suspend fun fetch(url: String, method: String, headers: Map<String, String>, body: String?): String {
                throw RuntimeException("boom")
            }
        }
        val r = HttpRequestTool(fake).invoke("""{"url":"https://x"}""")
        assertTrue(r.isError)
        assertTrue(r.content.contains("boom"))
    }
}
