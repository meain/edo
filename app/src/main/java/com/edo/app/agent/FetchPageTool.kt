package com.edo.app.agent

import com.edo.app.llm.ToolSpec
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import net.dankito.readability4j.Readability4J
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Reads a web page as Markdown: HTML is stripped down to the article text (headings, lists, links,
 * code, tables), anything else is returned as-is. Long pages come back in chunks; ask for the next
 * one with `offset`. Replaces the old raw `http_request`; scripts that need other methods or
 * headers use Lua's `http.*`.
 */
class FetchPageTool(private val fetcher: HttpFetcher) : Tool {
    override val spec = ToolSpec(
        name = "fetch_page",
        description = "Fetch a web page and return its readable text as Markdown, with links kept. " +
            "Non-HTML responses (JSON, plain text) come back unchanged. Long pages are returned in chunks: " +
            "pass the offset from the truncation note to continue.",
        parametersJson = """{"type":"object","properties":{"url":{"type":"string"},"raw":{"type":"boolean","description":"Return the full unprocessed HTML instead of Markdown, default false"},"offset":{"type":"integer","description":"Character offset to continue from, default 0"}},"required":["url"]}""",
    )

    override suspend fun invoke(argsJson: String): ToolResult {
        val args = parseArgs(argsJson) ?: return argsParseError(argsJson)
        val url = (args["url"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (url.isEmpty()) return ToolResult("missing 'url'", isError = true)
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolResult("url must start with http:// or https://", isError = true)
        }
        val offset = ((args["offset"] as? JsonPrimitive)?.intOrNull ?: 0).coerceAtLeast(0)

        val raw = try {
            fetcher.fetch(url, "GET", mapOf("User-Agent" to BROWSER_UA, "Accept" to "text/html,*/*;q=0.8"), null)
        } catch (t: Throwable) {
            return ToolResult("fetch error: ${t.message}", isError = true)
        }
        // KtorHttpFetcher prefixes the body with "HTTP <status>\n".
        val status = raw.lineSequence().firstOrNull()?.removePrefix("HTTP ")?.trim().orEmpty()
        val body = raw.substringAfter('\n', "")
        if (!status.startsWith("2")) return ToolResult("fetch failed: HTTP $status", isError = true)

        val wantRaw = (args["raw"] as? JsonPrimitive)?.booleanOrNull ?: false
        val text = if (!wantRaw && looksLikeHtml(body)) {
            val page = withContext(Dispatchers.Default) { htmlToMarkdown(body, url) }
            if (page.body.isBlank()) {
                return ToolResult("page has no readable text (it may need JavaScript to render)", isError = true)
            }
            buildString {
                if (page.title.isNotBlank()) append("Title: ").append(page.title).append('\n')
                append("URL: ").append(url).append("\n\n").append(page.body)
            }
        } else body

        if (offset >= text.length) return ToolResult("(offset $offset is past the end; page is ${text.length} characters)")
        val end = minOf(text.length, offset + PAGE_CHARS)
        val chunk = text.substring(offset, end)
        return ToolResult(
            if (end < text.length) "$chunk\n…[truncated at $end of ${text.length} characters; call again with offset=$end for the rest]"
            else chunk,
        )
    }

    companion object {
        private const val PAGE_CHARS = 40_000
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

        internal fun looksLikeHtml(body: String): Boolean {
            val head = body.take(2000).lowercase()
            return "<!doctype html" in head || "<html" in head || "<body" in head
        }
    }
}

internal data class MarkdownPage(val title: String, val body: String)

private val JUNK = "script,style,noscript,template,svg,iframe,canvas,nav,footer,aside,button,select,input,[hidden],[aria-hidden=true]"
private val WHITESPACE = Regex("\\s+")

/**
 * Converts [html] to Markdown, keeping the main content. Mozilla Readability (via Readability4J)
 * picks the article; when it finds nothing substantial (index pages, apps) we fall back to
 * `article` / `main`, else the whole body.
 */
internal fun htmlToMarkdown(html: String, baseUrl: String): MarkdownPage {
    val doc = Jsoup.parse(html, baseUrl)
    val title = doc.title().trim()
    val article = runCatching { Readability4J(baseUrl, html).parse() }.getOrNull()
    val articleHtml = article?.content
    if (!articleHtml.isNullOrBlank()) {
        val fragment = Jsoup.parseBodyFragment(articleHtml, baseUrl).body()
        val body = elementToMarkdown(fragment)
        if (body.length >= MIN_ARTICLE_CHARS) {
            return MarkdownPage(article.title?.trim()?.ifBlank { null } ?: title, body)
        }
    }
    val root = doc.selectFirst("article") ?: doc.selectFirst("main, [role=main]") ?: doc.body()
        ?: return MarkdownPage(title, "")
    return MarkdownPage(title, elementToMarkdown(root))
}

private const val MIN_ARTICLE_CHARS = 200

private fun elementToMarkdown(root: Element): String {
    root.select(JUNK).remove()
    return renderChildren(root, 0)
        .lines().joinToString("\n") { it.trimEnd() }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

private fun renderChildren(el: Element, depth: Int): String {
    val out = StringBuilder()
    for (child in el.childNodes()) walk(child, out, depth, pre = false)
    return out.toString()
}

private fun StringBuilder.blank() {
    if (isEmpty()) return
    while (isNotEmpty() && last() == ' ') setLength(length - 1)
    if (!endsWith("\n")) append('\n')
    if (!endsWith("\n\n")) append('\n')
}

private fun StringBuilder.newline() {
    if (isEmpty()) return
    while (isNotEmpty() && last() == ' ') setLength(length - 1)
    if (!endsWith("\n")) append('\n')
}

private fun inline(el: Element, depth: Int): String =
    renderChildren(el, depth).replace(WHITESPACE, " ").trim()

private fun walk(node: Node, out: StringBuilder, depth: Int, pre: Boolean) {
    if (node is TextNode) {
        if (pre) {
            out.append(node.wholeText)
        } else {
            val t = node.wholeText.replace(WHITESPACE, " ")
            val atStart = out.isEmpty() || out.endsWith("\n") || out.endsWith(" ")
            out.append(if (atStart) t.trimStart() else t)
        }
        return
    }
    if (node !is Element) return
    when (val tag = node.normalName()) {
        "h1", "h2", "h3", "h4", "h5", "h6" -> {
            val text = inline(node, depth)
            if (text.isNotEmpty()) {
                out.blank(); out.append("#".repeat(tag[1] - '0')).append(' ').append(text); out.blank()
            }
        }
        "p", "div", "section", "article", "main", "header", "figure", "figcaption", "dl", "dt", "dd", "details", "summary" -> {
            out.blank(); for (c in node.childNodes()) walk(c, out, depth, pre); out.blank()
        }
        "br" -> out.append('\n')
        "hr" -> { out.blank(); out.append("---"); out.blank() }
        "ul", "ol" -> {
            out.blank()
            var n = 1
            for (c in node.children()) {
                if (c.normalName() == "li") {
                    out.newline()
                    out.append("  ".repeat(depth)).append(if (tag == "ol") "${n++}. " else "- ")
                    val item = StringBuilder()
                    for (g in c.childNodes()) walk(g, item, depth + 1, pre)
                    out.append(item.toString().trim())
                    out.newline()
                } else walk(c, out, depth, pre)
            }
            out.blank()
        }
        "a" -> {
            val text = inline(node, depth)
            val href = node.absUrl("href")
            if (text.isEmpty()) return
            if (href.isBlank() || href.startsWith("javascript:") || href.startsWith("#")) out.append(text)
            else out.append('[').append(text).append("](").append(href).append(')')
        }
        "strong", "b" -> inline(node, depth).takeIf { it.isNotEmpty() }?.let { out.append("**").append(it).append("**") }
        "em", "i" -> inline(node, depth).takeIf { it.isNotEmpty() }?.let { out.append('*').append(it).append('*') }
        "code" -> if (pre) out.append(node.wholeText()) else
            inline(node, depth).takeIf { it.isNotEmpty() }?.let { out.append('`').append(it).append('`') }
        "pre" -> { out.blank(); out.append("```\n").append(node.wholeText().trim('\n')).append("\n```"); out.blank() }
        "blockquote" -> {
            val quoted = renderChildren(node, depth).trim()
            if (quoted.isNotEmpty()) {
                out.blank(); out.append(quoted.lines().joinToString("\n") { "> $it".trimEnd() }); out.blank()
            }
        }
        "table" -> {
            out.blank()
            node.select("tr").forEachIndexed { i, tr ->
                val cells = tr.children().filter { it.normalName() == "th" || it.normalName() == "td" }
                if (cells.isEmpty()) return@forEachIndexed
                out.append("| ").append(cells.joinToString(" | ") { inline(it, depth).replace("|", "\\|") }).append(" |\n")
                if (i == 0) out.append("|").append(" --- |".repeat(cells.size)).append('\n')
            }
            out.blank()
        }
        "img" -> {}
        else -> for (c in node.childNodes()) walk(c, out, depth, pre)
    }
}
