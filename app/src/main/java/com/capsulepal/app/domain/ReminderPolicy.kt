package com.capsulepal.app.domain

/**
 * 提醒体验的统一策略：免打扰判定与事件去重键。
 * 全部是纯函数，便于单测覆盖跨零点与边界值。
 */
object ReminderPolicy {

    /** 提醒出口的放行结果，用于诊断记录。 */
    enum class GateResult(val label: String) {
        ALLOWED("已放行"),
        DAILY_LIMIT("今日次数已用完"),
        GLOBAL_COOLDOWN("全局冷却中"),
        EVENT_DUPLICATE("同一事件刚提醒过")
    }

    /** 支持跨零点的免打扰判断；开始与结束相同表示不占用任何时段。 */
    fun isQuietMinute(minuteOfDay: Int, startMinute: Int, endMinute: Int): Boolean {
        val minute = minuteOfDay.coerceIn(0, MINUTES_PER_DAY - 1)
        val start = startMinute.coerceIn(0, MINUTES_PER_DAY - 1)
        val end = endMinute.coerceIn(0, MINUTES_PER_DAY - 1)
        if (start == end) return false
        return if (start < end) {
            minute in start until end
        } else {
            minute >= start || minute < end
        }
    }

    fun isQuietMinute(nowMillis: Long, startMinute: Int, endMinute: Int): Boolean {
        val minute = java.time.Instant.ofEpochMilli(nowMillis)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalTime()
            .let { it.hour * 60 + it.minute }
        return isQuietMinute(minute, startMinute, endMinute)
    }

    /**
     * 同一事件 / 待办的去重键。
     * 只对「同一次日历事件、同一个应用、同一条待办」去重；
     * 充电 / WiFi / 蓝牙 / 耳机每次事件都独立，交给全局冷却兜底，
     * 避免不同触发源在 30 分钟内互相压制。
     */
    fun cooldownKey(
        eventStartMillis: Long,
        appLaunchPackage: String?,
        taskId: Long?
    ): String? = when {
        eventStartMillis > 0L -> "calendar:$eventStartMillis"
        appLaunchPackage != null -> "app:${normalize(appLaunchPackage)}"
        taskId != null -> "task:$taskId"
        else -> null
    }

    /** 空值统一成 `*`，对应「任意 WiFi / 任意设备」的条件。 */
    private fun normalize(value: String): String {
        val clean = value.trim().lowercase()
        return if (clean.isEmpty()) "*" else clean
    }

    private const val MINUTES_PER_DAY = 24 * 60
}
