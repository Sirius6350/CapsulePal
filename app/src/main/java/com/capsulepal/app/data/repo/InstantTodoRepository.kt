package com.capsulepal.app.data.repo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 即时待办：只存进程内存，杀进程即消失；不写 Room、不进备份。 */
data class InstantTodo(
    val title: String,
    val startedAtMillis: Long
)

class InstantTodoRepository {

    private val _todo = MutableStateFlow<InstantTodo?>(null)
    val todo: StateFlow<InstantTodo?> = _todo.asStateFlow()

    /** 同一时刻只允许一条；已有未完成时返回 false。 */
    @Synchronized
    fun create(title: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val clean = title.trim()
        if (clean.isEmpty()) return false
        if (_todo.value != null) return false
        _todo.value = InstantTodo(title = clean, startedAtMillis = nowMillis)
        return true
    }

    @Synchronized
    fun complete(): InstantTodo? {
        val current = _todo.value ?: return null
        _todo.value = null
        return current
    }

    fun current(): InstantTodo? = _todo.value
}
