package com.thecryptosteer.xmrminer.domain

object DonationMath {
    const val BASIS_POINTS = 200L
    const val TOTAL_BASIS_POINTS = 10_000L
    const val USER_TO_DONATION_RATIO = 49L
    const val DONATION_QUANTUM_NANOS = 60_000_000_000L

    fun maximumDonationForUserTime(userNanos: Long): Long = (userNanos.coerceAtLeast(0L) / USER_TO_DONATION_RATIO)

    fun mayStartQuantum(userNanos: Long, donationNanos: Long): Boolean {
        if (userNanos < 49L * DONATION_QUANTUM_NANOS) return false
        return donationNanos <= Long.MAX_VALUE - DONATION_QUANTUM_NANOS &&
            donationNanos + DONATION_QUANTUM_NANOS <= maximumDonationForUserTime(userNanos)
    }

    fun percent(userNanos: Long, donationNanos: Long): Double {
        val user = userNanos.coerceAtLeast(0L).toDouble()
        val donation = donationNanos.coerceAtLeast(0L).toDouble()
        val total = user + donation
        return if (total <= 0.0) 0.0 else donation * 100.0 / total
    }

    fun invariantHolds(userNanos: Long, donationNanos: Long): Boolean {
        if (userNanos < 0 || donationNanos < 0) return false
        val user = userNanos.toBigInteger()
        val donation = donationNanos.toBigInteger()
        val total = user + donation
        if (total.signum() == 0) return true
        return donation * TOTAL_BASIS_POINTS.toBigInteger() <= total * BASIS_POINTS.toBigInteger()
    }
}
