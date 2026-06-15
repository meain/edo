package com.edo.app.ui.files

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.edo.app.AppContainer
import com.edo.app.agent.FileWorkspace
import com.edo.app.agent.SafWorkspace
import com.edo.app.agent.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val activeId by container.activeProjectId.collectAsState()
    var projectName by remember { mutableStateOf<String?>(null) }
    var workspace by remember { mutableStateOf<Workspace?>(null) }
    var currentPath by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<Workspace.Entry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var viewing by remember { mutableStateOf<Pair<String, String>?>(null) } // path -> content

    LaunchedEffect(activeId) {
        if (activeId <= 0) {
            workspace = null
            projectName = null
            return@LaunchedEffect
        }
        val p = container.db.projects().getById(activeId) ?: return@LaunchedEffect
        projectName = p.name
        workspace = if (p.workspaceUri.startsWith("content://")) {
            SafWorkspace(context.applicationContext, Uri.parse(p.workspaceUri))
        } else {
            FileWorkspace(java.io.File(p.workspaceUri))
        }
        currentPath = ""
    }

    LaunchedEffect(workspace, currentPath) {
        val ws = workspace ?: return@LaunchedEffect
        loading = true
        entries = withContext(Dispatchers.IO) { ws.ls(currentPath) ?: emptyList() }
            .sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
        loading = false
    }

    val title = if (currentPath.isBlank()) projectName ?: "Files" else currentPath
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        when {
                            viewing != null -> viewing = null
                            currentPath.isBlank() -> onBack()
                            else -> currentPath = currentPath.substringBeforeLast('/', "")
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            val ws = workspace
            val v = viewing
            when {
                v != null -> FileView(path = v.first, content = v.second)
                ws == null -> EmptyState("No project selected. Pick a project first.")
                loading && entries.isEmpty() -> Unit
                entries.isEmpty() -> EmptyState("(empty folder)")
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp),
                ) {
                    items(entries, key = { e -> "${currentPath}/${e.name}" }) { e ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (e.isDir) {
                                        currentPath = if (currentPath.isBlank()) e.name else "$currentPath/${e.name}"
                                    } else {
                                        val path = if (currentPath.isBlank()) e.name else "$currentPath/${e.name}"
                                        val text = ws.read(path) ?: "(could not read $path)"
                                        viewing = path to text
                                    }
                                }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (e.isDir) Icons.Filled.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = if (e.isDir) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                if (e.isDir) "${e.name}/" else e.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (e.isDir) FontWeight.Medium else FontWeight.Normal,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!e.isDir) {
                                Text(
                                    humanSize(e.size),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileView(path: String, content: String) {
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()
    Column(Modifier.fillMaxSize()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                path,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .verticalScroll(vScroll)
                .horizontalScroll(hScroll)
                .padding(12.dp),
        ) {
            Text(
                content,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${bytes / (1024 * 1024)} MB"
}
