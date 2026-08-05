package com.thecryptosteer.xmrminer.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.thecryptosteer.xmrminer.domain.MiningSnapshot
import com.thecryptosteer.xmrminer.domain.MiningState
import org.junit.Rule
import org.junit.Test

class CryptoSteerUiSmokeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun stoppedDashboardShowsRealPrimaryActionAndEmptyPoolState() {
        composeRule.setContent {
            CryptoSteerTheme(darkTheme = true) {
                DashboardScreen(
                    snapshot = MiningSnapshot(state = MiningState.STOPPED, updatedAtMillis = System.currentTimeMillis()),
                    profiles = emptyList(),
                    history = emptyList(),
                    logs = emptyList(),
                    onStart = { _, callback -> callback(Result.success(Unit)) },
                    onPause = {},
                    onResume = {},
                    onStop = {},
                    onOpenProfiles = {},
                    onOpenMining = {},
                )
            }
        }
        composeRule.onNodeWithText("Add pool").assertExists()
        composeRule.onNodeWithText("No pool profile").assertExists()
        composeRule.onNodeWithText("Live hashrate").assertExists()
    }

    @Test
    fun activeMiningUsesNaturalStatusCopyInLightTheme() {
        composeRule.setContent {
            CryptoSteerTheme(darkTheme = false) {
                MiningDetailsScreen(
                    snapshot = MiningSnapshot(
                        state = MiningState.MINING_USER,
                        statusMessage = "pool job active",
                        hashrate = 1_234.5,
                        averageHashrate = 1_200.0,
                        workers = 8,
                        poolConnected = true,
                        jobReceived = true,
                        updatedAtMillis = System.currentTimeMillis(),
                    ),
                    logs = emptyList(),
                    onOpenLogs = {},
                )
            }
        }
        composeRule.onNodeWithText("Mining").assertExists()
        composeRule.onNodeWithText("Active session").assertExists()
        composeRule.onNodeWithText("No active errors").assertExists()
    }
}
