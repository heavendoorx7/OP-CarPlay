package com.shilapi.xcertplay.mfi

import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class BundledMfiPackagingTest {
    @Test fun deploymentPairLoadsThroughProductionAuthenticatorAndSigns() {
        val directory = System.getenv("OP_MFI_ASSETS_DIR")
        assumeTrue("No private deployment assets in this build", directory != null)
        val certificate = File(directory!!, "op_mfi/certificate.p7b")
        val privateKey = File(directory, "op_mfi/identity.pk8")
        val client = certificate.inputStream().use { cert ->
            privateKey.inputStream().use { key -> LocalMfiAuthenticationClient.load(cert, key) }
        }
        // load() checks that the certificate and key match. Never print either on failure.
        assertTrue(client.protocolMajor() in 2..3)
        assertTrue(client.readCertificate(certificate.length().toInt()).contentEquals(certificate.readBytes()))
        assertTrue(client.signChallenge(ByteArray(32) { (it + 1).toByte() }).isNotEmpty())
    }
}
