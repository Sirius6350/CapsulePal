package com.capsulepal.app.partner

/**
 * 伙伴形象的可选外观。
 *
 * 不再随等级解锁：用户在伙伴页直接勾选，等级只奖励称号。
 */
enum class PartnerAppearance(val label: String, val defaultOn: Boolean) {
    SCAN_LINE("扫描线", true),
    SCREEN_REFLECTION("屏幕反光", true),
    PROGRESS_BAND("进度灯带", false);

    companion object {
        val defaults: Set<PartnerAppearance> = entries.filter { it.defaultOn }.toSet()
    }
}
