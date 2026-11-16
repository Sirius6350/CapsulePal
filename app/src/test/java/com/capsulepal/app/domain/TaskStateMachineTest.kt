package com.capsulepal.app.domain

import com.capsulepal.app.data.model.TaskPhase
import com.capsulepal.app.data.model.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskStateMachineTest {

    @Test
    fun `idle can enter prepare window`() {
        assertEquals(
            TaskStatus.PREPARE_WINDOW,
            TaskStateMachine.transition(TaskStatus.IDLE, TaskEvent.ENTER_PREPARE)
        )
    }

    @Test
    fun `prepare moves to in progress`() {
        assertEquals(
            TaskStatus.IN_PROGRESS,
            TaskStateMachine.transition(TaskStatus.PREPARE_WINDOW, TaskEvent.START)
        )
    }

    @Test
    fun `in progress can complete or skip`() {
        assertEquals(
            TaskStatus.COMPLETED,
            TaskStateMachine.transition(TaskStatus.IN_PROGRESS, TaskEvent.COMPLETE)
        )
        assertEquals(
            TaskStatus.SKIPPED,
            TaskStateMachine.transition(TaskStatus.IN_PROGRESS, TaskEvent.SKIP)
        )
    }

    @Test
    fun `completed is terminal except reset`() {
        assertNull(TaskStateMachine.transition(TaskStatus.COMPLETED, TaskEvent.START))
        assertNull(TaskStateMachine.transition(TaskStatus.COMPLETED, TaskEvent.SKIP))
        assertEquals(
            TaskStatus.IDLE,
            TaskStateMachine.transition(TaskStatus.COMPLETED, TaskEvent.RESET)
        )
    }

    @Test
    fun `skipped can be started again`() {
        assertEquals(
            TaskStatus.IN_PROGRESS,
            TaskStateMachine.transition(TaskStatus.SKIPPED, TaskEvent.START)
        )
        assertNull(TaskStateMachine.transition(TaskStatus.SKIPPED, TaskEvent.COMPLETE))
    }

    @Test
    fun `start cannot go back to prepare`() {
        assertNull(TaskStateMachine.transition(TaskStatus.IN_PROGRESS, TaskEvent.ENTER_PREPARE))
    }

    @Test
    fun `phase maps to status`() {
        assertEquals(TaskStatus.PREPARE_WINDOW, TaskStateMachine.statusForPhase(TaskPhase.PREPARE))
        assertEquals(TaskStatus.IN_PROGRESS, TaskStateMachine.statusForPhase(TaskPhase.IN_PROGRESS))
    }
}
