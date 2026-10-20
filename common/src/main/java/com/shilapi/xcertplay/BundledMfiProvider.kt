package com.shilapi.xcertplay

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Process
import android.provider.OpenableColumns
import java.io.FileNotFoundException

/** Same-app, read-only access to the two deployment-supplied APK assets. */
class BundledMfiProvider : ContentProvider() {
    override fun onCreate() = true
    private fun filename(uri: Uri): String {
        if (Binder.getCallingUid() != Process.myUid()) throw SecurityException("Private provider")
        return BundledMfiSelection.assetName(requireNotNull(context).packageName, uri.toString())
            ?: throw FileNotFoundException("Unknown bundled document")
    }
    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read-only document")
        return requireNotNull(context).assets.openFd("op_mfi/${filename(uri)}")
    }
    override fun getType(uri: Uri): String {
        filename(uri)
        return "application/octet-stream"
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val name = filename(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map { column -> when (column) {
                OpenableColumns.DISPLAY_NAME -> "内置 · $name"
                OpenableColumns.SIZE -> openAssetFile(uri, "r").use { it.length }
                else -> null
            } })
        }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read-only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read-only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read-only")

    companion object {
        fun available(context: Context): Boolean = listOf("certificate.p7b", "identity.pk8").all { name ->
            runCatching { context.assets.openFd("op_mfi/$name").use { it.length > 0 } }.getOrDefault(false)
        }
    }
}
