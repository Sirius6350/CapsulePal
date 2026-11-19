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

/** 额定容量的来源，用于在界面上如实标注，避免把默认值当成系统数据。 */
enum class CapacitySource(val label: String) {
    MANUAL("手动"),
    SYSTEM("系统设计"),
    SINGLE_CELL("单芯校正"),
    LEARNED("学习估算"),
    NONE("系统未提供")
}

/** 芯数判定与额定容量解析的完整结果。 */
data class RatedCapacityResolution(
    val cells: Int,
    val ratedMah: Int,
    val source: CapacitySource,
    /** 系统原始上报的设计容量（未乘芯数）。 */
    val designRawMah: Int?,
    /** 按芯数校正后的设计容量。 */
    val designMah: Int?
)

/**
 * 电池监测的纯逻辑：容量/健康度换算、学习权重、格式化。
 * 不依赖 Android，可直接单元测试。
 */
object BatteryCalc {

    const val DEFAULT_RATED_MAH = 5000

    /** 低于该值时按「系统只报了单芯容量」处理；高于它视为已上报整包容量。 */
    const val AUTO_DUAL_CELL_MAX_MAH = 3000

    const val MIN_CAPACITY_MAH = 1000
    const val MAX_CAPACITY_MAH = 20_000

    /** 充电功率护栏：0–300W，过滤厂商口径异常导致的离谱值。 */
    const val MAX_POWER_W = 300f

    /** BatteryManager.EXTRA_PLUGGED 的取值，纯逻辑里不能引用 Android 常量。 */
    const val PLUGGED_AC = 1
    const val PLUGGED_USB = 2
    const val PLUGGED_WIRELESS = 4
    const val PLUGGED_DOCK = 8

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
        val watt = voltageMv * abs(currentMa) / 1_000_000f
        if (watt <= 0f) return null
        return watt.coerceAtMost(MAX_POWER_W)
    }

    /**
     * 双芯校正强度：容量和电压可能各自需要放大，不能一刀切。
     * 例如「串联 + 整包电压」只需把容量按两芯相加；「串联 + 单芯电压」两者都要放大；
     * 「并联 + 整包容量」则都不需要放大。
     */
    data class BatteryCorrection(
        val cells: Int,
        val capacityScale: Int,
        val voltageScale: Int
    )

    /**
     * 自动判定芯数：
     * - 能量换算容量 ≥ 电荷容量 1.5 倍，说明电荷值只报了单芯；
     * - 电压 ≥ 6V，说明是串联整包；
     * - 设计容量 ≤ 3000 mAh 时按单芯上报兜底。
     */
    fun resolveCellCount(
        cellsSetting: Int,
        rawDesignMah: Int?,
        energyDesignMah: Int?,
        voltageMv: Int?
    ): Int {
        if (cellsSetting == 1 || cellsSetting == 2) return cellsSetting
        if (energyDesignMah != null && rawDesignMah != null && rawDesignMah > 0 &&
            energyDesignMah >= rawDesignMah * 1.5f
        ) {
            return 2
        }
        if (voltageMv != null && voltageMv >= 6000) return 2
        if (rawDesignMah != null && rawDesignMah in MIN_CAPACITY_MAH..AUTO_DUAL_CELL_MAX_MAH) {
            return 2
        }
        return 1
    }

    /**
     * 开源实现的通行判定（AOSP Health 2.1 的 charge/energy 语义）：
     * - 能量 µWh 与拓扑无关，能量换算容量 / 电荷容量 ≈ 2 说明系统只报了单芯；
     * - 串联整包电压 ≥ 6V 时，电荷容量是单串值，用户口径的 mAh 要按两芯相加；
     * - 并联整包（容量已是整包）不需要放大。
     */
    fun resolveCorrection(
        cellsSetting: Int,
        rawDesignMah: Int?,
        energyDesignMah: Int?,
        voltageMv: Int?
    ): BatteryCorrection {
        val cells = resolveCellCount(cellsSetting, rawDesignMah, energyDesignMah, voltageMv)
        if (cells != 2) {
            return BatteryCorrection(cells = 1, capacityScale = 1, voltageScale = 1)
        }

        val ratio = if (rawDesignMah != null && rawDesignMah > 0 && energyDesignMah != null) {
            energyDesignMah.toFloat() / rawDesignMah
        } else {
            null
        }
        val capacityScale = when {
            ratio != null && ratio >= 1.5f -> 2
            ratio == null && voltageMv != null && voltageMv >= 6000 -> 2
            ratio == null && rawDesignMah != null && rawDesignMah <= 3800 -> 2
            else -> 1
        }
        val voltageScale =
            if (capacityScale == 2 && voltageMv != null && voltageMv < 5000) 2 else 1
        return BatteryCorrection(
            cells = cells,
            capacityScale = capacityScale,
            voltageScale = voltageScale
        )
    }

    private fun scaleCapacity(mah: Int, scale: Int): Int =
        (mah * scale).coerceIn(MIN_CAPACITY_MAH, MAX_CAPACITY_MAH)

    fun scaledCapacity(mah: Int?, scale: Int): Int? =
        mah?.let { scaleCapacity(it, scale) }

    /**
     * 应用双芯校正：只改计算与展示用的容量/电压，原始电量百分比等采样不动。
     * 有能量换算值时优先用它，比 charge ×2 更接近厂商标称的等效容量。
     */
    fun applyCorrection(reading: BatteryReading, correction: BatteryCorrection): BatteryReading {
        if (correction.capacityScale == 1 && correction.voltageScale == 1) return reading
        return reading.copy(
            designCapacityMah = correctedCapacity(
                reading.designCapacityMah,
                reading.energyDesignCapacityMah,
                correction.capacityScale
            ),
            fullCapacityMah = correctedCapacity(
                reading.fullCapacityMah,
                reading.energyFullCapacityMah,
                correction.capacityScale
            ),
            chargeCounterMah = reading.chargeCounterMah?.let {
                scaleCapacity(it, correction.capacityScale)
            },
            voltageMv = reading.voltageMv?.let { mv ->
                if (correction.voltageScale == 2) (mv * 2).coerceAtMost(10_000) else mv
            }
        )
    }

    private fun correctedCapacity(raw: Int?, energyEquivalent: Int?, scale: Int): Int? =
        if (scale == 2 && energyEquivalent != null) {
            energyEquivalent
        } else {
            raw?.let { scaleCapacity(it, scale) }
        }

    /**
     * 解析最终使用的额定容量：
     * 手动 > 系统设计（按判定结果校正）> 学习估算 > 系统未提供（暂按 5000 估算）。
     */
    fun resolveRatedCapacity(
        correction: BatteryCorrection,
        designRawMah: Int?,
        ratedDesignMah: Int?,
        learnedCapacityMah: Int?,
        manualMah: Int
    ): RatedCapacityResolution {
        val manual = manualMah.takeIf { it in MIN_CAPACITY_MAH..MAX_CAPACITY_MAH }
        val learned = learnedCapacityMah?.takeIf { it in MIN_CAPACITY_MAH..MAX_CAPACITY_MAH }
        return when {
            manual != null -> RatedCapacityResolution(
                cells = correction.cells,
                ratedMah = manual,
                source = CapacitySource.MANUAL,
                designRawMah = designRawMah,
                designMah = ratedDesignMah
            )
            ratedDesignMah != null -> RatedCapacityResolution(
                cells = correction.cells,
                ratedMah = ratedDesignMah,
                source = if (correction.capacityScale == 2) {
                    CapacitySource.SINGLE_CELL
                } else {
                    CapacitySource.SYSTEM
                },
                designRawMah = designRawMah,
                designMah = ratedDesignMah
            )
            learned != null -> RatedCapacityResolution(
                cells = correction.cells,
                ratedMah = learned,
                source = CapacitySource.LEARNED,
                designRawMah = designRawMah,
                designMah = ratedDesignMah
            )
            else -> RatedCapacityResolution(
                cells = correction.cells,
                ratedMah = DEFAULT_RATED_MAH,
                source = CapacitySource.NONE,
                designRawMah = designRawMah,
                designMah = ratedDesignMah
            )
        }
    }

    /** 单条学习记录推算出的整包满充容量；不依赖额定容量，可直接作为兜底。 */
    fun capacityForSample(
        estimator: String,
        startMah: Int,
        startPct: Int,
        endMah: Int,
        endPct: Int
    ): Int? = when (estimator) {
        ESTIMATOR_FULL -> endMah.takeIf { it > 0 }
        ESTIMATOR_DELTA -> estimateCapacityByDelta(startMah, endMah, startPct, endPct)
        else -> null
    }

    data class CapacitySample(val capacityMah: Int, val weight: Int)

    fun weightedCapacity(samples: List<CapacitySample>): Int? {
        val valid = samples.filter { it.weight > 0 && it.capacityMah > 0 }
        if (valid.isEmpty()) return null
        val totalWeight = valid.sumOf { it.weight }
        if (totalWeight <= 0) return null
        val value = valid.sumOf { (it.capacityMah.toLong() * it.weight) }
        return (value.toDouble() / totalWeight).roundToInt()
            .coerceIn(MIN_CAPACITY_MAH, MAX_CAPACITY_MAH)
    }

    /** 官方 EXTRA_PLUGGED 语义，与系统「电池状态」里的充电方式一致。 */
    fun connectorLabel(pluggedType: Int): String = when {
        pluggedType and PLUGGED_DOCK != 0 -> "底座充电"
        pluggedType and PLUGGED_WIRELESS != 0 -> "无线充电器"
        pluggedType and PLUGGED_AC != 0 -> "AC充电器"
        pluggedType and PLUGGED_USB != 0 -> "USB充电器"
        else -> "未充电"
    }

    /**
     * 充电协议推断。内核字段命名不统一，只能按关键字匹配；
     * 读不到任何有效字段时如实显示「协议未知」。
     */
    fun protocolLabel(
        usbType: String?,
        realType: String?,
        typecMode: String?,
        chargeType: String?
    ): String {
        val tokens = listOfNotNull(usbType, realType, typecMode)
            .joinToString(" ")
            .uppercase(Locale.ROOT)
        return when {
            tokens.contains("UFCS") -> "UFCS"
            tokens.contains("PPS") -> "PD PPS"
            tokens.contains("PD") -> "PD"
            tokens.contains("HVDCP") || tokens.contains("QC") ||
                tokens.contains("QUICK") -> "QC"
            tokens.contains("CDP") || tokens.contains("DCP") ||
                tokens.contains("SDP") -> "BC 1.2"
            chargeType?.uppercase(Locale.ROOT)?.contains("FAST") == true -> "快充"
            else -> "协议未知"
        }
    }

    /** 实时数据卡上的「充电方式」文案：官方类型 + 能读到的协议。 */
    fun describeCharging(reading: BatteryReading): String {
        if (!reading.plugged && !reading.charging) return "未充电"
        val connector = connectorLabel(reading.pluggedType)
        if (connector == "未充电") return "未充电"
        val protocol = protocolLabel(
            usbType = reading.usbType,
            realType = reading.usbRealType,
            typecMode = reading.typecMode,
            chargeType = reading.chargeType
        )
        return if (protocol == "协议未知") connector else "$connector · $protocol"
    }

    /** 电池技术中文映射；未知或未提供时如实说明。 */
    fun describeTechnology(raw: String?): String {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty() || value.equals("Unknown", ignoreCase = true)) {
            return "系统未提供"
        }
        val upper = value.uppercase(Locale.ROOT)
        return when {
            upper.contains("LIFEPO4") || upper.contains("LFP") -> "磷酸铁锂（LiFePO4）"
            upper.contains("POLY") -> "锂聚合物（Li-poly）"
            upper.contains("ION") -> "锂离子（Li-ion）"
            upper.contains("NIMH") -> "镍氢（NiMH）"
            upper.contains("NICD") -> "镍镉（NiCd）"
            else -> value
        }
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
