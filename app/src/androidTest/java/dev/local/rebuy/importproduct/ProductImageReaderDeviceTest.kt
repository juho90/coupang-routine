package dev.local.rebuy.importproduct

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions

/** Runs the actual bundled Korean recognizer against generated text; never opens Coupang. */
@RunWith(AndroidJUnit4::class)
class ProductImageReaderDeviceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun bundledKoreanModelReadsProductOptionAndBothPricesAndReleasesOwnedBitmap() = runBlocking {
        val bitmap = ProductImageFixture.bitmap()
        assertFalse(bitmap.isRecycled)
        val suggestions = withTimeout(60_000) { ProductImageReader.recognizeOwnedBitmap(bitmap) }
        assertSyntheticSuggestions(suggestions)
        // Native completion posts the result and releases ownership in the same callback. The
        // caller can resume on another thread a moment before that callback's finally block runs.
        withTimeout(2_000) { while (!bitmap.isRecycled) delay(10) }
        assertTrue("The reader must release its owned bitmap", bitmap.isRecycled)
    }

    @Test fun contentUriPngIsDecodedAndRecognizedEndToEndWithoutGrantOrStoragePermission() = runBlocking {
        val uri = ProductImageFixtureProvider.imageUri()
        assertEquals("image/png", context.contentResolver.getType(uri))
        val suggestions = withTimeout(60_000) { ProductImageReader.read(context, uri) }
        assertSyntheticSuggestions(suggestions)
    }

    @Test fun fileAndNetworkUrisAreRejectedBeforeOpeningAnImage() = runBlocking {
        listOf(
            Uri.parse("file:///not-a-user-file/synthetic.png"),
            Uri.parse("http://example.invalid/synthetic.png"),
            Uri.parse("https://example.invalid/synthetic.png"),
        ).forEach { uri ->
            val failure = runCatching { ProductImageReader.read(context, uri) }.exceptionOrNull()
            assertTrue("Unsupported scheme ${uri.scheme} must be rejected", failure is IllegalArgumentException)
        }
    }

    @Test fun contentUriWithNonImageBytesFailsWithoutStartingRecognition() = runBlocking {
        val failure = runCatching { ProductImageReader.read(context, ProductImageFixtureProvider.brokenUri()) }.exceptionOrNull()
        assertTrue("Non-image content must fail decoding", failure is IllegalArgumentException)
    }

    @Test fun widePngIsDownsampledToTheImageSizeLimitAndCallerCanReleaseIt() {
        val original = Bitmap.createBitmap(4_096, 32, Bitmap.Config.ARGB_8888)
        val bytes = try {
            ByteArrayOutputStream().use { output ->
                check(original.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally { original.recycle() }
        val decoded = ProductImageReader.decodeBytes(bytes)
        try {
            assertTrue("Decoded images must be bounded before OCR", maxOf(decoded.width, decoded.height) <= 3_072)
            assertTrue(decoded.width > decoded.height && decoded.height > 0)
            assertFalse(decoded.isRecycled)
        } finally { decoded.recycle() }
        assertTrue(decoded.isRecycled)
    }

    private fun assertSyntheticSuggestions(suggestions: ProductTextSuggestions) {
        try {
            assertParsedSyntheticSuggestions(suggestions)
        } catch (failure: AssertionError) {
            throw AssertionError("${failure.message}\nFixed synthetic fixture OCR lines: ${syntheticOcrLinesForFailure()}", failure)
        }
    }

    private fun assertParsedSyntheticSuggestions(suggestions: ProductTextSuggestions) {
        val titles = suggestions.titleCandidates.map(::compact)
        assertTrue("Korean product text must be retained as a title suggestion", titles.any {
            it.contains("익스트림트리플아르기닌")
        })
        assertTrue("Pack option must remain distinct from purchase quantity", suggestions.optionCandidates.map(::compact).any {
            it.contains("600ml") && it.contains("2개")
        })
        assertEquals("Keep both recognized amounts available for review even if OCR drops the currency glyph",
            setOf(75_000L, 67_500L), (suggestions.priceCandidatesWon + suggestions.unconfirmedNumbers).toSet())
        assertTrue("Currency-less numbers must never silently become confirmed prices",
            suggestions.priceCandidatesWon.all { it in setOf(75_000L, 67_500L) })
        assertTrue("OCR must not invent a product URL", suggestions.productUrls.isEmpty())
    }

    /** Failure-only diagnostics for our hardcoded Canvas fixture, never for a selected user image. */
    private fun syntheticOcrLinesForFailure(): List<String> {
        if (Looper.myLooper() == Looper.getMainLooper()) return listOf("DIAGNOSTICS_SKIPPED_ON_MAIN_THREAD")
        val bitmap = ProductImageFixture.bitmap()
        var recognizer: TextRecognizer? = null
        var task: Task<Text>? = null
        return try {
            val client = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            recognizer = client
            val pending = client.process(InputImage.fromBitmap(bitmap, 0))
            task = pending
            Tasks.await(pending, 30, TimeUnit.SECONDS).textBlocks.flatMap { block -> block.lines }.map { it.text }
        } catch (failure: Exception) {
            listOf("SYNTHETIC_DIAGNOSTIC_FAILED_${failure.javaClass.simpleName}")
        } finally {
            val ownedRecognizer = recognizer
            val pending = task
            if (pending != null && !pending.isComplete) {
                pending.addOnCompleteListener {
                    try { ownedRecognizer?.close() } finally { bitmap.recycle() }
                }
            } else {
                try { ownedRecognizer?.close() } finally { bitmap.recycle() }
            }
        }
    }

    private fun compact(value: String): String = value.lowercase(java.util.Locale.ROOT).filter(Char::isLetterOrDigit)
}
