package dev.local.rebuy.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opens the local cart form only; never presses its start button or opens Coupang. */
@RunWith(AndroidJUnit4::class)
class CartAssistPrefillDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun registeredOptionPrefillsCartAndTemporaryEditsNeverReplaceStoredConditions() {
        val title = "담기 옵션 재사용 ${UUID.randomUUID()}"
        val container = (compose.activity.application as RebuyApplication).container
        var created: RoutineItem? = null
        try {
            compose.openRoutineList()
            compose.onNodeWithTag("add-product").assertIsDisplayed().performClick()
            compose.onNodeWithTag("product-url").performScrollTo().performTextInput(PRODUCT_URL)
            compose.openManualProductFields()
            compose.onNodeWithText("상품명").performScrollTo().performTextInput(title)
            compose.onNodeWithTag("purchase-option").performScrollTo().performTextInput(SAVED_OPTION)
            compose.onNodeWithTag("purchase-limit").performScrollTo().performTextInput("75000")
            compose.onNodeWithText("구매 수량").performScrollTo().performTextClearance()
            compose.onNodeWithText("구매 수량").performTextInput("1")
            compose.onNodeWithText("저장").assertIsEnabled().performClick()
            val original = runBlocking {
                withTimeout(10_000) {
                    container.repository.items.first { items -> items.any { it.title == title } }
                        .single { it.title == title }
                }
            }
            created = original
            assertEquals(SAVED_OPTION, original.purchaseOption)
            assertEquals(75_000L, original.maxTotalWon)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("purchase-option").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("routine-list").performScrollToNode(hasText(title))
            val ownCard = hasTestTag("routine-card") and hasAnyDescendant(hasText(title))
            val cartButton = compose.onNode(hasText("장바구니 담기") and hasAnyAncestor(ownCard))
            compose.waitUntil(10_000) { runCatching { cartButton.assertIsEnabled(); true }.getOrDefault(false) }
            compose.waitUntil(7_000) {
                compose.onAllNodesWithText("상품을 저장했습니다", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
            }
            cartButton.performScrollTo().performClick()
            compose.openCartProductFields()
            compose.onNodeWithText("화면에 표시되는 옵션").performScrollTo().assertIsDisplayed()
            assertEquals(SAVED_OPTION, optionValue())

            compose.onNodeWithText("화면에 표시되는 옵션").performTextClearance()
            compose.onNodeWithText("화면에 표시되는 옵션").performTextInput("600ml, 9개 (시험에서만)")
            assertEquals("600ml, 9개 (시험에서만)", optionValue())
            runBlocking { assertEquals(original, container.repository.getItem(original.id)) }
            compose.onNodeWithText("닫기").performClick()

            // Reopening uses the registered source value, rather than a previous trial's draft.
            cartButton.performScrollTo().assertIsEnabled().performClick()
            compose.openCartProductFields()
            compose.onNodeWithText("화면에 표시되는 옵션").performScrollTo()
            assertEquals(SAVED_OPTION, optionValue())
            compose.onNodeWithText("닫기").performClick()
            assertFalse(AutoBuyPolicy.ACTUAL_PURCHASE_ENABLED)
            compose.runOnIdle { assertEquals(Lifecycle.State.RESUMED, compose.activity.lifecycle.currentState) }
            runBlocking {
                assertEquals(original, container.repository.getItem(original.id))
                assertTrue(container.repository.history.first().none { it.itemId == original.id })
                assertTrue(AutoBuyStore(compose.activity).attempts.first().none { it.itemId == original.id })
            }
        } finally {
            runCatching { compose.onNodeWithText("닫기").performClick() }
            runBlocking {
                val own = created?.id?.let { container.repository.getItem(it) }
                    ?: container.repository.items.first().find { it.title == title }
                own?.let { item ->
                    container.repository.archiveItem(item.id)
                    container.scheduler.cancelNotification(item.id)
                    try { container.scheduler.reconcileAll() }
                    finally {
                        container.database.withTransaction {
                            container.database.openHelper.writableDatabase.delete("purchase_records", "itemId = ?", arrayOf(item.id))
                            container.database.openHelper.writableDatabase.delete("routine_items", "id = ?", arrayOf(item.id))
                        }
                    }
                }
            }
        }
    }

    private fun optionValue(): String = compose.onNodeWithText("화면에 표시되는 옵션")
        .fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private companion object {
        const val SAVED_OPTION = "285g × 1세트"
        const val PRODUCT_URL = "https://www.coupang.com/vp/products/7157272883?itemId=21389601649&vendorItemId=91294601197"
    }
}
