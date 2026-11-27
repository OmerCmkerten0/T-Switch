package com.tnoktasi.tswitch.sip

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
        val wwwHeader = msg.header("www-authenticate")
        val proxyHeader = msg.header("proxy-authenticate")
        val isProxy = (msg.statusCode == 407) || (wwwHeader == null && proxyHeader != null)
        val authHeader = (if (isProxy) (proxyHeader ?: wwwHeader) else (wwwHeader ?: proxyHeader))
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
        val algorithm = params["algorithm"]?.takeIf { it.isNotBlank() }?.uppercase() ?: "MD5"
        return AuthParams(
            realm = realm,
            nonce = nonce,
            opaque = params["opaque"],
            qop = params["qop"],
            isProxy = isProxy,
            algorithm = algorithm
        )
    }

    /** Build Authorization or Proxy-Authorization header for REGISTER / INVITE / MESSAGE */
    fun buildAuthHeader(
        method: String,
        uri: String,
        username: String,
        password: String,
        params: AuthParams,
        ncCount: Int = 1
    ): String {
        val useSha256 = params.algorithm.equals("SHA-256", ignoreCase = true)
        val algoName = if (useSha256) "SHA-256" else "MD5"

        val ha1 = digestHex("$username:${params.realm}:$password", algoName)
        val ha2 = digestHex("$method:$uri", algoName)

        val qopToken = params.qop
            ?.split(",")
            ?.map { it.trim().trim('"') }
            ?.firstOrNull { it.equals("auth", ignoreCase = true) }

        val ncHex = "%08x".format(ncCount.coerceAtLeast(1))
        val cnonce = UUID.randomUUID().toString().replace("-", "").take(16)

        val response = if (qopToken != null) {
            digestHex("$ha1:${params.nonce}:$ncHex:$cnonce:$qopToken:$ha2", algoName)
        } else {
            digestHex("$ha1:${params.nonce}:$ha2", algoName)
        }

        val headerName = if (params.isProxy) "Proxy-Authorization" else "Authorization"
        return buildString {
            append("$headerName: Digest username=\"$username\", ")
            append("realm=\"${params.realm}\", ")
            append("nonce=\"${params.nonce}\", ")
            append("uri=\"$uri\", ")
            append("response=\"$response\", ")
            append("algorithm=$algoName")
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

    private fun digestHex(input: String, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    data class AuthParams(
        val realm: String,
        val nonce: String,
        val opaque: String?,
        val qop: String?,
        val isProxy: Boolean = false,
        val algorithm: String = "MD5"
    )
}
