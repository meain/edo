package com.edo.app.agent

import com.edo.app.llm.ToolSpec
import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.ContentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal val ToolJson = Json { ignoreUnknownKeys = true; isLenient = true }

private fun parseArgs(argsJson: String): JsonObject =
    runCatching { ToolJson.parseToJsonElement(argsJson).jsonObject }.getOrElse { JsonObject(emptyMap()) }

private fun JsonObject.str(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull

private const val MAX_BYTES = 256 * 1024

class ReadFileTool(private val ws: Workspace) : Tool {
    override val spec = ToolSpec(
        name = "read_file",
        description = "Read a text file under the workspace.",
        parametersJson = """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""",
    )

    override suspend fun invoke(argsJson: String): ToolResult {
        val args = parseArgs(argsJson)
        val path = args.str("path") ?: return ToolResult("missing 'path'", isError = true)
        val text = ws.read(path) ?: return ToolResult("not found: $path", isError = true)
        val out = if (text.length > MAX_BYTES) text.substring(0, MAX_BYTES) + "\n…[truncated]" else text
        return ToolResult(out)
    }
}

class WriteFileTool(private val ws: Workspace) : Tool {
    override val spec = ToolSpec(
        name = "write_file",
        description = "Write or overwrite a text file under the workspace.",
        parametersJson = """{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"]}""",
    )

    override suspend fun invoke(argsJson: String): ToolResult {
        val args = parseArgs(argsJson)
        val path = args.str("path") ?: return ToolResult("missing 'path'", isError = true)
        val content = args.str("content") ?: return ToolResult("missing 'content'", isError = true)
        val ok = ws.write(path, content)
        return if (ok) ToolResult("wrote ${content.length} chars to $path")
        else ToolResult("failed to write $path", isError = true)
    }
}

class LsTool(private val ws: Workspace) : Tool {
    override val spec = ToolSpec(
        name = "ls",
        description = "List directory contents under the workspace.",
        parametersJson = """{"type":"object","properties":{"path":{"type":"string"}}}""",
    )

    override suspend fun invoke(argsJson: String): ToolResult {
        val args = parseArgs(argsJson)
        val path = args.str("path") ?: ""
        val entries = ws.ls(path) ?: return ToolResult("not a directory: $path", isError = true)
        if (entries.isEmpty()) return ToolResult("(empty)")
        val sorted = entries.sortedWith(compareBy({ !it.isDir }, { it.name }))
        val lines = sorted.joinToString("\n") { e ->
            if (e.isDir) "${e.name}/" else "${e.name}  (${e.size} bytes)"
        }
        return ToolResult(lines)
    }
}

class GrepTool(private val ws: Workspace) : Tool {
    override val spec = ToolSpec(
        name = "grep",
        description = "Regex search across text files under the given path.",
        parametersJson = """{"type":"object","properties":{"pattern":{"type":"string"},"path":{"type":"string"}},"required":["pattern"]}""",
    )

    override suspend fun invoke(argsJson: String): ToolResult {
        val args = parseArgs(argsJson)
        val pattern = args.str("pattern") ?: return ToolResult("missing 'pattern'", isError = true)
        val path = args.str("path") ?: ""
        val regex = runCatching { Regex(pattern) }.getOrElse {
            return ToolResult("invalid regex: ${it.message}", isError = true)
        }
        val out = StringBuilder()
        var matches = 0
        for (file in ws.walkTextFiles(path)) {
            val lines = file.content.lineSequence()
            for ((idx, line) in lines.withIndex()) {
                if (regex.containsMatchIn(line)) {
                    out.append(file.path).append(':').append(idx + 1).append(": ").append(line).append('\n')
                    matches++
                    if (matches >= 500) {
                        out.append("…[truncated at 500 matches]\n")
                        return ToolResult(out.toString())
                    }
                }
            }
        }
        if (matches == 0) return ToolResult("(no matches)")
        return ToolResult(out.toString())
    }
}

/** HTTP fetcher abstraction so we can unit-test without making real requests. */
interface HttpFetcher {
    suspend fun fetch(url: String, method: String, headers: Map<String, String>, body: String?): String
}

class HttpRequestTool(private val fetcher: HttpFetcher) : Tool {
    override val spec = ToolSpec(
        name = "http_request",
        description = "Make an HTTP request and return the body.",
        parametersJson = """{"type":"object","properties":{"url":{"type":"string"},"method":{"type":"string"},"headers":{"type":"object"},"body":{"type":"string"}},"required":["url"]}""",
    )

    override suspend fun invoke(argsJson: String): ToolResult {
        val args = parseArgs(argsJson)
        val url = args.str("url") ?: return ToolResult("missing 'url'", isError = true)
        val method = args.str("method")?.uppercase() ?: "GET"
        val headers = (args["headers"] as? JsonObject)
            ?.entries
            ?.mapNotNull { (k, v) -> v.jsonPrimitive.contentOrNull?.let { k to it } }
            ?.toMap()
            ?: emptyMap()
        val body = args.str("body")
        return try {
            val out = fetcher.fetch(url, method, headers, body)
            ToolResult(if (out.length > MAX_BYTES) out.substring(0, MAX_BYTES) + "\n…[truncated]" else out)
        } catch (t: Throwable) {
            ToolResult("http error: ${t.message}", isError = true)
        }
    }
}

class KtorHttpFetcher(private val http: HttpClient) : HttpFetcher {
    override suspend fun fetch(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
    ): String {
        val response = http.request(url) {
            this.method = HttpMethod.parse(method)
            headers {
                for ((k, v) in headers) append(k, v)
            }
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        val text = response.bodyAsText()
        return "HTTP ${response.status.value}\n$text"
    }
}
