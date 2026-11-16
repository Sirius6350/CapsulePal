package com.capsulepal.app.domain

import com.capsulepal.app.data.model.Priority
import com.capsulepal.app.data.model.Task
import com.capsulepal.app.data.model.TaskPhase
import com.capsulepal.app.data.model.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class ActiveTaskResolverTest {

    private val date = LocalDate.of(2026, 3, 2)

    private fun millis(hour: Int, minute: Int = 0): Long =
        date.atTime(LocalTime.of(hour, minute))
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    private fun task(
        id: Long = 1L,
        hour: Int = 10,
        minute: Int = 0,
        duration: Int = 60,
        prepare: Int = 15,
        status: TaskStatus = TaskStatus.IDLE,
        priority: Priority = Priority.NORMAL
    ) = Task(
        id = id,
        title = "任务 $id",
        date = date,
        startTime = LocalTime.of(hour, minute),
        durationMinutes = duration,
        prepareMinutes = prepare,
        status = status,
        priority = priority
    )

    @Test
    fun `prepare window becomes active before start`() {
        val state = ActiveTaskResolver.resolve(listOf(task()), millis(9, 50))
        assertEquals(TaskPhase.PREPARE, state.active?.phase)
        assertEquals(10 * 60_000L, state.active?.remainingMillis(millis(9, 50)))
    }

    @Test
    fun `task is in progress at start`() {
        val state = ActiveTaskResolver.resolve(listOf(task()), millis(10, 0))
        assertEquals(TaskPhase.IN_PROGRESS, state.active?.phase)
        assertEquals(millis(10, 0), state.active?.windowStartMillis)
        assertEquals(millis(11, 0), state.active?.windowEndMillis)
    }

    @Test
    fun `progress reaches half at half time`() {
        val state = ActiveTaskResolver.resolve(listOf(task()), millis(10, 30))
        assertEquals(0.5f, state.active!!.progress(millis(10, 30)), 0.001f)
    }

    @Test
    fun `after end the next task becomes upcoming`() {
        val first = task(id = 1, hour = 10)
        val second = task(id = 2, hour = 12, prepare = 10)
        val state = ActiveTaskResolver.resolve(listOf(first, second), millis(11, 1))
        assertNull(state.active)
        assertEquals(2L, state.next?.task?.id)
        assertEquals(millis(11, 50) - millis(11, 1), state.next?.untilPrepareMillis)
    }

    @Test
    fun `completed task is ignored`() {
        val state = ActiveTaskResolver.resolve(
            listOf(task(status = TaskStatus.COMPLETED)),
            millis(10, 30)
        )
        assertNull(state.active)
    }

    @Test
    fun `manual in progress starts early`() {
        val state = ActiveTaskResolver.resolve(
            listOf(task(status = TaskStatus.IN_PROGRESS)),
            millis(9, 0)
        )
        assertEquals(TaskPhase.IN_PROGRESS, state.active?.phase)
        assertEquals(2 * 60 * 60_000L, state.active?.remainingMillis(millis(9, 0)))
    }

    @Test
    fun `manual prepare starts early`() {
        val state = ActiveTaskResolver.resolve(
            listOf(task(status = TaskStatus.PREPARE_WINDOW)),
            millis(9, 0)
        )
        assertEquals(TaskPhase.PREPARE, state.active?.phase)
        assertEquals(60 * 60_000L, state.active?.remainingMillis(millis(9, 0)))
    }

    @Test
    fun `higher priority wins when tasks overlap`() {
        val normal = task(id = 1, hour = 10, priority = Priority.NORMAL)
        val important = task(id = 2, hour = 10, priority = Priority.HIGH)
        val state = ActiveTaskResolver.resolve(listOf(normal, important), millis(10, 10))
        assertEquals(2L, state.active?.task?.id)
    }

    @Test
    fun `nextWithin only returns close tasks`() {
        val later = task(id = 3, hour = 14, prepare = 15)
        assertNull(ActiveTaskResolver.nextWithin(listOf(later), millis(10, 0), 60 * 60_000L))
        assertTrue(
            ActiveTaskResolver.nextWithin(listOf(later), millis(10, 0), 5 * 60 * 60_000L) != null
        )
    }

    @Test
    fun `daily rollup counts completed and skipped`() {
        val tasks = listOf(
            task(id = 1, status = TaskStatus.COMPLETED),
            task(id = 2, status = TaskStatus.SKIPPED),
            task(id = 3, status = TaskStatus.IDLE)
        )
        assertEquals(2f / 3f, DailyRollup.progress(tasks), 0.001f)
        assertEquals(0f, DailyRollup.progress(emptyList()), 0.001f)
    }
}
