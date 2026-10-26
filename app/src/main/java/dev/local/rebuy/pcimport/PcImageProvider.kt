package dev.local.rebuy.pcimport

import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.FileNotFoundException

/** Shell/DUMP-only development transfer boundary; ordinary apps cannot write or inspect this inbox. */
class PcImageProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    private fun enforceCaller() {
        if (Binder.getCallingUid() != Process.myUid()) {
            requireNotNull(context).enforceCallingPermission(Manifest.permission.DUMP, "PC image transfer requires shell permission")
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        enforceCaller()
        return when (mode) {
            "w" -> PcImageInbox.openWrite(requireNotNull(context), uri)
            "r" -> PcImageInbox.openRead(requireNotNull(context), uri)
            else -> throw FileNotFoundException("Only image read or write is supported")
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        // ContentResolver.call does not automatically apply the provider's read/write checks.
        enforceCaller()
        require(method == "status" && arg != null) { "Unsupported image inbox request" }
        val result = PcImageInbox.status(requireNotNull(context), arg)
        return Bundle().apply {
            putString("status", result.status)
            result.reason?.let { putString("reason", it) }
        }
    }

    override fun getType(uri: Uri): String {
        enforceCaller()
        PcImageInbox.tokenFor(uri)
        return "image/png"
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        enforceCaller()
        throw UnsupportedOperationException("Image inbox does not expose a listing")
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        enforceCaller()
        throw UnsupportedOperationException("Use an explicit image token")
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        enforceCaller()
        throw UnsupportedOperationException("Image inbox does not update entries")
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        enforceCaller()
        throw UnsupportedOperationException("Image inbox does not expose deletion")
    }
}
