package com.thecryptosteer.xmrminer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DistributionPolicyTest {
    @Test fun distributionFlagsAreInternallyConsistent() {
        if (BuildConfig.DISTRIBUTION_LABEL == "F-Droid") {
            assertFalse(BuildConfig.SELF_UPDATE_ENABLED)
            assertFalse(BuildConfig.INSTALLATION_TELEMETRY_ENABLED)
        } else {
            assertTrue(BuildConfig.SELF_UPDATE_ENABLED)
        }
    }
}
