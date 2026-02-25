package com.rocksmithtab

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.work.Configuration
import androidx.work.WorkManager

/**
 * Application subclass.
 *
 * Responsibilities:
 *  - Lazily initialises WorkManager (we disable the default auto-init in the manifest
 *    so we can supply our own Configuration, e.g. a custom executor or logger).
 *  - Creates the notification channel required for the foreground conversion service
 *    on Android 8+.
 */
class RocksmithToTabApp : Application(), Configuration.Provider {

    companion object {
        const val NOTIFICATION_CHANNEL_ID   = "conversion"
        const val NOTIFICATION_CHANNEL_NAME = "Conversion progress"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    // ── WorkManager configuration ─────────────────────────────────────────

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    // ── Notification channel ──────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW   // silent — progress only
            ).apply {
                description = "Shows progress while converting a Rocksmith song to a tab file"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }
}
