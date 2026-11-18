package com.capsulepal.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.capsulepal.app.data.model.Task
import com.capsulepal.app.data.model.TaskStatus
import com.capsulepal.app.data.model.TemplateBlock
import com.capsulepal.app.data.model.TemplateType
import kotlinx.coroutines.flow.Flow

@Dao
abstract class TaskDao {

    @Transaction
    @Query("SELECT * FROM tasks WHERE epochDay = :epochDay ORDER BY startMinute ASC")
    abstract fun observeForDay(epochDay: Long): Flow<List<TaskWithSteps>>

    @Transaction
    @Query(
        "SELECT * FROM tasks WHERE epochDay BETWEEN :startEpochDay AND :endEpochDay " +
            "ORDER BY epochDay ASC, startMinute ASC"
    )
    abstract fun observeBetween(startEpochDay: Long, endEpochDay: Long): Flow<List<TaskWithSteps>>

    @Transaction
    @Query("SELECT * FROM tasks WHERE epochDay = :epochDay ORDER BY startMinute ASC")
    abstract suspend fun getForDay(epochDay: Long): List<TaskWithSteps>

    @Transaction
    @Query("SELECT * FROM tasks ORDER BY epochDay ASC, startMinute ASC")
    abstract fun observeAll(): Flow<List<TaskWithSteps>>

    /** 计划内待办；用于计划详情与进度。 */
    @Transaction
    @Query("SELECT * FROM tasks WHERE planId = :planId ORDER BY epochDay ASC, startMinute ASC")
    abstract fun observeForPlan(planId: Long): Flow<List<TaskWithSteps>>

    @Query("UPDATE tasks SET planId = 0 WHERE planId = :planId")
    abstract suspend fun detachFromPlan(planId: Long)

    /** 显式写入计划归属；0 表示移出计划。 */
    @Query("UPDATE tasks SET planId = :planId WHERE id = :id")
    abstract suspend fun assignPlan(id: Long, planId: Long)

    /** 整条重复系列一起改归属，避免编辑后计划里只剩一条实例。 */
    @Query("UPDATE tasks SET planId = :planId WHERE seriesId = :seriesId")
    abstract suspend fun assignPlanToSeries(seriesId: Long, planId: Long)

    @Query("SELECT COUNT(*) FROM tasks WHERE seriesId = :seriesId")
    abstract suspend fun countSeriesMembers(seriesId: Long): Int

    /**
     * 旧数据修复：按待办签名（标题、开始时间、重复规则、重复星期、日期最接近）
     * 找到同系列成员所属的 seriesId。此前编辑重复待办会把 seriesId 写成 0，
     * 被拆出来的实例靠这里重新认亲。
     */
    @Query(
        "SELECT seriesId FROM tasks WHERE seriesId != 0 AND id != :excludeId " +
            "AND title = :title AND startMinute = :startMinute " +
            "AND repeatRule = :repeatRule AND repeatDays = :repeatDays " +
            "ORDER BY ABS(epochDay - :epochDay) ASC LIMIT 1"
    )
    abstract suspend fun findSeriesIdBySignature(
        title: String,
        startMinute: Int,
        repeatRule: String,
        repeatDays: String,
        epochDay: Long,
        excludeId: Long
    ): Long?

    /** 旧数据修复：seriesId 为 0 但仍带重复规则的待办（被拆散的系列成员）。 */
    @Transaction
    @Query("SELECT * FROM tasks WHERE seriesId = 0 AND repeatRule != 'ONCE'")
    abstract suspend fun orphanRepeats(): List<TaskWithSteps>

    @Transaction
    @Query("SELECT * FROM tasks ORDER BY epochDay ASC, startMinute ASC")
    abstract suspend fun getAll(): List<TaskWithSteps>

    @Transaction
    @Query("SELECT * FROM tasks WHERE id = :id")
    abstract suspend fun getById(id: Long): TaskWithSteps?

    @Transaction
    @Query("SELECT * FROM tasks WHERE status IN ('IDLE', 'PREPARE_WINDOW', 'IN_PROGRESS') ORDER BY epochDay ASC, startMinute ASC")
    abstract suspend fun getOpen(): List<TaskWithSteps>

    @Query("SELECT COUNT(*) FROM tasks WHERE status IN ('IDLE', 'PREPARE_WINDOW', 'IN_PROGRESS')")
    abstract suspend fun countOpen(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertTask(entity: TaskEntity): Long

    @Update
    abstract suspend fun updateTask(entity: TaskEntity)

    @Query("UPDATE tasks SET status = :status WHERE id = :id")
    abstract suspend fun updateStatus(id: Long, status: String)

    @Query("SELECT awardedXp FROM tasks WHERE id = :id")
    abstract suspend fun awardedXp(id: Long): Int?

    @Query("UPDATE tasks SET status = :status, awardedXp = :awardedXp WHERE id = :id")
    abstract suspend fun updateStatusWithXp(id: Long, status: String, awardedXp: Int)

    /** 「立即开始」：把待办挪到今天此刻，并进入进行中。 */
    @Query(
        "UPDATE tasks SET epochDay = :epochDay, startMinute = :startMinute, status = :status " +
            "WHERE id = :id"
    )
    abstract suspend fun reanchorToNow(
        id: Long,
        epochDay: Long,
        startMinute: Int,
        status: String
    )

    /** 「挪到以后」：只改日期并回到待开始，时间、步骤与重复规则保持不变。 */
    @Query("UPDATE tasks SET epochDay = :epochDay, status = :status WHERE id = :id")
    abstract suspend fun moveToDay(id: Long, epochDay: Long, status: String)

    @Query("UPDATE tasks SET seriesId = :seriesId WHERE id = :id")
    abstract suspend fun updateSeriesId(id: Long, seriesId: Long)

    @Query("SELECT DISTINCT seriesId FROM tasks WHERE seriesId != 0")
    abstract suspend fun seriesIds(): List<Long>

    @Query("SELECT MAX(epochDay) FROM tasks WHERE seriesId = :seriesId")
    abstract suspend fun lastEpochDayOfSeries(seriesId: Long): Long?

    @Transaction
    @Query("SELECT * FROM tasks WHERE seriesId = :seriesId ORDER BY epochDay ASC LIMIT 1")
    abstract suspend fun getSeriesHead(seriesId: Long): TaskWithSteps?

    @Query("SELECT COUNT(*) FROM tasks WHERE seriesId = :seriesId AND epochDay = :epochDay")
    abstract suspend fun countInSeriesOn(seriesId: Long, epochDay: Long): Int

    @Query("DELETE FROM tasks WHERE id = :id")
    abstract suspend fun deleteTask(id: Long)

    /** 系列内全部实例 id；删除整组前用来逐个撤销闹钟与通知。 */
    @Query("SELECT id FROM tasks WHERE seriesId = :seriesId")
    abstract suspend fun idsInSeries(seriesId: Long): List<Long>

    /** 删除整个重复系列；seriesId 为 0 时不会误删单独待办。 */
    @Query("DELETE FROM tasks WHERE seriesId = :seriesId AND seriesId != 0")
    abstract suspend fun deleteSeriesTasks(seriesId: Long): Int

    @Query("SELECT id FROM tasks WHERE seriesId = :seriesId AND seriesId != 0 AND epochDay > :epochDay")
    abstract suspend fun idsInSeriesAfter(seriesId: Long, epochDay: Long): List<Long>

    @Query("DELETE FROM tasks WHERE seriesId = :seriesId AND seriesId != 0 AND epochDay > :epochDay")
    abstract suspend fun deleteSeriesRowsAfter(seriesId: Long, epochDay: Long): Int

    @Query("SELECT id FROM tasks WHERE seriesId = :seriesId AND seriesId != 0 AND epochDay >= :epochDay")
    abstract suspend fun idsInSeriesFrom(seriesId: Long, epochDay: Long): List<Long>

    @Query("DELETE FROM tasks WHERE seriesId = :seriesId AND seriesId != 0 AND epochDay >= :epochDay")
    abstract suspend fun deleteSeriesRowsFrom(seriesId: Long, epochDay: Long): Int

    @Transaction
    @Query("SELECT * FROM tasks WHERE seriesId = :seriesId AND seriesId != 0 AND epochDay > :epochDay")
    abstract suspend fun rowsInSeriesAfter(seriesId: Long, epochDay: Long): List<TaskWithSteps>

    /** 系列里日期最晚的一条，作为后续补实例的模板（编辑系列时它承载最新字段）。 */
    @Transaction
    @Query(
        "SELECT * FROM tasks WHERE seriesId = :seriesId AND seriesId != 0 " +
            "ORDER BY epochDay DESC, id DESC LIMIT 1"
    )
    abstract suspend fun getSeriesTail(seriesId: Long): TaskWithSteps?

    /** 编辑后日期可能和系列里现有实例重合，删掉同一天的其它待开始实例。 */
    @Query(
        "SELECT id FROM tasks WHERE seriesId = :seriesId AND seriesId != 0 AND epochDay = :epochDay " +
            "AND id != :keepId AND status IN ('IDLE', 'PREPARE_WINDOW')"
    )
    abstract suspend fun idsInSeriesOnDay(seriesId: Long, epochDay: Long, keepId: Long): List<Long>

    @Query(
        "DELETE FROM tasks WHERE seriesId = :seriesId AND seriesId != 0 AND epochDay = :epochDay " +
            "AND id != :keepId AND status IN ('IDLE', 'PREPARE_WINDOW')"
    )
    abstract suspend fun deleteSeriesRowsOnDay(seriesId: Long, epochDay: Long, keepId: Long): Int

    @Query("DELETE FROM task_steps WHERE taskId = :taskId")
    abstract suspend fun deleteSteps(taskId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertSteps(steps: List<TaskStepEntity>)

    @Transaction
    open suspend fun upsert(task: Task): Long {
        val existing = if (task.id != 0L) getById(task.id)?.task else null
        val entity = task.toEntity().copy(
            status = existing?.status ?: task.status.name,
            awardedXp = existing?.awardedXp ?: 0
        )
        val taskId = insertTask(entity)
        deleteSteps(taskId)
        if (task.steps.isNotEmpty()) {
            insertSteps(task.steps.map { it.toEntity(taskId) })
        }
        return taskId
    }

    @Query("UPDATE tasks SET status = :to WHERE status IN ('IDLE', 'PREPARE_WINDOW', 'IN_PROGRESS') AND epochDay = :epochDay")
    abstract suspend fun markDaySkipped(epochDay: Long, to: String): Int

    /** 次日结算：把早于指定日期且仍未处理的待办批量改成 COMPLETED / SKIPPED，不动 awardedXp。 */
    @Query(
        "UPDATE tasks SET status = :to WHERE epochDay < :epochDay " +
            "AND status IN ('IDLE', 'PREPARE_WINDOW', 'IN_PROGRESS')"
    )
    abstract suspend fun settleOverdueBefore(epochDay: Long, to: String): Int

    /** 次日结算（视为完成）：取出早于指定日期且仍未处理的待办，逐条按正常完成结算。 */
    @Transaction
    @Query(
        "SELECT * FROM tasks WHERE epochDay < :epochDay " +
            "AND status IN ('IDLE', 'PREPARE_WINDOW', 'IN_PROGRESS')"
    )
    abstract suspend fun overdueBefore(epochDay: Long): List<TaskWithSteps>

    @Query("SELECT COUNT(*) FROM tasks WHERE epochDay < :epochDay")
    abstract suspend fun countBefore(epochDay: Long): Int

    /** 清除已过期数据：只删早于今天的待办，步骤随外键级联删除。 */
    @Query("DELETE FROM tasks WHERE epochDay < :epochDay")
    abstract suspend fun deleteBefore(epochDay: Long): Int

    @Query("DELETE FROM tasks")
    abstract suspend fun clearAllTasks()

    @Query("DELETE FROM task_steps")
    abstract suspend fun clearAllSteps()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertTasks(tasks: List<TaskEntity>)

    /** 导入备份时整体覆盖：先清空，再按备份里的 id 原样写回，保证 seriesId 关联不乱。 */
    @Transaction
    open suspend fun replaceAllTasks(tasks: List<TaskEntity>, steps: List<TaskStepEntity>) {
        clearAllSteps()
        clearAllTasks()
        if (tasks.isNotEmpty()) insertTasks(tasks)
        if (steps.isNotEmpty()) insertSteps(steps.map { it.copy(id = 0L) })
    }
}

@Dao
abstract class TemplateDao {

    @Query("SELECT * FROM schedule_templates")
    abstract fun observeTemplates(): Flow<List<TemplateEntity>>

    @Query("SELECT * FROM template_blocks WHERE templateType = :type ORDER BY orderIndex ASC")
    abstract fun observeBlocks(type: String): Flow<List<TemplateBlockEntity>>

    @Query("SELECT * FROM template_blocks WHERE templateType = :type ORDER BY orderIndex ASC")
    abstract suspend fun getBlocks(type: String): List<TemplateBlockEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertTemplates(templates: List<TemplateEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertBlocks(blocks: List<TemplateBlockEntity>)

    @Query("DELETE FROM template_blocks WHERE templateType = :type")
    abstract suspend fun clearBlocks(type: String)

    @Query("SELECT COUNT(*) FROM schedule_templates")
    abstract suspend fun templateCount(): Int

    @Transaction
    open suspend fun replaceTemplate(type: TemplateType, blocks: List<TemplateBlock>) {
        insertTemplates(listOf(TemplateEntity(type.name, type.label)))
        clearBlocks(type.name)
        insertBlocks(
            blocks.mapIndexed { index, block ->
                TemplateBlockEntity(
                    templateType = type.name,
                    orderIndex = index,
                    title = block.title,
                    startMinute = block.startTime.hour * 60 + block.startTime.minute,
                    durationMinutes = block.durationMinutes,
                    prepareMinutes = block.prepareMinutes,
                    category = block.category.name
                )
            }
        )
    }
}

@Dao
abstract class PlanDao {

    @Query(
        "SELECT * FROM plans ORDER BY archived ASC, " +
            "CASE WHEN targetEpochDay = 0 THEN 1 ELSE 0 END, targetEpochDay ASC, " +
            "createdAtEpochDay DESC, id DESC"
    )
    abstract fun observeAll(): Flow<List<PlanEntity>>

    @Query(
        "SELECT * FROM plans ORDER BY archived ASC, " +
            "CASE WHEN targetEpochDay = 0 THEN 1 ELSE 0 END, targetEpochDay ASC, " +
            "createdAtEpochDay DESC, id DESC"
    )
    abstract suspend fun getAll(): List<PlanEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(plan: PlanEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(plans: List<PlanEntity>)

    @Query("UPDATE plans SET archived = :archived WHERE id = :id")
    abstract suspend fun setArchived(id: Long, archived: Boolean)

    /** 计划结束日（epochDay）；0 表示未设置。用于截断计划内重复待办。 */
    @Query("SELECT targetEpochDay FROM plans WHERE id = :id")
    abstract suspend fun endEpochDay(id: Long): Long?

    /**
     * 周期计划到期结算：结束日早于今天的计划，未处理的待办统一改为跳过。
     * 只影响周期计划，重复计划没有终点。
     */
    @Query(
        "UPDATE tasks SET status = 'SKIPPED' " +
            "WHERE status IN ('IDLE', 'PREPARE_WINDOW', 'IN_PROGRESS') AND planId IN (" +
            "SELECT id FROM plans WHERE type = 'PERIOD' " +
            "AND targetEpochDay > 0 AND targetEpochDay < :todayEpochDay)"
    )
    abstract suspend fun skipUnfinishedExpiredPlanTasks(todayEpochDay: Long): Int

    @Query("DELETE FROM plans WHERE id = :id")
    abstract suspend fun delete(id: Long)

    @Query("DELETE FROM plans")
    abstract suspend fun clearAll()
}

@Dao
abstract class GroupDao {

    @Query("SELECT * FROM task_groups ORDER BY sortOrder ASC, id ASC")
    abstract fun observeAll(): Flow<List<TaskGroupEntity>>

    @Query("SELECT * FROM task_groups ORDER BY sortOrder ASC, id ASC")
    abstract suspend fun getAll(): List<TaskGroupEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(group: TaskGroupEntity): Long

    @Query("DELETE FROM task_groups WHERE id = :id")
    abstract suspend fun delete(id: Long)

    /** 删除分组前先把待办移到「未分组」，避免连带丢待办。 */
    @Query("UPDATE tasks SET groupId = 0 WHERE groupId = :id")
    abstract suspend fun detachTasks(id: Long)

    @Query("UPDATE task_groups SET sortOrder = :order WHERE id = :id")
    abstract suspend fun updateOrder(id: Long, order: Int)

    @Query("DELETE FROM task_groups")
    abstract suspend fun clearAllGroups()

    /** 分组改名时同步已归属的待办（待办只存 groupId，无需额外处理）。 */
    @Transaction
    open suspend fun removeGroup(id: Long) {
        detachTasks(id)
        delete(id)
    }

    @Transaction
    open suspend fun move(id: Long, targetOrder: Int) {
        updateOrder(id, targetOrder)
    }
}
