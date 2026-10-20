package com.shilapi.xcertplay

/** One bounded recovery burst. A callback is not proof that an AP is usable. */
internal class HotspotRecovery {
    enum class Step { START, WAIT, READY, EXPIRED, CANCELLED }
    private var deadline = 0L
    private var nextRequest = 0L
    private var active = false
    val isActive: Boolean get() = active
    var attempts = 0
        private set

    fun begin(now: Long) {
        deadline = now + 30_000
        nextRequest = now
        attempts = 0
        active = true
    }

    fun cancel() { active = false }

    fun step(now: Long, allowed: Boolean, apState: Int, interfaceReady: Boolean): Step {
        if (!active || !allowed) { active = false; return Step.CANCELLED }
        if (apState == 13 && interfaceReady) { active = false; return Step.READY }
        if (now >= deadline) { active = false; return Step.EXPIRED }
        // Never cycle an AP that reports enabled, including the "invisible AP" case.
        // Transitional/unknown states must also settle before any start request.
        if (apState !in listOf(11, 14) || now < nextRequest || attempts >= 3) return Step.WAIT
        attempts++
        nextRequest = now + 10_000
        return Step.START
    }
}

internal object HotspotEligibility {
    fun allowed(supported: Boolean, enabled: Boolean, wireless: Boolean, manual: Boolean,
                paused: Boolean): Boolean = supported && enabled && wireless && manual && !paused
}
