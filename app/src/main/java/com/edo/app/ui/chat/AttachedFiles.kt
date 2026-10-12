package com.edo.app.ui.chat

/**
 * Text files attached to a message are sent to the model inline, wrapped in an
 * `<attached_file>` tag. The UI recognises the tag so the bubble can show a file
 * chip instead of dumping the file's contents.
 */
internal data class AttachedFile(val name: String, val chars: Int)

internal fun attachedFileBlock(name: String, content: String): String =
    "<attached_file name=\"${name.replace('"', '\'')}\">\n$content\n</attached_file>\n\n"

private val ATTACHED_FILE = Regex(
    """\A\s*<attached_file name="([^"]*)">\n(.*?)\n</attached_file>\n*""",
    RegexOption.DOT_MATCHES_ALL,
)

/** Splits leading attached-file blocks off [text]; returns the files and the remaining message. */
internal fun splitAttachedFiles(text: String): Pair<List<AttachedFile>, String> {
    val files = mutableListOf<AttachedFile>()
    var rest = text
    while (true) {
        val m = ATTACHED_FILE.find(rest) ?: break
        files.add(AttachedFile(m.groupValues[1], m.groupValues[2].length))
        rest = rest.substring(m.range.last + 1)
    }
    return files to rest.trim()
}
