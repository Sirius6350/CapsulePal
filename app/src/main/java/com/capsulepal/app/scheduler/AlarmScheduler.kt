package com.capsulepal.app.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.capsulepal.app.data.model.AlarmPoint
import com.capsulepal.app.data.model.Task
import com.capsulepal.app.domain.ReminderWindows
import com.capsulepal.app.domain.TimeUtils
import com.capsulepal.app.service.LiveUpdateService
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class AlarmScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    fun canScheduleExact(): Boolean =
        alarmManager?.canScheduleExactAlarms() ?: false

    fun exactAlarmSettingsIntent(): Intent =
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun scheduleTask(task: Task): Boolean {
        if (!task.isOpen) return false
        val exact = canScheduleExact()
        if (task.prepareMinutes > 0) {
            schedulePoint(task.id, AlarmPoint.PREPARE_START, task.prepareStartMillis, exact)
        }
        schedulePoint(task.id, AlarmPoint.TASK_START, task.startMillis, exact)
        schedulePoint(task.id, AlarmPoint.TASK_END, task.endMillis, exact)
        return exact
    }

    fun cancelTask(taskId: Long) {
        AlarmPoint.entries.forEach { point ->
            alarmManager?.cancel(taskPendingIntent(taskId, point))
        }
    }

    /** 清除全部本机数据前调用：撤掉这些待办的闹钟，并清掉日历与悬浮点心跳。 */
    fun cancelAll(taskIds: List<Long>) {
        taskIds.forEach { cancelTask(it) }
        cancelOffsetTick()
        cancelCalendarWatch()
    }

    fun reschedule(tasks: List<Task>) {
        val now = System.currentTimeMillis()
        // 系统对精确闹钟有配额上限，按「活跃 / 预活跃 / 沉睡」三档分配：
        // 今天剩下的待办排完整三点闹钟；未来一周只排开始点且强制非精确，不占精确配额；
        // 更远的待办进入沉睡，由每日唤醒重新纳入。
        val buckets = ReminderWindows.classify(
            tasks = tasks,
            nowMillis = now,
            endOfTodayMillis = TimeUtils.startOfDay(LocalDate.now().plusDays(1)),
            horizonMillis = ReminderWindows.PREACTIVE_HORIZON_DAYS * DAY_MILLIS
        )
        buckets.active.take(MAX_ACTIVE_TASKS).forEach { scheduleTask(it) }
        buckets.preactive.take(MAX_PREACTIVE_TASKS).forEach { task ->
            schedulePoint(task.id, AlarmPoint.TASK_START, task.startMillis, exact = false)
        }
        scheduleDailyWake()
    }

    /** 每天 00:05 唤醒一次：重排闹钟，把新进入窗口的沉睡待办纳入。 */
    fun scheduleDailyWake() {
        alarmManager?.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            nextDailyWakeAt(),
            dailyWakePendingIntent()
        )
    }

    private fun nextDailyWakeAt(): Long {
        val zone = ZoneId.systemDefault()
        val now = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(zone)
        var trigger = now.toLocalDate().atTime(DAILY_WAKE_HOUR, DAILY_WAKE_MINUTE).atZone(zone)
        if (!trigger.isAfter(now)) trigger = trigger.plusDays(1)
        return trigger.toInstant().toEpochMilli()
    }

    fun scheduleOffsetTick(minutes: Int) {
        // 仅用于兼容旧版本遗留的悬浮点心跳；新版本由前台服务内的协程循环驱动移动。
        val triggerAt = System.currentTimeMillis() + minutes.coerceIn(1, 120) * 60_000L
        alarmManager?.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAt,
            overlayPendingIntent()
        )
    }

    fun cancelOffsetTick() {
        alarmManager?.cancel(overlayPendingIntent())
    }

    /**
     * 主动提醒的日历心跳。使用 allow-while-idle 的轻量闹钟，
     * 每 15 分钟检查一次即将开始的系统日程；夜间自动降频。
     */
    fun scheduleCalendarTick(delayMinutes: Long = 15L) {
        val triggerAt = System.currentTimeMillis() + delayMinutes.coerceIn(5L, 120L) * 60_000L
        alarmManager?.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAt,
            calendarTickPendingIntent()
        )
    }

    fun cancelCalendarTick() {
        alarmManager?.cancel(calendarTickPendingIntent())
    }

    /** 日历事件开始前 N 分钟的精确闹钟；只有一个“最近事件”的槽位。 */
    fun scheduleCalendarEvent(triggerAt: Long) {
        if (triggerAt <= System.currentTimeMillis()) return
        val pending = calendarEventPendingIntent()
        val manager = alarmManager ?: return
        runCatching {
            if (manager.canScheduleExactAlarms()) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }
        }
    }

    fun cancelCalendarWatch() {
        alarmManager?.cancel(calendarTickPendingIntent())
        alarmManager?.cancel(calendarEventPendingIntent())
    }

    fun scheduleSnooze(task: Task, minutes: Int) {
        val triggerAt = System.currentTimeMillis() + minutes * 60_000L
        setAlarm(
            triggerAt,
            taskPendingIntent(task.id, AlarmPoint.PREPARE_START),
            canScheduleExact()
        )
    }

    fun schedulePoint(taskId: Long, point: AlarmPoint, triggerAt: Long, exact: Boolean) {
        if (triggerAt <= System.currentTimeMillis()) return
        setAlarm(triggerAt, taskPendingIntent(taskId, point), exact)
    }

    private fun setAlarm(triggerAt: Long, pendingIntent: PendingIntent, exact: Boolean) {
        val manager = alarmManager ?: return
        // 闹钟配额用尽时系统会抛 IllegalStateException；单个闹钟失败不能拖垮整个应用。
        runCatching {
            if (exact && manager.canScheduleExactAlarms()) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        }
    }

    private fun taskPendingIntent(
        taskId: Long,
        point: AlarmPoint
    ): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_TASK_ALARM
            putExtra(EXTRA_TASK_ID, taskId)
            putExtra(EXTRA_POINT, point.name)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode(taskId, point),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun overlayPendingIntent(): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_OVERLAY_TICK
        }
        return PendingIntent.getBroadcast(
            context,
            OFFSET_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun requestCode(taskId: Long, point: AlarmPoint): Int =
        (taskId * 10L + point.ordinal).toInt()

    private fun calendarTickPendingIntent(): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_CALENDAR_TICK
        }
        return PendingIntent.getBroadcast(
            context,
            CALENDAR_TICK_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun calendarEventPendingIntent(): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_CALENDAR_EVENT
        }
        return PendingIntent.getBroadcast(
            context,
            CALENDAR_EVENT_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun dailyWakePendingIntent(): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_DAILY_WAKE
        }
        return PendingIntent.getBroadcast(
            context,
            DAILY_WAKE_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val ACTION_TASK_ALARM = "com.capsulepal.app.action.TASK_ALARM"
        const val ACTION_OVERLAY_TICK = "com.capsulepal.app.action.OVERLAY_TICK"
        const val ACTION_CALENDAR_TICK = "com.capsulepal.app.action.CALENDAR_TICK"
        const val ACTION_CALENDAR_EVENT = "com.capsulepal.app.action.CALENDAR_EVENT"
        const val ACTION_DAILY_WAKE = "com.capsulepal.app.action.DAILY_WAKE"
        const val EXTRA_TASK_ID = "extra_alarm_task_id"
        const val EXTRA_POINT = "extra_alarm_point"

        const val OFFSET_REQUEST_CODE = 9_000_001
        const val CALENDAR_TICK_REQUEST_CODE = 9_000_002
        const val CALENDAR_EVENT_REQUEST_CODE = 9_000_003
        const val DAILY_WAKE_REQUEST_CODE = 9_000_004

        private const val DAY_MILLIS = 24 * 60 * 60_000L

        /** 每日唤醒的本地时间：00:05。 */
        private const val DAILY_WAKE_HOUR = 0
        private const val DAILY_WAKE_MINUTE = 5

        /** 活跃档单次最多排程的任务数；每个任务最多 3 个闹钟，用精确闹钟尽快提醒。 */
        private const val MAX_ACTIVE_TASKS = 120

        /** 预活跃档单次最多排程的任务数；只排开始闹钟，走 allow-while-idle 不占精确配额。 */
        private const val MAX_PREACTIVE_TASKS = 200
    }
}
