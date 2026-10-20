package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class ViewAreaAcknowledgementsTest {
    private fun response(cseq: Int, code: Int = 200, body: ByteArray = byteArrayOf()): RtspMessage.Request {
        val wire = "RTSP/1.0 $code OK\r\nCSeq: $cseq\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray() + body
        return RtspMessage.parseMessages(wire).messages.single()
    }

    @Test fun sendingDoesNotChangeTouchGeometryUntilAcknowledged() {
        val state = ViewAreaAcknowledgements(0)
        state.begin(5, 1) {}
        assertEquals(0, state.currentIndex)
        assertEquals(200, state.receive(response(5))!!.status)
        assertEquals(1, state.currentIndex)
        assertNull(state.receive(response(5)))
    }

    @Test fun rapidSplitFullSplitIgnoresOldAndUnrelatedResponses() {
        val state = ViewAreaAcknowledgements(0)
        state.begin(1, 1) {}
        state.begin(2, 0) {}
        state.begin(3, 1) {}
        assertNull(state.receive(response(2)))
        assertNull(state.receive(response(1)))
        assertNull(state.receive(response(99)))
        assertEquals(0, state.currentIndex)
        assertEquals(1, state.receive(response(3))!!.pending.index)
        assertEquals(1, state.currentIndex)
    }

    @Test fun rejectionPreservesPreviousGeometry() {
        val state = ViewAreaAcknowledgements(1)
        state.begin(2, 0) {}
        assertEquals(500, state.receive(response(2, 500))!!.status)
        assertEquals(1, state.currentIndex)
    }

    @Test fun transportSuccessWithProtocolErrorIsNotSuccessfulResize() {
        val state = ViewAreaAcknowledgements(0)
        state.begin(2, 1) {}
        val body = BplistCodec.encode(mapOf("error" to -6705L))
        assertEquals(-2, state.receive(response(2, body = body))!!.status)
        assertEquals(0, state.currentIndex)
    }

    @Test fun closeCancelsLateAcknowledgement() {
        val state = ViewAreaAcknowledgements(0)
        state.begin(2, 1) {}
        state.cancel()
        assertNull(state.receive(response(2)))
        assertEquals(0, state.currentIndex)
    }
}
