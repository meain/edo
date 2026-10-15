package com.edo.app.agent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FetchPageTest {
    private fun fetcher(body: String) = object : HttpFetcher {
        override suspend fun fetch(url: String, method: String, headers: Map<String, String>, body2: String?) =
            "HTTP 200\n$body"
    }

    private val para = "This is a reasonably long paragraph of article text that goes on for a while so that " +
        "the extractor has something real to latch on to, with a <a href=\"/more\">relative link</a> inside it. "

    private val html = """<html><head><title>Big Post</title></head><body>
        <div id="sidebar"><ul><li><a href="/a">Popular</a></li><li><a href="/b">Trending</a></li></ul></div>
        <div class="post"><h1>Big Post</h1>${"<p>$para</p>".repeat(6)}</div>
        <div class="comments"><p>first!</p></div></body></html>"""

    @Test
    fun extractsArticleAndResolvesLinks() = runTest {
        val r = FetchPageTool(fetcher(html)).invoke("""{"url":"https://blog.example/p/1"}""")
        assertFalse(r.isError)
        assertTrue(r.content, r.content.contains("reasonably long paragraph"))
        assertTrue(r.content, r.content.contains("[relative link](https://blog.example/more)"))
        assertFalse(r.content, r.content.contains("Trending"))
    }

    @Test
    fun rawReturnsTheHtmlUntouched() = runTest {
        val r = FetchPageTool(fetcher(html)).invoke("""{"url":"https://blog.example/p/1","raw":true}""")
        assertTrue(r.content.startsWith("<html>"))
        assertTrue(r.content.contains("Trending"))
    }

    @Test
    fun emptyShellPageIsAnError() = runTest {
        val r = FetchPageTool(fetcher("<html><body><div id=app></div><script>x()</script></body></html>"))
            .invoke("""{"url":"https://spa.example"}""")
        assertTrue(r.isError)
        assertTrue(r.content.contains("JavaScript"))
    }
}
