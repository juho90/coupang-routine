package dev.local.rebuy.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CoupangUrlTest {
    @Test
    fun trimsAndNormalizesSchemeAndHostWithoutLosingProductParameters() {
        assertEquals(
            "https://www.coupang.com/vp/products/123?itemId=456&vendorItemId=789#details",
            CoupangUrl.normalize("  HTTPS://WWW.COUPANG.COM/vp/products/123?itemId=456&vendorItemId=789#details  "),
        )
    }

    @Test
    fun acceptsProductAndOfficialSharedLinks() {
        listOf(
            "https://coupang.com/vp/products/123",
            "https://www.coupang.com/vp/products/123",
            "https://link.coupang.com/a/abc123",
            "https://m.coupang.com/vm/products/123",
        ).forEach { assertEquals(it, CoupangUrl.normalize(it)) }
    }

    @Test
    fun refusesUnsafeSchemesLookalikeHostsCredentialsAndPorts() {
        listOf(
            "http://www.coupang.com/vp/products/123",
            "javascript:alert(1)",
            "intent://coupang.com/vp/products/123",
            "//coupang.com/vp/products/123",
            "https://coupang.com.evil.example/vp/products/123",
            "https://fakecoupang.com/vp/products/123",
            "https://coupang.com@evil.example/vp/products/123",
            "https://user@coupang.com/vp/products/123",
            "https://coupang.com:443/vp/products/123",
            "https://coupang.com:/vp/products/123",
            "https://coupang.com./vp/products/123",
            "https://evil.example/path?redirect=https://coupang.com/vp/products/123",
            "https://coupang.com\\@evil.example/vp/products/123",
            "https://cоupang.com/vp/products/123", // Cyrillic o.
            "https://coupang.com/%not-an-escape",
            "https://coupang.com/vp/hello world",
            "https://coupang.com/vp/products/1%00",
            "https://coupang.com/vp/products/1?hidden=%0d%0a",
            "https://coupang.com/vp/products/1#hidden%7f",
            "https://coupang.com/%2F%2Fevil.example/path",
            "https://coupang.com/%5c%5cevil.example/path",
            "https://coupang.com/%C0%AFbad-utf8",
            "https://coupang.com/%E0%80%AFbad-utf8",
        ).forEach { raw ->
            assertThrows("Accepted $raw", IllegalArgumentException::class.java) { CoupangUrl.normalize(raw) }
        }
    }

    @Test
    fun refusesHomeAndEmptyPaths() {
        listOf("", "  ", "https://coupang.com", "https://coupang.com/", "https://coupang.com/?id=123", "https://coupang.com/./", "https://coupang.com/%2e%2e").forEach { raw ->
            assertThrows(IllegalArgumentException::class.java) { CoupangUrl.normalize(raw) }
        }
    }
}
