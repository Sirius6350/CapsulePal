package com.capsulepal.app.notifier

import android.app.Notification
import android.content.Context
import com.capsulepal.app.data.model.Task
import com.capsulepal.app.data.model.TaskPhase
import com.capsulepal.app.data.repo.PlanRepository
import com.capsulepal.app.data.repo.SettingsRepository
import com.capsulepal.app.data.repo.TaskRepository
import com.capsulepal.app.domain.ReminderPolicy
import com.capsulepal.app.domain.ReminderTrace
import com.capsulepal.app.domain.TaskStepSchedule
import com.capsulepal.app.domain.TriggerSuggestion
import com.capsulepal.app.monitor.MonitorState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import java.time.LocalDate

sealed interface NotificationPlan {
    data class Live(
        val notification: Notification,
        val content: TaskNotificationContent,
        val refreshIntervalMillis: Long = 60_000L
    ) : NotificationPlan

    data class Fallback(
        val notification: Notification,
        val content: TaskNotificationContent,
        val refreshIntervalMillis: Long = 60_000L
    ) : NotificationPlan

    data object None : NotificationPlan
}

class TaskNotificationCoordinator(
    private val context: Context,
    private val taskRepository: TaskRepository,
    private val planRepository: PlanRepository,
    private val settingsRepository: SettingsRepository,
    private val chargingState: () -> MonitorState? = { null }
) {

    private val liveNotifier = LiveUpdateNotifier(context)
    private val fallbackNotifier = FallbackNotifier(context)
    private val dualReminderNotifier = DualReminderNotifier(context)
    private val reminderNotifier = ReminderNotifier(context)

    @Volatile
    private var reminderTaskId: Long? = null

    private val _lastValidation = MutableStateFlow<ValidationResult?>(null)
    val lastValidation: StateFlow<ValidationResult?> = _lastValidation

    @Volatile
    private var testStartedAt = 0L

    /** 用户点过“收到”的充电会话；同一会话不再上岛，下次插电会换新 id。 */
    @Volatile
    private var chargingAckSessionId = -1L

    /**
     * 待办点了“收到”之后，插上电源时补一次「待办 + 充电功率」的岛。
     * 记录已经补过的待办；拔电时清空，下次插电可以再补一次。
     */
    @Volatile
    private var chargingRetriggerTaskId = 0L

    /** 补上岛后用户又点了“收到”，本次插电期间不再补。 */
    @Volatile
    private var chargingRetriggerSuppressedTaskId = 0L

    /** 上一次看到的插电状态，用于识别“刚插上”的那一刻。 */
    @Volatile
    private var lastPlugged = false

    fun acknowledgeCharging(sessionId: Long) {
        // 还没有落库会话时不记录，避免把之后所有 sessionId=0 的充电岛一起压掉。
        if (sessionId <= 0L) return
        chargingAckSessionId = sessionId
    }

    /** 待办再次点“收到”时调用：本次插电期间不再自动补岛。 */
    fun onTaskAcknowledged(taskId: Long) {
        if (chargingRetriggerTaskId == taskId) {
            chargingRetriggerSuppressedTaskId = taskId
        }
    }

    /** 开启一条虚拟测试待办：现在进入 1 分钟准备窗口，随后进行 1 分钟。 */
    fun startIslandTest() {
        testStartedAt = System.currentTimeMillis()
    }

    /** 取消测试，不改动任何真实待办与成长。 */
    fun cancelTest() {
        testStartedAt = 0L
    }

    fun testActive(): Boolean = testStartedAt > 0L

    private fun activeTestContent(nowMillis: Long): TaskNotificationContent? {
        val startedAt = testStartedAt
        if (startedAt <= 0L) return null
        val elapsed = nowMillis - startedAt
        if (elapsed < 0L || elapsed >= NotificationContentFactory.TEST_TOTAL_MILLIS) {
            testStartedAt = 0L
            return null
        }
        return NotificationContentFactory.testContent(elapsed)
    }

    suspend fun buildPlan(nowMillis: Long = System.currentTimeMillis()): NotificationPlan {
        val settings = settingsRepository.settings.first()

        // 测试优先占用同一条任务通知，结束后自动回到真实时间线。
        activeTestContent(nowMillis)?.let { content ->
            val notification = liveNotifier.build(content)
            val validation = liveNotifier.validate(notification)
            _lastValidation.value = validation
            syncDualReminder(
                content,
                settings.dualReminderEnabled && validation.eligible && !isQuiet(settings, nowMillis)
            )
            return if (validation.eligible) {
                NotificationPlan.Live(notification, content)
            } else {
                NotificationPlan.Fallback(fallbackNotifier.build(content), content)
            }
        }

        val today = LocalDate.now()
        val tasks = taskRepository.openTasks()
            .filter { settings.version.isTaskVisible(it) }
            .filter { it.date == today || it.date == today.plusDays(1) }
        val state = com.capsulepal.app.domain.ActiveTaskResolver.resolve(tasks, nowMillis)
        val charging = chargingState()
        // 到充电上限后系统会暂停充电但仍插着电，这里统一按“是否插电”判断。
        val plugged = charging?.let { it.reading.charging || it.reading.plugged } == true
        val justPlugged = plugged && !lastPlugged
        lastPlugged = plugged
        if (!plugged) {
            // 拔电后允许下次插电再补一次。
            chargingRetriggerTaskId = 0L
            chargingRetriggerSuppressedTaskId = 0L
        }
        val planTitles = if (state.active?.task?.planId?.let { it != 0L } == true) {
            planRepository.plans().associate { it.id to it.title }
        } else {
            emptyMap()
        }
        val content = NotificationContentFactory.fromTimeline(state, nowMillis, charging, planTitles)
        if (content == null) {
            // 没有待办上岛时，充电中优先展示充电实况。
            return buildChargingPlan(nowMillis)
        }

        // 只有「同一件事」已经进入准备/进行态时才收起它的主动提醒。
        // 这里不能无条件 cancelReminder()：充电时刷新循环是 1 秒一次，
        // 会把刚发出的充电 / WiFi / 日历提醒在下一轮就吞掉。
        content.taskId?.let { taskId ->
            if (reminderTaskId == taskId) {
                ReminderTrace.record(
                    content.taskTitle.ifBlank { "主动提醒" },
                    "同一件事已上岛，收起主动提醒",
                    false
                )
                cancelReminder(taskId)
            }
        }

        // 用户点过“收到”的待办，本次不再上岛；
        // 但在待办期间插上电源时，补一次带充电功率的岛上，直到拔电或再次点“收到”。
        if (content.taskId != null && content.taskId == settings.acknowledgedTaskId) {
            val taskId = content.taskId
            val retriggered = chargingRetriggerTaskId == taskId
            val suppressed = chargingRetriggerSuppressedTaskId == taskId
            if (plugged && !suppressed && (justPlugged || retriggered)) {
                if (!retriggered) chargingRetriggerTaskId = taskId
            } else {
                // 待办不再上岛时，别让这一轮直接收成空白：
                // 插着电就把位置让给充电功率岛，拔电时 buildChargingPlan 会自行收起。
                return buildChargingPlan(nowMillis)
            }
        }

        val liveNotification = liveNotifier.build(content)
        val validation = liveNotifier.validate(liveNotification)
        _lastValidation.value = validation
        // 只有真正上岛时才需要第二条普通通知；降级通知本身就是提醒。
        // 免打扰时段收起会响铃的第二条通知，实况岛仍保留状态。
        syncDualReminder(
            content,
            settings.dualReminderEnabled && validation.eligible && !isQuiet(settings, nowMillis)
        )

        // 充电时卡片里的时长/速度/温度要跟上，功率按 1 秒刷新才有实时感。
        val chargingNow = charging?.let { it.reading.charging || it.reading.plugged } == true
        // 步骤切换要在边界上准时刷新，其余时间仍按分钟走。
        val stepBoundary = if (chargingNow) {
            null
        } else {
            state.active
                ?.takeIf { it.phase == TaskPhase.IN_PROGRESS }
                ?.let { TaskStepSchedule.progress(it.task, nowMillis).nextBoundaryMillis }
        }
        val refreshMillis = when {
            chargingNow -> CHARGING_REFRESH_INTERVAL_MILLIS
            stepBoundary != null && stepBoundary > nowMillis ->
                (stepBoundary - nowMillis).coerceIn(500L, 60_000L)
            else -> 60_000L
        }
        return if (validation.eligible) {
            NotificationPlan.Live(liveNotification, content, refreshMillis)
        } else {
            NotificationPlan.Fallback(fallbackNotifier.build(content), content, refreshMillis)
        }
    }

    /** 没有待办上岛时，充电中展示功率、本次时长/速度与温度。 */
    private fun buildChargingPlan(nowMillis: Long): NotificationPlan {
        // 没有任务岛时，双重提醒的第二条通知也一并收起，避免留下过时文案。
        dualReminderNotifier.cancel()
        val monitor = chargingState()
        // 到充电上限后系统会暂停充电但电仍插着，这种情况也要保留充电岛。
        if (monitor == null || (!monitor.reading.charging && !monitor.reading.plugged)) {
            // 拔掉电源后重置“收到”，下次插电一定重新上岛。
            chargingAckSessionId = -1L
            cancel()
            return NotificationPlan.None
        }
        val content = NotificationContentFactory.chargingContent(monitor, nowMillis)
        if (content.sessionId > 0L && content.sessionId == chargingAckSessionId) {
            cancel()
            return NotificationPlan.None
        }
        val notification = liveNotifier.build(content)
        val validation = liveNotifier.validate(notification)
        _lastValidation.value = validation
        return if (validation.eligible) {
            NotificationPlan.Live(notification, content, CHARGING_REFRESH_INTERVAL_MILLIS)
        } else {
            NotificationPlan.Fallback(
                fallbackNotifier.build(content),
                content,
                CHARGING_REFRESH_INTERVAL_MILLIS
            )
        }
    }

    private fun syncDualReminder(content: TaskNotificationContent, enabled: Boolean) {
        if (!enabled) {
            dualReminderNotifier.cancel()
            return
        }
        val key = "${content.taskId ?: 0L}:${content.phase}"
        dualReminderNotifier.post(
            notification = dualReminderNotifier.build(content),
            key = key
        )
    }

    /** 免打扰时段只保留静默的实况岛，普通提醒不弹出、不响铃。 */
    private fun isQuiet(
        settings: com.capsulepal.app.data.model.AppSettings,
        nowMillis: Long
    ): Boolean = settings.quietHoursEnabled &&
        ReminderPolicy.isQuietMinute(
            nowMillis = nowMillis,
            startMinute = settings.quietStartMinute,
            endMinute = settings.quietEndMinute
        )

    /** 用户划掉了双重提醒的普通通知，本阶段不再补发。 */
    fun markDualReminderDismissed() {
        dualReminderNotifier.onDismissed()
    }

    fun cancel() {
        liveNotifier.cancel()
        fallbackNotifier.cancel()
        dualReminderNotifier.cancel()
    }

    /** 主动提醒上岛；设备不支持实况通知时降级为普通高优先级通知。 */
    fun showReminder(suggestion: TriggerSuggestion, task: Task?, nowMillis: Long): ValidationResult {
        reminderTaskId = suggestion.taskId
        val live = reminderNotifier.build(suggestion, task, nowMillis)
        val validation = reminderNotifier.validate(live)
        if (validation.eligible) {
            reminderNotifier.post(live)
        } else {
            reminderNotifier.postFallback(reminderNotifier.buildFallback(suggestion, task, nowMillis))
        }
        return validation
    }

    /**
     * 收起主动提醒。传入 taskId 时只在提醒属于该待办时收起，
     * 避免完成另一件事时误伤当前提醒。
     */
    fun cancelReminder(taskId: Long? = null) {
        if (taskId != null && reminderTaskId != null && reminderTaskId != taskId) return
        reminderNotifier.cancel()
        reminderTaskId = null
    }

    fun postFallback(notification: Notification) {
        fallbackNotifier.post(notification)
    }

    fun postLive(notification: Notification) {
        liveNotifier.postInternal(notification)
    }

    fun cancelPreview() {
        liveNotifier.cancelPreview()
        fallbackNotifier.cancelPreview()
    }

    fun placeholder(): Notification = liveNotifier.placeholder()

    fun channelId(): String = liveNotifier.channelId()

    fun lastValidationNow(): ValidationResult? = _lastValidation.value

    private companion object {
        /** 只要岛上有充电功率，就按 1 秒刷新，保证功率是实时的。 */
        const val CHARGING_REFRESH_INTERVAL_MILLIS = 1_000L
    }
}
