package com.capsulepal.app.domain

import com.capsulepal.app.data.model.Task
import com.capsulepal.app.data.model.TaskStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class TaskStepScheduleTest {

    private fun task(durationMinutes: Int, steps: List<TaskStep>) = Task(
        id = 1L,
        title = "写周报",
        date = LocalDate.of(2026, 3, 2),
        startTime = LocalTime.of(10, 0),
        durationMinutes = durationMinutes,
        steps = steps
    )

    private fun minutes(task: Task, offsetMillis: Long): Long =
        (offsetMillis - task.startMillis) / 60_000L

    @Test
    fun `steps shorter than the task leave a tail`() {
        val task = task(
            durationMinutes = 30,
            steps = listOf(
                TaskStep(order = 0, title = "列提纲", durationMinutes = 10),
                TaskStep(order = 1, title = "写正文", durationMinutes = 10)
            )
        )

        val progress = TaskStepSchedule.progress(task, task.startMillis + 25 * 60_000L)

        assertNull(progress.current)
        assertTrue(progress.tail)
        assertEquals(2, progress.completedCount)
    }

    @Test
    fun `untimed steps share the remaining minutes`() {
        val task = task(
            durationMinutes = 30,
            steps = listOf(
                TaskStep(order = 0, title = "列提纲", durationMinutes = 10),
                TaskStep(order = 1, title = "写正文", durationMinutes = 0)
            )
        )

        val spans = TaskStepSchedule.spans(task)
        assertEquals(10L, minutes(task, spans[0].endMillis))
        assertEquals(30L, minutes(task, spans[1].endMillis))

        val progress = TaskStepSchedule.progress(task, task.startMillis + 20 * 60_000L)
        assertEquals("写正文", progress.current?.step?.title)
        assertFalse(progress.tail)
    }

    @Test
    fun `all untimed steps split the task evenly`() {
        val task = task(
            durationMinutes = 30,
            steps = listOf(
                TaskStep(order = 0, title = "一", durationMinutes = 0),
                TaskStep(order = 1, title = "二", durationMinutes = 0)
            )
        )

        val spans = TaskStepSchedule.spans(task)
        assertEquals(15L, minutes(task, spans[0].endMillis))
        assertEquals(30L, minutes(task, spans[1].endMillis))
    }

    @Test
    fun `legacy steps longer than the task are scaled down`() {
        val task = task(
            durationMinutes = 30,
            steps = listOf(TaskStep(order = 0, title = "超长", durationMinutes = 40))
        )

        val spans = TaskStepSchedule.spans(task)
        assertEquals(30L, minutes(task, spans.single().endMillis))
        assertFalse(TaskStepSchedule.progress(task, task.startMillis + 10 * 60_000L).tail)
    }

    @Test
    fun `next boundary points at the current step end`() {
        val task = task(
            durationMinutes = 30,
            steps = listOf(
                TaskStep(order = 0, title = "一", durationMinutes = 10),
                TaskStep(order = 1, title = "二", durationMinutes = 20)
            )
        )

        val progress = TaskStepSchedule.progress(task, task.startMillis + 2 * 60_000L)
        assertEquals(task.startMillis + 10 * 60_000L, progress.nextBoundaryMillis)
    }
}
