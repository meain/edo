package com.edo.app.schedule

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.edo.app.MainActivity
import com.edo.app.R
import com.edo.app.data.ScheduleEntity

/** Posts the outcome of a scheduled run; tapping it opens the thread the run created. */
object ScheduleNotifier {
    const val CHANNEL_ID = "schedule_results"
    const val EXTRA_PROJECT_ID = "open_project_id"
    const val EXTRA_THREAD_ID = "open_thread_id"

    fun post(context: Context, s: ScheduleEntity, status: String, body: String, threadId: Long) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return

        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Scheduled runs", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            context, s.id.toInt(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_PROJECT_ID, s.projectId)
                .putExtra(EXTRA_THREAD_ID, threadId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (status == "OK") s.title else "${s.title} (${status.lowercase()})"
        val text = body.trim().replace(Regex("\\s+"), " ")
        nm.notify(
            NOTIFICATION_BASE + s.id.toInt(),
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(title)
                .setContentText(text.take(120))
                .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(600)))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
    }

    private const val NOTIFICATION_BASE = 2000
}
