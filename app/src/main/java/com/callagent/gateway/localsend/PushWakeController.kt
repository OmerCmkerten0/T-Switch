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
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Hybrid PushKit Wake & Cloud Call Leg Controller.
 *
 * Uses `t-switch_pusher@sip.linphone.org` to send a SIP INVITE to
 * `t-switch_receiver@sip.linphone.org` whenever an incoming GSM call cannot reach
 * Linphone over the local network immediately (e.g. iPhone screen locked or
 * app suspended/killed), or to originate an iPhone-first cloud callback.
 *
 * Supports both:
 * 1. Wake-only PushKit pings (`wakeOnly = true`) used in custom SIP mode or tests —
 *    never bridges its own media leg and immediately ACKs + sends in-dialog BYE on 200 OK.
 * 2. Full SIP call bridging over `sip.linphone.org` (`wakeOnly = false`) — bounded STUN
 *    discovery on an ephemeral socket, RFC 3261 `Record-Route`/`Route` `ACK` & `BYE`,
 *    in-dialog `re-INVITE` RTP handover, and bidirectional RTP handoff to `CallOrchestrator`.
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

    private const val STUN_DISCOVERY_TIMEOUT_MS = 2_500L
    private const val TLS_READ_TIMEOUT_MS = 1_000
    private const val CANCEL_487_WAIT_MS = 1_000L
    private const val BYE_RESPONSE_WAIT_MS = 1_500L

    private val SKIPPED_IFACE_PREFIXES = listOf("rmnet", "ccmni", "tun", "dummy", "p2p", "lo")
    private val PREFERRED_LAN_PREFIXES = listOf("wlan", "ap", "swlan", "eth")

    data class CallBridgeCallbacks(
        val localRtpPort: Int = 0,
        val onRinging: (() -> Unit)? = null,
        val onAnswered: ((remoteRtpAddr: String, remoteRtpPort: Int, payloadType: Int) -> Unit)? = null,
        val onRejectedOrEnded: ((statusCode: Int, reason: String) -> Unit)? = null,
        val onReInviteRtp: ((remoteRtpAddr: String, remoteRtpPort: Int) -> Unit)? = null,
        val wakeOnly: Boolean = false
    )

    private class ActiveDialog(
        val transport: TlsSipTransport,
        val domain: String,
        val pusher: String,
        val targetUri: String,
        val callId: String,
        val fromTag: String,
        val sdpIp: String,
        val localPort: Int,
        var activeBranch: String,
        var activeCseq: Int,
        var provisionalReceived: Boolean = false,
        var answered: Boolean = false,
        var remoteContactUri: String = targetUri,
        var toHdr: String = "<$targetUri>",
        var recordRoutes: List<String> = emptyList(),
        var byeCseq: Int = 2,
        var cancelDispatched: Boolean = false,
        var byeDispatched: Boolean = false
    )

    private data class CancelAction(
        val shouldWaitFor487: Boolean,
        val cancelPacket: String?,
        val branch: String,
        val cseq: Int
    )

    private val wakeActive = AtomicBoolean(false)
    private val wakeCancelled = AtomicBoolean(false)
    private val dialogLock = Any()
    @Volatile private var activeTransport: TlsSipTransport? = null
    @Volatile private var activeStunSocket: DatagramSocket? = null
    @Volatile private var activeDialog: ActiveDialog? = null
    @Volatile private var bridgeCallbacks: CallBridgeCallbacks? = null
    @Volatile var isCallActiveOrRinging: Boolean = false
        private set

    fun setCallBridgeCallbacks(callbacks: CallBridgeCallbacks?) {
        bridgeCallbacks = callbacks
    }

    fun isWakeActive(): Boolean = wakeActive.get() && !wakeCancelled.get()

    /**
     * Extract dialog Route set from a 200 OK response in reversed RFC 3261 order,
     * properly handling both multi-line and comma-separated Record-Route headers.
     */
    fun extractReversedRoutes(msg: SipMessage): List<String> =
        msg.recordRouteHeaders.reversed()

    /**
     * Select the preferred local LAN IPv4 address from `(interfaceName, ipAddress)` pairs,
     * skipping cellular (`rmnet*`, `ccmni*`), VPN (`tun*`), dummy (`dummy*`),
     * Wi-Fi Direct (`p2p*`), and loopback (`lo*`) interfaces.
     */
    fun selectPreferredIpv4(candidates: List<Pair<String, String>>): String {
        val eligible = candidates.filter { (rawName, rawIp) ->
            val name = rawName.trim().lowercase()
            val ip = rawIp.trim()
            ip.isNotEmpty() &&
                ip != "0.0.0.0" &&
                !ip.startsWith("127.") &&
                !ip.startsWith("169.254.") &&
                SKIPPED_IFACE_PREFIXES.none { prefix -> name.startsWith(prefix) }
        }
        val lanMatch = eligible.firstOrNull { (rawName, _) ->
            val name = rawName.trim().lowercase()
            PREFERRED_LAN_PREFIXES.any { prefix -> name.startsWith(prefix) }
        }
        return (lanMatch ?: eligible.firstOrNull())?.second?.trim() ?: "127.0.0.1"
    }

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

    /**
     * Immediately interrupts an active PushKit wake/call leg (<100ms):
     * - When answered: dispatches an in-dialog BYE immediately on a daemon thread
     *   (the active receive loop handles any 401/407 auth challenge on BYE).
     * - When ringing / provisional received: dispatches CANCEL immediately on a daemon thread
     *   (the active receive loop reads and ACKs the resulting 487).
     * - Before any 1xx provisional: never sends an illegal RFC 3261 CANCEL; closes the
     *   transport immediately to unblock any TLS connect/read.
     */
    fun cancelWake(reason: String = "local peer woke up") {
        if (!wakeActive.get()) return
        if (!wakeCancelled.compareAndSet(false, true)) return
        Log.i(TAG, "Cancelling PushKit wake: $reason")

        var packetToSend: String? = null
        var sendTransport: TlsSipTransport? = null
        var sendDomain: String? = null
        var abortTransport: TlsSipTransport? = null
        var actionLabel = ""

        synchronized(dialogLock) {
            val dlg = activeDialog
            if (dlg != null && dlg.transport.isOpen) {
                when {
                    dlg.answered && !dlg.byeDispatched -> {
                        dlg.byeDispatched = true
                        val byeBranch = "z9hG4bK${UUID.randomUUID().toString().take(10)}"
                        packetToSend = buildInDialogBye(
                            requestUri = dlg.remoteContactUri,
                            domain = dlg.domain,
                            pusherUser = dlg.pusher,
                            toHeaderValue = dlg.toHdr,
                            callId = dlg.callId,
                            fromTag = dlg.fromTag,
                            branch = byeBranch,
                            cseq = dlg.byeCseq++,
                            localIp = dlg.sdpIp,
                            localPort = dlg.localPort,
                            routeHeaders = dlg.recordRoutes
                        )
                        sendTransport = dlg.transport
                        sendDomain = dlg.domain
                        actionLabel = "in-dialog BYE"
                    }
                    !dlg.answered && dlg.provisionalReceived && !dlg.cancelDispatched -> {
                        dlg.cancelDispatched = true
                        packetToSend = buildWakeCancel(
                            targetUri = dlg.targetUri,
                            domain = dlg.domain,
                            pusherUser = dlg.pusher,
                            callId = dlg.callId,
                            fromTag = dlg.fromTag,
                            branch = dlg.activeBranch,
                            cseq = dlg.activeCseq,
                            localIp = dlg.sdpIp,
                            localPort = dlg.localPort
                        )
                        sendTransport = dlg.transport
                        sendDomain = dlg.domain
                        actionLabel = "CANCEL"
                    }
                    !dlg.answered && !dlg.provisionalReceived -> {
                        abortTransport = dlg.transport
                    }
                }
            } else if (activeTransport != null) {
                abortTransport = activeTransport
            }
        }

        val pkt = packetToSend
        val tx = sendTransport
        val dom = sendDomain
        if (pkt != null && tx != null && dom != null) {
            thread(name = "pushkit-cancel", isDaemon = true) {
                runCatching { tx.send(pkt, dom, 5061) }
                    .onSuccess { Log.i(TAG, "Sent immediate $actionLabel on cancelWake ($reason)") }
                    .onFailure { Log.w(TAG, "Immediate $actionLabel failed: ${it.message}") }
            }
        } else if (abortTransport != null || activeStunSocket != null) {
            val toClose = abortTransport
            val stunToClose = activeStunSocket
            thread(name = "pushkit-abort", isDaemon = true) {
                runCatching { stunToClose?.close() }
                runCatching { toClose?.close() }
            }
        }
    }

    private fun detectLocalIpv4(): String {
        return try {
            val candidates = mutableListOf<Pair<String, String>>()
            val ifaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (iface in ifaces) {
                if (iface.isLoopback || !iface.isUp) continue
                val name = iface.name.orEmpty()
                for (addr in iface.inetAddresses.toList()) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        val host = addr.hostAddress
                        if (!host.isNullOrBlank()) {
                            candidates.add(name to host)
                        }
                    }
                }
            }
            val preferred = selectPreferredIpv4(candidates)
            if (preferred != "127.0.0.1") {
                preferred
            } else {
                // Cellular-only fallback when no Wi-Fi/LAN interface is up
                candidates.firstOrNull { (name, ip) ->
                    val lower = name.lowercase()
                    ip != "0.0.0.0" && !ip.startsWith("127.") &&
                        !lower.startsWith("dummy") && !lower.startsWith("p2p") && !lower.startsWith("lo")
                }?.second ?: "127.0.0.1"
            }
        } catch (_: Exception) {
            "127.0.0.1"
        }
    }

    /**
     * Bounded STUN public IP discovery on an ephemeral UDP socket (never binds [localRtpPort]
     * so there is zero port conflict with `RtpSession`).
     */
    private fun discoverPublicIpBounded(timeoutMs: Long = STUN_DISCOVERY_TIMEOUT_MS): String? {
        val sock = runCatching { DatagramSocket() }.getOrNull() ?: return null
        activeStunSocket = sock
        val discoveredIp = AtomicReference<String?>(null)
        val worker = thread(name = "pushkit-stun", isDaemon = true) {
            discoveredIp.set(runCatching { StunClient.discover(sock)?.publicIp }.getOrNull())
        }
        try {
            worker.join(timeoutMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            if (activeStunSocket === sock) activeStunSocket = null
            runCatching { sock.close() }
        }
        return discoveredIp.get()
    }

    fun triggerWakeAsync(
        context: Context,
        callerNumber: String,
        holdMs: Long = 12_000L,
        onStatus: ((String) -> Unit)? = null,
        localRtpPort: Int = 0,
        onRinging: (() -> Unit)? = null,
        onAnswered: ((remoteRtpAddr: String, remoteRtpPort: Int, payloadType: Int) -> Unit)? = null,
        onRejectedOrEnded: ((statusCode: Int, reason: String) -> Unit)? = null,
        wakeOnly: Boolean = false,
        onReInviteRtp: ((String, Int) -> Unit)? = null
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
        val effWakeOnly = wakeOnly || (cb?.wakeOnly == true)
        val effRtpPort = if (effWakeOnly) 0 else if (localRtpPort > 0) localRtpPort else (cb?.localRtpPort ?: 0)
        val effOnRinging = if (effWakeOnly) onRinging else (onRinging ?: cb?.onRinging)
        val effOnAnswered = if (effWakeOnly) null else (onAnswered ?: cb?.onAnswered)
        val effOnRejectedOrEnded = if (effWakeOnly) onRejectedOrEnded else (onRejectedOrEnded ?: cb?.onRejectedOrEnded)
        val effOnReInviteRtp = if (effWakeOnly) null else (onReInviteRtp ?: cb?.onReInviteRtp)
        val effHoldMs = if (!effWakeOnly && effRtpPort > 0 && holdMs <= 12_000L) 22_000L else holdMs

        if (!wakeActive.compareAndSet(false, true)) {
            if (onStatus == null && !wakeCancelled.get()) {
                Log.d(TAG, "PushKit wake/call leg already active — keeping existing session")
                return
            }
            cancelWake("superseded by new wake")
            Thread.sleep(250)
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
            var dlg: ActiveDialog? = null
            try {
                val localIp = detectLocalIpv4()
                var sdpIp = localIp
                val sdpPort = if (effRtpPort > 0) effRtpPort else 40000
                if (!effWakeOnly && effRtpPort > 0) {
                    val stunPublicIp = discoverPublicIpBounded(STUN_DISCOVERY_TIMEOUT_MS)
                    if (wakeCancelled.get()) return@thread
                    sdpIp = stunPublicIp ?: localIp
                    Log.i(TAG, "PushKit SDP endpoint: $sdpIp:$sdpPort (localRtpPort=$effRtpPort, stun=${stunPublicIp != null})")
                }

                if (wakeCancelled.get()) return@thread
                transport = TlsSipTransport(domain, 5061, TLS_READ_TIMEOUT_MS) { Log.d(TAG, it) }
                activeTransport = transport
                transport.open()
                if (wakeCancelled.get()) return@thread

                val callId = UUID.randomUUID().toString()
                val fromTag = UUID.randomUUID().toString().take(10)
                val targetUri = "sip:$receiver@$domain"
                val localPort = transport.localPort
                val branch1 = "z9hG4bK${UUID.randomUUID().toString().take(10)}"
                val branch2 = "z9hG4bK${UUID.randomUUID().toString().take(10)}"

                dlg = ActiveDialog(
                    transport = transport,
                    domain = domain,
                    pusher = pusher,
                    targetUri = targetUri,
                    callId = callId,
                    fromTag = fromTag,
                    sdpIp = sdpIp,
                    localPort = localPort,
                    activeBranch = branch1,
                    activeCseq = 1
                )
                synchronized(dialogLock) { activeDialog = dlg }

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
                Log.i(TAG, "Sent PushKit wake INVITE to $targetUri (rtp=$sdpIp:$sdpPort, wakeOnly=$effWakeOnly)")

                val deadline = System.currentTimeMillis() + effHoldMs
                var callCompleted = false
                var authSent = false
                var pushAcceptedNotified = false

                while (System.currentTimeMillis() < deadline && transport.isOpen) {
                    if (wakeCancelled.get()) {
                        sendCancelAndAck487(dlg, CANCEL_487_WAIT_MS)
                        callCompleted = true
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
                        val authLine = SipAuth.buildAuthHeader("INVITE", targetUri, pusher, password, challenge)
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
                        synchronized(dialogLock) {
                            dlg.activeBranch = branch2
                            dlg.activeCseq = 2
                            dlg.provisionalReceived = false
                            dlg.cancelDispatched = false
                        }
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
                        if (!wakeCancelled.get()) {
                            effOnRejectedOrEnded?.invoke(code, "PushKit şifre hatası ($code)")
                        }
                        callCompleted = true
                        break
                    } else if (code in 100..199) {
                        synchronized(dialogLock) {
                            dlg.provisionalReceived = true
                        }
                        if (code == 100 && authSent && !pushAcceptedNotified) {
                            pushAcceptedNotified = true
                            Log.i(TAG, "PushKit wake accepted (100 Trying) — Flexisip sending APNs PushKit to $receiver")
                            onStatus?.invoke("✅ PushKit sinyali gönderildi ($receiver@$domain uyandırılıyor…)")
                        } else if (code in 180..183) {
                            if (!effWakeOnly) isCallActiveOrRinging = true
                            Log.i(TAG, "PushKit wake ringing ($code) — iPhone waking up (wakeOnly=$effWakeOnly)")
                            onStatus?.invoke("🔔 Başarılı! iPhone uyandı ve çalıyor ($code Ringing)!")
                            effOnRinging?.invoke()
                        }
                    } else if (code == 200 && msg.cseq?.contains("INVITE", ignoreCase = true) != false) {
                        callCompleted = true
                        val recordRoutes = extractReversedRoutes(msg)
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
                        Log.i(TAG, "Sent 200 OK ACK to $remoteContactUri (routes=${recordRoutes.size}, wakeOnly=$effWakeOnly)")

                        synchronized(dialogLock) {
                            dlg.answered = true
                            dlg.remoteContactUri = remoteContactUri
                            dlg.toHdr = toHdr
                            dlg.recordRoutes = recordRoutes
                            dlg.byeCseq = invCseq + 1
                        }

                        val rtpAddr = msg.sdpAddress
                        val rtpPort = msg.sdpRtpPort ?: 0
                        val pt = msg.sdpPreferredPayloadType
                        onStatus?.invoke("✅ iPhone aramayı yanıtladı (200 OK)!")

                        if (!effWakeOnly && !wakeCancelled.get() && rtpAddr != null && rtpPort > 0 && effOnAnswered != null) {
                            isCallActiveOrRinging = true
                            Log.i(TAG, "PushKit call answered! Bridging RTP -> $rtpAddr:$rtpPort (pt=$pt)")
                            effOnAnswered.invoke(rtpAddr, rtpPort, pt)

                            var byeAuthSent = false
                            var byeDeadline = 0L
                            while (transport.isOpen) {
                                if (wakeCancelled.get() && byeDeadline == 0L) {
                                    val byeReq = synchronized(dialogLock) {
                                        if (dlg.byeDispatched) null
                                        else {
                                            dlg.byeDispatched = true
                                            val byeBranch = "z9hG4bK${UUID.randomUUID().toString().take(10)}"
                                            buildInDialogBye(
                                                requestUri = dlg.remoteContactUri,
                                                domain = domain,
                                                pusherUser = pusher,
                                                toHeaderValue = toHdr,
                                                callId = callId,
                                                fromTag = fromTag,
                                                branch = byeBranch,
                                                cseq = dlg.byeCseq++,
                                                localIp = sdpIp,
                                                localPort = localPort,
                                                routeHeaders = recordRoutes
                                            )
                                        }
                                    }
                                    if (byeReq != null) {
                                        runCatching { transport.send(byeReq, domain, 5061) }
                                        Log.i(TAG, "Sent in-dialog BYE to $remoteContactUri for PushKit call $callId")
                                    }
                                    byeDeadline = System.currentTimeMillis() + BYE_RESPONSE_WAIT_MS
                                }
                                if (byeDeadline > 0L && System.currentTimeMillis() >= byeDeadline) break

                                val inDialogPkt = transport.receive() ?: continue
                                val inDialogMsg = SipMessage.parse(inDialogPkt.first) ?: continue
                                if (inDialogMsg.isRequest) {
                                    when (inDialogMsg.method) {
                                        "BYE" -> {
                                            val ok200 = SipBuilder.ok200(
                                                inDialogMsg, pusher, sdpIp, localPort,
                                                transportOverride = "TLS"
                                            )
                                            runCatching { transport.send(ok200, domain, 5061) }
                                            Log.i(TAG, "Received BYE from iPhone on PushKit call $callId")
                                            if (!wakeCancelled.get()) {
                                                effOnRejectedOrEnded?.invoke(200, "iPhone görüşmeyi sonlandırdı")
                                            }
                                            break
                                        }
                                        "OPTIONS", "INFO", "UPDATE" -> {
                                            val ok200 = SipBuilder.ok200(
                                                inDialogMsg, pusher, sdpIp, localPort,
                                                transportOverride = "TLS"
                                            )
                                            runCatching { transport.send(ok200, domain, 5061) }
                                        }
                                        "INVITE" -> {
                                            val newAddr = inDialogMsg.sdpAddress
                                            val newPort = inDialogMsg.sdpRtpPort ?: 0
                                            if (!newAddr.isNullOrBlank() && newPort > 0) {
                                                Log.i(TAG, "In-dialog re-INVITE RTP handover -> $newAddr:$newPort")
                                                effOnReInviteRtp?.invoke(newAddr, newPort)
                                            }
                                            val ok200 = SipBuilder.ok200(
                                                inDialogMsg, pusher, sdpIp, localPort,
                                                localRtpPort = sdpPort,
                                                toTag = fromTag,
                                                answeredPayloadType = pt,
                                                transportOverride = "TLS"
                                            )
                                            runCatching { transport.send(ok200, domain, 5061) }
                                        }
                                    }
                                } else if (inDialogMsg.isResponse) {
                                    val respCode = inDialogMsg.statusCode ?: continue
                                    if (inDialogMsg.cseq?.contains("BYE", ignoreCase = true) == true) {
                                        if ((respCode == 401 || respCode == 407) && !byeAuthSent) {
                                            val challenge = SipAuth.parseChallenge(inDialogMsg) ?: break
                                            val authLine = SipAuth.buildAuthHeader(
                                                method = "BYE",
                                                uri = dlg.remoteContactUri,
                                                username = pusher,
                                                password = password,
                                                params = challenge
                                            )
                                            val authBye = synchronized(dialogLock) {
                                                val byeBranch = "z9hG4bK${UUID.randomUUID().toString().take(10)}"
                                                buildInDialogBye(
                                                    requestUri = dlg.remoteContactUri,
                                                    domain = domain,
                                                    pusherUser = pusher,
                                                    toHeaderValue = toHdr,
                                                    callId = callId,
                                                    fromTag = fromTag,
                                                    branch = byeBranch,
                                                    cseq = dlg.byeCseq++,
                                                    localIp = sdpIp,
                                                    localPort = localPort,
                                                    routeHeaders = recordRoutes,
                                                    authHeader = authLine
                                                )
                                            }
                                            runCatching { transport.send(authBye, domain, 5061) }
                                            byeAuthSent = true
                                            byeDeadline = System.currentTimeMillis() + BYE_RESPONSE_WAIT_MS
                                            Log.i(TAG, "Sent authenticated in-dialog BYE ($respCode) to $remoteContactUri")
                                        } else if (respCode >= 200) {
                                            Log.i(TAG, "In-dialog BYE completed with $respCode")
                                            break
                                        }
                                    } else if (respCode == 200 &&
                                        inDialogMsg.cseq?.contains("INVITE", ignoreCase = true) == true
                                    ) {
                                        runCatching { transport.send(ack200, domain, 5061) }
                                    }
                                }
                            }
                        } else {
                            // Wake-only mode, cancelled mid-answer, or test ping — hang up immediately with BYE
                            sendByeAndAwaitResponse(dlg, password)
                        }
                        break
                    } else if (code == 487 && wakeCancelled.get()) {
                        val toHdr = msg.header("to") ?: "<$targetUri>"
                        val ack487 = buildWakeAck(
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
                        runCatching { transport.send(ack487, domain, 5061) }
                        Log.i(TAG, "ACKed 487 Request Terminated for cancelled PushKit call $callId")
                        callCompleted = true
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
                        if (!wakeCancelled.get()) {
                            effOnRejectedOrEnded?.invoke(code, "iPhone aramayı reddetti ($code)")
                        }
                        callCompleted = true
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
                        if (!wakeCancelled.get()) {
                            effOnRejectedOrEnded?.invoke(code, "iPhone ulaşılamadı ($code)")
                        }
                        callCompleted = true
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
                        if (!wakeCancelled.get()) {
                            effOnRejectedOrEnded?.invoke(code, "iPhone ulaşılamadı ($code)")
                        }
                        callCompleted = true
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
                        if (!wakeCancelled.get()) {
                            effOnRejectedOrEnded?.invoke(code, "iPhone ulaşılamadı ($code)")
                        }
                        callCompleted = true
                        break
                    }
                }

                if (!callCompleted) {
                    sendCancelAndAck487(dlg, CANCEL_487_WAIT_MS)
                    Log.i(TAG, "PushKit wake hold completed (${effHoldMs}ms) for $targetUri")
                    if (!wakeCancelled.get()) {
                        effOnRejectedOrEnded?.invoke(408, "iPhone yanıt vermedi")
                    }
                }
            } catch (e: Exception) {
                if (!wakeCancelled.get()) {
                    Log.w(TAG, "PushKit wake failed: ${e.message}")
                    onStatus?.invoke("❌ PushKit bağlantı hatası: ${e.message}")
                    effOnRejectedOrEnded?.invoke(503, "iPhone ulaşılamadı (${e.message})")
                } else {
                    Log.d(TAG, "PushKit wake aborted after cancel: ${e.message}")
                }
            } finally {
                synchronized(dialogLock) {
                    if (activeDialog === dlg) activeDialog = null
                }
                isCallActiveOrRinging = false
                activeTransport = null
                runCatching { transport?.close() }
                wakeActive.set(false)
            }
        }
    }

    private fun sendCancelAndAck487(dlg: ActiveDialog, waitMs: Long) {
        val transport = dlg.transport
        if (!transport.isOpen) return

        val action = synchronized(dialogLock) {
            if (!dlg.provisionalReceived) {
                CancelAction(false, null, dlg.activeBranch, dlg.activeCseq)
            } else if (dlg.cancelDispatched) {
                CancelAction(true, null, dlg.activeBranch, dlg.activeCseq)
            } else {
                dlg.cancelDispatched = true
                val pkt = buildWakeCancel(
                    targetUri = dlg.targetUri,
                    domain = dlg.domain,
                    pusherUser = dlg.pusher,
                    callId = dlg.callId,
                    fromTag = dlg.fromTag,
                    branch = dlg.activeBranch,
                    cseq = dlg.activeCseq,
                    localIp = dlg.sdpIp,
                    localPort = dlg.localPort
                )
                CancelAction(true, pkt, dlg.activeBranch, dlg.activeCseq)
            }
        }
        if (!action.shouldWaitFor487) return

        if (action.cancelPacket != null) {
            runCatching { transport.send(action.cancelPacket, dlg.domain, 5061) }
            Log.i(TAG, "Sent CANCEL to ${dlg.targetUri} (cseq=${action.cseq})")
        }

        val deadline = System.currentTimeMillis() + waitMs
        while (transport.isOpen && System.currentTimeMillis() < deadline) {
            val pkt = runCatching { transport.receive() }.getOrNull() ?: continue
            val msg = SipMessage.parse(pkt.first) ?: continue
            if (!msg.isResponse) continue
            val code = msg.statusCode ?: continue
            if (msg.cseq?.contains("INVITE", ignoreCase = true) == true && code >= 300) {
                val toHdr = msg.header("to") ?: "<${dlg.targetUri}>"
                val ack487 = buildWakeAck(
                    requestUri = dlg.targetUri,
                    domain = dlg.domain,
                    pusherUser = dlg.pusher,
                    toHeaderValue = toHdr,
                    callId = dlg.callId,
                    fromTag = dlg.fromTag,
                    branch = action.branch,
                    cseq = action.cseq,
                    localIp = dlg.sdpIp,
                    localPort = dlg.localPort
                )
                runCatching { transport.send(ack487, dlg.domain, 5061) }
                Log.i(TAG, "ACKed $code after CANCEL for PushKit call ${dlg.callId}")
                break
            }
        }
    }

    private fun sendByeAndAwaitResponse(dlg: ActiveDialog, password: String) {
        val transport = dlg.transport
        if (!transport.isOpen) return

        val byeReq = synchronized(dialogLock) {
            if (dlg.byeDispatched) null
            else {
                dlg.byeDispatched = true
                val byeBranch = "z9hG4bK${UUID.randomUUID().toString().take(10)}"
                buildInDialogBye(
                    requestUri = dlg.remoteContactUri,
                    domain = dlg.domain,
                    pusherUser = dlg.pusher,
                    toHeaderValue = dlg.toHdr,
                    callId = dlg.callId,
                    fromTag = dlg.fromTag,
                    branch = byeBranch,
                    cseq = dlg.byeCseq++,
                    localIp = dlg.sdpIp,
                    localPort = dlg.localPort,
                    routeHeaders = dlg.recordRoutes
                )
            }
        }
        if (byeReq != null) {
            runCatching { transport.send(byeReq, dlg.domain, 5061) }
            Log.i(TAG, "Sent immediate in-dialog BYE to ${dlg.remoteContactUri} (wakeOnly/test)")
        }

        var byeAuthSent = false
        var deadline = System.currentTimeMillis() + BYE_RESPONSE_WAIT_MS
        while (transport.isOpen && System.currentTimeMillis() < deadline) {
            val pkt = runCatching { transport.receive() }.getOrNull() ?: continue
            val msg = SipMessage.parse(pkt.first) ?: continue
            if (!msg.isResponse) continue
            if (msg.cseq?.contains("BYE", ignoreCase = true) != true) continue
            val code = msg.statusCode ?: continue
            if ((code == 401 || code == 407) && !byeAuthSent) {
                val challenge = SipAuth.parseChallenge(msg) ?: break
                val authLine = SipAuth.buildAuthHeader(
                    method = "BYE",
                    uri = dlg.remoteContactUri,
                    username = dlg.pusher,
                    password = password,
                    params = challenge
                )
                val authBye = synchronized(dialogLock) {
                    val byeBranch = "z9hG4bK${UUID.randomUUID().toString().take(10)}"
                    buildInDialogBye(
                        requestUri = dlg.remoteContactUri,
                        domain = dlg.domain,
                        pusherUser = dlg.pusher,
                        toHeaderValue = dlg.toHdr,
                        callId = dlg.callId,
                        fromTag = dlg.fromTag,
                        branch = byeBranch,
                        cseq = dlg.byeCseq++,
                        localIp = dlg.sdpIp,
                        localPort = dlg.localPort,
                        routeHeaders = dlg.recordRoutes,
                        authHeader = authLine
                    )
                }
                runCatching { transport.send(authBye, dlg.domain, 5061) }
                byeAuthSent = true
                deadline = System.currentTimeMillis() + BYE_RESPONSE_WAIT_MS
                Log.i(TAG, "Sent authenticated BYE ($code) to ${dlg.remoteContactUri}")
            } else if (code >= 200) {
                break
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
        routeHeaders: List<String> = emptyList(),
        authHeader: String? = null
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
        if (authHeader != null) append(authHeader)
        append("Content-Length: 0\r\n")
        append("\r\n")
    }
}
