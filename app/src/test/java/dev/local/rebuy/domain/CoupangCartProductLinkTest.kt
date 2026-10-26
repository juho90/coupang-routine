package dev.local.rebuy.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CoupangCartProductLinkTest {
    @Test fun observedMlpRouterUsesOnlyItsActualProductReturnUrl() {
        val nested = "coupang://product?pId=9750360880&itemId=29195429057&vendorItemId=123"
        val encoded = java.net.URLEncoder.encode(nested, "UTF-8")
        val link = CoupangCartProductLink.fromObservedNative("coupang://mlp?rUrl=$encoded&pageType=PRODUCT&pageValue=999")
        assertEquals("9750360880", link.productId)
        assertEquals("29195429057", link.itemId)
        assertEquals("123", link.vendorItemId)
    }

    @Test fun mlpCannotInventAnIdOrExecuteNestedRoutersOrCheckoutLinks() {
        listOf(
            "coupang://mlp?pageType=PRODUCT&pageValue=123",
            "coupang://mlp?rUrl=coupang%3A%2F%2Fcheckout%3FpId%3D1",
            "coupang://mlp?rUrl=coupang%3A%2F%2Fmlp%3FpageValue%3D1",
            "coupang://mlp?rUrl=https%3A%2F%2Fevil.example%2Fvp%2Fproducts%2F1",
            "coupang://mlp?rUrl=https%3A%2F%2Fwww.coupang.com%2Fvp%2Fproducts%2F1&rUrl=bad",
        ).forEach { raw -> assertThrows(raw, IllegalArgumentException::class.java) { CoupangCartProductLink.fromObservedNative(raw) } }
    }

    @Test fun partialObservedIdsRemainPartialWithoutInventingAnOption() {
        val link = CoupangCartProductLink.parse(
            "https://www.coupang.com/vp/products/9750360880?itemId=29195429057&sourceType=cmgoms",
        )
        assertEquals("9750360880", link.productId)
        assertEquals("29195429057", link.itemId)
        assertNull(link.vendorItemId)
        assertEquals("coupang://product?pId=9750360880&itemId=29195429057", link.nativeUri)
        assertEquals("https://www.coupang.com/vp/products/9750360880?itemId=29195429057", link.canonicalUrl)
    }

    @Test fun mobileRedirectAndFullOptionIdsArePreservedExactly() {
        val link = CoupangCartProductLink.parse(
            "https://m.coupang.com/vm/products/001234?itemId=00567&vendorItemId=00890#details",
        )
        assertEquals("coupang://product?pId=001234&itemId=00567&vendorItemId=00890", link.nativeUri)
        assertEquals("https://www.coupang.com/vp/products/001234?itemId=00567&vendorItemId=00890", link.canonicalUrl)
    }

    @Test fun productOnlyAddressDoesNotRequireOrGenerateOptionIds() {
        val link = CoupangCartProductLink.parse("https://www.coupang.com/vp/products/9750360880")
        assertEquals("coupang://product?pId=9750360880", link.nativeUri)
        assertNull(link.itemId)
        assertNull(link.vendorItemId)
    }

    @Test fun ambiguousOrUntrustedAddressesAreRejected() {
        listOf(
            "https://link.coupang.com/a/h4NNZza6N2",
            "http://www.coupang.com/vp/products/1",
            "https://fake.coupang.com/vp/products/1",
            "https://www.coupang.com/vp/products/1?itemId=2&%69temId=3",
            "https://www.coupang.com/vp/products/1?itemId=",
            "https://www.coupang.com/vp/products/1?vendorItemId=3%26action%3Dbuy",
        ).forEach { raw ->
            assertThrows(raw, IllegalArgumentException::class.java) { CoupangCartProductLink.parse(raw) }
        }
    }

    @Test fun observedNativeAddressOnlyAcceptsTheProductRouteAndNumericIds() {
        assertEquals(
            "https://www.coupang.com/vp/products/9750360880?itemId=29195429057",
            CoupangCartProductLink.fromObservedNative("coupang://product?pId=9750360880&itemId=29195429057").canonicalUrl,
        )
        listOf(
            "coupang://checkout?pId=1", "coupang://product?pId=1&pId=2", "coupang://product?pId=x",
            "coupang://product?productId=1", "coupang://product?pId=1#pay", "coupang:%",
            "intent://product?pId=1#Intent;scheme=coupang;end",
        ).forEach { raw ->
            assertThrows(raw, IllegalArgumentException::class.java) { CoupangCartProductLink.fromObservedNative(raw) }
        }
    }
}
