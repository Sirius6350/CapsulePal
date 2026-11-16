package com.capsulepal.app.partner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class PartnerOutfitTest {

    @Test
    fun `holiday outfit follows holiday name`() {
        val date = LocalDate.of(2026, 2, 17)
        assertEquals(PartnerOutfit.MID_AUTUMN, PartnerOutfit.holidayFor(date, "中秋"))
        assertEquals(PartnerOutfit.SPRING_FESTIVAL, PartnerOutfit.holidayFor(date, "春节"))
        assertEquals(PartnerOutfit.QINGMING, PartnerOutfit.holidayFor(date, "清明"))
        assertEquals(PartnerOutfit.LABOR_DAY, PartnerOutfit.holidayFor(date, "劳动节"))
        assertEquals(PartnerOutfit.DRAGON_BOAT, PartnerOutfit.holidayFor(date, "端午"))
        assertEquals(PartnerOutfit.NATIONAL_DAY, PartnerOutfit.holidayFor(date, "国庆"))
        assertEquals(PartnerOutfit.NEW_YEAR, PartnerOutfit.holidayFor(date, "元旦"))
    }

    @Test
    fun `merged holiday prefers mid autumn`() {
        val date = LocalDate.of(2025, 10, 3)
        assertEquals(PartnerOutfit.MID_AUTUMN, PartnerOutfit.holidayFor(date, "国庆·中秋"))
    }

    @Test
    fun `christmas and halloween use fixed dates`() {
        assertEquals(
            PartnerOutfit.CHRISTMAS,
            PartnerOutfit.holidayFor(LocalDate.of(2026, 12, 24), null)
        )
        assertEquals(
            PartnerOutfit.CHRISTMAS,
            PartnerOutfit.holidayFor(LocalDate.of(2026, 12, 25), null)
        )
        assertEquals(
            PartnerOutfit.HALLOWEEN,
            PartnerOutfit.holidayFor(LocalDate.of(2026, 10, 31), null)
        )
        assertNull(PartnerOutfit.holidayFor(LocalDate.of(2026, 12, 26), null))
        assertNull(PartnerOutfit.holidayFor(LocalDate.of(2026, 3, 3), null))
    }

    @Test
    fun `state outfit prefers charging`() {
        assertEquals(
            PartnerOutfit.CHARGE_BOLT,
            PartnerOutfit.stateOutfit(PartnerMood.SLEEPY, charging = true)
        )
        assertEquals(
            PartnerOutfit.NIGHT_CAP,
            PartnerOutfit.stateOutfit(PartnerMood.SLEEPY, charging = false)
        )
        assertEquals(
            PartnerOutfit.FOCUS_GOGGLES,
            PartnerOutfit.stateOutfit(PartnerMood.FOCUS, charging = false)
        )
        assertNull(PartnerOutfit.stateOutfit(PartnerMood.ALERT, charging = false))
        assertEquals(
            PartnerOutfit.CONFETTI,
            PartnerOutfit.stateOutfit(PartnerMood.HAPPY, charging = false)
        )
        assertNull(PartnerOutfit.stateOutfit(PartnerMood.IDLE, charging = false))
    }

    @Test
    fun `holiday outfit wins over state`() {
        val date = LocalDate.of(2026, 2, 17)
        assertEquals(
            PartnerOutfit.SPRING_FESTIVAL,
            PartnerOutfit.auto(date, "春节", PartnerMood.ALERT, charging = true)
        )
        assertNull(PartnerOutfit.auto(LocalDate.of(2026, 6, 3), null, PartnerMood.IDLE, charging = false))
    }
}
