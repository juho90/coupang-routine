package dev.local.rebuy.domain

import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** An exact product option. Tracking parameters never become part of the native app URI. */
data class CoupangProductLink private constructor(
    val canonicalUrl: String,
    val nativeUri: String,
    val productId: String,
    val itemId: String,
    val vendorItemId: String,
) {
    companion object {
        fun parse(raw: String): CoupangProductLink {
            val uri = URI(CoupangUrl.normalize(raw))
            require(uri.host == "www.coupang.com" || uri.host == "coupang.com") {
                "상품의 전체 주소가 필요해요. 쿠팡 공유 링크는 먼저 확인해 주세요."
            }
            val productId = Regex("/vp/products/([0-9]{1,30})/?")
                .matchEntire(uri.rawPath.orEmpty())?.groupValues?.get(1)
                ?: throw IllegalArgumentException("쿠팡 상품의 전체 주소를 입력해 주세요.")
            val query = uri.rawQuery.orEmpty().split('&').filter(String::isNotEmpty)
                .map { pair ->
                    val parts = pair.split('=', limit = 2)
                    decode(parts[0]) to decode(parts.getOrElse(1) { "" })
                }
                .groupBy({ it.first }, { it.second })
            fun optionId(key: String): String {
                val values = query[key]
                require(values?.size == 1 && values.single().matches(Regex("[0-9]{1,30}"))) {
                    "정확한 옵션을 열려면 itemId와 vendorItemId가 각각 하나씩 포함된 상품 주소가 필요해요."
                }
                return values.single()
            }
            val itemId = optionId("itemId")
            val vendorItemId = optionId("vendorItemId")
            return CoupangProductLink(
                canonicalUrl = "https://www.coupang.com/vp/products/$productId?itemId=$itemId&vendorItemId=$vendorItemId",
                nativeUri = "coupang://product?pId=$productId&itemId=$itemId&vendorItemId=$vendorItemId",
                productId = productId,
                itemId = itemId,
                vendorItemId = vendorItemId,
            )
        }

        private fun decode(value: String): String =
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }
}

/** Resolves a user-requested share link with ordinary HTTPS redirects, without loading HTML. */
object CoupangProductLinks {
    private val redirectCodes = setOf(301, 302, 303, 307, 308)
    private const val maximumRedirects = 6

    suspend fun resolve(raw: String): CoupangProductLink = withContext(Dispatchers.IO) {
        withTimeout(20_000) { resolveWith(raw, ::loadRedirect) }
    }

    internal data class RedirectResponse(val statusCode: Int, val location: String?)

    // Injectable transport keeps policy tests local and independent of Coupang availability.
    internal suspend fun resolveWith(
        raw: String,
        load: suspend (String) -> RedirectResponse,
    ): CoupangProductLink {
        var current = CoupangUrl.normalize(raw)
        runCatching { CoupangProductLink.parse(current) }.getOrNull()?.let { return it }
        require(URI(current).host == "link.coupang.com") {
            "itemId와 vendorItemId가 포함된 전체 상품 주소 또는 쿠팡 공유 링크를 입력해 주세요."
        }
        val visited = mutableSetOf<String>()
        var redirects = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            runCatching { CoupangProductLink.parse(current) }.getOrNull()?.let { return it }
            require(visited.add(current)) { "쿠팡 공유 링크가 같은 주소를 반복해서 열고 있어요." }
            val response = load(current)
            currentCoroutineContext().ensureActive()
            require(response.statusCode in redirectCodes) {
                "공유 링크에서 상품 주소를 확인하지 못했어요. 쿠팡의 전체 상품 주소를 입력해 주세요."
            }
            require(redirects < maximumRedirects) { "공유 링크의 이동 횟수가 너무 많아요." }
            current = resolveRedirect(current, response.location)
            redirects += 1
        }
    }

    internal fun resolveRedirect(current: String, location: String?): String {
        require(!location.isNullOrBlank() && location.length <= 4096) {
            "공유 링크가 올바른 상품 주소를 보내지 않았어요."
        }
        // Revalidate every hop, including relative and scheme-relative Location headers.
        return CoupangUrl.normalize(URI(current).resolve(location.trim()).toString())
    }

    private fun loadRedirect(url: String): RedirectResponse {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.useCaches = false
            connection.requestMethod = "GET"
            // This app does not use a cookie store or carry a Coupang login session.
            connection.setRequestProperty("Cookie", "")
            return RedirectResponse(connection.responseCode, connection.getHeaderField("Location"))
        } finally {
            connection.disconnect()
        }
    }
}
