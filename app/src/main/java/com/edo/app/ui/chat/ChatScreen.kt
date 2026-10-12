package com.edo.app.ui.chat

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.Image as ComposeImage
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.Image
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.SmallFloatingActionButton
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.edo.app.AppContainer
import com.edo.app.EdoApp
import com.edo.app.agent.ApprovalDecision
import com.edo.app.llm.Block
import com.edo.app.llm.Role
import kotlinx.serialization.json.contentOrNull

/** True when the last item's bottom edge is within [thresholdPx] of the viewport's bottom. */
private fun androidx.compose.foundation.lazy.LazyListState.isNearBottom(thresholdPx: Float = 120f): Boolean {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return true
    if (last.index < info.totalItemsCount - 1) return false
    return last.offset + last.size - info.viewportEndOffset <= thresholdPx
}

/**
 * Keep the conversation pinned to the bottom with a smooth glide. Computes the
 * exact pixel distance from the bottom edge of the last item to the bottom of
 * the viewport and animates just that delta with a spring, so following streaming
 * text or a new message reads as a continuous slide rather than a snap. When the
 * bottom is far off-screen (thread load, or a jump from far up) it scrolls
 * instantly instead, so fast streaming can never lag behind the text.
 */
private suspend fun androidx.compose.foundation.lazy.LazyListState.followBottom(totalItems: Int) {
    if (totalItems <= 0) return
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull()
    val viewportHeight = info.viewportEndOffset - info.viewportStartOffset
    if (last != null && last.index >= info.totalItemsCount - 1) {
        val delta = (last.offset + last.size - info.viewportEndOffset).toFloat()
        when {
            delta <= 0f -> return // already resting at the bottom
            delta > viewportHeight * 1.5f ->
                scrollToItem(totalItems - 1, Int.MAX_VALUE / 2)
            else -> animateScrollBy(delta, spring(stiffness = Spring.StiffnessMediumLow))
        }
    } else {
        // Last item isn't even on screen — too far to animate pleasantly; snap.
        scrollToItem(totalItems - 1, Int.MAX_VALUE / 2)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    container: AppContainer,
    onOpenSettings: () -> Unit,
    onOpenProjects: () -> Unit,
    onOpenThreads: () -> Unit,
    onOpenFiles: () -> Unit,
) {
    val context = LocalContext.current
    val vm: ChatViewModel = viewModel(
        factory = chatVmFactory(container, context.applicationContext as EdoApp)
    )
    val state by vm.state.collectAsState()
    // The field is backed by a TextFieldValue so programmatic edits (skill picker, shared
    // text) can put the cursor at the end; `input` is the plain-string view of it.
    val inputField = remember { mutableStateOf(TextFieldValue("")) }
    val input = inputField.value.text
    fun setInput(value: String) {
        if (value != inputField.value.text) inputField.value = TextFieldValue(value, TextRange(value.length))
    }

    // Typing "/" as the first character (no spaces yet) opens the skill picker.
    val skills by vm.skills.collectAsState()
    val slashQuery = input.takeIf { it.startsWith("/") && it.none { c -> c.isWhitespace() } }?.drop(1)
    LaunchedEffect(slashQuery != null) { if (slashQuery != null) vm.refreshSkills() }
    val slashMatches = if (slashQuery == null) emptyList()
    else skills.filter { it.name.contains(slashQuery, ignoreCase = true) }
    var pendingImages by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var pendingTextFile by remember { mutableStateOf<Pair<String, String>?>(null) }
    val expandedToolIds = remember { mutableStateMapOf<String, Boolean>() }
    var viewingImage by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    val pickFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        var anyUnsupported = false
        for (uri in uris) {
            when (val attachment = readAttachment(context.contentResolver, uri)) {
                is Attachment.Image -> pendingImages = pendingImages + (attachment.mediaType to attachment.base64)
                is Attachment.Text -> pendingTextFile = attachment.name to attachment.content
                null -> anyUnsupported = true
            }
        }
        if (anyUnsupported) android.widget.Toast
            .makeText(context, "Some files were unsupported", android.widget.Toast.LENGTH_SHORT)
            .show()
    }

    // Content shared into Edo from another app: once a project/chat has been chosen,
    // drop it into the input box (text) and attachment chips (files) for review.
    val share by container.pendingShare.collectAsState()
    LaunchedEffect(share?.id, share?.confirmed) {
        val s = share?.takeIf { it.confirmed } ?: return@LaunchedEffect
        if (s.text.isNotBlank()) {
            val current = inputField.value.text
            setInput(if (current.isBlank()) s.text else current + "\n" + s.text)
        }
        var anyUnsupported = false
        for (uri in s.uris) {
            val attachment = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { readAttachment(context.contentResolver, uri) }.getOrNull()
            }
            when (attachment) {
                is Attachment.Image -> pendingImages = pendingImages + (attachment.mediaType to attachment.base64)
                is Attachment.Text -> pendingTextFile = attachment.name to attachment.content
                null -> anyUnsupported = true
            }
        }
        container.clearShare()
        if (anyUnsupported) android.widget.Toast
            .makeText(context, "Some shared files were unsupported", android.widget.Toast.LENGTH_SHORT)
            .show()
    }

    // Camera capture: write to a cacheDir file via FileProvider, then read it
    // back as base64 once the camera app reports success.
    var pendingCaptureUri by remember { mutableStateOf<Uri?>(null) }
    val takePhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = pendingCaptureUri
        if (success && uri != null) {
            val img = readImageAsBase64(context.contentResolver, uri)
            if (img != null) pendingImages = pendingImages + img
        }
        pendingCaptureUri = null
    }

    // --- Message list state (hoisted so bottomBar LaunchedEffect can share it) ---
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Whether the list should keep following new content. Off as soon as the user grabs
    // the list (so new messages/tool calls don't yank them away from what they're
    // reading); back on when they scroll to the bottom, tap "jump to latest", send a
    // message, or open a different chat.
    var followTail by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) followTail = false }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.filter { !it }.collect {
            if (listState.isNearBottom()) followTail = true
        }
    }
    LaunchedEffect(state.currentThread?.id) { followTail = true }

    // Pulling up past the very end of the conversation suggests (and, on release, starts) a new chat.
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val newChatPullPx = with(density) { 80.dp.toPx() }
    var pull by remember { mutableFloatStateOf(0f) }
    val pullConnection = remember(listState, state.running) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Finger moving back down retracts the pull before the list scrolls.
                if (pull > 0f && available.y > 0f) {
                    val used = minOf(pull, available.y)
                    pull -= used
                    return Offset(0f, used)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || state.running) return Offset.Zero
                if (available.y < 0f && !listState.canScrollForward) {
                    val before = pull
                    pull = (pull - available.y).coerceAtMost(newChatPullPx * 1.5f)
                    if (before < newChatPullPx && pull >= newChatPullPx) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                val trigger = pull >= newChatPullPx
                pull = 0f
                if (trigger) vm.newChat()
                return Velocity.Zero
            }
        }
    }

    // Index tool results by their tool_use id so completed assistant cards can
    // display the result inline. Tool result blocks live on subsequent user
    // messages that are otherwise filtered from display.
    val toolResults: Map<String, Block.ToolResult> = remember(state.messages) {
        buildMap {
            for (msg in state.messages) {
                for (b in msg.blocks) if (b is Block.ToolResult) put(b.toolUseId, b)
            }
        }
    }
    // Flatten messages into list items: text messages stay as-is, and consecutive tool
    // calls (with no text between them) collapse into one ToolGroup.
    val chatItems = remember(state.messages) {
        buildList<ChatItem> {
            for (msg in state.messages) {
                if (msg.role == Role.User) {
                    if (msg.blocks.any { it !is Block.ToolResult }) add(ChatItem.Msg(msg))
                    continue
                }
                if (msg.role != Role.Assistant) continue
                if (msg.blocks.any { it is Block.Text }) add(ChatItem.Msg(msg))
                val uses = msg.blocks.filterIsInstance<Block.ToolUse>()
                if (uses.isNotEmpty()) {
                    val last = lastOrNull()
                    if (last is ChatItem.ToolGroup) {
                        this[lastIndex] = last.copy(uses = last.uses + uses)
                    } else {
                        add(ChatItem.ToolGroup(uses.first().id, uses))
                    }
                }
            }
        }
    }

    val totalItems = chatItems.size +
            (if (state.streamingText.isNotEmpty()) 1 else 0) +
            (if (state.pendingToolCalls.isNotEmpty()) 1 else 0)
    val isImeVisible = WindowInsets.isImeVisible
    val streamLen = state.streamingText.length

    // Follow the bottom on new items, keyboard opens, or new persisted messages.
    LaunchedEffect(totalItems, isImeVisible, state.scrollVersion, followTail) {
        if (totalItems > 0 && followTail) {
            if (isImeVisible) kotlinx.coroutines.delay(250) // wait for keyboard animation
            listState.followBottom(totalItems)
        }
    }
    // Follow the bottom as streaming text grows. Each chunk restarts this effect,
    // so the in-flight spring retargets to the new bottom — a continuous glide
    // instead of a per-chunk snap. Large jumps fall back to an instant scroll so
    // fast streaming never lags behind the text.
    LaunchedEffect(streamLen) {
        if (totalItems > 0 && streamLen > 0 && followTail) {
            listState.followBottom(totalItems)
        }
    }

    Scaffold(
        // Bottom insets (IME / nav bar) are handled by the content column and input bar;
        // letting Scaffold add them too left a doubled gap under the input.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(
                            modifier = Modifier.clickable(onClick = onOpenProjects),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = state.currentProject?.name ?: "Edo",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (state.currentProject != null) {
                                Spacer(Modifier.width(2.dp))
                                Icon(
                                    Icons.Filled.KeyboardArrowDown,
                                    contentDescription = "Switch project",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onOpenThreads) {
                        Icon(Icons.Filled.Menu, contentDescription = "Chat history")
                    }
                },
                actions = {
                    if (state.currentProject != null) {
                        IconButton(
                            onClick = { vm.newChat() },
                            enabled = state.running || state.currentThread != null || state.messages.isNotEmpty(),
                        ) {
                            Icon(Icons.Filled.Edit, contentDescription = "New chat")
                        }
                    }
                    var menuOpen by remember { mutableStateOf(false) }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (state.currentProject != null) {
                            DropdownMenuItem(
                                text = { Text("Files") },
                                leadingIcon = { Icon(Icons.Filled.FolderOpen, contentDescription = null) },
                                onClick = { menuOpen = false; onOpenFiles() },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Projects") },
                            leadingIcon = { Icon(Icons.Filled.Folder, contentDescription = null) },
                            onClick = { menuOpen = false; onOpenProjects() },
                        )
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                            onClick = { menuOpen = false; onOpenSettings() },
                        )
                    }
                },
            )
        },
    ) { scaffoldPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding)
                .imePadding(),
        ) {
            // --- Message list takes remaining space ---
            Box(modifier = Modifier.weight(1f)) {
                if (state.needsProject) {
                    NoProjectPlaceholder(onOpenProjects = onOpenProjects)
                } else if (state.messages.isEmpty() && state.streamingText.isEmpty() && !state.running) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(32.dp),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "Start a new chat",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Send a message to get started.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(pullConnection)
                            .graphicsLayer { translationY = -pull * 0.4f },
                        state = listState,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 12.dp,
                            vertical = 8.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(chatItems, key = { _, item -> item.key }) { index, item ->
                            when (item) {
                                is ChatItem.Msg ->
                                    MessageBubble(item.msg, expandedToolIds) { viewingImage = it }
                                is ChatItem.ToolGroup -> {
                                    // Fold once something follows the group (or the run is over).
                                    val folded = index < chatItems.lastIndex ||
                                        (!state.running && state.pendingToolCalls.isEmpty())
                                    ToolGroupView(item, toolResults, expandedToolIds, folded)
                                }
                            }
                        }
                        if (state.streamingText.isNotEmpty()) {
                            item("streaming") {
                                AssistantBubble(state.streamingText, isStreaming = true, expandedIds = expandedToolIds)
                            }
                        }
                        if (state.pendingToolCalls.isNotEmpty()) {
                            item("pending-tools") {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    for (tc in state.pendingToolCalls.values) {
                                        ToolCallCard(tc, expandedToolIds)
                                    }
                                }
                            }
                        }
                        state.retryingAttempt?.let { attempt ->
                            item("retrying") {
                                RetryingBanner(
                                    attempt = attempt,
                                    maxAttempts = com.edo.app.agent.Agent.MAX_LLM_RETRIES,
                                    cause = state.retryingCause,
                                )
                            }
                        }
                        state.error?.let { err ->
                            item("error") {
                                ErrorBanner(
                                    message = err,
                                    onDismiss = { vm.dismissError() },
                                    onRetry = if (state.canRetry) { { vm.retryAgent() } } else null,
                                )
                            }
                        }
                    }
                    if (pull > 0f) {
                        val ready = pull >= newChatPullPx
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.extraLarge,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 12.dp)
                                .graphicsLayer { alpha = (pull / newChatPullPx).coerceIn(0f, 1f) },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (ready) "Release to start a new chat" else "Keep pulling for a new chat",
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                    } else if (!followTail) {
                        SmallFloatingActionButton(
                            onClick = {
                                followTail = true
                                scope.launch { listState.followBottom(totalItems) }
                            },
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                        ) {
                            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Jump to latest")
                        }
                    }
                }
            }

            // --- Input bar pinned to bottom of column ---
            Surface(color = MaterialTheme.colorScheme.background) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(
                            WindowInsets.navigationBars.exclude(WindowInsets.ime)
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    if (slashMatches.isNotEmpty()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp).heightIn(max = 240.dp),
                        ) {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                for (skill in slashMatches) {
                                    Column(
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable { setInput("/${skill.name} ") }
                                            .padding(horizontal = 16.dp, vertical = 10.dp),
                                    ) {
                                        Text(
                                            "/${skill.name}",
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Medium,
                                        )
                                        Text(
                                            skill.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    state.queuedMessages.forEachIndexed { index, queued ->
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "Queued: " + queued.text.take(50).let { if (queued.text.length > 50) "$it…" else it }.ifBlank { "[image]" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                TextButton(
                                    onClick = { vm.sendQueuedNow(index) },
                                    modifier = Modifier.height(24.dp),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                ) {
                                    Text("Send now", style = MaterialTheme.typography.labelSmall)
                                }
                                TextButton(
                                    onClick = { vm.cancelQueue(index) },
                                    modifier = Modifier.height(24.dp),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                ) {
                                    Text("Cancel", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                    if (pendingImages.isNotEmpty()) {
                        AttachmentChip(
                            icon = Icons.Filled.Image,
                            label = if (pendingImages.size == 1) "1 image" else "${pendingImages.size} images",
                            onRemove = { pendingImages = emptyList() },
                        )
                    }
                    pendingTextFile?.let { (filename, content) ->
                        AttachmentChip(
                            icon = Icons.AutoMirrored.Filled.InsertDriveFile,
                            label = "$filename · ${content.length} chars",
                            onRemove = { pendingTextFile = null },
                        )
                    }
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(28.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        IconButton(
                            onClick = { pickFiles.launch(arrayOf("*/*")) },
                            modifier = Modifier.padding(bottom = 2.dp),
                        ) {
                            Icon(
                                Icons.Filled.AttachFile,
                                contentDescription = "Attach files",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            onClick = {
                                val uri = createCaptureUri(context)
                                if (uri != null) {
                                    pendingCaptureUri = uri
                                    takePhoto.launch(uri)
                                }
                            },
                            modifier = Modifier.padding(bottom = 2.dp),
                        ) {
                            Icon(
                                Icons.Filled.PhotoCamera,
                                contentDescription = "Take photo",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextField(
                            value = inputField.value,
                            onValueChange = { inputField.value = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Message…") },
                            maxLines = 6,
                            shape = RoundedCornerShape(24.dp),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                disabledContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            ),
                        )
                        val hasContent = input.isNotBlank() || pendingImages.isNotEmpty() || pendingTextFile != null
                        val composed = buildString {
                            pendingTextFile?.let { (filename, content) ->
                                append(attachedFileBlock(filename, content))
                            }
                            append(input.trim())
                        }
                        if (state.running && hasContent) {
                            // Queue button: agent is running but user has typed a message
                            IconButton(
                                onClick = {
                                    vm.queueMessage(composed.trim(), pendingImages)
                                    setInput("")
                                    pendingImages = emptyList()
                                    pendingTextFile = null
                                },
                                modifier = Modifier.padding(bottom = 2.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Add,
                                    contentDescription = "Queue message",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        } else {
                            // Plain Box instead of IconButton: IconButton enforces a 48dp minimum
                            // touch size, which made the circle overshoot the pill.
                            val canSend = !state.needsProject && hasContent
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .padding(start = 4.dp, end = 8.dp, bottom = 8.dp)
                                    .size(40.dp)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(
                                        if (state.running || canSend) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outlineVariant,
                                    )
                                    .clickable(enabled = state.running || canSend) {
                                        if (state.running) {
                                            vm.cancelAgent()
                                        } else {
                                            followTail = true
                                            vm.sendUserMessage(composed.trim(), pendingImages)
                                            setInput("")
                                            pendingImages = emptyList()
                                            pendingTextFile = null
                                        }
                                    },
                            ) {
                                if (state.running) {
                                    Icon(
                                        Icons.Filled.Stop,
                                        contentDescription = "Stop",
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                } else {
                                    Icon(
                                        Icons.AutoMirrored.Filled.Send,
                                        contentDescription = "Send",
                                        tint = if (canSend) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Question sheet
    val pendingQuestion = state.pendingQuestion
    if (pendingQuestion != null) {
        var customText by remember(pendingQuestion.id) { mutableStateOf("") }
        var showCustom by remember(pendingQuestion.id) { mutableStateOf(false) }
        val focusRequester = remember(pendingQuestion.id) { FocusRequester() }
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { /* non-dismissible — must pick an option */ },
            sheetState = sheetState,
        ) {
            Column(
                Modifier
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 16.dp)
                    .imePadding()
                    // The IME inset already covers the nav bar; excluding it avoids counting it twice.
                    .windowInsetsPadding(WindowInsets.navigationBars.exclude(WindowInsets.ime)),
            ) {
                Text("Question", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(4.dp))
                Text(pendingQuestion.question, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                if (!showCustom) {
                    pendingQuestion.options.forEach { option ->
                        Surface(
                            onClick = { vm.answerQuestion(option) },
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        ) {
                            Text(
                                option,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                    Surface(
                        onClick = { showCustom = true },
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    ) {
                        Text(
                            "Other…",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LaunchedEffect(Unit) { focusRequester.requestFocus() }
                    TextButton(
                        onClick = { showCustom = false; customText = "" },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) {
                        Text("← Back to options")
                    }
                    TextField(
                        value = customText,
                        onValueChange = { customText = it },
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                        placeholder = { Text("Type your answer…") },
                        singleLine = false,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { vm.answerQuestion(customText) },
                        enabled = customText.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Submit") }
                }
            }
        }
    }

    // Approval sheet
    val approval = state.approval
    if (approval != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { vm.approve(ApprovalDecision.Deny) },
            sheetState = sheetState,
        ) {
            Column(
                Modifier
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp)
                    .windowInsetsPadding(WindowInsets.navigationBars),
            ) {
                Text("Tool call", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(4.dp))
                Text(approval.toolName, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        approval.argsJson,
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { vm.approve(ApprovalDecision.AllowOnce) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Allow") }
                    OutlinedButton(
                        onClick = { vm.approve(ApprovalDecision.AllowAlwaysThisSession) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Always allow") }
                    TextButton(
                        onClick = { vm.approve(ApprovalDecision.Deny) },
                    ) { Text("Deny") }
                }
            }
        }
    }

    viewingImage?.let { bmp ->
        Dialog(
            onDismissRequest = { viewingImage = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            ImageViewer(
                bitmap = bmp,
                onDismiss = { viewingImage = null },
            )
        }
    }
}

@Composable
private fun ImageViewer(bitmap: android.graphics.Bitmap, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val transformState = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(0.5f, 8f)
        offset += pan
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.95f)),
        contentAlignment = Alignment.Center,
    ) {
        ComposeImage(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
                .transformable(state = transformState)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > 1f) { scale = 1f; offset = androidx.compose.ui.geometry.Offset.Zero }
                            else scale = 2.5f
                        }
                    )
                },
            contentScale = ContentScale.Fit,
        )
        // Close button
        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50)),
        ) {
            Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
        }
        // Action buttons
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            IconButton(
                onClick = {
                    val saved = saveBitmapToGallery(context, bitmap)
                    android.widget.Toast.makeText(
                        context,
                        if (saved) "Saved to gallery" else "Failed to save",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                },
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50))
                    .size(48.dp),
            ) {
                Icon(Icons.Filled.Save, contentDescription = "Download", tint = Color.White)
            }
            IconButton(
                onClick = { shareBitmap(context, bitmap) },
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50))
                    .size(48.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Share", tint = Color.White)
            }
        }
    }
}

private fun saveBitmapToGallery(context: android.content.Context, bitmap: android.graphics.Bitmap): Boolean {
    val values = android.content.ContentValues().apply {
        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "edo_${System.currentTimeMillis()}.jpg")
        put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES)
    }
    val uri = context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: return false
    return runCatching {
        context.contentResolver.openOutputStream(uri)?.use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it)
        }
        true
    }.getOrDefault(false)
}

private fun shareBitmap(context: android.content.Context, bitmap: android.graphics.Bitmap) {
    val file = java.io.File(context.cacheDir, "edo_share_${System.currentTimeMillis()}.jpg")
    file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it) }
    val uri = androidx.core.content.FileProvider.getUriForFile(
        context, context.packageName + ".fileprovider", file
    )
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(android.content.Intent.createChooser(intent, "Share image"))
}

@Composable
private fun NoProjectPlaceholder(onOpenProjects: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                Icons.Filled.Folder,
                null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
            Spacer(Modifier.height(16.dp))
            Text("No project selected", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Create a project to define a workspace folder and start chatting.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onOpenProjects) { Text("Create project") }
        }
    }
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    msg: UiMessage,
    expandedIds: MutableMap<String, Boolean>,
    onImageClick: (android.graphics.Bitmap) -> Unit = {},
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    when (msg.role) {
        Role.User -> {
            val textBlocks = msg.blocks.filterIsInstance<Block.Text>()
            val imageBlocks = msg.blocks.filterIsInstance<Block.Image>()
            if (textBlocks.isEmpty() && imageBlocks.isEmpty()) return
            val (attachedFiles, text) = splitAttachedFiles(textBlocks.joinToString("\n") { it.text })
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (imgBlock in imageBlocks) {
                        val bytes = remember(imgBlock.base64) {
                            Base64.decode(imgBlock.base64, Base64.DEFAULT)
                        }
                        val bitmap = remember(bytes) {
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        }
                        if (bitmap != null) {
                            ComposeImage(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier
                                    .widthIn(max = 240.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onImageClick(bitmap) },
                                contentScale = ContentScale.FillWidth,
                            )
                        }
                    }
                    for (file in attachedFiles) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.InsertDriveFile,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(
                                        file.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 220.dp),
                                    )
                                    Text(
                                        "${file.chars} chars",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                                    )
                                }
                            }
                        }
                    }
                    if (text.isNotEmpty()) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.widthIn(max = 300.dp).combinedClickable(
                                onClick = {},
                                onLongClick = {
                                    clipboard.setText(AnnotatedString(text))
                                    android.widget.Toast
                                        .makeText(context, "Copied message", android.widget.Toast.LENGTH_SHORT)
                                        .show()
                                },
                            ),
                        ) {
                            Text(
                                text = text,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            }
        }

        Role.Assistant -> {
            val textBlocks = msg.blocks.filterIsInstance<Block.Text>()
            // Tool calls are rendered separately (see ToolGroup) so consecutive ones can fold together.
            if (textBlocks.isNotEmpty()) {
                AssistantBubble(
                    text = textBlocks.joinToString("\n\n") { it.text },
                    isStreaming = false,
                    expandedIds = expandedIds,
                )
            }
        }

        Role.System -> Unit
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AssistantBubble(text: String, isStreaming: Boolean, expandedIds: MutableMap<String, Boolean>) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth()) {
        // No bubble: assistant replies sit directly on the background, full width.
        Surface(
            color = Color.Transparent,
            modifier = Modifier.weight(1f).combinedClickable(
                onClick = {},
                onLongClick = {
                    clipboard.setText(AnnotatedString(text))
                    android.widget.Toast
                        .makeText(context, "Copied markdown", android.widget.Toast.LENGTH_SHORT)
                        .show()
                },
            ),
        ) {
            Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
                MarkdownText(text = text)
                if (isStreaming) {
                    Text(
                        text = "▌",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolCallCard(tc: PendingToolUi, expandedIds: MutableMap<String, Boolean>) {
    val expanded = expandedIds[tc.id] == true
    CompletedToolCard(
        name = tc.name,
        argsJson = tc.argsJson,
        result = tc.result,
        isError = tc.isError || tc.approved == false,
        expanded = expanded,
        onToggle = { expandedIds[tc.id] = !expanded },
    )
}

/** One row in the chat list: a text message, or a run of consecutive tool calls. */
private sealed interface ChatItem {
    val key: String

    data class Msg(val msg: UiMessage) : ChatItem {
        override val key get() = "msg-${msg.id}"
    }

    data class ToolGroup(val firstId: String, val uses: List<Block.ToolUse>) : ChatItem {
        override val key get() = "tools-$firstId"
    }
}

/**
 * A run of tool calls. While in progress each call is a faded line; once folded the
 * whole run is a single "Used N tools" line that expands back to the individual lines.
 */
@Composable
private fun ToolGroupView(
    group: ChatItem.ToolGroup,
    toolResults: Map<String, Block.ToolResult>,
    expandedIds: MutableMap<String, Boolean>,
    folded: Boolean,
) {
    val groupKey = group.key
    val groupExpanded = expandedIds[groupKey] == true
    Column(Modifier.animateContentSize()) {
        if (folded) {
            val failed = group.uses.count { toolResults[it.id]?.isError == true }
            val label = buildString {
                append("Used ${group.uses.size} tool")
                if (group.uses.size != 1) append("s")
                if (failed > 0) append(" · $failed failed")
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expandedIds[groupKey] = !groupExpanded }
                    .padding(horizontal = 4.dp, vertical = 4.dp)
                    .alpha(FADED_ALPHA),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Icon(
                    if (groupExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    null,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        if (!folded || groupExpanded) {
            for (t in group.uses) {
                val res = toolResults[t.id]
                val expanded = expandedIds[t.id] == true
                CompletedToolCard(
                    name = t.name,
                    argsJson = t.argsJson,
                    result = res?.content,
                    isError = res?.isError == true,
                    expanded = expanded,
                    onToggle = { expandedIds[t.id] = !expanded },
                )
            }
        }
    }
}

private const val FADED_ALPHA = 0.55f

/** A tool call as a faded single line; tap to expand its arguments and result. */
@Composable
private fun CompletedToolCard(
    name: String,
    argsJson: String,
    result: String?,
    isError: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable(onClick = onToggle)
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.alpha(if (isError) 0.9f else FADED_ALPHA),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                iconForTool(name, isError),
                null,
                modifier = Modifier.size(14.dp),
                tint = if (isError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            val (label, detail) = toolHeader(name, argsJson)
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            if (detail.isNotBlank()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
        AnimatedVisibility(visible = expanded) {
            ToolExpandedContent(name = name, argsJson = argsJson, result = result, isError = isError)
        }
    }
}

@Composable
private fun RetryingBanner(attempt: Int, maxAttempts: Int, cause: String? = null) {
    val transition = rememberInfiniteTransition(label = "retry-pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.5f, targetValue = 1f, label = "pulse",
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
    )
    var showCause by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.animateContentSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Schedule,
                    null,
                    modifier = Modifier.size(14.dp).alpha(alpha),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Retrying… ($attempt / $maxAttempts)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f),
                )
                if (cause != null) {
                    IconButton(
                        onClick = { showCause = !showCause },
                        modifier = Modifier.size(20.dp),
                    ) {
                        Icon(
                            if (showCause) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = "Show error",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
            AnimatedVisibility(visible = showCause && cause != null) {
                Text(
                    cause ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp).fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit, onRetry: (() -> Unit)? = null) {
    var expanded by remember { mutableStateOf(false) }
    val isLong = message.length > 120
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.animateContentSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Error,
                    null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (!expanded && isLong) message.take(120) + "…" else message,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                if (isLong) {
                    IconButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(
                            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = "Show full error",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
                if (onRetry != null) {
                    TextButton(onClick = onRetry, modifier = Modifier.height(28.dp)) {
                        Text("Retry", style = MaterialTheme.typography.labelSmall)
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.height(28.dp)) {
                    Text("×", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun TypingDots() {
    val transition = rememberInfiniteTransition(label = "typing")
    val alpha by transition.animateFloat(
        initialValue = 0.3f, targetValue = 1f, label = "pulse",
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
    )
    Icon(
        Icons.AutoMirrored.Filled.Send,
        contentDescription = "Sending",
        modifier = Modifier.alpha(alpha),
        tint = MaterialTheme.colorScheme.primary,
    )
}

private fun readImageAsBase64(resolver: ContentResolver, uri: Uri): Pair<String, String>? {
    val type = resolver.getType(uri) ?: "image/jpeg"
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
    return type to b64
}

private sealed interface Attachment {
    data class Image(val mediaType: String, val base64: String) : Attachment
    data class Text(val name: String, val content: String) : Attachment
}

private const val MAX_TEXT_ATTACHMENT_BYTES = 256 * 1024

// Read a picked document URI into either an Image (for image mimes) or a
// Text attachment (for text mimes, or any other file whose bytes decode as
// valid UTF-8 within the size cap). Returns null for unsupported types.
private fun readAttachment(resolver: ContentResolver, uri: Uri): Attachment? {
    val mime = resolver.getType(uri) ?: ""
    val name = queryDisplayName(resolver, uri) ?: "attachment"
    if (mime.startsWith("image/")) {
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        return Attachment.Image(mime, Base64.encodeToString(bytes, Base64.NO_WRAP))
    }
    val bytes = resolver.openInputStream(uri)?.use {
        it.readNBytes(MAX_TEXT_ATTACHMENT_BYTES)
    } ?: return null
    if (bytes.isEmpty()) return null
    val text = runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull() ?: return null
    // Treat as text if the mime declares so, if it is a structured-text
    // format, or if the bytes contain no NUL byte (reliable non-binary signal).
    val looksLikeText = mime.startsWith("text/") ||
        mime in setOf("application/json", "application/xml", "application/yaml",
                      "application/javascript", "application/toml") ||
        !bytes.contains(0)
    if (looksLikeText) return Attachment.Text(name, text)
    return null
}

private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
    return runCatching {
        resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()
}

@Composable
private fun AttachmentChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onRemove, modifier = Modifier.height(24.dp)) {
            Text("Remove", style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Create a content:// URI in our app's cacheDir/captures/ for the camera
 *  intent to write the photo to. */
private fun createCaptureUri(context: android.content.Context): Uri? {
    return runCatching {
        val dir = java.io.File(context.cacheDir, "captures").apply { mkdirs() }
        val file = java.io.File(dir, "photo_${System.currentTimeMillis()}.jpg")
        androidx.core.content.FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file,
        )
    }.getOrNull()
}

/** Map tool name + raw args JSON to (label, inline detail) for the chat card header.
 *  Works on partial/streaming JSON: when the full object can't be parsed yet,
 *  falls back to a regex extract so the filename appears as soon as the model
 *  has emitted the relevant key/value pair. */
internal fun toolHeader(name: String, argsJson: String): Pair<String, String> {
    val args = runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(argsJson) as kotlinx.serialization.json.JsonObject
    }.getOrNull()
    fun s(key: String): String? {
        (args?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.let { return it }
        return extractPartialString(argsJson, key)
    }
    val detail = when (name) {
        "read_file", "write_file", "edit_file", "delete_file" -> s("path") ?: ""
        "copy_file" -> {
            val src = s("source") ?: ""
            val dst = s("dest") ?: ""
            if (src.isBlank() && dst.isBlank()) "" else "$src → $dst"
        }
        "ls" -> s("path") ?: "/"
        "grep" -> {
            val pattern = s("pattern") ?: ""
            val path = s("path")
            if (path.isNullOrBlank()) "\"$pattern\"" else "\"$pattern\" in $path"
        }
        "http_request" -> {
            val method = s("method")?.uppercase() ?: "GET"
            val url = s("url") ?: ""
            "$method $url"
        }
        "web_search" -> s("query") ?: ""
        "load_skill" -> s("name") ?: ""
        "current_datetime" -> ""
        "calculator" -> s("expression") ?: ""
        else -> ""
    }
    val label = when (name) {
        "read_file" -> "Read"
        "write_file" -> "Write"
        "edit_file" -> "Edit"
        "delete_file" -> "Delete"
        "copy_file" -> "Copy"
        "ls" -> "List"
        "grep" -> "Grep"
        "http_request" -> "HTTP"
        "web_search" -> "Search"
        "load_skill" -> "Skill"
        "current_datetime" -> "Time"
        "calculator" -> "Calc"
        else -> name
    }
    return label to detail
}

/** Best-effort lookup of a string field inside a partial JSON object — handles
 *  the case where streaming hasn't finished yet so the object isn't valid JSON. */
internal fun extractPartialString(partial: String, field: String): String? {
    if (partial.isBlank()) return null
    val key = "\"" + Regex.escape(field) + "\""
    val regex = Regex(key + "\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)(?:\"|\\z)")
    val raw = regex.find(partial)?.groupValues?.getOrNull(1) ?: return null
    return raw
        .replace("\\n", "\n")
        .replace("\\t", "\t")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
}

internal fun iconForTool(name: String, isError: Boolean): androidx.compose.ui.graphics.vector.ImageVector = when {
    isError -> Icons.Filled.Error
    name == "read_file" -> Icons.AutoMirrored.Filled.MenuBook
    name == "write_file" -> Icons.Filled.Save
    name == "edit_file" -> Icons.Filled.Edit
    name == "delete_file" -> Icons.Filled.DeleteOutline
    name == "copy_file" -> Icons.Filled.ContentCopy
    name == "ls" -> Icons.Filled.Folder
    name == "grep" || name == "web_search" -> Icons.Filled.Search
    name == "http_request" -> Icons.Filled.Language
    name == "load_skill" -> Icons.Filled.AutoAwesome
    name == "current_datetime" -> Icons.Filled.Schedule
    name == "calculator" -> Icons.Filled.Add
    else -> Icons.Filled.CheckCircle
}

@Composable
private fun ToolExpandedContent(name: String, argsJson: String, result: String?, isError: Boolean) {
    fun strArg(key: String): String? {
        val args = runCatching {
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
                .parseToJsonElement(argsJson) as kotlinx.serialization.json.JsonObject
        }.getOrNull() ?: return null
        return (args[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    }

    Column(Modifier.padding(top = 8.dp)) {
        when (name) {
            "edit_file" -> {
                val oldStr = strArg("old_string")
                val newStr = strArg("new_string")
                if (oldStr != null && newStr != null) {
                    Text("Changes", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    DiffView(
                        oldStr = if (oldStr.lines().size > 30) oldStr.lines().take(30).joinToString("\n") + "\n…" else oldStr,
                        newStr = if (newStr.lines().size > 30) newStr.lines().take(30).joinToString("\n") + "\n…" else newStr,
                    )
                    Spacer(Modifier.height(8.dp))
                } else {
                    ArgsBlock(argsJson)
                }
                if (result != null) ResultBlock(result, isError)
            }
            "write_file" -> {
                val content = strArg("content")
                if (content != null) {
                    Text("Content", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    CodeBlock(content)
                } else {
                    ArgsBlock(argsJson)
                }
                if (result != null) {
                    Text(
                        result,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            "read_file" -> {
                // Path is shown in header; result is the file content
                if (result != null) ResultBlock(result, isError)
            }
            else -> {
                if (argsJson.isNotBlank() && argsJson != "{}") ArgsBlock(argsJson)
                if (result != null) ResultBlock(result, isError)
            }
        }
    }
}

@Composable
private fun DiffView(oldStr: String, newStr: String) {
    Column(Modifier.fillMaxWidth()) {
        oldStr.lines().forEach { line ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "- $line",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
                )
            }
        }
        newStr.lines().forEach { line ->
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "+ $line",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
                )
            }
        }
    }
}

@Composable
private fun ArgsBlock(argsJson: String) {
    val args = runCatching {
        kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
            .parseToJsonElement(argsJson) as kotlinx.serialization.json.JsonObject
    }.getOrNull()
    Text("Arguments", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.padding(top = 2.dp, bottom = 6.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(8.dp)) {
            if (args != null) {
                for ((k, v) in args) {
                    val str = (v as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: v.toString()
                    val display = if (str.length > 120) str.take(120) + "…" else str
                    Row {
                        Text(
                            "$k: ",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            display,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                Text(argsJson, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun CodeBlock(content: String) {
    val display = if (content.length > 2000) content.take(2000) + "\n…[truncated]" else content
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.padding(bottom = 6.dp).fillMaxWidth(),
    ) {
        Text(
            display,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(8.dp),
        )
    }
}

@Composable
private fun ResultBlock(result: String, isError: Boolean) {
    val cap = if (isError) 8000 else 1200
    val preview = if (result.length > cap) result.take(cap) + "\n…[truncated, ${result.length - cap} more chars]" else result
    Text(
        if (isError) "Error" else "Result",
        style = MaterialTheme.typography.labelSmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Surface(
        color = if (isError) MaterialTheme.colorScheme.error.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.padding(top = 4.dp).fillMaxWidth(),
    ) {
        Text(
            preview,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(8.dp).fillMaxWidth(),
        )
    }
}
