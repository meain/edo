package com.edo.app.ui.files

import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.edo.app.ui.chat.MarkdownText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    var actionMenuFor by remember { mutableStateOf<Workspace.Entry?>(null) }
    var confirmDelete by remember { mutableStateOf<Workspace.Entry?>(null) }
    var refreshTick by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

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

    LaunchedEffect(workspace, currentPath, refreshTick) {
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
                        Box {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            if (e.isDir) {
                                                currentPath = if (currentPath.isBlank()) e.name else "$currentPath/${e.name}"
                                            } else {
                                                val path = if (currentPath.isBlank()) e.name else "$currentPath/${e.name}"
                                                val text = ws.read(path) ?: "(could not read $path)"
                                                viewing = path to text
                                            }
                                        },
                                        onLongClick = { actionMenuFor = e },
                                    )
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
                            DropdownMenu(
                                expanded = actionMenuFor == e,
                                onDismissRequest = { actionMenuFor = null },
                            ) {
                                if (!e.isDir) {
                                    DropdownMenuItem(
                                        text = { Text("Open with…") },
                                        leadingIcon = {
                                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                                        },
                                        onClick = {
                                            actionMenuFor = null
                                            val path = if (currentPath.isBlank()) e.name else "$currentPath/${e.name}"
                                            openWith(context, ws, path)
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Delete") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Filled.DeleteOutline,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    onClick = {
                                        confirmDelete = e
                                        actionMenuFor = null
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    confirmDelete?.let { target ->
        val ws = workspace
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${target.name}?") },
            text = {
                Text(
                    if (target.isDir)
                        "This will remove the folder and all its contents. This cannot be undone."
                    else
                        "This file will be permanently deleted from the workspace.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (ws != null) {
                            val path = if (currentPath.isBlank()) target.name else "$currentPath/${target.name}"
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { ws.delete(path) }
                                android.widget.Toast
                                    .makeText(
                                        context,
                                        if (ok) "Deleted ${target.name}" else "Failed to delete ${target.name}",
                                        android.widget.Toast.LENGTH_SHORT,
                                    )
                                    .show()
                                refreshTick++
                            }
                        }
                        confirmDelete = null
                    },
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
            },
        )
    }
}

private fun openWith(context: android.content.Context, ws: Workspace, path: String) {
    val uri = ws.uriFor(path)
    if (uri == null || uri.scheme == "file") {
        android.widget.Toast
            .makeText(context, "Open With unavailable for this file", android.widget.Toast.LENGTH_SHORT)
            .show()
        return
    }
    val ext = path.substringAfterLast('.', "").lowercase()
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val chooser = Intent.createChooser(intent, "Open with").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(chooser) }.onFailure {
        android.widget.Toast
            .makeText(context, "No app available", android.widget.Toast.LENGTH_SHORT)
            .show()
    }
}

@Composable
private fun FileView(path: String, content: String) {
    val isMarkdown = path.endsWith(".md", ignoreCase = true) ||
        path.endsWith(".markdown", ignoreCase = true)
    val isCsv = path.endsWith(".csv", ignoreCase = true)
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
        if (isMarkdown) {
            val vScroll = rememberScrollState()
            Box(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(vScroll)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                MarkdownText(text = content)
            }
        } else if (isCsv) {
            CsvView(content = content)
        } else {
            val hScroll = rememberScrollState()
            val vScroll = rememberScrollState()
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
}

@Composable
private fun CsvView(content: String) {
    val rows = remember(content) { parseCsv(content) }
    if (rows.isEmpty()) return
    val colCount = rows.maxOf { it.size }
    val colWidth = 120.dp
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()
    Column(
        Modifier
            .fillMaxSize()
            .horizontalScroll(hScroll)
            .verticalScroll(vScroll),
    ) {
        Row(Modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
            rows[0].forEach { cell ->
                Text(
                    cell,
                    modifier = Modifier.width(colWidth).padding(horizontal = 8.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            repeat(colCount - rows[0].size) { Spacer(Modifier.width(colWidth)) }
        }
        HorizontalDivider()
        rows.drop(1).forEachIndexed { index, row ->
            Row(
                Modifier.background(
                    if (index % 2 == 0) MaterialTheme.colorScheme.surface
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                ),
            ) {
                row.forEach { cell ->
                    Text(
                        cell,
                        modifier = Modifier.width(colWidth).padding(horizontal = 8.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                repeat(colCount - row.size) { Spacer(Modifier.width(colWidth)) }
            }
        }
    }
}

private fun parseCsv(content: String): List<List<String>> =
    content.lines().filter { it.isNotBlank() }.map { parseCsvLine(it) }

private fun parseCsvLine(line: String): List<String> {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < line.length) {
        when (val c = line[i]) {
            '"' -> if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                current.append('"'); i++
            } else inQuotes = !inQuotes
            ',' -> if (inQuotes) current.append(c) else { result.add(current.toString()); current.clear() }
            else -> current.append(c)
        }
        i++
    }
    result.add(current.toString())
    return result
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
