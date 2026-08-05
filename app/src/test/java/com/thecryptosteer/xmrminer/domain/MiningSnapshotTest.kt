package com.thecryptosteer.xmrminer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiningSnapshotTest {
    @Test fun donationPercentageMatchesCumulativeTime() {
        val minute = 60L * 1_000_000_000L
        val snapshot = MiningSnapshot(userMiningNanos = 49L * minute, donationMiningNanos = minute)
        assertEquals(2.0, snapshot.donationPercent, 0.000001)
        assertTrue(snapshot.donationPercent <= 2.0)
    }
}
