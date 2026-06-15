package com.edo.app.agent

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.OutputStream

/**
 * Abstraction over the user's workspace folder so tools can stay testable.
 * The Android implementation uses SAF/DocumentFile; tests use the in-memory one.
 */
interface Workspace {
    fun read(path: String): String?
    fun write(path: String, content: String): Boolean
    fun ls(path: String): List<Entry>?
    /** Walk relative paths under [path] for grep-style search. */
    fun walkTextFiles(path: String): Sequence<TextFile>

    data class Entry(val name: String, val isDir: Boolean, val size: Long)
    data class TextFile(val path: String, val content: String)
}

class SafWorkspace(
    private val context: Context,
    private val rootUri: Uri,
) : Workspace {

    private val root: DocumentFile? by lazy {
        DocumentFile.fromTreeUri(context, rootUri)
    }

    override fun read(path: String): String? {
        val file = resolve(path) ?: return null
        if (!file.isFile) return null
        val resolver = context.contentResolver
        return resolver.openInputStream(file.uri)?.use { it.bufferedReader().readText() }
    }

    override fun write(path: String, content: String): Boolean {
        val r = root ?: return false
        val (dir, name) = walkOrCreateDirs(r, path) ?: return false
        val target = dir.findFile(name)
            ?: dir.createFile(mimeFor(name), name)
            ?: return false
        val resolver = context.contentResolver
        return resolver.openOutputStream(target.uri, "wt")?.use { out: OutputStream ->
            out.write(content.toByteArray(Charsets.UTF_8))
            true
        } ?: false
    }

    private fun mimeFor(name: String): String {
        // Using "text/plain" makes SAF append a .txt extension when displayName has none.
        // application/octet-stream keeps the name verbatim for files like "Makefile".
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return "application/octet-stream"
        return when (name.substring(dot + 1).lowercase()) {
            "txt", "md", "markdown" -> "text/plain"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js", "ts", "tsx", "jsx" -> "text/javascript"
            else -> "application/octet-stream"
        }
    }

    override fun ls(path: String): List<Workspace.Entry>? {
        val dir = if (path.isBlank() || path == "/" || path == ".") root else resolve(path)
        if (dir == null || !dir.isDirectory) return null
        return dir.listFiles().map {
            Workspace.Entry(
                name = it.name ?: "?",
                isDir = it.isDirectory,
                size = if (it.isFile) it.length() else 0L,
            )
        }
    }

    override fun walkTextFiles(path: String): Sequence<Workspace.TextFile> = sequence {
        val start = if (path.isBlank() || path == "/" || path == ".") root else resolve(path)
        if (start == null) return@sequence
        val stack = ArrayDeque<Pair<DocumentFile, String>>()
        stack.addLast(start to "")
        val resolver = context.contentResolver
        while (stack.isNotEmpty()) {
            val (current, rel) = stack.removeLast()
            if (current.isDirectory) {
                for (child in current.listFiles()) {
                    val name = child.name ?: continue
                    val childRel = if (rel.isEmpty()) name else "$rel/$name"
                    stack.addLast(child to childRel)
                }
            } else if (current.isFile) {
                val text = runCatching {
                    resolver.openInputStream(current.uri)?.use { it.bufferedReader().readText() }
                }.getOrNull() ?: continue
                yield(Workspace.TextFile(rel, text))
            }
        }
    }

    private fun resolve(path: String): DocumentFile? {
        val r = root ?: return null
        if (path.isBlank() || path == "/" || path == ".") return r
        val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
        var cur: DocumentFile = r
        for (p in parts) {
            cur = cur.findFile(p) ?: return null
        }
        return cur
    }

    private fun walkOrCreateDirs(root: DocumentFile, path: String): Pair<DocumentFile, String>? {
        val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        var cur = root
        for (i in 0 until parts.size - 1) {
            val name = parts[i]
            val next = cur.findFile(name)
            cur = when {
                next != null && next.isDirectory -> next
                next == null -> cur.createDirectory(name) ?: return null
                else -> return null
            }
        }
        return cur to parts.last()
    }
}

/** File-system workspace using java.io.File — used for /sdcard/Edo/<project> paths. */
class FileWorkspace(private val root: java.io.File) : Workspace {

    override fun read(path: String): String? {
        val f = resolve(path) ?: return null
        return if (f.isFile) runCatching { f.readText() }.getOrNull() else null
    }

    override fun write(path: String, content: String): Boolean {
        val f = resolve(path) ?: return false
        f.parentFile?.mkdirs()
        return runCatching { f.writeText(content); true }.getOrElse { false }
    }

    override fun ls(path: String): List<Workspace.Entry>? {
        val dir = if (path.isBlank() || path == "/" || path == ".") root else resolve(path)
        if (dir == null || !dir.isDirectory) return null
        return dir.listFiles()?.map {
            Workspace.Entry(it.name, it.isDirectory, if (it.isFile) it.length() else 0L)
        } ?: emptyList()
    }

    override fun walkTextFiles(path: String): Sequence<Workspace.TextFile> = sequence {
        val start = if (path.isBlank() || path == "/" || path == ".") root else resolve(path)
        if (start == null || !start.exists()) return@sequence
        start.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(root).path
            val text = runCatching { f.readText() }.getOrNull() ?: return@forEach
            yield(Workspace.TextFile(rel, text))
        }
    }

    private fun resolve(path: String): java.io.File? {
        if (path.isBlank() || path == "/" || path == ".") return root
        val f = java.io.File(root, path.trim('/'))
        return if (f.canonicalPath.startsWith(root.canonicalPath)) f else null
    }
}

/** In-memory workspace for unit tests. */
class InMemoryWorkspace(initial: Map<String, String> = emptyMap()) : Workspace {
    private val files = HashMap(initial)

    override fun read(path: String): String? = files[normalize(path)]

    override fun write(path: String, content: String): Boolean {
        files[normalize(path)] = content
        return true
    }

    override fun ls(path: String): List<Workspace.Entry>? {
        val prefix = normalize(path).let { if (it.isEmpty()) "" else "$it/" }
        val direct = mutableMapOf<String, Workspace.Entry>()
        for ((p, c) in files) {
            if (!p.startsWith(prefix) && prefix.isNotEmpty()) continue
            val rel = p.removePrefix(prefix)
            if (rel.isEmpty()) continue
            val slash = rel.indexOf('/')
            if (slash < 0) {
                direct[rel] = Workspace.Entry(rel, isDir = false, size = c.toByteArray().size.toLong())
            } else {
                val dir = rel.substring(0, slash)
                direct.putIfAbsent(dir, Workspace.Entry(dir, isDir = true, size = 0))
            }
        }
        return direct.values.toList()
    }

    override fun walkTextFiles(path: String): Sequence<Workspace.TextFile> = sequence {
        val norm = normalize(path)
        val prefix = if (norm.isEmpty()) "" else "$norm/"
        for ((p, c) in files) {
            if (prefix.isEmpty() || p.startsWith(prefix)) {
                val rel = if (prefix.isEmpty()) p else p.removePrefix(prefix)
                yield(Workspace.TextFile(rel, c))
            }
        }
    }

    private fun normalize(path: String): String =
        path.trim().trim('/').replace(Regex("/+"), "/").ifEmpty { "" }
}
