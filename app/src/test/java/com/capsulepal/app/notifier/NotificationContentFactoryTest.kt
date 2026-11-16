package com.capsulepal.app.notifier

import com.capsulepal.app.data.model.ActiveTask
import com.capsulepal.app.data.model.Task
import com.capsulepal.app.data.model.TaskPhase
import com.capsulepal.app.data.model.TimelineState
import com.capsulepal.app.data.model.UpcomingTask
import com.capsulepal.app.monitor.BatteryReading
import com.capsulepal.app.monitor.ChargeSessionView
import com.capsulepal.app.monitor.MonitorState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class NotificationContentFactoryTest {

    private fun task(prepareMinutes: Int) = Task(
        id = 1L,
        title = "写周报",
        date = LocalDate.of(2026, 3, 2),
        startTime = LocalTime.of(10, 0),
        durationMinutes = 30,
        prepareMinutes = prepareMinutes
    )

    @Test
    fun `no island before the configured prepare time`() {
        val task = task(prepareMinutes = 10)
        val state = TimelineState(
            next = UpcomingTask(
                task = task,
                untilPrepareMillis = 11 * 60_000L,
                untilStartMillis = 21 * 60_000L
            )
        )

        assertNull(
            NotificationContentFactory.fromTimeline(state, task.startMillis - 21 * 60_000L)
        )
        assertNull(
            NotificationContentFactory.fromTimeline(state, task.startMillis - 11 * 60_000L)
        )
    }

    @Test
    fun `island appears exactly at the prepare window`() {
        val task = task(prepareMinutes = 10)
        val active = ActiveTask(
            task = task,
            phase = TaskPhase.PREPARE,
            windowStartMillis = task.prepareStartMillis,
            windowEndMillis = task.startMillis
        )
        val state = TimelineState(active = active)

        val content = NotificationContentFactory.fromTimeline(
            state,
            task.prepareStartMillis + 1_000L
        )

        assertEquals(NotificationPhase.PREPARE, content?.phase)
        assertEquals(task.id, content?.taskId)
        assertEquals("10:00 开始 · 还有 10 分钟", content?.text)
    }

    @Test
    fun `task without prepare window only appears when it starts`() {
        val task = task(prepareMinutes = 0)
        val beforeStart = TimelineState(
            next = UpcomingTask(
                task = task,
                untilPrepareMillis = 10 * 60_000L,
                untilStartMillis = 10 * 60_000L
            )
        )
        assertNull(
            NotificationContentFactory.fromTimeline(beforeStart, task.startMillis - 10 * 60_000L)
        )

        val active = ActiveTask(
            task = task,
            phase = TaskPhase.IN_PROGRESS,
            windowStartMillis = task.startMillis,
            windowEndMillis = task.endMillis
        )
        val content = NotificationContentFactory.fromTimeline(
            TimelineState(active = active),
            task.startMillis + 1_000L
        )
        assertEquals(NotificationPhase.IN_PROGRESS, content?.phase)
        assertEquals("进行中 · 已进行 1 分钟 · 还剩 30 分钟", content?.text)
    }

    private fun chargingState(
        now: Long,
        startTs: Long,
        startPct: Int,
        endPct: Int,
        charging: Boolean = true
    ) = MonitorState(
        reading = BatteryReading(
            tempC = 36.2f,
            levelPct = endPct,
            voltageMv = 4400,
            currentMa = 5000f,
            charging = charging,
            statusFull = false,
            plugged = true
        ),
        ratedCapacityMah = 5000,
        ratedIsAuto = true,
        systemDesignMah = 5000,
        fullCapacityMah = 4600,
        healthPredictionPct = 92f,
        learnedHealthPct = 91f,
        learnedSamples = 3,
        currentCharge = ChargeSessionView(
            id = 7L,
            startTs = startTs,
            endTs = 0L,
            startPct = startPct,
            endPct = endPct
        ),
        lastCharge = null,
        todayChargeCount = 1,
        updatedAt = now
    )

    @Test
    fun `charging island shows power duration speed and temperature`() {
        val start = 1_000_000L
        val now = start + 12 * 60_000L
        val content = NotificationContentFactory.chargingContent(
            chargingState(now, start, startPct = 40, endPct = 58),
            now
        )

        assertEquals(IslandKind.CHARGING, content.kind)
        assertEquals("正在充电", content.title)
        assertEquals("22.0W", content.shortText)
        assertEquals(7L, content.sessionId)
        assertEquals("已充 12 分 · 1.50%/分", content.text)
        assertEquals("电池温度 36.2 ℃", content.note)
        assertEquals(58, content.progress)
        assertEquals(null, content.taskId)
    }

    @Test
    fun `charging island right after plugging in has no speed yet`() {
        val start = 2_000_000L
        val content = NotificationContentFactory.chargingContent(
            chargingState(start, start, startPct = 40, endPct = 40),
            start
        )

        assertEquals("刚插上电源", content.text)
        assertEquals("22.0W", content.shortText)
    }

    @Test
    fun `charging paused at the limit still shows the charging island`() {
        val start = 2_000_000L
        val content = NotificationContentFactory.chargingContent(
            chargingState(start, start, startPct = 80, endPct = 80, charging = false),
            start
        )

        assertEquals("已接电源", content.title)
        assertEquals(IslandKind.CHARGING, content.kind)
        assertEquals(80, content.progress)
    }

    @Test
    fun `task island while charging shows power on the right and charging line in the card`() {
        val task = task(prepareMinutes = 10)
        val active = ActiveTask(
            task = task,
            phase = TaskPhase.PREPARE,
            windowStartMillis = task.prepareStartMillis,
            windowEndMillis = task.startMillis
        )
        val now = task.prepareStartMillis + 60_000L
        val start = now - 12 * 60_000L

        val content = NotificationContentFactory.fromTimeline(
            TimelineState(active = active),
            now,
            chargingState(now, start, startPct = 40, endPct = 58)
        )

        assertEquals("22.0W", content?.shortText)
        assertEquals(task.id, content?.taskId)
        assertEquals("正在充电 · 已充 12 分 · 1.50%/分 · 36.2 ℃", content?.note)
    }
}
