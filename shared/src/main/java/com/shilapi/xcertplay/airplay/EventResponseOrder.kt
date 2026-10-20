package com.shilapi.xcertplay.airplay

import java.util.ArrayDeque

/**
 * iPhone's event HTTP responses may omit CSeq. Account for EVERY outbound
 * request (including HID/night mode), so an earlier reply cannot acknowledge
 * a later view-area command. Sequenced replies can still match explicitly.
 */
internal class EventResponseOrder(private val capacity: Int = 1024) {
    private val outstanding = ArrayDeque<Int>()
    private var orderedMatchingReliable = true

    init { require(capacity > 0) }

    @Synchronized fun sent(cseq: Int) {
        if (outstanding.size >= capacity) {
            outstanding.removeFirst()
            // Never guess which unnumbered reply belongs to an evicted request.
            orderedMatchingReliable = false
        }
        outstanding.addLast(cseq)
    }

    @Synchronized fun received(explicitCseq: Int?): Int? {
        if (explicitCseq != null) {
            outstanding.remove(explicitCseq)
            return explicitCseq
        }
        return if (orderedMatchingReliable) outstanding.pollFirst() else null
    }

    @Synchronized fun clear() {
        outstanding.clear()
        orderedMatchingReliable = true
    }
}
