package com.edo.app.ui.schedules

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.edo.app.AppContainer
import com.edo.app.data.ScheduleEntity
import com.edo.app.data.usage
import com.edo.app.data.totalUsage
import com.edo.app.schedule.MIN_INTERVAL_MINUTES
import com.edo.app.schedule.ScheduleKind
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val STAMP = DateTimeFormatter.ofPattern("d MMM HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchedulesScreen(container: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val projectId by container.activeProjectId.collectAsState()
    val flow = remember(projectId) {
        if (projectId > 0) container.db.schedules().observeForProject(projectId) else flowOf(emptyList())
    }
    val schedules by flow.collectAsState(initial = emptyList())
    var projectName by remember { mutableStateOf("") }
    LaunchedEffect(projectId) {
        projectName = container.db.projects().getById(projectId)?.name.orEmpty()
    }
    var editing by remember { mutableStateOf<ScheduleEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<ScheduleEntity?>(null) }
    var expandedId by rememberSaveable { mutableLongStateOf(-1L) }
    val runningIds by container.scheduler.running.collectAsState()
    // Re-checked on each recomposition so the banner clears after returning from system settings.
    val exactOk = container.scheduler.canScheduleExact()

    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Schedules", style = MaterialTheme.typography.titleLarge)
                        if (projectName.isNotBlank()) {
                            Text(
                                projectName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { ensureNotificationPermission(); creating = true }, enabled = projectId > 0) {
                        Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("New")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!exactOk) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Exact alarms are off, so runs may be late.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                    .setData(Uri.parse("package:${context.packageName}"))
                            )
                        }) { Text("Allow") }
                    }
                }
            }
            if (schedules.isEmpty()) {
                Text(
                    if (projectId > 0) "No schedules yet. Tap New to create one."
                    else "Select a project first.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(schedules, key = { it.id }) { s ->
                        ScheduleCard(
                            container, s,
                            running = s.id in runningIds,
                            expanded = expandedId == s.id,
                            onToggleExpand = { expandedId = if (expandedId == s.id) -1L else s.id },
                            onEdit = { editing = s },
                            onToggle = { on -> scope.launch { container.scheduler.save(s.copy(enabled = on)) } },
                            onRunNow = {
                                ensureNotificationPermission()
                                // App scope, not the screen's: leaving this screen must not cancel the run
                                // or drop its notification.
                                container.appScope.launch { container.scheduler.run(s) }
                            },
                            onOpenThread = { id ->
                                container.setActiveThread(id)
                                onBack()
                            },
                            onDelete = { confirmDelete = s },
                        )
                    }
                }
            }
        }
    }

    if (creating || editing != null) {
        val existing = editing
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { creating = false; editing = null },
            sheetState = sheetState,
        ) {
            ScheduleEditor(
                existing = existing,
                onSave = { draft ->
                    scope.launch {
                        container.scheduler.save(
                            if (existing != null) draft.copy(id = existing.id, projectId = existing.projectId)
                            else draft.copy(projectId = projectId)
                        )
                    }
                    creating = false
                    editing = null
                },
                onCancel = { creating = false; editing = null },
            )
        }
    }

    confirmDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete schedule?") },
            text = { Text("\"${s.title}\" will stop running. Chats it already created are kept.") },
            confirmButton = {
                Button(onClick = {
                    scope.launch { container.scheduler.delete(s) }
                    confirmDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

private fun isFailure(status: String) = status == "FAILED" || status == "TIMEOUT"

@Composable
private fun statusColor(status: String, enabled: Boolean = true): Color = when {
    !enabled -> MaterialTheme.colorScheme.outline
    isFailure(status) -> MaterialTheme.colorScheme.error
    status == "OK" -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.outlineVariant
}

/** One schedule as a card; tapping expands the prompt, actions and run history in place. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleCard(
    container: AppContainer,
    s: ScheduleEntity,
    running: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onRunNow: () -> Unit,
    onOpenThread: (Long) -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (s.enabled) MaterialTheme.colorScheme.surfaceContainerHigh
            else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.animateContentSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand)
                    .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (running) {
                    CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 2.dp)
                } else {
                    Box(Modifier.size(10.dp).background(statusColor(s.lastStatus, s.enabled), CircleShape))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        s.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (s.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val next = when {
                        running -> "running…"
                        !s.enabled -> "off"
                        s.nextRunAt > 0 -> "next ${stamp(s.nextRunAt)}"
                        else -> ""
                    }
                    val failed = !running && isFailure(s.lastStatus)
                    Text(
                        listOf(cadence(s), next, if (failed) "last run failed" else "").filter { it.isNotEmpty() }.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Pill(kindLabel(s), if (s.enabled) kindColor(s) else MaterialTheme.colorScheme.outline)
                Switch(
                    checked = s.enabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.padding(start = 4.dp).scale(0.8f),
                )
            }
            if (expanded) {
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column {
                        Text("Prompt", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Text(s.prompt, style = MaterialTheme.typography.bodyMedium, maxLines = 8, overflow = TextOverflow.Ellipsis)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = onRunNow, enabled = !running) {
                            Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Run now")
                        }
                        OutlinedButton(onClick = onEdit) {
                            Icon(Icons.Filled.Edit, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Edit")
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    RunHistory(container, s, onOpenThread)
                }
            }
        }
    }
}

@Composable
private fun Pill(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.12f), shape = MaterialTheme.shapes.small) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ScheduleEditor(
    existing: ScheduleEntity?,
    onSave: (ScheduleEntity) -> Unit,
    onCancel: () -> Unit,
) {
    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    var prompt by remember { mutableStateOf(existing?.prompt.orEmpty()) }
    var kind by remember { mutableStateOf(existing?.let { ScheduleKind.from(it.kind) } ?: ScheduleKind.DAILY) }
    var minuteOfDay by remember { mutableIntStateOf(existing?.minuteOfDay ?: (9 * 60)) }
    var daysMask by remember { mutableIntStateOf(existing?.daysMask ?: 0b0011111) }
    var intervalText by remember { mutableStateOf((existing?.intervalMinutes ?: 60).toString()) }
    var dayText by remember { mutableStateOf((existing?.dayOfMonth ?: 1).toString()) }
    // For ONCE: the target instant; defaults to the next top of the hour.
    var onceAt by remember {
        mutableLongStateOf(
            existing?.takeIf { it.kind == ScheduleKind.ONCE.name && it.nextRunAt > System.currentTimeMillis() }?.nextRunAt
                ?: (System.currentTimeMillis() / 3_600_000L + 1) * 3_600_000L
        )
    }
    var pickTime by remember { mutableStateOf(false) }
    var pickDate by remember { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()

    val interval = intervalText.toIntOrNull() ?: 0
    val dayOfMonth = dayText.toIntOrNull() ?: 0
    val valid = prompt.isNotBlank() && when (kind) {
        ScheduleKind.MONTHLY -> dayOfMonth in 1..31
        ScheduleKind.ONCE -> onceAt > System.currentTimeMillis()
        ScheduleKind.INTERVAL -> interval >= MIN_INTERVAL_MINUTES
        ScheduleKind.WEEKLY -> daysMask != 0
        ScheduleKind.DAILY -> true
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 16.dp)
            .imePadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(if (existing == null) "New schedule" else "Edit schedule", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = title, onValueChange = { title = it },
            label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = prompt, onValueChange = { prompt = it },
            label = { Text("Prompt") }, minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((k, label) in listOf(
                ScheduleKind.ONCE to "Once", ScheduleKind.INTERVAL to "Every…",
                ScheduleKind.DAILY to "Daily", ScheduleKind.WEEKLY to "Weekly",
                ScheduleKind.MONTHLY to "Monthly",
            )) {
                FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(label) })
            }
        }
        Spacer(Modifier.height(8.dp))
        when (kind) {
            ScheduleKind.ONCE -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val at = Instant.ofEpochMilli(onceAt).atZone(zone)
                OutlinedButton(onClick = { pickDate = true }) { Text(at.format(DateTimeFormatter.ofPattern("EEE d MMM"))) }
                OutlinedButton(onClick = { pickTime = true }) { Text(at.format(DateTimeFormatter.ofPattern("HH:mm"))) }
            }
            ScheduleKind.INTERVAL -> OutlinedTextField(
                value = intervalText,
                onValueChange = { v -> intervalText = v.filter { it.isDigit() }.take(5) },
                label = { Text("Minutes (at least $MIN_INTERVAL_MINUTES)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                isError = interval < MIN_INTERVAL_MINUTES,
            )
            ScheduleKind.DAILY -> OutlinedButton(onClick = { pickTime = true }) { Text("At ${formatMinute(minuteOfDay)}") }
            ScheduleKind.MONTHLY -> Column {
                OutlinedTextField(
                    value = dayText,
                    onValueChange = { v -> dayText = v.filter { it.isDigit() }.take(2) },
                    label = { Text("Day of month (1–31; short months use their last day)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    isError = dayOfMonth !in 1..31,
                )
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = { pickTime = true }) { Text("At ${formatMinute(minuteOfDay)}") }
            }
            ScheduleKind.WEEKLY -> Column {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (day in DayOfWeek.values()) {
                        val bit = 1 shl (day.value - 1)
                        FilterChip(
                            selected = daysMask and bit != 0,
                            onClick = { daysMask = daysMask xor bit },
                            label = { Text(day.getDisplayName(TextStyle.SHORT, Locale.getDefault())) },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = { pickTime = true }) { Text("At ${formatMinute(minuteOfDay)}") }
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            Button(
                enabled = valid,
                onClick = {
                    onSave(
                        ScheduleEntity(
                            projectId = existing?.projectId ?: 0,
                            title = title.trim().ifBlank { prompt.trim().take(32) },
                            prompt = prompt.trim(),
                            kind = kind.name,
                            minuteOfDay = minuteOfDay,
                            daysMask = daysMask,
                            intervalMinutes = interval.coerceAtLeast(MIN_INTERVAL_MINUTES),
                            dayOfMonth = dayOfMonth.coerceIn(1, 31),
                            enabled = true,
                            nextRunAt = if (kind == ScheduleKind.ONCE) onceAt else 0,
                            lastRunAt = existing?.lastRunAt ?: 0,
                            lastStatus = existing?.lastStatus.orEmpty(),
                            lastThreadId = existing?.lastThreadId ?: 0,
                            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                        )
                    )
                },
            ) { Text("Save") }
        }
    }

    if (pickTime) {
        val current = if (kind == ScheduleKind.ONCE) {
            Instant.ofEpochMilli(onceAt).atZone(zone).let { it.hour * 60 + it.minute }
        } else minuteOfDay
        val state = rememberTimePickerState(initialHour = current / 60, initialMinute = current % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            confirmButton = {
                TextButton(onClick = {
                    val m = state.hour * 60 + state.minute
                    if (kind == ScheduleKind.ONCE) {
                        val date = Instant.ofEpochMilli(onceAt).atZone(zone).toLocalDate()
                        onceAt = date.atTime(LocalTime.of(m / 60, m % 60)).atZone(zone).toInstant().toEpochMilli()
                    } else minuteOfDay = m
                    pickTime = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text("Cancel") } },
            text = { TimePicker(state = state) },
        )
    }
    if (pickDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = Instant.ofEpochMilli(onceAt).atZone(zone).toLocalDate()
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { picked ->
                        // The picker reports UTC midnight of the chosen day; keep the existing time of day.
                        val date: LocalDate = Instant.ofEpochMilli(picked).atZone(ZoneOffset.UTC).toLocalDate()
                        val time = Instant.ofEpochMilli(onceAt).atZone(zone).toLocalTime()
                        onceAt = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
                    }
                    pickDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }
}

private fun ordinal(n: Int): String {
    val suffix = if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
    return "$n$suffix"
}

private fun formatMinute(m: Int) ="%02d:%02d".format(m / 60, m % 60)

private fun stamp(epoch: Long) =
    STAMP.format(Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()))

private fun kindLabel(s: ScheduleEntity) = when (ScheduleKind.from(s.kind)) {
    ScheduleKind.ONCE -> "Once"
    ScheduleKind.INTERVAL -> "Interval"
    ScheduleKind.DAILY -> "Daily"
    ScheduleKind.WEEKLY -> "Weekly"
    ScheduleKind.MONTHLY -> "Monthly"
}

@Composable
private fun kindColor(s: ScheduleEntity): Color = when (ScheduleKind.from(s.kind)) {
    ScheduleKind.DAILY -> MaterialTheme.colorScheme.primary
    ScheduleKind.WEEKLY -> MaterialTheme.colorScheme.tertiary
    ScheduleKind.MONTHLY -> MaterialTheme.colorScheme.secondary
    ScheduleKind.INTERVAL -> Color(0xFF2E7D6B)
    ScheduleKind.ONCE -> MaterialTheme.colorScheme.outline
}

/** The schedule's timing without the kind word, which the kind chip already shows. */
private fun cadence(s: ScheduleEntity): String {
    return when (ScheduleKind.from(s.kind)) {
        ScheduleKind.ONCE -> ""
        ScheduleKind.INTERVAL ->
            if (s.intervalMinutes % 60 == 0) "every ${s.intervalMinutes / 60} h" else "every ${s.intervalMinutes} min"
        ScheduleKind.DAILY -> formatMinute(s.minuteOfDay)
        ScheduleKind.MONTHLY -> "the ${ordinal(s.dayOfMonth)}, ${formatMinute(s.minuteOfDay)}"
        ScheduleKind.WEEKLY -> {
            val days = when (s.daysMask) {
                0b0011111 -> "Weekdays"
                0b1100000 -> "Weekends"
                else -> DayOfWeek.values().filter { s.daysMask and (1 shl (it.value - 1)) != 0 }
                    .joinToString(", ") { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
            }
            "$days ${formatMinute(s.minuteOfDay)}"
        }
    }
}

private fun lastRunLabel(s: ScheduleEntity): String = when {
    s.lastStatus.isEmpty() && s.lastRunAt == 0L -> ""
    s.lastStatus == "SKIPPED" -> "Last run skipped"
    s.lastRunAt == 0L -> s.lastStatus.lowercase().replaceFirstChar { it.uppercase() }
    else -> "Last run ${stamp(s.lastRunAt)} · ${s.lastStatus.lowercase()}"
}

private fun durationLabel(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s < 60) "${s}s" else "${s / 60}m ${s % 60}s"
}

/** The last runs of [s], newest first, with lifetime usage; tapping one opens its chat if it still exists. */
@Composable
private fun RunHistory(container: AppContainer, s: ScheduleEntity, onOpenThread: (Long) -> Unit) {
    val runs by remember(s.id) { container.db.scheduleRuns().observeForSchedule(s.id) }
        .collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Run history",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (!s.totalUsage().isEmpty) {
                Text(
                    "All runs: ${s.totalUsage().label()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (runs.isEmpty()) {
            Text(
                "No runs yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        runs.forEach { r ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = r.threadId > 0) {
                        scope.launch {
                            if (container.db.threads().getById(r.threadId) != null) onOpenThread(r.threadId)
                        }
                    }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).background(statusColor(r.status), CircleShape))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(stamp(r.startedAt), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        r.status.lowercase() + " · " + durationLabel(r.durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isFailure(r.status)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!r.usage().isEmpty) {
                    Text(
                        r.usage().label(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
