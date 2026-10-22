package dev.local.rebuy.autobuy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoBuyCheckoutPolicyTest {
    private val profile = AutoBuyProfile(
        itemId = "item", expectedTitle = "익스트림 트리플 아르기닌6200", expectedOption = "600ml,2개",
        maxTotalWon = 70_000, productUrlSnapshot = "https://www.coupang.com/vp/products/1?itemId=2&vendorItemId=3",
        quantitySnapshot = 1,
    )
    private val facts = AutoBuyCheckoutPolicy.CheckoutFacts(
        products = listOf(AutoBuyCheckoutPolicy.Product(profile.expectedTitle, "600ml, 2개", 1)),
        finalTotalCandidates = listOf(67_500), shippingWon = 0, hasCheckoutHeader = true, hasPaymentButton = true,
    )

    @Test fun actualPurchasesRemainDisabledIndependentlyOfPreviewValidation() {
        assertFalse(AutoBuyPolicy.ACTUAL_PURCHASE_ENABLED)
        assertEquals(AutoBuyCheckoutPolicy.Check.Ready(67_500), AutoBuyCheckoutPolicy.inspect(profile, facts))
    }

    @Test fun crossedOutAndDiscountedTotalsAreAmbiguousEvenWhenOneFitsLimit() {
        assertTrue(AutoBuyCheckoutPolicy.inspect(profile, facts.copy(finalTotalCandidates = listOf(75_000, 67_500))) is AutoBuyCheckoutPolicy.Check.Reject)
    }

    @Test fun missingProductRowsOrExtraProductsCannotPass() {
        assertTrue(AutoBuyCheckoutPolicy.inspect(profile, facts.copy(products = emptyList())) is AutoBuyCheckoutPolicy.Check.Reject)
        assertTrue(AutoBuyCheckoutPolicy.inspect(profile, facts.copy(products = facts.products + facts.products)) is AutoBuyCheckoutPolicy.Check.Reject)
    }

    @Test fun exactOptionAndCartUnitCountMustMatch() {
        assertTrue(AutoBuyCheckoutPolicy.inspect(profile, facts.copy(products = listOf(facts.products.single().copy(option = "600ml,3개")))) is AutoBuyCheckoutPolicy.Check.Reject)
        assertTrue(AutoBuyCheckoutPolicy.inspect(profile, facts.copy(products = listOf(facts.products.single().copy(quantity = 2)))) is AutoBuyCheckoutPolicy.Check.Reject)
    }

    @Test fun finalTotalMustIncludeKnownShippingAndFitCap() {
        assertTrue(AutoBuyCheckoutPolicy.inspect(profile, facts.copy(shippingWon = null)) is AutoBuyCheckoutPolicy.Check.Reject)
        assertTrue(AutoBuyCheckoutPolicy.inspect(profile, facts.copy(finalTotalCandidates = listOf(70_001))) is AutoBuyCheckoutPolicy.Check.Reject)
        assertTrue(AutoBuyCheckoutPolicy.inspect(profile, facts.copy(finalTotalCandidates = listOf(0))) is AutoBuyCheckoutPolicy.Check.Reject)
    }

    @Test fun checkoutAndPaymentButtonAreRequiredAsReadOnlyMarkers() {
        assertTrue(AutoBuyCheckoutPolicy.inspect(profile, facts.copy(hasCheckoutHeader = false)) is AutoBuyCheckoutPolicy.Check.Reject)
        assertTrue(AutoBuyCheckoutPolicy.inspect(profile, facts.copy(hasPaymentButton = false)) is AutoBuyCheckoutPolicy.Check.Reject)
    }

    @Test fun wonParserRejectsNegativePartialMixedAndOverflowValues() {
        assertEquals(67_500L, AutoBuyCheckoutPolicy.parseWon("67,500원"))
        assertEquals(0L, AutoBuyCheckoutPolicy.parseWon("0원"))
        listOf("-7,500원", "총 67,500원", "67,500원 할인", "67,50원", "9999999999999999999999원").forEach {
            assertNull(it, AutoBuyCheckoutPolicy.parseWon(it))
        }
    }
}
