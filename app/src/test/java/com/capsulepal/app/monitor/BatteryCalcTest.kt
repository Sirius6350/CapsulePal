package com.capsulepal.app.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class BatteryCalcTest {

    @Test
    fun estimateFullCapacity_extrapolatesFromChargeCounter() {
        assertEquals(5000, BatteryCalc.estimateFullCapacityMah(2500, 50))
        assertEquals(4400, BatteryCalc.estimateFullCapacityMah(2200, 50))
        assertNull(BatteryCalc.estimateFullCapacityMah(0, 50))
        assertNull(BatteryCalc.estimateFullCapacityMah(2500, 0))
        assertNull(BatteryCalc.estimateFullCapacityMah(null, 50))
    }

    @Test
    fun healthPercent_isFullCapacityOverRated() {
        assertEquals(90f, BatteryCalc.healthPercent(4500, 5000)!!, 0.001f)
        assertEquals(88f, BatteryCalc.healthPercentFrom(2200, 50, 5000)!!, 0.001f)
        assertNull(BatteryCalc.healthPercent(null, 5000))
        assertNull(BatteryCalc.healthPercent(4500, 0))
    }

    @Test
    fun chargePower_usesVoltageTimesCurrent() {
        assertEquals(22f, BatteryCalc.chargePowerW(4400, 5000f)!!, 0.001f)
        assertEquals(22f, BatteryCalc.chargePowerW(4400, -5000f)!!, 0.001f)
        assertNull(BatteryCalc.chargePowerW(null, 5000f))
        assertNull(BatteryCalc.chargePowerW(4400, null))
    }

    @Test
    fun learningWeight_followsAmplitudeBuckets() {
        assertEquals(30, BatteryCalc.learningWeight(50, fullQuiet = false))
        assertEquals(30, BatteryCalc.learningWeight(80, fullQuiet = false))
        assertEquals(20, BatteryCalc.learningWeight(30, fullQuiet = false))
        assertEquals(20, BatteryCalc.learningWeight(49, fullQuiet = false))
        assertEquals(10, BatteryCalc.learningWeight(20, fullQuiet = false))
        assertEquals(10, BatteryCalc.learningWeight(29, fullQuiet = false))
        assertNull(BatteryCalc.learningWeight(19, fullQuiet = false))
        assertNull(BatteryCalc.learningWeight(0, fullQuiet = false))
    }

    @Test
    fun learningWeight_reachedFullOutranksAmplitudeButNotQuiet() {
        assertEquals(40, BatteryCalc.learningWeight(10, fullQuiet = false, reachedFull = true))
        assertEquals(40, BatteryCalc.learningWeight(80, fullQuiet = false, reachedFull = true))
        assertEquals(50, BatteryCalc.learningWeight(10, fullQuiet = true, reachedFull = true))
    }

    @Test
    fun learningWeight_fullQuietWinsEvenWithTinyAmplitude() {
        assertEquals(50, BatteryCalc.learningWeight(0, fullQuiet = true))
        assertEquals(50, BatteryCalc.learningWeight(5, fullQuiet = true))
        assertEquals(50, BatteryCalc.learningWeight(80, fullQuiet = true))
    }

    @Test
    fun estimateCapacityByDelta_ignoresAbsoluteOffset() {
        assertEquals(5000, BatteryCalc.estimateCapacityByDelta(2000, 4000, 40, 80))
        // 电荷计绝对零点不可靠，整体偏移不影响增量结果。
        assertEquals(5000, BatteryCalc.estimateCapacityByDelta(2100, 4100, 40, 80))
        assertNull(BatteryCalc.estimateCapacityByDelta(2000, 4000, 80, 80))
        assertNull(BatteryCalc.estimateCapacityByDelta(4000, 2000, 80, 40))
        assertNull(BatteryCalc.estimateCapacityByDelta(0, 4000, 40, 90))
        assertNull(BatteryCalc.estimateCapacityByDelta(null, 4000, 40, 90))
        assertNull(BatteryCalc.estimateCapacityByDelta(2000, 4000, null, 90))
    }

    @Test
    fun healthPercentForSample_recomputesFromRawValues() {
        // FULL：终点电荷直接对额定容量。
        assertEquals(
            92f,
            BatteryCalc.healthPercentForSample("FULL", 2000, 40, 4600, 100, 5000)!!,
            0.001f
        )
        // DELTA：增量推算满充容量后再对额定容量。
        assertEquals(
            100f,
            BatteryCalc.healthPercentForSample("DELTA", 2000, 40, 4000, 80, 5000)!!,
            0.001f
        )
        // 改额定容量后按当前值重算，历史样本不会被污染。
        assertEquals(
            105.26f,
            BatteryCalc.healthPercentForSample("DELTA", 2000, 40, 4000, 80, 4750)!!,
            0.01f
        )
        assertNull(BatteryCalc.healthPercentForSample("LEGACY", 2000, 40, 4000, 80, 5000))
    }

    @Test
    fun weightedHealth_averagesByWeight() {
        val value = BatteryCalc.weightedHealth(
            listOf(
                BatteryCalc.HealthSample(90f, 50),
                BatteryCalc.HealthSample(80f, 50)
            )
        )!!
        assertEquals(85f, value, 0.001f)

        val mixed = BatteryCalc.weightedHealth(
            listOf(
                BatteryCalc.HealthSample(90f, 30),
                BatteryCalc.HealthSample(80f, 10)
            )
        )!!
        assertEquals(87.5f, mixed, 0.001f)
        assertNull(BatteryCalc.weightedHealth(emptyList()))
        assertNull(BatteryCalc.weightedHealth(listOf(BatteryCalc.HealthSample(90f, 0))))
    }

    @Test
    fun fullQuietCandidate_requiresFullLevelAndLowCurrent() {
        assertTrue(
            BatteryCalc.isFullQuietCandidate(
                BatteryReading(levelPct = 100, statusFull = true, currentMa = 20f)
            )
        )
        assertFalse(
            BatteryCalc.isFullQuietCandidate(
                BatteryReading(levelPct = 100, statusFull = true, currentMa = 60f)
            )
        )
        assertFalse(
            BatteryCalc.isFullQuietCandidate(
                BatteryReading(levelPct = 99, statusFull = true, currentMa = 10f)
            )
        )
        assertFalse(
            BatteryCalc.isFullQuietCandidate(
                BatteryReading(levelPct = 100, statusFull = false, currentMa = 10f)
            )
        )
        assertFalse(
            BatteryCalc.isFullQuietCandidate(
                BatteryReading(levelPct = 100, statusFull = true, currentMa = null)
            )
        )
    }

    @Test
    fun sessionView_computesLiveDurationAndSpeed() {
        val view = ChargeSessionView(
            id = 1L,
            startTs = 1_000_000L,
            endTs = 0L,
            startPct = 40,
            endPct = 45
        )
        val now = 1_000_000L + 5 * 60_000L
        assertEquals(5, view.durationMinutes(now))
        assertEquals(1f, view.speedPctPerMin(now)!!, 0.001f)
    }

    @Test
    fun sessionView_closedSessionUsesEndTimestamp() {
        val view = ChargeSessionView(
            id = 2L,
            startTs = 0L,
            endTs = 90_000L,
            startPct = 40,
            endPct = 41
        )
        assertFalse(view.live)
        assertEquals(1, view.durationMinutes(9_999_999L))
        assertEquals(2f / 3f, view.speedPctPerMin(9_999_999L)!!, 0.001f)
    }

    @Test
    fun formatDuration_readsNaturally() {
        assertEquals("不足 1 分", BatteryCalc.formatDuration(0))
        assertEquals("45 分", BatteryCalc.formatDuration(45))
        assertEquals("1 小时 23 分", BatteryCalc.formatDuration(83))
    }

    @Test
    fun formatSpeedAndPower() {
        assertEquals("0.82%/分", BatteryCalc.formatSpeed(0.82f))
        assertEquals("22.0 W", BatteryCalc.formatPower(22f))
        assertEquals("22.0W", BatteryCalc.formatPowerCompact(22f))
        assertEquals("8.5W", BatteryCalc.formatPowerCompact(8.5f))
        assertEquals("36.2 ℃", BatteryCalc.formatTemp(36.2f))
        assertEquals("4.40 V", BatteryCalc.formatVoltage(4400))
        assertEquals("512 mA", BatteryCalc.formatCurrent(-512f))
        assertEquals("92%", BatteryCalc.formatHealth(91.6f))
    }

    @Test
    fun todayStart_usesLocalMidnight() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = LocalDateTime.of(2026, 11, 11, 15, 30)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        val expected = LocalDate.of(2026, 11, 11)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
        assertEquals(expected, BatteryCalc.todayStart(now, zone))
    }

    @Test
    fun resolveCellCount_followsSettingThenEvidence() {
        assertEquals(1, BatteryCalc.resolveCellCount(0, 5000, null, 4000))
        assertEquals(2, BatteryCalc.resolveCellCount(0, 2500, null, 3850))
        assertEquals(1, BatteryCalc.resolveCellCount(0, null, null, null))
        // 电压 ≥ 6V 说明是串联整包。
        assertEquals(2, BatteryCalc.resolveCellCount(0, 7300, null, 7560))
        // 能量等效容量约为电荷容量 2 倍，说明系统只报了单芯。
        assertEquals(2, BatteryCalc.resolveCellCount(0, 3650, 7300, 3780))
        // 手动指定优先于证据推断。
        assertEquals(1, BatteryCalc.resolveCellCount(1, 2500, null, 3850))
        assertEquals(2, BatteryCalc.resolveCellCount(2, 6000, null, 4000))
    }

    @Test
    fun resolveCorrection_distinguishesSeriesAndParallel() {
        // 用户机型：6000 mAh 已是整包，4.0V，不放大。
        val parallel = BatteryCalc.resolveCorrection(2, 6000, null, 4000)
        assertEquals(2, parallel.cells)
        assertEquals(1, parallel.capacityScale)
        assertEquals(1, parallel.voltageScale)

        // 串联单芯上报：3650 mAh @3.78V，能量换算 7300 → 容量和电压都 ×2。
        val seriesCell = BatteryCalc.resolveCorrection(2, 3650, 7300, 3780)
        assertEquals(2, seriesCell.capacityScale)
        assertEquals(2, seriesCell.voltageScale)

        // 串联整包上报：3650 mAh @7.56V，能量换算 7300 → 只把容量按两芯相加。
        val seriesPack = BatteryCalc.resolveCorrection(2, 3650, 7300, 7560)
        assertEquals(2, seriesPack.capacityScale)
        assertEquals(1, seriesPack.voltageScale)
    }

    @Test
    fun applyCorrection_scalesCapacityAndVoltageIndependently() {
        val reading = BatteryReading(
            designCapacityMah = 3650,
            fullCapacityMah = 3400,
            chargeCounterMah = 1700,
            voltageMv = 3780,
            energyDesignCapacityMah = 7300,
            energyFullCapacityMah = 6800
        )
        val corrected = BatteryCalc.applyCorrection(
            reading,
            BatteryCalc.resolveCorrection(2, 3650, 7300, 3780)
        )
        // 有能量换算值时优先用它，比 charge ×2 更接近厂商标称值。
        assertEquals(7300, corrected.designCapacityMah)
        assertEquals(6800, corrected.fullCapacityMah)
        assertEquals(3400, corrected.chargeCounterMah)
        assertEquals(7560, corrected.voltageMv)

        val parallel = BatteryReading(designCapacityMah = 6000, voltageMv = 4000)
        assertEquals(
            parallel,
            BatteryCalc.applyCorrection(
                parallel,
                BatteryCalc.resolveCorrection(2, 6000, null, 4000)
            )
        )
    }

    @Test
    fun resolveRatedCapacity_priorityAndSources() {
        val dual = BatteryCalc.resolveCorrection(0, 2500, null, 3850)
        val manual = BatteryCalc.resolveRatedCapacity(dual, 2500, 5000, 4800, 6000)
        assertEquals(2, manual.cells)
        assertEquals(6000, manual.ratedMah)
        assertEquals(CapacitySource.MANUAL, manual.source)

        val autoDual = BatteryCalc.resolveRatedCapacity(dual, 2500, 5000, null, 0)
        assertEquals(5000, autoDual.ratedMah)
        assertEquals(CapacitySource.SINGLE_CELL, autoDual.source)
        assertEquals(2500, autoDual.designRawMah)
        assertEquals(5000, autoDual.designMah)

        val single = BatteryCalc.resolveCorrection(1, 2500, null, 3850)
        val system = BatteryCalc.resolveRatedCapacity(single, 2500, 2500, null, 0)
        assertEquals(2500, system.ratedMah)
        assertEquals(CapacitySource.SYSTEM, system.source)

        val oneCell = BatteryCalc.resolveCorrection(0, null, null, null)
        val learned = BatteryCalc.resolveRatedCapacity(oneCell, null, null, 4500, 0)
        assertEquals(4500, learned.ratedMah)
        assertEquals(CapacitySource.LEARNED, learned.source)

        val none = BatteryCalc.resolveRatedCapacity(oneCell, null, null, null, 0)
        assertEquals(CapacitySource.NONE, none.source)
        assertEquals(BatteryCalc.DEFAULT_RATED_MAH, none.ratedMah)
    }

    @Test
    fun chargePower_isClampedToGuardrail() {
        assertEquals(BatteryCalc.MAX_POWER_W, BatteryCalc.chargePowerW(9000, 60000f)!!, 0.001f)
        assertNull(BatteryCalc.chargePowerW(4400, 0f))
    }

    @Test
    fun capacityForSample_andWeightedCapacity() {
        assertEquals(4600, BatteryCalc.capacityForSample("FULL", 0, 0, 4600, 100))
        assertEquals(5000, BatteryCalc.capacityForSample("DELTA", 2000, 40, 4000, 80))
        assertNull(BatteryCalc.capacityForSample("LEGACY", 2000, 40, 4000, 80))

        val learned = BatteryCalc.weightedCapacity(
            listOf(
                BatteryCalc.CapacitySample(5000, 30),
                BatteryCalc.CapacitySample(4800, 10)
            )
        )
        assertEquals(4950, learned)
        assertNull(BatteryCalc.weightedCapacity(emptyList()))
        assertNull(BatteryCalc.weightedCapacity(listOf(BatteryCalc.CapacitySample(5000, 0))))
    }

    @Test
    fun connectorAndProtocol_labels() {
        assertEquals("未充电", BatteryCalc.connectorLabel(0))
        assertEquals("AC充电器", BatteryCalc.connectorLabel(1))
        assertEquals("USB充电器", BatteryCalc.connectorLabel(2))
        assertEquals("无线充电器", BatteryCalc.connectorLabel(4))
        assertEquals("底座充电", BatteryCalc.connectorLabel(8))

        assertEquals("PD PPS", BatteryCalc.protocolLabel("USB_PD_PPS", null, null, null))
        assertEquals("PD", BatteryCalc.protocolLabel("USB_PD", null, null, null))
        assertEquals("QC", BatteryCalc.protocolLabel("USB_HVDCP_3", null, null, null))
        assertEquals("UFCS", BatteryCalc.protocolLabel("UFCS", null, null, null))
        assertEquals("BC 1.2", BatteryCalc.protocolLabel("USB_DCP", null, null, null))
        assertEquals("快充", BatteryCalc.protocolLabel(null, null, null, "Fast"))
        assertEquals("协议未知", BatteryCalc.protocolLabel(null, null, null, "Standard"))
    }

    @Test
    fun describeCharging_usesOfficialConnectorAndProtocol() {
        val reading = BatteryReading(
            plugged = true,
            charging = true,
            pluggedType = 1,
            usbType = "USB_PD"
        )
        assertEquals("AC充电器 · PD", BatteryCalc.describeCharging(reading))
        // 协议读不到时只显示官方类型，不显示「协议未知」。
        assertEquals(
            "AC充电器",
            BatteryCalc.describeCharging(reading.copy(usbType = null))
        )
        assertEquals("未充电", BatteryCalc.describeCharging(BatteryReading()))
    }

    @Test
    fun technology_mapsKernelNames() {
        assertEquals("锂离子（Li-ion）", BatteryCalc.describeTechnology("Li-ion"))
        assertEquals("锂聚合物（Li-poly）", BatteryCalc.describeTechnology("Li-poly"))
        assertEquals("磷酸铁锂（LiFePO4）", BatteryCalc.describeTechnology("LiFePO4"))
        assertEquals("系统未提供", BatteryCalc.describeTechnology(null))
        assertEquals("系统未提供", BatteryCalc.describeTechnology("Unknown"))
    }
}
