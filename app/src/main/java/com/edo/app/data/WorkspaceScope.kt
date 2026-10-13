package com.edo.app.data

import java.net.URLDecoder

/**
 * Helpers for keeping each project confined to its own folder. The OS-level boundary is the
 * per-folder SAF grant (the app holds no broad storage permission); these cover the gaps that
 * remain inside the app, where all projects' grants belong to the same process.
 */
object WorkspaceScope {

    /** Document id of a SAF tree URI (`primary:Edo/Bubbly`), or null for anything else. */
    fun treeDocumentId(uri: String): String? {
        val marker = "/tree/"
        val start = uri.indexOf(marker).takeIf { it >= 0 } ?: return null
        val encoded = uri.substring(start + marker.length).substringBefore('/')
        return runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrNull()?.trimEnd('/')
    }

    /**
     * True when one folder is the same as, inside, or above the other. Such a pair would let a
     * project's agent reach another project's files, so it is refused.
     */
    fun overlaps(a: String, b: String): Boolean {
        val x = treeDocumentId(a) ?: return a == b
        val y = treeDocumentId(b) ?: return a == b
        return x == y || x.startsWith("$y/") || y.startsWith("$x/")
    }

    /** The first of [others] that [candidate] overlaps, if any. */
    fun conflict(candidate: String, others: List<ProjectEntity>): ProjectEntity? =
        others.firstOrNull { overlaps(candidate, it.workspaceUri) }

    /** Rejects `.`/`..` segments and empty paths so a tool path can't climb out of the root. */
    fun safeSegments(path: String): List<String>? {
        val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
        return if (parts.any { it == "." || it == ".." }) null else parts
    }
}
