package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class HotspotRecoveryTest {
    private val recovery = HotspotRecovery().apply { begin(1_000) }
    private fun step(time: Long, state: Int, ready: Boolean = false, allowed: Boolean = true) =
        recovery.step(time, allowed, state, ready)

    @Test fun offApStartsOnceWithoutBusyLoop() {
        assertEquals(HotspotRecovery.Step.START, step(1_000, 11))
        for (now in 1_001L..10_999L step 100) assertEquals(HotspotRecovery.Step.WAIT, step(now, 11))
        assertEquals(1, recovery.attempts)
    }
    @Test fun enabledApWithInterfaceDoesNotRestart() {
        assertEquals(HotspotRecovery.Step.READY, step(1_000, 13, true))
        assertEquals(0, recovery.attempts)
    }
    @Test fun invisibleEnabledApIsNotPowerCycled() {
        assertEquals(HotspotRecovery.Step.WAIT, step(1_000, 13))
        assertEquals(HotspotRecovery.Step.EXPIRED, step(31_000, 13))
        assertEquals(0, recovery.attempts)
    }
    @Test fun callbackSuccessWithoutInterfaceCannotBecomeReady() {
        step(1_000, 11)
        assertEquals(HotspotRecovery.Step.WAIT, step(2_000, 13))
        assertEquals(HotspotRecovery.Step.READY, step(5_000, 13, true))
    }
    @Test fun transitioningApWaitsWithoutDuplicateStart() {
        for (state in listOf(10, 12)) assertEquals(HotspotRecovery.Step.WAIT, step(1_000, state))
        assertEquals(0, recovery.attempts)
    }
    @Test fun unknownStateDoesNotIssueBlindRequests() {
        assertEquals(HotspotRecovery.Step.WAIT, step(1_000, -1))
        assertEquals(HotspotRecovery.Step.EXPIRED, step(31_000, -1))
        assertEquals(0, recovery.attempts)
    }
    @Test fun failureHasThreeSpacedAttemptsAndDeadline() {
        for (now in listOf(1_000L, 11_000L, 21_000L)) assertEquals(HotspotRecovery.Step.START, step(now, 14))
        assertEquals(HotspotRecovery.Step.WAIT, step(30_999, 14))
        assertEquals(HotspotRecovery.Step.EXPIRED, step(31_000, 14))
        assertEquals(3, recovery.attempts)
    }
    @Test fun delayedHandlerCannotStartAfterDeadline() {
        assertEquals(HotspotRecovery.Step.EXPIRED, step(90_000, 11))
        assertEquals(0, recovery.attempts)
    }
    @Test fun preferenceOffCancelsQueuedRequest() {
        assertEquals(HotspotRecovery.Step.CANCELLED, step(1_000, 11, allowed = false))
        assertEquals(HotspotRecovery.Step.CANCELLED, step(2_000, 11))
        assertEquals(0, recovery.attempts)
    }
    @Test fun exitCancelsAnInFlightRetry() {
        step(1_000, 11)
        recovery.cancel()
        assertEquals(HotspotRecovery.Step.CANCELLED, step(11_000, 11))
        assertEquals(1, recovery.attempts)
    }
    @Test fun completionCannotBeResurrectedByLatePoll() {
        step(1_000, 13, true)
        assertEquals(HotspotRecovery.Step.CANCELLED, step(2_000, 11))
    }
    @Test fun newWakeGetsNewBudgetAfterPriorTimeout() {
        step(31_000, 11)
        recovery.begin(200_000)
        assertEquals(HotspotRecovery.Step.START, step(200_000, 11))
        assertEquals(1, recovery.attempts)
    }
    @Test fun interfaceAloneIsNotApReady() {
        assertEquals(HotspotRecovery.Step.START, step(1_000, 11, true))
    }
    @Test fun cancelledByModeChangeEvenWhenReady() {
        assertEquals(HotspotRecovery.Step.CANCELLED, step(1_000, 13, true, false))
    }
    @Test fun rebootTimeOriginIsIndependent() {
        recovery.begin(0)
        assertEquals(HotspotRecovery.Step.START, step(0, 11))
    }
    @Test fun allEligibilityGatesAreRequired() {
        for (mask in 0..31) {
            val supported = mask and 1 != 0
            val enabled = mask and 2 != 0
            val wireless = mask and 4 != 0
            val manual = mask and 8 != 0
            val paused = mask and 16 != 0
            assertEquals(mask == 15, HotspotEligibility.allowed(supported, enabled, wireless, manual, paused))
        }
    }
}
