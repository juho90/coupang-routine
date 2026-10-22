package dev.local.rebuy.importproduct

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.io.ByteArrayOutputStream

internal object ProductImageFixture {
    fun bitmap(): Bitmap = Bitmap.createBitmap(2_000, 1_050, Bitmap.Config.ARGB_8888).also { bitmap ->
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textSize = 80f
        }
        canvas.drawText("익스트림 트리플 아르기닌6200", 100f, 170f, paint)
        canvas.drawText("600ml, 2개", 100f, 370f, paint)
        paint.textSize = 100f
        canvas.drawText("75,000원", 100f, 610f, paint)
        canvas.drawText("67,500원", 100f, 850f, paint)
    }

    fun pngBytes(): ByteArray {
        val bitmap = bitmap()
        return try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally { bitmap.recycle() }
    }
}
