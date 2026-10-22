package com.edo.app.ui.threads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
    val toolCalls: Int,
    val topTools: List<Pair<String, Int>>,
    val models: List<String>,
    val cacheReadTokens: Long,
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
                toolCalls = tools.values.sum(),
                topTools = tools.entries.sortedByDescending { it.value }.take(4).map { it.key to it.value },
                models = events.map { it.model }.filter { it.isNotBlank() }.distinct(),
                cacheReadTokens = events.sumOf { it.cacheReadTokens },
            )
        }
    }
    val usage = thread.usage()
    val dates = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(thread.title, maxLines = 2) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Fact("Started", dates.format(Date(thread.createdAt)))
                Fact("Last active", dates.format(Date(thread.lastUpdatedAt)))
                if (thread.fromSchedule) Fact("Origin", "Scheduled run")
                facts?.let { f ->
                    Fact("Messages", "${f.userMessages} yours · ${f.replies} replies")
                    Fact("Model calls", f.llmCalls.toString())
                    Fact("Tool calls", f.toolCalls.toString())
                    if (f.topTools.isNotEmpty()) Fact("Top tools", f.topTools.joinToString(", ") { "${it.first} ${it.second}" })
                    if (f.models.isNotEmpty()) Fact("Models", f.models.joinToString(", "))
                }
                Fact("Tokens", "${formatTokens(usage.inputTokens)} in · ${formatTokens(usage.outputTokens)} out")
                facts?.takeIf { it.cacheReadTokens > 0 }?.let { Fact("Cache reads", formatTokens(it.cacheReadTokens)) }
                Fact("Cost", usage.costLabel() ?: "–")
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
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}
