package com.edo.app.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.edo.app.EdoApp
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * Entry point for the alarm ([ScheduleManager.ACTION_FIRE]) and for the system broadcasts that
 * invalidate it (boot, app update, clock or time-zone change).
 */
class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as EdoApp).container
        val scheduler = container.scheduler
        val pending = goAsync()
        container.appScope.launch {
            if (intent.action != ScheduleManager.ACTION_FIRE) {
                try { scheduler.resync() } finally { pending.finish() }
                return@launch
            }
            // Bring the foreground service up while the alarm's background-start allowance is
            // still open, then release the broadcast; the runs themselves can take minutes.
            val due = try {
                scheduler.takeDue().also { if (it.isNotEmpty()) container.chatSessions.runStarted() }
            } finally {
                pending.finish()
            }
            if (due.isEmpty()) return@launch
            try {
                due.map { launch { scheduler.run(it) } }.joinAll()
            } finally {
                container.chatSessions.runEnded()
            }
        }
    }
}
