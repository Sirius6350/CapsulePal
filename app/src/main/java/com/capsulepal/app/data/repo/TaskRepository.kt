package com.capsulepal.app.data.repo

import com.capsulepal.app.data.db.GroupDao
import com.capsulepal.app.data.db.PlanDao
import com.capsulepal.app.data.db.TaskDao
import com.capsulepal.app.data.db.TaskGroupEntity
import com.capsulepal.app.data.db.TemplateDao
import com.capsulepal.app.data.db.toDomain
import com.capsulepal.app.data.db.toEntity
import com.capsulepal.app.data.model.MAX_CONSECUTIVE_DAYS
import com.capsulepal.app.data.model.OverduePolicy
import com.capsulepal.app.data.model.Priority
import com.capsulepal.app.data.model.RepeatRule
import com.capsulepal.app.data.model.ScheduleTemplate
import com.capsulepal.app.data.model.Task
import com.capsulepal.app.data.model.TaskCategory
import com.capsulepal.app.data.model.TaskDraft
import com.capsulepal.app.data.model.TaskGroup
import com.capsulepal.app.data.model.TaskStatus
import com.capsulepal.app.data.model.TemplateBlock
import com.capsulepal.app.data.model.TemplateType
import com.capsulepal.app.domain.HolidayProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.LocalTime

class TaskRepository(
    private val taskDao: TaskDao,
    private val templateDao: TemplateDao,
    private val groupDao: GroupDao,
    private val planDao: PlanDao
) {

    /** 启动补铺与跨天补铺可能同时触发，串行化避免同一日期重复插入实例。 */
    private val seriesMutex = Mutex()

    fun observeGroups(): Flow<List<TaskGroup>> = groupDao.observeAll().map { rows ->
        rows.map { TaskGroup(id = it.id, name = it.name, sortOrder = it.sortOrder) }
    }

    suspend fun groups(): List<TaskGroup> = groupDao.getAll().map {
        TaskGroup(id = it.id, name = it.name, sortOrder = it.sortOrder)
    }

    /** 新建分组默认排在最后。 */
    suspend fun saveGroup(group: TaskGroup): Long {
        val order = if (group.id == 0L) {
            (groupDao.getAll().maxOfOrNull { it.sortOrder } ?: -1) + 1
        } else {
            group.sortOrder
        }
        return groupDao.upsert(
            TaskGroupEntity(id = group.id, name = group.name.trim(), sortOrder = order)
        )
    }

    suspend fun deleteGroup(id: Long) = groupDao.removeGroup(id)

    /** 上移/下移分组：交换 sortOrder 后整体重排，保证序号连续。 */
    suspend fun moveGroup(id: Long, delta: Int) {
        val all = groupDao.getAll()
        val index = all.indexOfFirst { it.id == id }
        if (index < 0) return
        val target = (index + delta).coerceIn(0, all.lastIndex)
        if (target == index) return
        val reordered = all.toMutableList().apply {
            add(target, removeAt(index))
        }
        reordered.forEachIndexed { order, entity -> groupDao.updateOrder(entity.id, order) }
    }

    /** 导入备份：恢复分组（保留原 id，待办的 groupId 才能对上）。 */
    suspend fun replaceGroups(groups: List<TaskGroup>) {
        groupDao.clearAllGroups()
        groups.forEach { group ->
            groupDao.upsert(
                TaskGroupEntity(
                    id = group.id,
                    name = group.name.trim(),
                    sortOrder = group.sortOrder
                )
            )
        }
    }

    fun observeTasks(date: LocalDate): Flow<List<Task>> =
        taskDao.observeForDay(date.toEpochDay()).map { rows -> rows.map { it.toDomain() } }

    fun observeTasksBetween(start: LocalDate, end: LocalDate): Flow<List<Task>> =
        taskDao.observeBetween(start.toEpochDay(), end.toEpochDay())
            .map { rows -> rows.map { it.toDomain() } }

    /** 总结与计划进度用：全部待办（含已完成与已跳过）。 */
    fun observeAllTasks(): Flow<List<Task>> =
        taskDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    suspend fun tasksFor(date: LocalDate): List<Task> =
        taskDao.getForDay(date.toEpochDay()).map { it.toDomain() }

    suspend fun allTasks(): List<Task> = taskDao.getAll().map { it.toDomain() }

    /** 备份用：带上奖励经验的任务快照，导入后撤销完成不会重复领取经验。 */
    suspend fun backupItems(): List<TaskBackupItem> = taskDao.getAll().map { row ->
        TaskBackupItem(task = row.toDomain(), awardedXp = row.task.awardedXp)
    }

    /** 导入备份：整体覆盖现有待办与日程。 */
    suspend fun replaceAll(items: List<TaskBackupItem>) {
        taskDao.replaceAllTasks(
            tasks = items.map { it.task.toEntity().copy(awardedXp = it.awardedXp) },
            steps = items.flatMap { item -> item.task.steps.map { it.toEntity(item.task.id) } }
        )
    }

    suspend fun openTasks(): List<Task> = taskDao.getOpen().map { it.toDomain() }

    suspend fun canAddTask(): Boolean = taskDao.countOpen() < MAX_OPEN_TASKS

    suspend fun task(id: Long): Task? = taskDao.getById(id)?.toDomain()

    suspend fun upsert(draft: TaskDraft): Long {
        val existing = if (draft.id != 0L) taskDao.getById(draft.id)?.toDomain() else null
        val existingStatus = existing?.status ?: TaskStatus.IDLE
        val task = draft.toTask(existingStatus)
        // 时间被改到更晚时，旧的准备状态会让人误以为已经进入准备窗口，重置回待开始。
        val scheduleChanged = existing != null && (
            existing.date != task.date ||
                existing.startTime != task.startTime ||
                existing.durationMinutes != task.durationMinutes ||
                existing.prepareMinutes != task.prepareMinutes
            )
        val saved = if (scheduleChanged && existingStatus == TaskStatus.PREPARE_WINDOW) {
            task.copy(status = TaskStatus.IDLE)
        } else {
            task
        }
        val id = taskDao.upsert(saved)
        // 计划归属再显式写一次：新增 / 编辑后都能被计划详情稳定查到，0 表示移出计划。
        taskDao.assignPlan(id, draft.planId)
        if (existing == null && task.repeat != RepeatRule.ONCE) {
            taskDao.updateSeriesId(id, id)
            var until = if (task.repeat == RepeatRule.CONSECUTIVE) {
                task.date.plusDays(
                    task.repeatCount.coerceIn(2, MAX_CONSECUTIVE_DAYS) - 1L
                )
            } else {
                task.date.plusDays(SERIES_HORIZON_DAYS)
            }
            // 计划内的重复待办不越过计划结束日，避免把分母撑到计划之外。
            planEndDate(task.planId)?.let { end -> if (until.isAfter(end)) until = end }
            if (!until.isBefore(task.date.plusDays(1))) {
                materializeSeries(
                    base = task.copy(id = id, seriesId = id),
                    seriesId = id,
                    from = task.date.plusDays(1),
                    until = until
                )
            }
        }
        return id
    }

    /** 计划结束日；重复计划或未设置时返回 null。 */
    private suspend fun planEndDate(planId: Long): LocalDate? =
        if (planId == 0L) {
            null
        } else {
            planDao.endEpochDay(planId)?.takeIf { it > 0L }?.let(LocalDate::ofEpochDay)
        }

    /**
     * 把重复规则展开成具体日期，最多向后铺 [SERIES_HORIZON_DAYS] 天。
     * 勾选“节假日跳过”时，休息日不生成实例。
     */
    private suspend fun materializeSeries(
        base: Task,
        seriesId: Long,
        from: LocalDate,
        until: LocalDate
    ) {
        var date = from
        while (!date.isAfter(until)) {
            val matches = when (base.repeat) {
                RepeatRule.ONCE -> false
                RepeatRule.DAILY -> true
                RepeatRule.WEEKLY -> date.dayOfWeek in base.repeatDays
                RepeatRule.CONSECUTIVE -> true
            }
            val skipped = base.skipHolidays && HolidayProvider.isRestDay(date)
            if (matches && !skipped && taskDao.countInSeriesOn(seriesId, date.toEpochDay()) == 0) {
                taskDao.upsert(
                    base.copy(
                        id = 0L,
                        date = date,
                        status = TaskStatus.IDLE,
                        seriesId = seriesId,
                        steps = base.steps.map { it.copy(id = 0L) }
                    )
                )
            }
            date = date.plusDays(1)
        }
    }

    /** App 启动或跨天时补齐重复任务，避免长期使用后未来实例用完。 */
    suspend fun ensureSeriesHorizon(
        horizonDays: Long = SERIES_HORIZON_DAYS,
        include: (Task) -> Boolean = { true }
    ) =
        seriesMutex.withLock {
            val today = LocalDate.now()
            val horizon = today.plusDays(horizonDays)
            taskDao.seriesIds().forEach { seriesId ->
                val head = taskDao.getSeriesHead(seriesId)?.toDomain() ?: return@forEach
                // 当前版本隐藏的模块冻结：不再向后补实例。
                if (!include(head)) return@forEach
                // 连续 n 天在创建时已一次性铺满，删掉某一天就不该被自动补回来。
                if (head.repeat == RepeatRule.CONSECUTIVE) return@forEach
                val lastDay = taskDao.lastEpochDayOfSeries(seriesId) ?: return@forEach
                var start = LocalDate.ofEpochDay(lastDay).plusDays(1)
                var target = horizon
                // 计划内系列只补到计划结束日。
                planEndDate(head.planId)?.let { end -> if (target.isAfter(end)) target = end }
                if (start.isAfter(target)) return@forEach
                if (start.isBefore(today)) start = today
                if (start.isAfter(target)) return@forEach
                materializeSeries(head, seriesId, start, target)
            }
        }

    suspend fun setStatus(id: Long, status: TaskStatus) {
        taskDao.updateStatus(id, status.name)
    }

    /** 「立即开始」：把这一次挪到今天此刻并进入进行中，不改动同系列其他实例。 */
    suspend fun startAtNow(id: Long, date: LocalDate, startMinute: Int) {
        taskDao.reanchorToNow(id, date.toEpochDay(), startMinute, TaskStatus.IN_PROGRESS.name)
    }

    /** 「挪到以后」：只挪这一个实例的日期，状态回到待开始。 */
    suspend fun moveToDate(id: Long, date: LocalDate) {
        taskDao.moveToDay(id, date.toEpochDay(), TaskStatus.IDLE.name)
    }

    suspend fun awardedXp(id: Long): Int = taskDao.awardedXp(id) ?: 0

    suspend fun setStatusWithXp(id: Long, status: TaskStatus, awardedXp: Int) {
        taskDao.updateStatusWithXp(id, status.name, awardedXp)
    }

    suspend fun delete(id: Long) = taskDao.deleteTask(id)

    /** 系列内全部实例 id。 */
    suspend fun seriesTaskIds(seriesId: Long): List<Long> = taskDao.idsInSeries(seriesId)

    /** 删除整个重复系列，返回删除条数。 */
    suspend fun deleteSeries(seriesId: Long): Int = taskDao.deleteSeriesTasks(seriesId)

    suspend fun skipDay(date: LocalDate): Int =
        taskDao.markDaySkipped(date.toEpochDay(), TaskStatus.SKIPPED.name)

    /**
     * 次日结算：把日期早于 [before] 且仍未处理（IDLE / PREPARE_WINDOW / IN_PROGRESS）的待办
     * 统一改判为跳过。选择「视为完成」时由调用方逐条走 [AppContainer.completeTask]，
     * 以便与服务内完成保持一致的成长结算。
     */
    suspend fun settleOverdue(before: LocalDate, policy: OverduePolicy): Int =
        taskDao.settleOverdueBefore(before.toEpochDay(), policy.result.name)

    /** 次日结算用：早于 [before] 且仍未处理的待办（按正常完成处理时需要逐条结算发经验）。 */
    suspend fun overdueBefore(before: LocalDate): List<Task> =
        taskDao.overdueBefore(before.toEpochDay()).map { it.toDomain() }

    suspend fun countBefore(date: LocalDate): Int = taskDao.countBefore(date.toEpochDay())

    /** 清除已过期的待办与日程：只删早于 [date] 的记录，今天及以后保留。 */
    suspend fun deleteBefore(date: LocalDate): Int = taskDao.deleteBefore(date.toEpochDay())

    /** 清除全部本机数据：待办、步骤（外键级联）与分组一起清空。 */
    suspend fun clearAll() {
        taskDao.clearAllTasks()
        groupDao.clearAllGroups()
    }

    suspend fun templateBlocks(type: TemplateType): List<TemplateBlock> =
        templateDao.getBlocks(type.name).map { entity ->
            TemplateBlock(
                title = entity.title,
                startTime = LocalTime.ofSecondOfDay(entity.startMinute * 60L),
                durationMinutes = entity.durationMinutes,
                prepareMinutes = entity.prepareMinutes,
                category = runCatching { TaskCategory.valueOf(entity.category) }
                    .getOrDefault(TaskCategory.OTHER)
            )
        }

    suspend fun saveTemplate(type: TemplateType, blocks: List<TemplateBlock>) {
        templateDao.replaceTemplate(type, blocks)
    }

    suspend fun seedTemplatesIfEmpty() {
        if (templateDao.templateCount() > 0) return
        templateDao.replaceTemplate(TemplateType.WEEKDAY, DefaultTemplates.weekday)
        templateDao.replaceTemplate(TemplateType.WEEKEND, DefaultTemplates.weekend)
    }

    suspend fun applyTemplate(type: TemplateType, date: LocalDate): Int {
        val blocks = templateBlocks(type)
        if (blocks.isEmpty()) return 0
        val existingTitles = taskDao.getForDay(date.toEpochDay()).map { it.task.title }.toSet()
        var inserted = 0
        blocks.forEach { block ->
            if (block.title !in existingTitles) {
                val task = Task(
                    title = block.title,
                    date = date,
                    startTime = block.startTime,
                    durationMinutes = block.durationMinutes,
                    prepareMinutes = block.prepareMinutes,
                    category = block.category,
                    priority = Priority.NORMAL,
                    status = TaskStatus.IDLE,
                    fromTemplate = true
                )
                taskDao.upsert(task)
                inserted++
            }
        }
        return inserted
    }

    object DefaultTemplates {
        val weekday: List<TemplateBlock> = listOf(
            TemplateBlock("出门准备", LocalTime.of(8, 0), 20, 10, TaskCategory.OUTING),
            TemplateBlock("上午专注", LocalTime.of(9, 30), 50, 5, TaskCategory.FOCUS),
            TemplateBlock("午餐休息", LocalTime.of(12, 0), 40, 0, TaskCategory.FOOD),
            TemplateBlock("下午专注", LocalTime.of(14, 0), 50, 5, TaskCategory.FOCUS),
            TemplateBlock("晚餐", LocalTime.of(18, 30), 45, 10, TaskCategory.FOOD),
            TemplateBlock("睡前准备", LocalTime.of(22, 30), 30, 10, TaskCategory.ROUTINE)
        )

        val weekend: List<TemplateBlock> = listOf(
            TemplateBlock("慢慢吃早餐", LocalTime.of(9, 30), 30, 5, TaskCategory.FOOD),
            TemplateBlock("洗衣收拾", LocalTime.of(11, 0), 45, 5, TaskCategory.CHORES),
            TemplateBlock("自由安排", LocalTime.of(15, 0), 60, 0, TaskCategory.OTHER),
            TemplateBlock("睡前准备", LocalTime.of(21, 30), 30, 10, TaskCategory.ROUTINE)
        )
    }

    companion object {
        /** 重复任务一次向前展开的天数。 */
        const val SERIES_HORIZON_DAYS = 30L

        /** 极高的安全阈值，仅防止异常数据；闹钟按窗口分配，不再受总数限制。 */
        const val MAX_OPEN_TASKS = 5000
    }
}

/** 备份里的单条任务：领域对象 + 当前已领取的经验，用于原样恢复。 */
data class TaskBackupItem(
    val task: Task,
    val awardedXp: Int
)
