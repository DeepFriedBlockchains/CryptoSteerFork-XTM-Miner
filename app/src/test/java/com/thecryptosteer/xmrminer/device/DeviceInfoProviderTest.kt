package com.thecryptosteer.xmrminer.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceInfoProviderTest {
    @Test
    fun cpuListParserHandlesRangesDeduplicationAndInvalidTokens() {
        assertEquals(listOf(0, 1, 2, 4, 7, 8), parseCpuList("0-2,4,7-8,2,bad,9-3"))
        assertEquals(emptyList<Int>(), parseCpuList(""))
    }

    @Test
    fun clusterBuilderOrdersEfficiencyPerformanceAndPrime() {
        val cores = listOf(
            cpu(0, capacity = 300, maximum = 1_800_000),
            cpu(1, capacity = 300, maximum = 1_800_000),
            cpu(2, capacity = 700, maximum = 2_800_000),
            cpu(3, capacity = 1_024, maximum = 3_200_000),
        )
        val clusters = buildCpuClusters(cores)
        assertEquals(listOf("Efficiency", "Performance", "Prime"), clusters.map { it.label })
        assertEquals(listOf(0, 1), clusters.first().coreIds)
        assertEquals(listOf(3), clusters.last().coreIds)
    }

    @Test
    fun formattingUsesBinaryMemoryAndReadableFrequencyUnits() {
        assertEquals("2.5 GiB", formatBinaryBytes(2_684_354_560L))
        assertEquals("3.20 GHz", formatFrequency(3_200_000L))
        assertEquals("Unavailable", formatFrequency(null))
        assertTrue(formatBinaryBytes(512L).contains("bytes"))
    }

    private fun cpu(index: Int, capacity: Long, maximum: Long) = CpuCoreInfo(
        logicalIndex = index,
        online = true,
        allowed = true,
        capacity = capacity,
        minimumFrequencyKHz = null,
        currentFrequencyKHz = null,
        maximumFrequencyKHz = maximum,
        cluster = "",
    )
}
