package com.edo.app.ui.chat

import android.content.ContentResolver
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Image
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
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
    var input by remember { mutableStateOf("") }
    var pendingImage by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pendingTextFile by remember { mutableStateOf<Pair<String, String>?>(null) }
    val expandedToolIds = remember { mutableStateMapOf<String, Boolean>() }

    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val attachment = readAttachment(context.contentResolver, uri)
            when (attachment) {
                is Attachment.Image -> {
                    pendingImage = attachment.mediaType to attachment.base64
                }
                is Attachment.Text -> {
                    pendingTextFile = attachment.name to attachment.content
                }
                null -> android.widget.Toast
                    .makeText(context, "Unsupported file type", android.widget.Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    // Camera capture: write to a cacheDir file via FileProvider, then read it
    // back as base64 once the camera app reports success.
    var pendingCaptureUri by remember { mutableStateOf<Uri?>(null) }
    val takePhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = pendingCaptureUri
        if (success && uri != null) {
            pendingImage = readImageAsBase64(context.contentResolver, uri)
        }
        pendingCaptureUri = null
    }

    // --- Message list state (hoisted so bottomBar LaunchedEffect can share it) ---
    val listState = rememberLazyListState()

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
    val visibleMessages = remember(state.messages) {
        state.messages.filter { msg ->
            msg.role != Role.User || msg.blocks.any { it !is Block.ToolResult }
        }
    }

    val totalItems = visibleMessages.size +
            (if (state.streamingText.isNotEmpty()) 1 else 0) +
            (if (state.pendingToolCalls.isNotEmpty()) 1 else 0)
    val isImeVisible = WindowInsets.isImeVisible
    val streamLen = state.streamingText.length

    // Scroll to bottom on new items, keyboard opens, or while text streams in.
    LaunchedEffect(totalItems, isImeVisible) {
        if (totalItems > 0) {
            if (isImeVisible) kotlinx.coroutines.delay(250) // wait for keyboard animation
            listState.animateScrollToItem(totalItems - 1)
        }
    }
    // Snap to the bottom as streaming text grows. Use scrollToItem (not animated)
    // so each delta doesn't queue up a slow animation and lag behind the text.
    LaunchedEffect(streamLen) {
        if (totalItems > 0 && streamLen > 0) {
            listState.scrollToItem(totalItems - 1, Int.MAX_VALUE / 2)
        }
    }

    Scaffold(
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
                        val subtitle = state.currentThread?.title
                            ?: if (state.currentProject != null) "New chat" else null
                        if (subtitle != null) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    if (state.currentProject != null) {
                        IconButton(onClick = onOpenFiles) {
                            Icon(Icons.Filled.FolderOpen, contentDescription = "Files")
                        }
                        IconButton(onClick = onOpenThreads) {
                            Icon(Icons.Filled.History, contentDescription = "Chat history")
                        }
                        IconButton(
                            onClick = { vm.newChat() },
                            enabled = state.running || state.currentThread != null || state.messages.isNotEmpty(),
                        ) {
                            Icon(Icons.Filled.Edit, contentDescription = "New chat")
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
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
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 12.dp,
                            vertical = 8.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(visibleMessages, key = { it.id }) { msg ->
                            MessageBubble(msg, expandedToolIds, toolResults)
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
                                RetryingBanner(attempt = attempt, maxAttempts = com.edo.app.agent.Agent.MAX_LLM_RETRIES)
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
                }
            }

            // --- Input bar pinned to bottom of column ---
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(
                            WindowInsets.navigationBars.exclude(WindowInsets.ime)
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    if (pendingImage != null) {
                        AttachmentChip(
                            icon = Icons.Filled.Image,
                            label = "Image attached",
                            onRemove = { pendingImage = null },
                        )
                    }
                    pendingTextFile?.let { (filename, content) ->
                        AttachmentChip(
                            icon = Icons.AutoMirrored.Filled.InsertDriveFile,
                            label = "$filename · ${content.length} chars",
                            onRemove = { pendingTextFile = null },
                        )
                    }
                    Row(verticalAlignment = Alignment.Bottom) {
                        IconButton(
                            onClick = { pickFile.launch(arrayOf("*/*")) },
                            modifier = Modifier.padding(bottom = 2.dp),
                        ) {
                            Icon(
                                Icons.Filled.AttachFile,
                                contentDescription = "Attach file",
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
                            value = input,
                            onValueChange = { input = it },
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
                        val hasContent = input.isNotBlank() || pendingImage != null || pendingTextFile != null
                        IconButton(
                            enabled = if (state.running) true else !state.needsProject && hasContent,
                            onClick = {
                                if (state.running) {
                                    vm.cancelAgent()
                                } else {
                                    val composed = buildString {
                                        pendingTextFile?.let { (filename, content) ->
                                            append("Attached file `").append(filename).append("`:\n")
                                            append("```\n").append(content).append("\n```\n\n")
                                        }
                                        append(input.trim())
                                    }
                                    vm.sendUserMessage(composed.trim(), pendingImage)
                                    input = ""
                                    pendingImage = null
                                    pendingTextFile = null
                                }
                            },
                            modifier = Modifier.padding(bottom = 2.dp),
                        ) {
                            if (state.running) {
                                Icon(
                                    Icons.Filled.Stop,
                                    contentDescription = "Stop",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Send",
                                    tint = if (!state.needsProject && hasContent)
                                        MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
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
                    .padding(bottom = 32.dp)
                    .imePadding()
                    .windowInsetsPadding(WindowInsets.navigationBars),
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
    toolResults: Map<String, Block.ToolResult>,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    when (msg.role) {
        Role.User -> {
            val textBlocks = msg.blocks.filterIsInstance<Block.Text>()
            val imageBlocks = msg.blocks.filterIsInstance<Block.Image>()
            if (textBlocks.isEmpty() && imageBlocks.isEmpty()) return
            val text = buildString {
                if (imageBlocks.isNotEmpty()) append("[image] ")
                append(textBlocks.joinToString("\n") { it.text })
            }.trim()
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp),
                    tonalElevation = 1.dp,
                    modifier = Modifier.combinedClickable(
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
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        Role.Assistant -> {
            val textBlocks = msg.blocks.filterIsInstance<Block.Text>()
            val toolUses = msg.blocks.filterIsInstance<Block.ToolUse>()
            Column {
                if (textBlocks.isNotEmpty()) {
                    AssistantBubble(
                        text = textBlocks.joinToString("\n\n") { it.text },
                        isStreaming = false,
                        expandedIds = expandedIds,
                    )
                }
                if (toolUses.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (t in toolUses) {
                            val expanded = expandedIds[t.id] == true
                            val res = toolResults[t.id]
                            CompletedToolCard(
                                name = t.name,
                                argsJson = t.argsJson,
                                result = res?.content,
                                isError = res?.isError == true,
                                expanded = expanded,
                                onToggle = { expandedIds[t.id] = !expanded },
                                statusColor = when {
                                    res?.isError == true -> MaterialTheme.colorScheme.errorContainer
                                    res != null -> MaterialTheme.colorScheme.tertiaryContainer
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                },
                            )
                        }
                    }
                }
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
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 18.dp),
            modifier = Modifier.combinedClickable(
                onClick = {},
                onLongClick = {
                    clipboard.setText(AnnotatedString(text))
                    android.widget.Toast
                        .makeText(context, "Copied markdown", android.widget.Toast.LENGTH_SHORT)
                        .show()
                },
            ),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                MarkdownText(text = text)
                if (isStreaming) {
                    Text(
                        text = "▌",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun ToolCallCard(tc: PendingToolUi, expandedIds: MutableMap<String, Boolean>) {
    val expanded = expandedIds[tc.id] == true
    val statusColor = when {
        tc.isError -> MaterialTheme.colorScheme.errorContainer
        tc.result != null -> MaterialTheme.colorScheme.tertiaryContainer
        tc.approved == false -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    CompletedToolCard(
        name = tc.name,
        argsJson = tc.argsJson,
        result = tc.result,
        isError = tc.isError || tc.approved == false,
        expanded = expanded,
        onToggle = { expandedIds[tc.id] = !expanded },
        statusColor = statusColor,
    )
}

@Composable
private fun CompletedToolCard(
    name: String,
    argsJson: String,
    result: String?,
    isError: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    statusColor: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    Surface(
        color = statusColor,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.animateContentSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    iconForTool(name, isError),
                    null,
                    modifier = Modifier.size(14.dp),
                    tint = if (isError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.tertiary,
                )
                Spacer(Modifier.width(6.dp))
                val (label, detail) = toolHeader(name, argsJson)
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    if (detail.isNotBlank()) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            detail,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(top = 8.dp)) {
                    if (argsJson.isNotBlank() && argsJson != "{}") {
                        Text("Arguments", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            argsJson,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
                        )
                    }
                    if (result != null) {
                        val resultLabel = if (isError) "Error" else "Result"
                        val resultColor = if (isError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                        Text(
                            resultLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = resultColor,
                        )
                        // Errors are shown in full so the model's hint text isn't truncated;
                        // successful results stay capped to keep large file dumps readable.
                        val cap = if (isError) 8000 else 1200
                        val preview = if (result.length > cap) result.take(cap) + "\n…[truncated, " + (result.length - cap) + " more chars]" else result
                        Surface(
                            color = if (isError) MaterialTheme.colorScheme.error.copy(alpha = 0.10f)
                            else MaterialTheme.colorScheme.surfaceContainerHighest,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            Text(
                                preview,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isError) MaterialTheme.colorScheme.onErrorContainer
                                else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(8.dp).fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RetryingBanner(attempt: Int, maxAttempts: Int) {
    val transition = rememberInfiniteTransition(label = "retry-pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.5f, targetValue = 1f, label = "pulse",
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
    )
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
            )
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit, onRetry: (() -> Unit)? = null) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Error,
                null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
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
        "load_skill" -> s("name") ?: ""
        "current_datetime" -> ""
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
        "load_skill" -> "Skill"
        "current_datetime" -> "Time"
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
    name == "grep" -> Icons.Filled.Search
    name == "http_request" -> Icons.Filled.Language
    name == "load_skill" -> Icons.Filled.AutoAwesome
    name == "current_datetime" -> Icons.Filled.Schedule
    else -> Icons.Filled.CheckCircle
}
