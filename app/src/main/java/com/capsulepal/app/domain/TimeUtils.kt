package com.capsulepal.app.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

object TimeUtils {

    private val clockFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun formatClock(epochMillis: Long): String =
        java.time.Instant.ofEpochMilli(epochMillis)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalTime()
            .format(clockFormatter)

    fun formatClock(time: LocalTime): String = time.format(clockFormatter)

    fun formatCountdown(millis: Long): String {
        val totalSeconds = (millis.coerceAtLeast(0L) + 999L) / 1000L
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }

    fun formatMinutesRounded(millis: Long): String {
        val minutes = ((millis.coerceAtLeast(0L) + 59_999L) / 60_000L).toInt()
        return if (minutes <= 1) "1 分钟" else "$minutes 分钟"
    }

    fun formatDuration(minutes: Int): String = when {
        minutes < 60 -> "$minutes 分钟"
        minutes % 60 == 0 -> "${minutes / 60} 小时"
        else -> "${minutes / 60} 小时 ${minutes % 60} 分"
    }

    fun greeting(hour: Int): String = when (hour) {
        in 5..8 -> "早上好"
        in 9..11 -> "上午好"
        in 12..13 -> "中午好"
        in 14..17 -> "下午好"
        in 18..22 -> "晚上好"
        else -> "夜深了"
    }

    fun humanDate(date: LocalDate, today: LocalDate = LocalDate.now()): String = when (date) {
        today -> "今天"
        today.plusDays(1) -> "明天"
        today.minusDays(1) -> "昨天"
        else -> when (date.dayOfWeek) {
            DayOfWeek.MONDAY -> "周一"
            DayOfWeek.TUESDAY -> "周二"
            DayOfWeek.WEDNESDAY -> "周三"
            DayOfWeek.THURSDAY -> "周四"
            DayOfWeek.FRIDAY -> "周五"
            DayOfWeek.SATURDAY -> "周六"
            DayOfWeek.SUNDAY -> "周日"
        }
    }

    fun fullDate(date: LocalDate): String = "${date.monthValue} 月 ${date.dayOfMonth} 日"

    fun isSameDay(first: Long, second: Long): Boolean {
        val zone = java.time.ZoneId.systemDefault()
        val a = java.time.Instant.ofEpochMilli(first).atZone(zone).toLocalDate()
        val b = java.time.Instant.ofEpochMilli(second).atZone(zone).toLocalDate()
        return a == b
    }

    fun epochMillis(date: LocalDate, time: LocalTime): Long =
        date.atTime(time).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    fun startOfDay(date: LocalDate): Long =
        date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
}
