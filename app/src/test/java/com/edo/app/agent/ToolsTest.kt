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
        val r = ReadTool(ws).invoke("""{"path":"a.txt"}""")
        assertFalse(r.isError)
        assertEquals("hello", r.content)
    }

    @Test
    fun readMissingPathIsError() = runTest {
        val ws = InMemoryWorkspace()
        val r = ReadTool(ws).invoke("""{"path":"nope.txt"}""")
        assertTrue(r.isError)
        assertTrue(r.content.contains("not found"))
    }

    @Test
    fun readSliceByOffsetAndLimit() = runTest {
        val ws = InMemoryWorkspace(mapOf("a.txt" to "one\ntwo\nthree\nfour"))
        val r = ReadTool(ws).invoke("""{"path":"a.txt","offset":2,"limit":2}""")
        assertFalse(r.isError)
        assertEquals("[lines 2-3 of 4]\ntwo\nthree", r.content)
    }

    @Test
    fun readOffsetPastEndIsEmptySlice() = runTest {
        val ws = InMemoryWorkspace(mapOf("a.txt" to "one\ntwo"))
        val r = ReadTool(ws).invoke("""{"path":"a.txt","offset":99}""")
        assertFalse(r.isError)
        assertEquals("[lines 3-2 of 2]\n", r.content)
    }

    @Test
    fun readGlobFindsFilesAtAnyDepth() = runTest {
        val ws = InMemoryWorkspace(mapOf("a.md" to "x", "docs/b.md" to "y", "docs/c.txt" to "z"))
        val r = ReadTool(ws).invoke("""{"pattern":"*.md"}""")
        assertFalse(r.isError)
        assertTrue(r.content.contains("a.md"))
        assertTrue(r.content.contains("docs/b.md"))
        assertFalse(r.content.contains("c.txt"))
    }

    @Test
    fun readGlobScopedToDirectoryWithAlternation() = runTest {
        val ws = InMemoryWorkspace(mapOf("src/a.kt" to "x", "src/deep/b.java" to "y", "other/c.kt" to "z"))
        val r = ReadTool(ws).invoke("""{"path":"src","pattern":"**/*.{kt,java}"}""")
        assertFalse(r.isError)
        assertTrue(r.content.contains("a.kt"))
        assertTrue(r.content.contains("deep/b.java"))
        assertFalse(r.content.contains("c.kt"))
    }

    @Test
    fun readGlobNoMatches() = runTest {
        val ws = InMemoryWorkspace(mapOf("a.txt" to "x"))
        assertEquals("(no matches)", ReadTool(ws).invoke("""{"pattern":"*.md"}""").content)
    }

    @Test
    fun readPatternOnFileIsError() = runTest {
        val ws = InMemoryWorkspace(mapOf("a.txt" to "x"))
        val r = ReadTool(ws).invoke("""{"path":"a.txt","pattern":"*.md"}""")
        assertTrue(r.isError)
    }

    @Test
    fun writeFilePersists() = runTest {
        val ws = InMemoryWorkspace()
        val r = WriteFileTool(ws).invoke("""{"path":"dir/b.txt","content":"yo"}""")
        assertFalse(r.isError)
        assertEquals("yo", ws.read("dir/b.txt"))
        assertTrue("expected 'created' verb in: ${r.content}", r.content.contains("created"))
    }

    @Test
    fun writeFileOverwritesExisting() = runTest {
        val ws = InMemoryWorkspace(mapOf("a.txt" to "old"))
        val r = WriteFileTool(ws).invoke("""{"path":"a.txt","content":"new"}""")
        assertFalse(r.isError)
        assertEquals("new", ws.read("a.txt"))
        assertTrue("expected 'overwrote' verb in: ${r.content}", r.content.contains("overwrote"))
    }

    @Test
    fun editFileReplacesUniqueOccurrence() = runTest {
        val ws = InMemoryWorkspace(mapOf("a.kt" to "val x = 1\nval y = 2\n"))
        val r = EditFileTool(ws).invoke("""{"path":"a.kt","old_string":"val x = 1","new_string":"val x = 42"}""")
        assertFalse(r.isError)
        assertEquals("val x = 42\nval y = 2\n", ws.read("a.kt"))
    }

    @Test
    fun editFileRejectsAmbiguous() = runTest {
        val ws = InMemoryWorkspace(mapOf("a.kt" to "foo\nfoo\n"))
        val r = EditFileTool(ws).invoke("""{"path":"a.kt","old_string":"foo","new_string":"bar"}""")
        assertTrue(r.isError)
        assertTrue(r.content.contains("multiple"))
    }

    @Test
    fun editFileReplaceAllChangesEvery() = runTest {
        val ws = InMemoryWorkspace(mapOf("a.kt" to "foo\nfoo\nfoo\n"))
        val r = EditFileTool(ws).invoke("""{"path":"a.kt","old_string":"foo","new_string":"bar","replace_all":"true"}""")
        assertFalse(r.isError)
        assertEquals("bar\nbar\nbar\n", ws.read("a.kt"))
        assertTrue(r.content.contains("3"))
    }

    @Test
    fun editFileMissingFileIsError() = runTest {
        val ws = InMemoryWorkspace()
        val r = EditFileTool(ws).invoke("""{"path":"a.kt","old_string":"x","new_string":"y"}""")
        assertTrue(r.isError)
    }

    @Test
    fun editFileNotFoundOldStringIsError() = runTest {
        val ws = InMemoryWorkspace(mapOf("a.kt" to "abc"))
        val r = EditFileTool(ws).invoke("""{"path":"a.kt","old_string":"zzz","new_string":"y"}""")
        assertTrue(r.isError)
        assertTrue(r.content.contains("not found"))
    }

    @Test
    fun lsListsTopLevel() = runTest {
        val ws = InMemoryWorkspace(mapOf("a.txt" to "x", "sub/b.txt" to "y"))
        val r = ReadTool(ws).invoke("""{}""")
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
    fun fetchPageConvertsHtmlToMarkdown() = runTest {
        val html = """<html><head><title>T</title><script>var x=1;</script></head><body>
            <nav>menu</nav><article><h1>Head</h1><p>Hello <a href="/rel">world</a> and <strong>bold</strong>.</p>
            <ul><li>one</li><li>two</li></ul><pre>a < b</pre></article><footer>foot</footer></body></html>"""
        val fake = object : HttpFetcher {
            override suspend fun fetch(url: String, method: String, headers: Map<String, String>, body: String?) =
                "HTTP 200\n$html"
        }
        val r = FetchPageTool(fake).invoke("""{"url":"https://x.com/a"}""")
        assertFalse(r.isError)
        assertTrue(r.content, r.content.contains("Title: T"))
        assertTrue(r.content, r.content.contains("# Head"))
        assertTrue(r.content, r.content.contains("[world](https://x.com/rel)"))
        assertTrue(r.content, r.content.contains("**bold**"))
        assertTrue(r.content, r.content.contains("- one\n- two"))
        assertTrue(r.content, r.content.contains("```\na < b\n```"))
        assertFalse(r.content.contains("menu"))
        assertFalse(r.content.contains("foot"))
        assertFalse(r.content.contains("var x"))
    }

    @Test
    fun fetchPageReturnsNonHtmlUnchangedAndPages() = runTest {
        val big = "a".repeat(50_000)
        val fake = object : HttpFetcher {
            override suspend fun fetch(url: String, method: String, headers: Map<String, String>, body: String?) =
                "HTTP 200\n$big"
        }
        val first = FetchPageTool(fake).invoke("""{"url":"https://x"}""")
        assertTrue(first.content.contains("call again with offset=40000"))
        val second = FetchPageTool(fake).invoke("""{"url":"https://x","offset":40000}""")
        assertEquals("a".repeat(10_000), second.content)
    }

    @Test
    fun fetchPageRejectsBadUrlAndHttpErrors() = runTest {
        val fake = object : HttpFetcher {
            override suspend fun fetch(url: String, method: String, headers: Map<String, String>, body: String?) =
                "HTTP 404\nnope"
        }
        assertTrue(FetchPageTool(fake).invoke("""{"url":"file:///etc/passwd"}""").isError)
        val r = FetchPageTool(fake).invoke("""{"url":"https://x"}""")
        assertTrue(r.isError)
        assertTrue(r.content.contains("404"))
    }

    @Test
    fun deleteFileRemovesEntry() = runTest {
        val ws = InMemoryWorkspace(mapOf("a.txt" to "x", "keep.txt" to "y"))
        val r = DeleteFileTool(ws).invoke("""{"path":"a.txt"}""")
        assertFalse(r.isError)
        assertEquals(null, ws.read("a.txt"))
        assertEquals("y", ws.read("keep.txt"))
        assertTrue(r.content.contains("deleted"))
    }

    @Test
    fun deleteFileMissingIsError() = runTest {
        val ws = InMemoryWorkspace()
        val r = DeleteFileTool(ws).invoke("""{"path":"nope.txt"}""")
        assertTrue(r.isError)
    }

    @Test
    fun copyFileDuplicatesContent() = runTest {
        val ws = InMemoryWorkspace(mapOf("a.txt" to "hello"))
        val r = CopyFileTool(ws).invoke("""{"source":"a.txt","dest":"sub/b.txt"}""")
        assertFalse(r.isError)
        assertEquals("hello", ws.read("a.txt"))
        assertEquals("hello", ws.read("sub/b.txt"))
        assertTrue(r.content.contains("copied"))
    }

    @Test
    fun copyFileMissingSourceIsError() = runTest {
        val ws = InMemoryWorkspace()
        val r = CopyFileTool(ws).invoke("""{"source":"missing.txt","dest":"out.txt"}""")
        assertTrue(r.isError)
    }

    @Test
    fun copyFileSameSourceAndDestIsError() = runTest {
        val ws = InMemoryWorkspace(mapOf("a.txt" to "x"))
        val r = CopyFileTool(ws).invoke("""{"source":"a.txt","dest":"a.txt"}""")
        assertTrue(r.isError)
    }

    @Test
    fun writeFileMalformedJsonReportsParseError() = runTest {
        val ws = InMemoryWorkspace()
        // Unescaped double quote inside the "content" string — common when an
        // LLM forgets to escape quotes in raw HTML/code.
        val bad = """{"path":"page.html","content":"<a href="x">hi</a>"}"""
        val r = WriteFileTool(ws).invoke(bad)
        assertTrue(r.isError)
        assertTrue(
            "expected hint about JSON escaping, got: ${r.content}",
            r.content.contains("invalid tool arguments JSON"),
        )
        // The model should see the offending input echoed back so it can correct.
        assertTrue("expected raw args echoed, got: ${r.content}", r.content.contains("page.html"))
    }

    @Test
    fun dateTimeReturnsStableFormat() = runTest {
        // Fixed clock: 2026-06-15T12:00:00Z → 1750982400000 ms
        val fixed = 1750982400000L
        val r = DateTimeTool { fixed }.invoke("""{}""")
        assertFalse(r.isError)
        assertTrue("expected ISO local line, got: ${r.content}", r.content.contains("local:"))
        assertTrue("expected UTC line, got: ${r.content}", r.content.contains("utc:"))
        assertTrue("expected zone line, got: ${r.content}", r.content.contains("zone:"))
        assertTrue("expected epoch ms, got: ${r.content}", r.content.contains("$fixed"))
    }

    @Test
    fun fetchPagePropagatesFetcherErrors() = runTest {
        val fake = object : HttpFetcher {
            override suspend fun fetch(url: String, method: String, headers: Map<String, String>, body: String?): String {
                throw RuntimeException("boom")
            }
        }
        val r = FetchPageTool(fake).invoke("""{"url":"https://x"}""")
        assertTrue(r.isError)
        assertTrue(r.content.contains("boom"))
    }
}
