package dev.local.rebuy.cart

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class CartAssistControllerGateTest {
    private fun request(url: String = "https://www.coupang.com/vp/products/123456?itemId=333&vendorItemId=444") =
        CartAssistRequest(url, "동원 용기죽 4종 기획세트", "285g × 1세트")

    @Test fun nativeCartSessionAcceptsGenericOrCompleteProductLinksButRejectsPartialOptionIds() {
        assertTrue(CartAssistController.validRequest(request()))
        assertTrue(CartAssistController.validRequest(request("https://www.coupang.com/vp/products/123456")))
        assertTrue(CartAssistController.validRequest(request("https://www.coupang.com/vp/products/123456").copy(verifyOnly = true)))
        for (partial in listOf("?itemId=333", "?vendorItemId=444")) {
            val incomplete = request("https://www.coupang.com/vp/products/123456$partial")
            assertFalse(CartAssistController.validRequest(incomplete))
        }
        assertFalse(CartAssistController.validRequest(request("https://link.coupang.com/a/example")))
    }

    @Test fun publicLinkSupportDoesNotAllowUntrustedUrlsOrMissingIdentity() {
        assertFalse(CartAssistController.validRequest(request("https://coupang.com.evil.example/vp/products/1")))
        assertFalse(CartAssistController.validRequest(request("coupang://product?pId=1&itemId=2&vendorItemId=3")))
        assertFalse(CartAssistController.validRequest(request().copy(expectedTitle = "")))
        assertFalse(CartAssistController.validRequest(request().copy(expectedOption = "")))
    }

    @Test fun acceptedProductLinkStillRequiresAccessibilityBeforeAnyLaunchOrCartSession() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertFalse(CartAssistController.isEnabled(context))
        assertFalse(CartAssistController.start(context, request()))
        assertNull(CartAssistController.currentSession())
        assertTrue(CartAssistController.state.value is CartAssistState.Finished)
        assertNull(Shadows.shadowOf(context as Application).nextStartedActivity)
        assertNull(context.getSharedPreferences("cart_assist_attempt", Context.MODE_PRIVATE).getString("status", null))
    }

    @Test fun verificationRefusesClaimBeforePersistenceAndIndependentServiceGateRefusesAnyAction() {
        val session = CartAssistController.Session("read-only", request().copy(verifyOnly = true), startedAt = 0)
        var writes = 0
        var actions = 0
        assertFalse(session.claimBeforeEffect { writes++; true })
        assertFalse(session.clickClaimed)
        assertEquals(0, writes)
        assertFalse(CartAssistController.claimClick(session))
        // Simulate a future controller bug: the last service boundary must still refuse a click.
        session.clickClaimed = true
        assertFalse(session.performClaimedAction { actions++; true })
        assertEquals(0, actions)
        assertEquals("verified", session.resultStatus(success = true))
        assertEquals("stopped", session.resultStatus(success = false))
    }

    @Test fun explicitAdditionClaimsBeforeActionAndCannotRetryIncludingFailedPersistence() {
        val session = CartAssistController.Session("addition", request(), startedAt = 0)
        val order = mutableListOf<String>()
        assertFalse(session.performClaimedAction { order += "premature"; true })
        assertTrue(session.claimBeforeEffect { order += "persist"; true })
        assertTrue(session.performClaimedAction { order += "action"; true })
        assertFalse(session.claimBeforeEffect { order += "repeat-persist"; true })
        assertFalse(session.performClaimedAction { order += "repeat-action"; true })
        assertEquals(listOf("persist", "action"), order)
        assertEquals("confirmed", session.resultStatus(success = true))

        val diskFailure = CartAssistController.Session("disk-failure", request(), startedAt = 0)
        assertFalse(diskFailure.claimBeforeEffect { false })
        assertFalse(diskFailure.claimBeforeEffect { throw AssertionError("must never retry a claim") })
    }
    @Test fun cappedSessionCannotClaimOrActBeforePriceVerificationAndInvalidCapsNeverDisableGuard() {
        val session = CartAssistController.Session("capped", request().copy(maxTotalWon = 100), startedAt = 0)
        var effects = 0
        assertFalse(session.claimBeforeEffect { effects++; true })
        session.clickClaimed = true
        assertFalse(session.performClaimedAction { effects++; true })
        assertEquals(0, effects)
        assertFalse(CartAssistController.validRequest(request().copy(maxTotalWon = 0)))
        assertFalse(CartAssistController.validRequest(request().copy(maxTotalWon = 100_000_001)))
        assertTrue(CartAssistController.validRequest(request().copy(maxTotalWon = 100)))
    }

    @Test fun recordedAdditionBlocksSingleButBatchReportsRecordedAndUncertainItemsWithoutLaunching() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ledger = CartAdditionLedger(context)
        val recorded = request()
        val uncertain = CartBatchTarget("uncertain", "다른 등록 상품 이름", "https://www.coupang.com/vp/products/12?itemId=21&vendorItemId=22", "600ml, 1개")
        assertTrue(ledger.clear(recorded.productUrl))
        assertTrue(ledger.clear(uncertain.productUrl))
        assertTrue(ledger.claim(recorded.productUrl, "previous-request"))
        assertFalse(CartAssistController.start(context, recorded))
        assertNull(CartAssistController.currentSession())
        assertTrue(ledger.confirm(recorded.productUrl, "previous-request"))
        assertTrue(ledger.claim(uncertain.productUrl, "uncertain-request"))
        val duplicate = CartBatchTarget("recorded", recorded.expectedTitle, recorded.productUrl, recorded.expectedOption)
        assertTrue(CartBatchController.start(context, listOf(duplicate, uncertain)))
        assertFalse(CartBatchController.isRunning())
        assertEquals(listOf(CartBatchEntryStatus.SKIPPED, CartBatchEntryStatus.REVIEW_REQUIRED),
            CartBatchController.state.value.entries.map { it.status })
        assertEquals(0, CartBatchController.state.value.addedCount)
        assertEquals(CartAdditionLedger.Status.CONFIRMED, ledger.lookup(recorded.productUrl)?.status)
        assertEquals(CartAdditionLedger.Status.CLAIMED, ledger.lookup(uncertain.productUrl)?.status)
        assertNull(Shadows.shadowOf(context as Application).nextStartedActivity)
        assertTrue(ledger.clear(recorded.productUrl))
        assertTrue(ledger.clear(uncertain.productUrl))
    }

    @Test fun malformedRecordedAdditionStopsWithoutThrowingOrLaunching() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ledger = CartAdditionLedger(context)
        val request = request()
        assertTrue(ledger.clear(request.productUrl))
        assertTrue(ledger.claim(request.productUrl, "previous-request"))
        val prefs = context.getSharedPreferences("cart_addition_ledger_v1", Context.MODE_PRIVATE)
        val key = prefs.all.keys.single()
        assertTrue(prefs.edit().putString(key, "broken-json").commit())
        assertFalse(CartAssistController.start(context, request))
        assertNull(CartAssistController.currentSession())
        assertNull(Shadows.shadowOf(context as Application).nextStartedActivity)
        assertTrue(CartBatchController.start(context, listOf(CartBatchTarget("broken", request.expectedTitle, request.productUrl, request.expectedOption))))
        assertEquals(CartBatchEntryStatus.REVIEW_REQUIRED, CartBatchController.state.value.entries.single().status)
        assertFalse(CartBatchController.isRunning())
        assertNull(Shadows.shadowOf(context as Application).nextStartedActivity)
        assertTrue(ledger.clear(request.productUrl))
    }

}
