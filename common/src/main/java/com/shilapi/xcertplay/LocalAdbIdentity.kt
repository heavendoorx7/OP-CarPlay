package com.shilapi.xcertplay

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.interfaces.RSAPublicKey

/** Per-install AndroidKeyStore identity; no embedded/shared key and no exported private key. */
internal class LocalAdbIdentity : LocalAdbProtocol.Identity {
    private val entry: KeyStore.PrivateKeyEntry
    init {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setKeySize(2048).setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setDigests(KeyProperties.DIGEST_NONE).setUserAuthenticationRequired(false).build())
            }.generateKeyPair()
        }
        entry = store.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
    }

    override fun signToken(token: ByteArray): ByteArray {
        require(token.size == 20)
        // ADB sends a SHA-1 digest; do not hash it a second time. Prefix its DigestInfo.
        val prefix = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)
        return Signature.getInstance("NONEwithRSA").run {
            initSign(entry.privateKey); update(prefix); update(token); sign()
        }
    }
    override fun publicKey(): ByteArray = LocalAdbProtocol.publicKeyPayload(entry.certificate.publicKey as RSAPublicKey)
    companion object { private const val ALIAS = "op.local-adb.authorization.v1" }
}
