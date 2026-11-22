package com.capsulepal.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.capsulepal.app.AppContainer
import com.capsulepal.app.CapsuleApp
import com.capsulepal.app.CompletionOutcome
import com.capsulepal.app.RestoreResult
import com.capsulepal.app.data.backup.BackupKind
import com.capsulepal.app.data.model.ActiveTask
import com.capsulepal.app.data.model.AppSettings
import com.capsulepal.app.data.model.CategoryEntry
import com.capsulepal.app.data.model.CapsuleVersion
import com.capsulepal.app.data.model.PartnerProfile
import com.capsulepal.app.data.model.Plan
import com.capsulepal.app.data.model.ReminderRule
import com.capsulepal.app.data.model.Task
import com.capsulepal.app.data.model.Priority
import com.capsulepal.app.data.model.TaskCategory
import com.capsulepal.app.data.model.TaskDraft
import com.capsulepal.app.data.model.TaskKind
import com.capsulepal.app.data.model.TaskPhase
import com.capsulepal.app.data.model.TaskStatus
import com.capsulepal.app.data.model.TimelineState
import com.capsulepal.app.data.repo.TaskRepository
import com.capsulepal.app.domain.ActiveTaskResolver
import com.capsulepal.app.domain.HolidayProvider
import com.capsulepal.app.domain.LunarProvider
import com.capsulepal.app.domain.ReminderTrace
import com.capsulepal.app.domain.SummaryCalculator
import com.capsulepal.app.domain.SummaryRange
import com.capsulepal.app.domain.SummaryStats
import com.capsulepal.app.domain.TriggerSuggestion
import com.capsulepal.app.domain.PlanProgress
import com.capsulepal.app.domain.RepeatPlanStats
import com.capsulepal.app.domain.planProgress
import com.capsulepal.app.domain.repeatPlanStats
import com.capsulepal.app.monitor.MonitorState
import com.capsulepal.app.notifier.ValidationResult
import com.capsulepal.app.partner.PartnerAppearance
import com.capsulepal.app.partner.RecentSkip
import com.capsulepal.app.service.FloatingKeepAliveService
import com.capsulepal.app.service.LiveUpdateService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** 待办页的时间范围筛选。 */
enum class TodoRange { TODAY, TOMORROW, THREE_DAYS, CUSTOM }

/** 极客页共用的本地数据快照。 */
data class GeekUiState(
    val timeline: List<com.capsulepal.app.data.db.BatterySampleEntity> = emptyList(),
    val discharges: List<com.capsulepal.app.data.db.DischargeSessionEntity> = emptyList(),
    val charges: List<com.capsulepal.app.data.db.ChargeSessionEntity> = emptyList(),
    val appPowerToday: List<com.capsulepal.app.data.repo.AppPowerItem> = emptyList(),
    val appPowerWeek: List<com.capsulepal.app.data.repo.AppPowerItem> = emptyList(),
    val runtimePoints: List<com.capsulepal.app.monitor.RuntimePoint> = emptyList(),
    val runtime: com.capsulepal.app.monitor.RuntimeSnapshot =
        com.capsulepal.app.monitor.RuntimeSnapshot(null, null, null),
    val todayEpochDay: Long = 0L
)

data class TodoUiState(
    val today: LocalDate,
    val range: TodoRange,
    val customDays: Int,
    val layout: com.capsulepal.app.data.model.TodoLayout,
    val groups: List<com.capsulepal.app.data.model.TaskGroup>,
    val groupFilter: Long,
    val categoryFilter: TaskCategory?,
    val priorityFilter: Priority?,
    /** 计划筛选：null = 全部计划。 */
    val planFilter: Long? = null,
    val plans: List<Plan> = emptyList(),
    /** 已按分组排序、再按开始时间排序，且应用了分组/分类/重要程度/计划筛选。 */
    val tasks: List<Task>
) {
    val rangeDays: Int
        get() = when (range) {
            TodoRange.TODAY -> 1
            TodoRange.TOMORROW -> 1
            TodoRange.THREE_DAYS -> 3
            TodoRange.CUSTOM -> customDays.coerceIn(1, 30)
        }
}

data class CapsuleUiState(
    val date: LocalDate,
    val tasks: List<Task>,
    val timeline: TimelineState,
    val settings: AppSettings,
    val partner: PartnerProfile,
    val holidayName: String?,
    val isRestDay: Boolean,
    val lunarLabel: String,
    val nextHolidayName: String?,
    val nextHolidayDays: Long?,
    val nowMillis: Long
) {
    val active: ActiveTask? get() = timeline.active
    val next: com.capsulepal.app.data.model.UpcomingTask? get() = timeline.next
    val openCount: Int get() = tasks.count { it.isOpen }
    val doneCount: Int get() = tasks.count { !it.isOpen }
}

data class ScheduleUiState(
    val month: YearMonth,
    val selectedDate: LocalDate,
    val today: LocalDate,
    val tasks: List<Task>,
    val markedDays: Set<Long>,
    val monthTotal: Int
) {
    val isRestDay: Boolean get() = HolidayProvider.isRestDay(selectedDate)
    val isEstimatedHoliday: Boolean get() = HolidayProvider.isEstimated(selectedDate)
}

/** 计划列表里的一张卡片：计划本体 + 计划内待办的完成进度（重复计划用完成次数）。 */
data class PlanCard(
    val plan: Plan,
    val progress: PlanProgress,
    val stats: RepeatPlanStats = RepeatPlanStats()
)

data class PlansUiState(
    val plans: List<PlanCard>,
    val tasksByPlan: Map<Long, List<Task>>
) {
    val active: List<PlanCard> get() = plans.filter { !it.plan.archived }
    val archived: List<PlanCard> get() = plans.filter { it.plan.archived }
}

private data class DayContext(
    val date: LocalDate,
    val tasks: List<Task>,
    val settings: AppSettings,
    val partner: PartnerProfile,
    val holidayName: String?,
    val isRestDay: Boolean,
    val lunarLabel: String,
    val nextHolidayName: String?,
    val nextHolidayDays: Long?
)

@OptIn(ExperimentalCoroutinesApi::class)
class CapsuleViewModel(application: Application) : AndroidViewModel(application) {

    private val container: AppContainer = (application as CapsuleApp).container

    private val _now = MutableStateFlow(System.currentTimeMillis())
    val now: StateFlow<Long> = _now.asStateFlow()

    private val _completion = MutableStateFlow<CompletionOutcome?>(null)
    val completion: StateFlow<CompletionOutcome?> = _completion.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _highlightTaskId = MutableStateFlow<Long?>(null)
    val highlightTaskId: StateFlow<Long?> = _highlightTaskId.asStateFlow()

    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val toast: SharedFlow<String> = _toast.asSharedFlow()

    private val _openTasks = MutableStateFlow<List<Task>>(emptyList())
    /** 规则编辑里“关联指定待办”的可选列表。 */
    val openTasks: StateFlow<List<Task>> = _openTasks.asStateFlow()

    fun refreshOpenTasks() {
        viewModelScope.launch {
            _openTasks.value = container.taskRepository.openTasks()
        }
    }

    private val _recentSkip = MutableStateFlow<RecentSkip?>(null)
    val recentSkip: StateFlow<RecentSkip?> = _recentSkip.asStateFlow()

    private val _testStartedAt = MutableStateFlow(0L)
    val testStartedAt: StateFlow<Long> = _testStartedAt.asStateFlow()

    val suggestion: StateFlow<TriggerSuggestion?> = container.triggerSuggestion
    val exactAlarmAvailable: StateFlow<Boolean> = container.exactAlarmAvailable
    val lastValidation: StateFlow<ValidationResult?> = container.notifications.lastValidation
    val monitor: StateFlow<MonitorState?> = container.batteryMonitor.state

    /** 即时待办只活在内存里。 */
    val instantTodo: StateFlow<com.capsulepal.app.data.repo.InstantTodo?> =
        container.instantTodoRepository.todo

    fun createInstantTodo(title: String): Boolean = container.createInstantTodo(title)

    fun completeInstantTodo() = container.completeInstantTodo()

    private val _geek = MutableStateFlow(GeekUiState())
    val geek: StateFlow<GeekUiState> = _geek.asStateFlow()

    fun clearChargeSessions() {
        viewModelScope.launch {
            runCatching { container.geekRepository.clearChargeSessions() }
            refreshGeek()
        }
    }

    fun clearDischargeSessions() {
        viewModelScope.launch {
            runCatching { container.geekRepository.clearDischargeSessions() }
            refreshGeek()
        }
    }

    /** 拉取极客页数据：24 小时曲线、充放电会话、应用功耗与运行时指标。 */
    fun refreshGeek() {
        viewModelScope.launch {
            val today = LocalDate.now().toEpochDay()
            // 打开极客页时用最新采样重算一次，避免看到 15 分钟前的旧估算。
            runCatching { container.geekRepository.refreshAppPower(System.currentTimeMillis()) }
            val timeline = runCatching { container.geekRepository.timeline24h() }
                .getOrDefault(emptyList())
            val discharges = runCatching { container.geekRepository.recentDischargeSessions() }
                .getOrDefault(emptyList())
            val charges = runCatching { container.geekRepository.recentChargeSessions() }
                .getOrDefault(emptyList())
            val appPowerToday = runCatching { container.geekRepository.appPowerToday() }
                .getOrDefault(emptyList())
            val appPowerWeek = runCatching { container.geekRepository.appPowerHistory() }
                .getOrDefault(emptyList())
            _geek.value = GeekUiState(
                timeline = timeline,
                discharges = discharges,
                charges = charges,
                appPowerToday = appPowerToday,
                appPowerWeek = appPowerWeek,
                runtimePoints = com.capsulepal.app.monitor.RuntimeMetrics.points(),
                runtime = com.capsulepal.app.monitor.RuntimeMetrics.snapshot(),
                todayEpochDay = today
            )
        }
    }

    private val dayFlow = _now
        .map { millis ->
            Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
        }
        .distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val todayFlow = dayFlow.flatMapLatest { date ->
        combine(
            container.taskRepository.observeTasks(date),
            container.settingsRepository.settings,
            container.partnerRepository.profile
        ) { tasks, settings, partner ->
            val nextHoliday = HolidayProvider.nextHoliday(date)
            DayContext(
                date = date,
                tasks = tasks
                    .filter { settings.version.isTaskVisible(it) }
                    .sortedBy { it.startMillis },
                settings = settings,
                partner = partner,
                holidayName = HolidayProvider.holidayName(date),
                isRestDay = HolidayProvider.isRestDay(date),
                lunarLabel = runCatching { LunarProvider.label(date) }.getOrDefault(""),
                nextHolidayName = nextHoliday?.first,
                nextHolidayDays = nextHoliday?.second
                    ?.let { ChronoUnit.DAYS.between(date, it) }
            )
        }
    }

    val uiState: StateFlow<CapsuleUiState?> = combine(todayFlow, _now) { context, nowMillis ->
        CapsuleUiState(
            date = context.date,
            tasks = context.tasks,
            timeline = ActiveTaskResolver.resolve(context.tasks, nowMillis),
            settings = context.settings,
            partner = context.partner,
            holidayName = context.holidayName,
            isRestDay = context.isRestDay,
            lunarLabel = context.lunarLabel,
            nextHolidayName = context.nextHolidayName,
            nextHolidayDays = context.nextHolidayDays,
            nowMillis = nowMillis
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _todoRange = MutableStateFlow(TodoRange.TODAY)
    /** -1 = 全部分组；0 = 未分组；其余为分组 id。 */
    private val _todoGroupFilter = MutableStateFlow(-1L)
    private val _todoCategoryFilter = MutableStateFlow<TaskCategory?>(null)
    private val _todoPriorityFilter = MutableStateFlow<Priority?>(null)
    private val _todoPlanFilter = MutableStateFlow<Long?>(null)

    private data class TodoSpec(
        val range: TodoRange,
        val today: LocalDate,
        val settings: AppSettings,
        val groupFilter: Long,
        val categoryFilter: TaskCategory?,
        val priorityFilter: Priority?,
        val planFilter: Long? = null
    )

    /**
     * 待办页数据：按筛选范围取 [today, today + n) 的待办，分组顺序优先、其次按日期与开始时间。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val todoState: StateFlow<TodoUiState?> = combine(
        combine(
            _todoRange,
            dayFlow,
            container.settingsRepository.settings,
            _todoGroupFilter,
            _todoCategoryFilter
        ) { range, today, settings, groupFilter, categoryFilter ->
            TodoSpec(range, today, settings, groupFilter, categoryFilter, null)
        },
        _todoPriorityFilter
    ) { spec, priorityFilter ->
        spec.copy(priorityFilter = priorityFilter)
    }.combine(_todoPlanFilter) { spec, planFilter ->
        spec.copy(planFilter = planFilter)
    }.flatMapLatest { spec ->
        val start = if (spec.range == TodoRange.TOMORROW) {
            spec.today.plusDays(1)
        } else {
            spec.today
        }
        val days = when (spec.range) {
            TodoRange.TODAY -> 1L
            TodoRange.TOMORROW -> 1L
            TodoRange.THREE_DAYS -> 3L
            TodoRange.CUSTOM -> spec.settings.todoCustomRangeSafe.toLong()
        }
        combine(
            container.taskRepository.observeTasksBetween(start, start.plusDays(days - 1)),
            container.taskRepository.observeGroups(),
            container.planRepository.observePlans()
        ) { tasks, groups, plans ->
            val order = groups.withIndex().associate { (index, group) -> group.id to index }
            val filtered = tasks.filter { task ->
                // 次日隐藏：早于今天且已完成/已跳过的待办不再出现在待办页，数据仍保留。
                val finishedYesterday = spec.settings.hideFinishedAfterDay &&
                    task.date.isBefore(spec.today) &&
                    (task.status == TaskStatus.COMPLETED || task.status == TaskStatus.SKIPPED)
                val groupOk = spec.groupFilter == -1L || task.groupId == spec.groupFilter
                val categoryOk = spec.categoryFilter == null || task.category == spec.categoryFilter
                val priorityOk = spec.priorityFilter == null || task.priority == spec.priorityFilter
                val planOk = spec.planFilter == null || task.planId == spec.planFilter
                !finishedYesterday && groupOk && categoryOk && priorityOk && planOk
            }.sortedWith(
                compareBy(
                    { order[it.groupId] ?: Int.MAX_VALUE },
                    { it.date },
                    { it.startMillis }
                )
            )
            TodoUiState(
                today = spec.today,
                range = spec.range,
                customDays = spec.settings.todoCustomRangeSafe,
                layout = spec.settings.todoLayout,
                groups = groups,
                groupFilter = spec.groupFilter,
                categoryFilter = spec.categoryFilter,
                priorityFilter = spec.priorityFilter,
                plans = plans,
                planFilter = spec.planFilter,
                tasks = filtered
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setTodoRange(range: TodoRange) {
        _todoRange.value = range
    }

    private val pastCountTick = MutableStateFlow(0)

    /** 设置页「清除已过期待办与日程」将要删除的条数。 */
    val pastTaskCount: StateFlow<Int> = combine(dayFlow, pastCountTick) { date, _ -> date }
        .map { date -> container.taskRepository.countBefore(date) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun setTodoGroupFilter(groupId: Long) {
        _todoGroupFilter.value = groupId
    }

    fun setTodoCategoryFilter(category: TaskCategory?) {
        _todoCategoryFilter.value = category
    }

    fun setTodoPriorityFilter(priority: Priority?) {
        _todoPriorityFilter.value = priority
    }

    fun setTodoPlanFilter(planId: Long?) {
        _todoPlanFilter.value = planId
    }

    fun setTodoLayout(layout: com.capsulepal.app.data.model.TodoLayout) {
        viewModelScope.launch { container.settingsRepository.setTodoLayout(layout) }
    }

    fun setTodoCustomDays(days: Int) {
        viewModelScope.launch { container.settingsRepository.setTodoCustomRangeDays(days) }
    }

    /** 计划页：计划列表 + 每个计划内的待办与完成进度。 */
    val plansState: StateFlow<PlansUiState?> = combine(
        container.planRepository.observePlans(),
        container.taskRepository.observeAllTasks()
    ) { plans, tasks ->
        val byPlan = tasks.filter { it.planId != 0L }.groupBy { it.planId }
        PlansUiState(
            plans = plans.map { plan ->
                val planTasks = byPlan[plan.id].orEmpty()
                PlanCard(
                    plan = plan,
                    progress = planProgress(planTasks, plan),
                    stats = repeatPlanStats(planTasks)
                )
            },
            tasksByPlan = byPlan
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 计划详情：直接按 planId 查询，避免依赖内存分组带来的不一致。 */
    fun tasksForPlan(planId: Long) = container.planRepository.observeTasks(planId)

    fun savePlan(plan: Plan, onSaved: (Long) -> Unit = {}) {
        viewModelScope.launch {
            val id = container.planRepository.save(plan)
            onSaved(id)
            container.autoBackup()
            _toast.tryEmit(if (plan.id == 0L) "已创建计划" else "已更新计划")
        }
    }

    fun setPlanArchived(plan: Plan, archived: Boolean) {
        viewModelScope.launch {
            container.planRepository.setArchived(plan.id, archived)
            container.autoBackup()
            _toast.tryEmit(if (archived) "计划已归档" else "计划已恢复")
        }
    }

    fun deletePlan(plan: Plan) {
        viewModelScope.launch {
            container.planRepository.delete(plan.id)
            container.autoBackup()
            _toast.tryEmit("已删除计划，待办已保留")
        }
    }

    private val _summaryRange = MutableStateFlow(SummaryRange.DAY)
    private val _summaryAnchor = MutableStateFlow(LocalDate.now())

    /** 总结页：按 日/周/月/年 统计完成、跳过、完成率与分类分布。 */
    val summaryState: StateFlow<SummaryStats?> = combine(
        container.taskRepository.observeAllTasks(),
        _summaryRange,
        _summaryAnchor
    ) { tasks, range, anchor ->
        SummaryCalculator.summarize(tasks, range, anchor)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setSummaryRange(range: SummaryRange) {
        _summaryRange.value = range
    }

    fun shiftSummary(delta: Long) {
        _summaryAnchor.value = SummaryCalculator.shift(_summaryRange.value, _summaryAnchor.value, delta)
    }

    fun resetSummaryToToday() {
        _summaryAnchor.value = LocalDate.now()
    }

    fun setOverduePolicy(policy: com.capsulepal.app.data.model.OverduePolicy) {
        viewModelScope.launch { container.settingsRepository.setOverduePolicy(policy) }
    }

    fun setHideFinishedAfterDay(enabled: Boolean) {
        viewModelScope.launch { container.settingsRepository.setHideFinishedAfterDay(enabled) }
    }

    /** 设置页：删除日期早于今天的待办与日程，今天及以后保留。 */
    fun clearPastTasks() {
        viewModelScope.launch {
            val removed = container.taskRepository.deleteBefore(LocalDate.now())
            pastCountTick.value += 1
            container.rescheduleAll()
            _toast.tryEmit(
                if (removed > 0) "已清除 $removed 条过期待办与日程" else "没有需要清除的过期待办"
            )
        }
    }

    /**
     * 设置页：清除全部本机数据。
     * 只清本机数据库与设置，已导出的备份文件（SAF 与本机文件）保持不动。
     */
    fun clearAllLocalData() {
        viewModelScope.launch {
            val taskIds = container.taskRepository.allTasks().map { it.id }
            container.taskRepository.clearAll()
            container.planRepository.clearAll()
            container.scheduler.cancelAll(taskIds)
            container.notifications.cancel()
            container.notifications.cancelReminder()
            container.partnerRepository.reset()
            // 电池数据也属于「全部数据」：充电会话、学习健康度、放电会话、24h 采样与功耗历史。
            container.batteryRepository.clearAll()
            container.geekRepository.clearAll()
            container.settingsRepository.reset()
            _geek.value = GeekUiState()
            // 提醒规则回到默认值，重排一遍日历监听与后台保活。
            container.onReminderSettingsChanged()
            container.rescheduleAll()
            // 分组与筛选都已失效，重置回「全部」避免待办页看起来是空的。
            _todoGroupFilter.value = -1L
            _todoCategoryFilter.value = null
            _todoPriorityFilter.value = null
            LiveUpdateService.refresh(getApplication())
            pastCountTick.value += 1
            _toast.tryEmit("已清除全部本机数据")
        }
    }

    fun createGroup(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            container.taskRepository.saveGroup(
                com.capsulepal.app.data.model.TaskGroup(name = name)
            )
        }
    }

    fun renameGroup(id: Long, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val group = container.taskRepository.groups().firstOrNull { it.id == id } ?: return@launch
            container.taskRepository.saveGroup(group.copy(name = name))
        }
    }

    fun deleteGroup(id: Long) {
        viewModelScope.launch {
            container.taskRepository.deleteGroup(id)
            if (_todoGroupFilter.value == id) _todoGroupFilter.value = -1L
        }
    }

    fun moveGroup(id: Long, delta: Int) {
        viewModelScope.launch { container.taskRepository.moveGroup(id, delta) }
    }

    private val _scheduleMonth = MutableStateFlow(YearMonth.now())
    private val _selectedDate = MutableStateFlow(LocalDate.now())

    val scheduleState: StateFlow<ScheduleUiState?> =
        combine(
            _scheduleMonth,
            _selectedDate,
            container.settingsRepository.settings
        ) { month, selected, settings -> Triple(month, selected, settings) }
            .flatMapLatest { (month, selected, settings) ->
                container.taskRepository.observeTasksBetween(
                    month.atDay(1),
                    month.atEndOfMonth()
                ).map { tasks ->
                    val today = LocalDate.now()
                    // 与待办页一致：开启「完成后次日隐藏」时，过去日期里已完成的记录不再出现在日程里。
                    val visible = tasks.filter { task ->
                        !settings.hideFinishedAfterDay ||
                            !task.date.isBefore(today) ||
                            task.isOpen
                    }
                    val ordered = visible.sortedBy { it.startMillis }
                    // 日历只标「日程待办」；待办页与计划里的任务照常出现在当天列表。
                    val marked = ordered
                        .filter { it.kind == TaskKind.SCHEDULE }
                        .map { it.date.toEpochDay() }
                        .toSet()
                    ScheduleUiState(
                        month = month,
                        selectedDate = selected,
                        today = today,
                        tasks = ordered.filter { it.date == selected },
                        markedDays = marked,
                        monthTotal = ordered.size
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun selectDate(date: LocalDate) {
        _selectedDate.value = date
        val month = YearMonth.from(date)
        if (month != _scheduleMonth.value) {
            _scheduleMonth.value = month
        }
    }

    fun changeMonth(delta: Long) {
        val month = _scheduleMonth.value.plusMonths(delta)
        _scheduleMonth.value = month
        val today = LocalDate.now()
        _selectedDate.value = if (YearMonth.from(today) == month) today else month.atDay(1)
    }

    fun goToday() {
        _scheduleMonth.value = YearMonth.now()
        _selectedDate.value = LocalDate.now()
    }

    init {
        viewModelScope.launch {
            while (true) {
                _now.value = System.currentTimeMillis()
                delay(10_000L)
            }
        }
        // 打开 App 时维护一次，之后每次跨天再维护：补铺重复实例、结算到期周期计划。
        viewModelScope.launch {
            dayFlow.collect { date ->
                container.maintainOnDayChange(date)
                settleOverdueFor(date)
            }
        }
    }

    /** 当前进程已经结算过的日期；同一天重复触发时直接跳过。 */
    private var settledEpochDay: Long? = null

    /**
     * 次日结算：把早于今天仍未处理的待办按设置统一置为完成 / 跳过。
     * 「视为完成」逐条走 [AppContainer.completeTask]，与手动完成一致地发放经验；
     * 「视为跳过」逐条只改状态，不发经验。
     */
    private suspend fun settleOverdueFor(date: LocalDate) {
        if (settledEpochDay == date.toEpochDay()) return
        val settings = container.settingsRepository.settings.first()
        // 隐藏模块的待办保持冻结，不参与逾期结算。
        val overdue = container.taskRepository.overdueBefore(date)
            .filter { settings.version.isTaskVisible(it) }
        var changed = 0
        if (settings.overduePolicy.result == TaskStatus.COMPLETED) {
            overdue.forEach { task ->
                if (container.completeTask(task, refresh = false) != null) changed++
            }
            if (changed > 0) LiveUpdateService.refresh(getApplication())
        } else {
            overdue.forEach { task ->
                container.taskRepository.setStatus(task.id, settings.overduePolicy.result)
                changed++
            }
        }
        settledEpochDay = date.toEpochDay()
        if (changed > 0) {
            container.rescheduleAll()
            pastCountTick.value += 1
        }
    }

    fun onForeground() {
        container.onForeground(true)
        LiveUpdateService.refresh(getApplication())
    }

    fun onBackground() {
        container.onForeground(false)
    }

    fun complete(task: Task) {
        viewModelScope.launch {
            val outcome = container.completeTask(task)
            if (outcome != null) {
                _completion.value = outcome
            } else {
                _message.value = "这件已经收好啦。"
            }
        }
    }

    fun skip(task: Task) {
        viewModelScope.launch {
            container.skipTask(task)
            _recentSkip.value = RecentSkip(task.id, System.currentTimeMillis())
            _message.value = "「${task.title}」先放一放，不记欠债。"
        }
    }

    /** 清单右侧按钮长按：已完成 / 未完成 → 空。 */
    fun restore(task: Task) {
        viewModelScope.launch {
            when (container.restoreTask(task)) {
                RestoreResult.ENDED -> {
                    _message.value = "该事项已结束，正在琢磨时光机。"
                }
                RestoreResult.NOT_RESTORABLE -> Unit
                RestoreResult.RESTORED -> {
                    _message.value = "已把「${task.title}」放回时间线。"
                }
            }
        }
    }

    /** 清单右侧按钮：完成 ↔ 未完成。 */
    fun toggle(task: Task) {
        viewModelScope.launch {
            if (task.status == TaskStatus.COMPLETED) {
                val refund = container.uncompleteTask(task)
                _message.value = if (refund != null && refund > 0) {
                    "已标为未完成，回退了 $refund 点成长。"
                } else {
                    "已标为未完成。"
                }
            } else {
                val outcome = container.completeTask(task)
                if (outcome != null) {
                    _completion.value = outcome
                } else {
                    _message.value = "这件已经收好啦。"
                }
            }
        }
    }

    fun startNow(task: Task, phase: TaskPhase) {
        viewModelScope.launch {
            container.startTaskNow(task, phase)
            _message.value = when (phase) {
                TaskPhase.PREPARE -> "已经进入「${task.title}」的准备窗口。"
                TaskPhase.IN_PROGRESS -> "开始「${task.title}」了，慢慢来。"
            }
        }
    }

    /** 长按待办 → 立即开始：把开始时间改到此刻，并进入进行中。 */
    fun startRightNow(task: Task) {
        viewModelScope.launch {
            container.startTaskRightNow(task)
            _message.value = "现在开始「${task.title}」，慢慢来。"
        }
    }

    /** 长按待办 → 挪到以后：只移动这一次，时间与步骤保持不变。 */
    fun moveTaskTo(task: Task, date: LocalDate) {
        viewModelScope.launch {
            container.moveTaskTo(task, date)
            val today = LocalDate.now()
            _message.value = when (date) {
                today -> "已把「${task.title}」挪回今天。"
                today.plusDays(1) -> "已把「${task.title}」挪到明天。"
                else -> "已把「${task.title}」挪到 ${date.monthValue} 月 ${date.dayOfMonth} 日。"
            }
        }
    }

    /** 长按待办 → 挪到最近：进行中待办之后的下一个 5 分钟整点。 */
    fun moveTaskToNearest(task: Task) {
        viewModelScope.launch {
            val activeEnd = uiState.value?.active
                ?.takeIf { it.phase == com.capsulepal.app.data.model.TaskPhase.IN_PROGRESS }
                ?.task?.endMillis
            val target = container.moveTaskToNearest(task, activeEnd)
            val clock = "%02d:%02d".format(target.hour, target.minute)
            val today = LocalDate.now()
            _message.value = if (target.toLocalDate() == today) {
                "已把「${task.title}」挪到今天 $clock。"
            } else {
                "已把「${task.title}」挪到 ${target.monthValue} 月 ${target.dayOfMonth} 日 $clock。"
            }
        }
    }

    fun saveDraft(draft: TaskDraft, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            if (draft.title.isBlank()) {
                _message.value = "先给它起个名字吧。"
                onResult(false)
                return@launch
            }
            if (draft.id == 0L && !container.taskRepository.canAddTask()) {
                notifyTaskLimit()
                onResult(false)
                return@launch
            }
            val savedId = container.saveTask(draft)
            onResult(true)
            _message.value = "已把「${draft.title.trim()}」放进时间线。"
            if (draft.id == 0L) {
                _highlightTaskId.value = savedId
                delay(1800)
                if (_highlightTaskId.value == savedId) {
                    _highlightTaskId.value = null
                }
            }
        }
    }

    private fun notifyTaskLimit() {
        _toast.tryEmit(
            "待办数量已达上限（${TaskRepository.MAX_OPEN_TASKS}），先完成或清理一些再添加。"
        )
    }

    fun deleteTask(taskId: Long) {
        viewModelScope.launch {
            container.deleteTask(taskId)
            _message.value = "这件已经从时间线里拿掉了。"
        }
    }

    /** 删除这一天及之后的所有实例，今天之前的记录保留。 */
    fun deleteTaskFrom(taskId: Long) {
        viewModelScope.launch {
            container.deleteTaskFrom(taskId)
            _message.value = "这个待办已经结束，更早的记录保留。"
        }
    }

    /** 计划详情 · 按系列：长按后一次性删除整个系列的实例。 */
    fun deleteSeries(seriesId: Long) {
        viewModelScope.launch {
            val removed = container.deleteSeries(seriesId)
            _message.value = if (removed > 0) {
                "已删除这个系列的 $removed 条待办。"
            } else {
                "这个系列已经不存在了。"
            }
        }
    }

    fun applyTemplateFor(date: LocalDate) {
        viewModelScope.launch {
            if (!container.taskRepository.canAddTask()) {
                notifyTaskLimit()
                return@launch
            }
            val inserted = container.applyTemplate(HolidayProvider.templateType(date), date)
            _message.value = if (inserted == 0) {
                "模板里的安排已经在时间线里了。"
            } else {
                "按${HolidayProvider.templateType(date).label}模板加入了 $inserted 件。"
            }
        }
    }

    fun acceptSuggestion() {
        viewModelScope.launch {
            val current = container.triggerSuggestion.value ?: return@launch
            val draft = current.draft
            if (draft != null) {
                if (!container.taskRepository.canAddTask()) {
                    notifyTaskLimit()
                    container.dismissSuggestion()
                    return@launch
                }
                container.saveTask(draft)
                _message.value = "日历事件已经加入时间线，准备了 10 分钟窗口。"
            } else {
                val taskId = current.taskId
                val task = taskId?.let { container.taskRepository.task(it) }
                if (task != null) {
                    container.startTaskNow(task, TaskPhase.PREPARE)
                    _message.value = "现在开始准备「${task.title}」。"
                }
            }
            container.dismissSuggestion()
        }
    }

    fun dismissSuggestion() = container.dismissSuggestion()

    fun setKeepAlive(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                // 总开关打开：悬浮点回到左上角，透明度/间隔恢复默认，然后立即挂上。
                container.settingsRepository.enableKeepAliveWithDefaults()
            } else {
                container.settingsRepository.setKeepAliveEnabled(false)
            }
            container.syncBackgroundWatch()
            if (enabled) {
                FloatingKeepAliveService.start(getApplication())
                // 总开关打开：直接复位到左上角，不依赖服务内部的状态过渡。
                container.overlayDot.resetToTopLeft()
            }
        }
    }

    /** 从系统设置授权悬浮窗后回到应用，重新挂载一次悬浮点。 */
    fun refreshOverlayDot() {
        viewModelScope.launch {
            val settings = container.settingsRepository.settings.first()
            if (settings.keepAliveEnabled) {
                FloatingKeepAliveService.start(getApplication())
            }
        }
    }

    fun setOverlayHidden(hidden: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.setOverlayHidden(hidden)
            if (!hidden) {
                // 只恢复显示，保留上次位置，不重置也不提示。
                FloatingKeepAliveService.showDot(getApplication())
            }
        }
    }

    fun setOverlayMoveMinutes(minutes: Int) {
        viewModelScope.launch {
            // 服务会按悬浮点大小换算实际间隔，这里不再单独排闹钟。
            container.settingsRepository.setOverlayMoveMinutes(minutes)
        }
    }

    fun setOverlayAlphaPercent(percent: Int) {
        viewModelScope.launch {
            container.settingsRepository.setOverlayAlphaPercent(percent)
        }
    }

    fun setDualReminder(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.setDualReminderEnabled(enabled)
            LiveUpdateService.refresh(getApplication())
        }
    }

    fun refreshMonitor() {
        container.batteryMonitor.poke()
    }

    /** 清空学习样本，从下一次充电重新累计。 */
    fun resetBatteryLearning() {
        viewModelScope.launch {
            container.batteryRepository.resetLearning()
            container.batteryMonitor.poke()
        }
    }

    /** 0 表示恢复自动读取系统设计容量。 */
    fun setRatedCapacity(mah: Int) {
        viewModelScope.launch {
            container.settingsRepository.setRatedCapacity(mah)
            container.batteryMonitor.poke()
        }
    }

    /** 0 自动，1 单芯，2 双芯。 */
    fun setBatteryCells(cells: Int) {
        viewModelScope.launch {
            container.settingsRepository.setBatteryCells(cells)
            container.batteryMonitor.poke()
        }
    }

    fun setBatteryHighFrequencyAlways(enabled: Boolean) {
        viewModelScope.launch {
            // 极客体验锁定 1 秒采样：这里再兜一层，防止误关。
            if (!enabled) {
                val version = container.settingsRepository.settings.first().version
                if (version.recordsBatteryTimeline) return@launch
            }
            container.settingsRepository.setBatteryHighFrequencyAlways(enabled)
            container.batteryMonitor.poke()
            // 关掉高频后采样开关不再生效，同步让保活服务按需退出。
            container.syncBackgroundWatch()
        }
    }

    /** 非极客版同步记录极客数据；只在已开启高频轮询时才有意义。 */
    fun setGeekSamplingInOtherVersions(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.setGeekSamplingInOtherVersions(enabled)
            container.batteryMonitor.poke()
            container.syncBackgroundWatch()
        }
    }

    /** 切换版本：立即生效，不重启；数据保留，隐藏模块停止产生新数据。 */
    fun setVersion(version: CapsuleVersion) {
        viewModelScope.launch {
            container.settingsRepository.setVersion(version)
            container.onVersionChanged()
        }
    }

    fun setMetricOverlayAlpha(percent: Int) {
        viewModelScope.launch { container.settingsRepository.setMetricOverlayAlphaPercent(percent) }
    }

    fun setMetricOverlayScale(percent: Int) {
        viewModelScope.launch { container.settingsRepository.setMetricOverlayScalePercent(percent) }
    }

    fun setMetricOverlayShowTemp(show: Boolean) {
        viewModelScope.launch { container.settingsRepository.setMetricOverlayShowTemp(show) }
    }

    fun setMetricOverlayShowBattery(show: Boolean) {
        viewModelScope.launch { container.settingsRepository.setMetricOverlayShowBattery(show) }
    }

    fun setMetricOverlayShowFps(show: Boolean) {
        viewModelScope.launch { container.settingsRepository.setMetricOverlayShowFps(show) }
    }

    fun setMetricOverlayShowMemory(show: Boolean) {
        viewModelScope.launch { container.settingsRepository.setMetricOverlayShowMemory(show) }
    }

    fun setMetricOverlayEnabled(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.setMetricOverlayEnabled(enabled)
            container.onVersionChanged()
        }
    }

    fun setMetricOverlayHorizontal(horizontal: Boolean) {
        viewModelScope.launch {
            // 横竖排尺寸不同，先把位置复位，让新布局从默认位置重新挂出。
            container.settingsRepository.setMetricOverlayPosition(-1, -1)
            container.settingsRepository.setMetricOverlayHorizontal(horizontal)
            container.onVersionChanged()
        }
    }

    fun setHiddenBackground(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.setHiddenBackground(enabled)
        }
    }

    fun saveReminderRule(rule: ReminderRule) {
        viewModelScope.launch {
            val current = container.settingsRepository.settings.first()
            val index = current.reminderRules.indexOfFirst { it.id == rule.id }
            val merged = if (index >= 0) {
                current.reminderRules.toMutableList().also { it[index] = rule }
            } else {
                current.reminderRules + rule
            }
            container.settingsRepository.setReminderRules(merged)
            container.onReminderSettingsChanged()
            if (index < 0) container.autoBackup()
        }
    }

    fun deleteReminderRule(ruleId: Long) {
        viewModelScope.launch {
            val current = container.settingsRepository.settings.first()
            container.settingsRepository.setReminderRules(
                current.reminderRules.filterNot { it.id == ruleId }
            )
            container.onReminderSettingsChanged()
        }
    }

    fun setReminderRuleEnabled(ruleId: Long, enabled: Boolean) {
        viewModelScope.launch {
            val current = container.settingsRepository.settings.first()
            container.settingsRepository.setReminderRules(
                current.reminderRules.map {
                    if (it.id == ruleId) it.copy(enabled = enabled) else it
                }
            )
            container.onReminderSettingsChanged()
        }
    }

    fun setReminderCooldown(minutes: Int) {
        viewModelScope.launch { container.settingsRepository.setReminderCooldownMinutes(minutes) }
    }

    fun setQuietHours(startMinute: Int, endMinute: Int) {
        viewModelScope.launch {
            container.settingsRepository.setQuietHours(startMinute, endMinute)
        }
    }

    fun setQuietHoursEnabled(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.setQuietHoursEnabled(enabled)
        }
    }

    fun setMediaSilence(enabled: Boolean) {
        viewModelScope.launch { container.settingsRepository.setMediaSilenceEnabled(enabled) }
    }

    /** 主动提醒诊断：最近触发记录与运行环境。 */
    val reminderTrace = ReminderTrace.entries

    fun reminderDiagnostics() = container.reminderDiagnostics()

    fun clearReminderTrace() {
        ReminderTrace.clear()
    }

    /** 规则页回到设置页或重新可见时，刷新权限与日历闹钟。 */
    fun refreshReminderPermissions() {
        container.onReminderSettingsChanged()
    }

    /** 用户在系统文件选择器里选定备份目录（快速备份与自动备份共用）。 */
    fun chooseBackupDirectory(uri: Uri) {
        viewModelScope.launch {
            val ok = container.backupRepository.setDirectory(uri)
            _toast.tryEmit(if (ok) "备份位置已设置" else "无法使用这个位置，请换一个文件夹")
        }
    }

    fun manualBackup() {
        viewModelScope.launch {
            _toast.tryEmit(
                container.backupRepository.export(BackupKind.MANUAL).fold(
                    { name -> "备份完成：$name" },
                    { error -> "备份失败：${error.message ?: "未知错误"}" }
                )
            )
        }
    }

    /** 点击「今天」页顶部伙伴气泡任意区域时的快速备份。 */
    fun quickBackup() {
        viewModelScope.launch {
            if (!container.backupRepository.hasDirectory()) {
                _toast.tryEmit("请先在设置 > 备份中选择保存位置")
                return@launch
            }
            _toast.tryEmit(
                container.backupRepository.export(BackupKind.QUICK).fold(
                    { "已快速备份" },
                    { error -> "备份失败：${error.message ?: "未知错误"}" }
                )
            )
        }
    }

    fun importBackup(uri: Uri) {
        viewModelScope.launch {
            _toast.tryEmit(
                container.backupRepository.import(uri).fold(
                    { result ->
                        "已导入 ${result.taskCount} 条待办与日程、${result.ruleCount} 条主动规则"
                    },
                    { error -> "导入失败：${error.message ?: "未知错误"}" }
                )
            )
        }
    }

    fun setDailyFullXp(count: Int) {
        viewModelScope.launch { container.settingsRepository.setDailyFullXpCount(count) }
    }

    fun setCategoryLayout(layout: List<CategoryEntry>) {
        viewModelScope.launch { container.settingsRepository.setCategoryLayout(layout) }
    }

    fun acceptUserNotice() {
        viewModelScope.launch { container.settingsRepository.setNoticeAccepted() }
    }

    /** 分类编辑面板点「完成」时一次性提交：先删自建分类，再写入最终顺序。 */
    fun applyCategoryDraft(layout: List<CategoryEntry>, deletedCustoms: List<String>) {
        viewModelScope.launch {
            deletedCustoms.forEach { container.settingsRepository.deleteCustomCategory(it) }
            container.settingsRepository.setCategoryLayout(layout)
        }
    }

    fun resetCategoryLayout() {
        viewModelScope.launch { container.settingsRepository.resetCategoryLayout() }
    }

    fun addCustomCategory(name: String) {
        viewModelScope.launch { container.settingsRepository.addCustomCategory(name) }
    }

    fun deleteCustomCategory(name: String) {
        viewModelScope.launch { container.settingsRepository.deleteCustomCategory(name) }
    }

    fun renamePartner(name: String) {
        viewModelScope.launch {
            container.partnerRepository.setName(name)
            _message.value = "以后就叫它「${name.trim().ifBlank { "小胶囊" }}」了。"
        }
    }

    fun setPartnerAppearance(appearance: PartnerAppearance, enabled: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.setPartnerAppearance(appearance, enabled)
        }
    }

    fun completeOnboarding() {
        viewModelScope.launch { container.settingsRepository.setOnboardingDone(true) }
    }

    fun startIslandTest() {
        container.notifications.startIslandTest()
        _testStartedAt.value = System.currentTimeMillis()
        LiveUpdateService.refresh(getApplication())
        _message.value = "测试待办已上岛：1 分钟后开始，持续 1 分钟，按钮不会计入成长。"
    }

    fun refreshNotificationsNow() {
        LiveUpdateService.refresh(getApplication())
        _message.value = "已刷新当前这一件事。"
    }

    fun refreshPermissionsState() {
        viewModelScope.launch {
            container.rescheduleAll()
            LiveUpdateService.refresh(getApplication())
        }
    }

    fun dismissCompletion() {
        _completion.value = null
    }

    fun clearMessage() {
        _message.value = null
    }
}
