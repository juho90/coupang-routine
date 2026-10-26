package dev.local.rebuy.pcimport

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.net.Uri
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class PcImageInboxTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val owned = mutableListOf<Uri>()

    @After fun cleanup() {
        ShadowBinder.setCallingUid(Process.myUid())
        owned.forEach { PcImageInbox.release(context, it) }
    }

    @Test fun tokenUrisRejectTraversalAliasesQueryAndUppercase() {
        val token = UUID.randomUUID().toString()
        val uri = PcImageInbox.uriFor(token)
        assertEquals(token, PcImageInbox.tokenFor(uri))
        listOf(
            "content://${PcImageInbox.AUTHORITY}/$token/extra",
            "content://${PcImageInbox.AUTHORITY}/$token?x=1",
            "content://${PcImageInbox.AUTHORITY}/$token#fragment",
            "content://${PcImageInbox.AUTHORITY}/${token.uppercase()}",
            "content://${PcImageInbox.AUTHORITY}/%2e%2e",
            "content://${PcImageInbox.AUTHORITY}:80/$token",
            "content://user@${PcImageInbox.AUTHORITY}/$token",
            "file:///${PcImageInbox.AUTHORITY}/$token",
        ).forEach { value -> assertThrows(IllegalArgumentException::class.java) { PcImageInbox.tokenFor(Uri.parse(value)) } }
        assertThrows(IllegalArgumentException::class.java) { PcImageInbox.uriFor("../../other") }
    }

    @Test fun completePngPassesButDamagedCrcTruncationAndOversizeAreRejected() {
        val uri = nextUri()
        val file = file(uri)
        file.writeBytes(png())
        PcImageInbox.validatePng(file)
        val original = file.readBytes()
        file.writeBytes(original.copyOf(original.size - 5))
        assertThrows(IOException::class.java) { PcImageInbox.validatePng(file) }
        file.writeBytes(original.copyOf().apply { this[29] = (this[29].toInt() xor 1).toByte() })
        assertThrows(IOException::class.java) { PcImageInbox.validatePng(file) }
        file.writeBytes("not a PNG".toByteArray())
        assertThrows(IOException::class.java) { PcImageInbox.validatePng(file) }
        RandomAccessFile(file, "rw").use { it.setLength(PcImageInbox.MAX_BYTES.toLong() + 1) }
        assertThrows(IOException::class.java) { PcImageInbox.validatePng(file) }
    }

    @Test fun readyStatusChecksLiveCacheAndReleaseDoesNotTouchOtherProviders() {
        val uri = nextUri()
        val token = PcImageInbox.tokenFor(uri)
        assertEquals("MISSING", PcImageInbox.status(context, token).status)
        file(uri).writeBytes(png())
        assertEquals("READY", PcImageInbox.status(context, token).status)
        PcImageInbox.release(context, Uri.parse("content://photos.example/$token"))
        assertTrue(file(uri).isFile)
        assertEquals("READY", PcImageInbox.status(context, token).status)
        PcImageInbox.release(context, uri)
        assertEquals("MISSING", PcImageInbox.status(context, token).status)
    }

    @Test fun staleReadyAndPartFilesArePrunedBeforeStatusReads() {
        val uri = nextUri()
        val token = PcImageInbox.tokenFor(uri)
        val ready = file(uri).apply { writeBytes(png()); setLastModified(System.currentTimeMillis() - PcImageInbox.TTL_MILLIS - 1000) }
        val part = File(ready.parentFile, "$token.part").apply {
            writeBytes(byteArrayOf(1)); setLastModified(System.currentTimeMillis() - PcImageInbox.TTL_MILLIS - 1000)
        }
        assertEquals("MISSING", PcImageInbox.status(context, token).status)
        assertFalse(ready.exists())
        assertFalse(part.exists())
    }

    @Test fun callAndFileEntryPointsEnforceDumpForAnExternalUidBeforeReadingTokens() {
        var checks = 0
        val deniedContext = object : ContextWrapper(context) {
            override fun enforceCallingPermission(permission: String, message: String?) {
                assertEquals(android.Manifest.permission.DUMP, permission)
                checks++
                throw SecurityException("Synthetic permission denial")
            }
        }
        val provider = PcImageProvider().apply { attachInfo(deniedContext, ProviderInfo().apply { authority = PcImageInbox.AUTHORITY }) }
        ShadowBinder.setCallingUid(Process.myUid() + 1000)
        assertThrows(SecurityException::class.java) { provider.call("status", UUID.randomUUID().toString(), null) }
        assertThrows(SecurityException::class.java) { provider.openFile(Uri.parse("content://invalid/path"), "w") }
        assertThrows(SecurityException::class.java) { provider.getType(Uri.parse("content://invalid/path")) }
        assertEquals(3, checks)
        ShadowBinder.setCallingUid(Process.myUid())
        assertEquals("MISSING", provider.call("status", UUID.randomUUID().toString(), null).getString("status"))
        assertEquals(3, checks)
    }

    private fun nextUri(): Uri = PcImageInbox.uriFor(UUID.randomUUID().toString()).also(owned::add)
    private fun file(uri: Uri) = File(context.cacheDir, "pc-images/${PcImageInbox.tokenFor(uri)}.png").also { it.parentFile?.mkdirs() }

    /** A complete synthetic 1x1 RGBA PNG with ordinary zlib data and valid chunk CRCs. */
    private fun png(): ByteArray = ByteArrayOutputStream().use { output ->
        output.write(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
        fun chunk(type: String, bytes: ByteArray) {
            val label = type.toByteArray(Charsets.US_ASCII)
            val crc = CRC32().apply { update(label); update(bytes) }
            val data = DataOutputStream(output)
            data.writeInt(bytes.size); data.write(label); data.write(bytes); data.writeInt(crc.value.toInt())
        }
        val header = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).apply { writeInt(1); writeInt(1); write(byteArrayOf(8, 6, 0, 0, 0)) }
            buffer.toByteArray()
        }
        val image = ByteArrayOutputStream().use { buffer ->
            DeflaterOutputStream(buffer).use { it.write(byteArrayOf(0, 0, 0, 0, 0xff.toByte())) }
            buffer.toByteArray()
        }
        chunk("IHDR", header); chunk("IDAT", image); chunk("IEND", byteArrayOf())
        output.toByteArray()
    }
}
