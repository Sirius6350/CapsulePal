package com.capsulepal.app.notifier

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.capsulepal.app.R

class FallbackNotifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    fun build(content: TaskNotificationContent): Notification {
        val builder = NotificationCompat.Builder(context, NotificationChannels.TASK_FALLBACK)
            .setSmallIcon(content.iconRes)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.bigText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setColor(0xFFE8735A.toInt())
            .setContentIntent(NotificationActions.openApp(context))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (content.kind == IslandKind.CHARGING) {
            builder.addAction(
                0, "收到",
                NotificationActions.chargingAck(context, content.sessionId)
            )
        } else if (content.test) {
            val action = NotificationActions.testAction(context)
            builder.addAction(0, "完成", action)
            builder.addAction(0, "跳过", action)
            builder.addAction(0, "收到", action)
        } else if (content.taskId != null) {
            builder.addAction(0, "完成", NotificationActions.complete(context, content.taskId))
            builder.addAction(0, "跳过", NotificationActions.skip(context, content.taskId))
        }

        return builder.build()
    }

    fun post(
        notification: Notification,
        id: Int = NotificationActions.TASK_NOTIFICATION_ID
    ) {
        if (!NotificationValidator.hasPostPermission(context)) return
        // 同一 ID 从实况渠道切换到提醒渠道时，先取消再发，避免渠道沿用。
        manager.cancel(id)
        manager.cancel(NotificationActions.otherIslandNotificationId(id))
        manager.notify(id, notification)
    }

    /** 清理旧版本遗留的预览通知，当前测试已改走任务通知通道。 */
    fun cancelPreview() {
        manager.cancel(LiveUpdateNotifier.PREVIEW_NOTIFICATION_ID)
    }

    fun cancel() {
        manager.cancel(NotificationActions.TASK_NOTIFICATION_ID)
        manager.cancel(NotificationActions.CHARGING_ISLAND_NOTIFICATION_ID)
    }
}
