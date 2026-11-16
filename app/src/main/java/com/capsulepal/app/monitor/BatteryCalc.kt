package com.capsulepal.app.monitor

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** 一次充电会话的展示视图；endTs = 0 表示仍在进行中。 */
data class ChargeSessionView(
    val id: Long,
    val startTs: Long,
    val endTs: Long,
    val startPct: Int,
    val endPct: Int
) {
    val live: Boolean get() = endTs == 0L

    fun endAt(nowMillis: Long): Long = if (endTs == 0L) nowMillis else endTs

    fun durationMinutes(nowMillis: Long): Int =
        ((endAt(nowMillis) - startTs) / 60_000L).toInt().coerceAtLeast(0)

    fun speedPctPerMin(nowMillis: Long): Float? {
        val minutes = (endAt(nowMillis) - startTs) / 60_000f
        if (minutes < 0.5f) return null
        return abs(endPct - startPct) / minutes
    }
}

/**
 * 电池监测的纯逻辑：容量/健康度换算、学习权重、格式化。
 * 不依赖 Android，可直接单元测试。
 */
object BatteryCalc {

    const val DEFAULT_RATED_MAH = 5000

    /** 满充静置判定：连续 10 分钟电流绝对值小于 25mA（对齐社区做法）。 */
    const val FULL_QUIET_WINDOW_MILLIS = 10 * 60_000L
    const val FULL_QUIET_CURRENT_MA = 25f

    /** 学习样本上限：只取最近 10 次会话，避免早期样本拖住老化判断。 */
    const val MAX_LEARNING_SAMPLES = 10

    const val WEIGHT_FULL = 50
    const val WEIGHT_FULL_REACHED = 40
    const val WEIGHT_AMPLITUDE_50 = 30
    const val WEIGHT_AMPLITUDE_30 = 20
    const val WEIGHT_AMPLITUDE_20 = 10

    /** 最小充电幅度：低于 20% 的会话不参与学习。 */
    const val MIN_AMPLITUDE_PCT = 20

    const val ESTIMATOR_FULL = "FULL"
    const val ESTIMATOR_DELTA = "DELTA"
    const val ESTIMATOR_LEGACY = "LEGACY"

    data class HealthSample(val healthPct: Float, val weight: Int)

    /** 由剩余容量和电量百分比外推满电容量。 */
    fun estimateFullCapacityMah(remainingMah: Int?, levelPct: Int?): Int? {
        if (remainingMah == null || levelPct == null) return null
        if (remainingMah <= 0 || levelPct !in 1..100) return null
        return (remainingMah * 100f / levelPct).roundToInt()
    }

    fun healthPercent(fullCapacityMah: Int?, ratedMah: Int): Float? {
        if (fullCapacityMah == null || fullCapacityMah <= 0 || ratedMah <= 0) return null
        return fullCapacityMah * 100f / ratedMah
    }

    fun healthPercentFrom(remainingMah: Int?, levelPct: Int?, ratedMah: Int): Float? =
        healthPercent(estimateFullCapacityMah(remainingMah, levelPct), ratedMah)

    fun chargePowerW(voltageMv: Int?, currentMa: Float?): Float? {
        if (voltageMv == null || currentMa == null || voltageMv <= 0) return null
        return voltageMv * abs(currentMa) / 1_000_000f
    }

    /**
     * 增量法估算满电容量：`(ΔmAh) / (Δ电量%)`。
     * 电量计的绝对值没有“空/满”参考，只有相对变化可靠，因此用增量而不是终点外推。
     */
    fun estimateCapacityByDelta(
        startMah: Int?,
        endMah: Int?,
        startPct: Int?,
        endPct: Int?
    ): Int? {
        if (startMah == null || endMah == null || startPct == null || endPct == null) return null
        if (startMah <= 0 || endMah <= 0) return null
        val deltaPct = endPct - startPct
        val deltaMah = endMah - startMah
        if (deltaPct <= 0 || deltaMah <= 0) return null
        return (deltaMah * 100f / deltaPct).roundToInt()
    }

    /**
     * 学习健康度权重：满充静置优先（即使幅度不足也记 50）；
     * 仅到达 100% 但未静置记 40；其余按充电幅度分档，<20% 不记录。
     */
    fun learningWeight(
        amplitudePct: Int,
        fullQuiet: Boolean,
        reachedFull: Boolean = false
    ): Int? = when {
        fullQuiet -> WEIGHT_FULL
        reachedFull -> WEIGHT_FULL_REACHED
        amplitudePct >= 50 -> WEIGHT_AMPLITUDE_50
        amplitudePct >= 30 -> WEIGHT_AMPLITUDE_30
        amplitudePct >= MIN_AMPLITUDE_PCT -> WEIGHT_AMPLITUDE_20
        else -> null
    }

    /** 按记录里的原始值 + 当前额定容量重算健康度。 */
    fun healthPercentForSample(
        estimator: String,
        startMah: Int,
        startPct: Int,
        endMah: Int,
        endPct: Int,
        ratedMah: Int
    ): Float? = when (estimator) {
        ESTIMATOR_FULL -> healthPercent(endMah, ratedMah)
        ESTIMATOR_DELTA -> healthPercent(
            estimateCapacityByDelta(startMah, endMah, startPct, endPct),
            ratedMah
        )
        else -> null
    }

    fun weightedHealth(samples: List<HealthSample>): Float? {
        val valid = samples.filter { it.weight > 0 && it.healthPct > 0f }
        if (valid.isEmpty()) return null
        val totalWeight = valid.sumOf { it.weight }
        if (totalWeight <= 0) return null
        return (valid.sumOf { (it.healthPct * it.weight).toDouble() } / totalWeight).toFloat()
    }

    /** 满充且静置的候选时刻：100% + 系统 FULL + 电流绝对值 < 25mA。 */
    fun isFullQuietCandidate(reading: BatteryReading): Boolean {
        if (!reading.statusFull) return false
        if (reading.levelPct != 100) return false
        val current = reading.currentMa ?: return false
        return abs(current) < FULL_QUIET_CURRENT_MA
    }

    fun todayStart(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(nowMillis)
            .atZone(zone)
            .toLocalDate()
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()

    fun formatDuration(minutes: Int): String = when {
        minutes <= 0 -> "不足 1 分"
        minutes < 60 -> "$minutes 分"
        else -> "${minutes / 60} 小时 ${minutes % 60} 分"
    }

    fun formatSpeed(pctPerMin: Float): String =
        String.format(Locale.CHINA, "%.2f%%/分", pctPerMin)

    fun formatPower(watt: Float): String =
        String.format(Locale.CHINA, "%.1f W", watt)

    /** 胶囊右侧最多 7 个字符；功率统一保留一位小数。 */
    fun formatPowerCompact(watt: Float): String =
        String.format(Locale.CHINA, "%.1fW", watt)

    fun formatTemp(celsius: Float): String =
        String.format(Locale.CHINA, "%.1f ℃", celsius)

    fun formatVoltage(millivolt: Int): String =
        String.format(Locale.CHINA, "%.2f V", millivolt / 1000f)

    fun formatCurrent(milliamp: Float): String =
        String.format(Locale.CHINA, "%.0f mA", abs(milliamp))

    fun formatHealth(pct: Float): String =
        String.format(Locale.CHINA, "%.0f%%", pct)
}
