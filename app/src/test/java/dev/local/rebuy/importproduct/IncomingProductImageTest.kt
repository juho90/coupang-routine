package dev.local.rebuy.importproduct

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import dev.local.rebuy.pcimport.PcImageInbox
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class IncomingProductImageTest {
    private val image = Uri.parse("content://photos.example/item/123")

    @Test fun launcherAndCompletionIntentsDoNotProduceAnImageRequest() {
        assertNull(IncomingProductImage.fromIntent(Intent(Intent.ACTION_MAIN)))
        assertNull(IncomingProductImage.fromIntent(Intent(Intent.ACTION_VIEW, image)))
    }

    @Test fun explicitImageStreamIsAcceptedWithoutExecutingOrConsumingTheIntent() {
        val intent = imageIntent(image)
        val incoming = requireNotNull(IncomingProductImage.fromIntent(intent))
        assertEquals(image, incoming.uri)
        assertTrue(incoming.id.isNotBlank())
        assertTrue(intent.hasExtra(Intent.EXTRA_STREAM))
        assertNotEquals(incoming.id, requireNotNull(IncomingProductImage.fromIntent(intent)).id)
    }

    @Test fun matchingClipUriFallbackIsAcceptedAndDuplicateRepresentationIsOneImage() {
        val fallback = Intent(Intent.ACTION_SEND).setType("image/png").apply {
            clipData = ClipData.newRawUri("image", image)
        }
        assertEquals(image, requireNotNull(IncomingProductImage.fromIntent(fallback)).uri)
        assertEquals(image, requireNotNull(IncomingProductImage.fromIntent(imageIntent(image).apply {
            clipData = ClipData.newRawUri("same image", image)
        })).uri)
    }

    @Test fun textIntentDataAndNonContentSourcesAreNeverReinterpretedAsImages() {
        listOf("https://example.test/image.png", "file:///sdcard/image.png", "data:image/png;base64,AAAA", "content:opaque").forEach { raw ->
            assertThrows(IllegalArgumentException::class.java) { IncomingProductImage.fromIntent(imageIntent(Uri.parse(raw))) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            IncomingProductImage.fromIntent(Intent(Intent.ACTION_SEND, image).setType("image/png"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            IncomingProductImage.fromIntent(Intent(Intent.ACTION_SEND).setType("image/png").apply {
                clipData = ClipData.newPlainText("not an image", image.toString())
            })
        }
        assertThrows(IllegalArgumentException::class.java) {
            IncomingProductImage.fromIntent(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, "content://photos.example/123"))
        }
    }

    @Test fun multipleImagesAndNonImageMimeAreRejectedInsteadOfPickingOne() {
        assertThrows(IllegalArgumentException::class.java) {
            IncomingProductImage.fromIntent(Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/png"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            IncomingProductImage.fromIntent(imageIntent(image).setType("text/plain"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            IncomingProductImage.fromIntent(imageIntent(image).apply {
                clipData = ClipData.newRawUri("other image", Uri.parse("content://photos.example/item/456"))
            })
        }
        assertThrows(IllegalArgumentException::class.java) {
            IncomingProductImage.fromIntent(Intent(Intent.ACTION_SEND).setType("image/png").apply {
                clipData = ClipData.newRawUri("image", image).apply { addItem(ClipData.Item(image)) }
            })
        }
    }

    @Test fun inboxStreamRequiresTheExactTokenUri() {
        val token = "00112233-4455-6677-8899-aabbccddeeff"
        assertEquals(PcImageInbox.uriFor(token), requireNotNull(IncomingProductImage.fromIntent(imageIntent(PcImageInbox.uriFor(token)))).uri)
        listOf("/$token/extra", "/$token?x=1", "/$token#fragment", "/${token.uppercase()}").forEach { suffix ->
            assertThrows(IllegalArgumentException::class.java) {
                IncomingProductImage.fromIntent(imageIntent(Uri.parse("content://${PcImageInbox.AUTHORITY}$suffix")))
            }
        }
    }

    private fun imageIntent(uri: Uri) = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
}
