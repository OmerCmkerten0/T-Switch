package com.callagent.gateway.sip

import java.security.MessageDigest
import java.util.UUID

/**
 * SIP Digest Authentication (RFC 2617 / RFC 3261).
 * Supports both legacy Digest and qop="auth" (required by Flexisip / sip.linphone.org).
 */
object SipAuth {

    private val PARAM_REGEX = Regex("""([a-zA-Z0-9_-]+)\s*=\s*(?:"([^"]*)"|([^,\s]+))""")

    /** Parse WWW-Authenticate or Proxy-Authenticate header parameters */
    fun parseChallenge(msg: SipMessage): AuthParams? {
        val authHeader = msg.header("www-authenticate") ?: msg.header("proxy-authenticate")
            ?: return null

        if (!authHeader.lowercase().startsWith("digest")) return null

        val params = mutableMapOf<String, String>()
        val digestIdx = authHeader.lowercase().indexOf("digest")
        val paramStr = if (digestIdx >= 0) authHeader.substring(digestIdx + 6).trim() else return null
        for (match in PARAM_REGEX.findAll(paramStr)) {
            val key = match.groupValues[1].trim().lowercase()
            val value = match.groupValues[2].ifEmpty { match.groupValues[3] }.trim()
            params[key] = value
        }

        val realm = params["realm"] ?: return null
        val nonce = params["nonce"] ?: return null
        return AuthParams(realm, nonce, params["opaque"], params["qop"])
    }

    /** Build Authorization header for REGISTER / INVITE */
    fun buildAuthHeader(
        method: String,
        uri: String,
        username: String,
        password: String,
        params: AuthParams,
        ncCount: Int = 1
    ): String {
        val ha1 = md5("$username:${params.realm}:$password")
        val ha2 = md5("$method:$uri")

        val qopToken = params.qop
            ?.split(",")
            ?.map { it.trim().trim('"') }
            ?.firstOrNull { it.equals("auth", ignoreCase = true) }

        val ncHex = "%08x".format(ncCount.coerceAtLeast(1))
        val cnonce = UUID.randomUUID().toString().replace("-", "").take(16)

        val response = if (qopToken != null) {
            md5("$ha1:${params.nonce}:$ncHex:$cnonce:$qopToken:$ha2")
        } else {
            md5("$ha1:${params.nonce}:$ha2")
        }

        return buildString {
            append("Authorization: Digest username=\"$username\", ")
            append("realm=\"${params.realm}\", ")
            append("nonce=\"${params.nonce}\", ")
            append("uri=\"$uri\", ")
            append("response=\"$response\", ")
            append("algorithm=MD5")
            if (qopToken != null) {
                append(", qop=$qopToken, nc=$ncHex, cnonce=\"$cnonce\"")
            }
            if (params.opaque != null) append(", opaque=\"${params.opaque}\"")
            append("\r\n")
        }
    }

    /** Build Authorization header for INVITE */
    fun buildInviteAuthHeader(
        uri: String,
        username: String,
        password: String,
        params: AuthParams
    ): String = buildAuthHeader("INVITE", uri, username, password, params)

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val bytes = digest.digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    data class AuthParams(
        val realm: String,
        val nonce: String,
        val opaque: String?,
        val qop: String?
    )
}
