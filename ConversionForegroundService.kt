package com.rocksmithtab.ui

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.rocksmithtab.R
import com.rocksmithtab.RocksmithToTabApp

/**
 * A minimal foreground service that displays a persistent notification while a
 * conversion is in progress. It is started/stopped by [ConversionWorker] via
 * WorkManager's setForeground() API.
 *
 * On Android 12+ WorkManager handles foreground service promotion automatically
 * when you call setForeground() inside a CoroutineWorker; this class exists as
 * the declared service component required in the manifest.
 */
class ConversionForegroundService : Service() {

    companion object {
        const val NOTIFICATION_ID = 1001
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Converting…"))
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Notification helpers (also called by ConversionWorker) ───────────

    fun buildNotification(message: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, RocksmithToTabApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setProgress(100, 0, true)
            .build()
    }

    fun updateProgress(percent: Int, message: String) {
        val nm   = getSystemService(NotificationManager::class.java)
        val note = NotificationCompat.Builder(this, RocksmithToTabApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setProgress(100, percent, percent == 0)
            .build()
        nm.notify(NOTIFICATION_ID, note)
    }
}
