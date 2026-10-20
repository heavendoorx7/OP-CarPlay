package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64

class LocalAdbProtocolTest {
    private val p = LocalAdbProtocol
    private class Key : LocalAdbProtocol.Identity {
        var signatures = 0
        var offers = 0
        override fun signToken(token: ByteArray): ByteArray { signatures++; return ByteArray(256) }
        override fun publicKey(): ByteArray { offers++; return "test-key\u0000".toByteArray() }
    }
    private fun frame(cmd: Int, a: Int = 0, b: Int = 0, data: ByteArray = byteArrayOf()) = p.encode(LocalAdbProtocol.Packet(cmd, a, b, data))
    private fun connect(features: String = "shell_v2,cmd") = frame(p.CNXN, p.VERSION, 4096, "device::features=$features;\u0000".toByteArray())
    private fun protocol(bytes: ByteArray, key: Key = Key(), out: ByteArrayOutputStream = ByteArrayOutputStream()) = LocalAdbProtocol(ByteArrayInputStream(bytes), out, key)
    private fun shell(channel: Int, bytes: ByteArray) = ByteBuffer.allocate(5 + bytes.size).order(ByteOrder.LITTLE_ENDIAN).put(channel.toByte()).putInt(bytes.size).put(bytes).array()
    private fun result(text: String, exit: Int = 0) = shell(1, text.toByteArray()) + shell(3, byteArrayOf(exit.toByte()))

    @Test fun trustedConnectionDoesNotOfferOrSignKey() {
        val key = Key(); protocol(connect(), key).connect { fail("unnecessary consent") }
        assertEquals(0, key.signatures); assertEquals(0, key.offers)
    }
    @Test fun unknownKeySignsThenOffersExactlyOnceBeforeConnection() {
        val key = Key(); var prompts = 0
        protocol(frame(p.AUTH, 1, data = ByteArray(20)) + frame(p.AUTH, 1, data = ByteArray(20)) + connect(), key).connect { prompts++ }
        assertEquals(1, key.signatures); assertEquals(1, key.offers); assertEquals(1, prompts)
    }
    @Test fun rejectedAuthenticationCannotOpenAShell() {
        val out = ByteArrayOutputStream(); val auth = frame(p.AUTH, 1, data = ByteArray(20))
        assertThrows(LocalAdbProtocol.ProtocolError::class.java) { protocol(auth + auth + auth, out = out).connect {} }
        assertFalse(out.toString("ISO-8859-1").contains("shell,v2,raw:"))
    }
    @Test fun corruptFrameAndOversizedLengthAreRejected() {
        val badSum = connect().apply { this[16] = (this[16].toInt() xor 1).toByte() }
        val badMagic = connect().apply { this[20] = (this[20].toInt() xor 1).toByte() }
        val large = connect().apply { ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putInt(12, 1_000_000) }
        for (bytes in listOf(badSum, badMagic, large)) assertThrows(LocalAdbProtocol.ProtocolError::class.java) { protocol(bytes).connect {} }
    }
    @Test fun shellV2IsRequiredForReliableExitStatus() {
        assertThrows(LocalAdbProtocol.ProtocolError::class.java) { protocol(connect("cmd,stat_v2")).connect {} }
    }
    @Test fun fragmentedShellV2AndMultipleCommandsAreHandled() {
        val first = result("2000\n"); val second = result("WRITE_SETTINGS: allow\n")
        val bytes = frame(p.OKAY, 8, 1) + frame(p.WRTE, 8, 1, first.copyOfRange(0, 3)) + frame(p.WRTE, 8, 1, first.copyOfRange(3, first.size)) + frame(p.CLSE, 8, 1) +
            frame(p.CLSE, 8, 1) + frame(p.OKAY, 9, 2) + frame(p.WRTE, 9, 2, second) + frame(p.CLSE, 9, 2)
        val client = protocol(bytes)
        assertEquals("2000\n", client.execute(p.UID_ARGS).output)
        assertEquals("WRITE_SETTINGS: allow\n", client.execute(HotspotGrantCommand.queryArgs).output)
    }
    @Test fun noCallerSuppliedCommands() {
        val out = ByteArrayOutputStream(); val client = protocol(byteArrayOf(), out = out)
        for (command in listOf(listOf("reboot"), listOf("sh", "-c", "id"), HotspotGrantCommand.grantArgs.dropLast(1) + "default")) {
            assertThrows(IllegalArgumentException::class.java) { client.execute(command) }
        }
        assertEquals(0, out.size())
    }
    @Test fun mismatchedStreamCannotProvideSuccess() {
        assertThrows(LocalAdbProtocol.ProtocolError::class.java) {
            protocol(frame(p.OKAY, 3, 1) + frame(p.WRTE, 4, 1, result(""))).execute(p.UID_ARGS)
        }
    }
    @Test fun exitStatusAndStderrArePreserved() {
        val r = p.decodeShell(shell(2, "Error: denied".toByteArray()) + shell(3, byteArrayOf(1)))
        assertEquals(1, r.exit); assertEquals("Error: denied", r.output)
    }
    @Test fun missingOrMalformedExitAndTrailingDataFailClosed() {
        for (bytes in listOf(shell(1, "WRITE_SETTINGS: allow".toByteArray()), shell(3, byteArrayOf()), result("") + byteArrayOf(1), shell(3, ByteArray(4)))) {
            assertThrows(LocalAdbProtocol.ProtocolError::class.java) { p.decodeShell(bytes) }
        }
    }
    @Test fun outputFloodIsBounded() {
        assertThrows(LocalAdbProtocol.ProtocolError::class.java) { p.decodeShell(result("x".repeat(4097))) }
    }
    @Test fun rsaPublicEncodingHasCorrectMontgomeryParametersAndNoPrivateData() {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pub = key.public as RSAPublicKey
        val payload = p.publicKeyPayload(pub).toString(Charsets.US_ASCII)
        assertTrue(payload.endsWith(" OP@carplay\u0000"))
        val bytes = Base64.getDecoder().decode(payload.substringBefore(' ')); assertEquals(524, bytes.size)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, b.int)
        val n0 = BigInteger.valueOf(b.int.toLong() and 0xffffffffL)
        val modulus = ByteArray(256).also { b.get(it) }; val rr = ByteArray(256).also { b.get(it) }
        assertEquals(pub.modulus, BigInteger(1, modulus.reversedArray()))
        assertEquals(BigInteger.ONE.shiftLeft(4096).mod(pub.modulus), BigInteger(1, rr.reversedArray()))
        assertEquals(BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE), n0.multiply(pub.modulus).mod(BigInteger.ONE.shiftLeft(32)))
        assertEquals(65537, b.int)
    }
}
