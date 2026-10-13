package com.edo.app.ui.projects

import android.provider.DocumentsContract
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.edo.app.AppContainer
import com.edo.app.data.ProjectEntity
import com.edo.app.data.WorkspaceScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(container: AppContainer, atBottom: Boolean = false, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val projects by container.db.projects().observeAll().collectAsState(initial = emptyList())
    val activeId by container.activeProjectId.collectAsState()
    // The system folder picker stops the activity, which disposes this nav dialog. These must
    // survive that or the sheet vanishes (with its typed input) when the picker returns.
    var showCreate by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<ProjectEntity?>(null) }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    val editing = projects.firstOrNull { it.id == editingId }

    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val visible = remember { MutableTransitionState(false).apply { targetState = true } }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onBack,
                ),
            contentAlignment = if (atBottom) Alignment.BottomCenter else Alignment.TopCenter,
        ) {
            AnimatedVisibility(
                visibleState = visible,
                enter = slideInVertically(tween(180)) { if (atBottom) it else -it } + fadeIn(tween(180)),
            ) {
                Surface(
                    modifier = Modifier
                        .then(if (atBottom) Modifier.navigationBarsPadding() else Modifier.statusBarsPadding())
                        .fillMaxWidth()
                        .padding(8.dp)
                        .heightIn(max = 480.dp)
                        // Swallow taps inside the panel so only the scrim dismisses.
                        .pointerInput(Unit) { detectTapGestures { } },
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp,
                    shadowElevation = 8.dp,
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Projects",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { showCreate = true }) {
                                Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("New")
                            }
                        }
                        if (projects.isEmpty()) {
                            Text(
                                "No projects yet. Tap New to create one.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                            )
                        } else {
                            LazyColumn(
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 8.dp),
                            ) {
                                items(projects, key = { it.id }) { project ->
                                    ProjectRow(
                                        project = project,
                                        isActive = project.id == activeId,
                                        onSelect = {
                                            container.setActiveProject(project.id)
                                            onBack()
                                        },
                                        onEdit = { editingId = project.id },
                                        onDelete = { confirmDelete = project },
                                    )
                                }
                            }
                        }
                    }
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
                            container.scheduler.deleteForProject(project.id)
                            container.db.projects().delete(project)
                            container.releaseGrant(project.workspaceUri)
                            if (activeId == project.id) container.setActiveProject(-1L)
                        }
                        confirmDelete = null
                    },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
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

    editing?.let { proj ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { editingId = null },
            sheetState = sheetState,
        ) {
            EditProjectSheet(
                container = container,
                project = proj,
                onDone = { editingId = null },
                onDeleteRequest = {
                    editingId = null
                    confirmDelete = proj
                },
            )
        }
    }
}

@Composable
private fun ProjectRow(
    project: ProjectEntity,
    isActive: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
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
            Icon(
                Icons.Filled.Folder,
                contentDescription = null,
                tint = if (isActive) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f).padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = project.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isActive) androidx.compose.ui.text.font.FontWeight.SemiBold
                        else androidx.compose.ui.text.font.FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (project.yoloMode) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = MaterialTheme.shapes.extraSmall,
                        ) {
                            Text(
                                "YOLO",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                        }
                    }
                }
                Text(
                    text = project.description.ifBlank { humanWorkspacePath(project.workspaceUri) },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = "Edit project",
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

const val WORKSPACE_BASE = "/sdcard/Edo"

private fun overlapMessage(other: ProjectEntity) =
    "That folder overlaps \"${other.name}\". Projects can't share or nest folders, so each only sees its own files."

@Composable
private fun FolderError(message: String?) {
    if (message == null) return
    Spacer(Modifier.height(6.dp))
    Text(message, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
}

/**
 * New-project form state. Lives in a ViewModel because the system folder picker stops the
 * activity, which disposes the projects dialog and would otherwise wipe what was typed.
 */
class NewProjectForm : ViewModel() {
    val name = mutableStateOf("")
    val description = mutableStateOf("")
    val workspaceUri = mutableStateOf("")
    val yoloMode = mutableStateOf(false)

    fun reset() {
        name.value = ""
        description.value = ""
        workspaceUri.value = ""
        yoloMode.value = false
    }
}

@Composable
private fun CreateProjectSheet(
    container: AppContainer,
    onCreated: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val form: NewProjectForm = viewModel()
    var name by form.name
    var description by form.description
    var workspaceUri by form.workspaceUri
    var yoloMode by form.yoloMode

    var folderError by remember { mutableStateOf<String?>(null) }
    val picked by container.pickedFolder.collectAsState()
    LaunchedEffect(picked) {
        val uri = picked ?: return@LaunchedEffect
        container.pickedFolder.value = null
        val clash = WorkspaceScope.conflict(uri, container.db.projects().observeAll().first())
        if (clash == null) {
            folderError = null
            workspaceUri = uri
        } else {
            folderError = overlapMessage(clash)
            container.releaseGrant(uri)
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
            .padding(bottom = 16.dp)
            .imePadding()
            .windowInsetsPadding(WindowInsets.navigationBars.exclude(WindowInsets.ime))
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
        OutlinedTextField(
            value = description,
            onValueChange = { description = it },
            label = { Text("Description (optional)") },
            placeholder = { Text("What this project is about…") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3,
        )
        Spacer(Modifier.height(12.dp))
        if (workspaceUri.isEmpty()) {
            OutlinedButton(
                onClick = { keyboard?.hide(); container.launchFolderPicker(edoFolderUri) },
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
                        .clickable { keyboard?.hide(); container.launchFolderPicker(edoFolderUri) }
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
        FolderError(folderError)
        Spacer(Modifier.height(16.dp))
        YoloRow(yoloMode = yoloMode, onChange = { yoloMode = it })
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { form.reset(); onDismiss() }) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    scope.launch {
                        val id = container.db.projects().insert(
                            ProjectEntity(
                                name = name.trim(),
                                workspaceUri = workspaceUri,
                                description = description.trim(),
                                yoloMode = yoloMode,
                            )
                        )
                        form.reset()
                        onCreated(id)
                    }
                },
                enabled = name.isNotBlank() && workspaceUri.isNotEmpty(),
            ) { Text("Create") }
        }
    }
}

@Composable
private fun YoloRow(yoloMode: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("YOLO mode", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Auto-approve all tool calls in this project",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        androidx.compose.material3.Switch(checked = yoloMode, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditProjectSheet(
    container: AppContainer,
    project: ProjectEntity,
    onDone: () -> Unit,
    onDeleteRequest: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    var name by rememberSaveable(project.id) { mutableStateOf(project.name) }
    var description by rememberSaveable(project.id) { mutableStateOf(project.description) }
    var yoloMode by rememberSaveable(project.id) { mutableStateOf(project.yoloMode) }
    var luaNetwork by rememberSaveable(project.id) { mutableStateOf(project.luaNetwork) }
    var workspaceUri by rememberSaveable(project.id) { mutableStateOf(project.workspaceUri) }
    val global = remember { container.settings.load() }
    var overrideOn by rememberSaveable(project.id) {
        mutableStateOf(project.modelOverride != null || project.maxTurnsOverride != null)
    }
    var modelText by rememberSaveable(project.id) { mutableStateOf(project.modelOverride ?: global.model) }
    var maxTurnsText by rememberSaveable(project.id) {
        mutableStateOf((project.maxTurnsOverride ?: global.maxTurns).toString())
    }

    var folderError by remember { mutableStateOf<String?>(null) }
    val picked by container.pickedFolder.collectAsState()
    LaunchedEffect(picked) {
        val uri = picked ?: return@LaunchedEffect
        container.pickedFolder.value = null
        val others = container.db.projects().observeAll().first().filter { it.id != project.id }
        val clash = WorkspaceScope.conflict(uri, others)
        if (clash == null) {
            folderError = null
            workspaceUri = uri
        } else {
            folderError = overlapMessage(clash)
            container.releaseGrant(uri)
        }
    }

    val edoFolderUri = DocumentsContract.buildDocumentUri(
        "com.android.externalstorage.documents", "primary:Edo"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 16.dp)
            .imePadding()
            .windowInsetsPadding(WindowInsets.navigationBars.exclude(WindowInsets.ime))
            .verticalScroll(rememberScrollState())
            .animateContentSize(),
    ) {
        Text("Project settings", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Project name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = description,
            onValueChange = { description = it },
            label = { Text("Description") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 4,
        )
        Spacer(Modifier.height(12.dp))
        val folderLabel = humanWorkspacePath(workspaceUri).ifBlank { "Folder selected" }
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.small,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { keyboard?.hide(); container.launchFolderPicker(edoFolderUri) }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Workspace folder", style = MaterialTheme.typography.labelSmall)
                    Text(folderLabel, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        FolderError(folderError)
        Spacer(Modifier.height(16.dp))
        YoloRow(yoloMode = yoloMode, onChange = { yoloMode = it })
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Lua network access", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Let Lua scripts make HTTP requests. Lua can always compute and use this project's files",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            androidx.compose.material3.Switch(checked = luaNetwork, onCheckedChange = { luaNetwork = it })
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Override values", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (overrideOn) "Model and max turns for this project only"
                    else "Using global model and max turns",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            androidx.compose.material3.Switch(checked = overrideOn, onCheckedChange = { overrideOn = it })
        }
        if (overrideOn) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = modelText,
                onValueChange = { modelText = it },
                label = { Text("Model") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = maxTurnsText,
                onValueChange = { v -> maxTurnsText = v.filter { it.isDigit() }.take(4) },
                label = { Text("Max turns") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                ),
            )
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDone) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    scope.launch {
                        container.db.projects().update(
                            project.copy(
                                name = name.trim().ifBlank { project.name },
                                description = description.trim(),
                                yoloMode = yoloMode,
                                luaNetwork = luaNetwork,
                                workspaceUri = workspaceUri,
                                modelOverride = if (overrideOn) modelText.trim().ifBlank { null } else null,
                                maxTurnsOverride = if (overrideOn) maxTurnsText.toIntOrNull()?.takeIf { it > 0 } else null,
                            )
                        )
                        if (workspaceUri != project.workspaceUri) container.releaseGrant(project.workspaceUri)
                        onDone()
                    }
                },
            ) { Text("Save") }
        }
        Spacer(Modifier.height(12.dp))
        androidx.compose.material3.HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onDeleteRequest,
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.error.copy(alpha = 0.5f),
            ),
        ) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("Delete project")
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
