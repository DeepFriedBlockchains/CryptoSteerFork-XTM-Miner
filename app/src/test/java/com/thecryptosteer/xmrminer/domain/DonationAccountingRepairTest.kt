package com.thecryptosteer.xmrminer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DonationAccountingRepairTest {
    @Test fun maximumDonationUsesExactTwoPercentCeiling() {
        val minute = 60L * 1_000_000_000L
        assertEquals(minute, DonationMath.maximumDonationForUserTime(49L * minute))
        assertTrue(DonationMath.invariantHolds(49L * minute, minute))
        assertTrue(!DonationMath.invariantHolds(49L * minute, minute + 1L))
    }

    @Test fun shortSessionsCarryFractionalAllowanceWithoutExceedingCeiling() {
        val second = 1_000_000_000L
        val allowance = DonationMath.maximumDonationForUserTime(49L * second)
        assertEquals(second, allowance)
        assertEquals(2.0, DonationMath.percent(49L * second, allowance), 0.0000001)
    }
}
