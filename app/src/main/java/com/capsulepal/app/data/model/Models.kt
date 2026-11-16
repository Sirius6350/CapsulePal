package com.capsulepal.app.data.model

import com.capsulepal.app.partner.PartnerAppearance
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

enum class TaskCategory(val label: String) {
    CUSTOM("自定义"),
    ROUTINE("起居"),
    OUTING("出行"),
    FOOD("饮食"),
    WATER("喝水"),
    PAYMENT("缴费"),
    PET("宠物"),
    FOCUS("专注"),
    MEETING("会议"),
    MEDICATION("用药"),
    EXERCISE("运动"),
    REST("休息"),
    PICKUP("取物"),
    SOCIAL("社交"),
    SHOPPING("购物"),
    CHORES("家务"),
    OTHER("其他")
}

/** 分类编辑里的一条：内置分类，或用户自建的分类名称。 */
sealed interface CategoryEntry {
    data class BuiltIn(val category: TaskCategory) : CategoryEntry
    data class Custom(val name: String) : CategoryEntry
}

enum class Priority(val label: String, val weight: Int) {
    LOW("不急", 0),
    NORMAL("刚刚好", 1),
    HIGH("重要", 2)
}

enum class TaskStatus {
    IDLE,
    PREPARE_WINDOW,
    IN_PROGRESS,
    COMPLETED,
    SKIPPED
}

enum class TaskPhase { PREPARE, IN_PROGRESS }

enum class RepeatRule(val label: String) {
    ONCE("仅一次"),
    DAILY("每天"),
    WEEKLY("每周"),
    CONSECUTIVE("连续天数")
}

/** 「连续 n 天」的天数范围：含起始日，不设业务上限，这里只做防御性封顶。 */
const val MIN_CONSECUTIVE_DAYS = 2
const val MAX_CONSECUTIVE_DAYS = 1000

/** 待办的来源：普通待办 / 日程待办；计划待办由 planId 判断。 */
enum class TaskKind(val label: String) {
    NORMAL("待办"),
    SCHEDULE("日程")
}

enum class AlarmPoint { PREPARE_START, TASK_START, TASK_END }

enum class TemplateType(val label: String) {
    WEEKDAY("工作日"),
    WEEKEND("休息日")
}

data class TaskStep(
    val id: Long = 0L,
    val order: Int,
    val title: String,
    val durationMinutes: Int = 0
)

data class Task(
    val id: Long = 0L,
    val title: String,
    val shortTitle: String = "",
    val date: LocalDate,
    val startTime: LocalTime,
    val durationMinutes: Int = 30,
    val prepareMinutes: Int = 10,
    val category: TaskCategory = TaskCategory.ROUTINE,
    val customCategory: String = "",
    val priority: Priority = Priority.NORMAL,
    val status: TaskStatus = TaskStatus.IDLE,
    val note: String = "",
    val steps: List<TaskStep> = emptyList(),
    val repeat: RepeatRule = RepeatRule.ONCE,
    val repeatDays: Set<DayOfWeek> = emptySet(),
    /** 「连续 n 天」的总天数（含起始日）；其他重复规则为 0。 */
    val repeatCount: Int = 0,
    val skipHolidays: Boolean = false,
    val seriesId: Long = 0L,
    /** 由模板批量生成的任务；一旦用户自建任务，今日页不再推荐模板。 */
    val fromTemplate: Boolean = false,
    /** 用户自定义分组；0 表示未分组。 */
    val groupId: Long = 0L,
    /** 归属的计划；0 表示不属于任何计划。 */
    val planId: Long = 0L,
    /** 待办来源：待办页 / 日程页。 */
    val kind: TaskKind = TaskKind.NORMAL
) {
    val startMillis: Long
        get() = date.atTime(startTime).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    val endMillis: Long
        get() = startMillis + durationMinutes * 60_000L

    val prepareStartMillis: Long
        get() = startMillis - prepareMinutes * 60_000L

    val isOpen: Boolean
        get() = status == TaskStatus.IDLE || status == TaskStatus.PREPARE_WINDOW || status == TaskStatus.IN_PROGRESS
}

data class TaskDraft(
    val id: Long = 0L,
    val title: String = "",
    val shortTitle: String = "",
    val date: LocalDate = LocalDate.now(),
    val startTime: LocalTime = LocalTime.now().plusMinutes(30).withSecond(0).withNano(0),
    val durationMinutes: Int = 30,
    val prepareMinutes: Int = 10,
    val category: TaskCategory = TaskCategory.ROUTINE,
    val customCategory: String = "",
    val priority: Priority = Priority.NORMAL,
    val note: String = "",
    val steps: List<TaskStepDraft> = emptyList(),
    val repeat: RepeatRule = RepeatRule.ONCE,
    val repeatDays: Set<DayOfWeek> = emptySet(),
    val repeatCount: Int = 0,
    val skipHolidays: Boolean = false,
    val fromTemplate: Boolean = false,
    val groupId: Long = 0L,
    val planId: Long = 0L,
    val kind: TaskKind = TaskKind.NORMAL
) {
    fun toTask(existingStatus: TaskStatus = TaskStatus.IDLE): Task = Task(
        id = id,
        title = title.trim(),
        shortTitle = shortTitle.trim(),
        date = date,
        startTime = startTime,
        durationMinutes = durationMinutes,
        prepareMinutes = prepareMinutes,
        category = category,
        customCategory = if (category == TaskCategory.CUSTOM) customCategory.trim() else "",
        priority = priority,
        status = existingStatus,
        note = note.trim(),
        steps = steps.mapIndexed { index, step ->
            TaskStep(
                id = step.id,
                order = index,
                title = step.title.trim(),
                durationMinutes = step.durationMinutes
            )
        },
        repeat = repeat,
        repeatDays = repeatDays,
        repeatCount = if (repeat == RepeatRule.CONSECUTIVE) {
            repeatCount.coerceIn(MIN_CONSECUTIVE_DAYS, MAX_CONSECUTIVE_DAYS)
        } else {
            0
        },
        skipHolidays = if (repeat == RepeatRule.CONSECUTIVE) false else skipHolidays,
        fromTemplate = fromTemplate,
        groupId = groupId,
        planId = planId,
        kind = kind
    )
}

data class TaskStepDraft(
    val id: Long = 0L,
    val title: String = "",
    val durationMinutes: Int = 0
)

data class TemplateBlock(
    val title: String,
    val startTime: LocalTime,
    val durationMinutes: Int,
    val prepareMinutes: Int,
    val category: TaskCategory
)

data class ScheduleTemplate(
    val type: TemplateType,
    val blocks: List<TemplateBlock>
)

data class PartnerProfile(
    val name: String = "小胶囊",
    val totalXp: Int = 0,
    val completedTotal: Int = 0,
    val completedToday: Int = 0,
    val lastCompletionEpochDay: Long = 0L,
    val companionDays: Int = 1
)

data class ActiveTask(
    val task: Task,
    val phase: TaskPhase,
    val windowStartMillis: Long,
    val windowEndMillis: Long
) {
    fun remainingMillis(nowMillis: Long): Long = (windowEndMillis - nowMillis).coerceAtLeast(0L)

    fun progress(nowMillis: Long): Float {
        val total = (windowEndMillis - windowStartMillis).coerceAtLeast(1L)
        val elapsed = (nowMillis - windowStartMillis).coerceIn(0L, total)
        return elapsed.toFloat() / total.toFloat()
    }
}

data class UpcomingTask(
    val task: Task,
    val untilPrepareMillis: Long,
    val untilStartMillis: Long
)

data class TimelineState(
    val active: ActiveTask? = null,
    val next: UpcomingTask? = null
)

data class TaskGroup(val id: Long = 0L, val name: String, val sortOrder: Int = 0)

/** 计划颜色只在展示层使用，存库时保存名称。 */
enum class PlanColor(val label: String, val argb: Long) {
    CORAL("珊瑚", 0xFFE8735A),
    MINT("薄荷", 0xFF7FB6A4),
    BUTTER("奶油", 0xFFF2C879),
    LAVENDER("薰衣草", 0xFFA99BC9)
}

/** 计划类型：重复计划只看完成次数；周期计划必填起止日期，进度按占比。 */
enum class PlanType(val label: String) {
    REPEAT("重复计划"),
    PERIOD("周期计划")
}

/** 计划：一组有关联待办的目标，进度按计划内待办的完成情况计算。 */
data class Plan(
    val id: Long = 0L,
    val title: String,
    val note: String = "",
    val color: PlanColor = PlanColor.CORAL,
    val type: PlanType = PlanType.REPEAT,
    /** 周期计划的开始日期；重复计划为 null。 */
    val startDate: LocalDate? = null,
    /** 周期计划的结束日期（必填）；重复计划为可选的目标日期。 */
    val targetDate: LocalDate? = null,
    val createdAtEpochDay: Long = LocalDate.now().toEpochDay(),
    val archived: Boolean = false
) {
    val isPeriod: Boolean get() = type == PlanType.PERIOD

    /** 周期计划的收尾日；重复计划没有终点，重复待办不会被截断。 */
    val endDate: LocalDate?
        get() = if (isPeriod) targetDate else null
}

enum class TodoLayout { LIST, GRID }

/** 第二天仍未处理的待办怎么结算；结算不发放经验。 */
enum class OverduePolicy(val label: String, val result: TaskStatus) {
    MARK_DONE("视为完成", TaskStatus.COMPLETED),
    MARK_SKIPPED("视为跳过", TaskStatus.SKIPPED)
}

data class AppSettings(
    val keepAliveEnabled: Boolean = true,
    val overlayHidden: Boolean = false,
    val overlayMoveMinutes: Int = 3,
    /** 悬浮点不透明度百分比，只能取 10–80 且为 10 的整数倍。 */
    val overlayAlphaPercent: Int = 80,
    val onboardingDone: Boolean = false,
    val exactAlarmAsked: Boolean = false,
    /** 用户自定义的主动提醒规则。 */
    val reminderRules: List<ReminderRule> = ReminderRules.defaults(),
    /** 全局冷却分钟数；0 表示关闭冷却。 */
    val reminderCooldownMinutes: Int = 30,
    /** 免打扰开关；默认关闭，开启后默认 23:00–07:00 只记录不弹出。 */
    val quietHoursEnabled: Boolean = false,
    /** 免打扰开始/结束，按一天中的分钟数保存，支持跨零点。 */
    val quietStartMinute: Int = 23 * 60,
    val quietEndMinute: Int = 7 * 60,
    /** 播放媒体时静默所有非日历提醒。 */
    val mediaSilenceEnabled: Boolean = true,
    val dailyFullXpCount: Int = 4,
    val customCategories: Set<String> = emptySet(),
    val categoryLayout: List<CategoryEntry> = CategoryCatalog.defaultLayout,
    /** 用户点过“收到”的待办，本次不再上岛。 */
    val acknowledgedTaskId: Long = 0L,
    /** 上岛之外再发一条普通通知。 */
    val dualReminderEnabled: Boolean = false,
    /** 额定容量（mAh）；0 表示自动读取系统设计容量，读不到时回落 5000。 */
    val ratedCapacityMah: Int = 0,
    /** 打开后放电状态也保持 1 秒一次的高频监测。 */
    val batteryHighFrequencyAlways: Boolean = false,
    /** 非极客版也同步记录极客体验数据（采样、充放电会话、应用功耗）。 */
    val geekSamplingInOtherVersions: Boolean = false,
    /** 伙伴形象的可勾选外观（扫描线、光晕等），与等级无关。 */
    val partnerAppearance: Set<PartnerAppearance> = PartnerAppearance.defaults,
    /** 首次启动的用户须知是否已确认。 */
    val noticeAccepted: Boolean = false,
    /** 快速备份与自动备份共用的保存目录（SAF 持久化 URI），空表示尚未设置。 */
    val backupDirUri: String = "",
    /** 待办页布局：列表或宫格。 */
    val todoLayout: TodoLayout = TodoLayout.LIST,
    /** 待办页「近 n 天」的自定义天数，范围 1–30。 */
    val todoCustomRangeDays: Int = 7,
    /** 早于今天仍未处理的待办，次日按此方式结算。 */
    val overduePolicy: OverduePolicy = OverduePolicy.MARK_DONE,
    /** 已完成 / 已跳过的待办从次日起不再显示在待办页（数据保留）。 */
    val hideFinishedAfterDay: Boolean = true,
    /** 当前版本；老用户升级后默认完整本体版。 */
    val version: CapsuleVersion = CapsuleVersion.FULL,
    /** 极客版独立指标悬浮窗的不透明度（10–100%）。 */
    val metricOverlayEnabled: Boolean = false,
    val metricOverlayAlphaPercent: Int = 80,
    /** 极客版独立指标悬浮窗的整体大小（80–140%）。 */
    val metricOverlayScalePercent: Int = 100,
    /** 指标悬浮窗显示哪几项。 */
    val metricOverlayShowTemp: Boolean = true,
    val metricOverlayShowBattery: Boolean = true,
    val metricOverlayShowFps: Boolean = true,
    val metricOverlayShowMemory: Boolean = true,
    /** 横排展示指标；右侧固定留白，避免数值跳动导致宽度抽搐。 */
    val metricOverlayHorizontal: Boolean = false,
    /** 拖动后的窗口位置（px，相对 TOP|END 锚点）；-1 表示用默认位置。 */
    val metricOverlayOffsetX: Int = -1,
    val metricOverlayOffsetY: Int = -1,
    /** 从最近任务列表隐藏本应用卡片。 */
    val hiddenBackground: Boolean = false
) {
    val overlayMoveMinutesSafe: Int
        get() = overlayMoveMinutes.coerceIn(1, 30)

    val todoCustomRangeSafe: Int
        get() = todoCustomRangeDays.coerceIn(1, 30)

    val overlayAlphaSafe: Int
        get() = (overlayAlphaPercent / 10 * 10).coerceIn(10, 80)

    val metricOverlayAlphaSafe: Int
        get() = metricOverlayAlphaPercent.coerceIn(10, 100)

    val metricOverlayScaleSafe: Int
        get() = (metricOverlayScalePercent / 10 * 10).coerceIn(80, 140)
}
