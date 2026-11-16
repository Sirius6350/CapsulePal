package com.capsulepal.app.partner

import com.capsulepal.app.data.model.ActiveTask
import com.capsulepal.app.data.model.Task
import com.capsulepal.app.data.model.TaskPhase
import com.capsulepal.app.data.model.TaskStatus
import com.capsulepal.app.data.model.UpcomingTask
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PartnerCopyTest {

    private val date: LocalDate = LocalDate.of(2026, 11, 11)

    private fun task(
        id: Long,
        title: String,
        start: LocalTime,
        duration: Int = 30,
        status: TaskStatus = TaskStatus.IDLE
    ) = Task(
        id = id,
        title = title,
        date = date,
        startTime = start,
        durationMinutes = duration,
        status = status
    )

    private fun dayState(
        tasks: List<Task>,
        active: ActiveTask? = null,
        next: UpcomingTask? = null,
        nowMillis: Long = 0L,
        holidayName: String? = null,
        recentSkip: RecentSkip? = null,
        day: LocalDate = date
    ) = PartnerCopy.dayState(
        date = day,
        tasks = tasks,
        active = active,
        next = next,
        nowMillis = nowMillis,
        holidayName = holidayName,
        recentSkip = recentSkip
    )

    @Test
    fun `in progress shows countdown`() {
        val t = task(1, "出门准备", LocalTime.of(10, 30))
        val now = t.startMillis + 2 * 60_000L
        val active = ActiveTask(t, TaskPhase.IN_PROGRESS, t.startMillis, t.endMillis)
        val state = dayState(listOf(t), active = active, nowMillis = now)
        assertEquals("「出门准备」进行中，还剩 28 分钟，保持这个节奏。", state.message)
    }

    @Test
    fun `in progress near the end encourages`() {
        val t = task(1, "专注", LocalTime.of(10, 30), duration = 10)
        val active = ActiveTask(t, TaskPhase.IN_PROGRESS, t.startMillis, t.endMillis)
        val now = t.endMillis - 30_000L
        val state = dayState(listOf(t), active = active, nowMillis = now)
        assertEquals("再坚持一会儿，还有 1 分钟就结束了。", state.message)
    }

    @Test
    fun `prepare window shows countdown`() {
        val t = task(1, "出门准备", LocalTime.of(10, 30), duration = 30)
        val prepareStart = t.prepareStartMillis
        val active = ActiveTask(t, TaskPhase.PREPARE, prepareStart, t.startMillis)
        val state = dayState(
            listOf(t),
            active = active,
            nowMillis = prepareStart + 2 * 60_000L
        )
        assertEquals("还有 8 分钟就要开始「出门准备」啦，记得提前做好准备。", state.message)
    }

    @Test
    fun `upcoming task shows start clock`() {
        val t = task(1, "出门准备", LocalTime.of(10, 30))
        val now = t.startMillis - 20 * 60_000L
        val next = UpcomingTask(t, 10 * 60_000L, 20 * 60_000L)
        val state = dayState(listOf(t), next = next, nowMillis = now)
        assertEquals(
            "接下来的「出门准备」，10:30 开始，可以先做准备，也可以歇会儿先。",
            state.message
        )
    }

    @Test
    fun `all done celebrates`() {
        val tasks = listOf(
            task(1, "早餐", LocalTime.of(8, 0), status = TaskStatus.COMPLETED),
            task(2, "复盘", LocalTime.of(9, 0), status = TaskStatus.COMPLETED)
        )
        val state = dayState(tasks, nowMillis = tasks.last().endMillis)
        assertEquals("今天任务圆满完成，放松一下吧。", state.message)
    }

    @Test
    fun `all done with skipped counts them`() {
        val tasks = listOf(
            task(1, "早餐", LocalTime.of(8, 0), status = TaskStatus.COMPLETED),
            task(2, "复盘", LocalTime.of(9, 0), status = TaskStatus.SKIPPED)
        )
        val state = dayState(tasks, nowMillis = tasks.last().endMillis)
        assertTrue(state.message.startsWith("今天能完成的都完成了。跳过的 1 件事"))
    }

    @Test
    fun `just skipped points at the next task`() {
        val skipped = task(1, "早餐", LocalTime.of(8, 0), status = TaskStatus.SKIPPED)
        val following = task(2, "下午专注", LocalTime.of(14, 30))
        val state = dayState(
            listOf(skipped, following),
            nowMillis = skipped.startMillis,
            recentSkip = RecentSkip(skipped.id, skipped.startMillis)
        )
        assertEquals("这件先放一放，14:30 的「下午专注」见。", state.message)
    }

    @Test
    fun `just skipped without next ends the day`() {
        val skipped = task(1, "早餐", LocalTime.of(8, 0), status = TaskStatus.SKIPPED)
        val state = dayState(
            listOf(skipped),
            nowMillis = skipped.startMillis,
            recentSkip = RecentSkip(skipped.id, skipped.startMillis)
        )
        assertEquals("这件先放一放，今天先到这儿。", state.message)
    }

    @Test
    fun `empty weekend greets`() {
        val saturday = LocalDate.of(2026, 11, 14)
        val state = dayState(emptyList(), day = saturday)
        assertEquals("今天是周六，难得的美好时光。", state.message)
    }

    @Test
    fun `empty holiday greets with blessing`() {
        val nationalDay = LocalDate.of(2026, 10, 1)
        val state = dayState(emptyList(), day = nationalDay, holidayName = "国庆")
        assertEquals(
            "今天是国庆，山河辽阔，好好享受这几天，我先放个假，有事再叫我。",
            state.message
        )
    }

    @Test
    fun `empty make-up workday is plain`() {
        val sundayWorkday = LocalDate.of(2026, 2, 28)
        val state = dayState(emptyList(), day = sundayWorkday)
        assertEquals("今天没安排，按自己的节奏来。", state.message)
    }
}
