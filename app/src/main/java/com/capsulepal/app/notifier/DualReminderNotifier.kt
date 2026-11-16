package com.capsulepal.app.notifier

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * 双重提醒：上岛之外再发一条普通通知。
 * 同一任务同一阶段只发一条，文案变化不再逐分钟刷新；
 * 阶段切换或换任务时重新提醒一次，用户手动划掉后不再补发。
 */
class DualReminderNotifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    @Volatile
    private var lastKey: String? = null

    fun build(content: TaskNotificationContent): Notification {
        val builder = NotificationCompat.Builder(context, NotificationChannels.TASK_REMINDER)
            .setSmallIcon(content.iconRes)
            .setContentTitle(content.taskTitle)
            .setContentText(content.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.bigText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setColor(0xFFE8735A.toInt())
            .setContentIntent(NotificationActions.openApp(context))
            .setDeleteIntent(NotificationActions.dismissDual(context))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        val taskId = content.taskId
        if (!content.test && taskId != null) {
            builder.addAction(0, "完成", NotificationActions.complete(context, taskId))
            builder.addAction(0, "跳过", NotificationActions.skip(context, taskId))
            builder.addAction(0, "收到", NotificationActions.acknowledge(context, taskId))
        }
        return builder.build()
    }

    fun post(notification: Notification, key: String) {
        if (!NotificationValidator.hasPostPermission(context)) return
        if (key == lastKey) {
            // 同一任务同一阶段只提醒一次，不随剩余时间逐分钟重复发通知。
            return
        }
        if (lastKey != null) {
            // 任务或阶段变化时先取消，让新提醒重新响一次。
            manager.cancel(REMINDER_ID)
        }
        lastKey = key
        manager.notify(REMINDER_ID, notification)
    }

    /** 用户手动划掉通知：同一阶段本来就不会再补发。 */
    fun onDismissed() = Unit

    fun cancel() {
        lastKey = null
        manager.cancel(REMINDER_ID)
    }

    companion object {
        /** 1001 上岛主通知，1002 保活前台服务，1003 本通知，1004 主动提醒。 */
        const val REMINDER_ID = NotificationActions.DUAL_REMINDER_NOTIFICATION_ID
    }
}
