package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class EventResponseOrderTest {
    @Test fun unnumberedTouchRepliesCannotClaimLaterViewCommand() {
        val order = EventResponseOrder()
        order.sent(1) // Touch release.
        order.sent(2) // ViewArea.
        assertEquals(1, order.received(null))
        assertEquals(2, order.received(null))
        assertNull(order.received(null))
    }

    @Test fun explicitOutOfOrderRepliesRemoveOnlyTheirOwnRequest() {
        val order = EventResponseOrder()
        order.sent(10); order.sent(11); order.sent(12)
        assertEquals(12, order.received(12))
        assertEquals(10, order.received(null))
        assertEquals(11, order.received(null))
    }

    @Test fun missingReplyHistoryDisablesGuessingUntilNewSession() {
        val order = EventResponseOrder(capacity = 2)
        order.sent(1); order.sent(2); order.sent(3)
        assertNull(order.received(null))
        assertEquals(3, order.received(3))
        order.clear(); order.sent(4)
        assertEquals(4, order.received(null))
    }
}
