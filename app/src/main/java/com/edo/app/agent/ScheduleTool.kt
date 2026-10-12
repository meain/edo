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
            "with the result. Actions: create, list, delete, enable, disable. A scheduled run is a fresh, " +
            "unattended chat that gets `prompt` as its first message, so make the prompt self-contained. " +
            "Times are the user's local time; call current_datetime first to resolve things like " +
            "\"tomorrow at 9\". For create, `repeat` is one of: once (needs `date` and `time`), " +
            "interval (needs `every_minutes`, at least $MIN_INTERVAL_MINUTES), daily (needs `time`), " +
            "weekly (needs `days` and `time`).",
        parametersJson = """{"type":"object","properties":{
"action":{"type":"string","enum":["create","list","delete","enable","disable"]},
"id":{"type":"integer","description":"Schedule id, for delete / enable / disable"},
"title":{"type":"string","description":"Short name shown in the notification"},
"prompt":{"type":"string","description":"What the scheduled run should do"},
"repeat":{"type":"string","enum":["once","interval","daily","weekly"]},
"date":{"type":"string","description":"YYYY-MM-DD, for once"},
"time":{"type":"string","description":"HH:mm, 24-hour, for once / daily / weekly"},
"days":{"type":"array","items":{"type":"string"},"description":"mon, tue, wed, thu, fri, sat, sun; for weekly"},
"every_minutes":{"type":"integer","description":"For interval"}
},"required":["action"]}""",
    )

    override suspend fun invoke(argsJson: String): ToolResult {
        val args = parseArgs(argsJson) ?: return argsParseError(argsJson)
        return when (val action = args.text("action")) {
            "create" -> create(args)
            "list" -> list()
            "delete" -> withSchedule(args) { scheduler.delete(it); ToolResult("Deleted schedule ${it.id} (${it.title}).") }
            "enable" -> withSchedule(args) { setEnabled(it, true) }
            "disable" -> withSchedule(args) { setEnabled(it, false) }
            else -> ToolResult("unknown action '$action'; use create, list, delete, enable or disable", isError = true)
        }
    }

    private suspend fun list(): ToolResult {
        val all = scheduler.list(projectId)
        if (all.isEmpty()) return ToolResult("(no schedules)")
        return ToolResult(all.joinToString("\n") { "${it.id}: ${it.title} — ${describe(it)}" })
    }

    private suspend fun create(args: JsonObject): ToolResult {
        val prompt = args.text("prompt")?.trim().orEmpty()
        if (prompt.isEmpty()) return err("missing 'prompt'")
        if (scheduler.list(projectId).size >= MAX_PER_PROJECT) {
            return err("this project already has $MAX_PER_PROJECT schedules; delete one first")
        }
        val kind = when (args.text("repeat")?.lowercase()) {
            "once" -> ScheduleKind.ONCE
            "interval" -> ScheduleKind.INTERVAL
            "daily" -> ScheduleKind.DAILY
            "weekly" -> ScheduleKind.WEEKLY
            else -> return err("'repeat' must be once, interval, daily or weekly")
        }
        val zone = ZoneId.systemDefault()
        val time = if (kind == ScheduleKind.INTERVAL) LocalTime.MIDNIGHT else
            args.text("time")?.let { runCatching { LocalTime.parse(it.trim()) }.getOrNull() }
                ?: return err("'time' must be HH:mm, e.g. 09:30")
        var interval = 60
        var mask = 0b1111111
        var runAt = 0L
        when (kind) {
            ScheduleKind.ONCE -> {
                val date = args.text("date")?.let { runCatching { LocalDate.parse(it.trim()) }.getOrNull() }
                    ?: return err("'date' must be YYYY-MM-DD")
                runAt = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
                if (runAt <= System.currentTimeMillis()) return err("that time is already in the past")
            }
            ScheduleKind.INTERVAL -> {
                interval = args["every_minutes"]?.jsonPrimitive?.intOrNull ?: return err("missing 'every_minutes'")
                if (interval < MIN_INTERVAL_MINUTES) return err("'every_minutes' must be at least $MIN_INTERVAL_MINUTES")
            }
            ScheduleKind.WEEKLY -> {
                val names = runCatching { args["days"]!!.jsonArray.map { it.jsonPrimitive.content } }.getOrNull()
                    ?: return err("'days' must be an array like [\"mon\",\"thu\"]")
                mask = 0
                for (n in names) {
                    val day = DAY_NAMES[n.trim().lowercase().take(3)] ?: return err("unknown day '$n'")
                    mask = mask or (1 shl (day.value - 1))
                }
                if (mask == 0) return err("'days' must not be empty")
            }
            ScheduleKind.DAILY -> Unit
        }
        val entity = ScheduleEntity(
            projectId = projectId,
            title = args.text("title")?.trim().orEmpty().ifBlank { prompt.take(32) },
            prompt = prompt,
            kind = kind.name,
            minuteOfDay = time.hour * 60 + time.minute,
            daysMask = mask,
            intervalMinutes = interval,
            nextRunAt = runAt,
        )
        val id = scheduler.save(entity)
        val saved = scheduler.get(id) ?: return err("could not save the schedule")
        return ToolResult("Created schedule $id: ${saved.title} — ${describe(saved)}")
    }

    private suspend fun setEnabled(s: ScheduleEntity, on: Boolean): ToolResult {
        scheduler.save(s.copy(enabled = on))
        val now = scheduler.get(s.id) ?: return err("schedule ${s.id} no longer exists")
        return if (on && !now.enabled) {
            err("schedule ${s.id} has no upcoming run (a one-time schedule in the past); create a new one")
        } else {
            ToolResult("Schedule ${s.id} is now ${if (now.enabled) "on" else "off"}.")
        }
    }

    private suspend fun withSchedule(args: JsonObject, block: suspend (ScheduleEntity) -> ToolResult): ToolResult {
        val id = args["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return err("missing 'id'")
        val s = scheduler.get(id)?.takeIf { it.projectId == projectId } ?: return err("no schedule $id in this project")
        return block(s)
    }

    private fun err(msg: String) = ToolResult(msg, isError = true)

    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun describe(s: ScheduleEntity): String {
        val time = "%02d:%02d".format(s.minuteOfDay / 60, s.minuteOfDay % 60)
        val base = when (ScheduleKind.from(s.kind)) {
            ScheduleKind.ONCE -> "once"
            ScheduleKind.INTERVAL -> "every ${s.intervalMinutes} min"
            ScheduleKind.DAILY -> "daily at $time"
            ScheduleKind.WEEKLY -> DayOfWeek.values().filter { s.daysMask and (1 shl (it.value - 1)) != 0 }
                .joinToString(",") { it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) } + " at $time"
        }
        if (!s.enabled || s.nextRunAt <= 0) return "$base, off"
        val next = NEXT_FORMAT.format(Instant.ofEpochMilli(s.nextRunAt).atZone(ZoneId.systemDefault()))
        return "$base, next run $next"
    }

    private companion object {
        const val MAX_PER_PROJECT = 20
        val NEXT_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH)
        val DAY_NAMES = DayOfWeek.values().associateBy { it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).lowercase() }
    }
}
