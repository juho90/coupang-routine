package dev.local.rebuy.importproduct

import org.junit.Assert.*
import org.junit.Test

class ProductOcrLayoutTest {
    private fun line(text: String, x: Int, y: Int, width: Int, height: Int = 20) =
        ProductOcrLayout.Line(text, ProductOcrLayout.Box(x, y, x + width, y + height))

    @Test fun smallScreenshotsAreUpscaledWithinSideAndPixelBounds() {
        assertEquals(ProductOcrLayout.Size(994, 1536), ProductOcrLayout.recognitionSize(497, 768))
        assertEquals(ProductOcrLayout.Size(2902, 864), ProductOcrLayout.recognitionSize(1451, 432))
        assertEquals(ProductOcrLayout.Size(2400, 2000), ProductOcrLayout.recognitionSize(2400, 2000))
        listOf(1599 to 1599, 3000 to 500, 60_000 to 60_000, 1 to 60_000).forEach { (width, height) ->
            val size = ProductOcrLayout.recognitionSize(width, height)
            assertTrue(size.width in 1..3072 && size.height in 1..3072)
            assertTrue(size.width.toLong() * size.height <= 10_000_000)
        }
    }

    @Test fun sameRowMarkedWeightAndCountAreReconstructedWithoutLosingOriginals() {
        val count = line("1세트", 220, 103, 45)
        val label = line("개당 중량 × 수량", 0, 100, 140)
        val weight = line("285g ×", 148, 102, 65)
        val title = line("동원 용기죽 4종 기획세트 쇠고기3+야채3+참치3+전복3", 0, 50, 500)
        val result = ProductOcrLayout.linesForParsing(listOf(count, weight, title, label))
        assertEquals("개당 중량 × 수량 285g × 1세트", result.first())
        assertEquals(listOf(title.text, label.text, weight.text, count.text), result.drop(1))
    }

    @Test fun differentRowsColumnsAndUnmarkedPackageWordsAreNeverJoined() {
        val label = line("개당 중량 × 수량", 0, 100, 140)
        listOf(
            listOf(label, line("285g ×", 148, 140, 65), line("1세트", 220, 140, 45)),
            listOf(label, line("285g ×", 450, 100, 65), line("1세트", 520, 100, 45)),
            listOf(line("쇠고기죽", 0, 100, 80), line("285g ×", 90, 100, 65), line("1세트", 165, 100, 45)),
            listOf(label, line("2859 ×", 148, 100, 65), line("1세트", 220, 100, 45)),
        ).forEach { source ->
            val result = ProductOcrLayout.linesForParsing(source)
            assertEquals(source.size, result.size)
            assertEquals(source.map { it.text }.toSet(), result.toSet())
        }
    }

    @Test fun missingGeometryRemainsOriginalAndReadingOrderIsStable() {
        val bottom = line("아래", 0, 100, 80)
        val left = line("왼쪽", 0, 20, 60)
        val right = line("오른쪽", 100, 21, 60)
        val unknown = ProductOcrLayout.Line("좌표 없음", null)
        assertEquals(listOf("왼쪽", "오른쪽", "아래", "좌표 없음"),
            ProductOcrLayout.linesForParsing(listOf(bottom, right, unknown, left)))
    }
}
