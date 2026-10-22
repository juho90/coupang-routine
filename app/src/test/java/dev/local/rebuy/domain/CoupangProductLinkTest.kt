package dev.local.rebuy.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Test

class CoupangProductLinkTest {
    private val exact = "https://www.coupang.com/vp/products/7157272883?itemId=21389601649&vendorItemId=91294601197"

    @Test
    fun preservesExactOptionAndUsesCaseSensitiveNativeProductParameter() {
        val result = CoupangProductLink.parse("$exact&sourceType=cmgoms#details")
        assertEquals(exact, result.canonicalUrl)
        assertEquals("7157272883", result.productId)
        assertEquals("21389601649", result.itemId)
        assertEquals("91294601197", result.vendorItemId)
        assertEquals(
            "coupang://product?pId=7157272883&itemId=21389601649&vendorItemId=91294601197",
            result.nativeUri,
        )
    }

    @Test
    fun acceptsReorderedIdsAndNormalizesHost() {
        assertEquals(exact, CoupangProductLink.parse(
            " HTTPS://COUPANG.COM/vp/products/7157272883/?vendorItemId=91294601197&itemId=21389601649 ",
        ).canonicalUrl)
    }

    @Test
    fun rejectsMissingAmbiguousOrNonNumericOptions() {
        listOf(
            "https://www.coupang.com/vp/products/1",
            "https://www.coupang.com/vp/products/1?itemId=2",
            "$exact&itemId=21389601649",
            "$exact&vendorItemId=1",
            "$exact&%69temId=1",
            "https://www.coupang.com/vp/products/1?itemId=2%26buy%3D1&vendorItemId=3",
            "https://www.coupang.com/vp/products/1?itemId=+2&vendorItemId=3",
            "https://www.coupang.com/vp/products/1?itemId=２&vendorItemId=3",
            "https://www.coupang.com/vp/products/1/other?itemId=2&vendorItemId=3",
            "https://fake.coupang.com/vp/products/1?itemId=2&vendorItemId=3",
            "http://www.coupang.com/vp/products/1?itemId=2&vendorItemId=3",
        ).forEach { raw ->
            assertThrows("Accepted $raw", IllegalArgumentException::class.java) { CoupangProductLink.parse(raw) }
        }
    }

    @Test
    fun canonicalProductNeedsNoNetworkRequest() = runTest {
        val result = CoupangProductLinks.resolveWith(exact) { fail("Unexpected network request"); error("unreachable") }
        assertEquals(exact, result.canonicalUrl)
    }

    @Test
    fun resolvesShortLinkWithoutRequestingFinalProductPage() = runTest {
        val requested = mutableListOf<String>()
        val result = CoupangProductLinks.resolveWith("https://link.coupang.com/a/h1yJnHQWjY") { url ->
            requested += url
            CoupangProductLinks.RedirectResponse(302, exact)
        }
        assertEquals(exact, result.canonicalUrl)
        assertEquals(listOf("https://link.coupang.com/a/h1yJnHQWjY"), requested)
    }

    @Test
    fun redirectPolicyRejectsUntrustedDowngradedCredentialedAndIntentUrls() {
        listOf(
            "http://www.coupang.com/vp/products/1",
            "https://coupang.com.evil.example/p",
            "//evil.example/p",
            "https://user@www.coupang.com/vp/products/1",
            "https://www.coupang.com:443/vp/products/1",
            "coupang://product?pId=1",
            "intent://product#Intent;scheme=coupang;end",
        ).forEach { location ->
            assertThrows("Accepted $location", IllegalArgumentException::class.java) {
                CoupangProductLinks.resolveRedirect("https://link.coupang.com/a/share", location)
            }
        }
        assertEquals(
            "https://link.coupang.com/re/product",
            CoupangProductLinks.resolveRedirect("https://link.coupang.com/a/share", "../re/product"),
        )
    }

    @Test
    fun repeatedRedirectIsStoppedBeforeSecondRequest() = runTest {
        var requests = 0
        try {
            CoupangProductLinks.resolveWith("https://link.coupang.com/a/repeat") {
                requests++
                CoupangProductLinks.RedirectResponse(302, "/a/repeat")
            }
            fail("Expected redirect loop rejection")
        } catch (_: IllegalArgumentException) {
            assertEquals(1, requests)
        }
    }

    @Test
    fun redirectCountIsBoundedAndNonRedirectResponseCannotGuessAnOption() = runTest {
        var requests = 0
        try {
            CoupangProductLinks.resolveWith("https://link.coupang.com/a/start") {
                requests++
                CoupangProductLinks.RedirectResponse(302, "/a/hop$requests")
            }
            fail("Expected redirect limit rejection")
        } catch (_: IllegalArgumentException) {
            assertEquals(7, requests)
        }
        try {
            CoupangProductLinks.resolveWith("https://link.coupang.com/a/denied") {
                CoupangProductLinks.RedirectResponse(403, exact)
            }
            fail("403 is not a product redirect")
        } catch (_: IllegalArgumentException) {
            // The ordinary server response is respected; no HTML or alternate route is tried.
        }
    }

    @Test
    fun transportCancellationIsNotConvertedToAnInvalidLink() = runTest {
        try {
            CoupangProductLinks.resolveWith("https://link.coupang.com/a/cancel") {
                throw CancellationException("User cancelled")
            }
            fail("Expected cancellation")
        } catch (expected: CancellationException) {
            assertEquals("User cancelled", expected.message)
        }
    }
}
