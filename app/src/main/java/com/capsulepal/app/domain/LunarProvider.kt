package com.capsulepal.app.domain

import com.nlf.calendar.Solar
import java.time.LocalDate

object LunarProvider {

    fun lunarDayLabel(date: LocalDate): String {
        val lunar = Solar.fromYmd(date.year, date.monthValue, date.dayOfMonth).lunar
        return if (lunar.dayInChinese == "初一") {
            "${lunar.monthInChinese}月"
        } else {
            lunar.dayInChinese
        }
    }

    fun fullLabel(date: LocalDate): String {
        val lunar = Solar.fromYmd(date.year, date.monthValue, date.dayOfMonth).lunar
        return "${lunar.yearInGanZhi}${lunar.yearShengXiao}年 ${lunar.monthInChinese}月${lunar.dayInChinese}"
    }

    fun yearLabel(date: LocalDate): String {
        val lunar = Solar.fromYmd(date.year, date.monthValue, date.dayOfMonth).lunar
        return "${lunar.yearInGanZhi}${lunar.yearShengXiao}年"
    }

    fun festival(date: LocalDate): String? {
        val lunar = Solar.fromYmd(date.year, date.monthValue, date.dayOfMonth).lunar
        return lunar.festivals.firstOrNull()
    }

    fun solarTerm(date: LocalDate): String? {
        val term = Solar.fromYmd(date.year, date.monthValue, date.dayOfMonth).lunar.jieQi
        return term.takeIf { it.isNotBlank() }
    }

    fun label(date: LocalDate): String =
        festival(date) ?: solarTerm(date) ?: lunarDayLabel(date)
}
