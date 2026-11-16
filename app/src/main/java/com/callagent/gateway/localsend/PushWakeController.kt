package com.callagent.gateway.localsend

import android.content.Context
import android.util.Log
import com.callagent.gateway.net.StunClient
import com.callagent.gateway.sip.SipAuth
import com.callagent.gateway.sip.SipBuilder
import com.callagent.gateway.sip.SipMessage
import com.callagent.gateway.sip.TlsSipTransport
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Hybrid PushKit Wake & Cloud Call Leg Controller.
 *
 * Uses `t-switch_pusher@sip.linphone.org` to send a SIP INVITE to
 * `t-switch_receiver@sip.linphone.org` whenever an incoming GSM call cannot reach
 * Linphone over the local network immediately (e.g. iPhone screen locked or
 * app suspended/killed).
 *
 * Supports both:
 * 1. Short PushKit wake/test pings (`triggerWakeAsync(context, callerNumber, holdMs, onStatus)`), and
 * 2. Full SIP call bridging over `sip.linphone.org` (STUN-mapped SDP offer, `180 Ringing`,
 *    `200 OK` SDP answer parsing, RFC 3261 `Record-Route`/`Route` `ACK` & `BYE`, and bidirectional
 *    RTP handoff to `CallOrchestrator`) when the user answers the PushKit CallKit screen on iPhone.
 */
object PushWakeController {
    private const val TAG = "PushWakeController"
    const val DEFAULT_DOMAIN = "sip.linphone.org"
    const val DEFAULT_PUSHER_USER = "t-switch_pusher"
    const val DEFAULT_RECEIVER_USER = "t-switch_receiver"

    private const val PREF_ENABLED = "push_wake_enabled"
    private const val PREF_DOMAIN = "push_domain"
    private const val PREF_PUSHER_USER = "push_pusher_user"
    private const val PREF_PUSHER_PASS = "push_pusher_pass"
    const val PREF_RECEIVER_USER = "push_receiver_user"

    data class CallBridgeCallbacks(
        val localRtpPort: Int = 0,
        val onRinging: (() -> Unit)? = null,
        val onAnswered: ((remoteRtpAddr: String, remoteRtpPort: Int, payloadType: Int) -> Unit)? = null,
        val onRejectedOrEnded: ((statusCode: Int, reason: String) -> Unit)? = null
    )

    private val wakeActive = AtomicBoolean(false)
    private val wakeCancelled = AtomicBoolean(false)
    @Volatile private var activeTransport: TlsSipTransport? = null
    @Volatile private var bridgeCallbacks: CallBridgeCallbacks? = null
    @Volatile var isCallActiveOrRinging: Boolean = false
        private set

    fun setCallBridgeCallbacks(callbacks: CallBridgeCallbacks?) {
        bridgeCallbacks = callbacks
    }

    fun isWakeActive(): Boolean = wakeActive.get() && !wakeCancelled.get()

    fun ensureDefaults(context: Context) {
        val prefs = context.getSharedPreferences("gateway", Context.MODE_PRIVATE)
        val ed = prefs.edit()
        var changed = false
        if (prefs.getString(PREF_DOMAIN, "").isNullOrBlank()) {
            ed.putString(PREF_DOMAIN, DEFAULT_DOMAIN)
            changed = true
        }
        if (prefs.getString(PREF_PUSHER_USER, "").isNullOrBlank()) {
            ed.putString(PREF_PUSHER_USER, DEFAULT_PUSHER_USER)
            changed = true
        }
        if (prefs.getString(PREF_RECEIVER_USER, "").isNullOrBlank()) {
            ed.putString(PREF_RECEIVER_USER, DEFAULT_RECEIVER_USER)
            changed = true
        }
        if (!prefs.contains(PREF_ENABLED)) {
            ed.putBoolean(PREF_ENABLED, true)
            changed = true
        }
        if (changed) ed.apply()
    }

    fun isEnabled(context: Context): Boolean {
        ensureDefaults(context)
        return context.getSharedPreferences("gateway", Context.MODE_PRIVATE)
            .getBoolean(PREF_ENABLED, true)
    }

    fun getDomain(context: Context): String {
        ensureDefaults(context)
        return context.getSharedPreferences("gateway", Context.MODE_PRIVATE)
            .getString(PREF_DOMAIN, DEFAULT_DOMAIN)
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_DOMAIN
    }

    fun getPusherUser(context: Context): String {
        ensureDefaults(context)
        return context.getSharedPreferences("gateway", Context.MODE_PRIVATE)
            .getString(PREF_PUSHER_USER, DEFAULT_PUSHER_USER)
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_PUSHER_USER
    }

    fun getReceiverUser(context: Context): String {
        ensureDefaults(context)
        return context.getSharedPreferences("gateway", Context.MODE_PRIVATE)
            .getString(PREF_RECEIVER_USER, DEFAULT_RECEIVER_USER)
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_RECEIVER_USER
    }

    fun getPassword(context: Context): String =
        context.getSharedPreferences("gateway", Context.MODE_PRIVATE)
            .getString(PREF_PUSHER_PASS, "").orEmpty()

    fun isConfigured(context: Context): Boolean {
        ensureDefaults(context)
        val prefs = context.getSharedPreferences("gateway", Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_ENABLED, true) &&
            !prefs.getString(PREF_PUSHER_PASS, "").isNullOrBlank()
    }

    fun saveConfig(
        context: Context,
        enabled: Boolean,
        domain: String,
        pusherUser: String,
        pusherPass: String,
        receiverUser: String
    ) {
        context.getSharedPreferences("gateway", Context.MODE_PRIVATE).edit()
            .putBoolean(PREF_ENABLED, enabled)
            .putString(PREF_DOMAIN, domain.trim().ifEmpty { DEFAULT_DOMAIN })
            .putString(PREF_PUSHER_USER, pusherUser.trim().ifEmpty { DEFAULT_PUSHER_USER })
            .putString(PREF_PUSHER_PASS, pusherPass.trim())
            .putString(PREF_RECEIVER_USER, receiverUser.trim().ifEmpty { DEFAULT_RECEIVER_USER })
            .apply()
    }

    fun setPassword(context: Context, password: String) {
        ensureDefaults(context)
        context.getSharedPreferences("gateway", Context.MODE_PRIVATE).edit()
            .putString(PREF_PUSHER_PASS, password.trim())
            .putBoolean(PREF_ENABLED, password.trim().isNotEmpty())
            .apply()
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        ensureDefaults(context)
        context.getSharedPreferences("gateway", Context.MODE_PRIVATE).edit()
            .putBoolean(PREF_ENABLED, enabled)
            .apply()
    }

    fun dashboardSummary(context: Context): String {
        ensureDefaults(context)
        val prefs = context.getSharedPreferences("gateway", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(PREF_ENABLED, true)
        val domain = getDomain(context)
        val pusher = getPusherUser(context)
        val receiver = getReceiverUser(context)
        val hasPass = !prefs.getString(PREF_PUSHER_PASS, "").isNullOrBlank()
        val stateBadge = when {
            enabled && hasPass -> "Aktif ✅"
            enabled && !hasPass -> "Şifre Bekleniyor ⚠️"
            else -> "Kapalı"
        }
        return "$pusher → $receiver@$domain ($stateBadge)"
    }

    fun statusReport(context: Context): String {
        ensureDefaults(context)
        val prefs = context.getSharedPreferences("gateway", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(PREF_ENABLED, true)
        val domain = getDomain(context)
        val pusher = getPusherUser(context)
        val receiver = getReceiverUser(context)
        val hasPass = !prefs.getString(PREF_PUSHER_PASS, "").isNullOrBlank()

        return buildString {
            appendLine("🔔 [Hibrit Apple PushKit Durumu]")
            appendLine("• Durum   : ${if (enabled && hasPass) "AKTİF ✅" else if (!hasPass) "ŞİFRE EKSİK ⚠️" else "KAPALI"}")
            appendLine("• Gönderen: $pusher@$domain")
            appendLine("• Alıcı   : $receiver@$domain")
            appendLine("• Şifre   : ${if (hasPass) "Kayıtlı ✅" else "Girilmedi ❌"}")
            append("Komutlar: `!push sifre <parola>` | `!push test` | `!push ac/kapat`")
        }
    }

    fun cancelWake(reason: String = "local peer woke up") {
        if (!wakeActive.get()) return
        if (wakeCancelled.compareAndSet(false, true)) {
            Log.i(TAG, "Cancelling PushKit wake: $reason")
        }
    }

    private fun detectLocalIpv4(): String {
        return try {
            val ifaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (iface in ifaces) {
                if (iface.isLoopback || !iface.isUp) continue
                val ip = iface.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                    ?.hostAddress
                if (!ip.isNullOrBlank()) return ip
            }
            "127.0.0.1"
        } catch (_: Exception) {
            "127.0.0.1"
        }
    }

    fun triggerWakeAsync(
        context: Context,
        callerNumber: String,
        holdMs: Long = 12_000L,
        onStatus: ((String) -> Unit)? = null,
        localRtpPort: Int = 0,
        onRinging: (() -> Unit)? = null,
        onAnswered: ((remoteRtpAddr: String, remoteRtpPort: Int, payloadType: Int) -> Unit)? = null,
        onRejectedOrEnded: ((statusCode: Int, reason: String) -> Unit)? = null
    ) {
        val prefs = context.getSharedPreferences("gateway", Context.MODE_PRIVATE)
        val domain = prefs.getString(PREF_DOMAIN, DEFAULT_DOMAIN)?.takeIf { it.isNotBlank() } ?: DEFAULT_DOMAIN
        val pusher = prefs.getString(PREF_PUSHER_USER, DEFAULT_PUSHER_USER)?.takeIf { it.isNotBlank() } ?: DEFAULT_PUSHER_USER
        val receiver = prefs.getString(PREF_RECEIVER_USER, DEFAULT_RECEIVER_USER)?.takeIf { it.isNotBlank() } ?: DEFAULT_RECEIVER_USER
        val password = prefs.getString(PREF_PUSHER_PASS, "").orEmpty()
        if (password.isBlank()) {
            onStatus?.invoke("⚠️ t-switch_pusher şifresi girilmedi")
            return
        }

        val cb = bridgeCallbacks
        val effRtpPort = if (localRtpPort > 0) localRtpPort else (cb?.localRtpPort ?: 0)
        val effOnRinging = onRinging ?: cb?.onRinging
        val effOnAnswered = onAnswered ?: cb?.onAnswered
        val effOnRejectedOrEnded = onRejectedOrEnded ?: cb?.onRejectedOrEnded
        val effHoldMs = if (effRtpPort > 0 && holdMs <= 12_000L) 22_000L else holdMs

        if (!wakeActive.compareAndSet(false, true)) {
            if (onStatus == null && !wakeCancelled.get()) {
                Log.d(TAG, "PushKit wake/call leg already active — keeping existing session")
                return
            }
            wakeCancelled.set(true)
            Thread.sleep(350)
            if (!wakeActive.compareAndSet(false, true)) {
                Log.d(TAG, "PushKit wake already in progress")
                onStatus?.invoke("⏳ PushKit uyandırma zaten çalışıyor…")
                return
            }
        }
        wakeCancelled.set(false)
        isCallActiveOrRinging = false

        thread(name = "pushkit-wake", isDaemon = true) {
            var transport: TlsSipTransport? = null
            try {
                val localIp = detectLocalIpv4()
                var sdpIp = localIp
                var sdpPort = if (effRtpPort > 0) effRtpPort else 40000
                if (effRtpPort > 0) {
                    var sock: DatagramSocket? = null
                    val stunResult = runCatching {
                        sock = DatagramSocket(null).apply {
                            reuseAddress = true
                            bind(InetSocketAddress("0.0.0.0", effRtpPort))
                        }
                        StunClient.discover(sock)
                    }.getOrNull()
                    try {
                        sock?.close()
                    } catch (_: Exception) {}
                    sdpIp = stunResult?.publicIp ?: localIp
                    sdpPort = stunResult?.publicPort ?: effRtpPort
                    Log.i(TAG, "PushKit SDP endpoint: $sdpIp:$sdpPort (localRtpPort=$effRtpPort, stun=${stunResult != null})")
                }

                transport = TlsSipTransport(domain, 5061, 2000) { Log.d(TAG, it) }
                transport.open()
                activeTransport = transport

                val callId = UUID.randomUUID().toString()
                val fromTag = UUID.randomUUID().toString().take(10)
                val targetUri = "sip:$receiver@$domain"
                val localPort = transport.localPort
                val branch1 = "z9hG4bK${UUID.randomUUID().toString().take(10)}"
                val branch2 = "z9hG4bK${UUID.randomUUID().toString().take(10)}"

                val invite1 = buildWakeInvite(
                    targetUri = targetUri,
                    domain = domain,
                    pusherUser = pusher,
                    callerNumber = callerNumber,
                    callId = callId,
                    fromTag = fromTag,
                    branch = branch1,
                    cseq = 1,
                    localIp = sdpIp,
                    localPort = localPort,
                    sdpIp = sdpIp,
                    sdpPort = sdpPort,
                    authHeader = null
                )
                transport.send(invite1, domain, 5061)
                Log.i(TAG, "Sent PushKit wake INVITE to $targetUri (rtp=$sdpIp:$sdpPort)")

                val deadline = System.currentTimeMillis() + effHoldMs
                var cancelSent = false
                var authSent = false
                var pushAcceptedNotified = false

                while (System.currentTimeMillis() < deadline) {
                    if (wakeCancelled.get() && !cancelSent) {
                        val cancel = buildWakeCancel(
                            targetUri = targetUri,
                            domain = domain,
                            pusherUser = pusher,
                            callId = callId,
                            fromTag = fromTag,
                            branch = if (authSent) branch2 else branch1,
                            cseq = if (authSent) 2 else 1,
                            localIp = sdpIp,
                            localPort = localPort
                        )
                        transport.send(cancel, domain, 5061)
                        cancelSent = true
                        Log.i(TAG, "Sent CANCEL to $targetUri after wake cancel")
                        Thread.sleep(250)
                        break
                    }

                    val pkt = transport.receive() ?: continue
                    val rawText = pkt.first
                    val msg = SipMessage.parse(rawText) ?: continue
                    if (!msg.isResponse) continue

                    val code = msg.statusCode ?: continue
                    val activeBranch = if (authSent) branch2 else branch1
                    val activeCseq = if (authSent) 2 else 1

                    if ((code == 401 || code == 407) && !authSent) {
                        // RFC 3261: ACK the 401/407 challenge for CSeq 1 before sending CSeq 2
                        val toHdr = msg.header("to") ?: "<$targetUri>"
                        val ack1 = buildWakeAck(
                            requestUri = targetUri,
                            domain = domain,
                            pusherUser = pusher,
                            toHeaderValue = toHdr,
                            callId = callId,
                            fromTag = fromTag,
                            branch = branch1,
                            cseq = 1,
                            localIp = sdpIp,
                            localPort = localPort
                        )
                        runCatching { transport.send(ack1, domain, 5061) }

                        val challenge = SipAuth.parseChallenge(msg) ?: break
                        val rawAuth = SipAuth.buildAuthHeader("INVITE", targetUri, pusher, password, challenge)
                        val authLine = if (code == 407) {
                            rawAuth.replaceFirst("Authorization:", "Proxy-Authorization:")
                        } else rawAuth
                        val invite2 = buildWakeInvite(
                            targetUri = targetUri,
                            domain = domain,
                            pusherUser = pusher,
                            callerNumber = callerNumber,
                            callId = callId,
                            fromTag = fromTag,
                            branch = branch2,
                            cseq = 2,
                            localIp = sdpIp,
                            localPort = localPort,
                            sdpIp = sdpIp,
                            sdpPort = sdpPort,
                            authHeader = authLine
                        )
                        transport.send(invite2, domain, 5061)
                        authSent = true
                        Log.i(TAG, "Authenticated PushKit wake INVITE ($code) -> $targetUri")
                    } else if ((code == 401 || code == 407) && authSent) {
                        val toHdr = msg.header("to") ?: "<$targetUri>"
                        val ackErr = buildWakeAck(
                            requestUri = targetUri,
                            domain = domain,
                            pusherUser = pusher,
                            toHeaderValue = toHdr,
                            callId = callId,
                            fromTag = fromTag,
                            branch = activeBranch,
                            cseq = activeCseq,
                            localIp = sdpIp,
                            localPort = localPort
                        )
                        runCatching { transport.send(ackErr, domain, 5061) }
                        Log.w(TAG, "PushKit auth rejected ($code) for $pusher@$domain")
                        onStatus?.invoke("❌ Şifre reddedildi ($code) — t-switch_pusher şifresini kontrol edin")
                        effOnRejectedOrEnded?.invoke(code, "PushKit şifre hatası ($code)")
                        cancelSent = true
                        break
                    } else if (code == 100 && authSent && !pushAcceptedNotified) {
                        pushAcceptedNotified = true
                        Log.i(TAG, "PushKit wake accepted (100 Trying) — Flexisip sending APNs PushKit to $receiver")
                        onStatus?.invoke("✅ PushKit sinyali gönderildi ($receiver@$domain uyandırılıyor…)")
                    } else if (code in 180..183) {
                        isCallActiveOrRinging = true
                        Log.i(TAG, "PushKit wake ringing ($code) — iPhone waking up")
                        onStatus?.invoke("🔔 Başarılı! iPhone uyandı ve çalıyor ($code Ringing)!")
                        effOnRinging?.invoke()
                    } else if (code == 200 && msg.cseq?.contains("INVITE", ignoreCase = true) != false) {
                        isCallActiveOrRinging = true
                        cancelSent = true
                        val recordRoutes = rawText.lines()
                            .takeWhile { it.isNotEmpty() }
                            .filter { it.startsWith("Record-Route:", ignoreCase = true) }
                            .map { it.substringAfter(':').trim() }
                            .filter { it.isNotEmpty() }
                            .reversed()
                        val remoteContactUri = msg.contactUri ?: targetUri
                        val toHdr = msg.header("to") ?: "<$targetUri>"
                        val invCseq = msg.cseq?.trim()?.substringBefore(' ')?.toIntOrNull() ?: activeCseq
                        val ackBranch = "z9hG4bK${UUID.randomUUID().toString().take(10)}"
                        val ack200 = buildWakeAck(
                            requestUri = remoteContactUri,
                            domain = domain,
                            pusherUser = pusher,
                            toHeaderValue = toHdr,
                            callId = callId,
                            fromTag = fromTag,
                            branch = ackBranch,
                            cseq = invCseq,
                            localIp = sdpIp,
                            localPort = localPort,
                            routeHeaders = recordRoutes
                        )
                        runCatching { transport.send(ack200, domain, 5061) }
                        Log.i(TAG, "Sent 200 OK ACK to $remoteContactUri (routes=${recordRoutes.size})")

                        val rtpAddr = msg.sdpAddress
                        val rtpPort = msg.sdpRtpPort ?: 0
                        val pt = msg.sdpPreferredPayloadType
                        onStatus?.invoke("✅ iPhone aramayı yanıtladı (200 OK)!")

                        if (rtpAddr != null && rtpPort > 0 && effOnAnswered != null) {
                            Log.i(TAG, "PushKit call answered! Bridging RTP -> $rtpAddr:$rtpPort (pt=$pt)")
                            effOnAnswered.invoke(rtpAddr, rtpPort, pt)

                            var byeCseq = invCseq + 1
                            while (!wakeCancelled.get() && transport.isOpen) {
                                val inDialogPkt = transport.receive() ?: continue
                                val inDialogMsg = SipMessage.parse(inDialogPkt.first) ?: continue
                                if (inDialogMsg.isRequest) {
                                    when (inDialogMsg.method) {
                                        "BYE" -> {
                                            val ok200 = SipBuilder.ok200(inDialogMsg, pusher, sdpIp, localPort)
                                            runCatching { transport.send(ok200, domain, 5061) }
                                            Log.i(TAG, "Received BYE from iPhone on PushKit call $callId")
                                            effOnRejectedOrEnded?.invoke(200, "iPhone görüşmeyi sonlandırdı")
                                            break
                                        }
                                        "OPTIONS", "INFO", "UPDATE" -> {
                                            val ok200 = SipBuilder.ok200(inDialogMsg, pusher, sdpIp, localPort)
                                            runCatching { transport.send(ok200, domain, 5061) }
                                        }
                                        "INVITE" -> {
                                            val ok200 = SipBuilder.ok200(
                                                inDialogMsg, pusher, sdpIp, localPort,
                                                localRtpPort = sdpPort,
                                                toTag = fromTag,
                                                answeredPayloadType = pt
                                            )
                                            runCatching { transport.send(ok200, domain, 5061) }
                                        }
                                    }
                                } else if (inDialogMsg.isResponse && inDialogMsg.statusCode == 200 &&
                                    inDialogMsg.cseq?.contains("INVITE", ignoreCase = true) == true
                                ) {
                                    runCatching { transport.send(ack200, domain, 5061) }
                                }
                            }

                            if (wakeCancelled.get() && transport.isOpen) {
                                val byeBranch = "z9hG4bK${UUID.randomUUID().toString().take(10)}"
                                val byeReq = buildInDialogBye(
                                    requestUri = remoteContactUri,
                                    domain = domain,
                                    pusherUser = pusher,
                                    toHeaderValue = toHdr,
                                    callId = callId,
                                    fromTag = fromTag,
                                    branch = byeBranch,
                                    cseq = byeCseq++,
                                    localIp = sdpIp,
                                    localPort = localPort,
                                    routeHeaders = recordRoutes
                                )
                                runCatching { transport.send(byeReq, domain, 5061) }
                                Log.i(TAG, "Sent in-dialog BYE to $remoteContactUri for PushKit call $callId")
                                Thread.sleep(200)
                            }
                        } else {
                            // Test ping answered on iPhone — hang up cleanly with BYE
                            val byeBranch = "z9hG4bK${UUID.randomUUID().toString().take(10)}"
                            val byeReq = buildInDialogBye(
                                requestUri = remoteContactUri,
                                domain = domain,
                                pusherUser = pusher,
                                toHeaderValue = toHdr,
                                callId = callId,
                                fromTag = fromTag,
                                branch = byeBranch,
                                cseq = invCseq + 1,
                                localIp = sdpIp,
                                localPort = localPort,
                                routeHeaders = recordRoutes
                            )
                            runCatching { transport.send(byeReq, domain, 5061) }
                        }
                        break
                    } else if (code == 486 || code == 603) {
                        val toHdr = msg.header("to") ?: "<$targetUri>"
                        val ackErr = buildWakeAck(
                            requestUri = targetUri,
                            domain = domain,
                            pusherUser = pusher,
                            toHeaderValue = toHdr,
                            callId = callId,
                            fromTag = fromTag,
                            branch = activeBranch,
                            cseq = activeCseq,
                            localIp = sdpIp,
                            localPort = localPort
                        )
                        runCatching { transport.send(ackErr, domain, 5061) }
                        Log.i(TAG, "PushKit call rejected by iPhone ($code)")
                        onStatus?.invoke("📵 iPhone aramayı reddetti ($code)")
                        effOnRejectedOrEnded?.invoke(code, "iPhone aramayı reddetti ($code)")
                        cancelSent = true
                        break
                    } else if (code == 404) {
                        val toHdr = msg.header("to") ?: "<$targetUri>"
                        val ackErr = buildWakeAck(
                            requestUri = targetUri,
                            domain = domain,
                            pusherUser = pusher,
                            toHeaderValue = toHdr,
                            callId = callId,
                            fromTag = fromTag,
                            branch = activeBranch,
                            cseq = activeCseq,
                            localIp = sdpIp,
                            localPort = localPort
                        )
                        runCatching { transport.send(ackErr, domain, 5061) }
                        Log.w(TAG, "PushKit target $receiver@$domain not registered on Flexisip (404 Not Found)")
                        onStatus?.invoke(
                            "✅ GM 8 ($pusher) doğrulandı! ⚠️ Ancak '$receiver@$domain' henüz iPhone Linphone uygulamasında oturum açmamış (404 Not Found)."
                        )
                        effOnRejectedOrEnded?.invoke(code, "iPhone ulaşılamadı ($code)")
                        cancelSent = true
                        break
                    } else if (code == 480) {
                        val toHdr = msg.header("to") ?: "<$targetUri>"
                        val ackErr = buildWakeAck(
                            requestUri = targetUri,
                            domain = domain,
                            pusherUser = pusher,
                            toHeaderValue = toHdr,
                            callId = callId,
                            fromTag = fromTag,
                            branch = activeBranch,
                            cseq = activeCseq,
                            localIp = sdpIp,
                            localPort = localPort
                        )
                        runCatching { transport.send(ackErr, domain, 5061) }
                        Log.w(TAG, "PushKit target $receiver@$domain temporarily unavailable (480)")
                        onStatus?.invoke(
                            "🔔 PushKit gönderildi ancak iPhone yanıt vermedi (480) — iPhone Linphone'da Push bildirimlerinin açık olduğundan emin olun."
                        )
                        effOnRejectedOrEnded?.invoke(code, "iPhone ulaşılamadı ($code)")
                        cancelSent = true
                        break
                    } else if (code >= 300) {
                        val toHdr = msg.header("to") ?: "<$targetUri>"
                        val ackErr = buildWakeAck(
                            requestUri = targetUri,
                            domain = domain,
                            pusherUser = pusher,
                            toHeaderValue = toHdr,
                            callId = callId,
                            fromTag = fromTag,
                            branch = activeBranch,
                            cseq = activeCseq,
                            localIp = sdpIp,
                            localPort = localPort
                        )
                        runCatching { transport.send(ackErr, domain, 5061) }
                        Log.w(TAG, "PushKit wake ended with ${msg.startLine}")
                        onStatus?.invoke("ℹ️ Sunucu yanıtı: ${msg.startLine}")
                        effOnRejectedOrEnded?.invoke(code, "iPhone ulaşılamadı ($code)")
                        cancelSent = true
                        break
                    }
                }

                if (!cancelSent) {
                    val cancel = buildWakeCancel(
                        targetUri = targetUri,
                        domain = domain,
                        pusherUser = pusher,
                        callId = callId,
                        fromTag = fromTag,
                        branch = if (authSent) branch2 else branch1,
                        cseq = if (authSent) 2 else 1,
                        localIp = sdpIp,
                        localPort = localPort
                    )
                    runCatching { transport.send(cancel, domain, 5061) }
                    Log.i(TAG, "PushKit wake hold completed (${effHoldMs}ms), sent CANCEL to $targetUri")
                    if (!wakeCancelled.get()) {
                        effOnRejectedOrEnded?.invoke(408, "iPhone yanıt vermedi")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "PushKit wake failed: ${e.message}")
                onStatus?.invoke("❌ PushKit bağlantı hatası: ${e.message}")
                if (!wakeCancelled.get()) {
                    effOnRejectedOrEnded?.invoke(503, "iPhone ulaşılamadı (${e.message})")
                }
            } finally {
                isCallActiveOrRinging = false
                activeTransport = null
                runCatching { transport?.close() }
                wakeActive.set(false)
            }
        }
    }

    private fun buildWakeInvite(
        targetUri: String,
        domain: String,
        pusherUser: String,
        callerNumber: String,
        callId: String,
        fromTag: String,
        branch: String,
        cseq: Int,
        localIp: String,
        localPort: Int,
        sdpIp: String,
        sdpPort: Int,
        authHeader: String?
    ): String {
        val safeCaller = callerNumber.ifBlank { "T-Switch" }.replace("\"", "")
        val sdp = buildString {
            append("v=0\r\n")
            append("o=$pusherUser 1 1 IN IP4 $sdpIp\r\n")
            append("s=T-Switch Call\r\n")
            append("c=IN IP4 $sdpIp\r\n")
            append("t=0 0\r\n")
            append("m=audio $sdpPort RTP/AVP 9 8 0 101\r\n")
            append("a=rtpmap:9 G722/8000\r\n")
            append("a=rtpmap:8 PCMA/8000\r\n")
            append("a=rtpmap:0 PCMU/8000\r\n")
            append("a=rtpmap:101 telephone-event/8000\r\n")
            append("a=fmtp:101 0-16\r\n")
            append("a=ptime:20\r\n")
            append("a=sendrecv\r\n")
        }
        return buildString {
            append("INVITE $targetUri SIP/2.0\r\n")
            append("Via: SIP/2.0/TLS $localIp:$localPort;branch=$branch;rport\r\n")
            append("Max-Forwards: 70\r\n")
            append("From: \"$safeCaller\" <sip:$pusherUser@$domain>;tag=$fromTag\r\n")
            append("To: <$targetUri>\r\n")
            append("Call-ID: $callId\r\n")
            append("CSeq: $cseq INVITE\r\n")
            append("Contact: <sip:$pusherUser@$localIp:$localPort;transport=tls>\r\n")
            if (authHeader != null) append(authHeader)
            append("User-Agent: T-Switch/1.0\r\n")
            append("Content-Type: application/sdp\r\n")
            append("Content-Length: ${sdp.toByteArray().size}\r\n")
            append("\r\n")
            append(sdp)
        }
    }

    private fun buildWakeCancel(
        targetUri: String,
        domain: String,
        pusherUser: String,
        callId: String,
        fromTag: String,
        branch: String,
        cseq: Int,
        localIp: String,
        localPort: Int
    ): String = buildString {
        append("CANCEL $targetUri SIP/2.0\r\n")
        append("Via: SIP/2.0/TLS $localIp:$localPort;branch=$branch;rport\r\n")
        append("Max-Forwards: 70\r\n")
        append("From: <sip:$pusherUser@$domain>;tag=$fromTag\r\n")
        append("To: <$targetUri>\r\n")
        append("Call-ID: $callId\r\n")
        append("CSeq: $cseq CANCEL\r\n")
        append("Content-Length: 0\r\n")
        append("\r\n")
    }

    private fun buildWakeAck(
        requestUri: String,
        domain: String,
        pusherUser: String,
        toHeaderValue: String,
        callId: String,
        fromTag: String,
        branch: String,
        cseq: Int,
        localIp: String,
        localPort: Int,
        routeHeaders: List<String> = emptyList()
    ): String = buildString {
        append("ACK $requestUri SIP/2.0\r\n")
        append("Via: SIP/2.0/TLS $localIp:$localPort;branch=$branch;rport\r\n")
        for (route in routeHeaders) {
            append("Route: $route\r\n")
        }
        append("Max-Forwards: 70\r\n")
        append("From: <sip:$pusherUser@$domain>;tag=$fromTag\r\n")
        append("To: $toHeaderValue\r\n")
        append("Call-ID: $callId\r\n")
        append("CSeq: $cseq ACK\r\n")
        append("Contact: <sip:$pusherUser@$localIp:$localPort;transport=tls>\r\n")
        append("Content-Length: 0\r\n")
        append("\r\n")
    }

    private fun buildInDialogBye(
        requestUri: String,
        domain: String,
        pusherUser: String,
        toHeaderValue: String,
        callId: String,
        fromTag: String,
        branch: String,
        cseq: Int,
        localIp: String,
        localPort: Int,
        routeHeaders: List<String> = emptyList()
    ): String = buildString {
        append("BYE $requestUri SIP/2.0\r\n")
        append("Via: SIP/2.0/TLS $localIp:$localPort;branch=$branch;rport\r\n")
        for (route in routeHeaders) {
            append("Route: $route\r\n")
        }
        append("Max-Forwards: 70\r\n")
        append("From: <sip:$pusherUser@$domain>;tag=$fromTag\r\n")
        append("To: $toHeaderValue\r\n")
        append("Call-ID: $callId\r\n")
        append("CSeq: $cseq BYE\r\n")
        append("Contact: <sip:$pusherUser@$localIp:$localPort;transport=tls>\r\n")
        append("Content-Length: 0\r\n")
        append("\r\n")
    }
}
