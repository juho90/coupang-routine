package dev.local.rebuy.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.local.rebuy.importproduct.ProductTextSuggestions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Review-only checks: no photo picker, OCR, repository, Coupang launch, or purchase runs. */
@RunWith(AndroidJUnit4::class)
class ProductImageReviewDeviceTest {
    @get:Rule val compose = createComposeRule()

    private val fixture = ProductTextSuggestions(
        titleCandidates = listOf("익스트림 트리플 아르기닌6200", "트리플 아르기닌"),
        optionCandidates = listOf("600ml, 2개", "600ml, 1개"),
        priceCandidatesWon = listOf(75_000L, 82_900L),
        productUrls = emptyList(),
    )

    @Test fun recognizedPriceIsNotSelectedAndDraftRequiresExplicitApply() {
        val applied = mutableListOf<ProductImageDraft>()
        compose.setContent {
            MaterialTheme {
                ProductImageReview("default", fixture, enabled = true, onDismiss = {}, onApply = { applied += it })
            }
        }
        compose.onNodeWithTag("product-image-price-none").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("product-image-url").assertDoesNotExist()
        compose.runOnIdle { assertTrue(applied.isEmpty()) }

        compose.onNodeWithTag("product-image-apply").performClick()

        compose.runOnIdle {
            assertEquals(listOf(ProductImageDraft(fixture.titleCandidates.first(), fixture.optionCandidates.first(), null, null)), applied)
        }
    }

    @Test fun reviewedEditsAndExplicitlyChosenPriceAreAppliedWithoutInventingUrl() {
        val applied = mutableListOf<ProductImageDraft>()
        compose.setContent {
            MaterialTheme {
                ProductImageReview("edited", fixture, enabled = true, onDismiss = {}, onApply = { applied += it })
            }
        }
        compose.onNodeWithTag("product-image-title").performScrollTo().performTextClearance()
        compose.onNodeWithTag("product-image-title").performTextInput("검토한 아르기닌 상품명")
        compose.onNodeWithTag("product-image-option").performScrollTo().performTextClearance()
        compose.onNodeWithTag("product-image-option").performTextInput("600ml, 3개")
        compose.onNodeWithTag("product-image-price-82900").performScrollTo().performClick().assertIsSelected()
        compose.runOnIdle { assertTrue(applied.isEmpty()) }

        compose.onNodeWithTag("product-image-apply").performClick()

        compose.runOnIdle {
            assertEquals(listOf(ProductImageDraft("검토한 아르기닌 상품명", "600ml, 3개", 82_900L, null)), applied)
        }
    }

    @Test fun cancellingReviewLeavesParentFormUntouched() {
        val applied = mutableListOf<ProductImageDraft>()
        val visible = mutableStateOf(true)
        var dismissed = 0
        compose.setContent {
            MaterialTheme {
                if (visible.value) ProductImageReview(
                    "cancelled", fixture, enabled = true,
                    onDismiss = { dismissed += 1; visible.value = false },
                    onApply = { applied += it },
                )
            }
        }
        compose.onNodeWithTag("product-image-price-75000").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("product-image-review-cancel").performClick()

        compose.onNodeWithTag("product-image-review").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, dismissed)
            assertTrue(applied.isEmpty())
        }
    }

    @Test fun numberWithoutCurrencyRequiresExplicitConfirmationBeforeCopying() {
        val applied = mutableListOf<ProductImageDraft>()
        val numbersOnly = ProductTextSuggestions(
            titleCandidates = emptyList(), optionCandidates = emptyList(),
            priceCandidatesWon = emptyList(), productUrls = emptyList(),
            unconfirmedNumbers = listOf(67_500L),
        )
        compose.setContent {
            MaterialTheme {
                ProductImageReview("unconfirmed-number", numbersOnly, enabled = true, onDismiss = {}, onApply = { applied += it })
            }
        }
        compose.onNodeWithTag("product-image-price-none").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("product-image-apply").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(applied.isEmpty()) }

        compose.onNodeWithTag("product-image-number-67500").performScrollTo().performClick().assertIsSelected()
        compose.runOnIdle { assertTrue(applied.isEmpty()) }
        compose.onNodeWithTag("product-image-apply").performClick()

        compose.runOnIdle {
            assertEquals(listOf(ProductImageDraft("", "", 67_500L, null)), applied)
        }
    }
}
