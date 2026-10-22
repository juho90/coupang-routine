package dev.local.rebuy.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.local.rebuy.MainActivity
import dev.local.rebuy.autobuy.AutoBuyController
import dev.local.rebuy.autobuy.AutoBuyOutcome
import dev.local.rebuy.autobuy.AutoBuyPolicy
import dev.local.rebuy.autobuy.AutoBuyProfile
import dev.local.rebuy.data.RoutineItem
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Device checks for the user's explicit prohibition on making purchases. No Coupang action runs. */
@RunWith(AndroidJUnit4::class)
class AutoBuySafetyDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun actualExecutionIsBlockedBeforeOpeningCoupang() {
        val item = RoutineItem(
            title = "실제 구매 차단 시험",
            productUrl = "https://www.coupang.com/vp/products/123?itemId=456&vendorItemId=789",
            quantity = 1, intervalDays = 7, nextPurchaseEpochDay = LocalDate.now().toEpochDay(),
        )
        val profile = AutoBuyProfile(
            itemId = item.id, expectedTitle = item.title, expectedOption = "1개",
            maxTotalWon = 1, productUrlSnapshot = item.productUrl, quantitySnapshot = 1,
        )
        assertFalse(AutoBuyPolicy.ACTUAL_PURCHASE_ENABLED)
        val outcome = runBlocking(Dispatchers.Main) {
            AutoBuyController.execute(compose.activity, item, profile, false, "unclaimed-test-only")
        }
        assertTrue(outcome is AutoBuyOutcome.Blocked)
        compose.runOnIdle {
            assertEquals(Lifecycle.State.RESUMED, compose.activity.lifecycle.currentState)
        }
    }

    @Test fun previewSettingsExplicitlyShowThatPaymentIsDisabled() {
        compose.openRoutineList()
        compose.onNodeWithText("설정").performClick()
        compose.onNodeWithText("결제 전 시험 설정").performScrollTo().performClick()
        compose.onNodeWithText("실제 결제 비활성화").assertIsDisplayed()
        compose.onNodeWithText("닫기").performClick()
    }
}
