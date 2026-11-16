package com.capsulepal.app.domain

import com.capsulepal.app.data.model.TaskStatus

enum class TaskEvent { ENTER_PREPARE, START, COMPLETE, SKIP, RESET }

object TaskStateMachine {

    fun transition(current: TaskStatus, event: TaskEvent): TaskStatus? = when (current) {
        TaskStatus.IDLE -> when (event) {
            TaskEvent.ENTER_PREPARE -> TaskStatus.PREPARE_WINDOW
            TaskEvent.START -> TaskStatus.IN_PROGRESS
            TaskEvent.COMPLETE -> TaskStatus.COMPLETED
            TaskEvent.SKIP -> TaskStatus.SKIPPED
            TaskEvent.RESET -> TaskStatus.IDLE
        }
        TaskStatus.PREPARE_WINDOW -> when (event) {
            TaskEvent.START -> TaskStatus.IN_PROGRESS
            TaskEvent.COMPLETE -> TaskStatus.COMPLETED
            TaskEvent.SKIP -> TaskStatus.SKIPPED
            TaskEvent.ENTER_PREPARE -> TaskStatus.PREPARE_WINDOW
            TaskEvent.RESET -> TaskStatus.IDLE
        }
        TaskStatus.IN_PROGRESS -> when (event) {
            TaskEvent.COMPLETE -> TaskStatus.COMPLETED
            TaskEvent.SKIP -> TaskStatus.SKIPPED
            TaskEvent.RESET -> TaskStatus.IDLE
            else -> null
        }
        TaskStatus.COMPLETED -> when (event) {
            TaskEvent.RESET -> TaskStatus.IDLE
            else -> null
        }
        TaskStatus.SKIPPED -> when (event) {
            TaskEvent.RESET -> TaskStatus.IDLE
            TaskEvent.START -> TaskStatus.IN_PROGRESS
            else -> null
        }
    }

    fun statusForPhase(phase: com.capsulepal.app.data.model.TaskPhase): TaskStatus = when (phase) {
        com.capsulepal.app.data.model.TaskPhase.PREPARE -> TaskStatus.PREPARE_WINDOW
        com.capsulepal.app.data.model.TaskPhase.IN_PROGRESS -> TaskStatus.IN_PROGRESS
    }
}
