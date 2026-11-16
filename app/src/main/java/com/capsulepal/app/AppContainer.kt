package com.capsulepal.app

import android.content.Context
import com.capsulepal.app.data.db.CapsuleDatabase
import com.capsulepal.app.data.backup.BackupKind
import com.capsulepal.app.data.backup.BackupRepository
import com.capsulepal.app.data.model.AlarmPoint
import com.capsulepal.app.data.model.AppSettings
import com.capsulepal.app.data.model.PartnerProfile
import com.capsulepal.app.data.model.Task
import com.capsulepal.app.data.model.TaskDraft
import com.capsulepal.app.data.model.TaskStatus
import com.capsulepal.app.data.model.TemplateType
import com.capsulepal.app.data.repo.BatteryRepository
import com.capsulepal.app.data.repo.GeekRepository
import com.capsulepal.app.data.repo.InstantTodoRepository
import com.capsulepal.app.data.repo.PartnerRepository
import com.capsulepal.app.data.repo.PlanRepository
import com.capsulepal.app.data.repo.SettingsRepository
import com.capsulepal.app.data.repo.TaskRepository
import com.capsulepal.app.data.model.ReminderRule
import com.capsulepal.app.data.model.ReminderActionType
import com.capsulepal.app.data.model.ReminderTriggerType
import com.capsulepal.app.data.model.needsBackgroundWatch
import com.capsulepal.app.data.model.toReminderTriggerType
import com.capsulepal.app.domain.GrowthRules
import com.capsulepal.app.domain.ReminderDiagnostics
import com.capsulepal.app.domain.ReminderPolicy
import com.capsulepal.app.domain.ReminderTrace
import com.capsulepal.app.domain.SignalType
import com.capsulepal.app.domain.SystemSignal
import com.capsulepal.app.domain.TimeUtils
import com.capsulepal.app.domain.TriggerEngine
import com.capsulepal.app.domain.TriggerSuggestion
import com.capsulepal.app.monitor.BatteryMonitor
import com.capsulepal.app.monitor.RuntimeMetrics
import com.capsulepal.app.notifier.InstantTodoNotifier
import com.capsulepal.app.notifier.NotificationPlan
import com.capsulepal.app.notifier.NotificationValidator
import com.capsulepal.app.notifier.TaskNotificationCoordinator
import com.capsulepal.app.scheduler.AlarmScheduler
import com.capsulepal.app.service.FloatingKeepAliveService
import com.capsulepal.app.service.LiveUpdateService
import com.capsulepal.app.service.MetricOverlayController
import com.capsulepal.app.service.OverlayDotController
import com.capsulepal.app.signals.SystemSignalProvider
import com.capsulepal.app.signals.UsageAccess
import com.capsulepal.app.widget.CapsuleWidgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

data class CompletionOutcome(
    val taskTitle: String,
    val gainedXp: Int,
    val profile: PartnerProfile,
    val fullXpRemaining: Int
)

enum class RestoreResult {
    /** 已按当前时间回溯到准备前 / 准备中 / 进行中。 */
    RESTORED,

    /** 待办时间已经结束，无法恢复。 */
    ENDED,

    /** 当前状态本来就是空的，无需恢复。 */
    NOT_RESTORABLE
}

class AppContainer(context: Context) {

    private val appContext = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val database = CapsuleDatabase.get(appContext)

    val taskRepository = TaskRepository(
        database.taskDao(),
        database.templateDao(),
        database.groupDao(),
        database.planDao()
    )
    val planRepository = PlanRepository(
        database.planDao(),
        database.taskDao()
    )
    val settingsRepository = SettingsRepository(appContext)
    val partnerRepository = PartnerRepository(appContext)
    val batteryRepository = BatteryRepository(database.batteryDao())
    val geekRepository = GeekRepository(appContext, database.batteryDao())
    val batteryMonitor = BatteryMonitor(
        appContext,
        batteryRepository,
        geekRepository,
        settingsRepository,
        appScope
    )
    val scheduler = AlarmScheduler(appContext)
    val notifications = TaskNotificationCoordinator(
        appContext,
        taskRepository,
        settingsRepository,
        chargingState = { batteryMonitor.state.value }
    )
    val signals = SystemSignalProvider(appContext, appScope)

    /** 悬浮点窗口管理器：挂在 Application 上，服务重建也不丢窗口。 */
    val overlayDot = OverlayDotController(appContext)

    /** 极客版独立指标悬浮窗：与保活悬浮点互不影响。可拖动、双击关闭。 */
    val metricOverlay = MetricOverlayController(
        appContext,
        onRequestClose = {
            appScope.launch { settingsRepository.setMetricOverlayEnabled(false) }
        },
        onPositionChanged = { x, y ->
            appScope.launch { settingsRepository.setMetricOverlayPosition(x, y) }
        }
    )

    /** 即时待办只活在进程内存里，杀进程即消失。 */
    val instantTodoRepository = InstantTodoRepository()
    val instantTodoNotifier = InstantTodoNotifier(appContext)

    val backupRepository = BackupRepository(
        context = appContext,
        taskRepository = taskRepository,
        planRepository = planRepository,
        partnerRepository = partnerRepository,
        settingsRepository = settingsRepository,
        batteryDao = database.batteryDao(),
        batteryRepository = batteryRepository,
        geekRepository = geekRepository
    ) { onBackupRestored() }

    private val triggerEngine = TriggerEngine()
    private val _triggerSuggestion = MutableStateFlow<TriggerSuggestion?>(null)
    val triggerSuggestion: StateFlow<TriggerSuggestion?> = _triggerSuggestion

    /** 充电时进程内的 1 秒刷新兜底，服务拉不起来时也能实时更新功率。 */
    private var chargingTickerJob: Job? = null

    private val _exactAlarmAvailable = MutableStateFlow(true)
    val exactAlarmAvailable: StateFlow<Boolean> = _exactAlarmAvailable

    fun start() {
        _exactAlarmAvailable.value = scheduler.canScheduleExact()
        appScope.launch {
            notifications.cancelPreview()
            // 新进程里内存态一定是空的：清掉上次进程遗留的即时待办通知。
            instantTodoNotifier.cancel()
            taskRepository.seedTemplatesIfEmpty()
            val settings = settingsRepository.settings.first()
            // 旧版本编辑重复待办会把 seriesId 写成 0：启动时先把系列关系修回来，再补未来实例。
            taskRepository.repairSeriesLinks()
            taskRepository.ensureSeriesHorizon(include = { settings.version.isTaskVisible(it) })
            partnerRepository.ensureCompanionSince()
            syncRuntimeMetrics(settings)
            syncBackgroundWatch(settings)
            sweepFinishedTasks(refresh = false)
            val open = taskRepository.openTasks().filter { settings.version.isTaskVisible(it) }
            scheduler.reschedule(open)
            refreshCalendarNow()
            rescheduleCalendarWatch()
            LiveUpdateService.refresh(appContext)
        }
        signals.start()
        batteryMonitor.start()
        observeChargingForIsland()
        observeSignals()
    }

    /** 插拔电源时立刻刷新上岛，不必等下一次 60 秒轮询。 */
    private fun observeChargingForIsland() {
        appScope.launch {
            var lastPlugged: Boolean? = null
            batteryMonitor.state.collect { state ->
                // 到充电上限后系统会暂停充电但仍插着电，这里按“是否插电”判断。
                val plugged = state?.let { it.reading.charging || it.reading.plugged } == true
                // 电池读数同时驱动桌面组件的电量/温度；内部已做 20 秒节流。
                CapsuleWidgets.requestUpdate(appContext)
                if (plugged != lastPlugged) {
                    lastPlugged = plugged
                    LiveUpdateService.refresh(appContext)
                    if (plugged) startChargingTicker() else stopChargingTicker()
                }
            }
        }
    }

    /**
     * 插电后立刻开始每秒刷新一次。
     * 正常情况下由 LiveUpdateService 的刷新循环负责；当后台拉不起前台服务时
     * （例如待办点过“收到”后服务已停止），这里直接投递通知兜底，
     * 保证充电功率从第一秒起就是实时的，而不是等一分钟后的下一轮刷新。
     */
    private fun startChargingTicker() {
        chargingTickerJob?.cancel()
        chargingTickerJob = appScope.launch {
            while (true) {
                delay(1_000L)
                val plugged = batteryMonitor.state.value
                    ?.let { it.reading.charging || it.reading.plugged } == true
                if (!plugged) break
                if (!LiveUpdateService.isRunning()) refreshIslandDirect()
            }
        }
    }

    private fun stopChargingTicker() {
        chargingTickerJob?.cancel()
        chargingTickerJob = null
    }

    /** 创建即时待办：只写内存；已有未完成时返回 false。 */
    fun createInstantTodo(title: String): Boolean {
        val created = instantTodoRepository.create(title)
        if (created) {
            instantTodoRepository.current()?.let(instantTodoNotifier::post)
            CapsuleWidgets.requestUpdate(appContext, 0L)
            LiveUpdateService.refresh(appContext)
        }
        return created
    }

    /** 完成即时待办：立刻从页面与通知里消失，不写任何数据库。 */
    fun completeInstantTodo() {
        if (instantTodoRepository.complete() != null) {
            instantTodoNotifier.cancel()
            CapsuleWidgets.requestUpdate(appContext, 0L)
            LiveUpdateService.refresh(appContext)
        }
    }

    /** 普通待办通知刷新后，最后再投递即时待办，尽量让它占据岛上主位。 */
    internal fun refreshInstantTodoNotification() {
        instantTodoRepository.current()?.let(instantTodoNotifier::post)
    }

    /**
     * 后台启动前台服务被系统拦截时的兜底：直接投递一次当前上岛通知，
     * 至少让充电岛 / 任务岛出现，而不是完全静默。
     */
    internal fun refreshIslandDirect() {
        appScope.launch {
            when (val plan = notifications.buildPlan()) {
                is NotificationPlan.Live -> notifications.postLive(plan.notification)
                is NotificationPlan.Fallback -> notifications.postFallback(plan.notification)
                NotificationPlan.None -> notifications.cancel()
            }
            refreshInstantTodoNotification()
        }
    }

    fun onForeground(foreground: Boolean) {
        if (foreground) {
            batteryMonitor.poke()
            CapsuleWidgets.requestUpdate(appContext, 5_000L)
            appScope.launch {
                sweepFinishedTasks()
                refreshCalendarNow()
            }
            // 回到前台时补一次上岛刷新：充电岛被系统收掉后能在这里恢复。
            LiveUpdateService.refresh(appContext)
        }
    }

    /** 版本切换后即时生效：重排闹钟与上岛、同步后台服务，并让电池轮询按新版本工作。 */
    fun onVersionChanged() {
        CapsuleWidgets.requestUpdate(appContext, 0L)
        appScope.launch {
            val settings = settingsRepository.settings.first()
            syncRuntimeMetrics(settings)
            val open = taskRepository.openTasks()
            val visible = open.filter { settings.version.isTaskVisible(it) }
            val hidden = open.filterNot { settings.version.isTaskVisible(it) }
            // 切到隐藏模块时撤销闹钟、提醒与任务岛，数据本身保留。
            hidden.forEach { task ->
                scheduler.cancelTask(task.id)
                notifications.cancelReminder(task.id)
            }
            scheduler.reschedule(visible)
            if (hidden.isNotEmpty()) {
                notifications.cancel()
            }
            if (settings.version.showReminders) {
                rescheduleCalendarWatch()
            } else {
                // 极简效率 / 极简待办不提供提醒：切过来就撤掉日历心跳与进行中的提醒。
                scheduler.cancelCalendarWatch()
                notifications.cancelReminder()
            }
            syncBackgroundWatch(settings)
            batteryMonitor.poke()
            LiveUpdateService.refresh(appContext)
        }
    }

    /** 只给当前版本可见的模块排闹钟；隐藏模块的待办保持冻结。 */
    private suspend fun rescheduleVisibleTasks() {
        val version = settingsRepository.settings.first().version
        scheduler.reschedule(taskRepository.openTasks().filter { version.isTaskVisible(it) })
    }

    suspend fun completeTask(task: Task, refresh: Boolean = true): CompletionOutcome? {
        val current = taskRepository.task(task.id) ?: return null
        if (current.status == TaskStatus.COMPLETED) return null
        if (!current.isOpen && current.status != TaskStatus.SKIPPED) return null

        val settings = settingsRepository.settings.first()
        var profile = partnerRepository.profile.first()
        var gainedXp = 0
        val previousAward = taskRepository.awardedXp(current.id)
        val awardedXp: Int
        if (previousAward > 0) {
            // 改回完成或从空状态重新完成：补回该任务上次所得，避免反复切换刷分。
            awardedXp = previousAward
            gainedXp = awardedXp
            profile = partnerRepository.addCompletion(awardedXp)
        } else {
            gainedXp = GrowthRules.xpForCompletion(profile.completedToday, settings.dailyFullXpCount)
            profile = partnerRepository.addCompletion(gainedXp)
            awardedXp = gainedXp
        }
        taskRepository.setStatusWithXp(current.id, TaskStatus.COMPLETED, awardedXp)
        scheduler.cancelTask(current.id)
        notifications.cancelReminder(current.id)
        if (refresh) LiveUpdateService.refresh(appContext)
        return CompletionOutcome(
            taskTitle = current.title,
            gainedXp = gainedXp,
            profile = profile,
            fullXpRemaining = GrowthRules.fullXpRemaining(
                profile.completedToday,
                settings.dailyFullXpCount
            )
        )
    }

    /** 已完成 → 未完成：回扣该任务所得经验（等级允许下降）。 */
    suspend fun uncompleteTask(task: Task): Int? {
        val current = taskRepository.task(task.id) ?: return null
        if (current.status != TaskStatus.COMPLETED) return null
        val refund = taskRepository.awardedXp(current.id)
        // 保留 awardedXp，用户改回完成时可原额补回。
        taskRepository.setStatusWithXp(current.id, TaskStatus.SKIPPED, refund)
        if (refund > 0) {
            partnerRepository.removeCompletion(refund)
        }
        scheduler.cancelTask(current.id)
        LiveUpdateService.refresh(appContext)
        return refund
    }

    /**
     * 完成 / 未完成 → 空：回扣该任务所得成长，并按当前时间回溯到准备前、准备中或进行中。
     * 已结束的待办不再允许恢复。
     */
    suspend fun restoreTask(task: Task): RestoreResult {
        val current = taskRepository.task(task.id) ?: return RestoreResult.NOT_RESTORABLE
        val now = System.currentTimeMillis()
        if (current.endMillis <= now) return RestoreResult.ENDED
        if (current.status != TaskStatus.COMPLETED && current.status != TaskStatus.SKIPPED) {
            return RestoreResult.NOT_RESTORABLE
        }
        if (current.status == TaskStatus.COMPLETED) {
            val refund = taskRepository.awardedXp(current.id)
            // 保留 awardedXp，再次完成时按原额补回。
            if (refund > 0) {
                partnerRepository.removeCompletion(refund)
            }
        }
        val restored = when {
            now >= current.startMillis -> TaskStatus.IN_PROGRESS
            current.prepareMinutes > 0 && now >= current.prepareStartMillis ->
                TaskStatus.PREPARE_WINDOW
            else -> TaskStatus.IDLE
        }
        taskRepository.setStatus(current.id, restored)
        settingsRepository.clearAcknowledgedTask(current.id)
        scheduler.scheduleTask(current.copy(status = restored))
        notifications.cancelReminder(current.id)
        LiveUpdateService.refresh(appContext)
        return RestoreResult.RESTORED
    }

    /** 把今天已经结束但还没处理的待办静默结算为完成。 */
    suspend fun sweepFinishedTasks(refresh: Boolean = true) {
        val now = System.currentTimeMillis()
        val todayStart = TimeUtils.startOfDay(LocalDate.now())
        val version = settingsRepository.settings.first().version
        val finished = taskRepository.openTasks()
            .filter { version.isTaskVisible(it) }
            .filter { it.endMillis in todayStart..now }
            .sortedBy { it.endMillis }
        finished.forEach { completeTask(it, refresh = false) }
        if (refresh) LiveUpdateService.refresh(appContext)
    }

    suspend fun skipTask(task: Task) {
        taskRepository.setStatus(task.id, TaskStatus.SKIPPED)
        scheduler.cancelTask(task.id)
        notifications.cancelReminder(task.id)
        LiveUpdateService.refresh(appContext)
    }

    /** 点“收到”：不改任务状态，只让这一项不再上岛。 */
    suspend fun acknowledgeTask(taskId: Long) {
        settingsRepository.setAcknowledgedTask(taskId)
        notifications.onTaskAcknowledged(taskId)
        if (_triggerSuggestion.value?.taskId == taskId) {
            _triggerSuggestion.value = null
        }
        notifications.cancelReminder(taskId)
        notifications.cancel()
    }

    suspend fun startTaskNow(task: Task, phase: com.capsulepal.app.data.model.TaskPhase) {
        val status = if (phase == com.capsulepal.app.data.model.TaskPhase.PREPARE) {
            TaskStatus.PREPARE_WINDOW
        } else {
            TaskStatus.IN_PROGRESS
        }
        taskRepository.setStatus(task.id, status)
        notifications.cancelReminder(task.id)
        LiveUpdateService.refresh(appContext)
    }

    /** 长按待办 → 立即开始：把这一次挪到此刻并进入进行中。 */
    suspend fun startTaskRightNow(task: Task) {
        val now = LocalTime.now().withSecond(0).withNano(0)
        scheduler.cancelTask(task.id)
        notifications.cancelReminder(task.id)
        settingsRepository.clearAcknowledgedTask(task.id)
        if (_triggerSuggestion.value?.taskId == task.id) {
            _triggerSuggestion.value = null
        }
        taskRepository.startAtNow(task.id, LocalDate.now(), now.hour * 60 + now.minute)
        rescheduleVisibleTasks()
        LiveUpdateService.refresh(appContext)
    }

    /** 长按待办 → 挪到以后：只改日期，状态回到待开始。 */
    suspend fun moveTaskTo(task: Task, date: LocalDate) {
        scheduler.cancelTask(task.id)
        notifications.cancelReminder(task.id)
        settingsRepository.clearAcknowledgedTask(task.id)
        if (_triggerSuggestion.value?.taskId == task.id) {
            _triggerSuggestion.value = null
        }
        taskRepository.moveToDate(task.id, date)
        rescheduleVisibleTasks()
        LiveUpdateService.refresh(appContext)
    }

    suspend fun saveTask(draft: TaskDraft): Long {
        val id = taskRepository.upsert(draft)
        val task = taskRepository.task(id)
        if (task != null) {
            _exactAlarmAvailable.value = scheduler.scheduleTask(task)
        }
        // 重复任务会展开成多个实例，统一重建一遍开放任务的闹钟。
        if (draft.repeat != com.capsulepal.app.data.model.RepeatRule.ONCE) {
            rescheduleVisibleTasks()
        }
        // 只有新增（而不是编辑已有待办）才触发自动备份。
        if (draft.id == 0L) autoBackup()
        return id
    }

    suspend fun deleteTask(taskId: Long) {
        scheduler.cancelTask(taskId)
        notifications.cancelReminder(taskId)
        taskRepository.delete(taskId)
        LiveUpdateService.refresh(appContext)
    }

    /** 按系列删除整组待办：先逐个撤销闹钟与通知，再一次性删库。 */
    suspend fun deleteSeries(seriesId: Long): Int {
        taskRepository.seriesTaskIds(seriesId).forEach { id ->
            scheduler.cancelTask(id)
            notifications.cancelReminder(id)
        }
        val removed = taskRepository.deleteSeries(seriesId)
        LiveUpdateService.refresh(appContext)
        if (removed > 0) autoBackup()
        return removed
    }

    /**
     * 跨天维护：重复系列按 30 天滚动窗口继续向后补实例；
     * 同时把已过结束日的周期计划里未处理的待办统一结算为跳过。
     */
    suspend fun maintainOnDayChange(today: LocalDate): Int {
        val version = settingsRepository.settings.first().version
        taskRepository.ensureSeriesHorizon(include = { version.isTaskVisible(it) })
        // 计划页隐藏时，周期计划到期也不做跳过结算。
        val skipped = if (version.showPlansPage) {
            planRepository.skipUnfinishedExpired(today)
        } else {
            0
        }
        if (skipped > 0) {
            scheduler.reschedule(taskRepository.openTasks().filter { version.isTaskVisible(it) })
            LiveUpdateService.refresh(appContext)
        }
        return skipped
    }

    suspend fun applyTemplate(type: TemplateType, date: LocalDate): Int {
        val inserted = taskRepository.applyTemplate(type, date)
        rescheduleVisibleTasks()
        LiveUpdateService.refresh(appContext)
        if (inserted > 0) autoBackup()
        return inserted
    }

    /** 新增待办 / 日程 / 主动规则后静默备份；未设置保存路径时自动跳过。 */
    fun autoBackup() {
        appScope.launch { backupRepository.export(BackupKind.AUTO) }
    }

    /** 导入备份后重建闹钟、日历心跳与后台监听。 */
    private suspend fun onBackupRestored() {
        _exactAlarmAvailable.value = scheduler.canScheduleExact()
        rescheduleVisibleTasks()
        syncBackgroundWatch(settingsRepository.settings.first())
        refreshCalendarNow()
        rescheduleCalendarWatch()
        LiveUpdateService.refresh(appContext)
    }

    suspend fun handleAlarm(taskId: Long, point: AlarmPoint) {
        val now = System.currentTimeMillis()
        val settings = settingsRepository.settings.first()
        val task = taskRepository.task(taskId)
        // 版本切换后还可能收到旧闹钟：隐藏模块直接撤销，不改状态、不上岛。
        if (task != null && !settings.version.isTaskVisible(task)) {
            scheduler.cancelTask(taskId)
            notifications.cancelReminder(taskId)
            LiveUpdateService.refresh(appContext)
            return
        }
        if (task != null && task.isOpen) {
            when (point) {
                AlarmPoint.PREPARE_START -> {
                    if (task.status == TaskStatus.IDLE &&
                        task.prepareMinutes > 0 &&
                        now < task.startMillis
                    ) {
                        taskRepository.setStatus(task.id, TaskStatus.PREPARE_WINDOW)
                    }
                }
                AlarmPoint.TASK_START -> {
                    if (task.status == TaskStatus.IDLE || task.status == TaskStatus.PREPARE_WINDOW) {
                        taskRepository.setStatus(task.id, TaskStatus.IN_PROGRESS)
                    }
                }
                AlarmPoint.TASK_END -> {
                    sweepFinishedTasks(refresh = false)
                }
            }
        }

        if (settings.keepAliveEnabled) {
            FloatingKeepAliveService.start(appContext)
        }

        notifications.cancelReminder(taskId)
        LiveUpdateService.refresh(appContext)
    }

    suspend fun refreshAfterBoot() {
        _exactAlarmAvailable.value = scheduler.canScheduleExact()
        sweepFinishedTasks(refresh = false)
        rescheduleVisibleTasks()
        refreshCalendarNow()
        rescheduleCalendarWatch()
        val settings = settingsRepository.settings.first()
        syncBackgroundWatch(settings)
        LiveUpdateService.refresh(appContext)
    }

    suspend fun rescheduleAll() {
        _exactAlarmAvailable.value = scheduler.canScheduleExact()
        rescheduleVisibleTasks()
    }

    fun dismissSuggestion() {
        _triggerSuggestion.value = null
        notifications.cancelReminder()
    }

    fun scheduleOffsetTick(minutes: Int) = scheduler.scheduleOffsetTick(minutes)

    /** 系统广播唤醒进程后直接投递信号，避免必须等 App 打开。 */
    fun onExternalSignal(signal: SystemSignal) {
        appScope.launch { evaluateSignal(signal) }
    }

    /** 日历轻量心跳：检查即将开始的系统日程，并重新安排下一次精确闹钟。 */
    fun checkCalendarSoon() {
        appScope.launch {
            val settings = settingsRepository.settings.first()
            signals.refreshCalendar(calendarLeadMinutes(settings))
            rescheduleCalendarWatch()
        }
    }

    /** 设置页保存规则后调用，让日历闹钟按新配置重排。 */
    fun onReminderSettingsChanged() {
        appScope.launch {
            signals.refreshWakeableSources()
            refreshCalendarNow()
            rescheduleCalendarWatch()
            syncBackgroundWatch(settingsRepository.settings.first())
        }
    }

    /** 根据保活开关与启用中的后台监听规则，启动或停止前台服务。 */
    fun syncBackgroundWatch() {
        appScope.launch { syncBackgroundWatch(settingsRepository.settings.first()) }
    }

    /** 提醒诊断页使用的运行环境快照。 */
    fun reminderDiagnostics(): ReminderDiagnostics = ReminderDiagnostics(
        notificationsAllowed = NotificationValidator.hasPostPermission(appContext),
        usageAccess = UsageAccess.granted(appContext),
        calendarPermission = signals.calendarPermissionGranted(),
        serviceRunning = FloatingKeepAliveService.isRunning
    )

    /** 只有极客版需要帧率/内存运行时采样；切走后立即停止，不写库也不留后台线程。 */
    private fun syncRuntimeMetrics(settings: AppSettings) {
        if (settings.version.recordsBatteryTimeline) {
            RuntimeMetrics.start(appContext)
        } else {
            RuntimeMetrics.stop()
        }
    }

    private fun syncBackgroundWatch(settings: AppSettings) {
        val needsWatch = settings.version.showReminders &&
            settings.reminderRules.any {
                it.enabled && it.type.needsBackgroundWatch &&
                    (settings.version.showTodoPage || it.action == ReminderActionType.CUSTOM)
            }
        val needsMetrics = settings.version.showFloatingMetrics && settings.metricOverlayEnabled
        // 极客体验：所有采样都依赖常驻前台服务，即使不显示指标悬浮窗也要跑。
        // 其它版本开启「为极客采样」且高频轮询在跑时，同样常驻以保证数据连续。
        val needsGeek = settings.version.recordsBatteryTimeline ||
            (settings.geekSamplingInOtherVersions && settings.batteryHighFrequencyAlways)
        if (settings.keepAliveEnabled || needsWatch || needsMetrics || needsGeek) {
            FloatingKeepAliveService.start(appContext)
        } else {
            FloatingKeepAliveService.stop(appContext)
            // 没有指标悬浮窗需求时，顺手收掉可能残留的窗口。
            metricOverlay.hide()
        }
    }

    private suspend fun refreshCalendarNow() {
        val settings = settingsRepository.settings.first()
        signals.refreshCalendar(calendarLeadMinutes(settings))
    }

    private fun calendarLeadMinutes(settings: com.capsulepal.app.data.model.AppSettings): Int =
        settings.reminderRules
            .filter { it.type == ReminderTriggerType.CALENDAR_SOON && it.enabled }
            .maxOfOrNull { it.calendarLeadMinutes }
            ?: 15

    /**
     * 有精确闹钟权限时，按「事件开始 - 提前量」定一次闹钟；
     * 没有权限时退化为 15 分钟心跳，仍然保持纯本地检查。
     */
    suspend fun rescheduleCalendarWatch() {
        scheduler.cancelCalendarWatch()
        val settings = settingsRepository.settings.first()
        if (!settings.version.showReminders) return
        val calendarRulesEnabled = settings.reminderRules.any {
            it.type == ReminderTriggerType.CALENDAR_SOON && it.enabled
        }
        if (!calendarRulesEnabled || !signals.calendarPermissionGranted()) return
        val lead = calendarLeadMinutes(settings)
        val event = signals.firstUpcomingCalendarEvent()
        val triggerAt = event?.beginMillis?.minus(lead * 60_000L)
        if (scheduler.canScheduleExact() && triggerAt != null && triggerAt > System.currentTimeMillis()) {
            scheduler.scheduleCalendarEvent(triggerAt)
        } else {
            scheduler.scheduleCalendarTick()
        }
    }

    private fun observeSignals() {
        appScope.launch {
            signals.signals.collect { signal ->
                evaluateSignal(signal)
            }
        }
    }

    private data class ReminderCandidate(
        val suggestion: TriggerSuggestion,
        val signal: SystemSignal
    )

    private val pendingCandidates = mutableListOf<ReminderCandidate>()
    private var arbitrationJob: Job? = null

    /**
     * 3 秒仲裁窗口：同一时间到达的多个触发源先收集起来，只展示优先级最高的一条。
     * 这样「插上电 → 顺便连上 WiFi」不会连弹两次。
     */
    private suspend fun evaluateSignal(signal: SystemSignal) {
        val settings = settingsRepository.settings.first()
        if (!settings.version.showReminders) return
        if (settings.reminderRules.none { it.enabled }) return
        val now = System.currentTimeMillis()
        val today = LocalDate.now()
        val tasks = taskRepository.openTasks()
            .filter { it.date == today || it.date == today.plusDays(1) }
        val suggestions = triggerEngine.evaluate(
            signal = signal,
            tasks = tasks,
            nowMillis = now,
            rules = settings.reminderRules,
            mediaPlaying = signals.mediaPlaying.value,
            mediaSilenceEnabled = settings.mediaSilenceEnabled,
            // 极客体验没有待办模块：关联待办类规则退化为纯自定义提醒。
            allowTaskLink = settings.version.showTodoPage
        )
        if (suggestions.isEmpty()) {
            recordNoSuggestion(signal, settings)
            return
        }
        ReminderTrace.record(
            suggestions.first().type.label,
            "收到触发，生成 ${suggestions.size} 条候选",
            true
        )
        synchronized(pendingCandidates) {
            suggestions.forEach { suggestion ->
                pendingCandidates += ReminderCandidate(suggestion, signal)
            }
        }
        if (arbitrationJob?.isActive == true) return
        arbitrationJob = appScope.launch {
            delay(ARBITRATION_WINDOW_MILLIS)
            val batch = synchronized(pendingCandidates) {
                // 冲突裁决顺序固定：优先级高的先；同优先级按规则 id，保证结果可复现。
                pendingCandidates.sortedWith(
                    compareByDescending<ReminderCandidate> { it.suggestion.priority }
                        .thenBy { it.suggestion.ruleId }
                )
                    .toList()
                    .also { pendingCandidates.clear() }
            }
            showFirstAllowed(batch)
        }
    }

    private suspend fun showFirstAllowed(batch: List<ReminderCandidate>) {
        val settings = settingsRepository.settings.first()
        val now = System.currentTimeMillis()
        // 免打扰时段直接跳过，不消耗每日次数、不写事件去重，
        // 避免夜间把第二天的提醒配额和日历/应用去重状态一起吞掉。
        if (isQuietHours(now, settings)) {
            val title = batch.firstOrNull()?.let { candidate ->
                settings.reminderRules.firstOrNull { it.id == candidate.suggestion.ruleId }
                    ?.displayName
            } ?: batch.firstOrNull()?.suggestion?.ruleName.orEmpty()
            ReminderTrace.record(title, "免打扰时段，已跳过（不消耗次数）", false)
            return
        }

        var allowed: ReminderCandidate? = null
        var allowedTitle = ""
        for (candidate in batch) {
            val rule = settings.reminderRules.firstOrNull {
                it.id == candidate.suggestion.ruleId
            }
            if (rule == null) {
                ReminderTrace.record(
                    candidate.suggestion.ruleName,
                    "规则已被删除，跳过",
                    false
                )
                continue
            }
            val title = rule.displayName.ifBlank { candidate.suggestion.ruleName }
            val gate = settingsRepository.tryConsumeReminder(
                rule = rule,
                cooldownKey = reminderCooldownKey(candidate),
                nowMillis = now,
                globalCooldownMinutes = settings.reminderCooldownMinutes
            )
            if (gate == ReminderPolicy.GateResult.ALLOWED) {
                allowed = candidate
                allowedTitle = title
                ReminderTrace.record(title, "已通过闸门，准备发出", true)
                break
            }
            ReminderTrace.record(title, gate.label, false)
        }

        val chosen = allowed ?: return
        _triggerSuggestion.value = chosen.suggestion
        val task = chosen.suggestion.taskId?.let { taskRepository.task(it) }
        val canPost = NotificationValidator.hasPostPermission(appContext)
        notifications.showReminder(chosen.suggestion, task, now)
        ReminderTrace.record(
            allowedTitle,
            if (canPost) "通知已发出" else "未发出：系统通知权限被关闭",
            canPost
        )
    }

    /** 信号到了但没有生成建议时，把原因写进诊断记录。 */
    private fun recordNoSuggestion(signal: SystemSignal, settings: AppSettings) {
        val type = signal.type.toReminderTriggerType() ?: return
        if (settings.reminderRules.none { it.enabled && it.type == type }) return
        val detail = if (
            settings.mediaSilenceEnabled &&
            signals.mediaPlaying.value &&
            type != ReminderTriggerType.CALENDAR_SOON
        ) {
            "触发已到达，但正在播放媒体，按“媒体静默”跳过"
        } else {
            "触发已到达，但规则条件未匹配（星期/时段/对象/提前量）"
        }
        ReminderTrace.record(type.label, detail, false)
    }

    /**
     * 同一事件 / 待办共用一个去重键：
     * 日历按事件开始时间，应用按包名，有关联待办按待办 id。
     * 充电 / WiFi / 蓝牙 / 耳机不再按事件类型去重，交给全局冷却兜底，
     * 避免不同触发源在冷却期内互相压制。
     */
    private fun reminderCooldownKey(candidate: ReminderCandidate): String? {
        val suggestion = candidate.suggestion
        val signal = candidate.signal
        return ReminderPolicy.cooldownKey(
            eventStartMillis = signal.eventStartMillis,
            appLaunchPackage = signal.detail.takeIf {
                suggestion.type == ReminderTriggerType.APP_LAUNCH && it.isNotBlank()
            },
            taskId = suggestion.taskId
        )
    }

    private fun isQuietHours(nowMillis: Long, settings: AppSettings): Boolean {
        if (!settings.quietHoursEnabled) return false
        return ReminderPolicy.isQuietMinute(
            nowMillis = nowMillis,
            startMinute = settings.quietStartMinute,
            endMinute = settings.quietEndMinute
        )
    }

    private companion object {
        const val ARBITRATION_WINDOW_MILLIS = 3_000L
    }
}
