package com.edo.app.agent

import com.edo.app.llm.ToolSpec
import kotlin.math.*
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.luaj.vm2.Globals
import org.luaj.vm2.LoadState
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.lib.BaseLib
import org.luaj.vm2.lib.Bit32Lib
import org.luaj.vm2.lib.CoroutineLib
import org.luaj.vm2.lib.PackageLib
import org.luaj.vm2.lib.StringLib
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.TableLib
import org.luaj.vm2.lib.TwoArgFunction
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.jse.JseMathLib
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal val ToolJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** Returns the parsed args object, or null if the JSON could not be parsed. */
internal fun parseArgs(argsJson: String): JsonObject? =
    runCatching { ToolJson.parseToJsonElement(argsJson).jsonObject }.getOrNull()

/** Build a precise tool error explaining that args JSON was malformed,
 *  so the model can self-correct on the next turn. */
internal fun argsParseError(argsJson: String): ToolResult {
    val snippet = argsJson.take(400)
    val suffix = if (argsJson.length > 400) "…[truncated]" else ""
    val reason = runCatching { ToolJson.parseToJsonElement(argsJson) }
        .exceptionOrNull()?.message
        ?.lineSequence()?.firstOrNull()
        ?: "invalid JSON"
    return ToolResult(
        "invalid tool arguments JSON: $reason. " +
            "Likely cause: unescaped quotes, backslashes, or control characters " +
            "inside a string value (e.g. raw HTML/code). Re-emit the call with " +
            "the string properly JSON-escaped. Received bytes (${argsJson.length}): " +
            "$snippet$suffix",
        isError = true,
    )
}

private fun JsonObject.str(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.int(key: String): Int? =
    this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

private const val MAX_BYTES = 256 * 1024

private const val MAX_LISTED = 500

/**
 * Converts a glob to a regex over workspace-relative paths. `**` crosses
 * directories, `*` and `?` stay within one segment, `{a,b}` is alternation.
 * A pattern without a `/` matches the file name at any depth.
 */
internal fun globToRegex(glob: String): Regex {
    val sb = StringBuilder()
    if (!glob.contains('/')) sb.append("(?:.*/)?")
    var i = 0
    var inGroup = false
    while (i < glob.length) {
        val c = glob[i]
        when {
            c == '*' && glob.startsWith("**/", i) -> { sb.append("(?:.*/)?"); i += 2 }
            c == '*' && glob.startsWith("**", i) -> { sb.append(".*"); i += 1 }
            c == '*' -> sb.append("[^/]*")
            c == '?' -> sb.append("[^/]")
            c == '{' -> { sb.append("(?:"); inGroup = true }
            c == '}' && inGroup -> { sb.append(")"); inGroup = false }
            c == ',' && inGroup -> sb.append("|")
            else -> sb.append(Regex.escape(c.toString()))
        }
        i++
    }
    return Regex(sb.toString())
}

class ReadTool(private val ws: Workspace) : Tool {
    override val spec = ToolSpec(
        name = "read",
        description = "Read a path under the workspace. A file returns its text; 'offset' (1-based line) and 'limit' (lines) read a slice of a large file. A directory lists its entries; add 'pattern' (a glob such as '**/*.md' or '*.kt') to search the whole tree below it for matching files instead.",
        parametersJson = """{"type":"object","properties":{"path":{"type":"string"},"pattern":{"type":"string"},"offset":{"type":"integer"},"limit":{"type":"integer"}}}""",
    )

    override suspend fun invoke(argsJson: String): ToolResult {
        val args = parseArgs(argsJson) ?: return argsParseError(argsJson)
        val path = args.str("path") ?: ""
        val pattern = args.str("pattern")?.takeIf { it.isNotBlank() }
        val entries = ws.ls(path)
        if (entries != null) return if (pattern != null) glob(path, pattern) else list(entries)
        if (path.isBlank()) return ToolResult("not a directory: $path", isError = true)
        if (pattern != null) return ToolResult("'pattern' only applies to directories: $path", isError = true)
        val text = ws.read(path) ?: return ToolResult("not found: $path", isError = true)
        return ToolResult(slice(text, args.int("offset"), args.int("limit")))
    }

    private fun list(entries: List<Workspace.Entry>): ToolResult {
        if (entries.isEmpty()) return ToolResult("(empty)")
        val sorted = entries.sortedWith(compareBy({ !it.isDir }, { it.name }))
        return ToolResult(
            sorted.joinToString("\n") { e ->
                if (e.isDir) "${e.name}/" else "${e.name}  (${e.size} bytes)"
            },
        )
    }

    private fun glob(path: String, pattern: String): ToolResult {
        val regex = runCatching { globToRegex(pattern) }.getOrElse {
            return ToolResult("invalid pattern: ${it.message}", isError = true)
        }
        val hits = ws.walk(path).filter { !it.isDir && regex.matches(it.name) }.take(MAX_LISTED + 1).toList()
        if (hits.isEmpty()) return ToolResult("(no matches)")
        val shown = hits.take(MAX_LISTED).sortedBy { it.name }
        val out = shown.joinToString("\n") { "${it.name}  (${it.size} bytes)" }
        return ToolResult(if (hits.size > MAX_LISTED) "$out\n…[truncated at $MAX_LISTED matches]" else out)
    }

    private fun slice(text: String, offset: Int?, limit: Int?): String {
        var body = text
        var note = ""
        if (offset != null || limit != null) {
            val lines = text.lines()
            val from = ((offset ?: 1) - 1).coerceIn(0, lines.size)
            val to = if (limit != null) (from + limit).coerceIn(from, lines.size) else lines.size
            body = lines.subList(from, to).joinToString("\n")
            note = "[lines ${from + 1}-$to of ${lines.size}]\n"
        }
        if (body.length <= MAX_BYTES) return note + body
        return note + body.substring(0, MAX_BYTES) + "\n…[truncated; use offset and limit to read the rest]"
    }
}

class WriteFileTool(private val ws: Workspace) : Tool {
    override val spec = ToolSpec(
        name = "write_file",
        description = "Create a new file OR overwrite an existing one with the given content. Use this when replacing the entire file content; for partial edits prefer the 'edit_file' tool.",
        parametersJson = """{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"]}""",
    )

    override suspend fun invoke(argsJson: String): ToolResult {
        val args = parseArgs(argsJson) ?: return argsParseError(argsJson)
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
        val args = parseArgs(argsJson) ?: return argsParseError(argsJson)
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
        val args = parseArgs(argsJson) ?: return argsParseError(argsJson)
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
        val args = parseArgs(argsJson) ?: return argsParseError(argsJson)
        val source = args.str("source") ?: return ToolResult("missing 'source'", isError = true)
        val dest = args.str("dest") ?: return ToolResult("missing 'dest'", isError = true)
        if (source == dest) return ToolResult("source and dest are the same path", isError = true)
        if (ws.read(source) == null) return ToolResult("not found: $source", isError = true)
        val ok = ws.copy(source, dest)
        if (!ok) return ToolResult("failed to copy $source → $dest", isError = true)
        return ToolResult("copied $source → $dest")
    }
}

class GrepTool(private val ws: Workspace) : Tool {
    override val spec = ToolSpec(
        name = "grep",
        description = "Regex search across text files under the given path.",
        parametersJson = """{"type":"object","properties":{"pattern":{"type":"string"},"path":{"type":"string"}},"required":["pattern"]}""",
    )

    override suspend fun invoke(argsJson: String): ToolResult {
        val args = parseArgs(argsJson) ?: return argsParseError(argsJson)
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
        description = "Get the current date and time. Returns ISO 8601 local time, UTC, timezone, epoch ms, day of week, week number, day of year, and quarter.",
        parametersJson = """{"type":"object","properties":{}}""",
    )

    override suspend fun invoke(argsJson: String): ToolResult {
        val nowMs = clock()
        val zone = java.time.ZoneId.systemDefault()
        val instant = java.time.Instant.ofEpochMilli(nowMs)
        val local = instant.atZone(zone)
        val weekFields = java.time.temporal.WeekFields.ISO
        val out = buildString {
            append("local:       ").append(local.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)).append('\n')
            append("utc:         ").append(instant.toString()).append('\n')
            append("zone:        ").append(zone.id).append('\n')
            append("epoch:       ").append(nowMs).append('\n')
            append("day_of_week: ").append(local.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())).append('\n')
            append("week_number: ").append(local.get(weekFields.weekOfWeekBasedYear())).append('\n')
            append("day_of_year: ").append(local.dayOfYear).append('\n')
            append("quarter:     Q").append((local.monthValue - 1) / 3 + 1)
        }
        return ToolResult(out)
    }
}

class AskUserQuestionTool(
    /** Returns the user's answer, or null if they dismissed the question. */
    private val ask: suspend (question: String, options: List<String>) -> String?,
) : Tool {
    override val spec = ToolSpec(
        name = "ask_user",
        description = "Ask the user a question with a list of predefined options. The user can also type a custom answer. Use this when you need clarification and the likely answers are a known set.",
        parametersJson = """{"type":"object","properties":{"question":{"type":"string","description":"The question to present to the user"},"options":{"type":"array","items":{"type":"string"},"description":"Predefined options for the user to pick from"}},"required":["question","options"]}""",
    )

    override suspend fun invoke(argsJson: String): ToolResult {
        val args = parseArgs(argsJson) ?: return argsParseError(argsJson)
        val question = args.str("question") ?: return ToolResult("missing 'question'", isError = true)
        val optionsEl = args["options"] ?: return ToolResult("missing 'options'", isError = true)
        val options = runCatching {
            optionsEl.jsonArray.map { it.jsonPrimitive.content }
        }.getOrElse { return ToolResult("'options' must be an array of strings", isError = true) }
        if (options.isEmpty()) return ToolResult("'options' must not be empty", isError = true)
        val answer = ask(question, options)
            ?: return ToolResult(
                "The user dismissed the question without answering. Continue without it, make a reasonable " +
                    "assumption, or stop and say what you need.",
                isError = true,
            )
        return ToolResult(answer)
    }
}

/** HTTP fetcher abstraction so we can unit-test without making real requests. */
interface HttpFetcher {
    suspend fun fetch(url: String, method: String, headers: Map<String, String>, body: String?): String
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

class CalculatorTool : Tool {
    override val spec = ToolSpec(
        name = "calculator",
        description = "Evaluate a mathematical expression. Supports +, -, *, /, ^ (power), parentheses, and functions: sqrt, abs, floor, ceil, round, sin, cos, tan, log (base-10), ln (natural log), exp. Constants: pi, e.",
        parametersJson = """{"type":"object","properties":{"expression":{"type":"string","description":"The math expression to evaluate, e.g. '2 + 3 * 4' or 'sqrt(2) ^ 2'"}},"required":["expression"]}""",
    )

    override suspend fun invoke(argsJson: String): ToolResult {
        val args = parseArgs(argsJson) ?: return argsParseError(argsJson)
        val expression = args.str("expression") ?: return ToolResult("missing 'expression'", isError = true)
        return try {
            val result = MathEvaluator(expression.trim()).evaluate()
            val formatted = if (!result.isNaN() && !result.isInfinite() &&
                result == kotlin.math.floor(result) &&
                result >= Long.MIN_VALUE.toDouble() && result <= Long.MAX_VALUE.toDouble()
            ) result.toLong().toString() else result.toString()
            ToolResult("$expression = $formatted")
        } catch (e: Exception) {
            ToolResult("error: ${e.message}", isError = true)
        }
    }
}

/**
 * Sandboxed Lua, always on. Besides computation it can read, write and delete files through
 * `fs.*`, served by [ws] so paths get exactly the scoping of the file tools and can't leave this
 * project's folder. Network (`http.*`, via [fetcher]) is a per-project opt-in ([allowNetwork]).
 *
 * The Lua state is built from an allowlist of libraries; there is no `io`, `os`, `luajava` or
 * `debug`, so `fs` and `http` are the only ways out of the interpreter.
 */
class RunLuaTool(
    private val ws: Workspace,
    private val fetcher: HttpFetcher? = null,
    private val allowNetwork: Boolean = false,
) : Tool {
    private val network = allowNetwork && fetcher != null

    override val spec = ToolSpec(
        name = "run_lua",
        description = buildString {
            append("Execute a Lua 5.2 program and return its output. Use print() to produce output. ")
            append("Libraries: math, string, table, bit32, coroutine (no os, io or require). ")
            append("File access is limited to this project's workspace (paths relative to its root, ")
            append("cannot leave it): fs.read(path) -> string or nil, fs.ls(path) -> list of ")
            append("{name, dir, size} or nil, fs.exists(path) -> boolean, fs.write(path, content) -> boolean, ")
            append("fs.delete(path) -> boolean. ")
            if (network) {
                append("http.get(url), http.post(url, body) and http.request(method, url, body, headers) ")
                append("return \"HTTP <status>\\n<body>\" and raise an error on failure. ")
            } else {
                append("There is no network access. ")
            }
            append("Execution times out after 10 seconds.")
        },
        parametersJson = """{"type":"object","properties":{"code":{"type":"string","description":"Lua source code to execute"}},"required":["code"]}""",
    )

    private fun installFs(globals: Globals) {
        val fs = LuaTable()
        fs.set("read", object : OneArgFunction() {
            override fun call(path: LuaValue): LuaValue {
                val text = ws.read(path.checkjstring()) ?: return LuaValue.NIL
                return LuaValue.valueOf(if (text.length > MAX_BYTES) text.substring(0, MAX_BYTES) else text)
            }
        })
        fs.set("exists", object : OneArgFunction() {
            override fun call(path: LuaValue): LuaValue {
                val p = path.checkjstring()
                return LuaValue.valueOf(ws.read(p) != null || ws.ls(p) != null)
            }
        })
        fs.set("ls", object : OneArgFunction() {
            override fun call(path: LuaValue): LuaValue {
                val entries = ws.ls(path.optjstring("") ?: "") ?: return LuaValue.NIL
                val list = LuaTable()
                entries.forEachIndexed { i, e ->
                    val row = LuaTable()
                    row.set("name", LuaValue.valueOf(e.name))
                    row.set("dir", LuaValue.valueOf(e.isDir))
                    row.set("size", LuaValue.valueOf(e.size.toDouble()))
                    list.set(i + 1, row)
                }
                return list
            }
        })
        fs.set("write", object : TwoArgFunction() {
            override fun call(path: LuaValue, content: LuaValue): LuaValue =
                LuaValue.valueOf(ws.write(path.checkjstring(), content.checkjstring()))
        })
        fs.set("delete", object : OneArgFunction() {
            override fun call(path: LuaValue): LuaValue = LuaValue.valueOf(ws.delete(path.checkjstring()))
        })
        globals.set("fs", fs)
    }

    private fun installHttp(globals: Globals, fetcher: HttpFetcher) {
        fun request(method: String, url: String, body: String?, headers: Map<String, String>): LuaValue =
            try {
                LuaValue.valueOf(runBlocking { fetcher.fetch(url, method, headers, body) }.take(MAX_BYTES))
            } catch (t: Throwable) {
                throw LuaError("http error: ${t.message}")
            }
        val http = LuaTable()
        http.set("get", object : OneArgFunction() {
            override fun call(url: LuaValue): LuaValue = request("GET", url.checkjstring(), null, emptyMap())
        })
        http.set("post", object : TwoArgFunction() {
            override fun call(url: LuaValue, body: LuaValue): LuaValue =
                request("POST", url.checkjstring(), body.checkjstring(), emptyMap())
        })
        http.set("request", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val headers = mutableMapOf<String, String>()
                val table = args.arg(4)
                if (table.istable()) {
                    var k: LuaValue = LuaValue.NIL
                    while (true) {
                        val n = table.next(k)
                        k = n.arg1()
                        if (k.isnil()) break
                        headers[k.tojstring()] = n.arg(2).tojstring()
                    }
                }
                val body = args.arg(3).takeIf { !it.isnil() }?.tojstring()
                return request(args.checkjstring(1).uppercase(), args.checkjstring(2), body, headers)
            }
        })
        globals.set("http", http)
    }

    override suspend fun invoke(argsJson: String): ToolResult {
        val args = parseArgs(argsJson) ?: return argsParseError(argsJson)
        val code = args.str("code") ?: return ToolResult("missing 'code'", isError = true)

        return withContext(Dispatchers.IO) {
            val output = StringBuilder()
            var result: ToolResult? = null

            val thread = Thread {
                try {
                    // Allowlist, not JsePlatform.standardGlobals() minus a few names: that preset also
                    // loads `luajava` (a bridge to any Java class, i.e. the app's whole process,
                    // including every project's folder grant) and `debug`.
                    val globals = Globals()
                    globals.load(BaseLib())
                    // The other libs register themselves in package.loaded, so it must exist
                    // while they load; it is removed again below.
                    globals.load(PackageLib())
                    globals.load(TableLib())
                    globals.load(StringLib())
                    globals.load(Bit32Lib())
                    globals.load(CoroutineLib())
                    globals.load(JseMathLib())
                    LoadState.install(globals)
                    LuaC.install(globals)
                    // Module and file-loading entry points have no place in a sandbox.
                    globals.set("package", LuaValue.NIL)
                    globals.set("require", LuaValue.NIL)
                    globals.set("dofile", LuaValue.NIL)
                    globals.set("loadfile", LuaValue.NIL)
                    globals.set("print", object : VarArgFunction() {
                        override fun invoke(args: Varargs): Varargs {
                            if (output.length >= MAX_BYTES) return LuaValue.NONE
                            for (i in 1..args.narg()) {
                                if (i > 1) output.append('\t')
                                output.append(args.arg(i).tojstring())
                            }
                            output.append('\n')
                            return LuaValue.NONE
                        }
                    })
                    installFs(globals)
                    if (network) installHttp(globals, fetcher!!)
                    globals.load(code, "agent").call()
                    val out = output.toString().trimEnd()
                    result = ToolResult(if (out.isEmpty()) "(no output)" else out)
                } catch (e: Throwable) {
                    result = ToolResult("lua error: ${e.message}", isError = true)
                }
            }
            thread.isDaemon = true
            thread.start()
            thread.join(10_000L)
            if (thread.isAlive) {
                // Thread.stop() is unsupported on newer Android; the daemon thread is just abandoned.
                @Suppress("DEPRECATION")
                runCatching { thread.stop() }
                ToolResult("lua error: execution timed out after 10 seconds", isError = true)
            } else {
                result ?: ToolResult("lua error: unknown error", isError = true)
            }
        }
    }
}

private class MathEvaluator(input: String) {
    private val expr = input.replace("\\s+".toRegex(), "")
    private var pos = 0

    fun evaluate(): Double {
        val result = parseExpr()
        if (pos < expr.length) throw IllegalArgumentException("Unexpected character '${expr[pos]}' at position $pos")
        return result
    }

    private fun parseExpr(): Double {
        var result = parseTerm()
        while (pos < expr.length && (expr[pos] == '+' || expr[pos] == '-')) {
            val op = expr[pos++]
            result = if (op == '+') result + parseTerm() else result - parseTerm()
        }
        return result
    }

    private fun parseTerm(): Double {
        var result = parsePower()
        while (pos < expr.length && (expr[pos] == '*' || expr[pos] == '/')) {
            val op = expr[pos++]
            val rhs = parsePower()
            result = if (op == '/') {
                if (rhs == 0.0) throw ArithmeticException("division by zero")
                result / rhs
            } else result * rhs
        }
        return result
    }

    private fun parsePower(): Double {
        val base = parseUnary()
        if (pos < expr.length && expr[pos] == '^') {
            pos++
            return base.pow(parsePower()) // right-associative
        }
        return base
    }

    private fun parseUnary(): Double {
        if (pos < expr.length && expr[pos] == '-') { pos++; return -parseUnary() }
        if (pos < expr.length && expr[pos] == '+') { pos++; return parseUnary() }
        return parseAtom()
    }

    private fun parseAtom(): Double {
        if (pos >= expr.length) throw IllegalArgumentException("Unexpected end of expression")
        if (expr[pos] == '(') {
            pos++
            val result = parseExpr()
            if (pos >= expr.length || expr[pos] != ')') throw IllegalArgumentException("Missing closing parenthesis")
            pos++
            return result
        }
        if (expr[pos].isLetter()) {
            val start = pos
            while (pos < expr.length && (expr[pos].isLetter() || expr[pos].isDigit())) pos++
            val name = expr.substring(start, pos)
            if (pos < expr.length && expr[pos] == '(') {
                pos++
                val arg = parseExpr()
                if (pos >= expr.length || expr[pos] != ')') throw IllegalArgumentException("Missing ')' after $name(")
                pos++
                return applyFunc(name, arg)
            }
            return when (name.lowercase()) {
                "pi" -> kotlin.math.PI
                "e" -> kotlin.math.E
                else -> throw IllegalArgumentException("Unknown identifier: $name")
            }
        }
        val start = pos
        while (pos < expr.length && (expr[pos].isDigit() || expr[pos] == '.')) pos++
        if (pos < expr.length && (expr[pos] == 'e' || expr[pos] == 'E')) {
            pos++
            if (pos < expr.length && (expr[pos] == '+' || expr[pos] == '-')) pos++
            while (pos < expr.length && expr[pos].isDigit()) pos++
        }
        if (pos == start) throw IllegalArgumentException("Expected number at position $pos")
        return expr.substring(start, pos).toDouble()
    }

    private fun applyFunc(name: String, arg: Double): Double = when (name.lowercase()) {
        "sqrt" -> kotlin.math.sqrt(arg)
        "abs" -> kotlin.math.abs(arg)
        "floor" -> kotlin.math.floor(arg)
        "ceil" -> kotlin.math.ceil(arg)
        "round" -> kotlin.math.round(arg).toDouble()
        "sin" -> kotlin.math.sin(arg)
        "cos" -> kotlin.math.cos(arg)
        "tan" -> kotlin.math.tan(arg)
        "log" -> kotlin.math.log10(arg)
        "ln" -> kotlin.math.ln(arg)
        "exp" -> kotlin.math.exp(arg)
        "sign" -> kotlin.math.sign(arg)
        else -> throw IllegalArgumentException("Unknown function: $name")
    }
}
