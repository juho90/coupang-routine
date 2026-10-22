package dev.local.rebuy.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.room.withTransaction
import dev.local.rebuy.MainActivity
import dev.local.rebuy.RebuyApplication
import dev.local.rebuy.data.RoutineItem
import dev.local.rebuy.domain.CompletionResult
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on an emulator/device: form -> Room -> confirmation -> history -> replay guard. */
@RunWith(AndroidJUnit4::class)
class RoutineFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun registerAndConfirmPurchasePersistsExactlyOneCycle() {
        val title = "구매 흐름 테스트 ${System.nanoTime()}"
        val container = (compose.activity.application as RebuyApplication).container
        var created: RoutineItem? = null
        try {
            compose.openRoutineList()
            compose.onNodeWithTag("add-product").assertIsDisplayed().performClick()
            compose.onNodeWithText("상품명").performScrollTo().performTextInput(title)
            compose.onNodeWithText("쿠팡 상품 URL").performScrollTo().performTextInput("https://www.coupang.com/vp/products/123456789")
            compose.onNodeWithText("구매 수량").performScrollTo().performTextClearance()
            compose.onNodeWithText("구매 수량").performTextInput("2")
            compose.onNodeWithText("구매 주기").performScrollTo().performTextClearance()
            compose.onNodeWithText("구매 주기").performTextInput("7")
            // AlertDialog's save action is outside its scrolling form body.
            compose.onNodeWithText("저장").assertIsDisplayed().performClick()
            created = runBlocking {
                withTimeout(10_000) { container.repository.items.first { items -> items.any { it.title == title } }.single { it.title == title } }
            }
            compose.onNodeWithTag("routine-list").performScrollToNode(hasText(title))
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
            }
            val original = requireNotNull(created)
            assertEquals(2, original.quantity)
            assertEquals(7, original.intervalDays)
            val earliestNextDate = LocalDate.now().plusDays(7).toEpochDay()
            val card = hasTestTag("routine-card") and hasAnyDescendant(hasText(title))
            val completeButton = compose.onNode(hasText("구매 완료") and hasAnyAncestor(card))
            // Room emits the saved row before reminder reconciliation finishes. The button
            // deliberately remains disabled during that work, so wait for the actual UI state.
            compose.waitUntil(5_000) {
                runCatching { completeButton.assertIsEnabled(); true }.getOrDefault(false)
            }
            // On this compact landscape screen the save snackbar overlaps the card's
            // touch target. Wait for that visible feedback to clear before a physical tap.
            compose.waitUntil(7_000) {
                compose.onAllNodesWithText("상품을 저장했습니다", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
            }
            completeButton.performScrollTo().assertIsEnabled().performClick()
            // The platform Dialog gets its own window after the Compose state changes.
            // Wait for that window to be measured and visible before acting on it.
            try {
                compose.waitUntil(5_000) {
                    runCatching {
                        compose.onNodeWithText("구매를 완료했나요?").assertIsDisplayed()
                        true
                    }.getOrDefault(false)
                }
            } catch (failure: Throwable) {
                throw AssertionError(compose.onRoot(useUnmergedTree = true).printToString(), failure)
            }
            compose.onNodeWithText("구매 완료 기록").performClick()
            compose.waitUntil(10_000) {
                runBlocking { container.repository.getItem(original.id)?.cycleId != original.cycleId }
            }
            compose.onNodeWithText("구매 이력").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(title).assertIsDisplayed()
            runBlocking {
                val updated = requireNotNull(container.repository.getItem(original.id))
                assertNotEquals(original.cycleId, updated.cycleId)
                assertTrue(updated.nextPurchaseEpochDay in earliestNextDate..LocalDate.now().plusDays(7).toEpochDay())
                val record = container.repository.history.first().single { it.itemId == original.id }
                assertEquals(2, record.quantity)
                assertEquals(original.cycleId, record.cycleId)
                assertEquals(CompletionResult.AlreadyRecorded, container.repository.completePurchase(original.id, original.cycleId))
                assertEquals(1, container.repository.history.first().count { it.itemId == original.id })
            }
        } finally {
            runBlocking {
                val ownItem = created ?: container.repository.items.first().find { it.title == title }
                ownItem?.let {
                    container.repository.archiveItem(it.id)
                    container.scheduler.cancelNotification(it.id)
                    try {
                        container.scheduler.reconcileAll()
                    } finally {
                        // Remove only this test's UUID-owned data; ordinary app archival
                        // continues to retain all purchase history.
                        container.database.withTransaction {
                            container.database.openHelper.writableDatabase.delete("purchase_records", "itemId = ?", arrayOf(it.id))
                            container.database.openHelper.writableDatabase.delete("routine_items", "id = ?", arrayOf(it.id))
                        }
                    }
                }
            }
        }
    }
}
