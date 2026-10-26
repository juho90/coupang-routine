package dev.local.rebuy.ui

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.local.rebuy.AppContainer
import dev.local.rebuy.MainActivity
import dev.local.rebuy.RebuyApplication
import dev.local.rebuy.autobuy.AutoBuyPolicy
import dev.local.rebuy.autobuy.AutoBuyStore
import dev.local.rebuy.data.RoutineItem
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Local form/Room/read-only condition integration. No cart or checkout action is started. */
@RunWith(AndroidJUnit4::class)
class IntegratedConditionsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun oneRegistrationPersistsConditionsAndEditingUpdatesTheSameItem() {
        val title = "조건 통합 ${UUID.randomUUID()}"
        val container = (compose.activity.application as RebuyApplication).container
        var created: RoutineItem? = null
        try {
            openNewProduct(title)
            compose.onNodeWithTag("purchase-option").performScrollTo().performTextInput("600ml, 2개")
            compose.onNodeWithTag("purchase-limit").performScrollTo().performTextInput("75000")
            compose.onNodeWithText("저장").assertIsEnabled().performClick()

            val original = awaitItem(container, title) { it.purchaseOption == "600ml, 2개" && it.maxTotalWon == 75_000L }
            created = original
            assertEquals(PRODUCT_URL, original.productUrl)
            assertEquals(1, original.quantity)
            assertEquals("600ml, 2개", original.purchaseOption)
            assertEquals(75_000L, original.maxTotalWon)

            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("purchase-option").fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithTag("routine-list").performScrollToNode(hasText(title))
            val card = hasTestTag("routine-card") and hasAnyDescendant(hasText(title))
            val conditionsButton = compose.onNode(hasText("구매 조건 · 결제 전 시험") and hasAnyAncestor(card))
            awaitCardAction(conditionsButton)
            conditionsButton.performScrollTo().performClick()

            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("saved-purchase-option").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("saved-purchase-option").performScrollTo()
                .assertIsDisplayed().assertTextContains("600ml, 2개").assert(!hasSetTextAction())
            compose.onNodeWithTag("saved-purchase-limit").performScrollTo()
                .assertIsDisplayed().assertTextContains("75,000원").assert(!hasSetTextAction())
            assertNoExternalExecution(container, original.id)

            compose.onNodeWithTag("edit-purchase-conditions").performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("purchase-option").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("상품 수정").assertIsDisplayed()
            compose.onNodeWithTag("purchase-option").performScrollTo().assertTextContains("600ml, 2개")
            compose.onNodeWithTag("purchase-limit").performScrollTo().assertTextContains("75000")
            compose.onNodeWithTag("purchase-option").performScrollTo().performTextClearance()
            compose.onNodeWithTag("purchase-option").performTextInput("600ml, 3개")
            compose.onNodeWithTag("purchase-limit").performScrollTo().performTextClearance()
            compose.onNodeWithTag("purchase-limit").performTextInput("82900")
            compose.onNodeWithText("저장").assertIsEnabled().performClick()

            val updated = awaitItem(container, title) { it.purchaseOption == "600ml, 3개" && it.maxTotalWon == 82_900L }
            assertEquals(original.id, updated.id)
            assertNotEquals(original.cycleId, updated.cycleId)
            assertEquals(original.title, updated.title)
            assertEquals(original.productUrl, updated.productUrl)
            assertEquals(original.quantity, updated.quantity)
            assertEquals(original.intervalDays, updated.intervalDays)
            assertEquals(original.nextPurchaseEpochDay, updated.nextPurchaseEpochDay)
            runBlocking {
                assertEquals(1, container.repository.items.first().count { it.title == title })
            }
            assertNoExternalExecution(container, original.id)
        } finally {
            cleanupOwnItem(container, title, created?.id)
        }
    }

    @Test fun missingOptionAndInvalidLimitsKeepTheFormWithoutCreatingAnItem() {
        val title = "조건 검증 ${UUID.randomUUID()}"
        val container = (compose.activity.application as RebuyApplication).container
        try {
            openNewProduct(title)
            compose.onNodeWithTag("purchase-limit").performScrollTo().performTextInput("75000")
            rejectSaveAndCheckNoItem(container, title)

            compose.onNodeWithTag("purchase-option").performScrollTo().performTextInput("600ml, 2개")
            compose.onNodeWithTag("purchase-limit").performScrollTo().performTextClearance()
            compose.onNodeWithTag("purchase-limit").performTextInput("0")
            rejectSaveAndCheckNoItem(container, title)

            compose.onNodeWithTag("purchase-limit").performScrollTo().performTextClearance()
            compose.onNodeWithTag("purchase-limit").performTextInput("-1")
            compose.onNodeWithTag("purchase-limit").assertTextContains("-1")
            rejectSaveAndCheckNoItem(container, title)

            compose.onNodeWithTag("purchase-limit").performScrollTo().performTextClearance()
            compose.onNodeWithTag("purchase-limit").performTextInput("100000001")
            rejectSaveAndCheckNoItem(container, title)
            compose.onNodeWithTag("purchase-limit").performScrollTo().performTextClearance()
            compose.onNodeWithTag("purchase-limit").performTextInput("1000000000")
            compose.onNodeWithTag("purchase-limit").assertTextContains("1000000000")
            rejectSaveAndCheckNoItem(container, title)
            compose.onNodeWithText("취소").performClick()
        } finally {
            cleanupOwnItem(container, title, null)
        }
    }

    private fun openNewProduct(title: String) {
        compose.openRoutineList()
        compose.onNodeWithTag("add-product").assertIsDisplayed().performClick()
        compose.onNodeWithText("상품명").performScrollTo().performTextInput(title)
        compose.onNodeWithText("쿠팡 상품 URL").performScrollTo().performTextInput(PRODUCT_URL)
    }

    private fun awaitItem(container: AppContainer, title: String, condition: (RoutineItem) -> Boolean): RoutineItem = runBlocking {
        withTimeout(10_000) {
            container.repository.items.first { items -> items.any { it.title == title && condition(it) } }
                .single { it.title == title }
        }
    }

    private fun awaitCardAction(button: SemanticsNodeInteraction) {
        compose.waitUntil(10_000) { runCatching { button.assertIsEnabled(); true }.getOrDefault(false) }
        // The save snackbar can cover the card on a small landscape emulator.
        compose.waitUntil(7_000) {
            compose.onAllNodesWithText("상품을 저장했습니다", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun rejectSaveAndCheckNoItem(container: AppContainer, title: String) {
        compose.onNodeWithText("저장").assertIsEnabled().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("item-editor-error").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("item-editor-error").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("상품명").performScrollTo().assertTextContains(title)
        runBlocking { assertFalse(container.repository.items.first().any { it.title == title }) }
    }

    private fun assertNoExternalExecution(container: AppContainer, itemId: String) {
        assertFalse(AutoBuyPolicy.ACTUAL_PURCHASE_ENABLED)
        compose.runOnIdle { assertEquals(Lifecycle.State.RESUMED, compose.activity.lifecycle.currentState) }
        runBlocking {
            assertTrue(container.repository.history.first().none { it.itemId == itemId })
            assertTrue(AutoBuyStore(compose.activity).attempts.first().none { it.itemId == itemId })
        }
    }

    private fun cleanupOwnItem(container: AppContainer, title: String, id: String?) = runBlocking {
        val own = id?.let { container.repository.getItem(it) }
            ?: container.repository.items.first().find { it.title == title }
        own?.let { item ->
            container.repository.archiveItem(item.id)
            container.scheduler.cancelNotification(item.id)
            try {
                container.scheduler.reconcileAll()
            } finally {
                container.database.withTransaction {
                    container.database.openHelper.writableDatabase.delete("purchase_records", "itemId = ?", arrayOf(item.id))
                    container.database.openHelper.writableDatabase.delete("routine_items", "id = ?", arrayOf(item.id))
                }
            }
        }
    }

    private companion object {
        const val PRODUCT_URL = "https://www.coupang.com/vp/products/7157272883?itemId=21389601649&vendorItemId=91294601197"
    }
}
