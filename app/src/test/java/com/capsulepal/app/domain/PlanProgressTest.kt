package com.capsulepal.app.domain

import com.capsulepal.app.data.model.RepeatRule
import com.capsulepal.app.data.model.Task
import com.capsulepal.app.data.model.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class PlanProgressTest {

    private fun task(status: TaskStatus) = Task(
        title = "t",
        date = LocalDate.of(2026, 11, 15),
        startTime = LocalTime.of(9, 0),
        status = status
    )

    @Test
    fun `skipped tasks stay in the denominator`() {
        val progress = planProgress(
            listOf(
                task(TaskStatus.COMPLETED),
                task(TaskStatus.SKIPPED),
                task(TaskStatus.IDLE)
            )
        )
        assertEquals(3, progress.total)
        assertEquals(1, progress.completed)
        assertEquals(1, progress.skipped)
        assertEquals(1, progress.open)
        assertEquals(1f / 3f, progress.fraction, 0.0001f)
        assertEquals(33, progress.percent)
    }

    @Test
    fun `completed tasks fill the progress`() {
        val progress = planProgress(
            listOf(task(TaskStatus.COMPLETED), task(TaskStatus.COMPLETED))
        )
        assertEquals(1f, progress.fraction, 0.0001f)
        assertEquals(100, progress.percent)
    }

    @Test
    fun `empty plan has zero progress`() {
        val progress = planProgress(emptyList())
        assertEquals(0, progress.total)
        assertEquals(0f, progress.fraction, 0.0001f)
    }

    private fun done(date: LocalDate) = Task(
        title = "t",
        date = date,
        startTime = LocalTime.of(9, 0),
        status = TaskStatus.COMPLETED
    )

    @Test
    fun `repeat plan counts completions, this week and streak`() {
        val today = LocalDate.of(2026, 11, 15)
        val stats = repeatPlanStats(
            listOf(
                done(today),
                done(today.minusDays(1)),
                Task(title = "t", date = today.minusDays(2), startTime = LocalTime.of(9, 0)),
                done(today.minusDays(8))
            ),
            today
        )
        assertEquals(3, stats.completed)
        assertEquals(2, stats.thisWeek)
        assertEquals(2, stats.streak)
    }

    @Test
    fun `streak still counts when today is not checked yet`() {
        val today = LocalDate.of(2026, 11, 15)
        val stats = repeatPlanStats(
            listOf(done(today.minusDays(1)), done(today.minusDays(2)), done(today.minusDays(4))),
            today
        )
        assertEquals(3, stats.completed)
        assertEquals(2, stats.streak)
    }

    private fun seriesTask(
        date: LocalDate,
        status: TaskStatus = TaskStatus.IDLE
    ) = Task(
        title = "t",
        date = date,
        startTime = LocalTime.of(9, 0),
        status = status,
        repeat = RepeatRule.DAILY,
        seriesId = 100L,
        planId = 7L
    )

    @Test
    fun `denominator follows the tasks that actually remain`() {
        val tasks = (1..5).map { day ->
            seriesTask(LocalDate.of(2026, 11, day), if (day <= 2) TaskStatus.COMPLETED else TaskStatus.IDLE)
        }
        assertEquals(5, planProgress(tasks).total)

        // 删掉两条（例如删除计划里的重复待办）后，分母必须立即变成 3。
        val afterDelete = tasks.filterNot { it.date.dayOfMonth == 4 || it.date.dayOfMonth == 5 }
        val progress = planProgress(afterDelete)
        assertEquals(3, progress.total)
        assertEquals(2, progress.completed)
        assertEquals(67, progress.percent)
    }

    @Test
    fun `period border does not add future instances back to the denominator`() {
        // 计划区间还剩很多天，但计划里只剩这些实例，分母不应再按重复规则推算。
        val tasks = listOf(
            seriesTask(LocalDate.of(2026, 11, 1), TaskStatus.COMPLETED),
            seriesTask(LocalDate.of(2026, 11, 2), TaskStatus.SKIPPED)
        )
        val progress = planProgress(tasks)
        assertEquals(2, progress.total)
        assertEquals(50, progress.percent)
    }
}
