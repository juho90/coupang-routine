package dev.local.rebuy.autobuy

import androidx.test.core.app.ApplicationProvider
import dev.local.rebuy.data.RoutineItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AutoBuyControllerGateTest {
    @Before fun setMain() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun resetMain() { Dispatchers.resetMain() }

    @Test fun actualExecutionIsBlockedBeforeAccessibilityOrAttemptLookupOrAppLaunch() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val item = RoutineItem(title = "상품", productUrl = "https://www.coupang.com/vp/products/1?itemId=2&vendorItemId=3", quantity = 1, intervalDays = 7, nextPurchaseEpochDay = 0)
        val profile = AutoBuyProfile(item.id, "화면 상품명", "600ml,2개", 70_000, item.productUrl, 1)
        // No saved profile, attempt, service permission, or native app is needed: the gate is first.
        val outcome = AutoBuyController.execute(context, item, profile, false, "does-not-exist")
        assertTrue(outcome is AutoBuyOutcome.Blocked)
        assertFalse(AutoBuyController.checkoutAdapterVerified)
        assertFalse(AutoBuyController.isDryRunVerified(profile))
        assertTrue(org.robolectric.Shadows.shadowOf(context as android.app.Application).nextStartedActivity == null)
    }
}
