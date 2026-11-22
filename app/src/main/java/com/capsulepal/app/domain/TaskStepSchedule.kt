package com.capsulepal.app.domain

import com.capsulepal.app.data.model.Task
import com.capsulepal.app.data.model.TaskStep
import kotlin.math.roundToLong

/** 一个步骤在时间线上占用的区间。 */
data class TaskStepSpan(
    val step: TaskStep,
    val index: Int,
    val startMillis: Long,
    val endMillis: Long
)

/**
 * 当前步骤状态。
 * [current] 为空且 [tail] 为 true 时，表示所有步骤已走完、待办还没结束。
 */
data class TaskStepProgress(
    val current: TaskStepSpan?,
    val completedCount: Int,
    val totalCount: Int,
    val tail: Boolean,
    val nextBoundaryMillis: Long?
)

/**
 * 把步骤换算成时间线。
 *
 * 规则：
 * - 有明确时长的步骤按顺序累计；
 * - 0 分钟（不计时）步骤平分剩余时间；
 * - 所有步骤都有时长且总和小于待办时长时，步骤走完后留出一段“无步骤尾巴”；
 * - 旧数据步骤总时长超过待办时长时按比例压缩，保证不越过待办结束时间。
 */
object TaskStepSchedule {

    private const val MILLIS_PER_MINUTE = 60_000.0
    private const val TAIL_TOLERANCE_MILLIS = 500L

    fun spans(task: Task): List<TaskStepSpan> {
        val steps = task.steps
            .filter { it.title.isNotBlank() }
            .sortedBy { it.order }
        if (steps.isEmpty()) return emptyList()

        val totalMinutes = task.durationMinutes.coerceAtLeast(1).toDouble()
        val explicit = steps.map { it.durationMinutes.coerceAtLeast(0).toDouble() }
        val weights = explicit.toMutableList()

        val timedSum = explicit.sum()
        val untimedIndexes = explicit.indices.filter { explicit[it] <= 0.0 }
        if (timedSum <= 0.0) {
            val each = totalMinutes / steps.size
            for (index in weights.indices) weights[index] = each
        } else if (untimedIndexes.isNotEmpty() && timedSum < totalMinutes) {
            val each = (totalMinutes - timedSum) / untimedIndexes.size
            untimedIndexes.forEach { weights[it] = each }
        }

        val effectiveSum = weights.sum()
        if (effectiveSum > totalMinutes + 1e-6) {
            val scale = totalMinutes / effectiveSum
            for (index in weights.indices) weights[index] *= scale
        }

        var cursorMinutes = 0.0
        return steps.mapIndexed { index, step ->
            val startMillis = task.startMillis + (cursorMinutes * MILLIS_PER_MINUTE).roundToLong()
            cursorMinutes += weights[index]
            val rawEnd = task.startMillis + (cursorMinutes * MILLIS_PER_MINUTE).roundToLong()
            TaskStepSpan(
                step = step,
                index = index,
                startMillis = startMillis,
                endMillis = rawEnd.coerceAtMost(task.endMillis)
            )
        }
    }

    fun progress(task: Task, nowMillis: Long): TaskStepProgress {
        val spans = spans(task)
        if (spans.isEmpty()) {
            return TaskStepProgress(null, 0, 0, false, null)
        }
        val current = spans.firstOrNull { nowMillis >= it.startMillis && nowMillis < it.endMillis }
        val completed = spans.count { nowMillis >= it.endMillis }
        val lastEnd = spans.last().endMillis
        val tail = current == null &&
            nowMillis >= lastEnd &&
            lastEnd < task.endMillis - TAIL_TOLERANCE_MILLIS
        val nextBoundary = spans.firstOrNull { it.endMillis > nowMillis }?.endMillis
        return TaskStepProgress(
            current = current,
            completedCount = completed,
            totalCount = spans.size,
            tail = tail,
            nextBoundaryMillis = nextBoundary
        )
    }
}
