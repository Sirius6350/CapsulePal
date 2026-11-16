package com.capsulepal.app.data.repo

import com.capsulepal.app.data.db.TaskDao
import com.capsulepal.app.data.db.PlanDao
import com.capsulepal.app.data.db.toDomain
import com.capsulepal.app.data.db.toEntity
import com.capsulepal.app.data.model.Plan
import com.capsulepal.app.data.model.Task
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/** 计划与计划内待办的读写入口。 */
class PlanRepository(
    private val planDao: PlanDao,
    private val taskDao: TaskDao
) {

    fun observePlans(): Flow<List<Plan>> =
        planDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    suspend fun plans(): List<Plan> = planDao.getAll().map { it.toDomain() }

    fun observeTasks(planId: Long): Flow<List<Task>> =
        taskDao.observeForPlan(planId).map { rows -> rows.map { it.toDomain() } }

    /** 周期计划到期结算：结束日早于今天的计划，未处理待办统一跳过。 */
    suspend fun skipUnfinishedExpired(today: LocalDate): Int =
        planDao.skipUnfinishedExpiredPlanTasks(today.toEpochDay())

    suspend fun save(plan: Plan): Long =
        planDao.upsert(plan.copy(title = plan.title.trim()).toEntity())

    suspend fun setArchived(id: Long, archived: Boolean) = planDao.setArchived(id, archived)

    /** 删除计划只解除待办关联，待办本身保留。 */
    suspend fun delete(id: Long) {
        taskDao.detachFromPlan(id)
        planDao.delete(id)
    }

    /** 备份导入：整体覆盖计划，任务里的 planId 由 TaskRepository 一并恢复。 */
    suspend fun replaceAll(plans: List<Plan>) {
        planDao.clearAll()
        if (plans.isNotEmpty()) planDao.insertAll(plans.map { it.toEntity() })
    }

    suspend fun clearAll() = planDao.clearAll()
}
