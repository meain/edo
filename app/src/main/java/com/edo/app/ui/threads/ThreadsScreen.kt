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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.edo.app.AppContainer
import com.edo.app.data.ThreadEntity
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Chat list; hosted as the left panel of the chat screen's swipe container. */
@Composable
fun ThreadsScreen(container: AppContainer, onBack: () -> Unit) {
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
    var projectName by remember { mutableStateOf("") }
    val activeProjectIdState = activeProjectId
    androidx.compose.runtime.LaunchedEffect(activeProjectIdState) {
        projectName = if (activeProjectIdState > 0)
            container.db.projects().getById(activeProjectIdState)?.name ?: "Chats"
        else "Chats"
    }
    var confirmDelete by remember { mutableStateOf<ThreadEntity?>(null) }
    val focusManager = LocalFocusManager.current

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Chats", style = MaterialTheme.typography.titleMedium)
                if (projectName.isNotBlank()) {
                    Text(
                        projectName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = {
                container.setActiveThread(-1L)
                onBack()
            }) {
                Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("New")
            }
        }
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
                // Search shows everything; otherwise chats idle for 3+ days fold behind a header.
                val cutoff = System.currentTimeMillis() - FOLD_AFTER_MS
                val (recent, older) =
                    if (trimmedQuery.isEmpty()) threads.partition { it.lastUpdatedAt >= cutoff }
                    else threads to emptyList()
                val visible = if (showOlder) recent + older else recent
                items(visible, key = { it.id }) { thread ->
                    ThreadRow(
                        thread = thread,
                        isActive = thread.id == activeThreadId,
                        onSelect = {
                            container.setActiveThread(thread.id)
                            onBack()
                        },
                        onDelete = { confirmDelete = thread },
                    )
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
            // Scheduled runs are titled "⏰ title · time" (see ScheduleManager).
            val scheduled = thread.title.startsWith(SCHEDULED_PREFIX)
            Icon(
                if (scheduled) Icons.Filled.Alarm else Icons.AutoMirrored.Filled.Chat,
                contentDescription = if (scheduled) "Scheduled chat" else null,
                modifier = Modifier.size(18.dp),
                tint = if (isActive) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = if (scheduled) thread.title.removePrefix(SCHEDULED_PREFIX) else thread.title,
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

private const val SCHEDULED_PREFIX = "⏰ "
private const val FOLD_AFTER_MS = 3L * 24 * 60 * 60 * 1000

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
