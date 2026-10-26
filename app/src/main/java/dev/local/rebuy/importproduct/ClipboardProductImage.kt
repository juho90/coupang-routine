package dev.local.rebuy.importproduct

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import java.util.Locale

/** Reads only after an explicit foreground UI click. Never observes, writes, or clears the clipboard. */
object ClipboardProductImage {
    private const val NO_IMAGE = "클립보드에 복사된 이미지가 없습니다. 상품 이미지 한 장을 복사한 뒤 다시 눌러 주세요. 주소나 텍스트는 이미지로 읽지 않습니다."
    private const val MULTIPLE_IMAGES = "이미지가 여러 장 복사되어 있습니다. 읽을 상품 이미지 한 장만 복사한 뒤 다시 눌러 주세요."
    private const val MAX_ITEMS = 32

    fun read(context: Context): Uri {
        val manager = context.getSystemService(ClipboardManager::class.java)
            ?: throw IllegalArgumentException(NO_IMAGE)
        return imageUri(manager.primaryClip) { uri -> context.contentResolver.getType(uri) }
    }

    internal fun imageUri(clip: ClipData?, resolveMimeType: (Uri) -> String?): Uri {
        require(clip != null && clip.itemCount > 0) { NO_IMAGE }
        require(clip.itemCount <= MAX_ITEMS) { MULTIPLE_IMAGES }
        val description = clip.description
        val declaredImage = (0 until description.mimeTypeCount).any { index ->
            isImageMimeType(description.getMimeType(index))
        }
        var found: Uri? = null
        for (index in 0 until clip.itemCount) {
            // Item.uri is the only source. In particular, never coerce text into a URI or
            // reuse an Intent's data URI; a copied link is not a copied image.
            val uri = clip.getItemAt(index).uri ?: continue
            if (uri.scheme != "content" || !uri.isHierarchical || uri.authority.isNullOrBlank()) continue
            if (!declaredImage && !isImageMimeType(resolveMimeType(uri))) continue
            require(found == null) { MULTIPLE_IMAGES }
            found = uri
        }
        return found ?: throw IllegalArgumentException(NO_IMAGE)
    }

    private fun isImageMimeType(raw: String?): Boolean {
        val type = raw?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) ?: return false
        return type.startsWith("image/") && type.length > "image/".length && type.none(Char::isWhitespace)
    }
}
