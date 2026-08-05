package com.thecryptosteer.xmrminer.ui

import com.thecryptosteer.xmrminer.domain.HashratePoint
import com.thecryptosteer.xmrminer.domain.MiningState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiLogicTest {
    @Test
    fun miningLabelsUseNaturalUserFacingCopy() {
        assertEquals("Mining", miningStateLabel(MiningState.MINING_USER))
        assertEquals("Donation mining", miningStateLabel(MiningState.MINING_DONATION))
        assertEquals("Reconnecting to pool", humanMiningSummary(MiningState.CONNECTING, "retrying"))
        assertEquals("Preparing RandomX dataset", humanMiningSummary(MiningState.INITIALIZING, "dataset"))
    }

    @Test
    fun durationFormattingDoesNotJumpBetweenAmbiguousUnits() {
        assertEquals("00:05", formatDuration(5))
        assertEquals("01:05", formatDuration(65))
        assertEquals("1:01:01", formatDuration(3_661))
    }

    @Test
    fun chartDecimationIsBoundedAndKeepsBothEnds() {
        val points = (0 until 10_000).map { index ->
            HashratePoint(index.toLong(), index.toDouble(), index % 7, index % 101)
        }
        val result = decimatePoints(points, 260)
        assertTrue(result.size <= 260)
        assertEquals(points.first(), result.first())
        assertEquals(points.last(), result.last())
    }

    @Test
    fun supportedRuntimePageSizesAreDescribedWithoutFalseFailure() {
        assertEquals("4 KiB runtime", runtimePageSizeLabel(4_096))
        assertEquals("16 KiB runtime", runtimePageSizeLabel(16_384))
    }
    @Test
    fun monitoringGaugeFractionsAreBoundedAndMeaningful() {
        assertEquals(0f, thermalPressureFraction(-1), 0.0001f)
        assertEquals(0.5f, thermalPressureFraction(3), 0.0001f)
        assertEquals(1f, thermalPressureFraction(8), 0.0001f)
        assertEquals(0f, shareLossFraction(0, 0, 0), 0.0001f)
        assertEquals(0.1f, shareLossFraction(90, 5, 5), 0.0001f)
        assertEquals(1f, shareLossFraction(0, 4, 1), 0.0001f)
    }



    @Test
    fun chartWindowUsesSelectedDuration() {
        val now = 1_000_000L
        val points = (0..20).map { index -> HashratePoint(now - index * 60_000L, index.toDouble(), 0, 50) }
        val live = chartWindow(points, ChartRange.LIVE, now)
        val five = chartWindow(points, ChartRange.FIVE_MINUTES, now)
        val hour = chartWindow(points, ChartRange.ONE_HOUR, now)
        assertTrue(live.points.size < five.points.size)
        assertTrue(five.points.size < hour.points.size)
        assertEquals(60_000L, live.durationMillis)
        assertEquals(300_000L, five.durationMillis)
    }

    @Test
    fun compactHistoryPreservesNewestPointAndBound() {
        val points = (0 until 100).map { HashratePoint(it.toLong(), it.toDouble(), it % 6, 75) }
        val compacted = compactHashrateHistory(points, 24)
        assertTrue(compacted.size <= 24)
        assertEquals(points.last(), compacted.last())
        assertEquals(points.first(), compacted.first())
        assertEquals(compacted.sortedBy { it.timeMillis }, compacted)
    }

    @Test
    fun repeatedHistoryCompactionKeepsSessionBoundaryAndNewestSamples() {
        var history = emptyList<HashratePoint>()
        repeat(2_000) { index ->
            history = compactHashrateHistory(
                history + HashratePoint(index.toLong(), index.toDouble(), index % 6, 80),
                64,
            )
        }
        assertTrue(history.size <= 64)
        assertEquals(0L, history.first().timeMillis)
        assertEquals(1_999L, history.last().timeMillis)
        assertEquals((1_968L..1_999L).toList(), history.takeLast(32).map { it.timeMillis })
    }

}
