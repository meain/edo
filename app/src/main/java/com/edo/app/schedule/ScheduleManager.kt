package com.edo.app.schedule

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import com.edo.app.AppContainer
import com.edo.app.data.ScheduleEntity
import com.edo.app.llm.Block
import com.edo.app.llm.Role
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Owns the schedule table and the single alarm that wakes the app for the earliest pending run.
 * Everything here runs on the main thread (the app scope); Room moves the I/O off it.
 */
class ScheduleManager(private val app: Application, private val container: AppContainer) {
    private val dao get() = container.db.schedules()
    private val alarms = app.getSystemService(AlarmManager::class.java)

    /** Schedules with a run in flight; a second firing for the same one is skipped. */
    private val _running = MutableStateFlow<Set<Long>>(emptySet())
    val running: StateFlow<Set<Long>> = _running.asStateFlow()

    /** False until the user grants "Alarms & reminders"; runs then drift by minutes under Doze. */
    fun canScheduleExact(): Boolean = alarms.canScheduleExactAlarms()

    /** Insert or update [s], recomputing its next run time, and re-arm the alarm. */
    suspend fun save(s: ScheduleEntity): Long {
        val now = System.currentTimeMillis()
        val once = ScheduleKind.from(s.kind) == ScheduleKind.ONCE
        val next = when {
            !s.enabled -> 0L
            once -> s.nextRunAt.takeIf { it > now } ?: 0L
            else -> nextRunAfter(s, now) ?: 0L
        }
        val toSave = s.copy(nextRunAt = next, enabled = s.enabled && next > 0)
        val id = if (s.id == 0L) dao.insert(toSave) else toSave.also { dao.update(it) }.id
        rearm()
        return id
    }

    suspend fun list(projectId: Long): List<ScheduleEntity> =
        dao.listAll().filter { it.projectId == projectId }.sortedBy { it.createdAt }

    suspend fun get(id: Long): ScheduleEntity? = dao.getById(id)

    suspend fun delete(s: ScheduleEntity) {
        dao.delete(s)
        rearm()
    }

    suspend fun deleteForProject(projectId: Long) {
        dao.deleteForProject(projectId)
        rearm()
    }

    /** Point the one alarm at the earliest pending run, or clear it if nothing is pending. */
    suspend fun rearm() {
        val pi = firePendingIntent()
        val next = dao.earliestNextRun()
        if (next == null) {
            alarms.cancel(pi)
            return
        }
        if (canScheduleExact()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        }
    }

    /**
     * The alarm fired: return every schedule that is due, with each already advanced to its next
     * occurrence (missed ones in between are dropped, not replayed).
     */
    suspend fun takeDue(): List<ScheduleEntity> {
        val now = System.currentTimeMillis()
        val due = dao.listDue(now)
        for (s in due) advance(s, now, status = null)
        rearm()
        return due
    }

    /**
     * After boot, an app update or a clock / zone change the alarm is gone or stale. Runs that
     * were missed while the phone was off are skipped (not replayed); recurring wall-clock
     * schedules are re-aimed at the new local time.
     */
    suspend fun resync() {
        val now = System.currentTimeMillis()
        for (s in dao.listAll()) {
            if (!s.enabled) continue
            when {
                s.nextRunAt in 1..now -> advance(s, now, status = "SKIPPED")
                ScheduleKind.from(s.kind).let { it == ScheduleKind.DAILY || it == ScheduleKind.WEEKLY } ->
                    dao.update(s.copy(nextRunAt = nextRunAfter(s, now) ?: 0L))
            }
        }
        rearm()
    }

    private suspend fun advance(s: ScheduleEntity, now: Long, status: String?) {
        val next = nextRunAfter(s, now)
        dao.update(
            s.copy(
                nextRunAt = next ?: 0L,
                enabled = s.enabled && next != null,
                lastRunAt = if (status == null) now else s.lastRunAt,
                lastStatus = status ?: s.lastStatus,
            )
        )
    }

    /** Run [s] now in a fresh thread and notify with the outcome. Also backs "Run now". */
    suspend fun run(s: ScheduleEntity) {
        if (s.id in _running.value) {
            record(s.id) { it.copy(lastStatus = "SKIPPED") }
            return
        }
        _running.update { it + s.id }
        try {
            val session = container.chatSessions.unattended(s.projectId)
            if (session == null) {
                record(s.id) { it.copy(lastStatus = "FAILED") }
                return
            }
            val startedAt = System.currentTimeMillis()
            session.send(s.prompt)
            val finished = withTimeoutOrNull(RUN_TIMEOUT_MS) { session.awaitIdle(); true }
            if (finished == null) session.cancelAgent()

            val state = session.state.value
            val threadId = session.threadId
            val status = when {
                finished == null -> "TIMEOUT"
                state.error != null -> "FAILED"
                else -> "OK"
            }
            if (threadId > 0) {
                val stamp = RUN_TITLE_FORMAT.format(Instant.ofEpochMilli(startedAt).atZone(ZoneId.systemDefault()))
                container.db.threads().updateTitleAndTimestamp(threadId, "⏰ ${s.title} · $stamp", System.currentTimeMillis())
            }
            val reply = state.messages
                .lastOrNull { it.role == Role.Assistant && it.blocks.any { b -> b is Block.Text } }
                ?.blocks?.filterIsInstance<Block.Text>()?.joinToString("\n") { it.text }.orEmpty()
            val body = when (status) {
                "OK" -> reply.ifBlank { "Finished." }
                "TIMEOUT" -> "Stopped after ${RUN_TIMEOUT_MS / 60_000} minutes."
                else -> state.error ?: "Run failed."
            }
            record(s.id) { it.copy(lastStatus = status, lastThreadId = threadId) }
            ScheduleNotifier.post(app, s, status, body, threadId)
        } finally {
            _running.update { it - s.id }
        }
    }

    private suspend fun record(id: Long, change: (ScheduleEntity) -> ScheduleEntity) {
        // Re-read: the schedule may have been edited or deleted while the run was going.
        val current = dao.getById(id) ?: return
        dao.update(change(current))
    }

    private fun firePendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        app, 0,
        Intent(app, ScheduleReceiver::class.java).setAction(ACTION_FIRE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val ACTION_FIRE = "com.edo.app.SCHEDULE_FIRE"
        private const val RUN_TIMEOUT_MS = 10 * 60_000L
        private val RUN_TITLE_FORMAT = DateTimeFormatter.ofPattern("d MMM HH:mm")
    }
}
