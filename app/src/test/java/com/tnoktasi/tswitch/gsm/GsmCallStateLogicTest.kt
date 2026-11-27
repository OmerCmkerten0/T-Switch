package com.tnoktasi.tswitch.gsm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GsmCallStateLogicTest {

    @Test
    fun secondIncomingCallDoesNotOverwriteActiveCall() {
        assertFalse(GsmCallManager.shouldReplaceActiveCall(true, 4 /* STATE_ACTIVE */))
        assertFalse(GsmCallManager.shouldReplaceActiveCall(true, 2 /* STATE_RINGING */))
        assertFalse(GsmCallManager.shouldReplaceActiveCall(true, 1 /* STATE_DIALING */))
        assertFalse(GsmCallManager.shouldReplaceActiveCall(true, 9 /* STATE_CONNECTING */))
        assertTrue(GsmCallManager.shouldReplaceActiveCall(true, 7 /* STATE_DISCONNECTED */))
        assertTrue(GsmCallManager.shouldReplaceActiveCall(true, 10 /* STATE_DISCONNECTING */))
        assertTrue(GsmCallManager.shouldReplaceActiveCall(false, 0 /* STATE_NEW */))
    }

    @Test
    fun removingRejectedSecondCallDoesNotRestoreAudio() {
        assertFalse(GsmCallManager.shouldRestoreAudioOnRemove(false))
        assertTrue(GsmCallManager.shouldRestoreAudioOnRemove(true))
    }

    @Test
    fun kioskAutoSleepOnlyFiresForIncomingFallbackWithNoActiveCall() {
        assertTrue(GsmCallManager.shouldAutoSleepAfterLocalCall("IN", false))
        assertFalse(GsmCallManager.shouldAutoSleepAfterLocalCall("OUT", false))
        assertFalse(GsmCallManager.shouldAutoSleepAfterLocalCall("IN", true))
        assertFalse(GsmCallManager.shouldAutoSleepAfterLocalCall("OUT", true))
    }

    @Test
    fun rejectedSecondCallLifecycleEventsAreNotForwardedToListener() {
        // Rejected 2nd incoming call while a primary call is active: must NOT forward
        assertFalse(
            GsmCallManager.shouldForwardCallEventToListener(
                hasActiveCall = true,
                matchesActiveCall = false,
                matchesLocalFallback = false
            )
        )
        // Primary active call events MUST forward
        assertTrue(
            GsmCallManager.shouldForwardCallEventToListener(
                hasActiveCall = true,
                matchesActiveCall = true,
                matchesLocalFallback = false
            )
        )
        // Local fallback call events MUST forward even if activeCall differs
        assertTrue(
            GsmCallManager.shouldForwardCallEventToListener(
                hasActiveCall = true,
                matchesActiveCall = false,
                matchesLocalFallback = true
            )
        )
        // When no activeCall is tracked, events MUST forward
        assertTrue(
            GsmCallManager.shouldForwardCallEventToListener(
                hasActiveCall = false,
                matchesActiveCall = false,
                matchesLocalFallback = false
            )
        )
    }
}
