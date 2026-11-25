package com.capsulepal.app.data.model

/** 五个版本共用的页面；每个版本按自己的顺序挑选一部分展示。 */
enum class CapsulePage(val label: String) {
    POWER("功耗"),
    BATTERY("电池"),
    MONITOR("监测"),
    PARTNER("伙伴"),
    TODAY("待办"),
    SCHEDULE("日程"),
    PLANS("计划"),
    OVERLAY("悬浮窗"),
    SETTINGS("设置")
}

/**
 * App 的五个版本。
 *
 * 版本只改变外壳、配色与页面集合，所有业务数据共用同一套本地库；
 * 隐藏的模块停止产生新数据（冻结）但保留已有数据。
 */
enum class CapsuleVersion(
    val label: String,
    val summary: String,
    val detail: String,
    val pages: List<CapsulePage>,
    val centerPage: CapsulePage?,
    /** 只在极客版记录放电会话。 */
    val recordsDischargeHistory: Boolean,
    /** 只在极客版记录 24 小时电池采样。 */
    val recordsBatteryTimeline: Boolean,
    /** 只在极客版记录应用功耗历史。 */
    val recordsAppPower: Boolean,
    /** 有待办页时，是否在页内提供日程入口。 */
    val showScheduleEntry: Boolean,
    /** 右上角汇总入口（极简待办版与效率版都保留）。 */
    val showSummaryEntry: Boolean = true,
    /** 分类管理入口。 */
    val showCategoryManagement: Boolean = true
) {
    FULL(
        label = "完整本体",
        summary = "监测、伙伴、待办、计划与设置",
        detail = "完整保留监测、伙伴、待办、计划与设置。",
        pages = listOf(
            CapsulePage.MONITOR,
            CapsulePage.PARTNER,
            CapsulePage.TODAY,
            CapsulePage.PLANS,
            CapsulePage.SETTINGS
        ),
        centerPage = CapsulePage.TODAY,
        recordsDischargeHistory = false,
        recordsBatteryTimeline = false,
        recordsAppPower = false,
        showScheduleEntry = true
    ),
    BALANCED(
        label = "极简综合",
        summary = "监测、日程、待办、计划与设置",
        detail = "去掉伙伴页，日常监测与日程并重。",
        pages = listOf(
            CapsulePage.MONITOR,
            CapsulePage.SCHEDULE,
            CapsulePage.TODAY,
            CapsulePage.PLANS,
            CapsulePage.SETTINGS
        ),
        centerPage = CapsulePage.TODAY,
        recordsDischargeHistory = false,
        recordsBatteryTimeline = false,
        recordsAppPower = false,
        showScheduleEntry = true
    ),
    EFFICIENT(
        label = "极简效率",
        summary = "计划、待办与设置",
        detail = "只留计划与待办，日程从待办页进入；提醒与其它版本共用。",
        pages = listOf(
            CapsulePage.PLANS,
            CapsulePage.TODAY,
            CapsulePage.SETTINGS
        ),
        centerPage = CapsulePage.TODAY,
        recordsDischargeHistory = false,
        recordsBatteryTimeline = false,
        recordsAppPower = false,
        showScheduleEntry = true
    ),
    TODO(
        label = "极简待办",
        summary = "只有待办与上岛",
        detail = "单页待办，没有底栏；汇总与设置收在右上角，只保留普通待办。",
        pages = emptyList(),
        centerPage = null,
        recordsDischargeHistory = false,
        recordsBatteryTimeline = false,
        recordsAppPower = false,
        showScheduleEntry = false
    ),
    GEEK(
        label = "极客体验",
        summary = "功耗、电池、监测、悬浮窗与设置",
        detail = "记录充放电会话、24 小时采样与应用功耗，并提供独立悬浮指标。",
        pages = listOf(
            CapsulePage.POWER,
            CapsulePage.BATTERY,
            CapsulePage.MONITOR,
            CapsulePage.OVERLAY,
            CapsulePage.SETTINGS
        ),
        centerPage = CapsulePage.MONITOR,
        recordsDischargeHistory = true,
        recordsBatteryTimeline = true,
        recordsAppPower = true,
        showScheduleEntry = false
    );

    val usesBottomBar: Boolean get() = pages.isNotEmpty()

    /** 极客体验：没有待办模块，长按菜单的待办入口需要先切回普通版本。 */
    val isGeek: Boolean get() = this == GEEK

    val showMonitorPage: Boolean get() = CapsulePage.MONITOR in pages
    val showPartnerPage: Boolean get() = CapsulePage.PARTNER in pages
    val showPlansPage: Boolean get() = CapsulePage.PLANS in pages
    val showSchedulePage: Boolean get() = CapsulePage.SCHEDULE in pages
    val showTodoPage: Boolean get() = pages.isEmpty() || CapsulePage.TODAY in pages
    val showGeekTools: Boolean get() = CapsulePage.POWER in pages || CapsulePage.BATTERY in pages
    val showFloatingMetrics: Boolean get() = CapsulePage.OVERLAY in pages
    /** 五个版本共用同一套提醒规则；极客版只能触发纯自定义提醒。 */
    val showReminders: Boolean get() = true

    /**
     * 任务所属模块在当前版本是否可见。
     * 隐藏模块冻结：不补系列、不结算逾期、不排闹钟、不上岛。
     */
    fun isTaskVisible(task: Task): Boolean {
        if (task.planId > 0L) return showPlansPage
        return when (task.kind) {
            TaskKind.SCHEDULE -> showSchedulePage || showScheduleEntry
            TaskKind.NORMAL -> showTodoPage
        }
    }

    /** 切换版本弹窗里列出的「已有功能」。 */
    val features: List<String>
        get() = buildList {
            if (showMonitorPage) add("监测")
            if (showPartnerPage) add("伙伴")
            if (showTodoPage) add("待办")
            if (showSchedulePage || showScheduleEntry) add("日程")
            if (showPlansPage) add("计划")
            add("设置")
            if (showReminders) add("提醒")
            if (CapsulePage.POWER in pages) add("功耗")
            if (CapsulePage.BATTERY in pages) add("电池")
            if (showFloatingMetrics) add("悬浮窗")
            if (recordsBatteryTimeline) add("极客数据记录")
        }

    /** 切换版本弹窗里列出的「隐藏功能」。 */
    val hiddenFeatures: List<String>
        get() = ALL_FEATURES - features.toSet()

    companion object {
        private val ALL_FEATURES = listOf(
            "监测", "伙伴", "待办", "日程", "计划", "设置", "提醒",
            "功耗", "电池", "悬浮窗", "极客数据记录"
        )

        fun fromName(name: String?): CapsuleVersion =
            entries.firstOrNull { it.name == name } ?: FULL
    }
}
