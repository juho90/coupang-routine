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

    @Test fun nativeCartSessionRequiresResolvedProductUrlButAllowsMissingOptionIds() {
        assertTrue(CartAssistController.validRequest(request()))
        assertTrue(CartAssistController.validRequest(request("https://www.coupang.com/vp/products/123456")))
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
}
