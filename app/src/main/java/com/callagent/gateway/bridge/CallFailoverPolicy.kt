package com.callagent.gateway.bridge

import com.callagent.gateway.localsend.RoutingMode

/**
 * Pure call failover and routing decisions shared by [CallOrchestrator].
 *
 * Invariants:
 * - Explicit rejections (`486 Busy`, `603 Decline`) NEVER retry and NEVER fall back to GM 8 local ring.
 * - Custom SIP mode (`CUSTOM_SIP_WITH_PUSH_WAKE`) retries offline/transient errors (`404`, `408`,
 *   `480`, `500`, `503`, `504`, `0`) while the PushKit wake window is active (`elapsedMs < maxWakeMs`).
 * - Hybrid mode (`HYBRID_LOCAL_IP`) starts with local LAN SIP only when the local peer registration
 *   is fresh; otherwise it launches cloud PushKit directly to avoid wasted delay and double-ringing.
 */
object CallFailoverPolicy {
    private val NO_LOCAL_FALLBACK_CODES = setOf(200, 486, 603)
    private val CUSTOM_SIP_WAKE_RETRY_CODES = setOf(0, 404, 408, 480, 500, 503, 504)

    fun shouldRetryCustomSipAfterError(
        mode: RoutingMode,
        statusCode: Int,
        wakeWindowActive: Boolean,
        elapsedMs: Long,
        maxWakeMs: Long = 20_000L
    ): Boolean {
        if (mode != RoutingMode.CUSTOM_SIP_WITH_PUSH_WAKE) return false
        if (!wakeWindowActive) return false
        if (elapsedMs < 0L || elapsedMs >= maxWakeMs) return false
        return statusCode in CUSTOM_SIP_WAKE_RETRY_CODES
    }

    fun shouldTriggerCustomSipWake(
        wakeAlreadyTriggered: Boolean,
        pushWakeActive: Boolean,
        pushConfigured: Boolean
    ): Boolean = !wakeAlreadyTriggered && !pushWakeActive && pushConfigured

    fun shouldFallbackToLocalRingOnError(statusCode: Int): Boolean =
        statusCode !in NO_LOCAL_FALLBACK_CODES

    fun shouldStartWithLocalPeer(mode: RoutingMode, localPeerFresh: Boolean): Boolean =
        mode == RoutingMode.HYBRID_LOCAL_IP && localPeerFresh
}
