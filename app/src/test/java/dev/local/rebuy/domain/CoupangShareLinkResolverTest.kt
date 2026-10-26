package dev.local.rebuy.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CoupangShareLinkResolverTest {
    private val share = "https://link.coupang.com/a/h4NNZza6N2"

    @Test fun ordinaryObservedRedirectProducesAProductWithOnlyItsActualIds() {
        val policy = CoupangShareLinkResolver.NavigationPolicy()
        assertNull(policy.visit(share))
        assertNull(policy.visit("https://link.coupang.com/re/example"))
        assertEquals(
            "coupang://product?pId=9750360880&itemId=29195429057",
            policy.visit("https://m.coupang.com/vm/products/9750360880?itemId=29195429057")!!.nativeUri,
        )
    }

    @Test fun duplicateWebViewCallbacksDoNotSpendTheRedirectBudgetButLoopsFail() {
        val policy = CoupangShareLinkResolver.NavigationPolicy()
        repeat(10) { assertNull(policy.visit(share)) }
        assertNull(policy.visit("https://link.coupang.com/re/next"))
        assertThrows(IllegalArgumentException::class.java) { policy.visit(share) }
    }

    @Test fun productDestinationIsIncludedInTheSixRedirectLimit() {
        val allowed = CoupangShareLinkResolver.NavigationPolicy()
        assertNull(allowed.visit(share))
        repeat(5) { assertNull(allowed.visit("https://link.coupang.com/re/$it")) }
        assertEquals("123", allowed.visit("https://www.coupang.com/vp/products/123")!!.productId)

        val excess = CoupangShareLinkResolver.NavigationPolicy()
        assertNull(excess.visit(share))
        repeat(6) { assertNull(excess.visit("https://link.coupang.com/re/$it")) }
        assertThrows(IllegalArgumentException::class.java) { excess.visit("https://www.coupang.com/vp/products/123") }
    }

    @Test fun foreignHttpFileAndIntentNavigationIsNeverAccepted() {
        listOf(
            "http://www.coupang.com/vp/products/1", "https://coupang.com.evil.example/vp/products/1",
            "file:///sdcard/page.html", "content://provider/1", "javascript:alert(1)",
            "intent://product?pId=1#Intent;scheme=coupang;end",
        ).forEach { raw ->
            assertThrows(raw, IllegalArgumentException::class.java) {
                CoupangShareLinkResolver.NavigationPolicy().visit(raw)
            }
        }
    }

    @Test fun onlyShareOrProductCanStartAndResourcesRemainHttpsOnCoupangDomains() {
        val policy = CoupangShareLinkResolver.NavigationPolicy()
        assertTrue(policy.isShareOrProduct(share))
        assertTrue(policy.isShareOrProduct("https://www.coupang.com/vp/products/123"))
        assertFalse(policy.isShareOrProduct("https://www.coupang.com/login"))
        assertFalse(policy.isShareOrProduct("http://link.coupang.com/a/test"))
        assertTrue(CoupangShareLinkResolver.NavigationPolicy.allowedResource("https://static.coupangcdn.com/script.js"))
        listOf("http://static.coupangcdn.com/s.js", "https://evil.example/s.js", "file:///tmp/s.js",
            "https://static.coupangcdn.com.evil.example/s.js").forEach { raw ->
            assertFalse(raw, CoupangShareLinkResolver.NavigationPolicy.allowedResource(raw))
        }
    }
}
