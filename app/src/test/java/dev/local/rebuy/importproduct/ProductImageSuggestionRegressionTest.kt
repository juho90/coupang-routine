package dev.local.rebuy.importproduct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A synthetic OCR layout based on the supplied screenshot, not a recognizer mock success. */
class ProductImageSuggestionRegressionTest {
    @Test fun layoutAndParserChooseFullTitleAndCompletePackAsUneditedDefaults() {
        fun row(text: String, left: Int, top: Int, right: Int) =
            ProductOcrLayout.Line(text, ProductOcrLayout.Box(left, top, right, top + 22))
        val title = "동원 용기죽 4종 기획세트 쇠고기3+야채3+참치3+전복3"
        val lines = listOf(
            row("동원", 10, 10, 70),
            row("브랜드 상품 모아보기", 10, 40, 230),
            row(title, 10, 85, 760),
            row("원산지: 상품 상세설명 참조", 10, 120, 360),
            row("개당 중량 × 수량", 10, 170, 210),
            row("285g", 220, 171, 285),
            row("×", 295, 170, 315),
            row("1세트", 325, 172, 395),
            row("26,980원", 10, 220, 180),
            row("쿠팡이 배송하고 주식회사 더가온(이)가 판매합니다.", 10, 275, 760),
        )
        val result = ProductTextParser.parse(ProductOcrLayout.linesForParsing(lines))
        assertEquals(title, result.titleCandidates.first())
        assertEquals("285g×1세트", result.optionCandidates.first().replace(" ", ""))
        assertTrue(result.productUrls.isEmpty())
    }
}
