package dev.local.rebuy.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.Lifecycle
import dev.local.rebuy.MainActivity
import dev.local.rebuy.RebuyApplication
import dev.local.rebuy.importproduct.ProductImageFixture
import dev.local.rebuy.pcimport.PcImageInbox
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Generated PNG -> own-UID inbox -> singleTop ACTION_SEND -> real OCR -> explicit draft review. */
@RunWith(AndroidJUnit4::class)
class PcImageIncomingDeviceTest {
    // ActivityScenario 1.6.1 compares every lifecycle callback with this exact Intent
    // reference. Keep only its filter fields in sync when a real singleTop SEND changes
    // Activity.getIntent(); otherwise Scenario ignores RESUMED/DESTROYED after delivery.
    private val trackedLaunchIntent = Intent(Intent.ACTION_MAIN)
        .setClass(ApplicationProvider.getApplicationContext<android.content.Context>(), MainActivity::class.java)

    @get:Rule val compose = AndroidComposeTestRule(
        activityRule = ActivityScenarioRule<MainActivity>(trackedLaunchIntent),
        activityProvider = { rule ->
            lateinit var activity: MainActivity
            rule.scenario.onActivity { activity = it }
            activity
        },
    )

    @Test fun incomingImageKeepsAnOpenUnsavedDraftAndAppliesOnlyReviewedFields() {
        val token = UUID.randomUUID().toString().lowercase(Locale.ROOT)
        val uri = PcImageInbox.uriFor(token)
        val original = draft("PC 이미지 적용 ${token.take(8)}")
        try {
            prepareUnsavedDraft(original)
            val initialRows = rowCount("routine_items")
            val initialHistory = rowCount("purchase_records")
            val originalActivity = compose.activity
            writeSyntheticImageAndAwaitReady(uri, token)

            sendImage(uri)
            waitForReview()

            assertSame("singleTop image delivery must keep the editor's Activity", originalActivity, compose.activity)
            assertDraft(original)
            assertEquals(initialRows, rowCount("routine_items"))
            assertEquals(initialHistory, rowCount("purchase_records"))
            val readTitle = editableTag("product-image-title")
            val readOption = editableTag("product-image-option")
            assertTrue(compact(readTitle).contains("익스트림트리플아르기닌"))
            assertTrue(compact(readOption).let { it.contains("600ml") && it.contains("2개") })
            compose.onNodeWithTag("product-image-price-none").performScrollTo().assertIsSelected()
            compose.onNodeWithTag("product-image-url").assertDoesNotExist()

            compose.onNodeWithTag("product-image-apply").assertIsEnabled().performClick()

            compose.onNodeWithTag("product-image-review").assertDoesNotExist()
            assertDraft(original.copy(title = readTitle.trim(), option = readOption.trim()))
            assertEquals("Applying recognized text must not save a product", initialRows, rowCount("routine_items"))
            assertEquals(initialHistory, rowCount("purchase_records"))
            assertSourceReleased(uri, token)
        } finally {
            runCatching { PcImageInbox.release(compose.activity, uri) }
            closeUnsavedDialogs()
        }
    }

    @Test fun cancellingIncomingImageReviewPreservesEveryDraftFieldAndReleasesThePrivateSource() {
        val token = UUID.randomUUID().toString().lowercase(Locale.ROOT)
        val uri = PcImageInbox.uriFor(token)
        val original = draft("PC 이미지 취소 ${token.take(8)}")
        try {
            prepareUnsavedDraft(original)
            val initialRows = rowCount("routine_items")
            val initialHistory = rowCount("purchase_records")
            writeSyntheticImageAndAwaitReady(uri, token)

            sendImage(uri)
            waitForReview()
            assertDraft(original)
            compose.onNodeWithTag("product-image-review-cancel").performClick()

            compose.onNodeWithTag("product-image-review").assertDoesNotExist()
            assertDraft(original)
            assertEquals(initialRows, rowCount("routine_items"))
            assertEquals(initialHistory, rowCount("purchase_records"))
            assertSourceReleased(uri, token)
        } finally {
            runCatching { PcImageInbox.release(compose.activity, uri) }
            closeUnsavedDialogs()
        }
    }

    @Test fun activityRecreationRetainsTheDraftReviewEditsAndSinglePendingImageUntilCancel() {
        val token = UUID.randomUUID().toString().lowercase(Locale.ROOT)
        val uri = PcImageInbox.uriFor(token)
        val original = draft("PC 이미지 회전 ${token.take(8)}")
        val editedTitle = "회전 중 검토한 상품명"
        val editedOption = "600ml, 4개"
        try {
            prepareUnsavedDraft(original)
            val initialRows = rowCount("routine_items")
            val initialHistory = rowCount("purchase_records")
            writeSyntheticImageAndAwaitReady(uri, token)
            sendImage(uri)
            waitForReview()
            compose.onNodeWithTag("product-image-title").performScrollTo().performTextClearance()
            compose.onNodeWithTag("product-image-title").performTextInput(editedTitle)
            compose.onNodeWithTag("product-image-option").performScrollTo().performTextClearance()
            compose.onNodeWithTag("product-image-option").performTextInput(editedOption)
            assertEquals("READY", inboxStatus(uri, token))
            val previousActivity = compose.activity

            compose.activityRule.scenario.recreate()
            waitForReview()

            assertNotSame(previousActivity, compose.activity)
            assertEquals("A consumed intent must not create a second review after recreation", 1,
                compose.onAllNodesWithTag("product-image-review").fetchSemanticsNodes().size)
            assertEquals(editedTitle, editableTag("product-image-title"))
            assertEquals(editedOption, editableTag("product-image-option"))
            assertEquals("Recreation must not release a still-reviewed private image", "READY", inboxStatus(uri, token))
            assertDraft(original)
            assertEquals(initialRows, rowCount("routine_items"))
            assertEquals(initialHistory, rowCount("purchase_records"))

            compose.onNodeWithTag("product-image-review-cancel").performClick()
            assertDraft(original)
            assertSourceReleased(uri, token)
        } finally {
            runCatching { PcImageInbox.release(compose.activity, uri) }
            closeUnsavedDialogs()
        }
    }

    private fun prepareUnsavedDraft(draft: EditorDraft) {
        compose.openRoutineList()
        compose.onNodeWithTag("add-product").assertIsDisplayed().performClick()
        compose.onNodeWithTag("product-url").performScrollTo().performTextInput(draft.url)
        compose.openManualProductFields()
        compose.onNodeWithText("상품명").performScrollTo().performTextInput(draft.title)
        compose.onNodeWithTag("purchase-option").performScrollTo().performTextInput(draft.option)
        compose.onNodeWithTag("purchase-limit").performScrollTo().performTextInput(draft.limit)
        compose.onNodeWithText("구매 수량").performScrollTo().performTextClearance()
        compose.onNodeWithText("구매 수량").performTextInput(draft.quantity)
        compose.onNodeWithText("구매 주기").performScrollTo().performTextClearance()
        compose.onNodeWithText("구매 주기").performTextInput(draft.interval)
        assertDraft(draft)
    }

    private fun writeSyntheticImageAndAwaitReady(uri: Uri, token: String) {
        val bytes = ProductImageFixture.pngBytes()
        requireNotNull(compose.activity.contentResolver.openOutputStream(uri, "w")).use { output ->
            output.write(bytes)
        }
        runBlocking {
            withTimeout(10_000) {
                while (true) {
                    when (val status = inboxStatus(uri, token)) {
                        "READY" -> return@withTimeout
                        "REJECTED" -> throw AssertionError("The fixed generated PNG was rejected by the inbox")
                        "MISSING", "WRITING" -> delay(25)
                        else -> throw AssertionError("Unexpected generated-image inbox status: $status")
                    }
                }
            }
        }
    }

    private fun sendImage(uri: Uri) {
        compose.runOnIdle {
            val activity = compose.activity
            val delivery = Intent(Intent.ACTION_SEND)
                .setClass(activity, MainActivity::class.java)
                .setType("image/png")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            // Update the test harness's identity, not the app's Intent or its image extras.
            trackedLaunchIntent.action = delivery.action
            trackedLaunchIntent.setDataAndType(delivery.data, delivery.type)
            trackedLaunchIntent.component = delivery.component
            trackedLaunchIntent.setPackage(delivery.`package`)
            trackedLaunchIntent.categories?.toList()?.forEach { trackedLaunchIntent.removeCategory(it) }
            delivery.categories?.forEach { trackedLaunchIntent.addCategory(it) }
            activity.startActivity(delivery)
        }
    }

    private fun waitForReview() {
        compose.waitUntil(60_000) {
            if (compose.onAllNodesWithTag("product-image-error").fetchSemanticsNodes().isNotEmpty()) {
                throw AssertionError("The fixed PC inbox image did not reach OCR review.\n${compose.onRoot(useUnmergedTree = true).printToString()}")
            }
            compose.onAllNodesWithTag("product-image-review").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(5_000) { compose.activityRule.scenario.state == Lifecycle.State.RESUMED }
    }

    private fun assertSourceReleased(uri: Uri, token: String) {
        runBlocking {
            withTimeout(5_000) { while (inboxStatus(uri, token) != "MISSING") delay(25) }
        }
        val canRead = runCatching {
            compose.activity.contentResolver.openInputStream(uri)?.use { it.read(); true } == true
        }.getOrDefault(false)
        assertFalse("Released inbox images must no longer be readable", canRead)
    }

    private fun inboxStatus(uri: Uri, token: String): String = compose.activity.contentResolver
        .call(uri, "status", token, null)?.getString("status") ?: "MISSING"

    private fun assertDraft(expected: EditorDraft) {
        assertEquals(expected.title, editableLabel("상품명"))
        assertEquals(expected.url, editableLabel("쿠팡 상품 URL"))
        assertEquals(expected.option, editableTag("purchase-option"))
        assertEquals(expected.limit, editableTag("purchase-limit"))
        assertEquals(expected.quantity, editableLabel("구매 수량"))
        assertEquals(expected.interval, editableLabel("구매 주기"))
    }

    private fun editableLabel(label: String): String = compose.onNodeWithText(label)
        .fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun editableTag(tag: String): String = compose.onNodeWithTag(tag)
        .fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun rowCount(table: String): Long {
        check(table == "routine_items" || table == "purchase_records")
        val database = (compose.activity.application as RebuyApplication).container.database
        return database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }
    }

    private fun closeUnsavedDialogs() {
        runCatching {
            if (compose.onAllNodesWithTag("product-image-review-cancel").fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithTag("product-image-review-cancel").performClick()
            }
            compose.onNodeWithText("취소").performClick()
        }
    }

    private fun compact(value: String): String = value.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

    private fun draft(title: String) = EditorDraft(title,
        "https://www.coupang.com/vp/products/123456789?itemId=333&vendorItemId=444", "기존 옵션 3개", "88888", "3", "14")

    private data class EditorDraft(val title: String, val url: String, val option: String, val limit: String, val quantity: String, val interval: String)
}
