package com.shilapi.xcertplay

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class LocalMfiDocumentStoreTest {
    private fun inDirectory(test: (File) -> Unit) {
        val root = Files.createTempDirectory("op-mfi-import-test").toFile()
        try { test(File(root, "documents")) } finally { root.deleteRecursively() }
    }

    @Test fun importedPairHasIndependentPrivateCopies() = inDirectory { dir ->
        val certificate = byteArrayOf(1, 2, 3)
        val key = byteArrayOf(4, 5, 6)
        val a = LocalMfiDocumentStore.importDocument(ByteArrayInputStream(certificate), dir, true)
        val b = LocalMfiDocumentStore.importDocument(ByteArrayInputStream(key), dir, false)
        assertEquals("p7b", a.extension)
        assertEquals("pk8", b.extension)
        assertArrayEquals(certificate, LocalMfiDocumentStore.resolve(dir, a.name).readBytes())
        assertArrayEquals(key, LocalMfiDocumentStore.resolve(dir, b.name).readBytes())
    }

    @Test fun reselectionDoesNotOverwritePreviouslySavedFile() = inDirectory { dir ->
        val first = LocalMfiDocumentStore.importDocument(ByteArrayInputStream(byteArrayOf(7)), dir, true)
        val second = LocalMfiDocumentStore.importDocument(ByteArrayInputStream(byteArrayOf(8)), dir, true)
        assertNotEquals(first, second)
        assertArrayEquals(byteArrayOf(7), first.readBytes())
        assertArrayEquals(byteArrayOf(8), second.readBytes())
    }

    @Test fun emptyDocumentIsRejectedAndLeavesNoPartialCopy() = inDirectory { dir ->
        assertThrows(IllegalArgumentException::class.java) {
            LocalMfiDocumentStore.importDocument(ByteArrayInputStream(byteArrayOf()), dir, true)
        }
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test fun oversizedDocumentIsRejectedAndLeavesNoPartialCopy() = inDirectory { dir ->
        assertThrows(IllegalArgumentException::class.java) {
            LocalMfiDocumentStore.importDocument(ByteArrayInputStream(ByteArray(LocalMfiDocumentStore.MAX_BYTES + 1)), dir, false)
        }
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test fun documentAtLimitIsCopiedWithoutTruncation() = inDirectory { dir ->
        val bytes = ByteArray(LocalMfiDocumentStore.MAX_BYTES) { (it % 251).toByte() }
        val file = LocalMfiDocumentStore.importDocument(ByteArrayInputStream(bytes), dir, true)
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun providerReadFailureLeavesNoPartialCopy() = inDirectory { dir ->
        val broken = object : InputStream() { override fun read(): Int = throw IOException("synthetic provider failure") }
        assertThrows(IOException::class.java) { LocalMfiDocumentStore.importDocument(broken, dir, true) }
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test fun importedNamesCannotResolveOtherFiles() = inDirectory { dir ->
        for (name in listOf("../other.pk8", "/tmp/other.pk8", "identity.pk8", "certificate.p7b?x=1", "x.tmp", "")) {
            assertThrows(IllegalArgumentException::class.java) { LocalMfiDocumentStore.resolve(dir, name) }
        }
    }
}
