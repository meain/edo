package com.edo.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One block of text in the details view: a tool argument, the raw input, or the output. */
internal class ToolSection(
    val title: String,
    val text: String,
    val lua: Boolean = false,
    val isOutput: Boolean = false,
    val isError: Boolean = false,
)

private val PRETTY = Json { prettyPrint = true }
private val LENIENT = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Splits a tool call into display sections, in full: one per argument, then the output. String
 * arguments show as written; anything else as pretty JSON. Lua is flagged for highlighting: the
 * `code` of run_lua, and file content written to a `.lua` path.
 */
internal fun toolSections(name: String, argsJson: String, result: String?, isError: Boolean): List<ToolSection> {
    val args = runCatching { LENIENT.parseToJsonElement(argsJson) as? JsonObject }.getOrNull()
    val input = when {
        args == null -> if (argsJson.isBlank()) emptyList() else listOf(ToolSection("Input", argsJson))
        else -> {
            val path = (args["path"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            args.map { (key, value) ->
                val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: prettyValue(value.toString())
                ToolSection(key, text, lua = (name == "run_lua" && key == "code") || (key == "content" && path.endsWith(".lua")))
            }
        }
    }
    val output = if (result != null) listOf(ToolSection(if (isError) "Error" else "Output", result, isOutput = true, isError = isError)) else emptyList()
    return input + output
}

/** Re-indents a JSON value; falls back to the text as it was. */
private fun prettyValue(raw: String): String = runCatching {
    PRETTY.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), Json.parseToJsonElement(raw))
}.getOrDefault(raw)

private sealed interface Line {
    data class Heading(val text: String) : Line
    data class Title(val text: String, val copy: String) : Line
    data class Chunk(val text: AnnotatedString, val isError: Boolean) : Line
    data class Note(val text: String) : Line
}

/** Long texts are cut into blocks of lines so no single Text has to lay out a huge string. */
private const val LINES_PER_CHUNK = 60

private fun chunks(text: AnnotatedString): List<AnnotatedString> {
    val plain = text.text
    val out = ArrayList<AnnotatedString>()
    var start = 0
    var lines = 0
    for (i in plain.indices) {
        if (plain[i] == '\n' && ++lines == LINES_PER_CHUNK) {
            out += text.subSequence(start, i)
            start = i + 1
            lines = 0
        }
    }
    out += text.subSequence(start, plain.length)
    return out
}

@Composable
private fun luaPalette() = with(MaterialTheme.colorScheme) {
    LuaPalette(
        keyword = primary, string = tertiary, comment = outline,
        number = secondary, builtin = secondary, functionName = primary,
    )
}

/**
 * Near-full-screen view of one tool call: every argument and the whole output, selectable and
 * copyable, with Lua highlighted. Works for any tool.
 */
@Composable
fun ToolDetailsDialog(name: String, argsJson: String, result: String?, isError: Boolean, onDismiss: () -> Unit) {
    val palette = luaPalette()
    val clipboard = LocalClipboardManager.current
    val rows = remember(name, argsJson, result, isError, palette) {
        val (output, inputs) = toolSections(name, argsJson, result, isError).partition { it.isOutput }
        buildList<Line> {
            add(Line.Heading("Input"))
            if (inputs.isEmpty()) add(Line.Note("No arguments."))
            for (s in inputs) {
                add(Line.Title(s.title, s.text))
                val styled = if (s.lua) highlightLua(s.text, palette) else AnnotatedString(s.text)
                chunks(styled).forEach { add(Line.Chunk(it, false)) }
            }
            add(Line.Heading(if (isError) "Error" else "Output"))
            if (output.isEmpty()) add(Line.Note("No output yet."))
            for (s in output) {
                add(Line.Title(if (s.text.isEmpty()) "(empty)" else "${s.text.length} chars", s.text))
                chunks(AnnotatedString(s.text)).forEach { add(Line.Chunk(it, s.isError)) }
            }
        }
    }
    val (label, detail) = toolHeader(name, argsJson)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 24.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(iconForTool(name, isError), null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text(label, style = MaterialTheme.typography.titleMedium)
                    if (detail.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            detail,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                }
                HorizontalDivider()
                SelectionContainer {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        items(rows) { row ->
                            when (row) {
                                is Line.Heading -> Text(
                                    row.text,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (row.text == "Error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                                )
                                is Line.Note -> Text(
                                    row.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                is Line.Title -> Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        row.text,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = { clipboard.setText(AnnotatedString(row.copy)) }) {
                                        Icon(Icons.Filled.ContentCopy, null, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Copy", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                is Line.Chunk -> Text(
                                    row.text,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (row.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
