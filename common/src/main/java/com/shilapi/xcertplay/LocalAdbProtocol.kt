package com.shilapi.xcertplay

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/** Small, one-stream ADB transport. No sync, forwarding, uploads or caller-supplied commands. */
internal class LocalAdbProtocol(
    input: InputStream,
    private val output: OutputStream,
    private val identity: Identity,
) {
    interface Identity {
        fun signToken(token: ByteArray): ByteArray
        fun publicKey(): ByteArray
    }
    data class Packet(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray = byteArrayOf())
    class ProtocolError : IOException("Invalid or unsupported local ADB response")
    private val input = DataInputStream(input)
    private var nextId = 1

    fun connect(waitingForUser: () -> Unit) {
        send(Packet(CNXN, VERSION, MAX_PAYLOAD, "host::features=shell_v2;\u0000".toByteArray()))
        var signed = false
        var offered = false
        repeat(4) {
            val p = read()
            when (p.command) {
                CNXN -> {
                    if (p.arg0 < VERSION || p.arg1 < 4096 || !p.data.toString(Charsets.UTF_8).startsWith("device::")) throw ProtocolError()
                    val features = p.data.toString(Charsets.UTF_8).trimEnd('\u0000').substringAfter("features=", "").substringBefore(';').split(',')
                    if ("shell_v2" !in features) throw ProtocolError()
                    return
                }
                AUTH -> {
                    if (p.arg0 != 1 || p.arg1 != 0 || p.data.size != 20) throw ProtocolError()
                    if (!signed) {
                        send(Packet(AUTH, 2, 0, identity.signToken(p.data)))
                        signed = true
                    } else if (!offered) {
                        waitingForUser()
                        send(Packet(AUTH, 3, 0, identity.publicKey()))
                        offered = true
                    } else throw ProtocolError()
                }
                else -> throw ProtocolError()
            }
        }
        throw ProtocolError()
    }

    /** Allowlist is checked before opening a stream, including during tests. */
    fun execute(args: List<String>): HotspotGrantCommand.Result {
        require(args == UID_ARGS || args == HotspotGrantCommand.grantArgs || args == HotspotGrantCommand.queryArgs
            || args == P7InstrumentCommand.startArgs)
        val id = nextId++
        val command = if(args == P7InstrumentCommand.startArgs) P7InstrumentCommand.shellLine()
            else args.joinToString(" ")
        send(Packet(OPEN, id, 0, ("shell,v2,raw:" + command + "\u0000").toByteArray()))
        var remote = 0
        val bytes = ByteArrayOutputStream()
        repeat(64) {
            val p = read()
            // A close already in flight for a completed stream must not poison the next command.
            if (p.command == CLSE && p.arg1 in 1 until id && p.data.isEmpty()) return@repeat
            if (p.arg1 != id) throw ProtocolError()
            when (p.command) {
                OKAY -> {
                    if (remote != 0 || p.arg0 == 0 || p.data.isNotEmpty()) throw ProtocolError()
                    remote = p.arg0
                }
                WRTE -> {
                    if (remote == 0 || p.arg0 != remote || bytes.size() + p.data.size > MAX_SHELL_BYTES) throw ProtocolError()
                    bytes.write(p.data)
                    send(Packet(OKAY, id, remote))
                }
                CLSE -> {
                    if (p.data.isNotEmpty() || remote == 0 || p.arg0 != remote) throw ProtocolError()
                    // ADB CLOSE is terminal and must not be echoed; that can elicit a stale CLOSE.
                    return decodeShell(bytes.toByteArray())
                }
                else -> throw ProtocolError()
            }
        }
        throw ProtocolError()
    }

    private fun read(): Packet {
        val header = ByteArray(24)
        input.readFully(header)
        val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = b.int; val arg0 = b.int; val arg1 = b.int; val length = b.int; val sum = b.int; val magic = b.int
        if (magic != command.inv() || length !in 0..MAX_PAYLOAD) throw ProtocolError()
        val data = ByteArray(length)
        input.readFully(data)
        if (checksum(data) != sum) throw ProtocolError()
        return Packet(command, arg0, arg1, data)
    }

    private fun send(p: Packet) {
        output.write(encode(p)); output.flush()
    }

    companion object {
        const val CNXN = 0x4e584e43
        const val AUTH = 0x48545541
        const val OPEN = 0x4e45504f
        const val OKAY = 0x59414b4f
        const val WRTE = 0x45545257
        const val CLSE = 0x45534c43
        const val VERSION = 0x01000000 // Negotiate checksums even with newer adbd.
        const val MAX_PAYLOAD = 4096
        const val MAX_SHELL_BYTES = 16384
        val UID_ARGS: List<String> get() = listOf("/system/bin/id", "-u")

        private fun checksum(data: ByteArray) = data.sumOf { it.toInt() and 255 }
        fun encode(p: Packet): ByteArray {
            require(p.data.size <= MAX_PAYLOAD)
            return ByteBuffer.allocate(24 + p.data.size).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(p.command).putInt(p.arg0).putInt(p.arg1).putInt(p.data.size)
                .putInt(checksum(p.data)).putInt(p.command.inv()).put(p.data).array()
        }

        fun decodeShell(data: ByteArray): HotspotGrantCommand.Result {
            if (data.size > MAX_SHELL_BYTES) throw ProtocolError()
            val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val text = ByteArrayOutputStream()
            var exit: Int? = null
            while (b.hasRemaining()) {
                if (b.remaining() < 5 || exit != null) throw ProtocolError()
                val channel = b.get().toInt() and 255
                val length = b.int
                if (length < 0 || length > b.remaining()) throw ProtocolError()
                when (channel) {
                    1, 2 -> {
                        val chunk = ByteArray(length); b.get(chunk); text.write(chunk)
                        if (text.size() > 4096) throw ProtocolError()
                    }
                    3 -> { if (length != 1) throw ProtocolError(); exit = b.get().toInt() and 255 }
                    else -> throw ProtocolError()
                }
            }
            return HotspotGrantCommand.Result(exit ?: throw ProtocolError(), text.toString("UTF-8"))
        }

        /** Android ADB public key format, not X.509. Private key never leaves AndroidKeyStore. */
        fun publicKeyPayload(key: RSAPublicKey): ByteArray {
            require(key.modulus.bitLength() == 2048 && key.publicExponent == BigInteger.valueOf(65537))
            val n = key.modulus
            val two32 = BigInteger.ONE.shiftLeft(32)
            val n0inv = n.mod(two32).modInverse(two32).negate().mod(two32)
            fun little256(value: BigInteger): ByteArray {
                val big = value.toByteArray().reversedArray()
                return ByteArray(256) { if (it < big.size) big[it] else 0 }
            }
            val data = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(64).putInt(n0inv.toInt()).put(little256(n))
                .put(little256(BigInteger.ONE.shiftLeft(4096).mod(n))).putInt(65537).array()
            return (Base64.getEncoder().encodeToString(data) + " OP@carplay\u0000").toByteArray(Charsets.US_ASCII)
        }
    }
}
