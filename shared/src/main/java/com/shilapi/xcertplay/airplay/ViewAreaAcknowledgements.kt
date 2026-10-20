package com.shilapi.xcertplay.airplay

/** Correlates encrypted RTSP replies; stale replies cannot change HID geometry. */
internal class ViewAreaAcknowledgements(initialIndex: Int) {
    data class Pending(val cseq: Int, val index: Int, val reply: (Int) -> Unit)
    data class Completion(val status: Int, val pending: Pending)
    @Volatile var currentIndex = initialIndex
        private set
    private var pending: Pending? = null

    @Synchronized fun begin(cseq: Int, index: Int, reply: (Int) -> Unit) {
        pending = Pending(cseq, index, reply)
    }

    @Synchronized fun cancel() { pending = null }

    @Synchronized fun pendingCseq(): Int? = pending?.cseq

    @Synchronized fun receive(
        message: RtspMessage.Request,
        resolvedCseq: Int? = message.headers["cseq"]?.toIntOrNull(),
    ): Completion? {
        if (!message.method.startsWith("RTSP/") && !message.method.startsWith("HTTP/")) return null
        val request = pending?.takeIf { it.cseq == resolvedCseq } ?: return null
        pending = null
        var status = message.path.toIntOrNull() ?: -1
        if (status in 200..299 && message.body.isNotEmpty()) {
            // A transport-level 200 may still carry an application error.
            val body = runCatching { BplistCodec.decode(message.body) as? Map<*, *> }.getOrNull()
            val error = body?.get("error")
            if (body == null || (error != null && (error as? Number)?.toLong() != 0L)) status = -2
        }
        if (status in 200..299) currentIndex = request.index
        return Completion(status, request)
    }
}
