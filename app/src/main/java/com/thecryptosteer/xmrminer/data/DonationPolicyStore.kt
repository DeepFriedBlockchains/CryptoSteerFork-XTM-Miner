package com.thecryptosteer.xmrminer.data

import android.content.Context
import com.thecryptosteer.xmrminer.BuildConfig

class DonationPolicyStore(context: Context) {
    private val preferences = context.getSharedPreferences("donation_policy", Context.MODE_PRIVATE)
    val currentBasisPoints: Int
        get() = preferences.getInt("basis_points", BuildConfig.DONATION_BASIS_POINTS).takeIf { it == BuildConfig.DONATION_BASIS_POINTS || it == BuildConfig.REDUCED_DONATION_BASIS_POINTS } ?: BuildConfig.DONATION_BASIS_POINTS
    val accountActiveUserNanos: Long
        get() = preferences.getLong("account_active_mining_nanos", 0L)
    fun update(accountNanos: Long, basisPoints: Int) {
        val safeBasis = if (basisPoints == BuildConfig.REDUCED_DONATION_BASIS_POINTS) basisPoints else BuildConfig.DONATION_BASIS_POINTS
        preferences.edit().putLong("account_active_mining_nanos", accountNanos.coerceAtLeast(0L)).putInt("basis_points", safeBasis).apply()
    }
}
