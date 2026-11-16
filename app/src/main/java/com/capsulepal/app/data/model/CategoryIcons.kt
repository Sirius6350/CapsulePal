package com.capsulepal.app.data.model

import androidx.annotation.DrawableRes
import com.capsulepal.app.R
import java.net.URLDecoder
import java.net.URLEncoder

@DrawableRes
fun TaskCategory.iconRes(): Int = when (this) {
    TaskCategory.CUSTOM -> R.drawable.ic_cat_custom
    TaskCategory.ROUTINE -> R.drawable.ic_cat_routine
    TaskCategory.OUTING -> R.drawable.ic_cat_outing
    TaskCategory.FOOD -> R.drawable.ic_cat_food
    TaskCategory.WATER -> R.drawable.ic_cat_water
    TaskCategory.PAYMENT -> R.drawable.ic_cat_payment
    TaskCategory.PET -> R.drawable.ic_cat_pet
    TaskCategory.FOCUS -> R.drawable.ic_cat_focus
    TaskCategory.MEETING -> R.drawable.ic_cat_meeting
    TaskCategory.MEDICATION -> R.drawable.ic_cat_medication
    TaskCategory.EXERCISE -> R.drawable.ic_cat_exercise
    TaskCategory.REST -> R.drawable.ic_cat_rest
    TaskCategory.PICKUP -> R.drawable.ic_cat_pickup
    TaskCategory.SOCIAL -> R.drawable.ic_cat_social
    TaskCategory.SHOPPING -> R.drawable.ic_cat_shopping
    TaskCategory.CHORES -> R.drawable.ic_cat_chores
    TaskCategory.OTHER -> R.drawable.ic_cat_other
}

/** 自定义分类优先展示用户填写的名称。 */
fun Task.displayCategory(): String =
    if (category == TaskCategory.CUSTOM && customCategory.isNotBlank()) customCategory else category.label

object CategoryCatalog {

    val defaultOrder: List<TaskCategory> = listOf(
        TaskCategory.ROUTINE,
        TaskCategory.OUTING,
        TaskCategory.FOOD,
        TaskCategory.WATER,
        TaskCategory.PAYMENT,
        TaskCategory.PET,
        TaskCategory.FOCUS,
        TaskCategory.MEETING,
        TaskCategory.MEDICATION,
        TaskCategory.EXERCISE,
        TaskCategory.REST,
        TaskCategory.PICKUP,
        TaskCategory.SOCIAL,
        TaskCategory.SHOPPING,
        TaskCategory.CHORES,
        TaskCategory.CUSTOM,
        TaskCategory.OTHER
    )

    val defaultLayout: List<CategoryEntry> = defaultOrder.map { CategoryEntry.BuiltIn(it) }

    fun normalize(raw: List<TaskCategory>): List<TaskCategory> {
        val ordered = raw.distinct()
        return ordered + TaskCategory.entries.filter { it !in ordered }
    }

    fun parse(raw: String?): List<TaskCategory> {
        if (raw.isNullOrBlank()) return defaultOrder
        val parsed = raw.split(',')
            .mapNotNull { runCatching { TaskCategory.valueOf(it.trim()) }.getOrNull() }
        return normalize(parsed)
    }

    fun encode(order: List<TaskCategory>): String = normalize(order).joinToString(",") { it.name }

    /** 把内置顺序和用户自建分类合成一份可编辑的完整列表。 */
    fun layout(order: List<TaskCategory>, customNames: Collection<String>): List<CategoryEntry> =
        normalize(order).map { CategoryEntry.BuiltIn(it) } +
            customNames.map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
                .map { CategoryEntry.Custom(it) }

    /** 丢弃已删除的分类，并补齐缺失的内置分类与自建分类。 */
    fun normalizeLayout(
        raw: List<CategoryEntry>,
        customNames: Collection<String>
    ): List<CategoryEntry> {
        val names = customNames.map { it.trim() }.filter { it.isNotBlank() }.toSet()
        val seenBuiltIn = mutableSetOf<TaskCategory>()
        val seenCustom = mutableSetOf<String>()
        val kept = raw.filter { entry ->
            when (entry) {
                is CategoryEntry.BuiltIn -> seenBuiltIn.add(entry.category)
                is CategoryEntry.Custom -> entry.name in names && seenCustom.add(entry.name)
            }
        }
        val missingBuiltIn = TaskCategory.entries
            .filter { it !in seenBuiltIn }
            .map { CategoryEntry.BuiltIn(it) }
        val missingCustom = names
            .filter { it !in seenCustom }
            .sorted()
            .map { CategoryEntry.Custom(it) }
        return kept + missingBuiltIn + missingCustom
    }

    fun parseLayout(
        raw: String?,
        order: List<TaskCategory>,
        customNames: Collection<String>
    ): List<CategoryEntry> {
        if (raw.isNullOrBlank()) return layout(order, customNames)
        val parsed = raw.split(',').mapNotNull { token ->
            if (token.startsWith(CUSTOM_PREFIX)) {
                runCatching {
                    URLDecoder.decode(token.removePrefix(CUSTOM_PREFIX), "UTF-8")
                }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }?.let { CategoryEntry.Custom(it) }
            } else {
                runCatching { CategoryEntry.BuiltIn(TaskCategory.valueOf(token.trim())) }.getOrNull()
            }
        }
        if (parsed.isEmpty()) return layout(order, customNames)
        return normalizeLayout(parsed, customNames)
    }

    fun encodeLayout(entries: List<CategoryEntry>): String =
        entries.joinToString(",") { entry ->
            when (entry) {
                is CategoryEntry.BuiltIn -> entry.category.name
                is CategoryEntry.Custom -> CUSTOM_PREFIX + URLEncoder.encode(entry.name, "UTF-8")
            }
        }

    private const val CUSTOM_PREFIX = "custom:"
}
