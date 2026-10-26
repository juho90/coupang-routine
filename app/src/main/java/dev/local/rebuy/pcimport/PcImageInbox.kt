package dev.local.rebuy.pcimport

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32

/** Temporary, application-private PNG inbox for an explicitly requested PC transfer. */
object PcImageInbox {
    const val AUTHORITY = "dev.local.rebuy.pc-images"
    internal const val MAX_BYTES = 24 * 1024 * 1024
    internal const val TTL_MILLIS = 10 * 60 * 1000L
    private const val MAX_PENDING = 6
    private const val MAX_RECORDS = 32
    private val tokenPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val cacheName = Regex("([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.(?:png|part)")
    private val lock = Any()
    private val records = linkedMapOf<String, Record>()
    private val writers by lazy {
        ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(4), { task ->
            Thread(task, "pc-image-inbox").apply { isDaemon = true }
        }, ThreadPoolExecutor.AbortPolicy())
    }

    data class Status(val status: String, val reason: String? = null)

    private class Record(val key: String, val token: String, val createdAt: Long, val readEnd: ParcelFileDescriptor) {
        var status = Status("WRITING")
        var updatedAt = createdAt
    }

    fun uriFor(token: String): Uri {
        requireToken(token)
        return Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(token).build()
    }

    internal fun tokenFor(uri: Uri): String {
        require(uri.scheme == "content" && uri.encodedAuthority == AUTHORITY &&
            uri.encodedQuery == null && uri.encodedFragment == null && uri.pathSegments.size == 1
        ) { "올바른 PC 이미지 주소가 아닙니다." }
        val token = uri.pathSegments.single()
        requireToken(token)
        require(uri.encodedPath == "/$token") { "올바른 PC 이미지 주소가 아닙니다." }
        return token
    }

    internal fun requireToken(token: String) {
        require(tokenPattern.matches(token)) { "올바른 PC 이미지 식별자가 아닙니다." }
    }

    fun status(context: Context, token: String): Status {
        requireToken(token)
        synchronized(lock) {
            val directory = directory(context)
            pruneLocked(directory)
            val key = key(directory, token)
            val ready = File(directory, "$token.png")
            val record = records[key]
            if (ready.isFile && ready.length() in 1..MAX_BYTES.toLong()) return Status("READY")
            if (record?.status?.status == "READY") {
                records.remove(key)
                return Status("MISSING")
            }
            return record?.status ?: Status("MISSING")
        }
    }

    /** Gallery/shared provider URIs are owned by their provider and are deliberately ignored. */
    fun release(context: Context, uri: Uri) {
        val token = try { tokenFor(uri) } catch (_: IllegalArgumentException) { return }
        synchronized(lock) {
            val directory = directory(context)
            pruneLocked(directory)
            records.remove(key(directory, token))?.let { runCatching { it.readEnd.closeWithError("RELEASED") } }
            File(directory, "$token.part").delete()
            File(directory, "$token.png").delete()
        }
    }

    internal fun openRead(context: Context, uri: Uri): ParcelFileDescriptor {
        val token = tokenFor(uri)
        synchronized(lock) {
            val directory = directory(context)
            pruneLocked(directory)
            val file = File(directory, "$token.png")
            if (!file.isFile || file.length() !in 1..MAX_BYTES.toLong()) throw FileNotFoundException("Image is not ready")
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }

    internal fun openWrite(context: Context, uri: Uri): ParcelFileDescriptor {
        val token = tokenFor(uri)
        synchronized(lock) {
            val directory = directory(context)
            pruneLocked(directory)
            val key = key(directory, token)
            if (records.containsKey(key) || File(directory, "$token.png").exists() || File(directory, "$token.part").exists()) {
                throw FileNotFoundException("Image token already used")
            }
            if (records.size >= MAX_RECORDS || records.values.count { it.status.status == "WRITING" } >= MAX_PENDING) {
                throw FileNotFoundException("Image inbox is busy")
            }
            val pipe = ParcelFileDescriptor.createReliablePipe()
            val record = Record(key, token, System.currentTimeMillis(), pipe[0])
            records[key] = record
            try {
                writers.execute { receive(directory, record) }
            } catch (_: RuntimeException) {
                record.status = Status("REJECTED", "BUSY")
                runCatching { pipe[0].closeWithError("BUSY") }
                runCatching { pipe[1].closeWithError("BUSY") }
                throw FileNotFoundException("Image inbox is busy")
            }
            return pipe[1]
        }
    }

    private fun receive(directory: File, record: Record) {
        val part = File(directory, "${record.token}.part")
        val ready = File(directory, "${record.token}.png")
        try {
            ParcelFileDescriptor.AutoCloseInputStream(record.readEnd).use { input ->
                FileOutputStream(part).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var count = 0
                    while (true) {
                        val received = input.read(buffer)
                        if (received < 0) break
                        count += received
                        if (count > MAX_BYTES) throw Rejection("TOO_LARGE")
                        output.write(buffer, 0, received)
                    }
                    output.fd.sync()
                }
            }
            validatePng(part)
            synchronized(lock) {
                if (records[record.key] !== record || expired(record.createdAt)) throw Rejection("EXPIRED")
                if (ready.exists() || !part.renameTo(ready)) throw IOException("Cannot finalize image")
                ready.setLastModified(System.currentTimeMillis())
                record.status = Status("READY")
                record.updatedAt = System.currentTimeMillis()
            }
        } catch (failure: Exception) {
            synchronized(lock) {
                if (records[record.key] === record) {
                    record.status = Status("REJECTED", (failure as? Rejection)?.code ?: "IO_ERROR")
                    record.updatedAt = System.currentTimeMillis()
                }
            }
        } finally {
            runCatching { record.readEnd.close() }
            part.delete()
        }
    }

    /** Verify signature, complete chunks/CRCs and bounded dimensions without decoding a full bitmap. */
    internal fun validatePng(file: File) {
        if (!file.isFile || file.length() !in 8..MAX_BYTES.toLong()) throw Rejection("INVALID_PNG")
        try {
            DataInputStream(FileInputStream(file)).use { input ->
                val signature = ByteArray(8).also(input::readFully)
                if (!signature.contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))) throw Rejection("INVALID_PNG")
                var first = true
                var hasData = false
                var ended = false
                var width = 0
                var height = 0
                val buffer = ByteArray(8192)
                while (!ended) {
                    val length = input.readInt()
                    if (length < 0 || length > MAX_BYTES) throw Rejection("INVALID_PNG")
                    val typeBytes = ByteArray(4).also(input::readFully)
                    if (typeBytes.any { it.toInt() !in 'A'.code..'Z'.code && it.toInt() !in 'a'.code..'z'.code }) throw Rejection("INVALID_PNG")
                    val type = String(typeBytes, Charsets.US_ASCII)
                    if (first && (type != "IHDR" || length != 13)) throw Rejection("INVALID_PNG")
                    if (!first && type == "IHDR") throw Rejection("INVALID_PNG")
                    val crc = CRC32().apply { update(typeBytes) }
                    if (type == "IHDR") {
                        val header = ByteArray(13).also(input::readFully)
                        crc.update(header)
                        val dimensions = ByteBuffer.wrap(header)
                        width = dimensions.int
                        height = dimensions.int
                        if (width !in 1..60_000 || height !in 1..60_000 || width.toLong() * height > 160_000_000L) throw Rejection("INVALID_PNG")
                    } else {
                        var remaining = length
                        while (remaining > 0) {
                            val count = minOf(remaining, buffer.size)
                            input.readFully(buffer, 0, count)
                            crc.update(buffer, 0, count)
                            remaining -= count
                        }
                    }
                    if ((input.readInt().toLong() and 0xffff_ffffL) != crc.value) throw Rejection("INVALID_PNG")
                    if (type == "IDAT") hasData = true
                    if (type == "IEND") {
                        if (length != 0 || !hasData || input.read() != -1) throw Rejection("INVALID_PNG")
                        ended = true
                    }
                    first = false
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                if (bounds.outWidth != width || bounds.outHeight != height) throw Rejection("INVALID_PNG")
            }
        } catch (failure: Rejection) {
            throw failure
        } catch (_: EOFException) {
            throw Rejection("INVALID_PNG")
        } catch (_: IOException) {
            throw Rejection("INVALID_PNG")
        }
    }

    private fun directory(context: Context): File = File(context.applicationContext.cacheDir, "pc-images").also {
        if (!it.isDirectory && !it.mkdirs()) throw FileNotFoundException("Image inbox unavailable")
    }

    private fun key(directory: File, token: String) = "${directory.absolutePath}/$token"
    private fun expired(timestamp: Long): Boolean = System.currentTimeMillis() - timestamp >= TTL_MILLIS

    private fun pruneLocked(directory: File) {
        val prefix = directory.absolutePath + "/"
        val iterator = records.entries.iterator()
        while (iterator.hasNext()) {
            val (_, record) = iterator.next()
            val expiryBase = if (record.status.status == "WRITING") record.createdAt else record.updatedAt
            if (record.key.startsWith(prefix) && expired(expiryBase)) {
                runCatching { record.readEnd.closeWithError("EXPIRED") }
                iterator.remove()
                File(directory, "${record.token}.part").delete()
                File(directory, "${record.token}.png").delete()
            }
        }
        directory.listFiles()?.forEach { file ->
            if (cacheName.matches(file.name) && expired(file.lastModified())) file.delete()
        }
    }

    private class Rejection(val code: String) : IOException(code)
}
