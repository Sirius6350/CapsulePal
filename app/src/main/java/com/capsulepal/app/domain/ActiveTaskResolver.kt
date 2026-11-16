package com.capsulepal.app.domain

import com.capsulepal.app.data.model.ActiveTask
import com.capsulepal.app.data.model.Task
import com.capsulepal.app.data.model.TaskPhase
import com.capsulepal.app.data.model.TimelineState
import com.capsulepal.app.data.model.UpcomingTask

object ActiveTaskResolver {

    const val LIVE_COUNTDOWN_WINDOW_MILLIS = 60 * 60 * 1000L

    fun resolve(tasks: List<Task>, nowMillis: Long): TimelineState {
        val open = tasks.filter { it.isOpen }

        val candidates = open.mapNotNull { task ->
            when {
                task.status == com.capsulepal.app.data.model.TaskStatus.IN_PROGRESS &&
                    nowMillis < task.endMillis -> ActiveTask(
                    task = task,
                    phase = TaskPhase.IN_PROGRESS,
                    windowStartMillis = task.startMillis,
                    windowEndMillis = task.endMillis
                )
                task.status == com.capsulepal.app.data.model.TaskStatus.PREPARE_WINDOW &&
                    nowMillis < task.startMillis -> ActiveTask(
                    task = task,
                    phase = TaskPhase.PREPARE,
                    windowStartMillis = task.prepareStartMillis,
                    windowEndMillis = task.startMillis
                )
                nowMillis in task.prepareStartMillis until task.startMillis &&
                    task.prepareMinutes > 0 -> ActiveTask(
                    task = task,
                    phase = TaskPhase.PREPARE,
                    windowStartMillis = task.prepareStartMillis,
                    windowEndMillis = task.startMillis
                )
                nowMillis in task.startMillis until task.endMillis -> ActiveTask(
                    task = task,
                    phase = TaskPhase.IN_PROGRESS,
                    windowStartMillis = task.startMillis,
                    windowEndMillis = task.endMillis
                )
                else -> null
            }
        }

        val active = candidates.sortedWith(
            compareByDescending<ActiveTask> { it.phase == TaskPhase.IN_PROGRESS }
                .thenByDescending { it.task.priority.weight }
                .thenBy { it.task.startMillis }
        ).firstOrNull()

        val next = open
            .filter { it.prepareStartMillis > nowMillis }
            .minByOrNull { it.startMillis }
            ?.let {
                UpcomingTask(
                    task = it,
                    untilPrepareMillis = it.prepareStartMillis - nowMillis,
                    untilStartMillis = it.startMillis - nowMillis
                )
            }

        return TimelineState(active = active, next = next)
    }

    fun nextWithin(tasks: List<Task>, nowMillis: Long, windowMillis: Long): UpcomingTask? {
        val next = resolve(tasks, nowMillis).next ?: return null
        return next.takeIf { it.untilPrepareMillis <= windowMillis }
    }

    fun suggestedPrepareTask(tasks: List<Task>, nowMillis: Long, withinMillis: Long, category: String? = null): Task? {
        val candidates = tasks.filter { task ->
            task.isOpen &&
                task.startMillis > nowMillis &&
                task.startMillis - nowMillis <= withinMillis &&
                (category == null || task.category.name == category)
        }
        return candidates.minByOrNull { it.startMillis }
    }
}

object DailyRollup {
    fun open(tasks: List<Task>): List<Task> = tasks.filter { it.isOpen }
    fun completed(tasks: List<Task>): List<Task> = tasks.filter { it.status == com.capsulepal.app.data.model.TaskStatus.COMPLETED }
    fun progress(tasks: List<Task>): Float {
        if (tasks.isEmpty()) return 0f
        val done = tasks.count {
            it.status == com.capsulepal.app.data.model.TaskStatus.COMPLETED ||
                it.status == com.capsulepal.app.data.model.TaskStatus.SKIPPED
        }
        return done.toFloat() / tasks.size.toFloat()
    }
}
