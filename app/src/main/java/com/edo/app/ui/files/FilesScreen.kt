package com.edo.app.ui.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image as ImageIcon
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.edo.app.AppContainer
import com.edo.app.ui.openExternally
import com.edo.app.agent.FileWorkspace
import com.edo.app.agent.SafWorkspace
import com.edo.app.agent.Workspace
import com.edo.app.ui.chat.MarkdownText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class SortBy(val label: String) {
    NAME("Name"),
    MODIFIED("Modified"),
    SIZE("Size"),
}

private enum class NewKind { FILE, FOLDER }

private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
private val CODE_EXTENSIONS = setOf(
    "kt", "java", "py", "js", "ts", "go", "rs", "c", "h", "cpp", "sh", "lua", "json", "yaml", "yml", "toml", "xml", "css",
)

private fun extensionOf(name: String) = name.substringAfterLast('.', "").lowercase()
private fun isImage(name: String) = extensionOf(name) in IMAGE_EXTENSIONS

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FilesScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val activeId by container.activeProjectId.collectAsState()
    var projectName by remember { mutableStateOf<String?>(null) }
    var workspace by remember { mutableStateOf<Workspace?>(null) }
    var currentPath by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<Workspace.Entry>>(emptyList()) }
    var entriesPath by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var viewing by remember { mutableStateOf<Pair<String, String>?>(null) } // path -> content
    var viewingImage by remember { mutableStateOf<String?>(null) }
    var actionMenuFor by remember { mutableStateOf<Workspace.Entry?>(null) }
    var confirmDelete by remember { mutableStateOf<Workspace.Entry?>(null) }
    var renaming by remember { mutableStateOf<Workspace.Entry?>(null) }
    var creating by remember { mutableStateOf<NewKind?>(null) }
    var createMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var sortBy by rememberSaveable { mutableStateOf(SortBy.NAME) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var refreshTick by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    fun full(name: String) = if (currentPath.isBlank()) name else "$currentPath/$name"
    fun toast(msg: String) =
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
    fun closeSearch() {
        searching = false
        query = ""
    }

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
        closeSearch()
    }

    // With a query this searches everything below the current folder; without, lists it.
    LaunchedEffect(workspace, currentPath, refreshTick, query) {
        val ws = workspace ?: return@LaunchedEffect
        val q = query.trim()
        loading = true
        val path = currentPath
        if (q.isNotEmpty()) delay(250)
        val found = withContext(Dispatchers.IO) {
            if (q.isEmpty()) ws.ls(path) ?: emptyList()
            else ws.walk(path)
                .filter { it.name.substringAfterLast('/').contains(q, ignoreCase = true) }
                .take(200)
                .toList()
        }
        entries = found
        entriesPath = path
        loading = false
    }

    val sorted = remember(entries, sortBy) {
        val byFolder = compareBy<Workspace.Entry> { !it.isDir }
        entries.sortedWith(
            when (sortBy) {
                SortBy.NAME -> byFolder.thenBy { it.name.lowercase() }
                SortBy.MODIFIED -> byFolder.thenByDescending { it.modified }.thenBy { it.name.lowercase() }
                SortBy.SIZE -> byFolder.thenByDescending { it.size }.thenBy { it.name.lowercase() }
            },
        )
    }
    // Item counts for folder rows, filled in lazily as rows scroll into view.
    val counts = remember(workspace, refreshTick) { mutableStateMapOf<String, Int>() }

    val atRoot = currentPath.isBlank()
    val inViewer = viewing != null || viewingImage != null
    // System back closes a preview / search, then steps up a folder; at the root it falls
    // through to the panel container, which closes the browser.
    BackHandler(enabled = inViewer || searching || !atRoot) {
        when {
            inViewer -> { viewing = null; viewingImage = null }
            searching -> closeSearch()
            else -> currentPath = currentPath.substringBeforeLast('/', "")
        }
    }

    fun openEntry(ws: Workspace, e: Workspace.Entry) {
        val path = full(e.name)
        when {
            e.isDir -> { closeSearch(); currentPath = path }
            isImage(e.name) -> viewingImage = path
            else -> scope.launch {
                loading = true
                val text = withContext(Dispatchers.IO) { ws.read(path) } ?: "(could not read $path)"
                loading = false
                viewing = path to text
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching && !inViewer) {
                        val focus = remember { FocusRequester() }
                        LaunchedEffect(Unit) { focus.requestFocus() }
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text(if (atRoot) "Search files" else "Search in $currentPath") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {}),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                    } else {
                        Text(
                            if (inViewer) (viewing?.first ?: viewingImage).orEmpty().substringAfterLast('/')
                            else projectName ?: "Files",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        when {
                            inViewer -> { viewing = null; viewingImage = null }
                            searching -> closeSearch()
                            atRoot -> onBack()
                            else -> currentPath = currentPath.substringBeforeLast('/', "")
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    val open = viewing?.first ?: viewingImage
                    val ws = workspace
                    if (open != null && ws != null) {
                        IconButton(onClick = { openWith(context, ws, open) }) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open with…")
                        }
                    } else if (ws != null) {
                        if (searching) {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Clear")
                                }
                            }
                        } else {
                            IconButton(onClick = { searching = true }) {
                                Icon(Icons.Filled.Search, contentDescription = "Search")
                            }
                        }
                        Box {
                            IconButton(onClick = { sortMenu = true }) {
                                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
                            }
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                SortBy.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.label) },
                                        leadingIcon = {
                                            if (option == sortBy) Icon(Icons.Filled.Check, contentDescription = null)
                                            else Spacer(Modifier.size(24.dp))
                                        },
                                        onClick = { sortBy = option; sortMenu = false },
                                    )
                                }
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (workspace != null && !inViewer && !searching) {
                Box {
                    FloatingActionButton(onClick = { createMenu = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "New")
                    }
                    DropdownMenu(expanded = createMenu, onDismissRequest = { createMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("New file") },
                            leadingIcon = { Icon(Icons.Filled.NoteAdd, contentDescription = null) },
                            onClick = { createMenu = false; creating = NewKind.FILE },
                        )
                        DropdownMenuItem(
                            text = { Text("New folder") },
                            leadingIcon = { Icon(Icons.Filled.CreateNewFolder, contentDescription = null) },
                            onClick = { createMenu = false; creating = NewKind.FOLDER },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            val ws = workspace
            if (ws != null && !inViewer && !searching) {
                Breadcrumbs(
                    root = projectName ?: "Files",
                    path = currentPath,
                    onNavigate = { currentPath = it },
                )
            }
            Box(modifier = Modifier.fillMaxSize()) {
                val v = viewing
                val img = viewingImage
                val shown = if (entriesPath == currentPath) sorted else emptyList()
                when {
                    v != null -> FileView(path = v.first, content = v.second)
                    img != null && ws != null -> ImageView(ws = ws, path = img)
                    ws == null -> EmptyState("No project selected. Pick a project first.")
                    shown.isEmpty() && !loading ->
                        EmptyState(
                            when {
                                query.isNotBlank() -> "No matches"
                                else -> "(empty folder)"
                            },
                        )
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 88.dp),
                    ) {
                        items(shown, key = { e -> "${currentPath}/${e.name}" }) { e ->
                            val path = full(e.name)
                            val leaf = e.name.substringAfterLast('/')
                            val parent = e.name.substringBeforeLast('/', "")
                            if (e.isDir && path !in counts) {
                                LaunchedEffect(path, refreshTick) {
                                    counts[path] = withContext(Dispatchers.IO) { ws.ls(path)?.size ?: 0 }
                                }
                            }
                            Box {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            onClick = { openEntry(ws, e) },
                                            onLongClick = { actionMenuFor = e },
                                        )
                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        iconFor(e),
                                        contentDescription = null,
                                        modifier = Modifier.size(22.dp),
                                        tint = if (e.isDir) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            if (e.isDir) "$leaf/" else leaf,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (e.isDir) FontWeight.Medium else FontWeight.Normal,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        val detail = buildList {
                                            if (parent.isNotEmpty()) add(parent)
                                            if (e.isDir) counts[path]?.let { add(if (it == 1) "1 item" else "$it items") }
                                            else add(humanSize(e.size))
                                            if (e.modified > 0) add(relativeTime(e.modified))
                                        }.joinToString(" · ")
                                        if (detail.isNotEmpty()) {
                                            Text(
                                                detail,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
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
                                                openWith(context, ws, path)
                                            },
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text("Rename") },
                                        leadingIcon = {
                                            Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = null)
                                        },
                                        onClick = {
                                            actionMenuFor = null
                                            renaming = e
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Copy path") },
                                        leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                                        onClick = {
                                            actionMenuFor = null
                                            clipboard.setText(AnnotatedString(path))
                                            toast("Copied $path")
                                        },
                                    )
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
                if (loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                }
            }
        }
    }

    creating?.let { kind ->
        NameDialog(
            title = if (kind == NewKind.FILE) "New file" else "New folder",
            initial = "",
            confirmLabel = "Create",
            onDismiss = { creating = null },
            onConfirm = { name ->
                creating = null
                val ws = workspace ?: return@NameDialog
                scope.launch {
                    val path = full(name)
                    val exists = withContext(Dispatchers.IO) { ws.ls(currentPath)?.any { it.name == name } == true }
                    if (exists) return@launch toast("$name already exists")
                    val ok = withContext(Dispatchers.IO) {
                        if (kind == NewKind.FILE) ws.write(path, "") else ws.mkdir(path)
                    }
                    if (!ok) toast("Failed to create $name")
                    refreshTick++
                }
            },
        )
    }

    renaming?.let { target ->
        NameDialog(
            title = "Rename",
            initial = target.name.substringAfterLast('/'),
            confirmLabel = "Rename",
            onDismiss = { renaming = null },
            onConfirm = { name ->
                renaming = null
                val ws = workspace ?: return@NameDialog
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { ws.rename(full(target.name), name) }
                    if (!ok) toast("Failed to rename ${target.name.substringAfterLast('/')}")
                    refreshTick++
                }
            },
        )
    }

    confirmDelete?.let { target ->
        val ws = workspace
        val leaf = target.name.substringAfterLast('/')
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete $leaf?") },
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
                            val path = full(target.name)
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { ws.delete(path) }
                                toast(if (ok) "Deleted $leaf" else "Failed to delete $leaf")
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

@Composable
private fun Breadcrumbs(root: String, path: String, onNavigate: (String) -> Unit) {
    val segments = remember(path) { path.split('/').filter { it.isNotEmpty() } }
    val scroll = rememberScrollState()
    LaunchedEffect(path) { scroll.scrollTo(scroll.maxValue) }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = 10.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val crumbs = listOf(root to "") + segments.mapIndexed { i, s -> s to segments.take(i + 1).joinToString("/") }
        crumbs.forEachIndexed { i, (label, target) ->
            val last = i == crumbs.lastIndex
            if (i > 0) {
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal,
                color = if (last) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                maxLines = 1,
                modifier = Modifier
                    .clickable(enabled = !last) { onNavigate(target) }
                    .padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
    }
    HorizontalDivider()
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    val clean = name.trim()
    val valid = clean.isNotEmpty() && '/' !in clean && clean != "." && clean != ".."
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text("Name") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (valid) onConfirm(clean) }),
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(clean) }, enabled = valid) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun iconFor(e: Workspace.Entry) = when {
    e.isDir -> Icons.Filled.Folder
    else -> when (extensionOf(e.name)) {
        in IMAGE_EXTENSIONS -> Icons.Filled.ImageIcon
        "md", "markdown", "txt", "html", "htm" -> Icons.Filled.Description
        "csv", "tsv" -> Icons.Filled.TableChart
        in CODE_EXTENSIONS -> Icons.Filled.Code
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
}

private fun relativeTime(millis: Long): String {
    val diff = System.currentTimeMillis() - millis
    val minutes = diff / 60_000
    return when {
        diff < 0 || minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 60 * 24 -> "${minutes / 60}h ago"
        minutes < 60 * 24 * 7 -> "${minutes / (60 * 24)}d ago"
        else -> java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault()).format(java.util.Date(millis))
    }
}

@Composable
private fun ImageView(ws: Workspace, path: String) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, ws, path) {
        value = withContext(Dispatchers.IO) { decodeImage(context, ws, path) }
    }
    var failed by remember(path) { mutableStateOf(false) }
    var scale by remember(path) { mutableStateOf(1f) }
    var offsetX by remember(path) { mutableStateOf(0f) }
    var offsetY by remember(path) { mutableStateOf(0f) }
    val bmp = bitmap
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = path,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(path) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 8f)
                            if (scale == 1f) { offsetX = 0f; offsetY = 0f }
                            else { offsetX += pan.x; offsetY += pan.y }
                        }
                    }
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY,
                    ),
            )
        } else {
            LaunchedEffect(path) { delay(3000); failed = true }
            if (failed) Text("Couldn't load image", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }
}

/** Decodes at most ~2048px on the long side so big photos don't blow the heap. */
private fun decodeImage(context: android.content.Context, ws: Workspace, path: String): Bitmap? {
    val uri = ws.uriFor(path) ?: return null
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching { resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return runCatching { resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } }.getOrNull()
}

private fun openWith(context: android.content.Context, ws: Workspace, path: String) {
    val uri = ws.uriFor(path)
    if (uri == null) {
        android.widget.Toast
            .makeText(context, "Open With unavailable for this file", android.widget.Toast.LENGTH_SHORT)
            .show()
        return
    }
    openExternally(context, path, uri)
}

@Composable
private fun FileView(path: String, content: String) {
    val isMarkdown = path.endsWith(".md", ignoreCase = true) ||
        path.endsWith(".markdown", ignoreCase = true)
    val isCsv = path.endsWith(".csv", ignoreCase = true)
    val isHtml = path.endsWith(".html", ignoreCase = true) || path.endsWith(".htm", ignoreCase = true)
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
        } else if (isHtml) {
            AndroidView(
                factory = { ctx ->
                    android.webkit.WebView(ctx).apply {
                        settings.javaScriptEnabled = false
                        loadDataWithBaseURL(null, content, "text/html", "UTF-8", null)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
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
