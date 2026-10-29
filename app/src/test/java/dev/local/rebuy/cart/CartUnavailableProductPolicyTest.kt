package dev.local.rebuy.cart

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class CartUnavailableProductPolicyTest {
    private val content = listOf(CartAssistPolicy.PREFIX + "web_layout", CartAssistPolicy.PREFIX + "recycler_view")
    private val notice = CartAssistPolicy.Node(text = "현재 판매중인 상품이 아닙니다", ancestorIds = content)

    @Test fun exactUnavailableNoticeInCurrentDetailDoesNotNeedAnEnabledCartButton() {
        assertTrue(CartAssistPolicy.isUnavailableProduct(listOf(notice)))
        assertTrue(CartAssistPolicy.isUnavailableProduct(listOf(notice.copy(text = "현재 판매 중인 상품이 아닙니다."))))
        assertFalse(CartAssistPolicy.isDetailScreen(listOf(notice)))
    }

    @Test fun hiddenOutsideRecommendedAndRankingNoticesCannotClassifyTheProductAsUnavailable() {
        for (other in listOf(
            notice.copy(visible = false),
            notice.copy(ancestorIds = emptyList()),
            notice.copy(ancestorIds = content + "recommendation_list"),
            notice.copy(ancestorIds = content + "ranking_layout"),
        )) assertFalse(CartAssistPolicy.isUnavailableProduct(listOf(other)))
    }

    @Test fun soldOutWordAndOtherProductDescriptionsDoNotSupplyTheExactNotice() {
        for (text in listOf("품절", "일시 품절", "이전에 판매 중인 상품이 아닙니다", "현재 판매중인 상품이 아닙니다 안내를 참고하세요")) {
            assertFalse(CartAssistPolicy.isUnavailableProduct(listOf(notice.copy(text = text))))
        }
    }

    @Test fun unavailableResultIsImpossibleAfterAnyClaimOrActionAndForSuccessfulOrReadOnlyRequests() {
        val request = CartAssistRequest("https://www.coupang.com/vp/products/123?itemId=456&vendorItemId=789", "확인용 상품 이름", "600ml, 1개")
        val pristine = CartAssistController.Session("pristine", request, startedAt = 0)
        assertTrue(pristine.canReportUnavailable(success = false))
        assertFalse(pristine.canReportUnavailable(success = true))
        assertFalse(CartAssistController.Session("read-only", request.copy(verifyOnly = true), startedAt = 0).canReportUnavailable(false))
        val claimed = CartAssistController.Session("failed-claim", request, startedAt = 0)
        assertFalse(claimed.claimBeforeEffect { false })
        assertFalse(claimed.canReportUnavailable(false))
        val acted = CartAssistController.Session("acted", request, startedAt = 0)
        assertTrue(acted.claimBeforeEffect { true })
        assertFalse(acted.performClaimedAction { false })
        acted.clickClaimed = false
        assertFalse(acted.canReportUnavailable(false))
    }
}
