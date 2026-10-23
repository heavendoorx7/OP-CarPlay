package com.shilapi.xcertplay

import java.io.File
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.UUID

/** Bounded private copies keep GET_CONTENT documents readable after their temporary grant ends. */
internal object LocalMfiDocumentStore {
    const val DIRECTORY = "local-mfi-documents"
    const val MAX_BYTES = 1024 * 1024
    private val filename = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.(p7b|pk8)")

    fun importDocument(input: InputStream, directory: File, certificate: Boolean): File {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create private document directory" }
        val output = File(directory, "${UUID.randomUUID()}.${if (certificate) "p7b" else "pk8"}")
        val temporary = File(directory, "${output.name}.tmp")
        try {
            temporary.outputStream().use { stream ->
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Import cancelled")
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(count <= MAX_BYTES - total) { "Document exceeds 1 MiB" }
                    stream.write(buffer, 0, count)
                    total += count
                }
                require(total > 0) { "Empty document" }
            }
            check(temporary.renameTo(output)) { "Cannot finish private document import" }
            return output
        } catch (failure: Exception) {
            temporary.delete()
            output.delete()
            throw failure
        }
    }

    fun resolve(directory: File, name: String): File {
        require(filename.matches(name)) { "Unknown private document" }
        val file = File(directory, name).canonicalFile
        require(file.parentFile == directory.canonicalFile && file.isFile) { "Unknown private document" }
        return file
    }
}
