package dev.local.rebuy.importproduct

import dev.local.rebuy.domain.CoupangUrl
import java.text.Normalizer

/** Suggestions require user selection; the order of a list is not a purchase recommendation. */
data class ProductTextSuggestions(
    val titleCandidates: List<String>,
    val optionCandidates: List<String>,
    val priceCandidatesWon: List<Long>,
    val productUrls: List<String>,
    val unconfirmedNumbers: List<Long> = emptyList(),
)

/** Local, bounded OCR text interpretation. This never searches for a product or invents an ID. */
object ProductTextParser {
    private const val MAX_LINES = 200
    private const val MAX_LINE_LENGTH = 4096
    private const val MAX_TOTAL_LENGTH = 32_000
    private const val MAX_CANDIDATES = 12
    private const val MAX_URLS = 8
    private const val MAX_FIELD_LENGTH = 150
    private const val MAX_PRICE_WON = 100_000_000L

    private val spaces = Regex("[\\s\\p{Z}]+")
    private val visibleUrl = Regex("(?i)https://[^\\s\\p{Z}<>\"']+")
    private val money = Regex(
        "(?i)(?<![0-9,./])(?:(?:₩|KRW)\\s*([0-9][0-9,]*)|([0-9][0-9,]*)\\s*원)(?![0-9])",
    )
    private val validAmount = Regex("(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)")
    // A missing currency glyph is not repaired. These isolated, comma-grouped OCR numbers
    // are review material only: the UI must ask whether to use one as a won amount. A lone
    // trailing OCR glyph is retained as uncertainty, never interpreted as a currency symbol.
    private val unconfirmedNumber = Regex("^([0-9]{1,3}(?:,[0-9]{3})+)\\s*[lI|]?$")
    private val measure = Regex(
        "(?i)(?<![0-9.,])(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]+)?\\s*" +
            "(?:밀리리터|킬로그램|리터|그램|캡슐|박스|상자|세트|묶음|kg|mg|ml|g|l|ℓ|개입|개|팩|봉|병|롤|매|장|포|정|구|입)" +
            "(?![A-Za-z가-힣])",
    )
    private val optionLabel = Regex("^(?:선택\\s*옵션|상품\\s*옵션|옵션(?:\\s*선택)?|용량|구성|색상|컬러|사이즈)\\s*[:：=]?\\s*", RegexOption.IGNORE_CASE)
    private val optionPrefix = Regex("^(?:대형|중형|소형|특대|미니|기본|리필|본품|초코맛|딸기맛|바닐라맛|블랙|화이트|블루|레드)[ ,:/-]*$")
    private val nonPricePrefix = Regex(
        "(?:배송비|배달비|적립(?:금)?|포인트|캐시(?:백)?|할부|개월|매월|월|쿠폰\\s*할인액|할인액|할인금액|쿠폰|절약)\\s*$",
        RegexOption.IGNORE_CASE,
    )
    private val nonPriceSuffix = Regex("^\\s*(?:적립|포인트|캐시(?:백)?|할인|절약)")
    private val unitPricePrefix = Regex("(?:[0-9]+(?:\\.[0-9]+)?\\s*(?:kg|mg|ml|g|l|ℓ|개|매|장|포|정|캡슐)|1회|1개월)\\s*(?:당|/)[^0-9]*$", RegexOption.IGNORE_CASE)
    private val totalPriceLabel = Regex("(?:총\\s*(?:결제\\s*)?(?:금액|가격)?|결제\\s*금액|판매가|할인가|쿠폰가|와우가|상품가)\\s*[:：]?\\s*$")
    private val excludedTitle = Regex(
        "(?:도착\\s*(?:보장|예정)|무료\\s*(?:배송|반품)|배송비|배달비|할부|적립|캐시백|" +
            "장바구니|구매하기|바로\\s*구매|상품평|리뷰|옵션을?\\s*선택|선택해\\s*주세요|" +
            "쿠폰\\s*(?:받기|적용)|다시\\s*구매|함께\\s*본\\s*상품|품절)",
    )
    private val optionPrompt = Regex("(?:옵션을?\\s*선택|선택해\\s*주세요)")
    private val identifierLabel = Regex("^(?:상품\\s*(?:번호|코드)|itemId|vendorItemId|productId|pId)\\s*[:：]?\\s*[0-9]", RegexOption.IGNORE_CASE)
    private val uiLabels = setOf(
        "쿠팡", "로켓배송", "로켓프레시", "로켓와우", "와우회원", "상품상세", "상세정보", "상품정보",
        "상품문의", "배송/교환/반품", "수량", "옵션선택", "선택옵션", "옵션", "구성", "용량", "색상", "사이즈",
        "할인", "가격", "판매가", "정상가", "구매", "추천상품", "상세보기", "혜택보기", "선물하기", "공유하기",
    )

    fun parse(lines: List<String>): ProductTextSuggestions {
        val titles = linkedSetOf<String>()
        val options = linkedSetOf<String>()
        val prices = linkedSetOf<Long>()
        val urls = linkedSetOf<String>()
        val numbers = linkedSetOf<Long>()
        var totalLength = 0

        for (rawLine in lines.take(MAX_LINES)) {
            // Never truncate a long URL into a different, apparently valid one.
            if (rawLine.length > MAX_LINE_LENGTH) continue
            totalLength += rawLine.length
            if (totalLength > MAX_TOTAL_LENGTH) break
            collectUrls(rawLine, urls)
            val line = normalize(rawLine)
            if (line.isEmpty()) continue
            val amounts = money.findAll(line).toList()
            collectPrices(line, amounts, prices)
            val numberMatch = unconfirmedNumber.matchEntire(line)
            if (numbers.size < MAX_CANDIDATES) {
                numberMatch?.groupValues?.get(1)
                    ?.replace(",", "")?.toLongOrNull()
                    ?.takeIf { it in 1_000..MAX_PRICE_WON }
                    ?.let(numbers::add)
            }
            // A residual 'l' might be a unit or a bad OCR glyph. Do not also promote an
            // uncertain numeric line into a capacity/option suggestion.
            if (numberMatch != null) continue
            val option = findOption(line, amounts.firstOrNull()?.range?.first)
            if (option != null && options.size < MAX_CANDIDATES) options += option
            if (titles.size < MAX_CANDIDATES && isTitle(line, option, amounts.isNotEmpty())) titles += line
        }
        return ProductTextSuggestions(
            titles.toList(), options.toList(), prices.toList(), urls.toList(),
            numbers.filterNot(prices::contains),
        )
    }

    private fun normalize(raw: String): String = spaces.replace(
        Normalizer.normalize(raw, Normalizer.Form.NFKC),
        " ",
    ).trim()

    private fun collectUrls(raw: String, urls: MutableSet<String>) {
        if (urls.size >= MAX_URLS) return
        for (match in visibleUrl.findAll(raw)) {
            if (urls.size >= MAX_URLS) break
            // Strip a matching text wrapper only. Do not repair OCR characters, join wrapped
            // lines, add missing query parameters, or turn a product number into an address.
            val opening = raw.getOrNull(match.range.first - 1)
            val closing = when (opening) { '(' -> ')'; '[' -> ']'; '{' -> '}'; else -> null }
            val candidate = if (closing != null && match.value.endsWith(closing)) match.value.dropLast(1) else match.value
            try {
                urls += CoupangUrl.normalize(candidate)
            } catch (_: IllegalArgumentException) {
                // A visible but invalid or non-Coupang URL is not a usable suggestion.
            }
        }
    }

    private fun collectPrices(line: String, matches: List<MatchResult>, prices: MutableSet<Long>) {
        matches.forEachIndexed { index, match ->
            if (prices.size >= MAX_CANDIDATES) return
            val rawAmount = match.groupValues[1].ifEmpty { match.groupValues[2] }
            if (!validAmount.matches(rawAmount)) return@forEachIndexed
            val before = line.substring(if (index == 0) 0 else matches[index - 1].range.last + 1, match.range.first).takeLast(45)
            val after = line.substring(match.range.last + 1, matches.getOrNull(index + 1)?.range?.first ?: line.length).take(30)
            val explicitTotal = totalPriceLabel.containsMatchIn(before)
            if (!explicitTotal && (nonPricePrefix.containsMatchIn(before) || unitPricePrefix.containsMatchIn(before))) return@forEachIndexed
            if (nonPriceSuffix.containsMatchIn(after)) return@forEachIndexed
            val amount = rawAmount.replace(",", "").toLongOrNull() ?: return@forEachIndexed
            if (amount in 1..MAX_PRICE_WON) prices += amount
        }
    }

    private fun findOption(line: String, firstPriceAt: Int?): String? {
        if (optionPrompt.containsMatchIn(line)) return null
        if (excludedTitle.containsMatchIn(line) && !line.startsWith("옵션") && !line.startsWith("구성") && !line.startsWith("용량")) return null
        val priceFree = line.substring(0, firstPriceAt ?: line.length).trimEnd(' ', ',', '|', '/', '(', ':')
        val label = optionLabel.find(priceFree)
        val withoutLabel = if (label != null) priceFree.substring(label.range.last + 1).trim() else priceFree
        if (withoutLabel.isBlank()) return null
        val firstMeasure = measure.find(withoutLabel)
        val candidate = when {
            label != null -> withoutLabel
            firstMeasure == null -> return null
            firstMeasure.range.first == 0 -> withoutLabel
            optionPrefix.matches(withoutLabel.substring(0, firstMeasure.range.first).trim()) -> withoutLabel
            else -> withoutLabel.substring(firstMeasure.range.first)
        }.trim(' ', ',', '|', '/', ':')
        if (candidate.length !in 1..MAX_FIELD_LENGTH || visibleUrl.containsMatchIn(candidate)) return null
        if (candidate.contains('%') || candidate.contains('₩') || candidate.contains("원")) return null
        return candidate
    }

    private fun isTitle(line: String, option: String?, hasMoney: Boolean): Boolean {
        if (line.length !in 2..MAX_FIELD_LENGTH || hasMoney || line.contains('%')) return false
        if (visibleUrl.containsMatchIn(line) || line.contains("http", ignoreCase = true)) return false
        if (line.any { it.isISOControl() || it == '\uFFFD' }) return false
        if (line.count(Char::isLetter) < 2 || uiLabels.contains(line.replace(" ", ""))) return false
        if (excludedTitle.containsMatchIn(line) || optionLabel.containsMatchIn(line) || identifierLabel.containsMatchIn(line)) return false
        val firstDigit = line.indexOfFirst(Char::isDigit).let { if (it < 0) line.length else it }
        if (option != null && (line == option || measure.find(line)?.range?.first == 0 || optionPrefix.matches(line.take(firstDigit).trim()))) return false
        return true
    }
}
