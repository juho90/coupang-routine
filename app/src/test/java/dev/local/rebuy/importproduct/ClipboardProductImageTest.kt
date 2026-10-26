package dev.local.rebuy.importproduct

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ClipboardProductImageTest {
    private val image = Uri.parse("content://test.product.images/one.png")

    @Test fun singleExplicitImageUriUsesClipboardMetadataWithoutFetchingAType() {
        val clip = imageClip(image)
        assertEquals(image, ClipboardProductImage.imageUri(clip) { fail("Metadata already identifies an image"); null })
    }

    @Test fun contentProviderImageMimeCanIdentifyAUriList() {
        val clip = ClipData.newRawUri("copied image", image)
        val requested = mutableListOf<Uri>()
        val result = ClipboardProductImage.imageUri(clip) { uri -> requested += uri; "image/png" }
        assertEquals(image, result)
        assertEquals(listOf(image), requested)
    }

    @Test fun nullAndEmptyTextClipboardGiveImageCopyInstructions() {
        val missing = assertThrows(IllegalArgumentException::class.java) {
            ClipboardProductImage.imageUri(null) { "image/png" }
        }
        assertTrue(missing.message.orEmpty().contains("클립보드에 복사된 이미지가 없습니다"))
        assertNoImage(ClipData.newPlainText("empty", ""))
    }

    @Test fun copiedWebOrContentUriTextIsNeverConvertedIntoAnImageUri() {
        listOf(
            "https://www.coupang.com/vp/products/7157272883",
            "https://example.test/product.png",
            image.toString(),
            "data:image/png;base64,AAAA",
        ).forEach { text ->
            assertNoImage(ClipData.newPlainText("copied text", text))
        }
        // Even an image declaration cannot turn Item.text into Item.uri.
        assertNoImage(ClipData("misleading image metadata", arrayOf("image/png"), ClipData.Item(image.toString())))
    }

    @Test fun nonContentSchemesAndOpaqueContentUriAreRejectedDespiteImageMetadata() {
        listOf(
            "https://example.test/product.png", "http://example.test/product.png",
            "file:///sdcard/product.png", "data:image/png;base64,AAAA", "android.resource://app/drawable/product",
            "coupang://product?pId=1", "content:opaque-image", "content:///missing-authority.png",
        ).forEach { value -> assertNoImage(imageClip(Uri.parse(value))) }
    }

    @Test fun multipleImageItemsAreRejectedInsteadOfChoosingTheFirst() {
        val clip = imageClip(image).apply { addItem(ClipData.Item(Uri.parse("content://test.product.images/two.png"))) }
        val error = assertThrows(IllegalArgumentException::class.java) {
            ClipboardProductImage.imageUri(clip) { "image/png" }
        }
        assertTrue(error.message.orEmpty().contains("한 장만"))
        val repeated = imageClip(image).apply { addItem(ClipData.Item(image)) }
        assertThrows(IllegalArgumentException::class.java) { ClipboardProductImage.imageUri(repeated) { "image/png" } }
    }

    @Test fun aMixedClipCanUseItsOnlyImageWithoutTreatingTextAsASecondImage() {
        val clip = ClipData.newPlainText("mixed", "a product title").apply { addItem(ClipData.Item(image)) }
        assertEquals(image, ClipboardProductImage.imageUri(clip) { "image/jpeg" })
    }

    @Test fun missingOrNonImageMimeTypesCannotPromoteAContentUri() {
        val clip = ClipData.newRawUri("unknown file", image)
        listOf(null, "text/plain", "application/pdf", "image", "image/", "image/png extra").forEach { mime ->
            assertThrows(IllegalArgumentException::class.java) { ClipboardProductImage.imageUri(clip) { mime } }
        }
    }

    @Test fun imageMetadataCanBeGenericOrUseNormalMimeParameters() {
        listOf("image/*", "IMAGE/PNG", "image/jpeg; charset=binary").forEach { mime ->
            val clip = ClipData("image", arrayOf(mime), ClipData.Item(image))
            assertEquals(image, ClipboardProductImage.imageUri(clip) { null })
        }
    }

    @Test fun copiedIntentDataIsNeverReadOrExecutedAsAnImage() {
        val intent = Intent(Intent.ACTION_VIEW, image)
        val clip = ClipData.newIntent("copied intent", intent)
        assertNoImage(clip)
        assertNoImage(ClipData("image metadata with intent", arrayOf("image/png"), ClipData.Item(intent)))
        val context = ApplicationProvider.getApplicationContext<Application>()
        assertTrue(shadowOf(context).nextStartedActivity == null)
    }

    @Test fun providerPermissionFailureIsPreservedForTheUiErrorHandler() {
        val denied = SecurityException("Synthetic provider permission denied")
        val result = assertThrows(SecurityException::class.java) {
            ClipboardProductImage.imageUri(ClipData.newRawUri("image", image)) { throw denied }
        }
        assertSame(denied, result)
    }

    @Test fun explicitReadReturnsImageWithoutClearingWritingOrOpeningAnything() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val clip = imageClip(image)
        clipboard.setPrimaryClip(clip) // Robolectric's synthetic clipboard, never the PC clipboard.

        assertEquals(image, ClipboardProductImage.read(context))
        assertEquals(image, clipboard.primaryClip?.getItemAt(0)?.uri)
        assertTrue(shadowOf(context as Application).nextStartedActivity == null)
    }

    private fun imageClip(uri: Uri) = ClipData("copied image", arrayOf("image/png"), ClipData.Item(uri))

    private fun assertNoImage(clip: ClipData) {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ClipboardProductImage.imageUri(clip) { fail("No provider lookup expected for text, intent or unsupported URI"); null }
        }
        assertTrue(error.message.orEmpty().contains("클립보드에 복사된 이미지가 없습니다"))
    }
}
