package dev.local.rebuy.importproduct

import org.junit.Assert.*
import org.junit.Test

class ProductTextParserTest {
    @Test fun eggDetailUsesTitleImmediatelyBeforeOriginInsteadOfCategoryRank() {
        val title = "곰곰 촉촉 반숙란 (냉장), 15구, 1개"
        val result = ProductTextParser.parse(listOf(
            "구운계란/훈제란 구매 2위", "한 달간 30만명 이상 구매했어요",
            title, "원산지: 상품 상세설명 참조", "15구, 1개", "8,990원",
        ))
        assertEquals(listOf(title), result.titleCandidates)
        assertEquals(listOf("15구, 1개"), result.optionCandidates)
        assertFalse(result.titleCandidates.any { it.contains("구매 2위") })
    }

    @Test fun uniqueOriginAnchorOutranksLongerTextEvenWhenTitleWasSeenEarlier() {
        val title = "곰곰 촉촉 반숙란"
        val longer = "아주 길고 상세하게 적힌 계란 포장 내용과 보관 방법을 포함하는 다른 문구"
        val result = ProductTextParser.parse(listOf(
            title, longer, "제조사: 포장업체", title, "원산지： 상품 상세설명 참조",
        ))
        assertEquals(title, result.titleCandidates.first())
        assertTrue(result.titleCandidates.contains(longer))
    }

    @Test fun rankAndMetadataNeverBecomeTitlesThroughAnOriginAnchor() {
        val result = ProductTextParser.parse(listOf(
            "구운계란/훈제란 구매 2위", "원산지: 상품 상세설명 참조",
            "판매자: 계란 판매처", "원산지: 상품 상세설명 참조", "곰곰 촉촉 반숙란",
        ))
        assertEquals(listOf("곰곰 촉촉 반숙란"), result.titleCandidates)
    }

    @Test fun wideCoupangScreenshotPrefersFullProductTitleAndCompleteLabeledOption() {
        val title = "동원 용기죽 4종 기획세트 쇠고기3+야채3+참치3+전복3"
        val result = ProductTextParser.parse(listOf(
            "양반 쇠고기죽", "양반 참치죽", "양반 전복죽", "양반 야채죽",
            "동원", "브랜드 상품 모아보기", title,
            "원산지: 상품 상세설명 참조", "쿠팡이 배송하고 주식회사 더가온(이)가 판매합니다.",
            "개당 중량 × 수량 285g × 1세트", "내일 도착 보장", "리뷰 12,345개",
        ))
        assertEquals(title, result.titleCandidates.first())
        assertEquals(listOf("285g × 1세트"), result.optionCandidates)
        assertFalse(result.titleCandidates.any { it.contains("모아보기") || it.contains("원산지") || it.contains("판매합니다") || it.contains("리뷰") || it.contains("도착") })
        // Short names remain usable alternatives; length is not a validity requirement.
        assertTrue(result.titleCandidates.contains("양반 참치죽"))
        assertEquals(listOf("햇반"), ProductTextParser.parse(listOf("햇반")).titleCandidates)
    }

    @Test fun labeledCapacityCountPairAcceptsExplicitMultipliersAndBoundedLineBreaks() {
        val result = ProductTextParser.parse(listOf(
            "개당 중량 x 수량 285gx1세트",
            "개당 용량 × 수량", "600ml", "×", "2개",
            "개당 무게 × 수량", "1.5kg ×", "3봉",
        ))
        assertEquals(listOf("285g x 1세트", "600ml × 2개", "1.5kg × 3봉"), result.optionCandidates)
        assertTrue(result.titleCandidates.isEmpty())
        assertEquals(listOf("285gx1세트"), ProductTextParser.parse(listOf("285gx1세트")).optionCandidates)
    }

    @Test fun incompleteLabeledPairsNeverInventAUnitOrAMultiplierOrCrossOtherText() {
        val badUnit = ProductTextParser.parse(listOf("개당 중량 × 수량", "2859", "×", "1세트"))
        assertTrue(badUnit.optionCandidates.isEmpty())
        assertFalse(badUnit.optionCandidates.any { it.contains("285g") })
        assertTrue(ProductTextParser.parse(listOf("개당 중량 × 수량", "285g", "1세트")).optionCandidates.isEmpty())

        val separated = ProductTextParser.parse(listOf(
            "개당 중량 × 수량", "285g", "추천 상품 다른 용기죽", "×", "1세트",
        ))
        assertFalse(separated.optionCandidates.any { it.contains("285g") && it.contains("1세트") })
        assertTrue(separated.titleCandidates.contains("추천 상품 다른 용기죽"))
        // A bare neighboring weight/count without a labeled field is not joined.
        val unrelated = ProductTextParser.parse(listOf("285g", "×", "1세트"))
        assertEquals(listOf("285g", "1세트"), unrelated.optionCandidates)
        val otherOption = ProductTextParser.parse(listOf("개당 중량 × 수량", "285g", "500g × 2개"))
        assertEquals(listOf("500g × 2개"), otherOption.optionCandidates)
    }

    @Test fun fullProductTitleAfterManyPackagingCandidatesStillParticipatesInRanking() {
        val title = "동원 용기죽 4종 기획세트 쇠고기3+야채3+참치3+전복3"
        val result = ProductTextParser.parse((1..20).map { "양반 죽 포장 $it" } + listOf("동원", "브랜드 상품 모아보기", title, "원산지: 상세설명 참조"))
        assertEquals(title, result.titleCandidates.first())
        assertEquals(12, result.titleCandidates.size)
    }

    @Test fun extractsVisibleTitlePackagingAndSalePricesWithoutChoosingOne() {
        val result = ProductTextParser.parse(listOf(
            "익스트림 트리플 아르기닌 6200",
            "옵션: 600ml, 2개",
            "25% 100,000원 75,000원",
            "최대 3,750원 적립",
            "장바구니 담기", "바로구매", "내일(금) 도착 보장",
        ))
        assertEquals(listOf("익스트림 트리플 아르기닌 6200"), result.titleCandidates)
        assertEquals(listOf("600ml, 2개"), result.optionCandidates)
        assertEquals(listOf(100_000L, 75_000L), result.priceCandidatesWon)
        assertTrue(result.productUrls.isEmpty())
    }

    @Test fun normalizesFullWidthNumbersKoreanWhitespaceAndDeduplicates() {
        val result = ProductTextParser.parse(listOf(
            "  데일리\u00a0\u00a0비타민   C  ", "데일리 비타민 C",
            "구성： １박스（３０포）", "구성: 1박스(30포)", "판매가 ７５，０００원", "₩75,000",
        ))
        assertEquals(listOf("데일리 비타민 C"), result.titleCandidates)
        assertEquals(listOf("1박스(30포)"), result.optionCandidates)
        assertEquals(listOf(75_000L), result.priceCandidatesWon)
    }

    @Test fun recognizesCapacityAndPackagingInsideProductTitle() {
        val result = ProductTextParser.parse(listOf(
            "다우니 실내건조 섬유유연제 1.5L, 3개",
            "500g × 2팩", "리필 1kg, 4봉", "색상: 화이트 / 대형",
        ))
        assertEquals(listOf("다우니 실내건조 섬유유연제 1.5L, 3개"), result.titleCandidates)
        assertEquals(listOf("1.5L, 3개", "500g × 2팩", "리필 1kg, 4봉", "화이트 / 대형"), result.optionCandidates)
    }

    @Test fun separatesTotalPriceFromInstallmentsCashPointsDeliveryAndUnitPrices() {
        val result = ProductTextParser.parse(listOf(
            "무이자 12개월 월 6,250원",
            "쿠팡캐시 3,750원 적립",
            "포인트 500원", "쿠폰 5,000원", "2,000원 할인",
            "배송비 3,000원", "100g당 1,250원", "1개월당 9,900원",
            "상품가 75,000원 | 배송비 3,000원 | 총 결제금액 78,000원",
        ))
        assertEquals(listOf(75_000L, 78_000L), result.priceCandidatesWon)
        assertTrue(result.optionCandidates.isEmpty())
    }

    @Test fun priceParsingRequiresExplicitCurrencyAndValidIntegerGrouping() {
        val result = ProductTextParser.parse(listOf(
            "15%", "75000", "상품번호 7157272883", "평점 4.8", "12,34원", "12.50원",
            "0원", "100,000,001원", "9999999999999999999999999원",
            "KRW 49,900", "₩ 50,000", "판매가: 49900원",
        ))
        assertEquals(listOf(49_900L, 50_000L), result.priceCandidatesWon)
    }

    @Test fun extractsOnlyVisibleValidatedCoupangHttpsUrlsWithoutInventingIds() {
        val product = "https://www.coupang.com/vp/products/7157272883?itemId=21389601649&vendorItemId=91294601197"
        val share = "https://link.coupang.com/a/h1yJnHQWjY"
        val result = ProductTextParser.parse(listOf(
            "상품번호: 7157272883", "itemId: 21389601649", "vendorItemId: 91294601197",
            "공유 주소: [$share]", product, product,
            "https://www.coupang.com.evil.example/vp/products/1",
            "https://user@www.coupang.com/vp/products/1",
            "http://www.coupang.com/vp/products/1", "coupang://product?pId=1",
            "www.coupang.com/vp/products/123",
            "https://www.coupang.com/vp/products/1%00",
            "https://www.coupang.com:443/vp/products/1",
        ))
        assertEquals(listOf(share, product), result.productUrls)
    }

    @Test fun doesNotRepairOrJoinUrlsOrGuessAProductFromItsTitle() {
        val result = ProductTextParser.parse(listOf(
            "익스트림 트리플 아르기닌6200", "600ml, 2개",
            "https://www.cou", "pang.com/vp/products/7157272883",
            "https://www.c0upang.com/vp/products/7157272883",
            "ｈｔｔｐｓ：／／ｗｗｗ．ｃｏｕｐａｎｇ．ｃｏｍ／ｖｐ／ｐｒｏｄｕｃｔｓ／１",
        ))
        assertTrue(result.productUrls.isEmpty())
    }

    @Test fun retainsMultiplePriceCandidatesAndRejectsNonPricesOnTheSameLine() {
        val result = ProductTextParser.parse(listOf(
            "20% 50,000원 40,000원 최대 2,000원 적립",
            "월 10,000원 / 총 60,000원",
        ))
        assertEquals(listOf(50_000L, 40_000L, 60_000L), result.priceCandidatesWon)
    }

    @Test fun boundsLinesFieldsOutputAndNeverTruncatesALongUrlIntoAValidPrefix() {
        val longLine = "https://www.coupang.com/vp/products/1?padding=" + "a".repeat(5000)
        val manyLines = (1..300).map { "상품이름 $it" }
        val result = ProductTextParser.parse(listOf(longLine) + manyLines)
        assertEquals(12, result.titleCandidates.size)
        assertTrue(result.productUrls.isEmpty())
        assertFalse(result.titleCandidates.any { it == "상품이름 201" })
        assertTrue(ProductTextParser.parse(emptyList()).titleCandidates.isEmpty())
        assertTrue(ProductTextParser.parse(listOf("a".repeat(151))).titleCandidates.isEmpty())
    }

    @Test fun ignoresRatingsAndUiLabelsInsteadOfSuggestingThemAsProductNames() {
        val result = ProductTextParser.parse(listOf(
            "쿠팡", "로켓 배송", "옵션 선택", "4.8 (3,251)", "수량", "추천상품", "상세정보",
            "무료배송", "와우회원", "상품평 100개", "쿠폰 받기", "옵션을 선택해 주세요",
        ))
        assertTrue(result.titleCandidates.isEmpty())
        assertTrue(result.optionCandidates.isEmpty())
        assertTrue(result.priceCandidatesWon.isEmpty())
    }

    @Test fun missingCurrencyRemainsAnUnconfirmedNumberInsteadOfBecomingAPrice() {
        val result = ProductTextParser.parse(listOf(
            "익스트림 트리플 아르기닌6200", "600ml, 2개", "75,000l", "67,500", "82,500I", "90,000|",
        ))
        assertEquals(listOf(75_000L, 67_500L, 82_500L, 90_000L), result.unconfirmedNumbers)
        assertTrue(result.priceCandidatesWon.isEmpty())
        assertEquals(listOf("600ml, 2개"), result.optionCandidates)
    }

    @Test fun explicitlyPrintedCurrencyKeepsMoneySemanticsAndRemovesUnconfirmedDuplicates() {
        val result = ProductTextParser.parse(listOf(
            "75,000l", "67,500", "판매가 75,000원", "₩67,500", "82,500", "82,500",
        ))
        assertEquals(listOf(75_000L, 67_500L), result.priceCandidatesWon)
        assertEquals(listOf(82_500L), result.unconfirmedNumbers)
    }

    @Test fun unknownNumbersRequireAnIsolatedCommaGroupedLineAndNeverComeFromLabelsOrUnits() {
        val result = ProductTextParser.parse(listOf(
            "67500", "평점 4.8 (67,500)", "리뷰 67,500", "67,500개", "상품번호 67,500",
            "https://www.coupang.com/vp/products/67,500", "67,500%", "67,500ml", "67,500 g",
            "포인트 67,500", "67,500P", "67,500ab", "12,34", "0,100", "100,000,001",
            "999999999999999999999999", "500", "1,000", "100,000,000",
        ))
        assertEquals(listOf(1_000L, 100_000_000L), result.unconfirmedNumbers)
        assertTrue(result.priceCandidatesWon.isEmpty())
    }
}
