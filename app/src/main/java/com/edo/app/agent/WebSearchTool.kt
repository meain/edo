package com.edo.app.agent

import com.edo.app.llm.ToolSpec
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.net.URLDecoder

data class SearchResult(val title: String, val url: String, val snippet: String)

/**
 * Web search via DuckDuckGo Lite. DDG redirects lynx-like clients from `duckduckgo.com/?q=`
 * to the lite endpoint, which is plain HTML; a browser user agent instead gets a bot
 * challenge, so we deliberately identify as lynx.
 */
class WebSearchTool(private val fetcher: HttpFetcher) : Tool {
    override val spec = ToolSpec(
        name = "web_search",
        description = "Search the web (DuckDuckGo) and return result titles, URLs and snippets. " +
            "Use http_request on a result URL to read the full page.",
        parametersJson = """{"type":"object","properties":{"query":{"type":"string"},"limit":{"type":"integer","description":"Max results, default 8, max 20"}},"required":["query"]}""",
    )

    override suspend fun invoke(argsJson: String): ToolResult {
        val args = parseArgs(argsJson) ?: return argsParseError(argsJson)
        val query = (args["query"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (query.isEmpty()) return ToolResult("missing 'query'", isError = true)
        val limit = ((args["limit"] as? JsonPrimitive)?.intOrNull ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

        val url = "https://lite.duckduckgo.com/lite/?q=" + java.net.URLEncoder.encode(query, "UTF-8")
        val raw = try {
            fetcher.fetch(url, "GET", mapOf("User-Agent" to LYNX_UA), null)
        } catch (t: Throwable) {
            return ToolResult("search error: ${t.message}", isError = true)
        }
        // KtorHttpFetcher prefixes the body with "HTTP <status>\n".
        val status = raw.lineSequence().firstOrNull()?.removePrefix("HTTP ")?.trim()
        if (status != "200") {
            return ToolResult(
                "search failed: HTTP $status (DuckDuckGo may be rate-limiting; retry shortly)",
                isError = true,
            )
        }
        val results = parseDdgLite(raw.substringAfter('\n'), limit)
        if (results.isEmpty()) return ToolResult("(no results for \"$query\")")
        return ToolResult(formatResults(results))
    }

    companion object {
        private const val DEFAULT_LIMIT = 8
        private const val MAX_LIMIT = 20
        private const val LYNX_UA = "Lynx/2.9.0dev.12 libwww-FM/2.14 SSL-MM/1.4.1 OpenSSL/3.0.0"

        private val LINK = Regex(
            """<a[^>]*href="([^"]*)"[^>]*class=['"]result-link['"][^>]*>(.*?)</a>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val SNIPPET = Regex(
            """class=['"]result-snippet['"][^>]*>(.*?)</td>""",
            RegexOption.DOT_MATCHES_ALL,
        )

        internal fun parseDdgLite(html: String, limit: Int): List<SearchResult> {
            val links = LINK.findAll(html).toList()
            val out = mutableListOf<SearchResult>()
            for ((i, m) in links.withIndex()) {
                val url = resolveUrl(decodeEntities(m.groupValues[1]))
                // Sponsored results go through DDG's ad redirector; skip them.
                if (url.contains("duckduckgo.com/y.js")) continue
                val region = html.substring(m.range.last + 1, links.getOrNull(i + 1)?.range?.first ?: html.length)
                val snippet = SNIPPET.find(region)?.groupValues?.get(1)?.let { clean(it) }.orEmpty()
                out.add(SearchResult(clean(m.groupValues[2]), url, snippet))
                if (out.size >= limit) break
            }
            return out
        }

        /** DDG wraps targets as `//duckduckgo.com/l/?uddg=<encoded url>&rut=...`. */
        private fun resolveUrl(href: String): String {
            val enc = Regex("""[?&]uddg=([^&]+)""").find(href)?.groupValues?.get(1)
            if (enc != null) return runCatching { URLDecoder.decode(enc, "UTF-8") }.getOrDefault(href)
            return if (href.startsWith("//")) "https:$href" else href
        }

        private fun clean(htmlFragment: String): String =
            decodeEntities(htmlFragment.replace(Regex("<[^>]+>"), ""))
                .replace(Regex("\\s+"), " ")
                .trim()

        private fun decodeEntities(s: String): String =
            Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);").replace(s) { m ->
                val e = m.groupValues[1]
                when {
                    e.startsWith("#x") || e.startsWith("#X") ->
                        e.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
                    e.startsWith("#") ->
                        e.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
                    else -> when (e) {
                        "amp" -> "&"
                        "lt" -> "<"
                        "gt" -> ">"
                        "quot" -> "\""
                        "apos" -> "'"
                        "nbsp" -> " "
                        else -> m.value
                    }
                }
            }

        private fun formatResults(results: List<SearchResult>): String = buildString {
            for ((i, r) in results.withIndex()) {
                if (i > 0) append("\n\n")
                append(i + 1).append(". ").append(r.title).append('\n').append("   ").append(r.url)
                if (r.snippet.isNotEmpty()) append('\n').append("   ").append(r.snippet)
            }
        }
    }
}
