package dev.local.rebuy.importproduct

import java.text.Normalizer
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Geometry is kept only during recognition; no image text or coordinates are persisted. */
internal object ProductOcrLayout {
    data class Size(val width: Int, val height: Int)
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val height: Int get() = bottom - top
        val valid: Boolean get() = left >= 0 && top >= 0 && right > left && bottom > top
    }
    data class Line(val text: String, val box: Box?)

    fun recognitionSize(width: Int, height: Int): Size {
        require(width > 0 && height > 0)
        val longest = maxOf(width, height)
        val preferred = if (longest < 1_600 || minOf(width, height) < 600) 2.0 else 1.0
        val scale = min(preferred, min(3_072.0 / longest, sqrt(10_000_000.0 / (width.toDouble() * height))))
        return Size((width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1))
    }

    private val labelStart = Regex("^(?:개당\\s*)?(?:중량|용량)(?:\\s|[x×*:]|$)", RegexOption.IGNORE_CASE)
    private val completeOption = Regex(
        "^(?:개당\\s*)?(?:중량|용량)\\s*[x×*]\\s*수량\\s*[:：]?\\s*" +
            "[0-9]+(?:\\.[0-9]+)?\\s*(?:밀리리터|킬로그램|밀리그램|리터|그램|kg|mg|ml|g|l)\\s*" +
            "[x×*]\\s*[0-9]+\\s*(?:세트|묶음|개입|개|팩|봉|병|입)$",
        RegexOption.IGNORE_CASE,
    )
    private fun normalized(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKC).trim()
    private fun sameRow(a: Box, b: Box): Boolean {
        val overlap = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        val centerDifference = kotlin.math.abs((a.top.toLong() + a.bottom) - (b.top.toLong() + b.bottom)) / 2.0
        return overlap >= minOf(a.height, b.height) * 0.6 && centerDifference <= maxOf(a.height, b.height) * 0.35
    }

    /** Only a complete, explicitly labelled weight/count row may be reconstructed. */
    fun linesForParsing(input: List<Line>): List<String> {
        val limited = input.take(500).filter { it.text.isNotBlank() && it.text.length <= 4_096 }
        val located = limited.filter { it.box?.valid == true }.sortedWith(compareBy({ it.box!!.top }, { it.box!!.left }))
        val rows = mutableListOf<MutableList<Line>>()
        for (line in located) {
            val row = rows.lastOrNull { sameRow(it.first().box!!, line.box!!) }
            if (row == null) rows += mutableListOf(line) else row += line
        }
        val orderedRows = rows.map { it.sortedBy { line -> line.box!!.left } }
        val reconstructed = linkedSetOf<String>()
        for (row in orderedRows) {
            for (start in row.indices) {
                if (!labelStart.containsMatchIn(normalized(row[start].text))) continue
                var joined = normalized(row[start].text)
                var previous = row[start].box!!
                for (index in start + 1 until minOf(row.size, start + 8)) {
                    val box = row[index].box!!
                    val gap = box.left - previous.right
                    // Never bridge a distant recommendation column or overlapping OCR blocks.
                    if (gap < 0 || gap > maxOf(12.0, maxOf(previous.height, box.height) * 2.5)) break
                    joined += " " + normalized(row[index].text)
                    if (joined.length > 200) break
                    if (completeOption.matches(joined)) { reconstructed += joined; break }
                    previous = box
                }
            }
        }
        // Reconstructed field rows are first, while all original fragments remain available.
        return reconstructed.toList() + orderedRows.flatten().map(Line::text) +
            limited.filter { it.box?.valid != true }.map(Line::text)
    }
}
