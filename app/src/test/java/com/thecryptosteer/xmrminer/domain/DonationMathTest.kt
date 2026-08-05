package com.thecryptosteer.xmrminer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DonationMathTest {
    @Test fun exactEpochIsTwoPercent() {
        val minute = 60L * 1_000_000_000L
        val result = DonationMath.percent(49L * minute, minute)
        assertEquals(2.0, result, 0.0000001)
    }

    @Test fun emptyAccountingIsZero() {
        assertEquals(0.0, DonationMath.percent(0, 0), 0.0)
    }

    @Test fun displayedPercentageNeverBecomesNan() {
        assertTrue(DonationMath.percent(Long.MAX_VALUE, Long.MAX_VALUE).isFinite())
    }
}
