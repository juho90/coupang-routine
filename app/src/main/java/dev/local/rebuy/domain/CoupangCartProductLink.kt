package dev.local.rebuy.domain

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** An observed product address. Missing option IDs remain missing; the cart screen verifies the option. */
data class CoupangCartProductLink private constructor(
    val canonicalUrl: String,
    val nativeUri: String,
    val productId: String,
    val itemId: String?,
    val vendorItemId: String?,
) {
    companion object {
        private val digits = Regex("[0-9]{1,30}")

        fun parse(raw: String): CoupangCartProductLink {
            val uri = URI(CoupangUrl.normalize(raw))
            val path = when (uri.host) {
                "coupang.com", "www.coupang.com" -> Regex("/vp/products/([0-9]{1,30})/?")
                "m.coupang.com" -> Regex("/v[mp]/products/([0-9]{1,30})/?")
                else -> throw IllegalArgumentException("상품으로 연결된 전체 주소가 필요해요.")
            }
            val productId = path.matchEntire(uri.rawPath.orEmpty())?.groupValues?.get(1)
                ?: throw IllegalArgumentException("쿠팡 상품 주소를 확인하지 못했어요.")
            return create(productId, query(uri))
        }

        fun parseExactOption(raw: String): CoupangCartProductLink = parse(raw).also {
            require(it.itemId != null && it.vendorItemId != null) {
                "상품 수정에서 선택한 옵션의 링크를 불러와 저장해 주세요."
            }
        }

        /** Only a product navigation actually emitted by the share page is accepted. Never launches it. */
        internal fun fromObservedNative(raw: String): CoupangCartProductLink {
            require(raw.length in 1..4096)
            val uri = runCatching { URI(raw) }.getOrElse { throw IllegalArgumentException("Invalid product navigation", it) }
            require(uri.scheme == "coupang" && uri.rawAuthority in setOf("product", "mlp") &&
                uri.rawPath.orEmpty() in setOf("", "/") && uri.rawFragment == null)
            val query = query(uri)
            if (uri.rawAuthority == "mlp") {
                // The public share page emits an MLP router. Inspect its observed return URL;
                // never execute the router or infer an item from tracking/page-value fields.
                val returnUrls = query["rUrl"]
                require(returnUrls?.size == 1 && returnUrls.single().length <= 4096)
                val nested = returnUrls.single()
                return if (nested.startsWith("https://")) parse(nested)
                else {
                    require(URI(nested).rawAuthority == "product")
                    fromObservedNative(nested)
                }
            }
            val productId = id(query, "pId", required = true)!!
            return create(productId, query)
        }

        private fun create(productId: String, query: Map<String, List<String>>): CoupangCartProductLink {
            val itemId = id(query, "itemId")
            val vendorItemId = id(query, "vendorItemId")
            val options = buildString {
                itemId?.let { append("&itemId="); append(it) }
                vendorItemId?.let { append("&vendorItemId="); append(it) }
            }
            return CoupangCartProductLink(
                canonicalUrl = "https://www.coupang.com/vp/products/$productId" +
                    if (options.isEmpty()) "" else "?${options.drop(1)}",
                nativeUri = "coupang://product?pId=$productId$options",
                productId = productId,
                itemId = itemId,
                vendorItemId = vendorItemId,
            )
        }

        private fun query(uri: URI): Map<String, List<String>> =
            uri.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).map { pair ->
                val parts = pair.split('=', limit = 2)
                fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name()).also {
                    require(it.none { character -> character.isISOControl() || character == '\uFFFD' || character == '\\' })
                }
                decode(parts[0]) to decode(parts.getOrElse(1) { "" })
            }.groupBy({ it.first }, { it.second })

        private fun id(query: Map<String, List<String>>, key: String, required: Boolean = false): String? {
            val values = query[key]
            require((values != null || !required) &&
                (values == null || (values.size == 1 && digits.matches(values.single())))) {
                "상품 주소의 선택 정보가 올바르지 않아요. 상품 링크를 다시 확인해 주세요."
            }
            return values?.single()
        }
    }
}
