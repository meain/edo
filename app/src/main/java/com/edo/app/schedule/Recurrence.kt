package com.edo.app.schedule

import com.edo.app.data.ScheduleEntity
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

enum class ScheduleKind {
    ONCE,
    INTERVAL,
    DAILY,
    WEEKLY,
    MONTHLY;

    companion object {
        fun from(name: String): ScheduleKind = entries.firstOrNull { it.name == name } ?: DAILY
    }
}

/** Shortest repeat interval; anything tighter would just burn tokens and battery. */
const val MIN_INTERVAL_MINUTES = 15

/**
 * The first run time strictly after [after] (epoch millis), or null if the schedule has no
 * further runs. ONCE schedules fire only at their stored [ScheduleEntity.nextRunAt], so they never
 * have a "next" one.
 */
fun nextRunAfter(s: ScheduleEntity, after: Long, zone: ZoneId = ZoneId.systemDefault()): Long? =
    when (ScheduleKind.from(s.kind)) {
        ScheduleKind.ONCE -> null
        ScheduleKind.INTERVAL -> after + maxOf(s.intervalMinutes, MIN_INTERVAL_MINUTES) * 60_000L
        ScheduleKind.DAILY -> nextAtTime(s.minuteOfDay, 0b1111111, after, zone)
        ScheduleKind.WEEKLY -> if (s.daysMask and 0b1111111 == 0) null
        else nextAtTime(s.minuteOfDay, s.daysMask, after, zone)
        ScheduleKind.MONTHLY -> nextMonthly(s.dayOfMonth, s.minuteOfDay, after, zone)
    }

private fun nextMonthly(dayOfMonth: Int, minuteOfDay: Int, after: Long, zone: ZoneId): Long? {
    val time = LocalTime.of((minuteOfDay / 60).coerceIn(0, 23), (minuteOfDay % 60).coerceIn(0, 59))
    val first = Instant.ofEpochMilli(after).atZone(zone).toLocalDate().withDayOfMonth(1)
    for (m in 0L..1L) {
        val month = first.plusMonths(m)
        // Short months clamp to their last day (31st → 30th / 28th).
        val date = month.withDayOfMonth(dayOfMonth.coerceIn(1, month.lengthOfMonth()))
        val candidate = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
        if (candidate > after) return candidate
    }
    return null
}

private fun nextAtTime(minuteOfDay: Int, daysMask: Int, after: Long, zone: ZoneId): Long? {
    val time = LocalTime.of((minuteOfDay / 60).coerceIn(0, 23), (minuteOfDay % 60).coerceIn(0, 59))
    val start = Instant.ofEpochMilli(after).atZone(zone).toLocalDate()
    // Eight days always contains a matching one (today's slot may already have passed).
    for (offset in 0L..7L) {
        val date = start.plusDays(offset)
        if (daysMask and (1 shl (date.dayOfWeek.value - DayOfWeek.MONDAY.value)) == 0) continue
        // atZone resolves DST gaps by shifting forward, so the slot always exists.
        val candidate = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
        if (candidate > after) return candidate
    }
    return null
}
