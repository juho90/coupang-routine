package dev.local.rebuy.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.local.rebuy.importproduct.ProductImageFixtureProvider
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Injected clipboard-image source only: the user's real clipboard is never read or written. */
@RunWith(AndroidJUnit4::class)
class ProductImageClipboardDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun clipboardImageIsReadOnlyOnClickAndOcrDraftRequiresExplicitApply() {
        val reads = AtomicInteger()
        val generation = mutableIntStateOf(0)
        val parent = mutableStateOf(EXISTING_FORM)
        val applied = mutableListOf<ProductImageDraft>()
        assertEquals(0, reads.get())
        compose.setContent {
            MaterialTheme {
                Column {
                    Text("재구성 ${generation.intValue}")
                    Text(parent.value.title)
                    ProductImageImport(
                        enabled = true,
                        includeOption = generation.intValue > 0,
                        readClipboardImage = {
                            reads.incrementAndGet()
                            ProductImageFixtureProvider.imageUri()
                        },
                        onApply = { draft -> applied += draft; parent.value = draft },
                    )
                }
            }
        }
        compose.runOnIdle {
            assertEquals(0, reads.get())
            generation.intValue += 1
        }
        compose.onNodeWithText("재구성 1").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("Recomposition must not read a clipboard image", 0, reads.get())
            assertEquals(EXISTING_FORM, parent.value)
            assertTrue(applied.isEmpty())
        }

        compose.onNodeWithTag("product-image-paste").assertIsEnabled().performClick()
        waitForOcrReview()
        compose.runOnIdle {
            assertEquals(1, reads.get())
            assertTrue("Recognizing an image must not apply it to the form", applied.isEmpty())
            assertEquals(EXISTING_FORM, parent.value)
        }
        val recognizedTitle = editableValue("product-image-title")
        val recognizedOption = editableValue("product-image-option")
        assertTrue("The actual bundled Korean OCR must populate the review title", compact(recognizedTitle).contains("익스트림트리플아르기닌"))
        assertTrue("The actual OCR must populate the pack option", compact(recognizedOption).let { it.contains("600ml") && it.contains("2개") })
        compose.onNodeWithTag("product-image-price-none").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("product-image-url").assertDoesNotExist()
        compose.runOnIdle { assertTrue(applied.isEmpty()) }

        compose.onNodeWithTag("product-image-apply").assertIsEnabled().performClick()

        compose.onNodeWithTag("product-image-review").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, reads.get())
            assertEquals(listOf(ProductImageDraft(recognizedTitle.trim(), recognizedOption.trim(), null, null)), applied)
            assertEquals(applied.single(), parent.value)
        }
    }

    @Test fun textOnlyClipboardErrorLeavesTheExistingFormUntouched() {
        assertFailurePreservesForm(
            failure = IllegalArgumentException("클립보드에 이미지가 없습니다. 상품 이미지를 복사한 뒤 다시 시도해 주세요."),
            expectedWords = listOf("이미지", "복사"),
        )
    }

    @Test fun clipboardAccessFailureAsksForRecopyAndPreservesTheForm() {
        assertFailurePreservesForm(
            failure = SecurityException("Synthetic clipboard access denied"),
            expectedWords = listOf("다시", "복사"),
        )
    }

    @Test fun disabledPasteButtonDoesNotReadAnImageEvenWhenTouched() {
        val reads = AtomicInteger()
        val applied = mutableListOf<ProductImageDraft>()
        compose.setContent {
            MaterialTheme {
                Column {
                    ProductImageImport(
                        enabled = false,
                        readClipboardImage = { reads.incrementAndGet(); ProductImageFixtureProvider.imageUri() },
                        onApply = { applied += it },
                    )
                }
            }
        }
        compose.onNodeWithTag("product-image-paste").assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(0, reads.get())
            assertTrue(applied.isEmpty())
        }
        compose.onNodeWithTag("product-image-review").assertDoesNotExist()
    }

    private fun assertFailurePreservesForm(failure: RuntimeException, expectedWords: List<String>) {
        val reads = AtomicInteger()
        val generation = mutableIntStateOf(0)
        val parent = mutableStateOf(EXISTING_FORM)
        val applied = mutableListOf<ProductImageDraft>()
        compose.setContent {
            MaterialTheme {
                Column {
                    Text("재구성 ${generation.intValue}")
                    Text(parent.value.title)
                    ProductImageImport(
                        enabled = true,
                        includeOption = generation.intValue > 0,
                        readClipboardImage = { reads.incrementAndGet(); throw failure },
                        onApply = { draft -> applied += draft; parent.value = draft },
                    )
                }
            }
        }
        compose.runOnIdle { assertEquals(0, reads.get()); generation.intValue += 1 }
        compose.onNodeWithText("재구성 1").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, reads.get()) }
        compose.onNodeWithTag("product-image-paste").performClick()
        compose.onNodeWithTag("product-image-error").assertIsDisplayed()
        val error = compose.onNodeWithTag("product-image-error").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
        expectedWords.forEach { word -> assertTrue("Clipboard error should explain recovery: $error", error.contains(word)) }
        compose.onNodeWithTag("product-image-review").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, reads.get())
            assertTrue(applied.isEmpty())
            assertEquals(EXISTING_FORM, parent.value)
        }
    }

    private fun waitForOcrReview() {
        compose.waitUntil(60_000) {
            if (compose.onAllNodesWithTag("product-image-error").fetchSemanticsNodes().isNotEmpty()) {
                throw AssertionError("The fixed clipboard image did not reach OCR review.\n${compose.onRoot(useUnmergedTree = true).printToString()}")
            }
            compose.onAllNodesWithTag("product-image-review").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun editableValue(tag: String): String = compose.onNodeWithTag(tag)
        .fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun compact(value: String): String = value.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

    private companion object {
        val EXISTING_FORM = ProductImageDraft("기존 상품명", "기존 옵션", 33_300, "https://www.coupang.com/vp/products/123?itemId=456&vendorItemId=789")
    }
}
