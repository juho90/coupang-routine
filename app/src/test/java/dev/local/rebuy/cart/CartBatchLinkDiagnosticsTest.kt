package dev.local.rebuy.cart

import dev.local.rebuy.domain.CoupangCartProductLink
import org.junit.Assert.*
import org.junit.Test

class CartBatchLinkDiagnosticsTest {
    private fun target(id: Int, productUrl: String = "https://www.coupang.com/vp/products/$id?itemId=${id}1&vendorItemId=${id}2") =
        CartBatchTarget("item-$id", "확인용 상품 이름 $id", productUrl, "600ml, ${id}개")

    @Test fun genericAndPartialDirectLinksNameTheProductAndRequireItsSelectedOptionLink() {
        val incompleteUrls = listOf(
            "https://www.coupang.com/vp/products/20",
            "https://www.coupang.com/vp/products/20?itemId=201",
            "https://www.coupang.com/vp/products/20?vendorItemId=202",
        )
        for ((index, url) in incompleteUrls.withIndex()) {
            val offending = target(index + 2, url)
            val problem = requireNotNull(CartBatchValidation.directLinkProblem(offending))
            assertTrue(problem.contains(offending.title))
            assertTrue(problem.contains(CoupangCartProductLink.EXACT_OPTION_REQUIRED_MESSAGE))
            val error = runCatching { CartBatchValidation.prepare(listOf(target(1), offending)) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertTrue(error?.message.orEmpty().contains(offending.title))
            assertTrue(error?.message.orEmpty().contains(CoupangCartProductLink.EXACT_OPTION_REQUIRED_MESSAGE))
        }
    }

    @Test fun completeOptionIdsRemainAcceptedAndCanonicalized() {
        val original = target(7, "https://m.coupang.com/vm/products/123/?vendorItemId=456&itemId=789&utm_source=test")
        assertNull(CartBatchValidation.directLinkProblem(original))
        val prepared = CartBatchValidation.prepare(listOf(original)).single()
        assertEquals("https://www.coupang.com/vp/products/123?itemId=789&vendorItemId=456", prepared.productUrl)
        assertEquals(original.copy(productUrl = prepared.productUrl), prepared)
        val parsed = CoupangCartProductLink.parseExactOption(prepared.productUrl)
        assertEquals("789", parsed.itemId)
        assertEquals("456", parsed.vendorItemId)
    }

    @Test fun sharedLinkIsDeferredByDirectValidationButUnresolvedPreparationNamesTheProduct() {
        val shared = target(8, "https://link.coupang.com/a/example")
        assertNull(CartBatchValidation.directLinkProblem(shared))
        val error = runCatching { CartBatchValidation.prepare(listOf(shared)) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains(shared.title))
        val reason = "공유 링크를 상품 주소로 확인하지 못했어요."
        val named = CartBatchValidation.namedLinkProblem(shared, reason)
        assertTrue(named.contains(shared.title))
        assertTrue(named.contains(reason))
    }

    @Test fun missingOrMalformedAddressNamesTheOffendingProductWithoutMutatingInput() {
        for ((index, url) in listOf("", "not a URL", "https://www.coupang.com/vp/products/not-an-id").withIndex()) {
            val first = target(1).copy(title = "  확인용 정상 상품  ", option = "  600ml, 1개  ")
            val offending = target(index + 2, url)
            val targets = mutableListOf(first, offending)
            val before = targets.toList()
            val error = runCatching { CartBatchValidation.prepare(targets) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertTrue(error?.message.orEmpty().contains(offending.title))
            assertEquals(before, targets)
            assertSame(first, targets.first())
            assertSame(offending, targets.last())
        }
    }
}
