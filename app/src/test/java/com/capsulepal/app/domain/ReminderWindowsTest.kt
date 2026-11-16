package com.capsulepal.app.domain

import com.capsulepal.app.data.model.Task
import com.capsulepal.app.data.model.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class ReminderWindowsTest {

    private val date = LocalDate.of(2026, 3, 2)
    private val now = millis(0, 8, 0)
    private val endOfToday = millis(1, 0, 0)
    private val horizon = ReminderWindows.PREACTIVE_HORIZON_DAYS * 24 * 60 * 60_000L

    private fun millis(dayOffset: Long, hour: Int, minute: Int = 0): Long =
        date.plusDays(dayOffset).atTime(LocalTime.of(hour, minute))
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    private fun task(
        id: Long,
        dayOffset: Long = 0,
        hour: Int = 10,
        minute: Int = 0,
        duration: Int = 60,
        status: TaskStatus = TaskStatus.IDLE
    ) = Task(
        id = id,
        title = "任务 $id",
        date = date.plusDays(dayOffset),
        startTime = LocalTime.of(hour, minute),
        durationMinutes = duration,
        prepareMinutes = 10,
        status = status
    )

    private fun classify(tasks: List<Task>) =
        ReminderWindows.classify(
            tasks = tasks,
            nowMillis = now,
            endOfTodayMillis = endOfToday,
            horizonMillis = horizon
        )

    @Test
    fun `splits active preactive and sleeping`() {
        val today = task(id = 1, hour = 10)
        val tomorrow = task(id = 2, dayOffset = 1, hour = 9)
        val faraway = task(id = 3, dayOffset = 20, hour = 9)

        val buckets = classify(listOf(today, tomorrow, faraway))

        assertEquals(listOf(1L), buckets.active.map { it.id })
        assertEquals(listOf(2L), buckets.preactive.map { it.id })
        assertTrue(buckets.active.none { it.id == 3L })
        assertTrue(buckets.preactive.none { it.id == 3L })
    }

    @Test
    fun `drops finished and closed tasks`() {
        val finished = task(id = 1, hour = 7, minute = 0, duration = 30)
        val completed = task(id = 2, hour = 11, status = TaskStatus.COMPLETED)
        val skipped = task(id = 3, hour = 12, status = TaskStatus.SKIPPED)
        val alive = task(id = 4, hour = 13)

        val buckets = classify(listOf(finished, completed, skipped, alive))

        assertEquals(listOf(4L), buckets.active.map { it.id })
        assertTrue(buckets.preactive.isEmpty())
    }

    @Test
    fun `returns tasks sorted by start time`() {
        val later = task(id = 1, dayOffset = 1, hour = 18)
        val earliest = task(id = 2, hour = 9)
        val middle = task(id = 3, dayOffset = 1, hour = 6)

        val buckets = classify(listOf(later, earliest, middle))

        assertEquals(listOf(2L), buckets.active.map { it.id })
        assertEquals(listOf(3L, 1L), buckets.preactive.map { it.id })
    }
}
