package com.capsulepal.app.notifier

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.capsulepal.app.R

class LiveUpdateNotifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    fun channelId(): String = NotificationChannels.TASK_LIVE

    fun build(content: TaskNotificationContent): Notification {
        val accent = 0xFFE8735A.toInt()
        val charging = content.kind == IslandKind.CHARGING
        val style = NotificationCompat.ProgressStyle()
            .setStyledByProgress(false)
            .addProgressSegment(
                NotificationCompat.ProgressStyle.Segment(content.max.coerceAtLeast(1))
                    .setColor(accent)
            )
            .setProgress(content.progress.coerceIn(0, content.max))

        val builder = NotificationCompat.Builder(context, NotificationChannels.TASK_LIVE)
            .setSmallIcon(content.iconRes)
            .setContentTitle(content.title)
            .setContentText(content.text)
            // 展开卡片第三行显示备注；没有备注时不占位。
            .apply {
                val note = content.note.trim()
                if (note.isNotEmpty()) setSubText(note)
            }
            // Android 16 唯一允许出现在状态栏胶囊上的自定义文字（上限 7 个字符）。
            // 充电时右侧显示功率；待办岛上岛时左侧仍是待办名称。
            .setShortCriticalText(
                content.shortText.take(7).ifBlank { content.title.take(7) }
            )
            .setStyle(style)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            // 收起态固定展示待办名称，不显示系统计时。
            .setShowWhen(false)
            .setUsesChronometer(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setColor(accent)
            .setColorized(false)
            // 保持胶囊形态，始终请求上岛。
            .setRequestPromotedOngoing(true)
            .setContentIntent(NotificationActions.openApp(context))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (charging) {
            // 卡片右上角的电池图标由代码绘制；底部只保留“收到”。
            builder.setLargeIcon(ChargingIconFactory.render(context, content.progress))
            builder.setSilent(true)
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
            builder.addAction(
                0, "完成",
                NotificationActions.complete(context, content.taskId)
            )
            builder.addAction(
                0, "跳过",
                NotificationActions.skip(context, content.taskId)
            )
            builder.addAction(
                0, "收到",
                NotificationActions.acknowledge(context, content.taskId)
            )
        }

        return builder.build()
    }

    fun validate(notification: Notification): ValidationResult =
        NotificationValidator.validate(context, notification, NotificationChannels.TASK_LIVE)

    fun post(notification: Notification): ValidationResult {
        val validation = validate(notification)
        if (validation.eligible) {
            postInternal(notification)
        }
        return validation
    }

    fun postInternal(notification: Notification) {
        if (!NotificationValidator.hasPostPermission(context)) return
        manager.notify(NotificationActions.TASK_NOTIFICATION_ID, notification)
    }

    fun cancel() {
        manager.cancel(NotificationActions.TASK_NOTIFICATION_ID)
    }

    fun placeholder(): Notification =
        NotificationCompat.Builder(context, NotificationChannels.TASK_LIVE)
            .setSmallIcon(R.drawable.ic_cat_custom)
            .setContentTitle("小胶囊正在整理下一件事")
            .setContentText("马上就好")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    /** 清理旧版本遗留的预览通知，当前测试已改走任务通知通道。 */
    fun cancelPreview() {
        manager.cancel(PREVIEW_NOTIFICATION_ID)
    }

    fun openAppIntent(): PendingIntent = NotificationActions.openApp(context)

    companion object {
        const val PREVIEW_NOTIFICATION_ID = 1005
    }
}
