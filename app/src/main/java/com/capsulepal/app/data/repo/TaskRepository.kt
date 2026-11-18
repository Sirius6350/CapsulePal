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

    /**
     * 兼容旧数据：此前编辑重复待办会把 seriesId 写成 0。
     * 头项被拆散时子项仍以它的 id 作为 seriesId，可以直接认回；
     * 子实例被拆散时则按签名找回原系列，找不到才把自己当成新的系列头。
     */
    private suspend fun resolveSeriesId(task: Task): Long {
        if (task.seriesId != 0L) return task.seriesId
        if (task.repeat == RepeatRule.ONCE) return 0L
        if (taskDao.countSeriesMembers(task.id) > 0) return task.id
        return taskDao.findSeriesIdBySignature(
            title = task.title,
            startMinute = task.startTime.hour * 60 + task.startTime.minute,
            repeatRule = task.repeat.name,
            repeatDays = seriesDaysSignature(task),
            epochDay = task.date.toEpochDay(),
            excludeId = task.id
        ) ?: task.id
    }

    /** 与 TaskEntity.repeatDays 的存储格式保持一致，才能按签名匹配。 */
    private fun seriesDaysSignature(task: Task): String =
        task.repeatDays.sortedBy { it.value }.joinToString(",") { it.value.toString() }

    /**
     * 启动时修复旧版本编辑拆散的系列关系：
     * 把 seriesId 为 0 的重复待办重新挂回原系列，并把计划归属同步到整条系列。
     */
    suspend fun repairSeriesLinks(): Int {
        var fixed = 0
        taskDao.orphanRepeats().forEach { row ->
            val task = row.toDomain()
            if (task.seriesId != 0L || task.repeat == RepeatRule.ONCE) return@forEach
            // 找不到原系列时会把它当成新的系列头，后续才能继续往后铺实例。
            val target = resolveSeriesId(task)
            if (target == 0L) return@forEach
            taskDao.updateSeriesId(task.id, target)
            if (task.planId != 0L) {
                // 用户刚编辑过这条待办的归属，以它为准同步整条系列。
                taskDao.assignPlanToSeries(target, task.planId)
            } else {
                val headPlanId = taskDao.getSeriesHead(target)?.toDomain()?.planId ?: 0L
                if (headPlanId != 0L) taskDao.assignPlan(task.id, headPlanId)
            }
            fixed++
        }
        return fixed
    }

    /** 保存结果：removedIds 是这次编辑顺带删掉的实例，调用方需撤销它们的闹钟与通知。 */
    data class UpsertResult(val id: Long, val removedIds: List<Long> = emptyList())

    suspend fun upsert(draft: TaskDraft): Long = upsertDetailed(draft).id

    suspend fun upsertDetailed(draft: TaskDraft): UpsertResult {
        val existing = if (draft.id != 0L) taskDao.getById(draft.id)?.toDomain() else null
        val existingStatus = existing?.status ?: TaskStatus.IDLE
        // toTask() 不带 seriesId：编辑时直接覆盖会把这条待办从重复系列里拆出去，
        // 于是计划里只剩单条、取消关联后系列成员还留在计划里。
        val previousSeriesId = existing?.let { resolveSeriesId(it) } ?: 0L
        val wantsSeries = draft.repeat != RepeatRule.ONCE
        // 单次改成重复时，这条自己就是系列头；全新待办要等插库拿到 id 之后再补。
        val seriesId = when {
            !wantsSeries -> 0L
            previousSeriesId != 0L -> previousSeriesId
            existing != null -> existing.id
            else -> 0L
        }
        val task = draft.toTask(existingStatus).copy(seriesId = seriesId)
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
        // 编辑的是重复待办时，归属要写进整条系列；只改当前实例会让计划里只剩单次待办。
        if (seriesId != 0L) {
            taskDao.assignPlanToSeries(seriesId, draft.planId)
        }

        val removedIds = mutableListOf<Long>()
        when {
            existing == null -> {
                if (wantsSeries) {
                    taskDao.updateSeriesId(id, id)
                    materializeAround(saved.copy(id = id, seriesId = id), id)
                }
            }
            !wantsSeries -> {
                if (previousSeriesId != 0L) {
                    // 重复改成仅一次：删掉这一天之后的实例，当前这条脱出系列。
                    removedIds += taskDao.idsInSeriesAfter(previousSeriesId, saved.date.toEpochDay())
                    taskDao.deleteSeriesRowsAfter(previousSeriesId, saved.date.toEpochDay())
                    taskDao.updateSeriesId(id, 0L)
                    closeSeries(previousSeriesId)
                }
            }
            previousSeriesId == 0L -> {
                // 仅一次改成重复：以当前这条为系列头，按新规则向后铺。
                taskDao.updateSeriesId(id, id)
                materializeAround(saved.copy(id = id, seriesId = id), id)
            }
            else -> {
                val ruleChanged = existing.repeat != saved.repeat ||
                    existing.repeatDays != saved.repeatDays ||
                    existing.repeatCount != saved.repeatCount ||
                    existing.skipHolidays != saved.skipHolidays
                if (ruleChanged) {
                    // 规则变了：旧的未来实例作废，按新规则重铺。
                    removedIds += taskDao.idsInSeriesAfter(previousSeriesId, saved.date.toEpochDay())
                    taskDao.deleteSeriesRowsAfter(previousSeriesId, saved.date.toEpochDay())
                } else {
                    // 规则没变：把这次编辑同步到这一天之后的整条系列。
                    syncFutureRows(previousSeriesId, saved)
                }
                // 日期被挪动后可能与系列里已有的实例重合，去掉重复的那条。
                removedIds += taskDao.idsInSeriesOnDay(previousSeriesId, saved.date.toEpochDay(), id)
                taskDao.deleteSeriesRowsOnDay(previousSeriesId, saved.date.toEpochDay(), id)
                taskDao.updateSeriesId(id, previousSeriesId)
                materializeAround(saved.copy(id = id, seriesId = previousSeriesId), previousSeriesId)
            }
        }
        return UpsertResult(id, removedIds)
    }

    /** 删除这一天及之后的所有实例，更早的历史保留；返回被删除的实例 id。 */
    suspend fun deleteSeriesFrom(taskId: Long): List<Long> {
        val task = taskDao.getById(taskId)?.toDomain() ?: return emptyList()
        val seriesId = resolveSeriesId(task)
        return if (seriesId == 0L) {
            taskDao.deleteTask(taskId)
            listOf(taskId)
        } else {
            val removed = taskDao.idsInSeriesFrom(seriesId, task.date.toEpochDay())
            taskDao.deleteSeriesRowsFrom(seriesId, task.date.toEpochDay())
            closeSeries(seriesId)
            removed
        }
    }

    /** 把被编辑实例的字段同步到系列里这一天之后的待开始实例。 */
    private suspend fun syncFutureRows(seriesId: Long, template: Task) {
        taskDao.rowsInSeriesAfter(seriesId, template.date.toEpochDay()).forEach { row ->
            val current = row.toDomain()
            if (current.status != TaskStatus.IDLE) return@forEach
            taskDao.upsert(
                current.copy(
                    title = template.title,
                    shortTitle = template.shortTitle,
                    startTime = template.startTime,
                    durationMinutes = template.durationMinutes,
                    prepareMinutes = template.prepareMinutes,
                    category = template.category,
                    customCategory = template.customCategory,
                    priority = template.priority,
                    note = template.note,
                    repeat = template.repeat,
                    repeatDays = template.repeatDays,
                    repeatCount = template.repeatCount,
                    skipHolidays = template.skipHolidays,
                    groupId = template.groupId,
                    planId = template.planId,
                    kind = template.kind,
                    steps = template.steps.map { it.copy(id = 0L) }
                )
            )
        }
    }

    /** 系列只剩历史时把模板改成「仅一次」，避免跨天维护又把未来实例补回来。 */
    private suspend fun closeSeries(seriesId: Long) {
        val tail = taskDao.getSeriesTail(seriesId)?.toDomain() ?: return
        if (tail.repeat == RepeatRule.ONCE) return
        taskDao.upsert(tail.copy(repeat = RepeatRule.ONCE, repeatDays = emptySet(), repeatCount = 0))
    }

    /** 按当前规则把这一条之后的实例补到滚动窗口；不回填今天之前的历史。 */
    private suspend fun materializeAround(template: Task, seriesId: Long) {
        val today = LocalDate.now()
        var until = when (template.repeat) {
            RepeatRule.ONCE -> return
            RepeatRule.CONSECUTIVE -> template.date.plusDays(
                template.repeatCount.coerceIn(2, MAX_CONSECUTIVE_DAYS) - 1L
            )
            RepeatRule.DAILY, RepeatRule.WEEKLY -> today.plusDays(SERIES_HORIZON_DAYS)
        }
        // 计划内的重复待办不越过计划结束日，避免把分母撑到计划之外。
        planEndDate(template.planId)?.let { end -> if (until.isAfter(end)) until = end }
        var from = template.date.plusDays(1)
        if (from.isBefore(today)) from = today
        if (from.isAfter(until)) return
        materializeSeries(template, seriesId, from, until)
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
                extendSeriesTo(head, seriesId, today, horizon)
            }
        }

    /** 单条系列补到当前 30 天窗口（计划内系列补到计划结束日为止）。 */
    private suspend fun extendSeries(seriesId: Long) {
        val head = taskDao.getSeriesHead(seriesId)?.toDomain() ?: return
        val today = LocalDate.now()
        extendSeriesTo(head, seriesId, today, today.plusDays(SERIES_HORIZON_DAYS))
    }

    private suspend fun extendSeriesTo(
        head: Task,
        seriesId: Long,
        today: LocalDate,
        horizon: LocalDate
    ) {
        // 用系列里最晚的一条当模板：编辑系列时会同步到这里，保证新补的实例用最新字段。
        val template = taskDao.getSeriesTail(seriesId)?.toDomain() ?: head
        // 连续 n 天在创建时已一次性铺满；仅一次表示系列已被手动结束。
        if (template.repeat == RepeatRule.CONSECUTIVE || template.repeat == RepeatRule.ONCE) return
        val lastDay = taskDao.lastEpochDayOfSeries(seriesId) ?: return
        var start = LocalDate.ofEpochDay(lastDay).plusDays(1)
        var target = horizon
        // 计划内系列只补到计划结束日。
        planEndDate(template.planId)?.let { end -> if (target.isAfter(end)) target = end }
        if (start.isAfter(target)) return
        if (start.isBefore(today)) start = today
        if (start.isAfter(target)) return
        materializeSeries(template, seriesId, start, target)
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
