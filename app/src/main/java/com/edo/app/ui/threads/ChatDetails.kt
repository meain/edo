package com.edo.app.ui.threads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.edo.app.AppContainer
import com.edo.app.data.ThreadEntity
import com.edo.app.data.formatTokens
import com.edo.app.data.usage
import com.edo.app.ui.stats.TOOL_NAME
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private class ChatFacts(
    val userMessages: Int,
    val replies: Int,
    val llmCalls: Int,
    val tools: List<Pair<String, Int>>,
    val models: List<Pair<String, Int>>,
    val cacheReadTokens: Long,
    val contextTokens: Long,
)

/** Counts, usage and models for one chat, read from its messages and usage events. */
@Composable
fun ChatDetailsDialog(container: AppContainer, thread: ThreadEntity, onDismiss: () -> Unit) {
    var facts by remember(thread.id) { mutableStateOf<ChatFacts?>(null) }
    LaunchedEffect(thread.id) {
        facts = withContext(Dispatchers.IO) {
            val messages = container.db.messages().listForThread(thread.id)
            val events = container.db.usageEvents().listForThread(thread.id)
            val tools = HashMap<String, Int>()
            for (m in messages.filter { it.role == "Assistant" }) {
                for (hit in TOOL_NAME.findAll(m.contentJson)) tools.merge(hit.groupValues[1], 1, Int::plus)
            }
            ChatFacts(
                // Tool results are stored as user messages; only count the ones the user typed.
                userMessages = messages.count { it.role == "User" && !it.contentJson.contains("\"kind\":\"tool_result\"") },
                replies = messages.count { it.role == "Assistant" },
                llmCalls = events.size,
                tools = tools.entries.sortedByDescending { it.value }.map { it.key to it.value },
                models = events.filter { it.model.isNotBlank() }.groupingBy { it.model }.eachCount()
                    .entries.sortedByDescending { it.value }.map { it.key to it.value },
                cacheReadTokens = events.sumOf { it.cacheReadTokens },
                contextTokens = events.lastOrNull()?.inputTokens ?: 0,
            )
        }
    }
    val usage = thread.usage()
    val dates = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(thread.title, maxLines = 2) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // The numbers worth a glance, before the detail.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tile("Cost", usage.costLabel() ?: "–", Modifier.weight(1f))
                    Tile("Context", facts?.contextTokens?.takeIf { it > 0 }?.let { formatTokens(it) } ?: "–", Modifier.weight(1f))
                    Tile("Messages", facts?.let { (it.userMessages + it.replies).toString() } ?: "…", Modifier.weight(1f))
                }
                Group("Chat") {
                    Fact("Started", dates.format(Date(thread.createdAt)))
                    Fact("Last active", dates.format(Date(thread.lastUpdatedAt)))
                    if (thread.fromSchedule) Fact("Origin", "Scheduled run")
                }
                facts?.let { f ->
                    Group("Messages") {
                        Fact("From you", f.userMessages.toString())
                        Fact("Replies", f.replies.toString())
                        Fact("Model calls", f.llmCalls.toString())
                        Fact("Tool calls", f.tools.sumOf { it.second }.toString())
                    }
                }
                Group("Tokens") {
                    Fact("Sent (all calls)", formatTokens(usage.inputTokens))
                    Fact("Received", formatTokens(usage.outputTokens))
                    facts?.takeIf { it.cacheReadTokens > 0 }?.let { Fact("Of sent, from cache", formatTokens(it.cacheReadTokens)) }
                }
                facts?.takeIf { it.models.isNotEmpty() }?.let { f ->
                    Group("Models") { f.models.forEach { (name, n) -> Fact(name, "$n calls") } }
                }
                facts?.takeIf { it.tools.isNotEmpty() }?.let { f ->
                    Group("Tools") { f.tools.take(8).forEach { (name, n) -> Fact(name, "×$n") } }
                }
                if (facts?.llmCalls == 0 && !usage.isEmpty) {
                    Text(
                        "Per-call figures only exist for responses logged since usage logging began.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun Tile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f))
            Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 1)
        }
    }
}

/** A titled block of rows. */
@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        HorizontalDivider()
        content()
    }
}

/** Label on the left, value flush right so the figures line up in one column. */
@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
    }
}
