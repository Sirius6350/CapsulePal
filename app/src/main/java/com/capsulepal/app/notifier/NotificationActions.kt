package com.capsulepal.app.notifier

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.capsulepal.app.MainActivity

object NotificationActions {
    const val TASK_NOTIFICATION_ID = 1001

    /**
     * 充电岛使用独立通知位。
     * 待办岛按进度填充红条（styledByProgress = true），充电岛保留整条色带
     * （styledByProgress = false）；同一 ID 来回切换这个样式时，系统会丢弃更新，
     * 表现为完成/跳过/收到待办后充电功率岛消失。
     */
    const val CHARGING_ISLAND_NOTIFICATION_ID = 1007

    fun islandNotificationId(content: TaskNotificationContent): Int =
        if (content.kind == IslandKind.CHARGING) {
            CHARGING_ISLAND_NOTIFICATION_ID
        } else {
            TASK_NOTIFICATION_ID
        }

    /** 待办岛与充电岛互斥，投递其中一个时清掉另一个。 */
    fun otherIslandNotificationId(id: Int): Int =
        if (id == TASK_NOTIFICATION_ID) {
            CHARGING_ISLAND_NOTIFICATION_ID
        } else {
            TASK_NOTIFICATION_ID
        }

    /** 保活前台服务占用的通知位，必须独立，不能被普通通知复用。 */
    const val KEEP_ALIVE_NOTIFICATION_ID = 1002
    const val DUAL_REMINDER_NOTIFICATION_ID = 1003

    /**
     * 主动提醒使用独立通知位。
     * 曾经与保活前台服务共用 1002，导致提醒通知属于前台服务、`cancel()` 收不掉，
     * 表现为“知道了 / 开始准备”点了没有反应。
     */
    const val REMINDER_NOTIFICATION_ID = 1004

    const val ACTION_COMPLETE = "com.capsulepal.app.action.COMPLETE"
    const val ACTION_SKIP = "com.capsulepal.app.action.SKIP"
    const val ACTION_ACK = "com.capsulepal.app.action.ACK"
    const val ACTION_CHARGE_ACK = "com.capsulepal.app.action.CHARGE_ACK"
    const val ACTION_TEST = "com.capsulepal.app.action.TEST"
    const val ACTION_REMINDER_ACCEPT = "com.capsulepal.app.action.REMINDER_ACCEPT"
    const val ACTION_REMINDER_DISMISS = "com.capsulepal.app.action.REMINDER_DISMISS"
    const val ACTION_DUAL_DISMISS = "com.capsulepal.app.action.DUAL_DISMISS"
    const val ACTION_COMPLETE_INSTANT = "com.capsulepal.app.action.COMPLETE_INSTANT"

    /** 即时待办独立通知位；与普通待办同时存在，互不覆盖。 */
    const val INSTANT_NOTIFICATION_ID = 1006
    const val EXTRA_TASK_ID = "extra_task_id"
    const val EXTRA_SESSION_ID = "extra_session_id"
    const val EXTRA_REMINDER_TITLE = "extra_reminder_title"
    const val EXTRA_REMINDER_START = "extra_reminder_start"
    const val EXTRA_REMINDER_DURATION = "extra_reminder_duration"

    fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, 2001, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun complete(context: Context, taskId: Long?): PendingIntent =
        action(context, ACTION_COMPLETE, 2002, taskId)

    fun skip(context: Context, taskId: Long?): PendingIntent =
        action(context, ACTION_SKIP, 2003, taskId)

    fun acknowledge(context: Context, taskId: Long?): PendingIntent =
        action(context, ACTION_ACK, 2004, taskId)

    /** 充电岛“收到”：只收起本次充电，下次插电重新上岛。 */
    fun chargingAck(context: Context, sessionId: Long): PendingIntent {
        val intent = Intent(context, com.capsulepal.app.scheduler.NotificationActionReceiver::class.java).apply {
            action = ACTION_CHARGE_ACK
            putExtra(EXTRA_SESSION_ID, sessionId)
        }
        return PendingIntent.getBroadcast(
            context, 2008, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 上岛测试卡片的三个按钮共用：点击只收起通知，不写待办、不计成长。 */
    fun testAction(context: Context): PendingIntent {
        val intent = Intent(context, com.capsulepal.app.scheduler.NotificationActionReceiver::class.java).apply {
            action = ACTION_TEST
        }
        return PendingIntent.getBroadcast(
            context, 2005, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** “开始准备”：已有待办时直接开始准备；日历事件则带着草稿信息落库。 */
    fun reminderAccept(
        context: Context,
        taskId: Long?,
        title: String? = null,
        startMillis: Long = 0L,
        durationMinutes: Int = 0
    ): PendingIntent {
        val intent = Intent(context, com.capsulepal.app.scheduler.NotificationActionReceiver::class.java).apply {
            action = ACTION_REMINDER_ACCEPT
            putExtra(EXTRA_TASK_ID, taskId ?: -1L)
            putExtra(EXTRA_REMINDER_TITLE, title)
            putExtra(EXTRA_REMINDER_START, startMillis)
            putExtra(EXTRA_REMINDER_DURATION, durationMinutes)
        }
        return PendingIntent.getBroadcast(
            context, 2006, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** “知道了”：只收起这次提醒，不影响待办之后按准备/进行状态上岛。 */
    fun reminderDismiss(context: Context): PendingIntent {
        val intent = Intent(context, com.capsulepal.app.scheduler.NotificationActionReceiver::class.java).apply {
            action = ACTION_REMINDER_DISMISS
        }
        return PendingIntent.getBroadcast(
            context, 2007, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 即时待办“完成”：只清掉内存里的即时待办与通知。 */
    fun completeInstant(context: Context): PendingIntent {
        val intent = Intent(context, com.capsulepal.app.scheduler.NotificationActionReceiver::class.java).apply {
            action = ACTION_COMPLETE_INSTANT
        }
        return PendingIntent.getBroadcast(
            context, 2010, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 用户划掉双重提醒的普通通知：本阶段不再补发。 */
    fun dismissDual(context: Context): PendingIntent {
        val intent = Intent(context, com.capsulepal.app.scheduler.NotificationActionReceiver::class.java).apply {
            action = ACTION_DUAL_DISMISS
        }
        return PendingIntent.getBroadcast(
            context, 2009, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun action(context: Context, action: String, requestCode: Int, taskId: Long?): PendingIntent {
        val intent = Intent(context, com.capsulepal.app.scheduler.NotificationActionReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_TASK_ID, taskId ?: -1L)
        }
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
