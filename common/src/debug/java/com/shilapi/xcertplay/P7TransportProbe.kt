package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.network.CarPlayVpnService
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger

/** Debug-only loopback test. Uses a temporary identity, no real MFi or phone. */
internal object P7TransportProbe {
    fun run(): String {
        val result = StringBuilder()
        val service = CarPlayVpnService()
        val port = 38127
        val ended = AtomicInteger()
        val errors = AtomicInteger()
        var client: Socket? = null
        try {
            val config = AirPlayConfig(
                deviceName = "P7 loopback test", deviceId = "02:00:00:00:00:01",
                btMac = "02:00:00:00:00:02", sourceVersion = "1.3.3",
                main = AirPlayDisplayConfig(2400, 990), port = port,
            )
            val attached = service.attachWireless(
                config, AirPlayIdentity.generate(), PairingStore(), null,
                object : AirPlaySessionListener {
                    override fun onSessionEnded(session: AirPlaySession) { ended.incrementAndGet() }
                    override fun onTransportError(message: String) { errors.incrementAndGet() }
                },
                object : AirPlayMediaHandler {},
            )
            check(attached == CarPlayVpnService.AttachResult.Started)
            client = connect(port)
            check(readSize(client) == (2400 to 990))
            result.appendLine("PASS initial /info 2400x990")
            for ((width, height) in listOf(1440 to 990, 2400 to 1200, 2400 to 990)) {
                val before = ended.get()
                check(service.reconfigureWirelessDisplay(AirPlayDisplayConfig(width, height)))
                check(service.isAttached())
                check(ended.get() == before + 1)
                val previous = requireNotNull(client)
                val eof = try { previous.getInputStream().read() == -1 } catch (_: SocketException) { true }
                check(eof) { "Previous session remained open" }
                previous.close()
                client = connect(port)
                check(readSize(client) == (width to height))
                result.appendLine("PASS old session closed; same listener /info ${width}x$height")
            }
            service.detach()
            check(!service.isAttached())
            check(!service.reconfigureWirelessDisplay(AirPlayDisplayConfig(1440, 990)))
            check(errors.get() == 0)
            result.appendLine("PASS detached listener rejects resize; no transport errors")
            result.appendLine("RESULT PASS (loopback only; phone handshake/media not tested)")
        } catch (error: Exception) {
            result.appendLine("RESULT FAIL ${error.javaClass.simpleName}: ${error.message}")
        } finally {
            client?.close()
            service.detach()
        }
        return result.toString()
    }

    private fun connect(port: Int) = Socket("127.0.0.1", port).apply { soTimeout = 3_000 }

    private fun readSize(socket: Socket): Pair<Int, Int> {
        socket.getOutputStream().apply {
            write("GET /info RTSP/1.0\r\nCSeq: 1\r\n\r\n".toByteArray(Charsets.US_ASCII))
            flush()
        }
        val input = socket.getInputStream()
        val head = ByteArrayOutputStream()
        var tail = 0
        while (tail != 0x0d0a0d0a) {
            val b = input.read()
            check(b >= 0 && head.size() < 16_384)
            head.write(b)
            tail = (tail shl 8) or b
        }
        val headers = head.toString("US-ASCII")
        check(headers.lineSequence().first().contains(" 200 "))
        val length = Regex("(?im)^Content-Length: *([0-9]+)").find(headers)!!.groupValues[1].toInt()
        check(length in 1..65_536)
        val bytes = ByteArray(length)
        var count = 0
        while (count < length) {
            val n = input.read(bytes, count, length - count)
            check(n > 0)
            count += n
        }
        val info = BplistCodec.decode(bytes) as Map<*, *>
        val display = (info["displays"] as List<*>).first() as Map<*, *>
        return (display["widthPixels"] as Number).toInt() to (display["heightPixels"] as Number).toInt()
    }
}
