package com.edo.app.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.edo.app.AppContainer
import com.edo.app.data.MessageStamp
import com.edo.app.data.ScheduleRunEntity
import com.edo.app.data.ThreadEntity
import com.edo.app.data.UsageEventEntity
import com.edo.app.data.UsageTotals
import com.edo.app.data.formatCost
import com.edo.app.data.formatTokens
import com.edo.app.data.usage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

private const val DAY_MS = 86_400_000L
private val TOOL_NAME = Regex("\"kind\":\"tool_use\",\"id\":\"[^\"]*\",\"name\":\"([^\"]+)\"")

/** Tokens, cost, chats, messages, tools and schedule runs across every project. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(container: AppContainer, onBack: () -> Unit, onOpenChat: () -> Unit) {
    val threads by container.db.threads().observeAll().collectAsState(initial = emptyList())
    val projects by container.db.projects().observeAll().collectAsState(initial = emptyList())
    val events by container.db.usageEvents().observeAll().collectAsState(initial = emptyList())
    val runs by container.db.scheduleRuns().observeAll().collectAsState(initial = emptyList())

    // Message-derived numbers need a scan of the message table, so keep them off the main thread.
    var messageStats by remember { mutableStateOf(MessageStats()) }
    LaunchedEffect(threads.size, threads.maxOfOrNull { it.lastUpdatedAt }) {
        messageStats = withContext(Dispatchers.IO) {
            val dao = container.db.messages()
            val stamps = dao.stamps()
            val tools = HashMap<String, Int>()
            for (json in dao.toolCallJson()) {
                for (m in TOOL_NAME.findAll(json)) tools.merge(m.groupValues[1], 1, Int::plus)
            }
            MessageStats(dao.countAll(), stamps, tools.entries.sortedByDescending { it.value }.map { it.key to it.value })
        }
    }

    // Schedule runs only know the schedule id; look the titles up for display.
    var scheduleTitles by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    LaunchedEffect(runs.size) {
        scheduleTitles = withContext(Dispatchers.IO) {
            container.db.schedules().listAll().associate { it.id to it.title }
        }
    }
    var period by remember { mutableStateOf(Period.Day) }
    val now = System.currentTimeMillis()
    var groupBy by remember { mutableStateOf(GroupBy.Total) }
    val projectNames = remember(projects) { projects.associate { it.id to it.name } }
    fun projectName(id: Long) = projectNames[id] ?: "Deleted project"
    val costChart = remember(events, period, groupBy, projectNames) {
        stack(period, now, groupBy, events.map {
            Sample(it.createdAt, it.costUsd, if (groupBy == GroupBy.Project) projectName(it.projectId) else it.model.ifBlank { "unknown" })
        })
    }
    val messageChart = remember(messageStats.stamps, period, groupBy, projectNames) {
        // Only assistant messages carry a model, so the model view counts replies alone.
        val source = if (groupBy == GroupBy.Model) messageStats.stamps.filter { it.role == "Assistant" } else messageStats.stamps
        stack(period, now, groupBy, source.map {
            Sample(it.createdAt, 1.0, if (groupBy == GroupBy.Project) projectName(it.projectId) else it.model.ifBlank { "unknown (older)" })
        })
    }

    val stats = remember(threads, projects, events, runs) { compute(threads, projects, events, runs) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Stats") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("All time", stats.total, Modifier.weight(1f))
                    StatTile("7 days", stats.last7, Modifier.weight(1f))
                    StatTile("30 days", stats.last30, Modifier.weight(1f))
                }
            }
            item {
                Note(
                    "${stats.chats} chats (${stats.scheduled} scheduled) · ${messageStats.total} messages. " +
                        "7 and 30 days follow each chat's last activity."
                )
            }

            item {
                Section("Spend over time", "Cost per ${period.unit}, newest on the right") {
                    ChartControls(period, { period = it }, groupBy) { groupBy = it }
                    StackedChart(
                        costChart, period, now,
                        emptyText = "Fills in as you chat; usage is logged per response.",
                        label = { formatCost(it) },
                    )
                }
            }
            item {
                Section("Messages over time", "Messages per ${period.unit}, newest on the right") {
                    ChartControls(period, { period = it }, groupBy) { groupBy = it }
                    StackedChart(
                        messageChart, period, now,
                        emptyText = "No messages in this range.",
                        label = { it.toLong().toString() },
                    )
                    if (groupBy == GroupBy.Model) Note("Model view counts assistant replies; replies from before models were recorded show as unknown.")
                }
            }
            if (stats.perModel.isNotEmpty()) {
                item {
                    Section("Cost by model", "Per-response log, so only usage since model logging started") {
                        stats.perModel.forEach { (model, u) -> UsageRow(model.ifBlank { "unknown" }, u.label(), u) }
                    }
                }
            }
            if (stats.perProject.isNotEmpty()) {
                item {
                    Section("Cost by project", "Every chat in each project") {
                        stats.perProject.forEach { (name, chats, u) ->
                            UsageRow(name, "$chats chat${if (chats == 1) "" else "s"} · ${formatTokens(u.inputTokens + u.outputTokens)} tokens", u)
                        }
                    }
                }
            }
            if (stats.topChats.isNotEmpty()) {
                item {
                    Section("Most expensive chats", "Top five by cost") {
                        stats.topChats.forEach { t ->
                            UsageRow(
                                t.title,
                                formatTokens(t.inputTokens + t.outputTokens) + " tokens",
                                t.usage(),
                                onClick = {
                                    // Switching project clears the thread, so select the chat after it.
                                    container.setActiveProject(t.projectId)
                                    container.setActiveThread(t.id)
                                    onOpenChat()
                                },
                            )
                        }
                    }
                }
            }
            if (messageStats.tools.isNotEmpty()) {
                item {
                    Section("Most used tools", "How often the agent called each tool") {
                        val max = messageStats.tools.first().second.toFloat()
                        messageStats.tools.take(8).forEach { (name, n) ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.35f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Box(Modifier.weight(0.5f).height(8.dp)) {
                                    Box(Modifier.fillMaxHeight().fillMaxWidth(n / max).background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraSmall))
                                }
                                Text("$n", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(0.15f).padding(start = 8.dp))
                            }
                        }
                    }
                }
            }
            if (stats.scheduleRuns > 0) {
                item {
                    Section("Scheduled runs", "Newest 30 runs per schedule") {
                        Text(
                            "${stats.scheduleRuns} runs · ${stats.runsOk} ok · ${stats.runsFailed} failed · ${stats.runsSkipped} skipped",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (stats.avgRunSeconds > 0) Note("Average run ${stats.avgRunSeconds}s")
                        stats.perSchedule.forEach { (id, n, u) ->
                            UsageRow(scheduleTitles[id] ?: "Deleted schedule", "$n run${if (n == 1) "" else "s"}", u)
                        }
                    }
                }
            }
            item {
                Note("Chat-title generation is not counted. Totals and top chats follow existing chats; deleting a chat removes it from them but not from the per-day and per-model figures.")
            }
        }
    }
}

private data class MessageStats(
    val total: Int = 0,
    val stamps: List<MessageStamp> = emptyList(),
    val tools: List<Pair<String, Int>> = emptyList(),
)

private data class Stats(
    val total: UsageTotals,
    val last7: UsageTotals,
    val last30: UsageTotals,
    val chats: Int,
    val scheduled: Int,
    val perProject: List<Triple<String, Int, UsageTotals>>,
    val perModel: List<Pair<String, UsageTotals>>,
    val eventCount: Int,
    val topChats: List<ThreadEntity>,
    val scheduleRuns: Int,
    val runsOk: Int,
    val runsFailed: Int,
    val runsSkipped: Int,
    val avgRunSeconds: Long,
    val perSchedule: List<Triple<Long, Int, UsageTotals>>,
)

private fun startOfDay(ts: Long): Long = Calendar.getInstance().apply {
    timeInMillis = ts
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun compute(
    threads: List<ThreadEntity>,
    projects: List<com.edo.app.data.ProjectEntity>,
    events: List<UsageEventEntity>,
    runs: List<ScheduleRunEntity>,
): Stats {
    val now = System.currentTimeMillis()
    fun sum(list: List<ThreadEntity>) = list.fold(UsageTotals()) { acc, t -> acc + t.usage() }
    val perProject = projects.map { p ->
        val own = threads.filter { it.projectId == p.id }
        Triple(p.name, own.size, sum(own))
    }.sortedByDescending { it.third.costUsd }

    val perModel = events.groupBy { it.model }.map { (m, list) ->
        m to list.fold(UsageTotals()) { acc, e ->
            acc + UsageTotals(e.inputTokens, e.outputTokens, e.costUsd, e.costPartial)
        }
    }.sortedByDescending { it.second.costUsd }

    val perSchedule = runs.groupBy { it.scheduleId }.map { (id, list) ->
        Triple(id, list.size, list.fold(UsageTotals()) { acc, r -> acc + r.usage() })
    }.sortedByDescending { it.third.costUsd }
    val timed = runs.filter { it.status == "OK" || it.status == "FAILED" }

    return Stats(
        total = sum(threads),
        last7 = sum(threads.filter { now - it.lastUpdatedAt < 7 * DAY_MS }),
        last30 = sum(threads.filter { now - it.lastUpdatedAt < 30 * DAY_MS }),
        chats = threads.size,
        scheduled = threads.count { it.fromSchedule },
        perProject = perProject,
        perModel = perModel,
        eventCount = events.size,
        topChats = threads.filter { it.costUsd > 0 }.sortedByDescending { it.costUsd }.take(5),
        scheduleRuns = runs.size,
        runsOk = runs.count { it.status == "OK" },
        runsFailed = runs.count { it.status == "FAILED" || it.status == "TIMEOUT" },
        runsSkipped = runs.count { it.status == "SKIPPED" },
        avgRunSeconds = if (timed.isEmpty()) 0 else timed.sumOf { it.durationMs } / timed.size / 1000,
        perSchedule = perSchedule,
    )
}

@Composable
private fun Section(title: String, subtitle: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HorizontalDivider(Modifier.padding(bottom = 6.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Note(subtitle)
        }
        content()
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun UsageRow(name: String, detail: String, usage: UsageTotals, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(usage.costLabel() ?: "–", style = MaterialTheme.typography.bodyMedium)
    }
}

/** What a chart's bars are split by. */
private enum class GroupBy(val title: String) { Total("Total"), Project("Project"), Model("Model") }

private class Sample(val ts: Long, val value: Double, val series: String)

/** Per-bucket values for each series, largest series first; the tail is folded into "Other". */
private class Stacked(val series: List<String>, val bars: List<DoubleArray>)

private const val MAX_SERIES = 5

private fun stack(period: Period, now: Long, groupBy: GroupBy, samples: List<Sample>): Stacked {
    val totals = HashMap<String, DoubleArray>()
    for (s in samples) {
        val slot = period.slot(s.ts, now) ?: continue
        val key = if (groupBy == GroupBy.Total) "All" else s.series
        totals.getOrPut(key) { DoubleArray(period.count) }[slot] += s.value
    }
    val ranked = totals.entries.sortedByDescending { it.value.sum() }
    val top = ranked.take(MAX_SERIES)
    val names = top.map { it.key }.toMutableList()
    val arrays = top.map { it.value }.toMutableList()
    if (ranked.size > MAX_SERIES) {
        val other = DoubleArray(period.count)
        ranked.drop(MAX_SERIES).forEach { e -> e.value.forEachIndexed { i, v -> other[i] += v } }
        names += "Other"; arrays += other
    }
    return Stacked(names, List(period.count) { i -> DoubleArray(arrays.size) { j -> arrays[j][i] } })
}

@Composable
private fun seriesColors(): List<Color> = with(MaterialTheme.colorScheme) {
    listOf(primary, tertiary, secondary, error, inversePrimary, outline)
}

/** Stacked bars, oldest bucket first, with a legend when the bars are split into series. */
@Composable
private fun StackedChart(
    data: Stacked,
    period: Period,
    now: Long,
    emptyText: String,
    label: (Double) -> String,
) {
    val colors = seriesColors()
    val sums = data.bars.map { it.sum() }
    val max = sums.maxOrNull() ?: 0.0
    if (max <= 0) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(80.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), MaterialTheme.shapes.medium),
            contentAlignment = Alignment.Center,
        ) { Note(emptyText) }
        return
    }
    Text(
        "peak ${label(max)}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
        Modifier.fillMaxWidth().height(72.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        data.bars.forEachIndexed { i, parts ->
            val total = sums[i]
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom) {
                if (total <= 0) {
                    Box(Modifier.fillMaxWidth().height(2.dp).background(MaterialTheme.colorScheme.surfaceVariant))
                } else {
                    val barHeight = (total / max).toFloat()
                    Column(Modifier.fillMaxWidth().fillMaxHeight(barHeight.coerceAtLeast(0.03f))) {
                        // Last series sits at the bottom, so the biggest one stacks on top.
                        for (j in parts.indices.reversed()) {
                            if (parts[j] <= 0) continue
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .weight((parts[j] / total).toFloat())
                                    .background(colors[j % colors.size]),
                            )
                        }
                    }
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Note(period.label(0, now))
        Note(period.label(period.count - 1, now))
    }
    if (data.series.size > 1 || (data.series.firstOrNull() ?: "All") != "All") {
        data.series.forEachIndexed { j, name ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(colors[j % colors.size], MaterialTheme.shapes.extraSmall))
                Spacer(Modifier.width(8.dp))
                Text(name, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sum = data.bars.sumOf { it[j] }
                Text(label(sum), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun ChartControls(period: Period, onPeriod: (Period) -> Unit, groupBy: GroupBy, onGroupBy: (GroupBy) -> Unit) {
    PeriodPicker(period, onPeriod)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GroupBy.entries.forEach { g ->
            FilterChip(
                selected = g == groupBy,
                onClick = { onGroupBy(g) },
                label = { Text(if (g == GroupBy.Total) "Total" else "By ${g.title.lowercase()}") },
            )
        }
    }
}

@Composable
private fun PeriodPicker(selected: Period, onSelect: (Period) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        Period.entries.forEachIndexed { i, p ->
            SegmentedButton(
                selected = p == selected,
                onClick = { onSelect(p) },
                shape = SegmentedButtonDefaults.itemShape(i, Period.entries.size),
            ) { Text(p.title) }
        }
    }
}

/** Chart granularity: how many buckets to show, and which bucket a timestamp falls in. */
private enum class Period(val title: String, val unit: String, val count: Int) {
    Day("Day", "day", 14),
    Month("Month", "month", 12),
    Year("Year", "year", 5);

    /** Bucket index (0 = oldest shown) for [ts], or null when outside the window. */
    fun slot(ts: Long, now: Long): Int? {
        val age = age(ts, now)
        return if (age in 0 until count) count - 1 - age else null
    }

    private fun age(ts: Long, now: Long): Int {
        val a = Calendar.getInstance().apply { timeInMillis = now }
        val b = Calendar.getInstance().apply { timeInMillis = ts }
        return when (this) {
            Day -> Math.round((startOfDay(now) - startOfDay(ts)) / DAY_MS.toDouble()).toInt()
            Month -> (a.get(Calendar.YEAR) * 12 + a.get(Calendar.MONTH)) - (b.get(Calendar.YEAR) * 12 + b.get(Calendar.MONTH))
            Year -> a.get(Calendar.YEAR) - b.get(Calendar.YEAR)
        }
    }

    /** Axis text for bucket [index] (0 = oldest shown). */
    fun label(index: Int, now: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val back = count - 1 - index
        when (this) {
            Day -> cal.add(Calendar.DAY_OF_YEAR, -back)
            Month -> cal.add(Calendar.MONTH, -back)
            Year -> cal.add(Calendar.YEAR, -back)
        }
        val pattern = when (this) { Day -> "d MMM"; Month -> "MMM yyyy"; Year -> "yyyy" }
        return java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(cal.time)
    }
}

@Composable
private fun StatTile(title: String, usage: UsageTotals, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(usage.costLabel() ?: "$0", style = MaterialTheme.typography.titleLarge)
            Text(
                formatTokens(usage.inputTokens + usage.outputTokens) + " tokens",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
