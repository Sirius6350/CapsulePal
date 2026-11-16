package com.capsulepal.app.data.repo

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.capsulepal.app.data.model.PartnerProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.time.LocalDate

class PartnerRepository(context: Context) {

    private val dataStore = context.applicationContext.capsuleDataStore

    val profile: Flow<PartnerProfile> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { prefs -> prefs.toProfile(LocalDate.now().toEpochDay()) }

    suspend fun addCompletion(xp: Int, date: LocalDate = LocalDate.now()): PartnerProfile {
        var result = PartnerProfile()
        dataStore.edit { prefs ->
            val today = date.toEpochDay()
            val current = prefs.toProfile(today)
            val completedToday = if (current.lastCompletionEpochDay == today) current.completedToday else 0
            prefs[Keys.TOTAL_XP] = current.totalXp + xp.coerceAtLeast(0)
            prefs[Keys.COMPLETED_TOTAL] = current.completedTotal + 1
            prefs[Keys.COMPLETED_TODAY] = completedToday + 1
            prefs[Keys.LAST_DAY] = today
            result = current.copy(
                totalXp = current.totalXp + xp.coerceAtLeast(0),
                completedTotal = current.completedTotal + 1,
                completedToday = completedToday + 1,
                lastCompletionEpochDay = today
            )
        }
        return result
    }

    /** 撤销一次完成：回扣经验和计数，等级由当前经验重新计算。 */
    suspend fun removeCompletion(xp: Int, date: LocalDate = LocalDate.now()): PartnerProfile {
        var result = PartnerProfile()
        dataStore.edit { prefs ->
            val today = date.toEpochDay()
            val current = prefs.toProfile(today)
            val sameDay = current.lastCompletionEpochDay == today
            val totalXp = (current.totalXp - xp.coerceAtLeast(0)).coerceAtLeast(0)
            val completedTotal = (current.completedTotal - 1).coerceAtLeast(0)
            val completedToday = if (sameDay) {
                (current.completedToday - 1).coerceAtLeast(0)
            } else {
                current.completedToday
            }
            prefs[Keys.TOTAL_XP] = totalXp
            prefs[Keys.COMPLETED_TOTAL] = completedTotal
            if (sameDay) prefs[Keys.COMPLETED_TODAY] = completedToday
            result = current.copy(
                totalXp = totalXp,
                completedTotal = completedTotal,
                completedToday = completedToday
            )
        }
        return result
    }

    suspend fun setName(name: String) {
        dataStore.edit { it[Keys.NAME] = name.trim().ifBlank { "小胶囊" } }
    }

    /** 首次启用时记录开始陪伴的日期，之后只读。 */
    suspend fun ensureCompanionSince(date: LocalDate = LocalDate.now()) {
        dataStore.edit { prefs ->
            val since = prefs[Keys.SINCE_DAY]
            if (since == null || since <= 0L) {
                prefs[Keys.SINCE_DAY] = date.toEpochDay()
            }
        }
    }

    /** 清除全部本机数据：伙伴名字、等级、经验与陪伴天数全部回到初始值。 */
    suspend fun reset() {
        dataStore.edit { prefs ->
            prefs.remove(Keys.NAME)
            prefs.remove(Keys.TOTAL_XP)
            prefs.remove(Keys.COMPLETED_TOTAL)
            prefs.remove(Keys.COMPLETED_TODAY)
            prefs.remove(Keys.LAST_DAY)
            prefs.remove(Keys.SINCE_DAY)
        }
    }

    /** 备份用：读取原始陪伴数据（含开始陪伴的日期）。 */
    suspend fun snapshot(): PartnerBackup {
        val prefs = dataStore.data
            .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
            .first()
        return PartnerBackup(
            name = prefs[Keys.NAME] ?: "小胶囊",
            totalXp = prefs[Keys.TOTAL_XP] ?: 0,
            completedTotal = prefs[Keys.COMPLETED_TOTAL] ?: 0,
            completedToday = prefs[Keys.COMPLETED_TODAY] ?: 0,
            lastCompletionEpochDay = prefs[Keys.LAST_DAY] ?: 0L,
            companionSinceEpochDay = prefs[Keys.SINCE_DAY] ?: 0L
        )
    }

    /** 导入备份：整体覆盖陪伴天数、完成数、经验与等级。 */
    suspend fun restore(backup: PartnerBackup) {
        dataStore.edit { prefs ->
            prefs[Keys.NAME] = backup.name.trim().ifBlank { "小胶囊" }
            prefs[Keys.TOTAL_XP] = backup.totalXp.coerceAtLeast(0)
            prefs[Keys.COMPLETED_TOTAL] = backup.completedTotal.coerceAtLeast(0)
            prefs[Keys.COMPLETED_TODAY] = backup.completedToday.coerceAtLeast(0)
            prefs[Keys.LAST_DAY] = backup.lastCompletionEpochDay
            prefs[Keys.SINCE_DAY] = backup.companionSinceEpochDay
        }
    }

    private fun androidx.datastore.preferences.core.Preferences.toProfile(today: Long): PartnerProfile {
        val lastDay = this[Keys.LAST_DAY] ?: 0L
        val completedToday = if (lastDay == today) this[Keys.COMPLETED_TODAY] ?: 0 else 0
        val since = this[Keys.SINCE_DAY] ?: 0L
        val companionDays = if (since > 0L) {
            (today - since + 1L).coerceAtLeast(1L).toInt()
        } else {
            1
        }
        return PartnerProfile(
            name = this[Keys.NAME] ?: "小胶囊",
            totalXp = this[Keys.TOTAL_XP] ?: 0,
            completedTotal = this[Keys.COMPLETED_TOTAL] ?: 0,
            completedToday = completedToday,
            lastCompletionEpochDay = lastDay,
            companionDays = companionDays
        )
    }

    private object Keys {
        val NAME = stringPreferencesKey("partner_name")
        val TOTAL_XP = intPreferencesKey("partner_total_xp")
        val COMPLETED_TOTAL = intPreferencesKey("partner_completed_total")
        val COMPLETED_TODAY = intPreferencesKey("partner_completed_today")
        val LAST_DAY = longPreferencesKey("partner_last_completion_day")
        val SINCE_DAY = longPreferencesKey("partner_since_epoch_day")
    }
}

/** 备份里的伙伴数据，字段与 DataStore 原始键一一对应。 */
data class PartnerBackup(
    val name: String,
    val totalXp: Int,
    val completedTotal: Int,
    val completedToday: Int,
    val lastCompletionEpochDay: Long,
    val companionSinceEpochDay: Long
)
