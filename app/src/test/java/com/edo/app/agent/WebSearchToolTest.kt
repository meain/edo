package com.edo.app.agent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchToolTest {

    // Trimmed from a real lite.duckduckgo.com/lite/ response, plus one sponsored row.
    private val html = """
        <table>
        <tr><td valign="top">1.&nbsp;</td><td>
          <a rel="nofollow" href="//duckduckgo.com/y.js?ad_domain=shop.example&amp;u3=x" class='result-link'>Buy stuff</a>
        </td></tr>
        <tr><td>&nbsp;</td><td class='result-snippet'>An ad</td></tr>
        <tr><td valign="top">2.&nbsp;</td><td>
          <a rel="nofollow" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fkotlinlang.org%2Fdocs%2Fcoroutines%2Doverview.html&amp;rut=abc" class='result-link'>Coroutines | Kotlin Documentation</a>
        </td></tr>
        <tr><td>&nbsp;&nbsp;&nbsp;</td><td class='result-snippet'>
          <b>Coroutines</b> in <b>Kotlin</b> are built on suspending functions &amp; it&#x27;s neat.
        </td></tr>
        <tr><td><span class='link-text'>kotlinlang.org/docs/coroutines-overview.html</span></td></tr>
        <tr><td valign="top">3.&nbsp;</td><td>
          <a rel="nofollow" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fdeveloper.android.com%2Fkotlin%2Fcoroutines&amp;rut=def" class='result-link'>Kotlin coroutines on Android</a>
        </td></tr>
        </table>
    """.trimIndent()

    private class FakeFetcher(private val response: String) : HttpFetcher {
        var lastUrl: String? = null
        var lastHeaders: Map<String, String> = emptyMap()
        override suspend fun fetch(url: String, method: String, headers: Map<String, String>, body: String?): String {
            lastUrl = url
            lastHeaders = headers
            return response
        }
    }

    @Test
    fun parsesTitlesUrlsAndSnippetsAndSkipsAds() {
        val results = WebSearchTool.parseDdgLite(html, limit = 10)
        assertEquals(2, results.size)
        assertEquals("Coroutines | Kotlin Documentation", results[0].title)
        assertEquals("https://kotlinlang.org/docs/coroutines-overview.html", results[0].url)
        assertEquals("Coroutines in Kotlin are built on suspending functions & it's neat.", results[0].snippet)
        // No snippet row before the next link -> empty, not borrowed from a neighbour.
        assertEquals("", results[1].snippet)
    }

    @Test
    fun respectsLimit() {
        assertEquals(1, WebSearchTool.parseDdgLite(html, limit = 1).size)
    }

    @Test
    fun invokeQueriesLiteEndpointAsLynx() = runTest {
        val fetcher = FakeFetcher("HTTP 200\n$html")
        val r = WebSearchTool(fetcher).invoke("""{"query":"kotlin coroutines"}""")
        assertFalse(r.isError)
        assertEquals("https://lite.duckduckgo.com/lite/?q=kotlin+coroutines", fetcher.lastUrl)
        assertTrue(fetcher.lastHeaders["User-Agent"]!!.startsWith("Lynx/"))
        assertTrue(r.content.startsWith("1. Coroutines | Kotlin Documentation\n   https://kotlinlang.org"))
    }

    @Test
    fun nonOkStatusIsError() = runTest {
        val r = WebSearchTool(FakeFetcher("HTTP 202\n")).invoke("""{"query":"x"}""")
        assertTrue(r.isError)
        assertTrue(r.content.contains("202"))
    }

    @Test
    fun emptyResultsIsNotAnError() = runTest {
        val r = WebSearchTool(FakeFetcher("HTTP 200\n<html></html>")).invoke("""{"query":"zzz"}""")
        assertFalse(r.isError)
        assertTrue(r.content.contains("no results"))
    }

    @Test
    fun missingQueryIsError() = runTest {
        val r = WebSearchTool(FakeFetcher("HTTP 200\n")).invoke("""{}""")
        assertTrue(r.isError)
    }
}
