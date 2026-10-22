package com.edo.app.ui.threads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.edo.app.AppContainer
import com.edo.app.data.ThreadEntity
import com.edo.app.data.UsageTotals
import com.edo.app.data.formatTokens
import com.edo.app.data.usage
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Chat list; hosted as the left panel of the chat screen's swipe container. */
@Composable
fun ThreadsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val activeProjectId by container.activeProjectId.collectAsState()
    val activeThreadId by container.activeThreadId.collectAsState()
    var query by remember { mutableStateOf("") }
    var showOlder by remember { mutableStateOf(false) }
    val trimmedQuery = query.trim()
    val threadsFlow = remember(activeProjectId, trimmedQuery) {
        when {
            activeProjectId <= 0 -> flowOf(emptyList())
            trimmedQuery.isEmpty() -> container.db.threads().observeForProject(activeProjectId)
            else -> container.db.threads().searchForProject(activeProjectId, escapeLike(trimmedQuery))
        }
    }
    val threads by threadsFlow.collectAsState(initial = emptyList())
    // Unfiltered, so the project card's numbers don't change with the search box.
    val projectThreads by remember(activeProjectId) {
        if (activeProjectId > 0) container.db.threads().observeForProject(activeProjectId) else flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    var projectName by remember { mutableStateOf("") }
    val activeProjectIdState = activeProjectId
    androidx.compose.runtime.LaunchedEffect(activeProjectIdState) {
        projectName = if (activeProjectIdState > 0)
            container.db.projects().getById(activeProjectIdState)?.name ?: "Chats"
        else "Chats"
    }
    var confirmDelete by remember { mutableStateOf<ThreadEntity?>(null) }
    var pickingProject by remember { mutableStateOf(false) }
    var detailsFor by remember { mutableStateOf<ThreadEntity?>(null) }
    val allProjects by container.db.projects().observeAll().collectAsState(initial = emptyList())
    val focusManager = LocalFocusManager.current

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        // App header: name plus app-wide screens. Project things live below it.
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Edo",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onOpenStats) {
                Icon(Icons.Filled.BarChart, contentDescription = "Stats")
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        // Current project: name, chat count, settings.
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp)
                .clip(MaterialTheme.shapes.large)
                .clickable { pickingProject = true },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        projectName.ifBlank { "No project" },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (activeProjectId > 0) {
                        val usage = projectThreads.fold(UsageTotals()) { acc, t -> acc + t.usage() }
                        val weekAgo = System.currentTimeMillis() - 7 * 86_400_000L
                        val recent = projectThreads.count { it.lastUpdatedAt >= weekAgo }
                        Text(
                            buildString {
                                append(projectThreads.size).append(if (projectThreads.size == 1) " chat" else " chats")
                                if (recent != projectThreads.size) append(" · ").append(recent).append(" this week")
                                if (!usage.isEmpty) {
                                    append("\n").append(formatTokens(usage.inputTokens + usage.outputTokens)).append(" tokens")
                                    usage.costLabel()?.let { append(" · ").append(it) }
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }
        FilledTonalButton(
            onClick = {
                container.setActiveThread(-1L)
                onBack()
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        ) {
            Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("New chat")
        }
        Spacer(Modifier.height(8.dp))
        if (threads.isEmpty()) {
            Text(
                if (trimmedQuery.isEmpty()) "No chats yet. Tap New to start one."
                else "No chats match \"$trimmedQuery\".",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            )
            Spacer(Modifier.weight(1f))
        } else {
            // Newest-first data with a reversed layout: latest chat sits at the bottom, in thumb reach.
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                reverseLayout = true,
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                // Search shows everything; otherwise only the newest chats show and the rest
                // fold behind a header (threads arrive newest-first).
                val (recent, older) =
                    if (trimmedQuery.isEmpty()) threads.take(MAX_VISIBLE_CHATS) to threads.drop(MAX_VISIBLE_CHATS)
                    else threads to emptyList()
                val visible = if (showOlder) recent + older else recent
                // Reversed layout: a group's label goes after its rows so it lands above them.
                val groups = visible.groupBy { dayBucket(it.lastUpdatedAt) }
                for ((label, group) in groups) {
                    items(group, key = { it.id }) { thread ->
                        ThreadRow(
                            thread = thread,
                            isActive = thread.id == activeThreadId,
                            onSelect = {
                                container.setActiveThread(thread.id)
                                onBack()
                            },
                            onDetails = { detailsFor = thread },
                            onDelete = { confirmDelete = thread },
                        )
                    }
                    item(key = "group-$label") {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 6.dp),
                        )
                    }
                }
                // Reversed layout: the last item sits at the top, above the oldest chats.
                if (older.isNotEmpty()) {
                    item(key = "older-header") {
                        TextButton(
                            onClick = { showOlder = !showOlder },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                (if (showOlder) "Hide" else "Show") + " ${older.size} older chat" +
                                    if (older.size == 1) "" else "s",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
        }
        // At the bottom, next to the newest chats and in thumb reach. Searches titles and messages.
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search chats") },
            leadingIcon = { Icon(Icons.Filled.Search, null, modifier = Modifier.size(20.dp)) },
            trailingIcon = if (query.isNotEmpty()) {
                {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search", modifier = Modifier.size(18.dp))
                    }
                }
            } else null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            shape = MaterialTheme.shapes.large,
            textStyle = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth().imePadding().padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }

    if (pickingProject) {
        AlertDialog(
            onDismissRequest = { pickingProject = false },
            title = { Text("Switch project") },
            text = {
                LazyColumn {
                    items(allProjects) { p ->
                        Text(
                            p.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (p.id == activeProjectId) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth()
                                .clickable {
                                    pickingProject = false
                                    if (p.id != activeProjectId) container.setActiveProject(p.id)
                                }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickingProject = false }) { Text("Close") } },
        )
    }

    detailsFor?.let { thread ->
        ChatDetailsDialog(container, thread, onDismiss = { detailsFor = null })
    }

    confirmDelete?.let { thread ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete chat?") },
            text = { Text("\"${thread.title}\" and all its messages will be deleted permanently.") },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            container.chatSessions.discard(thread.id)
                            container.db.messages().clearThread(thread.id)
                            container.db.threads().delete(thread)
                            if (activeThreadId == thread.id) container.setActiveThread(-1L)
                        }
                        confirmDelete = null
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ThreadRow(
    thread: ThreadEntity,
    isActive: Boolean,
    onSelect: () -> Unit,
    onDetails: () -> Unit,
    onDelete: () -> Unit,
) {
    val bg = if (isActive) MaterialTheme.colorScheme.secondaryContainer
    else MaterialTheme.colorScheme.surface
    Surface(
        color = bg,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp),
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onSelect)
                .padding(start = 12.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val scheduled = thread.fromSchedule
            Icon(
                if (scheduled) Icons.Filled.Alarm else Icons.AutoMirrored.Filled.Chat,
                contentDescription = if (scheduled) "Scheduled chat" else null,
                modifier = Modifier.size(18.dp),
                tint = if (isActive) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = thread.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = relativeTime(thread.lastUpdatedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = onDetails, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = "Chat details",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

private const val MAX_VISIBLE_CHATS = 15

/** Group label for the chat list: Today, Yesterday, This week, or Earlier. */
private fun dayBucket(ts: Long): String {
    val cal = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }
    val today = cal.timeInMillis
    return when {
        ts >= today -> "Today"
        ts >= today - 86_400_000L -> "Yesterday"
        ts >= today - 6 * 86_400_000L -> "This week"
        else -> "Earlier"
    }
}

/** Escapes LIKE wildcards so the query matches literally (pairs with `ESCAPE '\'`). */
private fun escapeLike(s: String): String =
    s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

private fun relativeTime(ts: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - ts
    return when {
        diff < 60_000 -> "just now"
        diff < 3_600_000 -> "${diff / 60_000} min ago"
        diff < 86_400_000 -> "${diff / 3_600_000}h ago"
        diff < 604_800_000 -> "${diff / 86_400_000}d ago"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ts))
    }
}
