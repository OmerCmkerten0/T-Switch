package com.callagent.gateway.localsend

/**
 * Classified action for an inbound SIP MESSAGE received by the T-Switch gateway.
 */
sealed class InboundMessageAction {
    data class TerminalCommand(val commandText: String) : InboundMessageAction()
    data class CallbackCall(val destinationNumber: String) : InboundMessageAction()
    data class ContactCallQuery(val nameQuery: String) : InboundMessageAction()
    data class OutboundSmsCommand(val destinationNumber: String, val text: String) : InboundMessageAction()
    data class ContactSmsQuery(val nameQuery: String, val text: String) : InboundMessageAction()
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

    sealed class DecodedMessageBody {
        data class Text(val text: String) : DecodedMessageBody()
        object IgnoreControl : DecodedMessageBody()
        data class Unsupported(val contentType: String) : DecodedMessageBody()
    }

    /**
     * Unwraps plain text and Linphone's `message/cpim` chat envelopes, while
     * silently ignoring typing (`im-iscomposing+xml`) and delivery receipt
     * (`imdn+xml`) notifications so Linphone iOS/Android chats work seamlessly.
     */
    fun decodeMessageBody(contentType: String?, rawBody: String): DecodedMessageBody {
        val ct = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (ct.isEmpty() || ct == "text/plain") {
            return DecodedMessageBody.Text(rawBody)
        }
        if (ct == "application/im-iscomposing+xml" || ct == "message/imdn+xml") {
            return DecodedMessageBody.IgnoreControl
        }
        if (ct == "message/cpim") {
            val normalized = rawBody.replace("\r\n", "\n")
            val afterCpimHeaders = normalized.substringAfter("\n\n", missingDelimiterValue = "")
            if (afterCpimHeaders.isEmpty()) {
                return DecodedMessageBody.Text(rawBody)
            }
            val innerHeaders = afterCpimHeaders.substringBefore("\n\n", missingDelimiterValue = "")
            val innerPayload = afterCpimHeaders.substringAfter("\n\n", missingDelimiterValue = "")
            if (innerPayload.isEmpty()) {
                return DecodedMessageBody.Text(afterCpimHeaders.trim())
            }
            val innerCtLine = innerHeaders.lineSequence()
                .firstOrNull { it.trimStart().startsWith("content-type:", ignoreCase = true) }
                ?.substringAfter(':')
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase()
                .orEmpty()
            if (innerCtLine == "application/im-iscomposing+xml" || innerCtLine == "message/imdn+xml") {
                return DecodedMessageBody.IgnoreControl
            }
            if (innerCtLine.isNotEmpty() && innerCtLine != "text/plain") {
                return DecodedMessageBody.Unsupported(innerCtLine)
            }
            return DecodedMessageBody.Text(innerPayload)
        }
        return DecodedMessageBody.Unsupported(ct)
    }

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
        // Normalize optional whitespace after '!' (e.g., "! ara 0532..." -> "!ara 0532...")
        val normalizedCmd = if (trimmedBody.startsWith("!")) {
            "!" + trimmedBody.drop(1).trimStart()
        } else {
            trimmedBody
        }

        if (hasCommandPrefix(normalizedCmd, PREFIX_ARA)) {
            val arg = normalizedCmd.drop(PREFIX_ARA.length).trim()
            if (arg.isEmpty()) {
                return InboundMessageAction.Reject(400, "Invalid Callback Target")
            }
            return if (isPhoneNumber(arg)) {
                InboundMessageAction.CallbackCall(arg)
            } else {
                InboundMessageAction.ContactCallQuery(arg)
            }
        }

        if (hasCommandPrefix(normalizedCmd, PREFIX_SMS)) {
            val rest = normalizedCmd.drop(PREFIX_SMS.length).trim()
            if (rest.isEmpty()) {
                return InboundMessageAction.Reject(400, "Invalid SMS Command")
            }
            val (recipient, smsText) = parseSmsTargetAndBody(rest)
                ?: return InboundMessageAction.Reject(400, "Invalid SMS Command")
            if (recipient.isEmpty() || smsText.isEmpty()) {
                return InboundMessageAction.Reject(400, "Invalid SMS Command")
            }
            return if (isPhoneNumber(recipient)) {
                InboundMessageAction.OutboundSmsCommand(recipient, smsText)
            } else {
                InboundMessageAction.ContactSmsQuery(recipient, smsText)
            }
        }

        if (isPhoneNumber(trimmedBody)) {
            return InboundMessageAction.CallbackCall(trimmedBody)
        }

        return InboundMessageAction.TerminalCommand(normalizedCmd)
    }

    private fun parseSmsTargetAndBody(rest: String): Pair<String, String>? {
        if (rest.startsWith("\"")) {
            val closeIdx = rest.indexOf('"', startIndex = 1)
            if (closeIdx <= 1) return null
            val target = rest.substring(1, closeIdx).trim()
            val text = rest.substring(closeIdx + 1).trim()
            return if (target.isNotEmpty() && text.isNotEmpty()) target to text else null
        }
        val spaceIdx = rest.indexOfFirst { it.isWhitespace() }
        if (spaceIdx <= 0) return null
        val target = rest.substring(0, spaceIdx).trim()
        val text = rest.substring(spaceIdx + 1).trim()
        return if (target.isNotEmpty() && text.isNotEmpty()) target to text else null
    }

    private fun hasCommandPrefix(body: String, prefix: String): Boolean {
        if (body.equals(prefix, ignoreCase = true)) return true
        return body.length > prefix.length &&
            body.startsWith(prefix, ignoreCase = true) &&
            body[prefix.length].isWhitespace()
    }
}
