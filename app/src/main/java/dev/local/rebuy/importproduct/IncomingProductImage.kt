package dev.local.rebuy.importproduct

import android.content.Intent
import android.net.Uri
import android.os.Build
import dev.local.rebuy.pcimport.PcImageInbox
import java.util.Locale
import java.util.UUID

/** Plain UI state; neither the image bytes nor the URI are persisted as product information. */
data class IncomingProductImage(val id: String, val uri: Uri) {
    companion object {
        private const val INVALID_IMAGE = "이미지 한 장을 다시 공유해 주세요. 이미지 주소나 텍스트는 직접 읽지 않습니다."

        fun fromIntent(intent: Intent): IncomingProductImage? {
            if (intent.action == Intent.ACTION_SEND_MULTIPLE) throw IllegalArgumentException("상품 이미지 한 장만 공유해 주세요.")
            if (intent.action != Intent.ACTION_SEND) return null
            val mime = intent.type?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
            require(mime != null && mime.startsWith("image/") && mime.length > 6 && mime.none(Char::isWhitespace)) { INVALID_IMAGE }
            val uris = mutableListOf<Uri>()
            if (intent.hasExtra(Intent.EXTRA_STREAM)) {
                val stream = stream(intent)
                require(stream != null) { INVALID_IMAGE }
                uris += stream
            }
            intent.clipData?.let { clip ->
                require(clip.itemCount <= 32) { INVALID_IMAGE }
                val clippedUris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
                require(clippedUris.size <= 1) { "상품 이미지 한 장만 공유해 주세요." }
                uris += clippedUris
            }
            val unique = uris.distinct()
            require(unique.size == 1) { INVALID_IMAGE }
            return IncomingProductImage(UUID.randomUUID().toString(), requireContentUri(unique.single()))
        }

        internal fun requireContentUri(uri: Uri): Uri {
            require(uri.scheme == "content" && uri.isHierarchical && !uri.authority.isNullOrBlank()) { INVALID_IMAGE }
            if (uri.authority == PcImageInbox.AUTHORITY) PcImageInbox.tokenFor(uri)
            return uri
        }

        @Suppress("DEPRECATION")
        private fun stream(intent: Intent): Uri? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra<android.os.Parcelable>(Intent.EXTRA_STREAM) as? Uri
        }
    }
}
