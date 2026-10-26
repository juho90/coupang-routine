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
class CartAssistDiagnosticsTest {
    @Test fun structuralFlagsIdentifyMissingResourceIdsWithoutWeakeningTheDetailGate() {
        val bottom = CartAssistPolicy.BOTTOM_BUTTONS_ID
        val nodes = listOf(
            CartAssistPolicy.Node(resourceId = bottom),
            CartAssistPolicy.Node(resourceId = CartAssistPolicy.BUTTON_LAYOUT_ID),
            CartAssistPolicy.Node(resourceId = CartAssistPolicy.PREFIX + "web_layout"),
            CartAssistPolicy.Node(text = "장바구니 담기", resourceId = CartAssistPolicy.CART_TEXT_ID, ancestorIds = listOf(bottom)),
            CartAssistPolicy.Node(text = "바로구매", resourceId = CartAssistPolicy.CART_TEXT_ID, ancestorIds = listOf(bottom)),
        )
        assertTrue(CartAssistPolicy.detailStructure(nodes).complete)
        val noResourceIds = nodes.map { it.copy(resourceId = "") }
        assertEquals(CartDetailStructure(), CartAssistPolicy.detailStructure(noResourceIds))
        assertFalse(CartAssistPolicy.isDetailScreen(noResourceIds))
        val missingContent = nodes.filterNot { it.resourceId in CartAssistPolicy.DETAIL_CONTENT_IDS }
        val flags = CartAssistPolicy.detailStructure(missingContent)
        assertTrue(flags.bottomContainer && flags.buttonContainer && flags.cartLabel && flags.buyLabel)
        assertFalse(flags.detailContent)
        assertFalse(CartAssistPolicy.isDetailScreen(missingContent))
    }

    @Test fun terminalReasonPreservesLastActiveStageAndClaimDoesNotMeanAcceptedClick() {
        val waiting = CartAssistDiagnostics().atStage(CartAssistStage.WAITING_DETAIL_SCREEN)
        val timedOut = waiting.atStage(CartAssistStage.TIMED_OUT, CartAssistStopReason.TIMEOUT)
        assertEquals(CartAssistStage.WAITING_DETAIL_SCREEN, timedOut.lastActiveStage)
        assertEquals(CartAssistStopReason.TIMEOUT, timedOut.stopReason)
        listOf(CartAssistStopReason.USER_CANCEL, CartAssistStopReason.ACCESSIBILITY_INTERRUPTED,
            CartAssistStopReason.SERVICE_DISCONNECTED).forEach { reason ->
            val stopped = waiting.atStage(CartAssistStage.CANCELLED, reason)
            assertEquals(reason, stopped.stopReason)
            assertEquals(CartAssistStage.WAITING_DETAIL_SCREEN, stopped.lastActiveStage)
        }
        val session = CartAssistController.Session("diagnostic-test",
            CartAssistRequest("https://www.coupang.com/vp/products/1", "동원 용기죽", "285g x 1세트"), startedAt = 0)
        assertTrue(session.claimBeforeEffect { true })
        assertTrue(session.clickClaimed)
        assertNull(session.clickAccepted)
        assertFalse(session.performClaimedAction { false })
        assertEquals(false, session.clickAccepted)
        assertFalse(session.performClaimedAction { throw AssertionError("must not retry rejected action") })
    }

    @Test fun restoresFinishedOrInterruptedSummaryWithoutRestoringAnActionAndHandlesLegacyUnknowns() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("cart_assist_attempt", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("status", "stopped").putString("stage", "TIMED_OUT")
            .putString("last_active_stage", "WAITING_DETAIL_SCREEN").putString("stop_reason", "TIMEOUT")
            .putInt("scanned_nodes", 600).putInt("resource_id_nodes", 0).putBoolean("scan_truncated", true)
            .putBoolean("structure_present", true).putBoolean("structure_content", true)
            .putBoolean("click_claimed", true).putBoolean("click_accepted", false).commit()
        CartAssistController.restoreDiagnostics(context)
        val restored = CartAssistController.diagnostics.value
        assertEquals(CartAssistStage.TIMED_OUT, restored.stage)
        assertEquals(CartAssistStage.WAITING_DETAIL_SCREEN, restored.lastActiveStage)
        assertEquals(CartAssistStopReason.TIMEOUT, restored.stopReason)
        assertEquals(600, restored.scannedNodeCount)
        assertEquals(0, restored.resourceIdNodeCount)
        assertTrue(restored.scanTruncated)
        assertEquals(CartDetailStructure(detailContent = true), restored.detailStructure)
        assertTrue(restored.clickClaimed)
        assertTrue(restored.hasClickRecord)
        assertEquals(false, restored.clickAccepted)

        prefs.edit().clear().putString("status", "armed").putString("stage", "WAITING_TITLE").commit()
        CartAssistController.restoreDiagnostics(context)
        assertEquals(CartAssistStage.CANCELLED, CartAssistController.diagnostics.value.stage)
        assertEquals(CartAssistStopReason.SERVICE_DISCONNECTED, CartAssistController.diagnostics.value.stopReason)
        assertNull(CartAssistController.diagnostics.value.detailStructure)
        assertNull(CartAssistController.diagnostics.value.lastActiveStage)
        assertNull(CartAssistController.diagnostics.value.clickAccepted)
        assertFalse(CartAssistController.diagnostics.value.hasClickRecord)
        prefs.edit().clear().putString("status", "stopped").putString("stage", "TIMED_OUT")
            .putString("stop_reason", "future_unknown_reason").commit()
        CartAssistController.restoreDiagnostics(context)
        assertEquals(CartAssistStopReason.TIMEOUT, CartAssistController.diagnostics.value.stopReason)
        assertFalse(CartAssistController.diagnostics.value.hasClickRecord)
        assertNull(CartAssistController.currentSession())
        assertNull(Shadows.shadowOf(context as Application).nextStartedActivity)
    }
}
