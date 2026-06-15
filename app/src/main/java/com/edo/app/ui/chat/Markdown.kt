package com.edo.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Minimal markdown renderer for chat bubbles. */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(text) { parseMarkdown(text) }
    Column(modifier = modifier) {
        for ((i, block) in blocks.withIndex()) {
            if (i > 0) Spacer(Modifier.height(6.dp))
            RenderBlock(block)
        }
    }
}

internal sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class CodeBlock(val language: String?, val text: String) : MdBlock
    data class BulletList(val items: List<String>) : MdBlock
    data class NumberedList(val items: List<String>) : MdBlock
    data class Quote(val text: String) : MdBlock
    data object HorizontalRule : MdBlock
}

internal fun parseMarkdown(input: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val lines = input.split('\n')
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()

        // Code fence
        if (trimmed.startsWith("```")) {
            val lang = trimmed.removePrefix("```").trim().ifBlank { null }
            val body = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                if (body.isNotEmpty()) body.append('\n')
                body.append(lines[i])
                i++
            }
            if (i < lines.size) i++ // skip closing fence
            out.add(MdBlock.CodeBlock(lang, body.toString()))
            continue
        }

        // Heading
        if (trimmed.startsWith("#")) {
            val hashes = trimmed.takeWhile { it == '#' }
            val rest = trimmed.removePrefix(hashes).trim()
            if (hashes.length in 1..6 && (rest.isNotEmpty() || trimmed.length == hashes.length)) {
                out.add(MdBlock.Heading(hashes.length.coerceAtMost(6), rest))
                i++
                continue
            }
        }

        // Horizontal rule
        if (trimmed == "---" || trimmed == "***" || trimmed == "___") {
            out.add(MdBlock.HorizontalRule)
            i++
            continue
        }

        // Bullet list
        if (isBulletStart(trimmed)) {
            val items = mutableListOf<String>()
            while (i < lines.size && isBulletStart(lines[i].trimStart())) {
                val item = lines[i].trimStart().substring(2)
                items.add(item.trim())
                i++
            }
            out.add(MdBlock.BulletList(items))
            continue
        }

        // Numbered list
        if (numberedPrefix(trimmed) != null) {
            val items = mutableListOf<String>()
            while (i < lines.size && numberedPrefix(lines[i].trimStart()) != null) {
                val raw = lines[i].trimStart()
                items.add(raw.substringAfter(' ').trim())
                i++
            }
            out.add(MdBlock.NumberedList(items))
            continue
        }

        // Quote
        if (trimmed.startsWith("> ") || trimmed == ">") {
            val body = StringBuilder()
            while (i < lines.size && (lines[i].trimStart().startsWith(">"))) {
                if (body.isNotEmpty()) body.append('\n')
                body.append(lines[i].trimStart().removePrefix(">").trimStart())
                i++
            }
            out.add(MdBlock.Quote(body.toString()))
            continue
        }

        // Blank line
        if (line.isBlank()) { i++; continue }

        // Paragraph — accumulate consecutive non-empty, non-special lines
        val para = StringBuilder(line)
        i++
        while (i < lines.size) {
            val next = lines[i]
            val nt = next.trimStart()
            if (next.isBlank() ||
                nt.startsWith("#") || nt.startsWith("```") ||
                isBulletStart(nt) || numberedPrefix(nt) != null ||
                nt.startsWith("> ") || nt == ">"
            ) break
            para.append('\n').append(next)
            i++
        }
        out.add(MdBlock.Paragraph(para.toString()))
    }
    return out
}

private fun isBulletStart(s: String): Boolean =
    (s.startsWith("- ") || s.startsWith("* ") || s.startsWith("+ "))

private fun numberedPrefix(s: String): Int? {
    val dot = s.indexOf('.')
    if (dot <= 0) return null
    val prefix = s.substring(0, dot)
    if (prefix.any { !it.isDigit() }) return null
    if (dot + 1 >= s.length || s[dot + 1] != ' ') return null
    return prefix.toIntOrNull()
}

@Composable
private fun RenderBlock(block: MdBlock) {
    when (block) {
        is MdBlock.Heading -> {
            val style = when (block.level) {
                1 -> MaterialTheme.typography.titleLarge
                2 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            }
            Text(
                renderInline(block.text),
                style = style.copy(fontWeight = FontWeight.SemiBold),
            )
        }

        is MdBlock.Paragraph ->
            Text(
                renderInline(block.text),
                style = MaterialTheme.typography.bodyMedium,
            )

        is MdBlock.CodeBlock -> CodeBlockView(block.text)

        is MdBlock.BulletList -> Column {
            for (item in block.items) {
                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                    Text("•  ", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        renderInline(item),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        is MdBlock.NumberedList -> Column {
            for ((idx, item) in block.items.withIndex()) {
                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                    Text(
                        "${idx + 1}. ",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        renderInline(item),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        is MdBlock.Quote -> Row {
            Box(
                Modifier
                    .width(3.dp)
                    .height(20.dp)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
            )
            Spacer(Modifier.width(8.dp))
            Text(
                renderInline(block.text),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalContentColor.current.copy(alpha = 0.85f),
                fontStyle = FontStyle.Italic,
            )
        }

        MdBlock.HorizontalRule -> Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
    }
}

@Composable
private fun CodeBlockView(code: String) {
    val scrollState = rememberScrollState()
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            code,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            ),
            modifier = Modifier.horizontalScroll(scrollState),
        )
    }
}

@Composable
internal fun renderInline(text: String): AnnotatedString {
    val codeBg = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
    val linkColor = MaterialTheme.colorScheme.primary
    return remember(text, codeBg, linkColor) {
        parseInline(text, codeBg, linkColor)
    }
}

internal fun parseInline(text: String, codeBg: Color, linkColor: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            // Inline code: `...`
            c == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    val code = text.substring(i + 1, end)
                    withStyle(SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = codeBg,
                    )) { append(code) }
                    i = end + 1
                } else {
                    append(c); i++
                }
            }
            // Bold: **...**
            c == '*' && i + 1 < text.length && text[i + 1] == '*' -> {
                val end = text.indexOf("**", i + 2)
                if (end > i + 1) {
                    val inner = text.substring(i + 2, end)
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(parseInline(inner, codeBg, linkColor))
                    }
                    i = end + 2
                } else { append(c); i++ }
            }
            // Italic: *...*
            c == '*' -> {
                val end = text.indexOf('*', i + 1)
                if (end > i) {
                    val inner = text.substring(i + 1, end)
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(parseInline(inner, codeBg, linkColor))
                    }
                    i = end + 1
                } else { append(c); i++ }
            }
            // Underscore italic: _..._
            c == '_' && (i == 0 || !text[i - 1].isLetterOrDigit()) -> {
                val end = text.indexOf('_', i + 1)
                if (end > i && (end == text.length - 1 || !text[end + 1].isLetterOrDigit())) {
                    val inner = text.substring(i + 1, end)
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(parseInline(inner, codeBg, linkColor))
                    }
                    i = end + 1
                } else { append(c); i++ }
            }
            // Link: [text](url)
            c == '[' -> {
                val close = text.indexOf(']', i + 1)
                if (close > i && close + 1 < text.length && text[close + 1] == '(') {
                    val urlEnd = text.indexOf(')', close + 2)
                    if (urlEnd > close) {
                        val label = text.substring(i + 1, close)
                        val url = text.substring(close + 2, urlEnd)
                        val startIdx = length
                        withStyle(
                            SpanStyle(
                                color = linkColor,
                                textDecoration = TextDecoration.Underline,
                            )
                        ) {
                            append(parseInline(label, codeBg, linkColor))
                        }
                        addStringAnnotation(tag = "URL", annotation = url, start = startIdx, end = length)
                        i = urlEnd + 1
                        continue
                    }
                }
                append(c); i++
            }
            else -> { append(c); i++ }
        }
    }
}
