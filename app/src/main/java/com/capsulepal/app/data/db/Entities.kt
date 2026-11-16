package com.capsulepal.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.capsulepal.app.data.model.Plan
import com.capsulepal.app.data.model.PlanColor
import com.capsulepal.app.data.model.PlanType
import com.capsulepal.app.data.model.Priority
import com.capsulepal.app.data.model.RepeatRule
import com.capsulepal.app.data.model.Task
import com.capsulepal.app.data.model.TaskCategory
import com.capsulepal.app.data.model.TaskKind
import com.capsulepal.app.data.model.TaskStatus
import com.capsulepal.app.data.model.TaskStep
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    @ColumnInfo(defaultValue = "''") val shortTitle: String = "",
    val epochDay: Long,
    val startMinute: Int,
    val durationMinutes: Int,
    val prepareMinutes: Int,
    val category: String,
    @ColumnInfo(defaultValue = "''") val customCategory: String = "",
    val priority: String,
    val status: String,
    val note: String,
    @ColumnInfo(defaultValue = "'ONCE'") val repeatRule: String = "ONCE",
    @ColumnInfo(defaultValue = "''") val repeatDays: String = "",
    @ColumnInfo(defaultValue = "0") val repeatCount: Int = 0,
    @ColumnInfo(defaultValue = "0") val skipHolidays: Boolean = false,
    @ColumnInfo(defaultValue = "0") val seriesId: Long = 0L,
    /** 该任务当前完成状态对应的经验，撤销完成时按此值回扣。 */
    @ColumnInfo(defaultValue = "0") val awardedXp: Int = 0,
    @ColumnInfo(defaultValue = "0") val fromTemplate: Boolean = false,
    /** 用户自定义分组；0 表示未分组。 */
    @ColumnInfo(defaultValue = "0") val groupId: Long = 0L,
    /** 归属的计划；0 表示不属于任何计划。 */
    @ColumnInfo(defaultValue = "0") val planId: Long = 0L,
    /** 待办来源：NORMAL 待办页 / SCHEDULE 日程页。 */
    @ColumnInfo(defaultValue = "'NORMAL'") val kind: String = "NORMAL"
)

@Entity(tableName = "plans")
data class PlanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val note: String = "",
    val color: String = PlanColor.CORAL.name,
    /** REPEAT 重复计划 / PERIOD 周期计划。 */
    @ColumnInfo(defaultValue = "'REPEAT'") val type: String = "REPEAT",
    /** 周期计划开始日期；0 表示未设置。 */
    @ColumnInfo(defaultValue = "0") val startEpochDay: Long = 0L,
    /** 周期计划的结束日期 / 重复计划的目标日期；0 表示不设。 */
    val targetEpochDay: Long = 0L,
    val createdAtEpochDay: Long,
    val archived: Boolean = false
)

@Entity(tableName = "task_groups")
data class TaskGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val sortOrder: Int = 0
)

@Entity(
    tableName = "task_steps",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("taskId")]
)
data class TaskStepEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val taskId: Long,
    val orderIndex: Int,
    val title: String,
    val durationMinutes: Int
)

data class TaskWithSteps(
    @Embedded val task: TaskEntity,
    @Relation(parentColumn = "id", entityColumn = "taskId")
    val steps: List<TaskStepEntity>
)

@Entity(tableName = "schedule_templates")
data class TemplateEntity(
    @PrimaryKey val type: String,
    val name: String
)

@Entity(
    tableName = "template_blocks",
    foreignKeys = [
        ForeignKey(
            entity = TemplateEntity::class,
            parentColumns = ["type"],
            childColumns = ["templateType"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("templateType")]
)
data class TemplateBlockEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val templateType: String,
    val orderIndex: Int,
    val title: String,
    val startMinute: Int,
    val durationMinutes: Int,
    val prepareMinutes: Int,
    val category: String
)

fun TaskWithSteps.toDomain(): Task = Task(
    id = task.id,
    title = task.title,
    shortTitle = task.shortTitle,
    date = LocalDate.ofEpochDay(task.epochDay),
    startTime = LocalTime.ofSecondOfDay(task.startMinute * 60L),
    durationMinutes = task.durationMinutes,
    prepareMinutes = task.prepareMinutes,
    category = runCatching { TaskCategory.valueOf(task.category) }.getOrDefault(TaskCategory.OTHER),
    customCategory = task.customCategory,
    priority = runCatching { Priority.valueOf(task.priority) }.getOrDefault(Priority.NORMAL),
    status = runCatching { TaskStatus.valueOf(task.status) }.getOrDefault(TaskStatus.IDLE),
    note = task.note,
    steps = steps.sortedBy { it.orderIndex }.map {
        TaskStep(
            id = it.id,
            order = it.orderIndex,
            title = it.title,
            durationMinutes = it.durationMinutes
        )
    },
    repeat = runCatching { RepeatRule.valueOf(task.repeatRule) }.getOrDefault(RepeatRule.ONCE),
    repeatDays = task.repeatDays.split(',')
        .mapNotNull { runCatching { DayOfWeek.of(it.trim().toInt()) }.getOrNull() }
        .toSet(),
    repeatCount = task.repeatCount,
    skipHolidays = task.skipHolidays,
    seriesId = task.seriesId,
    fromTemplate = task.fromTemplate,
    groupId = task.groupId,
    planId = task.planId,
    kind = runCatching { TaskKind.valueOf(task.kind) }.getOrDefault(TaskKind.NORMAL)
)

fun Task.toEntity(): TaskEntity = TaskEntity(
    id = id,
    title = title,
    shortTitle = shortTitle,
    epochDay = date.toEpochDay(),
    startMinute = startTime.hour * 60 + startTime.minute,
    durationMinutes = durationMinutes,
    prepareMinutes = prepareMinutes,
    category = category.name,
    customCategory = customCategory,
    priority = priority.name,
    status = status.name,
    note = note,
    repeatRule = repeat.name,
    repeatDays = repeatDays.sortedBy { it.value }.joinToString(",") { it.value.toString() },
    repeatCount = repeatCount,
    skipHolidays = skipHolidays,
    seriesId = seriesId,
    fromTemplate = fromTemplate,
    groupId = groupId,
    planId = planId,
    kind = kind.name
)

fun PlanEntity.toDomain(): Plan = Plan(
    id = id,
    title = title,
    note = note,
    color = runCatching { PlanColor.valueOf(color) }.getOrDefault(PlanColor.CORAL),
    type = runCatching { PlanType.valueOf(type) }.getOrDefault(PlanType.REPEAT),
    startDate = startEpochDay.takeIf { it != 0L }?.let { LocalDate.ofEpochDay(it) },
    targetDate = targetEpochDay.takeIf { it != 0L }?.let { LocalDate.ofEpochDay(it) },
    createdAtEpochDay = createdAtEpochDay,
    archived = archived
)

fun Plan.toEntity(): PlanEntity = PlanEntity(
    id = id,
    title = title,
    note = note,
    color = color.name,
    type = type.name,
    startEpochDay = startDate?.toEpochDay() ?: 0L,
    targetEpochDay = targetDate?.toEpochDay() ?: 0L,
    createdAtEpochDay = createdAtEpochDay,
    archived = archived
)

fun TaskStep.toEntity(taskId: Long): TaskStepEntity = TaskStepEntity(
    id = id,
    taskId = taskId,
    orderIndex = order,
    title = title,
    durationMinutes = durationMinutes
)
