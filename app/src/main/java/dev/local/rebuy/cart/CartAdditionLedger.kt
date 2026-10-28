package dev.local.rebuy.cart

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest

internal class CartAdditionLedger(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    enum class Status { CLAIMED, CONFIRMED }
    data class Record(val status: Status, val atEpochMillis: Long)
    private data class Stored(val record: Record, val attemptId: String)

    fun lookup(url: String): Record? = synchronized(lock) { read(key(url))?.record }

    fun claim(url: String, attemptId: String): Boolean = synchronized(lock) {
        requireAttempt(attemptId)
        val key = key(url)
        if (read(key) != null) return@synchronized false
        write(key, Stored(Record(Status.CLAIMED, System.currentTimeMillis()), attemptId))
    }

    fun confirm(url: String, attemptId: String): Boolean = synchronized(lock) {
        requireAttempt(attemptId)
        val key = key(url)
        val stored = read(key) ?: return@synchronized false
        if (stored.attemptId != attemptId || stored.record.status != Status.CLAIMED) return@synchronized false
        write(key, stored.copy(record = stored.record.copy(status = Status.CONFIRMED)))
    }

    fun clear(url: String): Boolean = synchronized(lock) {
        runCatching {
            val key = key(url)
            val cleared = runCatching { preferences.edit().remove(key).commit() }.getOrDefault(false)
            if (cleared) failedClears.remove(key) else failedClears.add(key)
            cleared
        }.getOrDefault(false)
    }

    private fun key(url: String): String {
        val canonical = dev.local.rebuy.domain.CoupangCartProductLink.parseExactOption(url).canonicalUrl
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun read(key: String): Stored? {
        check(key !in failedClears) { "담기 기록 해제를 저장하지 못했습니다. 장바구니 확인 후 다시 해제해 주세요." }
        val raw = preferences.getString(key, null) ?: return null
        require(raw.length <= 1024) { "담기 기록의 형식을 확인하지 못했습니다." }
        val json = JSONObject(raw)
        require(json.keys().asSequence().toSet() == setOf("version", "status", "attemptId", "atEpochMillis")) {
            "담기 기록의 형식을 확인하지 못했습니다."
        }
        require(json.get("version") == 1) { "지원하지 않는 담기 기록 버전입니다." }
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
        return Stored(Record(Status.valueOf(status), timestamp), attemptId)
    }

    private fun write(key: String, stored: Stored): Boolean = runCatching {
        val raw = JSONObject().apply {
            put("version", 1)
            put("status", stored.record.status.name)
            put("attemptId", stored.attemptId)
            put("atEpochMillis", stored.record.atEpochMillis)
        }.toString()
        preferences.edit().putString(key, raw).commit()
    }.getOrDefault(false)

    private fun requireAttempt(attemptId: String) {
        require(attemptId.length in 1..100 && attemptId.isNotBlank() && attemptId.none(Char::isISOControl)) {
            "담기 실행 식별자를 확인하지 못했습니다."
        }
    }

    private companion object {
        const val PREFS = "cart_addition_ledger_v1"
        val lock = Any()
        val failedClears = mutableSetOf<String>()
    }
}
