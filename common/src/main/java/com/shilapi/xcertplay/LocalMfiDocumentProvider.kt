package com.shilapi.xcertplay

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/** App-only, read-only documents; no URI grants, backup, external storage, or network. */
class LocalMfiDocumentProvider : ContentProvider() {
    override fun onCreate() = true

    private fun document(uri: Uri): File {
        if (Binder.getCallingUid() != Process.myUid()) throw SecurityException("Private provider")
        val ctx = requireNotNull(context)
        if (uri.authority != "${ctx.packageName}.local-mfi" || uri.query != null || uri.fragment != null ||
            uri.pathSegments.size != 1) throw FileNotFoundException("Unknown private document")
        return try {
            LocalMfiDocumentStore.resolve(directory(ctx), uri.pathSegments.single())
        } catch (_: Exception) {
            throw FileNotFoundException("Unknown private document")
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read-only document")
        return ParcelFileDescriptor.open(document(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String {
        document(uri)
        return "application/octet-stream"
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val file = document(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map { when (it) {
                OpenableColumns.DISPLAY_NAME -> "已导入 · ${if (file.extension == "p7b") "certificate.p7b" else "identity.pk8"}"
                OpenableColumns.SIZE -> file.length()
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
        internal fun directory(context: Context) = File(context.noBackupFilesDir, LocalMfiDocumentStore.DIRECTORY)
        internal fun uri(context: Context, file: File): Uri = Uri.Builder().scheme("content")
            .authority("${context.packageName}.local-mfi").appendPath(file.name).build()
    }
}
