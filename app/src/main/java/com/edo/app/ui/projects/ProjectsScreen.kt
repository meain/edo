package com.edo.app.ui.projects

import android.content.Intent
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.edo.app.AppContainer
import com.edo.app.data.ProjectEntity
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(container: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val projects by container.db.projects().observeAll().collectAsState(initial = emptyList())
    val activeId by container.activeProjectId.collectAsState()
    var showCreate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<ProjectEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Projects") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreate = true },
                modifier = Modifier.navigationBarsPadding(),
            ) {
                Icon(Icons.Filled.Add, contentDescription = "New project")
            }
        },
    ) { padding ->
        if (projects.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("No projects yet", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Tap + to create your first project",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(projects, key = { it.id }) { project ->
                    ProjectRow(
                        project = project,
                        isActive = project.id == activeId,
                        onSelect = {
                            container.setActiveProject(project.id)
                            onBack()
                        },
                        onDelete = { confirmDelete = project },
                    )
                }
            }
        }
    }

    confirmDelete?.let { project ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete project?") },
            text = { Text("\"${project.name}\" and all its chat history will be deleted permanently.") },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            container.db.messages().clearProject(project.id)
                            container.db.threads().deleteForProject(project.id)
                            container.db.projects().delete(project)
                            if (activeId == project.id) container.setActiveProject(-1L)
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

    if (showCreate) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showCreate = false },
            sheetState = sheetState,
        ) {
            CreateProjectSheet(
                container = container,
                onCreated = { id ->
                    container.setActiveProject(id)
                    showCreate = false
                    onBack()
                },
                onDismiss = { showCreate = false },
            )
        }
    }
}

@Composable
private fun ProjectRow(
    project: ProjectEntity,
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
            .padding(horizontal = 12.dp, vertical = 2.dp),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onSelect)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Folder,
                contentDescription = null,
                tint = if (isActive) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isActive) androidx.compose.ui.text.font.FontWeight.SemiBold
                    else androidx.compose.ui.text.font.FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val folderLabel = humanWorkspacePath(project.workspaceUri)
                Text(
                    text = folderLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isActive) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "Active",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
            }
            IconButton(onClick = onDelete) {
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

const val WORKSPACE_BASE = "/sdcard/Edo"

@Composable
private fun CreateProjectSheet(
    container: AppContainer,
    onCreated: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    var name by remember { mutableStateOf("") }
    var workspaceUri by remember { mutableStateOf("") }

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            workspaceUri = uri.toString()
        }
    }

    // Initial URI pre-navigates picker to /sdcard/Edo
    val edoFolderUri = DocumentsContract.buildDocumentUri(
        "com.android.externalstorage.documents", "primary:Edo"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp)
            .navigationBarsPadding()
            .animateContentSize(),
    ) {
        Text("New Project", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Project name") },
            placeholder = { Text("My project") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(12.dp))
        if (workspaceUri.isEmpty()) {
            OutlinedButton(
                onClick = { keyboard?.hide(); pickFolder.launch(edoFolderUri) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Folder, null, Modifier.padding(end = 8.dp))
                Text("Pick folder in /sdcard/Edo/")
            }
        } else {
            val label = humanWorkspacePath(workspaceUri).ifBlank { "Folder selected" }
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.small,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { keyboard?.hide(); pickFolder.launch(edoFolderUri) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Workspace folder", style = MaterialTheme.typography.labelSmall)
                        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    scope.launch {
                        val id = container.db.projects().insert(
                            ProjectEntity(name = name.trim(), workspaceUri = workspaceUri)
                        )
                        onCreated(id)
                    }
                },
                enabled = name.isNotBlank() && workspaceUri.isNotEmpty(),
            ) { Text("Create") }
        }
    }
}

/** Render a workspace URI as a human-readable filesystem-style path.
 *  - SAF tree URIs: keeps the slashes from the document id (e.g. "Edo/Ola")
 *  - File paths: returned as-is (e.g. "/sdcard/Edo/Ola"), with /sdcard stripped
 *  Falls back to a trimmed raw URI if the format isn't recognised. */
internal fun humanWorkspacePath(workspaceUri: String): String {
    if (workspaceUri.isBlank()) return ""
    // file:// or raw /sdcard/...
    if (workspaceUri.startsWith("/")) {
        return workspaceUri.removePrefix("/sdcard/").ifBlank { workspaceUri }
    }
    if (workspaceUri.startsWith("file://")) {
        return android.net.Uri.parse(workspaceUri).path
            ?.removePrefix("/sdcard/")
            ?.ifBlank { workspaceUri }
            ?: workspaceUri
    }
    // SAF tree URI: content://.../tree/<documentId>
    // documentId looks like "primary:Edo/Ola" once decoded.
    val docId = workspaceUri.substringAfterLast("/tree/")
    val decoded = runCatching { java.net.URLDecoder.decode(docId, "UTF-8") }
        .getOrElse { docId }
    // Drop the volume prefix ("primary:") for compactness.
    return decoded.substringAfter(':', decoded)
}
