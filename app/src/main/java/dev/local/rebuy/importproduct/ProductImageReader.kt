package dev.local.rebuy.importproduct

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.CancellationSignal
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object ProductImageReader {
    private const val MAX_BYTES = 24 * 1024 * 1024
    private const val MAX_SIDE = 3_072

    suspend fun read(context: Context, uri: Uri): ProductTextSuggestions = withTimeout(45_000) {
        var decoded: Bitmap? = null
        try {
            // Assign inside IO so cancellation at the dispatcher boundary cannot lose a bitmap.
            withContext(Dispatchers.IO) { decoded = decode(context.applicationContext, uri) }
            currentCoroutineContext().ensureActive()
            val owned = checkNotNull(decoded)
            decoded = null
            recognizeOwnedBitmap(owned)
        } finally { decoded?.recycle() }
    }

    private suspend fun decode(context: Context, uri: Uri): Bitmap {
        require(uri.scheme == "content") { "사진 선택기에서 이미지를 선택해 주세요." }
        val bytes = readEncoded(context, uri)
        currentCoroutineContext().ensureActive()
        return decodeBytes(bytes)
    }

    private suspend fun readEncoded(context: Context, uri: Uri): ByteArray = suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        val stream = AtomicReference<InputStream?>()
        continuation.invokeOnCancellation {
            runCatching { signal.cancel() }
            runCatching { stream.getAndSet(null)?.close() }
        }
        try {
            val bytes = context.contentResolver.openAssetFileDescriptor(uri, "r", signal)?.use { asset ->
                asset.createInputStream().use inputUse@ { input ->
                    stream.set(input)
                    if (!continuation.isActive) return@inputUse ByteArray(0)
                    ByteArrayOutputStream().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var total = 0
                        while (continuation.isActive) {
                            val count = input.read(buffer)
                            if (count == -1) break
                            total += count
                            require(total <= MAX_BYTES) { "24MB 이하의 이미지로 다시 선택해 주세요." }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                }
            } ?: throw IllegalArgumentException("선택한 이미지를 열 수 없습니다.")
            if (continuation.isActive) continuation.resume(bytes)
        } catch (failure: Exception) {
            if (continuation.isActive) continuation.resumeWithException(failure)
        } finally {
            stream.set(null)
        }
    }

    internal fun decodeBytes(bytes: ByteArray): Bitmap {
        require(bytes.size <= MAX_BYTES) { "24MB 이하의 이미지로 다시 선택해 주세요." }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..60_000 && bounds.outHeight in 1..60_000 &&
            bounds.outWidth.toLong() * bounds.outHeight <= 160_000_000L) {
            "읽을 수 있는 일반 사진이나 상품 스크린샷을 선택해 주세요."
        }
        var sample = 1
        while ((maxOf(bounds.outWidth, bounds.outHeight) + sample - 1) / sample > MAX_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IllegalArgumentException("이미지를 해석하지 못했습니다.")
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            }
        }
        if (matrix.isIdentity) return bitmap
        return try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
                if (it !== bitmap) bitmap.recycle()
            }
        } catch (failure: Throwable) { bitmap.recycle(); throw failure }
    }

    /** Ownership transfers here; the native task releases the bitmap even after UI cancellation. */
    internal suspend fun recognizeOwnedBitmap(bitmap: Bitmap): ProductTextSuggestions {
        var owned: Bitmap? = bitmap
        try {
            withContext(Dispatchers.IO) {
                val source = checkNotNull(owned)
                val size = ProductOcrLayout.recognitionSize(source.width, source.height)
                if (size.width != source.width || size.height != source.height) {
                    val scaled = Bitmap.createScaledBitmap(source, size.width, size.height, true)
                    owned = scaled // Assign before leaving IO, including cancellation at its boundary.
                    if (scaled !== source) source.recycle()
                }
            }
            currentCoroutineContext().ensureActive()
            val nativeOwned = checkNotNull(owned)
            owned = null
            return recognizePreparedOwnedBitmap(nativeOwned)
        } finally { owned?.recycle() }
    }

    private suspend fun recognizePreparedOwnedBitmap(bitmap: Bitmap): ProductTextSuggestions =
        suspendCancellableCoroutine { continuation ->
            val recognizer = try {
                TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            } catch (failure: Exception) {
                bitmap.recycle()
                continuation.resumeWithException(failure)
                return@suspendCancellableCoroutine
            }
            try {
                recognizer.process(InputImage.fromBitmap(bitmap, 0)).addOnCompleteListener { task ->
                    try {
                        if (continuation.isActive) {
                            if (task.isSuccessful) {
                                val lines = task.result.textBlocks.flatMap { it.lines }.map { line ->
                                    val box = line.boundingBox
                                    ProductOcrLayout.Line(line.text, box?.let {
                                        ProductOcrLayout.Box(it.left, it.top, it.right, it.bottom)
                                    })
                                }
                                continuation.resume(ProductTextParser.parse(ProductOcrLayout.linesForParsing(lines)))
                            } else continuation.resumeWithException(task.exception ?: IllegalStateException("글자를 읽지 못했습니다."))
                        }
                    } catch (failure: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(failure)
                    } finally { try { recognizer.close() } finally { bitmap.recycle() } }
                }
            } catch (failure: Exception) {
                try { recognizer.close() } finally { bitmap.recycle() }
                if (continuation.isActive) continuation.resumeWithException(failure)
            }
        }
}
