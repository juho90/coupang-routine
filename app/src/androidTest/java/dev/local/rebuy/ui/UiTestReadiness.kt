package dev.local.rebuy.ui

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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
