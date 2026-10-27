package dev.local.rebuy.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString

/** Navigation is saved UI state; tests must select their entry tab instead of assuming it. */
internal fun ComposeTestRule.openRoutineList() {
    try {
        waitUntil(10_000) { onAllNodesWithText("구매 목록").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("구매 목록").performClick()
        waitUntil(10_000) { onAllNodesWithTag("add-product").fetchSemanticsNodes().isNotEmpty() }
    } catch (failure: Throwable) {
        val tree = runCatching { onRoot(useUnmergedTree = true).printToString() }
            .getOrElse { "Semantics unavailable: ${it.message}" }
        throw AssertionError("구매 목록의 상품 등록 버튼을 준비하지 못했습니다.\n$tree", failure)
    }
}

/** Manual fixtures still use the explicit fallback after supplying their product URL. */
internal fun ComposeTestRule.openManualProductFields() {
    waitUntil(10_000) {
        onAllNodesWithTag("purchase-option").fetchSemanticsNodes().isNotEmpty() ||
            onAllNodesWithTag("product-details-edit").fetchSemanticsNodes().isNotEmpty()
    }
    if (onAllNodesWithTag("purchase-option").fetchSemanticsNodes().isEmpty()) {
        waitUntil(10_000) {
            runCatching { onNodeWithTag("product-details-edit").assertIsEnabled(); true }.getOrDefault(false)
        }
        onNodeWithTag("product-details-edit").performScrollTo().performClick()
    }
    waitUntil(10_000) { onAllNodesWithTag("purchase-option").fetchSemanticsNodes().isNotEmpty() }
}

/** Inspect/edit only; this helper never starts the external cart operation. */
internal fun ComposeTestRule.openCartProductFields() {
    waitUntil(10_000) {
        onAllNodesWithText("화면에 표시되는 옵션").fetchSemanticsNodes().isNotEmpty() ||
            onAllNodesWithTag("cart-details-edit").fetchSemanticsNodes().isNotEmpty()
    }
    if (onAllNodesWithText("화면에 표시되는 옵션").fetchSemanticsNodes().isEmpty()) {
        onNodeWithTag("cart-details-edit").performScrollTo().performClick()
    }
    waitUntil(10_000) { onAllNodesWithText("화면에 표시되는 옵션").fetchSemanticsNodes().isNotEmpty() }
}
