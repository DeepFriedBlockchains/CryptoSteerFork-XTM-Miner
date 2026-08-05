package com.thecryptosteer.xmrminer.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoolValidatorTest {
    @Test
    fun acceptsTlsPoolWalletAndWorker() {
        val result = PoolValidator.validate(
            PoolProfile(
                name = "Primary",
                primary = PoolEndpoint("pool.example.com", 443, true),
                username = "44VNzHZAE1HK3NQZRPeDkMQ8pjDzivtq2eAE4iZLuLaSLZiu3wmCswrCUEm7NnWGMUB4k6cK7p7S7EXfJU7FbVTUMczHdSE",
                worker = "Pixel10-Max",
            ),
        )
        assertTrue(result.errors.joinToString(), result.valid)
    }

    @Test
    fun rejectsMissingWorkerName() {
        val result = PoolValidator.validate(
            PoolProfile(
                name = "Missing worker",
                primary = PoolEndpoint("pool.example.com", 443, true),
                username = "wallet-or-pool-user",
                worker = "",
            ),
        )
        assertFalse(result.valid)
        assertTrue(result.errors.any { it.contains("Worker name") })
    }

    @Test
    fun rejectsInvalidPortAndHost() {
        val result = PoolValidator.validate(
            PoolProfile(
                name = "Bad",
                primary = PoolEndpoint("", 70000),
                username = "x",
                worker = "worker",
            ),
        )
        assertFalse(result.valid)
    }
}
