package com.edo.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListBlock
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import org.commonmark.node.Text as MdText

private val markdownParser: Parser = Parser.builder()
    .extensions(
        listOf(
            // Turns bare URLs / emails into Link nodes, so they render clickable like [text](url).
            AutolinkExtension.create(),
            TablesExtension.create(),
            StrikethroughExtension.create(),
            TaskListItemsExtension.create(),
        )
    )
    .build()

/** CommonMark + GFM (tables, strikethrough, task lists) renderer for chat messages. */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
) {
    val doc = remember(text) { markdownParser.parse(text) }
    Column(modifier = modifier) { RenderBlocks(doc, tight = false) }
}

private fun Node.children(): Sequence<Node> = generateSequence(firstChild) { it.next }

@Composable
private fun RenderBlocks(parent: Node, tight: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(if (tight) 2.dp else 8.dp)) {
        for (child in parent.children()) RenderBlock(child)
    }
}

@Composable
private fun RenderBlock(node: Node) {
    when (node) {
        is Heading -> {
            val style = when (node.level) {
                1 -> MaterialTheme.typography.headlineSmall
                2 -> MaterialTheme.typography.titleLarge
                3 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            }
            Text(
                rememberInline(node),
                style = style.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        is Paragraph -> Text(rememberInline(node), style = MaterialTheme.typography.bodyLarge)

        is FencedCodeBlock -> CodeBlockView(node.literal.trimEnd('\n'))
        is IndentedCodeBlock -> CodeBlockView(node.literal.trimEnd('\n'))

        is BulletList -> ListView(node, ordered = false, start = 1)
        is OrderedList -> ListView(node, ordered = true, start = node.markerStartNumber ?: 1)

        is BlockQuote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
            )
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalContentColor provides LocalContentColor.current.copy(alpha = 0.8f),
                ) { RenderBlocks(node, tight = false) }
            }
        }

        is ThematicBreak -> Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )

        is TableBlock -> TableView(node)

        is HtmlBlock -> Text(
            node.literal.trimEnd('\n'),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )

        else -> Unit
    }
}

@Composable
private fun ListView(list: ListBlock, ordered: Boolean, start: Int) {
    val tight = list.isTight
    Column(verticalArrangement = Arrangement.spacedBy(if (tight) 2.dp else 6.dp)) {
        var index = start
        for (item in list.children()) {
            val first = item.firstChild
            val isTask = first is TaskListItemMarker || first?.firstChild is TaskListItemMarker
            val marker = when {
                isTask -> "" // the checkbox glyph is rendered inline with the text
                ordered -> "$index."
                else -> "•"
            }
            Row {
                Text(
                    marker,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.width(if (ordered) 30.dp else if (isTask) 0.dp else 20.dp),
                )
                Box(Modifier.weight(1f)) { RenderBlocks(item, tight) }
            }
            index++
        }
    }
}

@Composable
private fun TableView(table: TableBlock) {
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val linkColor = MaterialTheme.colorScheme.primary
    val rows = remember(table, codeBg, linkColor) {
        val out = mutableListOf<List<TableCellData>>()
        for (section in table.children()) {
            for (row in section.children()) {
                out.add(
                    row.children().filterIsInstance<TableCell>().map { cell ->
                        TableCellData(
                            text = buildAnnotatedString { appendInlines(cell, codeBg, linkColor) },
                            header = cell.isHeader,
                            align = when (cell.alignment) {
                                TableCell.Alignment.CENTER -> TextAlign.Center
                                TableCell.Alignment.RIGHT -> TextAlign.End
                                else -> TextAlign.Start
                            },
                        )
                    }.toList()
                )
            }
        }
        out
    }
    val columnCount = rows.maxOfOrNull { it.size } ?: return
    // Approximate each column's width from its longest cell so wide tables scroll sideways.
    val widths: List<Dp> = List(columnCount) { c ->
        val longest = rows.maxOf { r ->
            r.getOrNull(c)?.text?.text?.lines()?.maxOf { it.length } ?: 0
        }
        (longest * 8 + 24).coerceIn(72, 260).dp
    }
    val shape = RoundedCornerShape(8.dp)
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, borderColor, shape)
            .horizontalScroll(rememberScrollState()),
    ) {
        Column {
            rows.forEachIndexed { r, cells ->
                val isHeader = cells.firstOrNull()?.header == true
                Row(
                    Modifier.background(
                        if (isHeader) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent
                    )
                ) {
                    for (c in 0 until columnCount) {
                        val cell = cells.getOrNull(c)
                        Text(
                            cell?.text ?: AnnotatedString(""),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isHeader) FontWeight.SemiBold else null,
                            textAlign = cell?.align ?: TextAlign.Start,
                            modifier = Modifier
                                .width(widths[c])
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                }
                if (r < rows.lastIndex) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(borderColor))
                }
            }
        }
    }
}

private data class TableCellData(val text: AnnotatedString, val header: Boolean, val align: TextAlign)

@Composable
private fun CodeBlockView(code: String) {
    val scrollState = rememberScrollState()
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 12.dp, vertical = 10.dp),
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
private fun rememberInline(parent: Node): AnnotatedString {
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val linkColor = MaterialTheme.colorScheme.primary
    return remember(parent, codeBg, linkColor) {
        buildAnnotatedString { appendInlines(parent, codeBg, linkColor) }
    }
}

private fun AnnotatedString.Builder.appendInlines(parent: Node, codeBg: Color, linkColor: Color) {
    for (child in parent.children()) appendInline(child, codeBg, linkColor)
}

private fun AnnotatedString.Builder.appendInline(node: Node, codeBg: Color, linkColor: Color) {
    when (node) {
        is MdText -> append(node.literal)
        is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) {
            append(node.literal)
        }
        is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
            appendInlines(node, codeBg, linkColor)
        }
        is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            appendInlines(node, codeBg, linkColor)
        }
        is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
            appendInlines(node, codeBg, linkColor)
        }
        is SoftLineBreak, is HardLineBreak -> append('\n')
        is Link -> withLink(linkAnnotation(node.destination, linkColor)) {
            appendInlines(node, codeBg, linkColor)
        }
        is Image -> withLink(linkAnnotation(node.destination, linkColor)) {
            append("🖼 ")
            appendInlines(node, codeBg, linkColor)
        }
        is HtmlInline -> append(node.literal)
        is TaskListItemMarker -> append(if (node.isChecked) "☑ " else "☐ ")
        else -> appendInlines(node, codeBg, linkColor)
    }
}

private fun linkAnnotation(url: String, color: Color) = LinkAnnotation.Url(
    url,
    TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline)),
)
