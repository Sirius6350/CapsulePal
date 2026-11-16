package com.capsulepal.app.notifier

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.capsulepal.app.R

object NotificationChannels {

    const val KEEP_ALIVE = "keep_alive"
    const val TASK_LIVE = "task_live"
    const val TASK_FALLBACK = "task_fallback"
    const val TASK_REMINDER = "task_reminder"
    const val REMINDER_LIVE = "reminder_live"
    const val SYSTEM = "system"

    fun ensure(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                KEEP_ALIVE,
                context.getString(R.string.keep_alive_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.keep_alive_channel_desc)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                TASK_LIVE,
                context.getString(R.string.task_live_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.task_live_channel_desc)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                TASK_FALLBACK,
                context.getString(R.string.task_fallback_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.task_fallback_channel_desc)
                setShowBadge(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setAllowBubbles(false)
                }
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                TASK_REMINDER,
                context.getString(R.string.task_reminder_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.task_reminder_channel_desc)
                setShowBadge(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setAllowBubbles(false)
                }
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                REMINDER_LIVE,
                context.getString(R.string.reminder_live_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.reminder_live_channel_desc)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                SYSTEM,
                context.getString(R.string.system_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.system_channel_desc)
                setShowBadge(true)
            }
        )
    }
}
