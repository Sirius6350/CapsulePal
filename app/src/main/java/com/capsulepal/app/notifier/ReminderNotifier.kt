package com.capsulepal.app.notifier

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.capsulepal.app.data.model.ReminderTriggerType
import com.capsulepal.app.data.model.Task
import com.capsulepal.app.data.model.TaskKind
import com.capsulepal.app.domain.TimeUtils
import com.capsulepal.app.domain.TriggerSuggestion
import java.time.ZoneId

/**
 * 主动提醒上岛通知。
 *
 * 与“当前这件事”共用实况通知形态，但使用独立通知位：
 * 胶囊上显示待办简称，展开后显示提醒文案和“开始准备 / 知道了”。
 */
class ReminderNotifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    fun build(
        suggestion: TriggerSuggestion,
        task: Task?,
        nowMillis: Long
    ): Notification {
        val content = Content.from(suggestion, task, nowMillis)
        val accent = 0xFF6FA8DC.toInt()
        val style = NotificationCompat.ProgressStyle()
            .setStyledByProgress(false)
            .addProgressSegment(
                NotificationCompat.ProgressStyle.Segment(content.max.coerceAtLeast(1))
                    .setColor(accent)
            )
            .setProgress(content.progress.coerceIn(0, content.max))

        val builder = NotificationCompat.Builder(context, NotificationChannels.REMINDER_LIVE)
            .setSmallIcon(suggestion.iconRes)
            .setContentTitle(content.title)
            .setContentText(suggestion.message)
            .setStyle(style)
            .setShortCriticalText(content.title.take(7))
            .setSubText(content.subText.takeIf { it.isNotBlank() })
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setColor(accent)
            .setColorized(false)
            .setRequestPromotedOngoing(true)
            .setContentIntent(NotificationActions.openApp(context))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setTimeoutAfter(content.timeoutMillis)

        if (content.startMillis > nowMillis) {
            builder
                .setShowWhen(true)
                .setWhen(content.startMillis)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
        } else {
            builder.setShowWhen(false)
        }

        when {
            task != null -> builder.addAction(
                0,
                "开始准备",
                NotificationActions.reminderAccept(
                    context,
                    task.id,
                    task.title,
                    task.startMillis,
                    task.durationMinutes
                )
            )
            suggestion.draft != null -> builder.addAction(
                0,
                "加入日程",
                NotificationActions.reminderAccept(
                    context,
                    null,
                    suggestion.draft.title,
                    content.startMillis,
                    suggestion.draft.durationMinutes
                )
            )
        }
        builder.addAction(0, "知道了", NotificationActions.reminderDismiss(context))

        return builder.build()
    }

    fun buildFallback(
        suggestion: TriggerSuggestion,
        task: Task?,
        nowMillis: Long
    ): Notification {
        val content = Content.from(suggestion, task, nowMillis)
        val builder = NotificationCompat.Builder(context, NotificationChannels.TASK_FALLBACK)
            .setSmallIcon(suggestion.iconRes)
            .setContentTitle(content.title)
            .setContentText(suggestion.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.bigText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(NotificationActions.openApp(context))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        when {
            task != null -> builder.addAction(
                0,
                "开始准备",
                NotificationActions.reminderAccept(
                    context,
                    task.id,
                    task.title,
                    task.startMillis,
                    task.durationMinutes
                )
            )
            suggestion.draft != null -> builder.addAction(
                0,
                "加入日程",
                NotificationActions.reminderAccept(
                    context,
                    null,
                    suggestion.draft.title,
                    content.startMillis,
                    suggestion.draft.durationMinutes
                )
            )
        }
        builder.addAction(0, "知道了", NotificationActions.reminderDismiss(context))
        return builder.build()
    }

    fun validate(notification: Notification): ValidationResult =
        NotificationValidator.validate(context, notification, NotificationChannels.REMINDER_LIVE)

    fun post(notification: Notification): ValidationResult {
        val validation = validate(notification)
        if (validation.eligible) postInternal(notification)
        return validation
    }

    fun postFallback(notification: Notification) {
        postInternal(notification)
    }

    private fun postInternal(notification: Notification) {
        if (!NotificationValidator.hasPostPermission(context)) return
        manager.notify(NotificationActions.REMINDER_NOTIFICATION_ID, notification)
    }

    fun cancel() {
        manager.cancel(NotificationActions.REMINDER_NOTIFICATION_ID)
    }

    private data class Content(
        val title: String,
        val subText: String,
        val bigText: String,
        val progress: Int,
        val max: Int,
        val startMillis: Long,
        val timeoutMillis: Long
    ) {
        companion object {
            fun from(
                suggestion: TriggerSuggestion,
                task: Task?,
                nowMillis: Long
            ): Content {
                val draft = suggestion.draft
                val marker = when {
                    (task?.planId ?: draft?.planId ?: 0L) != 0L -> "计划 · "
                    (task?.kind ?: draft?.kind) == TaskKind.SCHEDULE -> "日程 · "
                    else -> ""
                }
                val title = marker + (
                    task?.shortTitle?.takeIf { it.isNotBlank() }?.let { it }
                        ?: task?.title
                        ?: draft?.title?.takeIf { it.isNotBlank() }
                        ?: suggestion.title.ifBlank { "准备一下" }
                    )

                val startMillis = when {
                    task != null -> task.startMillis
                    draft != null -> draft.date.atTime(draft.startTime)
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli()
                    else -> 0L
                }

                val windowEnd = when {
                    task != null && task.prepareMinutes > 0 -> task.prepareStartMillis
                    task != null -> task.startMillis
                    startMillis > 0L -> startMillis
                    else -> nowMillis + 60_000L
                }.coerceAtLeast(nowMillis + 60_000L)

                val windowMillis = windowMillis(suggestion.type)
                val windowStart = windowEnd - windowMillis
                val progress = if (windowMillis <= 0L) {
                    0
                } else {
                    ((nowMillis - windowStart).coerceAtLeast(0L) * 100 / windowMillis)
                        .toInt()
                        .coerceIn(0, 100)
                }

                val remainingMinutes = if (startMillis > nowMillis) {
                    TimeUtils.formatMinutesRounded(startMillis - nowMillis)
                } else {
                    ""
                }
                val subText = when {
                    startMillis > 0L -> buildString {
                        append(TimeUtils.formatClock(startMillis))
                        append(" 开始")
                        if (remainingMinutes.isNotBlank()) {
                            append(" · 还有 ")
                            append(remainingMinutes)
                        }
                    }
                    else -> "现在就可以动起来"
                }
                val bigText = if (subText.isNotBlank()) {
                    "${suggestion.message}\n$subText"
                } else {
                    suggestion.message
                }

                return Content(
                    title = title,
                    subText = subText,
                    bigText = bigText,
                    progress = progress,
                    max = 100,
                    startMillis = startMillis,
                    timeoutMillis = ((windowEnd - nowMillis).coerceIn(60_000L, MAX_TIMEOUT_MILLIS) + 60_000L)
                )
            }

            private fun windowMillis(type: ReminderTriggerType): Long = when (type) {
                ReminderTriggerType.HEADSET -> 45 * 60_000L
                ReminderTriggerType.CHARGING_START,
                ReminderTriggerType.CHARGING_STOP,
                ReminderTriggerType.BLUETOOTH -> 60 * 60_000L
                ReminderTriggerType.WIFI -> 30 * 60_000L
                ReminderTriggerType.CALL_ENDED,
                ReminderTriggerType.CALENDAR_SOON -> 20 * 60_000L
                ReminderTriggerType.APP_LAUNCH -> 60 * 60_000L
            }

            private const val MAX_TIMEOUT_MILLIS = 90 * 60_000L
        }
    }
}
