package com.edo.app.schedule

import com.edo.app.data.ScheduleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class RecurrenceTest {
    private val zone = ZoneId.of("Europe/Berlin")

    private fun at(s: String) = ZonedDateTime.parse("$s[Europe/Berlin]").toInstant().toEpochMilli()

    private fun sched(kind: ScheduleKind, minuteOfDay: Int = 9 * 60, mask: Int = 0b1111111, every: Int = 60) =
        ScheduleEntity(
            projectId = 1, title = "t", prompt = "p", kind = kind.name,
            minuteOfDay = minuteOfDay, daysMask = mask, intervalMinutes = every,
        )

    @Test
    fun dailyBeforeTodaysSlotRunsToday() {
        val next = nextRunAfter(sched(ScheduleKind.DAILY), at("2026-10-12T08:00:00+02:00"), zone)
        assertEquals(at("2026-10-12T09:00:00+02:00"), next)
    }

    @Test
    fun dailyAfterTodaysSlotRunsTomorrow() {
        val next = nextRunAfter(sched(ScheduleKind.DAILY), at("2026-10-12T09:00:00+02:00"), zone)
        assertEquals(at("2026-10-13T09:00:00+02:00"), next)
    }

    @Test
    fun weeklySkipsToNextSelectedDay() {
        // Mon + Thu; 2026-10-12 is a Monday and its slot has passed.
        val next = nextRunAfter(sched(ScheduleKind.WEEKLY, mask = 0b0001001), at("2026-10-12T10:00:00+02:00"), zone)
        assertEquals(at("2026-10-15T09:00:00+02:00"), next)
    }

    @Test
    fun weeklyOnlyTodayWrapsToNextWeek() {
        val next = nextRunAfter(sched(ScheduleKind.WEEKLY, mask = 0b0000001), at("2026-10-12T10:00:00+02:00"), zone)
        assertEquals(at("2026-10-19T09:00:00+02:00"), next)
    }

    @Test
    fun weeklyWithNoDaysHasNoRun() {
        assertNull(nextRunAfter(sched(ScheduleKind.WEEKLY, mask = 0), 0L, zone))
    }

    @Test
    fun dailyKeepsLocalTimeAcrossDstChange() {
        // Clocks go back on 2026-10-25; 09:00 local is still 09:00 afterwards.
        val next = nextRunAfter(sched(ScheduleKind.DAILY), at("2026-10-24T10:00:00+02:00"), zone)
        assertEquals(at("2026-10-25T09:00:00+01:00"), next)
    }

    @Test
    fun intervalAddsMinutesAndEnforcesFloor() {
        val now = at("2026-10-12T08:00:00+02:00")
        assertEquals(now + 120 * 60_000L, nextRunAfter(sched(ScheduleKind.INTERVAL, every = 120), now, zone))
        assertEquals(now + MIN_INTERVAL_MINUTES * 60_000L, nextRunAfter(sched(ScheduleKind.INTERVAL, every = 1), now, zone))
    }

    @Test
    fun onceHasNoFollowUp() {
        assertNull(nextRunAfter(sched(ScheduleKind.ONCE), 0L, zone))
    }

    @Test
    fun monthlyClampsToLastDayOfShortMonth() {
        val s = sched(ScheduleKind.MONTHLY).copy(dayOfMonth = 31)
        // 31 Oct slot has passed; November has 30 days.
        val next = nextRunAfter(s, at("2026-10-31T10:00:00+01:00"), zone)
        assertEquals(at("2026-11-30T09:00:00+01:00"), next)
    }

    @Test
    fun monthlyBeforeThisMonthsSlotRunsThisMonth() {
        val s = sched(ScheduleKind.MONTHLY).copy(dayOfMonth = 15)
        assertEquals(at("2026-10-15T09:00:00+02:00"), nextRunAfter(s, at("2026-10-12T08:00:00+02:00"), zone))
    }
}
