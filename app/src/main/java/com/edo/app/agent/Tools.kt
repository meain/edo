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
        description = "Create a new file OR overwrite an existing one with the given content. Use this when replacing the entire file content; for partial edits prefer the 'edit_file' tool.",
        parametersJson = """{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"]}""",
    )

    override suspend fun invoke(argsJson: String): ToolResult {
        val args = parseArgs(argsJson)
        val path = args.str("path") ?: return ToolResult("missing 'path'", isError = true)
        val content = args.str("content") ?: return ToolResult("missing 'content'", isError = true)
        val existed = ws.read(path) != null
        val ok = ws.write(path, content)
        if (!ok) return ToolResult("failed to write $path", isError = true)
        val verb = if (existed) "overwrote" else "created"
        return ToolResult("$verb $path (${content.length} chars)")
    }
}

class EditFileTool(private val ws: Workspace) : Tool {
    override val spec = ToolSpec(
        name = "edit_file",
        description = "Replace exact text within an existing file. 'old_string' must match exactly (whitespace included). If 'replace_all' is false (default), 'old_string' must be unique within the file — otherwise the edit is rejected and you should include more surrounding context.",
        parametersJson = """{"type":"object","properties":{"path":{"type":"string"},"old_string":{"type":"string"},"new_string":{"type":"string"},"replace_all":{"type":"boolean"}},"required":["path","old_string","new_string"]}""",
    )

    override suspend fun invoke(argsJson: String): ToolResult {
        val args = parseArgs(argsJson)
        val path = args.str("path") ?: return ToolResult("missing 'path'", isError = true)
        val oldStr = args.str("old_string") ?: return ToolResult("missing 'old_string'", isError = true)
        val newStr = args.str("new_string") ?: return ToolResult("missing 'new_string'", isError = true)
        val replaceAll = args["replace_all"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        if (oldStr.isEmpty()) return ToolResult("'old_string' must be non-empty", isError = true)
        if (oldStr == newStr) return ToolResult("no-op: old_string equals new_string", isError = true)
        val current = ws.read(path) ?: return ToolResult("not found: $path", isError = true)
        val first = current.indexOf(oldStr)
        if (first < 0) return ToolResult("old_string not found in $path", isError = true)
        val second = current.indexOf(oldStr, first + 1)
        val updated: String
        val occ: Int
        if (replaceAll) {
            occ = occurrences(current, oldStr)
            updated = current.replace(oldStr, newStr)
        } else {
            if (second >= 0) {
                return ToolResult(
                    "old_string appears multiple times in $path; include more surrounding context or set replace_all=true",
                    isError = true,
                )
            }
            occ = 1
            updated = current.replaceRange(first, first + oldStr.length, newStr)
        }
        val ok = ws.write(path, updated)
        if (!ok) return ToolResult("failed to write $path", isError = true)
        return ToolResult("edited $path ($occ replacement${if (occ == 1) "" else "s"})")
    }

    private fun occurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var n = 0
        var idx = haystack.indexOf(needle)
        while (idx >= 0) {
            n++
            idx = haystack.indexOf(needle, idx + needle.length)
        }
        return n
    }
}

class DeleteFileTool(private val ws: Workspace) : Tool {
    override val spec = ToolSpec(
        name = "delete_file",
        description = "Delete a file (or empty/non-empty directory) from the workspace. Returns an error if the path does not exist or cannot be deleted.",
        parametersJson = """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""",
    )

    override suspend fun invoke(argsJson: String): ToolResult {
        val args = parseArgs(argsJson)
        val path = args.str("path") ?: return ToolResult("missing 'path'", isError = true)
        if (ws.read(path) == null && ws.ls(path) == null) {
            return ToolResult("not found: $path", isError = true)
        }
        val ok = ws.delete(path)
        if (!ok) return ToolResult("failed to delete $path", isError = true)
        return ToolResult("deleted $path")
    }
}

class CopyFileTool(private val ws: Workspace) : Tool {
    override val spec = ToolSpec(
        name = "copy_file",
        description = "Copy a file from 'source' to 'dest' inside the workspace. Overwrites 'dest' if it already exists. Use a fresh path under the workspace root for 'dest'.",
        parametersJson = """{"type":"object","properties":{"source":{"type":"string"},"dest":{"type":"string"}},"required":["source","dest"]}""",
    )

    override suspend fun invoke(argsJson: String): ToolResult {
        val args = parseArgs(argsJson)
        val source = args.str("source") ?: return ToolResult("missing 'source'", isError = true)
        val dest = args.str("dest") ?: return ToolResult("missing 'dest'", isError = true)
        if (source == dest) return ToolResult("source and dest are the same path", isError = true)
        if (ws.read(source) == null) return ToolResult("not found: $source", isError = true)
        val ok = ws.copy(source, dest)
        if (!ok) return ToolResult("failed to copy $source → $dest", isError = true)
        return ToolResult("copied $source → $dest")
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

class DateTimeTool(private val clock: () -> Long = System::currentTimeMillis) : Tool {
    override val spec = ToolSpec(
        name = "current_datetime",
        description = "Get the current date and time. Returns ISO 8601 formatted local time plus a UTC timestamp, the device timezone, and milliseconds since epoch.",
        parametersJson = """{"type":"object","properties":{}}""",
    )

    override suspend fun invoke(argsJson: String): ToolResult {
        val nowMs = clock()
        val zone = java.time.ZoneId.systemDefault()
        val instant = java.time.Instant.ofEpochMilli(nowMs)
        val local = instant.atZone(zone)
        val out = buildString {
            append("local: ").append(local.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)).append('\n')
            append("utc:   ").append(instant.toString()).append('\n')
            append("zone:  ").append(zone.id).append('\n')
            append("epoch: ").append(nowMs)
        }
        return ToolResult(out)
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
