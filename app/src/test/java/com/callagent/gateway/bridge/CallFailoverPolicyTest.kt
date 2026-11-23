package com.callagent.gateway.bridge

import com.callagent.gateway.localsend.RoutingMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallFailoverPolicyTest {

    @Test
    fun explicitRejection486Or603NeverFallsBackToLocalRing() {
        assertFalse(CallFailoverPolicy.shouldFallbackToLocalRingOnError(200))
        assertFalse(CallFailoverPolicy.shouldFallbackToLocalRingOnError(486))
        assertFalse(CallFailoverPolicy.shouldFallbackToLocalRingOnError(603))

        for (code in listOf(0, 404, 408, 480, 503)) {
            assertTrue(
                "Expected status $code to allow fallback to GM 8 local ring",
                CallFailoverPolicy.shouldFallbackToLocalRingOnError(code)
            )
        }
    }

    @Test
    fun customSipWakeTriggersAtMostOncePerInboundCall() {
        assertTrue(
            CallFailoverPolicy.shouldTriggerCustomSipWake(
                wakeAlreadyTriggered = false,
                pushWakeActive = false,
                pushConfigured = true
            )
        )
        // Once triggered, must NOT re-fire PushKit on 2s custom SIP retries even after the wake finishes
        assertFalse(
            CallFailoverPolicy.shouldTriggerCustomSipWake(
                wakeAlreadyTriggered = true,
                pushWakeActive = false,
                pushConfigured = true
            )
        )
        assertFalse(
            CallFailoverPolicy.shouldTriggerCustomSipWake(
                wakeAlreadyTriggered = false,
                pushWakeActive = true,
                pushConfigured = true
            )
        )
        assertFalse(
            CallFailoverPolicy.shouldTriggerCustomSipWake(
                wakeAlreadyTriggered = false,
                pushWakeActive = false,
                pushConfigured = false
            )
        )
    }

    @Test
    fun customSipRetries480And404DuringWakeWindowButStopsAt20s() {
        assertTrue(
            CallFailoverPolicy.shouldRetryCustomSipAfterError(
                mode = RoutingMode.CUSTOM_SIP_WITH_PUSH_WAKE,
                statusCode = 480,
                wakeWindowActive = true,
                elapsedMs = 4_000L
            )
        )
        assertTrue(
            CallFailoverPolicy.shouldRetryCustomSipAfterError(
                mode = RoutingMode.CUSTOM_SIP_WITH_PUSH_WAKE,
                statusCode = 404,
                wakeWindowActive = true,
                elapsedMs = 19_999L
            )
        )
        assertTrue(
            CallFailoverPolicy.shouldRetryCustomSipAfterError(
                mode = RoutingMode.CUSTOM_SIP_WITH_PUSH_WAKE,
                statusCode = 408,
                wakeWindowActive = true,
                elapsedMs = 5_000L
            )
        )
        assertTrue(
            CallFailoverPolicy.shouldRetryCustomSipAfterError(
                mode = RoutingMode.CUSTOM_SIP_WITH_PUSH_WAKE,
                statusCode = 503,
                wakeWindowActive = true,
                elapsedMs = 5_000L
            )
        )

        // Stops at 20s boundary and beyond
        assertFalse(
            CallFailoverPolicy.shouldRetryCustomSipAfterError(
                mode = RoutingMode.CUSTOM_SIP_WITH_PUSH_WAKE,
                statusCode = 480,
                wakeWindowActive = true,
                elapsedMs = 20_000L
            )
        )
        assertFalse(
            CallFailoverPolicy.shouldRetryCustomSipAfterError(
                mode = RoutingMode.CUSTOM_SIP_WITH_PUSH_WAKE,
                statusCode = 404,
                wakeWindowActive = true,
                elapsedMs = 25_000L
            )
        )

        // Explicit rejection NEVER retries, even during active wake window
        assertFalse(
            CallFailoverPolicy.shouldRetryCustomSipAfterError(
                mode = RoutingMode.CUSTOM_SIP_WITH_PUSH_WAKE,
                statusCode = 486,
                wakeWindowActive = true,
                elapsedMs = 1_000L
            )
        )
        assertFalse(
            CallFailoverPolicy.shouldRetryCustomSipAfterError(
                mode = RoutingMode.CUSTOM_SIP_WITH_PUSH_WAKE,
                statusCode = 603,
                wakeWindowActive = true,
                elapsedMs = 1_000L
            )
        )

        // Wake window inactive or HYBRID mode never uses custom SIP wake retry
        assertFalse(
            CallFailoverPolicy.shouldRetryCustomSipAfterError(
                mode = RoutingMode.CUSTOM_SIP_WITH_PUSH_WAKE,
                statusCode = 480,
                wakeWindowActive = false,
                elapsedMs = 1_000L
            )
        )
        assertFalse(
            CallFailoverPolicy.shouldRetryCustomSipAfterError(
                mode = RoutingMode.HYBRID_LOCAL_IP,
                statusCode = 480,
                wakeWindowActive = true,
                elapsedMs = 1_000L
            )
        )
    }

    @Test
    fun hybridModeStartsLocalOnlyWhenPeerFresh() {
        assertTrue(
            CallFailoverPolicy.shouldStartWithLocalPeer(
                mode = RoutingMode.HYBRID_LOCAL_IP,
                localPeerFresh = true
            )
        )
        assertFalse(
            CallFailoverPolicy.shouldStartWithLocalPeer(
                mode = RoutingMode.HYBRID_LOCAL_IP,
                localPeerFresh = false
            )
        )
        assertFalse(
            CallFailoverPolicy.shouldStartWithLocalPeer(
                mode = RoutingMode.CUSTOM_SIP_WITH_PUSH_WAKE,
                localPeerFresh = true
            )
        )
        assertFalse(
            CallFailoverPolicy.shouldStartWithLocalPeer(
                mode = RoutingMode.CUSTOM_SIP_WITH_PUSH_WAKE,
                localPeerFresh = false
            )
        )
    }
}
