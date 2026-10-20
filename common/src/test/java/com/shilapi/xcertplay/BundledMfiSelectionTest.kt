package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class BundledMfiSelectionTest {
    private val pkg = "com.shilapi.xcertplay.p7"
    @Test fun newInstallSelectsBundledPairOnlyWhenAvailable() {
        val empty = MfiDocumentSelection("", "")
        assertEquals(BundledMfiSelection.documents(pkg), BundledMfiSelection.resolve(pkg, empty, true))
        assertEquals(empty, BundledMfiSelection.resolve(pkg, empty, false))
    }
    @Test fun customAndIncompleteChoicesAreNeverOverwrittenOrMixed() {
        for (pair in listOf(MfiDocumentSelection("content://custom/cert", "content://custom/key"),
            MfiDocumentSelection("content://custom/cert", ""), MfiDocumentSelection("", "content://custom/key"))) {
            assertEquals(pair, BundledMfiSelection.resolve(pkg, pair, true))
        }
    }
    @Test fun providerAcceptsOnlyItsTwoExactPrivateUris() {
        val pair = BundledMfiSelection.documents(pkg)
        assertEquals("certificate.p7b", BundledMfiSelection.assetName(pkg, pair.certificate))
        assertEquals("identity.pk8", BundledMfiSelection.assetName(pkg, pair.privateKey))
        for (uri in listOf(pair.certificate + "/../identity.pk8", pair.privateKey + "?x=1",
            "content://other.bundled-mfi/identity.pk8", "file:///identity.pk8", "content://$pkg.bundled-mfi/%69dentity.pk8")) {
            assertNull(BundledMfiSelection.assetName(pkg, uri))
        }
    }
}
