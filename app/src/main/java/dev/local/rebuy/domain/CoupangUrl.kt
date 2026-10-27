package dev.local.rebuy.domain

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

object CoupangUrl {
    fun normalize(raw: String): String {
        val input = raw.trim()
        require(input.length in 1..4096) { "상품 URL은 4,096자 이내로 입력해 주세요." }
        val uri = try {
            URI(input).normalize()
        } catch (_: URISyntaxException) {
            throw IllegalArgumentException("올바른 쿠팡 HTTPS URL을 입력해 주세요.")
        }
        require(!uri.isOpaque && uri.scheme.equals("https", ignoreCase = true)) {
            "https로 시작하는 쿠팡 URL만 등록할 수 있어요."
        }
        val host = uri.host?.lowercase(Locale.ROOT)
        require(host != null && (host == "coupang.com" || host.endsWith(".coupang.com"))) {
            "coupang.com의 상품 URL을 입력해 주세요."
        }
        require(uri.rawUserInfo == null && uri.port == -1 && uri.rawAuthority.equals(host, ignoreCase = true)) {
            "사용자 정보나 포트가 포함된 URL은 등록할 수 없어요."
        }
        val path = uri.rawPath.orEmpty()
        // Browsers and share links must not interpret hidden controls or path separators
        // differently from java.net.URI. Normal product/share paths need neither.
        require(!Regex("(?i)%2f|%5c").containsMatchIn(path)) { "숨겨진 경로 구분자가 포함된 URL은 등록할 수 없어요." }
        val decodedPath = decodeSafe(path)
        uri.rawQuery?.let(::decodeSafe)
        uri.rawFragment?.let(::decodeSafe)
        require(decodedPath.split('/').any { it.isNotBlank() && it != "." && it != ".." }) {
            "쿠팡 홈 주소 대신 상품 또는 공유 URL을 입력해 주세요."
        }
        val normalized = buildString {
            append("https://")
            append(host)
            append(path)
            uri.rawQuery?.let { append('?'); append(it) }
            uri.rawFragment?.let { append('#'); append(it) }
        }
        return URI(normalized).toASCIIString()
    }

    private fun decodeSafe(component: String): String {
        val decoded = URLDecoder.decode(component, StandardCharsets.UTF_8.name())
        require(decoded.none { it.isISOControl() || it == '\uFFFD' || it == '\\' }) {
            "제어 문자나 잘못된 인코딩이 포함된 URL은 등록할 수 없어요."
        }
        return decoded
    }
}
