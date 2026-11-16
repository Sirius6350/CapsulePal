package com.capsulepal.app.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 一条主动提醒诊断记录，只在内存中保留最近若干条。 */
data class ReminderTraceEntry(
    val atMillis: Long,
    val title: String,
    val detail: String,
    val success: Boolean
)

/** 主动提醒运行环境快照，帮助判断“信号是否有可能到达”。 */
data class ReminderDiagnostics(
    val notificationsAllowed: Boolean,
    val usageAccess: Boolean,
    val calendarPermission: Boolean,
    val serviceRunning: Boolean
)

/**
 * 主动提醒诊断日志。
 *
 * 记录「信号到达 → 生成建议 → 出口放行 → 通知发出」的关键节点，
 * 帮助判断提醒没出现时究竟卡在哪一层。进程被杀后记录会清空，
 * 这本身也说明触发信号根本没有到达 App。
 */
object ReminderTrace {

    private const val MAX_ENTRIES = 40

    private val lock = Any()
    private val _entries = MutableStateFlow<List<ReminderTraceEntry>>(emptyList())
    val entries: StateFlow<List<ReminderTraceEntry>> = _entries.asStateFlow()

    fun record(title: String, detail: String, success: Boolean) {
        val entry = ReminderTraceEntry(
            atMillis = System.currentTimeMillis(),
            title = title,
            detail = detail,
            success = success
        )
        synchronized(lock) {
            _entries.value = (listOf(entry) + _entries.value).take(MAX_ENTRIES)
        }
    }

    fun clear() {
        synchronized(lock) { _entries.value = emptyList() }
    }
}
