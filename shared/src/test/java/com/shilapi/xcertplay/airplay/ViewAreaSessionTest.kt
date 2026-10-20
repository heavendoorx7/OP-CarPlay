package com.shilapi.xcertplay.airplay

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** Exercises the real session's encrypted command writer/reader, without MFi or an iPhone. */
class ViewAreaSessionTest {
    @Test fun repeatedViewChangesReuseControlAndEventSocketsWithoutMediaTeardown() {
        fun pair(): Pair<Socket, Socket> = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use {
            val client = Socket(InetAddress.getLoopbackAddress(), it.localPort)
            val server = it.accept()
            client.soTimeout = 3000
            server.soTimeout = 3000
            server to client
        }
        val (control, controller) = pair()
        val (event, phone) = pair()
        val readKey = ByteArray(32) { (it + 1).toByte() }
        val writeKey = ByteArray(32) { (it + 61).toByte() }
        val phoneCipher = ControlCipher(writeKey, readKey)
        val closed = AtomicInteger()
        val ended = AtomicInteger()
        val replies = LinkedBlockingQueue<Int>()
        val error = AtomicReference<Throwable?>()
        val display = P7ViewAreas.configure(AirPlayDisplayConfig(2400, 1200, 400, 200), 10, 0)
        val session = AirPlaySession(control,
            AirPlayConfig("local-test", "02:00:00:00:00:02", "02:00:00:00:00:01", "950.7.1", display),
            AirPlayIdentity(ByteArray(32), ByteArray(32), "test-only"), PairingStore(), null,
            object : AirPlaySessionListener {
                override fun onSessionEnded(session: AirPlaySession) { ended.incrementAndGet() }
            },
            object : AirPlayMediaHandler {
                override fun onSessionClosed(session: AirPlaySession) { closed.incrementAndGet() }
            }, diagnosticLog = {},
        )
        assertFalse(session.updateMainViewArea(1) { fail("Not sent yet") })
        assertEquals(display.viewAreas[0], session.mainViewArea)
        // Inject the already-authenticated event transport. No pairing or auth
        // algorithms are replaced; this fixture only starts after that boundary.
        fun field(name: String, value: Any) {
            AirPlaySession::class.java.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
        }
        field("eventSocket", event)
        field("eventCipher", ControlCipher(readKey, writeKey))
        val reader = Thread {
            try {
                AirPlaySession::class.java.getDeclaredMethod("runEventRead", Socket::class.java)
                    .apply { isAccessible = true }.invoke(session, event)
            } catch (e: Throwable) { error.set(e) }
        }.apply { isDaemon = true; start() }
        var encrypted = byteArrayOf()
        var plain = byteArrayOf()
        fun readCommand(): RtspMessage.Request {
            val buffer = ByteArray(8192)
            while (true) {
                val count = phone.getInputStream().read(buffer)
                check(count > 0)
                encrypted += buffer.copyOf(count)
                val decoded = phoneCipher.decrypt(encrypted)
                encrypted = decoded.rest
                plain += decoded.data
                val parsed = RtspMessage.parseMessages(plain)
                plain = parsed.rest
                if (parsed.messages.isNotEmpty()) return parsed.messages.single()
            }
        }
        try {
            val samePort = event.localPort
            repeat(20) { n ->
                val index = (n + 1) % 2
                // The real iPhone omits CSeq even for ordinary event responses.
                // Earlier key-frame/HID replies must not acknowledge this resize.
                val earlier = if (n >= 10) {
                    assertTrue(session.requestMainKeyFrame())
                    readCommand()
                } else null
                assertTrue(session.updateMainViewArea(index, includeDisplayUuid = n < 10) { replies.put(it) })
                val request = readCommand()
                assertEquals("POST", request.method)
                assertEquals("/command", request.path)
                val command = BplistCodec.decode(request.body) as Map<*, *>
                assertEquals("updateViewArea", command["type"])
                val params = command["params"] as Map<*, *>
                assertEquals(index.toLong(), params["viewAreaIndex"])
                assertEquals(n < 10, params.containsKey("uuid"))
                if (earlier != null) {
                    phone.getOutputStream().write(phoneCipher.encrypt(RtspMessage.buildResponse(
                        earlier.copy(headers = earlier.headers - "cseq"),
                        RtspMessage.Response(status = 200),
                    )))
                    assertNull("An unrelated response must not complete the resize", replies.poll(100, TimeUnit.MILLISECONDS))
                }
                val reply = RtspMessage.buildResponse(
                    if (n >= 10) request.copy(headers = request.headers - "cseq") else request,
                    RtspMessage.Response(status = 200),
                )
                // Fragment the encrypted response to cover real TCP boundaries.
                val wire = phoneCipher.encrypt(reply)
                wire.toList().chunked(3).forEach { phone.getOutputStream().write(it.toByteArray()) }
                phone.getOutputStream().flush()
                assertEquals(200, replies.poll(3, TimeUnit.SECONDS))
                assertEquals(display.viewAreas[index], session.mainViewArea)
                assertEquals(samePort, event.localPort)
                assertFalse(event.isClosed)
                assertFalse(control.isClosed)
                assertEquals(0, closed.get())
                assertEquals(0, ended.get())
            }
            // A rejected request also keeps the same transport and old viewport.
            assertTrue(session.updateMainViewArea(1) { replies.put(it) })
            val rejected = readCommand()
            phone.getOutputStream().write(phoneCipher.encrypt(RtspMessage.buildResponse(rejected, RtspMessage.Response(status = 500))))
            assertEquals(500, replies.poll(3, TimeUnit.SECONDS))
            assertEquals(display.viewAreas[0], session.mainViewArea)
            assertEquals(0, closed.get())

            // A queued old response must not undo a newer full-screen request.
            assertTrue(session.updateMainViewArea(1) { replies.put(it) })
            val stale = readCommand()
            assertTrue(session.updateMainViewArea(0) { replies.put(it) })
            val latest = readCommand()
            phone.getOutputStream().write(phoneCipher.encrypt(
                RtspMessage.buildResponse(stale.copy(headers = stale.headers - "cseq"), RtspMessage.Response(status = 200)) +
                    RtspMessage.buildResponse(latest.copy(headers = latest.headers - "cseq"), RtspMessage.Response(status = 200)),
            ))
            assertEquals(200, replies.poll(3, TimeUnit.SECONDS))
            assertNull(replies.poll(100, TimeUnit.MILLISECONDS))
            assertEquals(display.viewAreas[0], session.mainViewArea)

            // Key-frame requests share this socket and the proven main-screen command.
            assertTrue(session.requestMainKeyFrame())
            val keyFrame = BplistCodec.decode(readCommand().body) as Map<*, *>
            assertEquals("forceKeyFrame", keyFrame["type"])
            assertEquals(emptyMap<String, Any?>(), keyFrame["params"])
            assertEquals(0, closed.get())
        } finally {
            session.close()
            controller.close()
            phone.close()
            reader.join(3000)
        }
        assertFalse(reader.isAlive)
        assertNull(error.get())
        assertEquals(1, closed.get())
        assertEquals(1, ended.get())
    }
}
