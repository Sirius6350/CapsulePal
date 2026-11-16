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
}
