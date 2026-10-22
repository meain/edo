package com.edo.app.schedule

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import com.edo.app.AppContainer
import com.edo.app.data.ScheduleEntity
import com.edo.app.data.ScheduleRunEntity
import com.edo.app.data.usage
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
        // The editor and the toggle hand us a copy that may predate a run; lifetime usage is
        // only ever changed by addUsage, so keep whatever the table has.
        val current = if (s.id != 0L) dao.getById(s.id) else null
        // Same for the list position; a new schedule goes to the end of its project's list.
        val sortOrder = current?.sortOrder
            ?: ((dao.listAll().filter { it.projectId == s.projectId }.maxOfOrNull { it.sortOrder } ?: -1) + 1)
        val toSave = s.copy(
            sortOrder = sortOrder,
            nextRunAt = next, enabled = s.enabled && next > 0,
            totalInputTokens = current?.totalInputTokens ?: 0, totalOutputTokens = current?.totalOutputTokens ?: 0,
            totalCostUsd = current?.totalCostUsd ?: 0.0, totalCostPartial = current?.totalCostPartial ?: false,
        )
        val id = if (s.id == 0L) dao.insert(toSave) else toSave.also { dao.update(it) }.id
        rearm()
        return id
    }

    suspend fun list(projectId: Long): List<ScheduleEntity> =
        dao.listAll().filter { it.projectId == projectId }.sortedWith(compareBy({ it.sortOrder }, { it.createdAt }))

    /** Store the list positions for [ids], first to last. */
    suspend fun reorder(ids: List<Long>) {
        ids.forEachIndexed { i, id -> dao.setSortOrder(id, i) }
    }

    suspend fun get(id: Long): ScheduleEntity? = dao.getById(id)

    /**
     * The schedule that started chat [threadId]. A run is only logged once it ends, so a chat reopened
     * after that is found through the run history, or failing that as a schedule's latest chat.
     */
    suspend fun scheduleForThread(threadId: Long): Long? {
        if (threadId <= 0) return null
        return container.db.scheduleRuns().scheduleIdForThread(threadId)
            ?: dao.listAll().firstOrNull { it.lastThreadId == threadId }?.id
    }

    suspend fun delete(s: ScheduleEntity) {
        container.db.scheduleRuns().deleteForSchedule(s.id)
        dao.delete(s)
        rearm()
    }

    suspend fun deleteForProject(projectId: Long) {
        container.db.scheduleRuns().deleteForProject(projectId)
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
                ScheduleKind.from(s.kind).let { it != ScheduleKind.ONCE && it != ScheduleKind.INTERVAL } ->
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
            logRun(s.id, threadId = 0, startedAt = System.currentTimeMillis(), status = "SKIPPED")
            return
        }
        _running.update { it + s.id }
        try {
            val startedAt = System.currentTimeMillis()
            val session = container.chatSessions.unattended(s.projectId)
            if (session == null) {
                record(s.id) { it.copy(lastStatus = "FAILED") }
                logRun(s.id, threadId = 0, startedAt = startedAt, status = "FAILED")
                return
            }
            val stamp = RUN_TITLE_FORMAT.format(Instant.ofEpochMilli(startedAt).atZone(ZoneId.systemDefault()))
            session.initialTitle = "${s.title} · $stamp"
            session.scheduleId = s.id
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
            val reply = state.messages
                .lastOrNull { it.role == Role.Assistant && it.blocks.any { b -> b is Block.Text } }
                ?.blocks?.filterIsInstance<Block.Text>()?.joinToString("\n") { it.text }.orEmpty()
            val body = when (status) {
                "OK" -> reply.ifBlank { "Finished." }
                "TIMEOUT" -> "Stopped after ${RUN_TIMEOUT_MS / 60_000} minutes."
                else -> state.error ?: "Run failed."
            }
            record(s.id) { it.copy(lastStatus = status, lastThreadId = threadId) }
            logRun(s.id, threadId, startedAt, status)
            ScheduleNotifier.post(app, s, status, body, threadId)
        } finally {
            _running.update { it - s.id }
        }
    }

    /** Adds a history row for a run; its tokens and cost are the totals of the chat it ran in. */
    private suspend fun logRun(scheduleId: Long, threadId: Long, startedAt: Long, status: String) {
        // The schedule may have been deleted mid-run; don't leave orphan history behind.
        if (dao.getById(scheduleId) == null) return
        val usage = if (threadId > 0) container.db.threads().getById(threadId)?.usage() else null
        val runs = container.db.scheduleRuns()
        runs.insert(
            ScheduleRunEntity(
                scheduleId = scheduleId, threadId = threadId, startedAt = startedAt,
                durationMs = System.currentTimeMillis() - startedAt, status = status,
                inputTokens = usage?.inputTokens ?: 0, outputTokens = usage?.outputTokens ?: 0,
                costUsd = usage?.costUsd ?: 0.0, costPartial = usage?.partial ?: false,
            )
        )
        runs.prune(scheduleId)
        if (usage != null) {
            dao.addUsage(scheduleId, usage.inputTokens, usage.outputTokens, usage.costUsd, usage.partial)
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
