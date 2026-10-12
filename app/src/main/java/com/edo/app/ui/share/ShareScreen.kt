package com.edo.app.ui.share

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.edo.app.AppContainer
import kotlinx.coroutines.flow.flowOf

/**
 * Destination picker shown after content is shared into Edo: choose a project, then
 * either a new chat or one of that project's existing chats.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareScreen(
    container: AppContainer,
    onOpenProjects: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    val share by container.pendingShare.collectAsState()
    val projects by container.db.projects().observeAll().collectAsState(initial = emptyList())
    val activeProjectId by container.activeProjectId.collectAsState()

    // Default to the project the user was last in, else the first one.
    var chosenProjectId by remember { mutableStateOf<Long?>(null) }
    val projectId = chosenProjectId?.takeIf { id -> projects.any { it.id == id } }
        ?: projects.firstOrNull { it.id == activeProjectId }?.id
        ?: projects.firstOrNull()?.id

    val threadsFlow = remember(projectId) {
        if (projectId != null) container.db.threads().observeForProject(projectId) else flowOf(emptyList())
    }
    val threads by threadsFlow.collectAsState(initial = emptyList())

    fun deliver(threadId: Long) {
        val pid = projectId ?: return
        container.setActiveProject(pid)
        container.setActiveThread(threadId)
        container.confirmShare()
        onDone()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Share to Edo") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Filled.Close, contentDescription = "Cancel")
                    }
                },
            )
        },
    ) { padding ->
        if (projects.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Create a project first", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onOpenProjects) { Text("Open projects") }
                }
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item { Preview(share?.text.orEmpty(), share?.uris?.size ?: 0) }
            item {
                SectionLabel("Project")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(projects, key = { it.id }) { p ->
                        FilterChip(
                            selected = p.id == projectId,
                            onClick = { chosenProjectId = p.id },
                            label = { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }
            item { SectionLabel("Chat") }
            item { ChatRow(title = "New chat", isNew = true, onClick = { deliver(-1L) }) }
            items(threads, key = { it.id }) { t ->
                ChatRow(title = t.title, isNew = false, onClick = { deliver(t.id) })
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
    )
}

@Composable
private fun Preview(text: String, fileCount: Int) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            if (text.isNotBlank()) {
                Text(text, maxLines = 5, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            }
            if (fileCount > 0) {
                if (text.isNotBlank()) Spacer(Modifier.height(6.dp))
                Text(
                    if (fileCount == 1) "1 attachment" else "$fileCount attachments",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun ChatRow(title: String, isNew: Boolean, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (isNew) Icons.Filled.Add else Icons.AutoMirrored.Filled.Chat,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
