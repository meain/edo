package com.edo.app.ui.chat

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
import com.edo.app.schedule.ScheduleNotifier

/** Tells the user a chat's run has finished while the app is in the background. */
object RunNotifier {
    private const val CHANNEL_ID = "run_complete"
    private const val NOTIFICATION_BASE = 3000

    fun post(context: Context, projectId: Long, threadId: Long, title: String, body: String, failed: Boolean) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return

        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Finished chats", NotificationManager.IMPORTANCE_DEFAULT)
        )
        // Same extras as scheduled-run notifications, so MainActivity opens the thread on tap.
        val open = PendingIntent.getActivity(
            context, threadId.toInt(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(ScheduleNotifier.EXTRA_PROJECT_ID, projectId)
                .putExtra(ScheduleNotifier.EXTRA_THREAD_ID, threadId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = body.trim().replace(Regex("\\s+"), " ")
        nm.notify(
            NOTIFICATION_BASE + threadId.toInt(),
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(if (failed) "$title (failed)" else title)
                .setContentText(text.take(120))
                .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(600)))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
    }
}
