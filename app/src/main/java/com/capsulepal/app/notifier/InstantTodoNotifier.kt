package com.capsulepal.app.notifier

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.capsulepal.app.R
import com.capsulepal.app.data.repo.InstantTodo

/**
 * 即时待办使用独立通知位，让它和普通待办通知同时存在。
 * 部分 ROM 的流体云只展示最新一条，因此每次刷新都最后投递即时待办。
 */
class InstantTodoNotifier(private val context: Context) {

    private val appContext = context.applicationContext
    private val manager = NotificationManagerCompat.from(appContext)

    fun post(todo: InstantTodo) {
        if (!NotificationValidator.hasPostPermission(appContext)) return
        NotificationChannels.ensure(appContext)
        val accent = 0xFFE8735A.toInt()
        val style = NotificationCompat.ProgressStyle()
            .setStyledByProgress(false)
            .addProgressSegment(
                NotificationCompat.ProgressStyle.Segment(1).setColor(accent)
            )
            .setProgress(0)

        val builder = NotificationCompat.Builder(appContext, NotificationChannels.TASK_LIVE)
            .setSmallIcon(R.drawable.ic_instant_eyes)
            .setContentTitle(todo.title)
            .setContentText("即时待办 · 进行中")
            .setSubText("紧急优先")
            // 胶囊右侧固定显示待办名称（上限 7 字），不再显示通用标签。
            .setShortCriticalText(todo.title.take(7))
            .setStyle(style)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(true)
            .setWhen(todo.startedAtMillis)
            .setUsesChronometer(true)
            .setChronometerCountDown(false)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setColor(accent)
            .setColorized(false)
            .setRequestPromotedOngoing(true)
            .setContentIntent(NotificationActions.openApp(appContext))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                0,
                "完成",
                NotificationActions.completeInstant(appContext)
            )

        runCatching { manager.notify(NotificationActions.INSTANT_NOTIFICATION_ID, builder.build()) }
    }

    fun cancel() {
        runCatching { manager.cancel(NotificationActions.INSTANT_NOTIFICATION_ID) }
    }
}
