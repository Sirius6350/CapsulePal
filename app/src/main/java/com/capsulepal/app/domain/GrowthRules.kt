package com.capsulepal.app.domain

data class GrowthLevel(
    val level: Int,
    val title: String,
    val totalXp: Int,
    val xpIntoLevel: Int,
    val xpForNextLevel: Int
) {
    val progress: Float
        get() = if (xpForNextLevel <= 0) 1f else (xpIntoLevel.toFloat() / xpForNextLevel.toFloat()).coerceIn(0f, 1f)

    val isMax: Boolean
        get() = level >= GrowthRules.MAX_LEVEL
}

object GrowthRules {

    const val XP_PER_COMPLETION = 10
    const val MAX_LEVEL = 12
    const val DEFAULT_FULL_XP_COUNT = 4

    private val titles = listOf(
        "初识", "熟悉", "默契", "知心", "同行", "老友",
        "搭子", "灵魂搭子", "守护者", "小太阳", "星星糖", "传说伙伴"
    )

    fun xpForCompletion(completedToday: Int, fullXpCount: Int = DEFAULT_FULL_XP_COUNT): Int {
        val full = fullXpCount.coerceIn(3, 5)
        return if (completedToday < full) {
            XP_PER_COMPLETION
        } else {
            (XP_PER_COMPLETION - (completedToday - full + 1) * 2).coerceAtLeast(1)
        }
    }

    fun thresholdForLevel(level: Int): Int {
        if (level <= 1) return 0
        var total = 0
        for (current in 2..level) {
            total += 60 + (current - 2) * 30
        }
        return total
    }

    fun levelForXp(totalXp: Int): Int {
        var level = 1
        while (level < MAX_LEVEL && totalXp >= thresholdForLevel(level + 1)) {
            level++
        }
        return level
    }

    fun levelInfo(totalXp: Int): GrowthLevel {
        val safeXp = totalXp.coerceAtLeast(0)
        val level = levelForXp(safeXp)
        val floor = thresholdForLevel(level)
        val next = if (level >= MAX_LEVEL) floor else thresholdForLevel(level + 1)
        return GrowthLevel(
            level = level,
            title = titles.getOrElse(level - 1) { titles.last() },
            totalXp = safeXp,
            xpIntoLevel = safeXp - floor,
            xpForNextLevel = (next - floor).coerceAtLeast(0)
        )
    }

    fun fullXpRemaining(completedToday: Int, fullXpCount: Int = DEFAULT_FULL_XP_COUNT): Int =
        (fullXpCount.coerceIn(3, 5) - completedToday).coerceAtLeast(0)
}
