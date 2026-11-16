package com.capsulepal.app.domain

import com.capsulepal.app.data.model.Task
import com.capsulepal.app.data.model.TaskCategory
import com.capsulepal.app.data.model.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class SummaryCalculatorTest {

    private fun task(
        date: LocalDate,
        status: TaskStatus,
        category: TaskCategory = TaskCategory.ROUTINE,
        customCategory: String = ""
    ) = Task(
        title = "t",
        date = date,
        startTime = LocalTime.of(9, 0),
        status = status,
        category = category,
        customCategory = customCategory
    )

    @Test
    fun `day counts finished and open with rate over all tasks`() {
        val day = LocalDate.of(2026, 11, 15)
        val stats = SummaryCalculator.summarize(
            listOf(
                task(day, TaskStatus.COMPLETED),
                task(day, TaskStatus.SKIPPED),
                task(day, TaskStatus.IDLE)
            ),
            SummaryRange.DAY,
            day
        )
        assertEquals(3, stats.total)
        assertEquals(1, stats.completed)
        assertEquals(1, stats.skipped)
        assertEquals(1, stats.open)
        assertEquals(1f / 3f, stats.completionRate, 0.0001f)
        assertEquals(33, stats.percent)
    }

    @Test
    fun `week runs from monday to sunday`() {
        val (start, end) = SummaryCalculator.bounds(
            SummaryRange.WEEK,
            LocalDate.of(2026, 11, 15)
        )
        assertEquals(LocalDate.of(2026, 11, 9), start)
        assertEquals(LocalDate.of(2026, 11, 15), end)
    }

    @Test
    fun `month and year bounds cover full periods`() {
        val (monthStart, monthEnd) = SummaryCalculator.bounds(
            SummaryRange.MONTH,
            LocalDate.of(2026, 2, 10)
        )
        assertEquals(LocalDate.of(2026, 2, 1), monthStart)
        assertEquals(LocalDate.of(2026, 2, 28), monthEnd)

        val (yearStart, yearEnd) = SummaryCalculator.bounds(
            SummaryRange.YEAR,
            LocalDate.of(2026, 8, 3)
        )
        assertEquals(LocalDate.of(2026, 1, 1), yearStart)
        assertEquals(LocalDate.of(2026, 12, 31), yearEnd)
    }

    @Test
    fun `tasks outside the period are ignored`() {
        val day = LocalDate.of(2026, 11, 15)
        val stats = SummaryCalculator.summarize(
            listOf(
                task(day.minusDays(1), TaskStatus.COMPLETED),
                task(day, TaskStatus.COMPLETED),
                task(day.plusDays(1), TaskStatus.SKIPPED)
            ),
            SummaryRange.DAY,
            day
        )
        assertEquals(1, stats.total)
        assertEquals(1, stats.completed)
    }

    @Test
    fun `category distribution groups custom names and sorts by total`() {
        val day = LocalDate.of(2026, 11, 15)
        val stats = SummaryCalculator.summarize(
            listOf(
                task(day, TaskStatus.COMPLETED, TaskCategory.FOCUS),
                task(day, TaskStatus.IDLE, TaskCategory.FOCUS),
                task(day, TaskStatus.COMPLETED, TaskCategory.CUSTOM, "阅读"),
                task(day, TaskStatus.SKIPPED, TaskCategory.FOOD)
            ),
            SummaryRange.DAY,
            day
        )
        assertEquals(3, stats.categories.size)
        assertEquals(TaskCategory.FOCUS, stats.categories[0].category)
        assertEquals(2, stats.categories[0].total)
        assertEquals(1, stats.categories[0].completed)
        assertTrue(stats.categories.any { it.label == "阅读" && it.total == 1 })
    }

    @Test
    fun `empty period has no categories and zero rate`() {
        val stats = SummaryCalculator.summarize(
            emptyList(),
            SummaryRange.DAY,
            LocalDate.of(2026, 11, 15)
        )
        assertFalse(stats.hasData)
        assertEquals(0f, stats.completionRate, 0.0001f)
        assertTrue(stats.categories.isEmpty())
    }

    @Test
    fun `day trend covers the last seven days`() {
        val anchor = LocalDate.of(2026, 11, 15)
        val stats = SummaryCalculator.summarize(
            listOf(task(anchor, TaskStatus.COMPLETED), task(anchor, TaskStatus.SKIPPED)),
            SummaryRange.DAY,
            anchor
        )
        assertEquals(7, stats.trend.size)
        assertEquals("11/9", stats.trend.first().label)
        assertEquals("11/15", stats.trend.last().label)
        assertEquals(1, stats.trend.last().completed)
        assertEquals(2, stats.trend.last().total)
    }

    @Test
    fun `week trend has one bucket per weekday`() {
        val anchor = LocalDate.of(2026, 11, 15)
        val stats = SummaryCalculator.summarize(
            listOf(
                task(LocalDate.of(2026, 11, 9), TaskStatus.COMPLETED),
                task(LocalDate.of(2026, 11, 15), TaskStatus.COMPLETED)
            ),
            SummaryRange.WEEK,
            anchor
        )
        assertEquals(7, stats.trend.size)
        assertEquals("一", stats.trend.first().label)
        assertEquals(1, stats.trend.first().completed)
        assertEquals("日", stats.trend.last().label)
        assertEquals(1, stats.trend.last().completed)
    }

    @Test
    fun `month trend groups days into week chunks`() {
        val stats = SummaryCalculator.summarize(
            emptyList(),
            SummaryRange.MONTH,
            LocalDate.of(2026, 11, 15)
        )
        assertEquals(listOf("1-7", "8-14", "15-21", "22-28", "29-30"), stats.trend.map { it.label })
    }

    @Test
    fun `year trend has twelve months`() {
        val stats = SummaryCalculator.summarize(
            listOf(task(LocalDate.of(2026, 3, 8), TaskStatus.COMPLETED)),
            SummaryRange.YEAR,
            LocalDate.of(2026, 11, 15)
        )
        assertEquals(12, stats.trend.size)
        assertEquals("3", stats.trend[2].label)
        assertEquals(1, stats.trend[2].completed)
    }

    @Test
    fun `shift moves anchor by range unit`() {
        val anchor = LocalDate.of(2026, 11, 15)
        assertEquals(LocalDate.of(2026, 11, 16), SummaryCalculator.shift(SummaryRange.DAY, anchor, 1))
        assertEquals(LocalDate.of(2026, 11, 22), SummaryCalculator.shift(SummaryRange.WEEK, anchor, 1))
        assertEquals(LocalDate.of(2026, 12, 15), SummaryCalculator.shift(SummaryRange.MONTH, anchor, 1))
        assertEquals(LocalDate.of(2027, 11, 15), SummaryCalculator.shift(SummaryRange.YEAR, anchor, 1))
    }
}
