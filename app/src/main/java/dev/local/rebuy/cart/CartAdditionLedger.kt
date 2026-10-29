package dev.local.rebuy.cart

import android.content.Context
import dev.local.rebuy.domain.CoupangCartProductLink
import org.json.JSONObject
import java.security.MessageDigest

/** Durable claims. A product-only claim conservatively locks every option of that product. */
internal class CartAdditionLedger(context: Context, knownUrls: List<String> = emptyList()) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    enum class Status { CLAIMED, CONFIRMED }
    data class Record(val status: Status, val atEpochMillis: Long)
    private data class Stored(
        val record: Record,
        val attemptId: String,
        // Null only for a retained version-1 receipt whose original exact URL is not yet known.
        val product: CoupangCartProductLink? = null,
    )

    init {
        if (knownUrls.isNotEmpty()) check(migrateKnownUrls(knownUrls)) {
            "이전 담기 기록의 상품 연결을 저장하지 못했습니다."
        }
    }

    fun lookup(url: String): Record? = synchronized(lock) {
        lookupStored(CoupangCartProductLink.parseForCart(url))?.record
    }

    fun claim(url: String, attemptId: String): Boolean = synchronized(lock) {
        requireAttempt(attemptId)
        val product = CoupangCartProductLink.parseForCart(url)
        if (lookupStored(product) != null) return@synchronized false
        write(key(product.canonicalUrl), Stored(Record(Status.CLAIMED, System.currentTimeMillis()), attemptId, product))
    }

    fun confirm(url: String, attemptId: String): Boolean = synchronized(lock) {
        requireAttempt(attemptId)
        val product = CoupangCartProductLink.parseForCart(url)
        val key = key(product.canonicalUrl)
        val stored = read(key) ?: return@synchronized false
        if (stored.attemptId != attemptId || stored.record.status != Status.CLAIMED) return@synchronized false
        write(key, stored.copy(record = stored.record.copy(status = Status.CONFIRMED), product = product))
    }

    /** Clears only this URL's receipt. A basic URL never clears another option's exact receipt. */
    fun clear(url: String): Boolean = synchronized(lock) {
        runCatching {
            val key = key(CoupangCartProductLink.parseForCart(url).canonicalUrl)
            val cleared = runCatching { preferences.edit().remove(key).commit() }.getOrDefault(false)
            if (cleared) failedClears.remove(key) else failedClears.add(key)
            cleared
        }.getOrDefault(false)
    }

    /**
     * Upgrades only version-1 keys proven to match a known exact URL. All upgrades share one
     * commit; unmatched receipts are retained and continue to block product-only claims.
     * Basic, partial, and invalid known URLs provide no migration evidence and are ignored.
     */
    fun migrateKnownUrls(knownUrls: List<String>): Boolean = synchronized(lock) {
        runCatching {
            val known = knownUrls.mapNotNull { url ->
                runCatching { CoupangCartProductLink.parseExactOption(url) }.getOrNull()
            }.distinctBy { it.canonicalUrl }
            val upgrades = known.mapNotNull { product ->
                val key = key(product.canonicalUrl)
                val stored = read(key) ?: return@mapNotNull null
                if (stored.product != null) return@mapNotNull null
                key to stored.copy(product = product)
            }
            if (upgrades.isEmpty()) return@runCatching true
            val editor = preferences.edit()
            upgrades.forEach { (key, stored) -> editor.putString(key, encode(stored)) }
            editor.commit()
        }.getOrDefault(false)
    }

    private fun lookupStored(product: CoupangCartProductLink): Stored? {
        val own = read(key(product.canonicalUrl))
        if (own != null) return own
        if (product.itemId != null && product.vendorItemId != null) {
            // Preserve independent exact-option claims, but never bypass an earlier basic claim.
            val basic = read(key(basicUrl(product.productId))) ?: return null
            check(basic.product != null) { "이전 담기 기록의 상품 연결을 확인하지 못했습니다." }
            return basic
        }
        // A failed clear of an absent key is still unsafe; it is not visible in preferences.all.
        check(failedClears.isEmpty()) { "담기 기록 해제를 저장하지 못했습니다. 장바구니 확인 후 다시 해제해 주세요." }
        val records = preferences.all.keys.map { key ->
            val stored = checkNotNull(read(key)) { "이전 담기 기록을 읽지 못했습니다." }
            check(stored.product != null) {
                "이전 담기 기록의 상품 연결을 확인하지 못해 기본 상품 주소로 다시 담을 수 없습니다. 기존 옵션의 장바구니와 담기 기록을 확인해 주세요."
            }
            stored
        }.filter { it.product?.productId == product.productId }
        // Prefer the stronger warning if any matching exact attempt is still unconfirmed.
        return records.firstOrNull { it.record.status == Status.CLAIMED } ?: records.firstOrNull()
    }

    private fun read(key: String): Stored? {
        check(key !in failedClears) { "담기 기록 해제를 저장하지 못했습니다. 장바구니 확인 후 다시 해제해 주세요." }
        val raw = preferences.getString(key, null) ?: return null
        require(raw.length <= 1024) { "담기 기록의 형식을 확인하지 못했습니다." }
        val json = JSONObject(raw)
        val version = json.get("version")
        require(version == 1 || version == 2) { "지원하지 않는 담기 기록 버전입니다." }
        val fields = setOf("version", "status", "attemptId", "atEpochMillis") +
            if (version == 2) setOf("canonicalUrl") else emptySet()
        require(json.keys().asSequence().toSet() == fields) { "담기 기록의 형식을 확인하지 못했습니다." }
        val status = json.get("status")
        val attemptId = json.get("attemptId")
        require(status is String && attemptId is String) { "담기 기록의 형식을 확인하지 못했습니다." }
        requireAttempt(attemptId)
        val timestamp = when (val value = json.get("atEpochMillis")) {
            is Long -> value
            is Int -> value.toLong()
            else -> throw IllegalArgumentException("담기 기록의 시간을 확인하지 못했습니다.")
        }
        require(timestamp > 0) { "담기 기록의 시간을 확인하지 못했습니다." }
        val product = if (version == 2) {
            val canonical = json.get("canonicalUrl")
            require(canonical is String) { "담기 기록의 상품 연결을 확인하지 못했습니다." }
            CoupangCartProductLink.parseForCart(canonical).also {
                require(it.canonicalUrl == canonical && this.key(canonical) == key) {
                    "담기 기록의 상품 연결을 확인하지 못했습니다."
                }
            }
        } else null
        return Stored(Record(Status.valueOf(status), timestamp), attemptId, product)
    }

    private fun encode(stored: Stored): String = JSONObject().apply {
        put("version", if (stored.product == null) 1 else 2)
        put("status", stored.record.status.name)
        put("attemptId", stored.attemptId)
        put("atEpochMillis", stored.record.atEpochMillis)
        stored.product?.let { put("canonicalUrl", it.canonicalUrl) }
    }.toString()

    private fun write(key: String, stored: Stored): Boolean = runCatching {
        preferences.edit().putString(key, encode(stored)).commit()
    }.getOrDefault(false)

    private fun key(canonicalUrl: String): String = MessageDigest.getInstance("SHA-256")
        .digest(canonicalUrl.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun basicUrl(productId: String) = "https://www.coupang.com/vp/products/$productId"

    private fun requireAttempt(attemptId: String) {
        require(attemptId.length in 1..100 && attemptId.isNotBlank() && attemptId.none(Char::isISOControl)) {
            "담기 실행 식별자를 확인하지 못했습니다."
        }
    }

    private companion object {
        // Exact version-1 hashes stay in this file. No receipt is deleted or silently reset.
        const val PREFS = "cart_addition_ledger_v1"
        val lock = Any()
        val failedClears = mutableSetOf<String>()
    }
}
