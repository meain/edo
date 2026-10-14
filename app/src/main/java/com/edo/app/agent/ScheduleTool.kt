package com.edo.app.agent

import com.edo.app.data.ScheduleEntity
import com.edo.app.llm.ToolSpec
import com.edo.app.schedule.MIN_INTERVAL_MINUTES
import com.edo.app.schedule.ScheduleKind
import com.edo.app.schedule.ScheduleManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Lets the agent manage scheduled prompts for the current project. Not offered to scheduled
 * runs themselves, so a schedule can't spawn more schedules.
 */
class ScheduleTool(
    private val scheduler: ScheduleManager,
    private val projectId: Long,
) : Tool {
    override val spec = ToolSpec(
        name = "schedule",
        description = "Manage scheduled prompts that run in this project on their own and notify the user " +
            "with the result. Actions: create, list, get (full details of one), update, delete, enable, " +
            "disable. A scheduled run is a fresh, unattended chat that gets `prompt` as its first message, " +
            "so make the prompt self-contained. Times are the user's local time; call current_datetime " +
            "first to resolve things like \"tomorrow at 9\". `repeat` is one of: once (needs `date` and " +
            "`time`), interval (needs `every_minutes`, at least $MIN_INTERVAL_MINUTES), daily (needs " +
            "`time`), weekly (needs `days` and `time`), monthly (needs `day_of_month` and `time`; short " +
            "months run on their last day). For update, pass the id plus only the fields to " +
            "change; get first if you need the current values.",
        parametersJson = """{"type":"object","properties":{
"action":{"type":"string","enum":["create","list","get","update","delete","enable","disable"]},
"id":{"type":"integer","description":"Schedule id, for get / update / delete / enable / disable"},
"title":{"type":"string","description":"Short name shown in the notification"},
"prompt":{"type":"string","description":"What the scheduled run should do"},
"repeat":{"type":"string","enum":["once","interval","daily","weekly","monthly"]},
"date":{"type":"string","description":"YYYY-MM-DD, for once"},
"time":{"type":"string","description":"HH:mm, 24-hour, for once / daily / weekly"},
"days":{"type":"array","items":{"type":"string"},"description":"mon, tue, wed, thu, fri, sat, sun; for weekly"},
"every_minutes":{"type":"integer","description":"For interval"},
"day_of_month":{"type":"integer","description":"1-31, for monthly"}
},"required":["action"]}""",
    )

    override suspend fun invoke(argsJson: String): ToolResult {
        val args = parseArgs(argsJson) ?: return argsParseError(argsJson)
        return when (val action = args.text("action")) {
            "create" -> create(args)
            "list" -> list()
            "get" -> withSchedule(args) { ToolResult(details(it)) }
            "update" -> withSchedule(args) { update(it, args) }
            "delete" -> withSchedule(args) { scheduler.delete(it); ToolResult("Deleted schedule ${it.id} (${it.title}).") }
            "enable" -> withSchedule(args) { setEnabled(it, true) }
            "disable" -> withSchedule(args) { setEnabled(it, false) }
            else -> err("unknown action '$action'; use create, list, get, update, delete, enable or disable")
        }
    }

    private suspend fun list(): ToolResult {
        val all = scheduler.list(projectId)
        if (all.isEmpty()) return ToolResult("(no schedules)")
        return ToolResult(all.joinToString("\n") { "${it.id}: ${it.title} — ${describe(it)}" })
    }

    private suspend fun create(args: JsonObject): ToolResult {
        if (args.text("prompt").isNullOrBlank()) return err("missing 'prompt'")
        if (scheduler.list(projectId).size >= MAX_PER_PROJECT) {
            return err("this project already has $MAX_PER_PROJECT schedules; delete one first")
        }
        val entity = build(args, existing = null).getOrElse { return err(it.message.orEmpty()) }
        val id = scheduler.save(entity)
        val saved = scheduler.get(id) ?: return err("could not save the schedule")
        return ToolResult("Created schedule $id: ${saved.title} — ${describe(saved)}")
    }

    private suspend fun update(existing: ScheduleEntity, args: JsonObject): ToolResult {
        val entity = build(args, existing).getOrElse { return err(it.message.orEmpty()) }
        scheduler.save(entity)
        val saved = scheduler.get(existing.id) ?: return err("schedule ${existing.id} no longer exists")
        return ToolResult("Updated schedule ${saved.id}: ${saved.title} — ${describe(saved)}")
    }

    /**
     * Apply [args] on top of [existing] (or defaults when creating) and validate the result.
     * Fields left out keep their current values, so an update can change just one thing.
     */
    private fun build(args: JsonObject, existing: ScheduleEntity?): Result<ScheduleEntity> = runCatching {
        val zone = ZoneId.systemDefault()
        val kind = when (args.text("repeat")?.lowercase()) {
            null -> existing?.let { ScheduleKind.from(it.kind) } ?: fail("missing 'repeat'")
            "once" -> ScheduleKind.ONCE
            "interval" -> ScheduleKind.INTERVAL
            "daily" -> ScheduleKind.DAILY
            "weekly" -> ScheduleKind.WEEKLY
            "monthly" -> ScheduleKind.MONTHLY
            else -> fail("'repeat' must be once, interval, daily, weekly or monthly")
        }
        val prompt = args.text("prompt")?.trim()?.also { if (it.isEmpty()) fail("'prompt' must not be empty") }
            ?: existing?.prompt ?: fail("missing 'prompt'")

        var minuteOfDay = existing?.minuteOfDay ?: 9 * 60
        args.text("time")?.let { raw ->
            val t = runCatching { LocalTime.parse(raw.trim()) }.getOrNull() ?: fail("'time' must be HH:mm, e.g. 09:30")
            minuteOfDay = t.hour * 60 + t.minute
        }
        // Switching an interval schedule to a clock-based one has no meaningful old time to keep.
        val timeGiven = args.text("time") != null ||
            (existing != null && ScheduleKind.from(existing.kind) != ScheduleKind.INTERVAL)
        if (kind != ScheduleKind.INTERVAL && !timeGiven) fail("missing 'time'")

        var interval = existing?.intervalMinutes ?: 60
        if (kind == ScheduleKind.INTERVAL) {
            val given = args["every_minutes"]?.jsonPrimitive?.intOrNull
            if (given == null && (existing == null || ScheduleKind.from(existing.kind) != ScheduleKind.INTERVAL)) {
                fail("missing 'every_minutes'")
            }
            interval = given ?: interval
            if (interval < MIN_INTERVAL_MINUTES) fail("'every_minutes' must be at least $MIN_INTERVAL_MINUTES")
        }

        var mask = existing?.daysMask ?: 0b1111111
        if (kind == ScheduleKind.WEEKLY) {
            val raw = args["days"]
            if (raw != null) {
                val names = runCatching { raw.jsonArray.map { it.jsonPrimitive.content } }.getOrNull()
                    ?: fail("'days' must be an array like [\"mon\",\"thu\"]")
                mask = 0
                for (n in names) {
                    val day = DAY_NAMES[n.trim().lowercase().take(3)] ?: fail("unknown day '$n'")
                    mask = mask or (1 shl (day.value - 1))
                }
            } else if (existing == null || ScheduleKind.from(existing.kind) != ScheduleKind.WEEKLY) {
                fail("missing 'days'")
            }
            if (mask == 0) fail("'days' must not be empty")
        }

        var dayOfMonth = existing?.dayOfMonth ?: 1
        if (kind == ScheduleKind.MONTHLY) {
            val given = args["day_of_month"]?.jsonPrimitive?.intOrNull
            if (given == null && (existing == null || ScheduleKind.from(existing.kind) != ScheduleKind.MONTHLY)) {
                fail("missing 'day_of_month'")
            }
            dayOfMonth = given ?: dayOfMonth
            if (dayOfMonth !in 1..31) fail("'day_of_month' must be 1-31")
        }

        var runAt = 0L
        if (kind == ScheduleKind.ONCE) {
            val wasOnce = existing != null && ScheduleKind.from(existing.kind) == ScheduleKind.ONCE
            val date = args.text("date")?.let { runCatching { LocalDate.parse(it.trim()) }.getOrNull() ?: fail("'date' must be YYYY-MM-DD") }
                ?: if (wasOnce && existing!!.nextRunAt > 0) Instant.ofEpochMilli(existing.nextRunAt).atZone(zone).toLocalDate()
                else fail("missing 'date'")
            runAt = date.atTime(LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)).atZone(zone).toInstant().toEpochMilli()
            if (runAt <= System.currentTimeMillis()) fail("that time is already in the past")
        }

        (existing ?: ScheduleEntity(projectId = projectId, title = "", prompt = prompt)).copy(
            title = args.text("title")?.trim()?.takeIf { it.isNotEmpty() }
                ?: existing?.title?.takeIf { it.isNotEmpty() } ?: prompt.take(32),
            prompt = prompt,
            kind = kind.name,
            minuteOfDay = minuteOfDay,
            daysMask = mask,
            intervalMinutes = interval,
            dayOfMonth = dayOfMonth,
            nextRunAt = runAt,
            // Editing a schedule that had turned itself off (a finished one-time run) brings it back.
            enabled = true,
        )
    }

    private suspend fun setEnabled(s: ScheduleEntity, on: Boolean): ToolResult {
        scheduler.save(s.copy(enabled = on))
        val now = scheduler.get(s.id) ?: return err("schedule ${s.id} no longer exists")
        return if (on && !now.enabled) {
            err("schedule ${s.id} has no upcoming run (a one-time schedule in the past); update it with a new date or create a new one")
        } else {
            ToolResult("Schedule ${s.id} is now ${if (now.enabled) "on" else "off"}.")
        }
    }

    private suspend fun withSchedule(args: JsonObject, block: suspend (ScheduleEntity) -> ToolResult): ToolResult {
        val id = args["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return err("missing 'id'")
        val s = scheduler.get(id)?.takeIf { it.projectId == projectId } ?: return err("no schedule $id in this project")
        return block(s)
    }

    private fun fail(msg: String): Nothing = throw IllegalArgumentException(msg)

    private fun err(msg: String) = ToolResult(msg, isError = true)

    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun stamp(epoch: Long) = NEXT_FORMAT.format(Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()))

    private fun details(s: ScheduleEntity): String = buildString {
        appendLine("id: ${s.id}")
        appendLine("title: ${s.title}")
        appendLine("schedule: ${describe(s)}")
        appendLine("enabled: ${s.enabled}")
        if (s.lastRunAt > 0) appendLine("last run: ${stamp(s.lastRunAt)} (${s.lastStatus.lowercase().ifEmpty { "unknown" }})")
        else if (s.lastStatus.isNotEmpty()) appendLine("last run: ${s.lastStatus.lowercase()}")
        appendLine("prompt:")
        append(s.prompt)
    }

    private fun describe(s: ScheduleEntity): String {
        val time = "%02d:%02d".format(s.minuteOfDay / 60, s.minuteOfDay % 60)
        val base = when (ScheduleKind.from(s.kind)) {
            ScheduleKind.ONCE -> "once"
            ScheduleKind.INTERVAL -> "every ${s.intervalMinutes} min"
            ScheduleKind.DAILY -> "daily at $time"
            ScheduleKind.MONTHLY -> "monthly on day ${s.dayOfMonth} at $time"
            ScheduleKind.WEEKLY -> DayOfWeek.values().filter { s.daysMask and (1 shl (it.value - 1)) != 0 }
                .joinToString(",") { it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) } + " at $time"
        }
        if (!s.enabled || s.nextRunAt <= 0) return "$base, off"
        return "$base, next run ${stamp(s.nextRunAt)}"
    }

    private companion object {
        const val MAX_PER_PROJECT = 20
        val NEXT_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH)
        val DAY_NAMES = DayOfWeek.values().associateBy { it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).lowercase() }
    }
}
