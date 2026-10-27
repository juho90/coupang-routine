package dev.local.rebuy.domain

import java.text.Normalizer
import org.json.JSONArray
import org.json.JSONObject

/** Each observed option keeps its own identity and address together. */
data class ProductVariant(val title: String, val option: String, val productUrl: String)

object ProductVariantCodec {
    private const val MAX_JSON_LENGTH = 32_000
    private val spaces = Regex("[\\s\\p{Z}]+")

    fun validate(variants: List<ProductVariant>): List<ProductVariant> {
        require(variants.size <= 20) { "한 상품에 최대 20개 옵션을 저장할 수 있습니다." }
        var productId: String? = null
        val addresses = mutableSetOf<String>()
        return variants.map { variant ->
            val title = spaces.replace(variant.title.trim(), " ")
            val option = spaces.replace(Normalizer.normalize(variant.option, Normalizer.Form.NFKC), " ").trim()
            require(title.length in 1..100 && title.none(Char::isISOControl)) { "옵션의 상품명을 확인해 주세요." }
            require(option.length in 2..120 && option.count(Char::isLetterOrDigit) >= 2 &&
                option.none(Char::isISOControl)) { "옵션은 문자·숫자 2자 이상, 전체 120자 이내로 입력해 주세요." }
            val link = CoupangCartProductLink.parse(variant.productUrl)
            require(productId == null || productId == link.productId) { "같은 상품의 옵션만 함께 저장할 수 있습니다." }
            require(addresses.add(link.canonicalUrl)) { "같은 상품 주소가 중복되어 있습니다." }
            productId = link.productId
            ProductVariant(title, option, link.canonicalUrl)
        }
    }

    fun encode(variants: List<ProductVariant>): String = JSONArray().apply {
        validate(variants).forEach { variant ->
            put(JSONObject().put("title", variant.title).put("option", variant.option).put("productUrl", variant.productUrl))
        }
    }.toString()

    /** Corrupt cached choices never become selectable. Explicit saves use decodeStrict instead. */
    fun decode(raw: String?): List<ProductVariant> =
        if (raw == null) emptyList() else runCatching { decodeStrict(raw) }.getOrDefault(emptyList())

    fun decodeStrict(raw: String): List<ProductVariant> {
        require(raw.length <= MAX_JSON_LENGTH) { "옵션 목록이 너무 큽니다." }
        val array = try { JSONArray(raw) } catch (_: Exception) {
            throw IllegalArgumentException("저장할 옵션 목록의 형식을 확인해 주세요.")
        }
        require(array.length() <= 20) { "한 상품에 최대 20개 옵션을 저장할 수 있습니다." }
        return validate((0 until array.length()).map { index ->
            try {
                val value = array.getJSONObject(index)
                require(value.get("title") is String && value.get("option") is String && value.get("productUrl") is String)
                ProductVariant(value.getString("title"), value.getString("option"), value.getString("productUrl"))
            } catch (_: Exception) {
                throw IllegalArgumentException("옵션의 상품명·선택값·상품 주소를 확인해 주세요.")
            }
        })
    }
}
