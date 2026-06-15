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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.LocalContext
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
) {
    val context = LocalContext.current
    val vm: ChatViewModel = viewModel(
        factory = chatVmFactory(container, context.applicationContext as EdoApp)
    )
    val state by vm.state.collectAsState()
    var input by remember { mutableStateOf("") }
    var pendingImage by remember { mutableStateOf<Pair<String, String>?>(null) }
    val expandedToolIds = remember { mutableStateMapOf<String, Boolean>() }

    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) pendingImage = readImageAsBase64(context.contentResolver, uri)
    }

    // --- Message list state (hoisted so bottomBar LaunchedEffect can share it) ---
    val listState = rememberLazyListState()

    val totalItems = state.messages.size +
            (if (state.streamingText.isNotEmpty()) 1 else 0) +
            (if (state.pendingToolCalls.isNotEmpty()) 1 else 0)
    val isImeVisible = WindowInsets.isImeVisible

    // Scroll to bottom when new content arrives or keyboard opens
    LaunchedEffect(totalItems, isImeVisible) {
        if (totalItems > 0) {
            if (isImeVisible) kotlinx.coroutines.delay(250) // wait for keyboard animation
            listState.animateScrollToItem(totalItems - 1)
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
                        IconButton(onClick = onOpenThreads) {
                            Icon(Icons.Filled.History, contentDescription = "Chat history")
                        }
                        IconButton(
                            onClick = { vm.newChat() },
                            enabled = !state.running && (state.currentThread != null || state.messages.isNotEmpty()),
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
                        items(state.messages, key = { it.id }) { msg ->
                            MessageBubble(msg, expandedToolIds)
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
                        state.error?.let { err ->
                            item("error") {
                                ErrorBanner(err, onDismiss = { vm.dismissError() })
                            }
                        }
                    }
                }
            }

            // --- Input bar pinned to bottom of column ---
            Surface(tonalElevation = 2.dp) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    if (pendingImage != null) {
                        Row(
                            modifier = Modifier.padding(bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.Add,
                                null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Image attached", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            TextButton(
                                onClick = { pendingImage = null },
                                modifier = Modifier.height(24.dp),
                            ) { Text("Remove", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                    Row(verticalAlignment = Alignment.Bottom) {
                        IconButton(
                            onClick = {
                                pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                            modifier = Modifier.padding(bottom = 2.dp),
                        ) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = "Attach image",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Message…") },
                            maxLines = 6,
                            shape = RoundedCornerShape(24.dp),
                        )
                        IconButton(
                            enabled = !state.running && !state.needsProject && (input.isNotBlank() || pendingImage != null),
                            onClick = {
                                vm.sendUserMessage(input.trim(), pendingImage)
                                input = ""
                                pendingImage = null
                            },
                            modifier = Modifier.padding(bottom = 2.dp),
                        ) {
                            if (state.running) {
                                TypingDots()
                            } else {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Send",
                                    tint = if (!state.needsProject && (input.isNotBlank() || pendingImage != null))
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
                    .navigationBarsPadding(),
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


@Composable
private fun MessageBubble(msg: UiMessage, expandedIds: MutableMap<String, Boolean>) {
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
                            CompletedToolCard(
                                name = t.name,
                                argsJson = t.argsJson,
                                result = null,
                                isError = false,
                                expanded = expanded,
                                onToggle = { expandedIds[t.id] = !expanded },
                            )
                        }
                    }
                }
            }
        }

        Role.System -> Unit
    }
}

@Composable
private fun AssistantBubble(text: String, isStreaming: Boolean, expandedIds: MutableMap<String, Boolean>) {
    Row(Modifier.fillMaxWidth()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 18.dp),
        ) {
            Text(
                text = if (isStreaming) "$text▌" else text,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
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
                            maxLines = 1,
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
                        Text("Result", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val preview = if (result.length > 600) result.take(600) + "\n…" else result
                        Text(
                            preview,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
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
    val type = resolver.getType(uri) ?: "image/png"
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
    return type to b64
}

/** Map tool name + raw args JSON to (label, inline detail) for the chat card header. */
internal fun toolHeader(name: String, argsJson: String): Pair<String, String> {
    val args = runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(argsJson) as kotlinx.serialization.json.JsonObject
    }.getOrNull()
    fun s(key: String): String? =
        (args?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    val detail = when (name) {
        "read_file", "write_file", "edit_file" -> s("path") ?: ""
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
        else -> ""
    }
    val label = when (name) {
        "read_file" -> "Read"
        "write_file" -> "Write"
        "edit_file" -> "Edit"
        "ls" -> "List"
        "grep" -> "Grep"
        "http_request" -> "HTTP"
        "load_skill" -> "Skill"
        else -> name
    }
    return label to detail
}

internal fun iconForTool(name: String, isError: Boolean): androidx.compose.ui.graphics.vector.ImageVector = when {
    isError -> Icons.Filled.Error
    name == "read_file" -> Icons.AutoMirrored.Filled.MenuBook
    name == "write_file" -> Icons.Filled.Save
    name == "edit_file" -> Icons.Filled.Edit
    name == "ls" -> Icons.Filled.Folder
    name == "grep" -> Icons.Filled.Search
    name == "http_request" -> Icons.Filled.Language
    name == "load_skill" -> Icons.Filled.AutoAwesome
    else -> Icons.Filled.CheckCircle
}
