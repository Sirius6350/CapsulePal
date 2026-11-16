package com.capsulepal.app.partner

import java.time.LocalDate

/**
 * 伙伴的自动装扮：状态触发与节日限定。
 *
 * 节日优先级高于状态；充电闪电是叠加在端帽上的状态徽章，
 * 节日当天仍会亮起，只是不再占用头部位置。
 */
enum class PartnerOutfitKind { STATE, HOLIDAY }

enum class PartnerOutfit(val label: String, val kind: PartnerOutfitKind, val trigger: String) {
    NIGHT_CAP("睡帽", PartnerOutfitKind.STATE, "23:00–6:00"),
    FOCUS_GOGGLES("护目镜", PartnerOutfitKind.STATE, "进行中 / 提醒"),
    CONFETTI("彩带", PartnerOutfitKind.STATE, "完成待办"),
    CHARGE_BOLT("闪电徽章", PartnerOutfitKind.STATE, "充电时"),
    NEW_YEAR("派对帽", PartnerOutfitKind.HOLIDAY, "元旦"),
    SPRING_FESTIVAL("福帽", PartnerOutfitKind.HOLIDAY, "春节"),
    QINGMING("纸鸢", PartnerOutfitKind.HOLIDAY, "清明"),
    LABOR_DAY("安全帽", PartnerOutfitKind.HOLIDAY, "劳动节"),
    DRAGON_BOAT("粽叶帽", PartnerOutfitKind.HOLIDAY, "端午"),
    MID_AUTUMN("兔耳", PartnerOutfitKind.HOLIDAY, "中秋"),
    NATIONAL_DAY("小红旗", PartnerOutfitKind.HOLIDAY, "国庆"),
    CHRISTMAS("圣诞帽", PartnerOutfitKind.HOLIDAY, "12/24–25"),
    HALLOWEEN("南瓜帽", PartnerOutfitKind.HOLIDAY, "10/31");

    val isHoliday: Boolean get() = kind == PartnerOutfitKind.HOLIDAY

    companion object {
        /** 节日装扮只看当天；圣诞与万圣不在法定假日表里，用公历固定日期。 */
        fun holidayFor(date: LocalDate, holidayName: String?): PartnerOutfit? {
            val name = holidayName.orEmpty()
            return when {
                name.contains("中秋") -> MID_AUTUMN
                name.contains("春节") -> SPRING_FESTIVAL
                name.contains("清明") -> QINGMING
                name.contains("劳动") -> LABOR_DAY
                name.contains("端午") -> DRAGON_BOAT
                name.contains("国庆") -> NATIONAL_DAY
                name.contains("元旦") -> NEW_YEAR
                date.monthValue == 12 && date.dayOfMonth in 24..25 -> CHRISTMAS
                date.monthValue == 10 && date.dayOfMonth == 31 -> HALLOWEEN
                else -> null
            }
        }

        fun stateOutfit(mood: PartnerMood, charging: Boolean): PartnerOutfit? = when {
            mood == PartnerMood.FOCUS -> FOCUS_GOGGLES
            charging || mood == PartnerMood.CHARGING -> CHARGE_BOLT
            mood == PartnerMood.SLEEPY -> NIGHT_CAP
            mood == PartnerMood.HAPPY -> CONFETTI
            else -> null
        }

        fun auto(
            date: LocalDate,
            holidayName: String?,
            mood: PartnerMood,
            charging: Boolean
        ): PartnerOutfit? = holidayFor(date, holidayName) ?: stateOutfit(mood, charging)
    }
}
