package com.callagent.gateway.localsend

/**
 * Classified action for an inbound SIP MESSAGE received by the T-Switch gateway.
 */
sealed class InboundMessageAction {
    data class TerminalCommand(val commandText: String) : InboundMessageAction()
    data class CallbackCall(val destinationNumber: String) : InboundMessageAction()
    data class OutboundSmsCommand(val destinationNumber: String, val text: String) : InboundMessageAction()
    data class DirectGsmSms(val destinationNumber: String, val text: String) : InboundMessageAction()
    data class Reject(val statusCode: Int, val reason: String) : InboundMessageAction()
}

/**
 * Authorizes and classifies inbound SIP MESSAGE payloads for terminal commands,
 * GSM callbacks (`!ara` / bare phone number), outbound SMS (`!sms`), and direct
 * GSM SMS forwarding.
 *
 * Security invariants:
 * - Real phone-number targets (`>= 7` digits) are ALWAYS dispatched as [InboundMessageAction.DirectGsmSms]
 *   and never intercepted as terminal commands, even if the body starts with `!` or `$`.
 * - Terminal targets require sender verification: matching registered local peer IP on LAN,
 *   or exact paired receiver user and allowed SIP domain on cloud/custom SIP.
 */
object CommandAuthorization {
    private const val MIN_PHONE_DIGITS = 7
    private const val PREFIX_ARA = "!ara"
    private const val PREFIX_SMS = "!sms"

    private val STATIC_TERMINAL_TARGETS = setOf(
        "0000", "tswitch", "t-switch", "9999", "cmd", "terminal", "bash", "root"
    )

    private val PHONE_ALLOWED_PUNCTUATION = setOf('+', '-', '.', '(', ')', ' ')

    /**
     * Classifies an inbound SIP MESSAGE into an [InboundMessageAction].
     */
    fun classify(
        isLocalTransport: Boolean,
        target: String,
        fromUser: String?,
        fromDomain: String?,
        sourcePeer: Pair<String, Int>?,
        registeredLocalPeer: Pair<String, Int>?,
        pairedReceiverUser: String,
        allowedCloudDomains: Set<String>,
        gatewayUser: String,
        body: String
    ): InboundMessageAction {
        if (body.isBlank()) {
            return InboundMessageAction.Reject(400, "Empty Body")
        }

        if (isPhoneNumber(target)) {
            return InboundMessageAction.DirectGsmSms(target.trim(), body)
        }

        if (!isTerminalTarget(target, gatewayUser)) {
            return InboundMessageAction.Reject(400, "Invalid Destination")
        }

        if (!isAuthorizedSender(
                isLocalTransport = isLocalTransport,
                fromUser = fromUser,
                fromDomain = fromDomain,
                sourcePeer = sourcePeer,
                registeredLocalPeer = registeredLocalPeer,
                pairedReceiverUser = pairedReceiverUser,
                allowedCloudDomains = allowedCloudDomains
            )
        ) {
            return InboundMessageAction.Reject(403, "Forbidden")
        }

        return dispatchTerminalBody(body.trim())
    }

    /**
     * Returns `true` iff [raw] trimmed contains at least 7 ASCII digits and only
     * digits or `+`, `-`, `.`, `(`, `)`, space.
     */
    fun isPhoneNumber(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return false

        var digitCount = 0
        for (ch in trimmed) {
            if (ch in '0'..'9') {
                digitCount++
            } else if (ch !in PHONE_ALLOWED_PUNCTUATION) {
                return false
            }
        }
        return digitCount >= MIN_PHONE_DIGITS
    }

    private fun isTerminalTarget(target: String, gatewayUser: String): Boolean {
        val normalizedTarget = target.trim().lowercase()
        if (normalizedTarget in STATIC_TERMINAL_TARGETS) return true
        val normalizedGateway = gatewayUser.trim().lowercase()
        return normalizedGateway.isNotEmpty() && normalizedTarget == normalizedGateway
    }

    private fun isAuthorizedSender(
        isLocalTransport: Boolean,
        fromUser: String?,
        fromDomain: String?,
        sourcePeer: Pair<String, Int>?,
        registeredLocalPeer: Pair<String, Int>?,
        pairedReceiverUser: String,
        allowedCloudDomains: Set<String>
    ): Boolean {
        if (isLocalTransport) {
            return sourcePeer != null &&
                registeredLocalPeer != null &&
                sourcePeer.first.isNotBlank() &&
                sourcePeer.first == registeredLocalPeer.first
        }

        val expectedUser = pairedReceiverUser.trim()
        val actualUser = fromUser?.trim()
        val actualDomain = fromDomain?.trim()
        if (expectedUser.isEmpty() || actualUser.isNullOrEmpty() || actualDomain.isNullOrEmpty()) {
            return false
        }
        if (!actualUser.equals(expectedUser, ignoreCase = true)) {
            return false
        }
        return allowedCloudDomains.any { domain ->
            val trimmedDomain = domain.trim()
            trimmedDomain.isNotEmpty() && trimmedDomain.equals(actualDomain, ignoreCase = true)
        }
    }

    private fun dispatchTerminalBody(trimmedBody: String): InboundMessageAction {
        if (hasCommandPrefix(trimmedBody, PREFIX_ARA)) {
            val number = trimmedBody.drop(PREFIX_ARA.length).trim()
            return if (isPhoneNumber(number)) {
                InboundMessageAction.CallbackCall(number)
            } else {
                InboundMessageAction.Reject(400, "Invalid Callback Number")
            }
        }

        if (hasCommandPrefix(trimmedBody, PREFIX_SMS)) {
            val rest = trimmedBody.drop(PREFIX_SMS.length).trim()
            val spaceIdx = rest.indexOfFirst { it.isWhitespace() }
            if (spaceIdx <= 0) {
                return InboundMessageAction.Reject(400, "Invalid SMS Command")
            }
            val number = rest.substring(0, spaceIdx).trim()
            val smsText = rest.substring(spaceIdx + 1).trim()
            return if (isPhoneNumber(number) && smsText.isNotEmpty()) {
                InboundMessageAction.OutboundSmsCommand(number, smsText)
            } else {
                InboundMessageAction.Reject(400, "Invalid SMS Command")
            }
        }

        if (isPhoneNumber(trimmedBody)) {
            return InboundMessageAction.CallbackCall(trimmedBody)
        }

        return InboundMessageAction.TerminalCommand(trimmedBody)
    }

    private fun hasCommandPrefix(body: String, prefix: String): Boolean {
        if (body.equals(prefix, ignoreCase = true)) return true
        return body.length > prefix.length &&
            body.startsWith(prefix, ignoreCase = true) &&
            body[prefix.length].isWhitespace()
    }
}
