package com.callagent.gateway.localsend

/**
 * Primary traffic routing mode for T-Switch.
 */
enum class RoutingMode {
    HYBRID_LOCAL_IP,
    CUSTOM_SIP_WITH_PUSH_WAKE
}

/**
 * Resolved routing configuration produced by [RoutingPolicy.resolve].
 */
data class RoutingDecision(
    val mode: RoutingMode,
    val customServer: String,
    val customPort: Int,
    val customUseTls: Boolean,
    val pushWakeOnly: Boolean,
    val pinnedLocalIp: String
)

/**
 * Resolves the active T-Switch routing policy from user SIP settings.
 *
 * Invariant: Leaving the custom SIP server blank or set to `sip.linphone.org`
 * preserves [RoutingMode.HYBRID_LOCAL_IP] (fixed local IP + Linphone cloud/PushKit).
 * Any other non-empty server switches primary call/media/SMS traffic to that server
 * and forces [RoutingDecision.pushWakeOnly] to `true`.
 */
object RoutingPolicy {
    private const val LINPHONE_CLOUD_HOST = "sip.linphone.org"
    private const val DEFAULT_SIP_PORT = 5060
    private const val SIPS_TLS_PORT = 5061

    fun resolve(
        customSipServer: String?,
        customSipPort: Int,
        customSipTls: Boolean,
        pinnedLocalIp: String? = LocalIpPinController.DEFAULT_PINNED_IP
    ): RoutingDecision {
        val candidatePinnedIp = pinnedLocalIp?.trim().orEmpty()
        val resolvedPinnedIp = if (LocalIpPinController.isValidRoutableIpv4(candidatePinnedIp)) {
            candidatePinnedIp
        } else {
            LocalIpPinController.DEFAULT_PINNED_IP
        }

        val server = customSipServer?.trim().orEmpty()
        val validPort = if (customSipPort in 1..65535) customSipPort else DEFAULT_SIP_PORT

        if (server.isEmpty() || server.equals(LINPHONE_CLOUD_HOST, ignoreCase = true)) {
            return RoutingDecision(
                mode = RoutingMode.HYBRID_LOCAL_IP,
                customServer = "",
                customPort = validPort,
                customUseTls = false,
                pushWakeOnly = false,
                pinnedLocalIp = resolvedPinnedIp
            )
        }

        val useTls = customSipTls || validPort == SIPS_TLS_PORT
        return RoutingDecision(
            mode = RoutingMode.CUSTOM_SIP_WITH_PUSH_WAKE,
            customServer = server,
            customPort = validPort,
            customUseTls = useTls,
            pushWakeOnly = true,
            pinnedLocalIp = resolvedPinnedIp
        )
    }
}
