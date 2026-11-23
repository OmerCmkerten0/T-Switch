package com.callagent.gateway.sip

import android.net.Network
import android.os.Build
import com.callagent.gateway.rtp.SrtpCryptoSuite
import com.callagent.gateway.rtp.SrtpKeys
import android.util.Log
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * SIP client: handles UDP transport, registration, and call routing.
 * Ported from the Python SIPClient in sip/sip.py
 */
class SipClient(
    val username: String,
    private val password: String,
    val serverDomain: String,
    val serverPort: Int = 5060,
    var localIp: String = "0.0.0.0",
    val localPort: Int = 5060,
    /** Android network carrying this SIP session; refreshed on reconnect. */
    private val network: Network? = null,
    /** Public IP discovered via STUN — used in Contact headers and SDP for NAT traversal */
    var publicIp: String = localIp,
    /** SIP over TLS instead of UDP.  Signalling only — RTP is unaffected. */
    val useTls: Boolean = false,
    /** Offer and accept SDES-keyed SRTP.  Only honoured when [useTls] is on. */
    private val srtpRequested: Boolean = false,
    /**
     * Local Send mode: turns this gateway into a local SIP Registrar / UAS on the
     * Hotspot interface so a connected Zoiper / Linphone device can register and
     * place/receive GSM calls and messages without any internet connection.
     */
    val localSendMode: Boolean = false
) {
    /**
     * Whether this call leg may use SRTP at all.
     *
     * SDES puts the key in the SDP, so over plaintext UDP anyone who can read
     * the INVITE can decrypt the audio.  Offering it there would produce a
     * call that reports itself as encrypted while providing nothing, which is
     * worse than being plainly unencrypted -- so the transport decides, not
     * the checkbox alone.
     */
    val srtpEnabled: Boolean get() = srtpRequested && useTls && !localSendMode

    /** Last softphone (Zoiper) endpoint that registered or sent traffic in [localSendMode]. */
    @Volatile var registeredPeer: Pair<String, Int>? = null; private set
    @Volatile var registeredPeerUser: String = "zoiper"; private set
    @Volatile var lastPeerSeenMs: Long = 0L; private set
    @Volatile var onLocalPeerRegistered: ((String, Pair<String, Int>) -> Unit)? = null

    fun isLocalPeerFresh(nowMs: Long = System.currentTimeMillis(), ttlMs: Long = 65_000L): Boolean =
        registeredPeer != null && isPeerFresh(lastPeerSeenMs, nowMs, ttlMs)

    /** Call-IDs whose local LAN resend/Timer-A retransmit is suppressed (e.g. after cloud failover). */
    private val suppressedLocalCallIds = ConcurrentHashMap.newKeySet<String>()

    fun suppressLocalResendForCall(callId: String) {
        suppressedLocalCallIds.add(callId)
    }

    val serverAddress: Pair<String, Int>
        get() = if (localSendMode) {
            resolveLocalPeerAddress() ?: Pair(serverDomain, serverPort)
        } else {
            Pair(serverDomain, serverPort)
        }

    fun resolveLocalPeerAddress(context: android.content.Context? = null): Pair<String, Int>? {
        registeredPeer?.takeIf {
            it.first != localIp && it.first != "127.0.0.1" && it.first != "0.0.0.0"
        }?.let { return it }
        if (localSendMode) {
            val clientIp = com.callagent.gateway.localsend.HotspotHelper
                .detectHotspot(context)
                .connectedClients
                .firstOrNull {
                    it.isNotBlank() && it != localIp && it != "127.0.0.1" && it != "0.0.0.0"
                }
            if (!clientIp.isNullOrEmpty()) {
                return Pair(clientIp, 5060)
            }
            return null
        }
        return Pair(serverDomain, serverPort)
    }

    private var transport: SipTransport? = null

    /**
     * Port to put in Via and Contact.  UDP binds the configured port; TLS is
     * given an ephemeral one by the OS and has to advertise that instead.
     */
    val advertisedPort: Int get() = transport?.localPort ?: localPort

    /** Per-client SIP transport token ("UDP" or "TLS") so dual-stack clients never clobber each other. */
    val viaTransport: String get() = transport?.viaTransport ?: if (useTls && !localSendMode) "TLS" else "UDP"
    /** Pre-resolved server address — avoids DNS on main thread */
    @Volatile private var resolvedServerAddr: InetAddress? = null
    private val cseq = AtomicInteger(1)
    private var callIdBase = "${System.currentTimeMillis() / 1000}@$publicIp"
    private val running = AtomicBoolean(false)
    @Volatile var registered = false; private set
    @Volatile private var lastRegisterTime = 0L
    /** Tracks last time we got ANY response from server (REGISTER, OPTIONS, etc.) */
    @Volatile private var lastServerResponseTime = 0L
    @Volatile private var registrationLatch: CountDownLatch? = null

    /** Guards against two threads registering at once: start() launches a
     *  SIP-Register thread while SIP-Monitor retries on its own schedule. */
    private val registering = AtomicBoolean(false)

    /** Caps 401/407 auth retries to 1 per [register] attempt so bad credentials
     *  do not loop until timeout and trigger server bans. */
    private val registerAuthAttempted = AtomicBoolean(false)

    /** Consecutive real (not backed-off) registration failures. */
    @Volatile private var registerFailures = 0

    private val activeCalls = ConcurrentHashMap<String, SipCall>()
    /** Single-thread executor for all socket sends — avoids NetworkOnMainThreadException */
    private var sendExecutor: ExecutorService? = null

    @Volatile var listener: Listener? = null
    /** Log callback — forwards key SIP events to the UI */
    @Volatile var logListener: ((String) -> Unit)? = null

    /**
     * Page-mode MESSAGE from the server — a request to send an SMS.
     *
     * Returns the status to answer with: 202 once the request is safely
     * stored, or a 4xx the server should not retry.  Kept off SipClient's
     * Listener, which is about calls, and invoked on the receive thread — so
     * whatever it does must be quick.
     */
    @Volatile var onSmsRequest: ((SipMessage) -> Pair<Int, List<String>>)? = null

    /**
     * Optional peer-aware variant of [onSmsRequest] that receives the packet's
     * source `(ip, port)` for sender authorization (e.g. [com.callagent.gateway.localsend.CommandAuthorization]).
     * When set, takes precedence over [onSmsRequest].
     */
    @Volatile var onSmsRequestWithPeer: ((SipMessage, Pair<String, Int>) -> Pair<Int, List<String>>)? = null

    private fun uiLog(msg: String) {
        Log.i(TAG, msg)
        logListener?.invoke(msg)
    }

    interface Listener {
        fun onRegistered()
        fun onRegistrationFailed()
        /** Incoming INVITE from Asterisk — either a new call or a GSM-forward request */
        fun onIncomingCall(call: SipCall)
        fun onCallTerminated(call: SipCall)
    }

    // ── Socket ──────────────────────────────────────────

    @Synchronized
    fun start() {
        if (running.get()) return
        running.set(true)
        // Identify the handset, not the server: with several gateways
        // registered to the same PBX the server domain is the one thing every
        // one of them has in common, so it told the CDRs nothing.  Build.DEVICE
        // is the short codename — "serranolte", "surya" — which is what
        // distinguishes them.  Whitespace is stripped because a User-Agent
        // value has to stay a single header token.
        val handset = (Build.DEVICE?.takeIf { it.isNotBlank() }
            ?: Build.MODEL?.takeIf { it.isNotBlank() }
            ?: "unknown").trim().replace(Regex("\\s+"), "-")
        SipBuilder.userAgent =
            "T-Switch v${com.callagent.gateway.BuildConfig.VERSION_NAME} $handset"
        callIdBase = "${System.currentTimeMillis() / 1000}@$publicIp"
        createSocket()

        Thread({
            try { receiveLoop() }
            catch (e: Exception) { uiLog("Receive loop crashed: ${e.message}") }
        }, "SIP-Recv").start()
        Thread({
            try { monitorLoop() }
            catch (e: Exception) { uiLog("Monitor loop crashed: ${e.message}") }
        }, "SIP-Monitor").start()

        if (localSendMode) {
            registered = true
            lastRegisterTime = System.currentTimeMillis()
            lastServerResponseTime = lastRegisterTime
            uiLog("Local Send SIP Sunucusu aktif — Zoiper bekleniyor ($localIp:$localPort)")
            listener?.onRegistered()
        } else {
            Thread({
                try { register() }
                catch (e: Exception) { uiLog("Register thread crashed: ${e.message}") }
            }, "SIP-Register").start()
        }
    }

    @Synchronized
    fun stop() {
        running.set(false)
        registered = false
        // A stopped client must not be able to drive the service any more.
        // monitorLoop can be up to 10s into a sleep or blocked on the
        // registration latch when stop() lands, and the callback it fired on
        // the way out tore down the *replacement* client.
        onConnectionLost = null
        listener = null
        // Release anyone blocked waiting for a REGISTER response so the
        // thread reaches its running.get() check instead of sitting out the
        // full 10s timeout.
        registrationLatch?.countDown()
        activeCalls.values.forEach { it.hangup() }
        activeCalls.clear()
        suppressedLocalCallIds.clear()
        sendExecutor?.shutdownNow()
        sendExecutor = null
        transport?.close()
        transport = null
        resolvedServerAddr = null
    }

    private fun createSocket() {
        // Drop both references before open(), not just close them.  open()
        // throws while the network is down, and this used to leave the client
        // holding the closed transport and a *terminated* executor with
        // running still true: every REGISTER after that died with
        // RejectedExecutionException (which skipped the failure count, so no
        // full reconnect was ever asked for), and the next incoming call
        // crashed the app in makeCall — S4 Mini, 2026-10-23.  With both null,
        // sendTo falls back to a thread and the monitor sees !isOpen and
        // retries the connection.
        transport?.close()
        transport = null
        sendExecutor?.shutdownNow()
        sendExecutor = null
        val t: SipTransport = if (useTls && !localSendMode) {
            TlsSipTransport(serverDomain, serverPort, SOCKET_TIMEOUT_MS) { uiLog(it) }
        } else {
            // In Local Send mode, bind on all local interfaces (network = null)
            // so the Hotspot interface (ap0/wlan0) can receive Zoiper traffic
            // even with mobile data and internet completely disabled.
            UdpSipTransport(localPort, if (localSendMode) null else network)
        }
        t.open()
        transport = t
        // Note: per-client transportOverride is passed to SipBuilder calls so dual-stack
        // local UDP + cloud TLS clients never clobber each other's Via/Contact headers.
        sendExecutor = Executors.newSingleThreadExecutor { r ->
            Thread({
                // Once for the thread, not once per packet: this was building
                // a ThreadPolicy object on every SIP message sent.
                android.os.StrictMode.setThreadPolicy(
                    android.os.StrictMode.ThreadPolicy.Builder().permitAll().build()
                )
                r.run()
            }, "SIP-Send")
        }
        // Resolve server DNS now (background thread) so sendTo never blocks on
        // DNS.  TLS resolved the name when it connected and writes to an open
        // socket, so it needs no address cache.
        resolvedServerAddr = if (useTls || localSendMode) null else runCatching {
            InetAddress.getByName(serverDomain)
        }.getOrNull()
        uiLog(
            if (useTls && !localSendMode) "TLS session up to $serverDomain:$serverPort (local port $advertisedPort)"
            else "Socket bound to $localIp:$localPort${if (localSendMode) " [Local Send]" else ""}"
        )
    }

    // ── Send ────────────────────────────────────────────

    fun sendTo(data: String, address: Pair<String, Int>) {
        val executor = sendExecutor
        if (executor == null) {
            uiLog("sendTo: executor is NULL — sending on new thread")
            Thread({
                android.os.StrictMode.setThreadPolicy(
                    android.os.StrictMode.ThreadPolicy.Builder().permitAll().build()
                )
                doSend(data, address)
            }, "SIP-FallbackSend").start()
            return
        }
        try {
            executor.execute { doSend(data, address) }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // Shut down under us (stop() or a reconnect in progress).  Losing
            // one datagram is recoverable; an exception thrown from here into
            // a call setup thread is not.
            uiLog("sendTo: executor shut down — sending on new thread")
            Thread({ doSend(data, address) }, "SIP-FallbackSend").start()
        }
    }

    private fun doSend(data: String, address: Pair<String, Int>) {
        try {
            val t = transport ?: return
            // Use the cached address for the server so this never does DNS.
            val cached = resolvedServerAddr
            if (t is UdpSipTransport && cached != null && address.first == serverDomain) {
                t.send(data, cached, address.second)
            } else {
                t.send(data, address.first, address.second)
            }
        } catch (e: Exception) {
            uiLog("Send error to ${address.first}:${address.second} [thread=${Thread.currentThread().name}]: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun sendResponse(data: String, address: Pair<String, Int>) = sendTo(data, address)

    // ── Receive Loop ────────────────────────────────────

    private fun receiveLoop() {
        while (running.get()) {
            try {
                val t = transport ?: break
                val (data, address) = t.receive() ?: continue
                handlePacket(data, address)
            } catch (_: SocketTimeoutException) {
                // normal
            } catch (e: Exception) {
                if (!running.get()) break
                uiLog("Receive error: ${e.message}")
                // A datagram socket survives a bad packet; a dropped stream
                // does not, and would spin here throwing the same error
                // forever.  Tear it down and let the monitor loop rebuild it.
                if (useTls) {
                    registered = false
                    transport?.close()
                }
            }
        }
    }

    private fun handlePacket(data: String, address: Pair<String, Int>) {
        if (localSendMode && (address.first == localIp || address.first == "127.0.0.1" || address.first == "0.0.0.0")) {
            return
        }
        val msg = SipMessage.parse(data) ?: return

        // In Local Send mode, incoming SIP requests from a LAN softphone
        // (REGISTER, SUBSCRIBE, OPTIONS, INVITE) teach us the peer's live UDP
        // address so we can send incoming GSM calls & fake SMS to it.
        // MESSAGE requests must NOT overwrite registeredPeer so an unverified
        // LAN sender cannot spoof authorization before CommandAuthorization runs.
        if (localSendMode && msg.isRequest) {
            if (msg.method == "MESSAGE") {
                if (registeredPeer?.first == address.first) {
                    lastPeerSeenMs = System.currentTimeMillis()
                }
            } else {
                val prevPeer = registeredPeer
                val callerUser = msg.callerNumber?.takeIf {
                    it.isNotBlank() && it != "unknown" && it != "0000"
                }
                val userFromPacket = if (msg.method == "REGISTER") {
                    callerUser
                        ?: msg.to?.let { msg.extractUser(it) }?.takeIf { it.isNotBlank() && it != "0000" }
                        ?: registeredPeerUser
                } else {
                    callerUser ?: registeredPeerUser
                }
                registeredPeer = address
                registeredPeerUser = userFromPacket
                lastPeerSeenMs = System.currentTimeMillis()
                if (msg.method == "REGISTER") {
                    registered = true
                    lastRegisterTime = lastPeerSeenMs
                    lastServerResponseTime = lastRegisterTime
                    val ok = SipBuilder.registerOk200(msg, userFromPacket, address.first, address.second)
                    sendTo(ok, address)
                    if (prevPeer != address) {
                        uiLog("Local Send: Zoiper bağlandı ($userFromPacket@${address.first}:${address.second})")
                        onLocalPeerRegistered?.invoke(userFromPacket, address)
                    }
                    resendPendingOutboundInvites(address)
                    return
                }
                if (prevPeer == null) {
                    uiLog("Local Send: İstemci algılandı ($userFromPacket@${address.first}:${address.second})")
                    onLocalPeerRegistered?.invoke(userFromPacket, address)
                }
                if (prevPeer != address) {
                    resendPendingOutboundInvites(address)
                }
            }
        }

        // SUBSCRIBE / PUBLISH (sent by Zoiper on registration for MWI / presence)
        if (msg.isRequest && (msg.method == "SUBSCRIBE" || msg.method == "PUBLISH")) {
            sendTo(
                SipBuilder.statusResponse(msg, 200, "OK", listOf("Expires: 3600")),
                address
            )
            return
        }

        // OPTIONS keepalive from server / Zoiper
        if (msg.isRequest && msg.method == "OPTIONS") {
            val resp = SipBuilder.optionsResponse(
                msg, username, publicIp, advertisedPort,
                transportOverride = viaTransport
            )
            sendTo(resp, address)
            return
        }

        // Registration responses
        if (msg.isResponse && msg.cseq?.contains("REGISTER", ignoreCase = true) == true) {
            handleRegisterResponse(msg)
            return
        }

        // Responses to a MESSAGE we sent (an SMS handed to the server).
        // Checked before call routing: a page-mode MESSAGE has no dialog, so
        // its Call-ID must not be mistaken for a call's.
        if (msg.isResponse && msg.cseq?.contains("MESSAGE") == true) {
            if (handleMessageResponse(msg)) return
        }

        // OPTIONS response (for our keepalive) — update server liveness tracker
        if (msg.isResponse && msg.cseq?.contains("OPTIONS") == true) {
            lastServerResponseTime = System.currentTimeMillis()
            return
        }

        // Route to existing call
        val callId = msg.callId
        if (callId != null) {
            val call = activeCalls[callId]
            if (call != null) {
                call.peerSourceAddress = address
                // Log non-trivial SIP responses for debugging
                if (msg.isResponse && msg.statusCode != 100) {
                    uiLog("SIP ${msg.statusCode} for INVITE (call $callId)")
                }
                if (localSendMode && msg.isResponse && (msg.statusCode == 180 || msg.statusCode == 183 || msg.statusCode == 200)) {
                    com.callagent.gateway.localsend.PushWakeController.cancelWake("local SIP ${msg.statusCode} alındı")
                }
                val handled = call.handleMessage(msg)
                if (!handled) {
                    Log.w(TAG, "Unhandled SIP message for call $callId: ${msg.startLine}")
                }
                if (call.state == SipCall.State.TERMINATED) {
                    activeCalls.remove(callId)
                    listener?.onCallTerminated(call)
                }
                return
            }
        }

        // Page-mode MESSAGE from the server — an SMS to send.
        if (msg.isRequest && msg.method == "MESSAGE") {
            val (code, extra) = try {
                onSmsRequestWithPeer?.invoke(msg, address)
                    ?: onSmsRequest?.invoke(msg)
                    ?: (405 to emptyList())
            } catch (e: Exception) {
                Log.e(TAG, "onSmsRequest failed: ${e.message}", e)
                500 to emptyList()
            }
            val reason = when (code) {
                200 -> "OK"
                202 -> "Accepted"
                400 -> "Bad Request"
                403 -> "Forbidden"
                404 -> "Not Found"
                405 -> "Method Not Allowed"
                415 -> "Unsupported Media Type"
                486 -> "Busy Here"
                503 -> "Service Unavailable"
                else -> "Error"
            }
            sendTo(SipBuilder.statusResponse(msg, code, reason, extra), address)
            return
        }

        // New INVITE
        if (msg.isRequest && msg.method == "INVITE") {
            handleIncomingInvite(msg, address)
            return
        }

        // Stray response for unknown call
        if (msg.isResponse) {
            Log.w(TAG, "Stray SIP response ${msg.statusCode} for unknown call-id=$callId (${msg.cseq})")
            return
        }

        // Stray ACK
        if (msg.isRequest && msg.method == "ACK") {
            // route to call if exists
            if (callId != null) activeCalls[callId]?.handleMessage(msg)
            return
        }

        // Stray BYE
        if (msg.isRequest && msg.method == "BYE") {
            if (callId != null) {
                activeCalls[callId]?.handleMessage(msg)
                activeCalls.remove(callId)
            }
            // Send 200 OK even for unknown BYE
            val ok = SipBuilder.ok200(
                msg, username, publicIp, advertisedPort,
                transportOverride = viaTransport
            )
            sendTo(ok, address)
            return
        }
    }

    // ── Registration ────────────────────────────────────

    /**
     * Send ONE REGISTER and wait for receiveLoop() to handle the response.
     * Uses a CountDownLatch so only receiveLoop() reads from the socket
     * (eliminates the old race condition with waitForRegistration).
     *
     * Rate-limited by [RegisterBackoff]: while a previous failure's cooldown
     * is still running this returns false without putting a packet on the
     * wire.  Returns false immediately if another thread is already
     * registering, so the startup and monitor threads cannot overlap.
     */
    private fun register(): Boolean {
        if (!running.get()) return false
        if (!registering.compareAndSet(false, true)) {
            Log.d(TAG, "REGISTER already in flight — skipping duplicate")
            return false
        }
        try {
            val hold = RegisterBackoff.holdOffMs(serverDomain)
            if (hold > 0) {
                Log.d(TAG, "REGISTER held off for ${hold / 1000}s more (backoff)")
                return false
            }
            uiLog("REGISTER → $serverDomain")
            registerAuthAttempted.set(false)
            registrationLatch = CountDownLatch(1)
            sendRegister()
            // Wait for receiveLoop → handleRegisterResponse to signal
            try {
                registrationLatch?.await(REGISTER_TIMEOUT_SEC, TimeUnit.SECONDS)
            } catch (_: InterruptedException) { return false }
            if (registered) {
                RegisterBackoff.onSuccess()
                registerFailures = 0
                return true
            }
            if (!running.get()) return false
            val next = RegisterBackoff.onFailure()
            registerFailures++
            uiLog("REGISTER failed — next attempt in ${next / 1000}s")
            listener?.onRegistrationFailed()
            return false
        } finally {
            registering.set(false)
        }
    }

    /**
     * Register now, ignoring the backoff cooldown.  For an explicit retry from
     * the UI, where waiting out an exponential delay is not what was asked for.
     */
    fun retryNow() {
        RegisterBackoff.reset()
        registerFailures = 0
        Thread({
            try {
                uiLog("Manual retry requested")
                register()
            } catch (e: Exception) {
                uiLog("Manual retry failed: ${e.message}")
            }
        }, "SIP-ManualRetry").start()
    }

    private fun sendRegister(auth: String? = null) {
        val msg = SipBuilder.register(
            username, serverDomain, serverPort,
            publicIp, advertisedPort,
            callIdBase, cseq.getAndIncrement(),
            auth,
            transportOverride = viaTransport
        )
        sendTo(msg, serverAddress)
    }

    /** Called by receiveLoop() — no socket reads here. */
    private fun handleRegisterResponse(msg: SipMessage): Boolean {
        return when (msg.statusCode) {
            200 -> {
                registered = true
                lastRegisterTime = System.currentTimeMillis()
                lastServerResponseTime = lastRegisterTime
                uiLog("Registered with $serverDomain")
                registrationLatch?.countDown()
                listener?.onRegistered()
                true
            }
            401, 407 -> {
                if (!registerAuthAttempted.compareAndSet(false, true)) {
                    uiLog("REGISTER ${msg.statusCode} rejected after auth — aborting retry")
                    registrationLatch?.countDown()
                    return false
                }
                uiLog("REGISTER ${msg.statusCode} challenge, sending auth")
                val authParams = SipAuth.parseChallenge(msg)
                if (authParams != null) {
                    val uri = "sip:$serverDomain:$serverPort"
                    val auth = SipAuth.buildAuthHeader("REGISTER", uri, username, password, authParams)
                    sendRegister(auth)
                } else {
                    uiLog("Failed to parse auth challenge")
                    registrationLatch?.countDown()
                }
                false
            }
            else -> {
                uiLog("REGISTER unexpected response: ${msg.statusCode}")
                registrationLatch?.countDown()
                false
            }
        }
    }

    // ── Incoming INVITE ─────────────────────────────────

    private fun handleIncomingInvite(msg: SipMessage, address: Pair<String, Int>) {
        val callId = msg.callId ?: return
        Log.i(TAG, "Incoming INVITE call-id=$callId from=${msg.callerNumber}")

        // Send 100 Trying
        sendTo(SipBuilder.trying100(msg), address)

        val call = SipCall(callId, SipCall.Direction.INBOUND, this)
        call.originalInvite = msg
        call.callerNumber = msg.callerNumber
        call.callerDisplayName = msg.callerDisplayName
        call.remoteContactUri = msg.contactUri
        call.remoteContactAddress = msg.contactAddress ?: address
        call.peerSourceAddress = address
        call.remoteCseq = msg.cseq?.split(" ")?.firstOrNull()?.toIntOrNull() ?: 1
        call.fromHeader = msg.from
        call.toHeader = msg.to
        call.remoteTag = msg.fromTag
        call.routeSet = msg.recordRouteHeaders

        // Parse SDP
        msg.sdpRtpPort?.let { call.remoteRtpPort = it }
        msg.sdpAddress?.let { call.remoteRtpAddress = it }

        // SRTP is answered, never volunteered: we key up only if their media
        // line actually said SAVP.  Our own key is fresh and independent --
        // reflecting theirs back would mean one key protecting both
        // directions, which SDES explicitly does not do.
        if (srtpEnabled && call.absorbRemoteSrtp(msg)) {
            val suite = call.remoteSrtpKeys?.suite ?: SrtpCryptoSuite.offered
            call.localSrtpKeys = SrtpKeys.generate(suite)
            uiLog("SRTP offered by server (${suite.sdpName}) — answering SAVP")
        } else if (msg.sdpIsSavp) {
            // They require encryption and we cannot give it.  Better to say so
            // than to answer AVP and have the call rejected for a reason that
            // never reaches this log.
            uiLog(
                if (!useTls) "Server offered SRTP but signalling is UDP — not accepting keys over cleartext"
                else "Server offered SRTP but it is switched off here"
            )
        }
        call.negotiatedPayloadType = msg.sdpPreferredPayloadType
        msg.sdpTelephoneEventPt?.let { call.telephoneEventPt = it }
        Log.i(TAG, "Incoming INVITE codec: pt=${call.negotiatedPayloadType} dtmfPt=${call.telephoneEventPt} offered=${msg.sdpOfferedPayloadTypes} codecs=${msg.sdpCodecs}")
        Log.i(TAG, "Incoming INVITE SDP: ${msg.body.replace("\r\n", " | ").replace("\n", " | ")}")

        // Check for GSM-forward header
        call.gsmForwardNumber = msg.gsmForwardNumber

        activeCalls[callId] = call
        listener?.onIncomingCall(call)
    }

    // ── Outbound INVITE ─────────────────────────────────

    private fun buildOutboundInviteForPeer(
        call: SipCall,
        targetExtension: String,
        peerAddr: Pair<String, Int>,
        cseqNum: Int
    ): String {
        val effectiveDomain = if (localSendMode) peerAddr.first else serverDomain
        val effectiveTarget = if (localSendMode) registeredPeerUser.ifEmpty { targetExtension } else targetExtension
        call.toHeader = "<sip:$effectiveTarget@$effectiveDomain>"
        val targetUri = if (localSendMode) {
            "sip:$effectiveTarget@${peerAddr.first}:${peerAddr.second}"
        } else {
            "sip:$targetExtension@$serverDomain"
        }
        val (fromUser, fromDisplayName) = buildInviteFromParts(
            serverDomain = serverDomain,
            username = username,
            localSendMode = localSendMode,
            callerIdNumber = call.outboundCallerIdNumber,
            callerIdName = call.outboundCallerIdName
        )
        val fromDomain = if (localSendMode) publicIp else serverDomain
        return SipBuilder.invite(
            targetUri, username, fromDomain,
            publicIp, advertisedPort,
            call.callId, cseqNum,
            call.localRtpPort,
            fromTag = call.localTag,
            callerIdNumber = fromUser,
            callerIdName = fromDisplayName,
            srtp = call.localSrtpKeys,
            transportOverride = viaTransport
        )
    }

    /**
     * When a Local Send softphone (Linphone on iOS) wakes from background or
     * screen lock while an incoming GSM call is still ringing, it opens a new
     * UDP port and sends REGISTER.  Immediately re-send any unanswered outbound
     * INVITE to that new port so the call rings on the phone right away.
     */
    private fun resendPendingOutboundInvites(newPeerAddr: Pair<String, Int>) {
        if (!localSendMode) return
        for (call in activeCalls.values) {
            if (call.direction == SipCall.Direction.OUTBOUND &&
                !call.responseReceived &&
                call.callId !in suppressedLocalCallIds &&
                call.state != SipCall.State.ANSWERED &&
                call.state != SipCall.State.TERMINATED) {
                call.peerSourceAddress = newPeerAddr
                val cseqNum = (call.localCseq - 1).coerceAtLeast(1)
                val updatedInvite = buildOutboundInviteForPeer(call, registeredPeerUser, newPeerAddr, cseqNum)
                call.originalInvite = SipMessage.parse(updatedInvite)
                Log.i(TAG, "Peer re-registered at ${newPeerAddr.first}:${newPeerAddr.second} during ringing call ${call.callId} — sending INVITE immediately")
                sendTo(updatedInvite, newPeerAddr)
            }
        }
    }

    /** Place an outbound SIP call (gateway → Asterisk or Local Send Zoiper peer) */
    fun makeCall(
        targetExtension: String,
        localRtpPort: Int,
        callerIdNumber: String? = null,
        callerIdName: String? = null
    ): SipCall {
        val initialPeer = if (localSendMode) resolveLocalPeerAddress() else null
        val peerAddr = if (localSendMode) (initialPeer ?: Pair(serverDomain, serverPort)) else serverAddress
        val effectiveDomain = if (localSendMode) peerAddr.first else serverDomain
        val effectiveTarget = if (localSendMode) registeredPeerUser.ifEmpty { targetExtension } else targetExtension
        val callId = "${System.currentTimeMillis()}call@$publicIp"
        val call = SipCall(callId, SipCall.Direction.OUTBOUND, this)
        call.peerSourceAddress = peerAddr
        call.localRtpPort = localRtpPort
        call.outboundCallerIdNumber = callerIdNumber
        call.outboundCallerIdName = callerIdName
        val (fromUser, fromDisplayName) = buildInviteFromParts(
            serverDomain = serverDomain,
            username = username,
            localSendMode = localSendMode,
            callerIdNumber = callerIdNumber,
            callerIdName = callerIdName
        )
        val fromDomain = if (localSendMode) publicIp else serverDomain
        val fromDisplay = if (!fromDisplayName.isNullOrBlank()) "\"$fromDisplayName\" " else ""
        call.fromHeader = "$fromDisplay<sip:$fromUser@$fromDomain>;tag=${call.localTag}"
        call.toHeader = "<sip:$effectiveTarget@$effectiveDomain>"

        // Generated once, here, and reused for every retry and re-INVITE of
        // this call: rekeying mid-dialog is not something chan_sip handles
        // predictably, and there is no reason to.
        if (srtpEnabled) call.localSrtpKeys = SrtpKeys.generate(SrtpCryptoSuite.offered)

        val cseqNum = call.localCseq++
        var invite = buildOutboundInviteForPeer(call, targetExtension, peerAddr, cseqNum)
        val targetUri = if (localSendMode) {
            "sip:$effectiveTarget@${peerAddr.first}:${peerAddr.second}"
        } else {
            "sip:$targetExtension@$serverDomain"
        }
        // Keep the exact INVITE transaction headers.  If the GSM caller
        // hangs up before Asterisk answers, the early dialog must be ended
        // with CANCEL using the same Via branch and INVITE CSeq.
        call.originalInvite = SipMessage.parse(invite)

        activeCalls[callId] = call
        if (!localSendMode) {
            sendTo(invite, peerAddr)
            Log.i(TAG, "Sent INVITE RURI=$targetUri From=${call.fromHeader} (call-id=$callId)")
        } else if (initialPeer != null && initialPeer.first != localIp) {
            sendTo(invite, initialPeer)
            Log.i(TAG, "Sent local INVITE RURI=$targetUri From=${call.fromHeader} (call-id=$callId)")
        } else {
            Log.i(TAG, "Local Send: no active LAN peer at call start — firing PushKit immediately (call-id=$callId)")
        }

        // RFC 3261 Timer A: retransmit INVITE over UDP until any response is received.
        // Intervals: 500ms, 1s, 2s, 4s (capped at T2=4s, or 2s in Local Send).
        // Stops immediately when any SIP response arrives (including 401 auth challenge).
        // RFC 3261 §17.1.1.2 forbids Timer A retransmissions over reliable transports (TCP/TLS).
        if (!(useTls && !localSendMode)) {
            Thread({
                var delay = 500L
                val maxDelay = if (localSendMode) 2000L else 4000L
                val maxRetransmits = if (localSendMode) 16 else 7
                var elapsed = 0L
                var unanswered = false
                var wakeFired = false
                var lastDest = peerAddr

                if (localSendMode && initialPeer == null && callId !in suppressedLocalCallIds) {
                    wakeFired = true
                    if (com.callagent.gateway.localsend.LocalSendController.triggerPushWakeIfNeeded(callerIdNumber ?: targetExtension)) {
                        uiLog("Local Send: iPhone uykuda — sip.linphone.org Apple PushKit uyandırma hemen gönderiliyor")
                    }
                }

                for (i in 1..maxRetransmits) {
                    Thread.sleep(delay)
                    elapsed += delay
                    if (call.responseReceived) {
                        if (call.state == SipCall.State.RINGING || call.state == SipCall.State.ANSWERED) {
                            com.callagent.gateway.localsend.PushWakeController.cancelWake("local INVITE yanıt aldı")
                        }
                        break
                    }
                    if (!activeCalls.containsKey(callId)) break
                    if (callId in suppressedLocalCallIds) break
                    if (localSendMode && !wakeFired && elapsed >= 500L) {
                        wakeFired = true
                        if (com.callagent.gateway.localsend.LocalSendController.triggerPushWakeIfNeeded(callerIdNumber ?: targetExtension)) {
                            uiLog("Local Send: iPhone uykuda — sip.linphone.org Apple PushKit uyandırma gönderiliyor")
                        }
                    }
                    val currentDest = if (localSendMode) resolveLocalPeerAddress() else peerAddr
                    if (currentDest != null && (!localSendMode || currentDest.first != localIp)) {
                        if (localSendMode && currentDest != lastDest) {
                            lastDest = currentDest
                            call.peerSourceAddress = currentDest
                            invite = buildOutboundInviteForPeer(call, targetExtension, currentDest, cseqNum)
                            call.originalInvite = SipMessage.parse(invite)
                        }
                        Log.i(TAG, "INVITE retransmit #$i for $callId to ${currentDest.first}:${currentDest.second} (${delay}ms)")
                        if (i == 1) uiLog("No response to INVITE — retransmitting")
                        sendTo(invite, currentDest)
                        if (localSendMode && currentDest.second != 5060) {
                            sendTo(invite, Pair(currentDest.first, 5060))
                        }
                        unanswered = true
                    }
                    delay = minOf(delay * 2, maxDelay)
                }
                if (unanswered && !call.responseReceived && activeCalls.containsKey(callId) && callId !in suppressedLocalCallIds) {
                    uiLog("Server never answered the INVITE (${elapsed / 1000}s, $maxRetransmits sends)")
                }
            }, "INVITE-Retransmit").start()
        }

        return call
    }

    // ── Page-mode MESSAGE (RFC 3428) ────────────────────

    /** One in-flight outbound MESSAGE, keyed by Call-ID. */
    private class MessageTxn {
        val latch = CountDownLatch(1)
        @Volatile var status = 0
        @Volatile var challenge: SipAuth.AuthParams? = null
    }

    private val pendingMessages = ConcurrentHashMap<String, MessageTxn>()

    /**
     * Send a SIP MESSAGE and wait for its final response.
     *
     * Blocking, so call it off the main thread.  Returns the status code the
     * server answered with (202 and 200 both mean accepted), or 0 if nothing
     * came back — the caller keeps the message queued and tries again rather
     * than dropping it.
     *
     * A 401/407 challenge is answered once with credentials, the way REGISTER
     * and INVITE are: Asterisk may or may not require auth on MESSAGE
     * depending on how the endpoint is configured, and guessing wrong in
     * either direction loses messages.
     */
    fun sendSipMessage(
        targetUri: String,
        fromUser: String,
        body: String,
        extraHeaders: List<String> = emptyList(),
        contentType: String = "text/plain;charset=UTF-8",
        fromDisplayName: String? = null
    ): Int = sendSipMessageToAddress(
        targetUri = targetUri,
        targetAddress = serverAddress,
        fromUser = fromUser,
        fromDisplayName = fromDisplayName,
        body = body,
        extraHeaders = extraHeaders,
        contentType = contentType
    )

    fun sendSipMessageToAddress(
        targetUri: String,
        targetAddress: Pair<String, Int>,
        fromUser: String,
        fromDisplayName: String? = null,
        body: String,
        extraHeaders: List<String> = emptyList(),
        contentType: String = "text/plain;charset=UTF-8"
    ): Int {
        if (!running.get()) return 0
        val callId = "${System.currentTimeMillis()}msg@$publicIp"
        val fromTag = "gw${(100000000..999999999).random()}"
        val domainForFrom = if (localSendMode) publicIp else serverDomain
        val txn = MessageTxn()
        pendingMessages[callId] = txn
        try {
            var cseq = 1
            sendTo(
                SipBuilder.message(
                    targetUri, fromUser, domainForFrom, publicIp, advertisedPort,
                    callId, cseq, body, contentType, extraHeaders, fromTag,
                    auth = null, fromDisplayName = fromDisplayName,
                    transportOverride = viaTransport
                ),
                targetAddress
            )
            if (!txn.latch.await(MESSAGE_TIMEOUT_SEC, TimeUnit.SECONDS)) return 0

            val challenge = txn.challenge
            if (challenge != null) {
                // Same transaction identity, next CSeq — a fresh Call-ID would
                // read as an unrelated message to the server.
                val retry = MessageTxn()
                pendingMessages[callId] = retry
                cseq++
                val auth = SipAuth.buildAuthHeader(
                    "MESSAGE", targetUri, username, password, challenge
                )
                sendTo(
                    SipBuilder.message(
                        targetUri, fromUser, domainForFrom, publicIp, advertisedPort,
                        callId, cseq, body, contentType, extraHeaders, fromTag, auth,
                        fromDisplayName = fromDisplayName,
                        transportOverride = viaTransport
                    ),
                    targetAddress
                )
                if (!retry.latch.await(MESSAGE_TIMEOUT_SEC, TimeUnit.SECONDS)) return 0
                return retry.status
            }
            return txn.status
        } catch (e: Exception) {
            uiLog("SIP MESSAGE failed: ${e.message}")
            return 0
        } finally {
            pendingMessages.remove(callId)
        }
    }

    private fun handleMessageResponse(msg: SipMessage): Boolean {
        val callId = msg.callId ?: return false
        val txn = pendingMessages[callId] ?: return false
        val code = msg.statusCode ?: 0
        if (code == 100) return true          // provisional, keep waiting
        if (code == 401 || code == 407) {
            txn.challenge = SipAuth.parseChallenge(msg)
        }
        txn.status = code
        txn.latch.countDown()
        return true
    }

    /** Re-send INVITE with authentication */
    fun resendInviteWithAuth(call: SipCall, authParams: SipAuth.AuthParams) {
        val toHeader = call.toHeader ?: return
        // Extract target URI from To header
        val uriStart = toHeader.indexOf("sip:")
        val uriEnd = toHeader.indexOf('>', uriStart).let { if (it < 0) toHeader.length else it }
        val targetUri = if (uriStart >= 0) toHeader.substring(uriStart, uriEnd) else return

        val (fromUser, fromDisplayName) = buildInviteFromParts(
            serverDomain = serverDomain,
            username = username,
            localSendMode = localSendMode,
            callerIdNumber = call.outboundCallerIdNumber,
            callerIdName = call.outboundCallerIdName
        )
        val fromDomain = if (localSendMode) publicIp else serverDomain
        val fromDisplay = if (!fromDisplayName.isNullOrBlank()) "\"$fromDisplayName\" " else ""
        call.fromHeader = "$fromDisplay<sip:$fromUser@$fromDomain>;tag=${call.localTag}"

        val auth = SipAuth.buildInviteAuthHeader(targetUri, username, password, authParams)
        val invite = SipBuilder.invite(
            targetUri, username, fromDomain,
            publicIp, advertisedPort,
            call.callId, call.localCseq++,
            call.localRtpPort,
            fromTag = call.localTag,
            callerIdNumber = fromUser,
            callerIdName = fromDisplayName,
            auth = auth,
            srtp = call.localSrtpKeys,
            transportOverride = viaTransport
        )
        // The authenticated INVITE has a new CSeq and Via branch; a later
        // early-dialog cancellation must cancel this transaction, not the
        // initial unauthenticated challenge request.
        call.originalInvite = SipMessage.parse(invite)
        sendTo(invite, serverAddress)
    }

    fun removeCall(callId: String) {
        activeCalls.remove(callId)
    }

    // ── Monitor / Keepalive ─────────────────────────────

    /** Consecutive keepalive failures (OPTIONS sent with no response) */
    @Volatile private var keepaliveFailures = 0

    /** When the last NAT keepalive went out, so its interval is independent
     *  of how often the monitor loop wakes up. */
    @Volatile private var lastKeepaliveTime = 0L

    /** When the last TLS connect was attempted, and how long to wait again. */
    @Volatile private var lastTlsAttempt = 0L
    @Volatile private var tlsBackoffMs = TLS_BACKOFF_MIN_MS
    private val MAX_KEEPALIVE_FAILURES = 3

    /** Called when the connection appears dead and needs a full reconnect */
    @Volatile var onConnectionLost: (() -> Unit)? = null

    private fun monitorLoop() {
        Thread.sleep(15_000)
        while (running.get()) {
            try {
                if (localSendMode) {
                    registered = true
                    val now = System.currentTimeMillis()
                    if (registeredPeer != null && now - lastKeepaliveTime >= 10_000L) {
                        sendNatKeepalive()
                        lastKeepaliveTime = now
                    }
                    Thread.sleep(POLL_INTERVAL_MS)
                    continue
                }
                if (!registered) {
                    // A dropped TLS connection has to be rebuilt before any
                    // REGISTER can go anywhere.  UDP has nothing to reconnect,
                    // so this is skipped there entirely.
                    if (useTls && transport?.isOpen != true) {
                        // Backed off, not retried on every poll.  This host
                        // runs fail2ban, and a connection attempt every 5s
                        // while the server is down gets the gateway's address
                        // banned -- which then looks exactly like the outage
                        // that started it, and outlives it.
                        val now = System.currentTimeMillis()
                        if (now - lastTlsAttempt >= tlsBackoffMs) {
                            lastTlsAttempt = now
                            try {
                                createSocket()
                                tlsBackoffMs = TLS_BACKOFF_MIN_MS
                            } catch (e: Exception) {
                                uiLog(
                                    "TLS reconnect failed (${e.message}), " +
                                        "next try in ${tlsBackoffMs / 1000}s"
                                )
                                tlsBackoffMs =
                                    (tlsBackoffMs * 2).coerceAtMost(TLS_BACKOFF_MAX_MS)
                            }
                        }
                    }
                    // register() rate-limits itself — while the cooldown is
                    // running it returns without sending anything, so polling
                    // once per loop costs no traffic.
                    if (register()) {
                        keepaliveFailures = 0
                    } else if (registerFailures >= MAX_REGISTER_FAILURES) {
                        // Rebuild the socket in case the local IP changed.
                        // Safe to repeat: the backoff schedule lives in the
                        // companion, so the replacement SipClient inherits the
                        // cooldown instead of restarting the retry cycle.
                        uiLog("Registration failing repeatedly, requesting reconnect")
                        registerFailures = 0
                        if (running.get()) onConnectionLost?.invoke()
                    }
                } else {
                    // Keeping the NAT binding open needs a packet every 20-30s,
                    // but it does not need to be a SIP transaction: a bare CRLF
                    // refreshes the mapping and the server neither answers it
                    // nor logs it.  Timed on its own clock rather than once per
                    // iteration, so the loop can poll faster than the binding
                    // needs packets.
                    // TLS needs none of this: the mapping is held open by the
                    // TCP connection itself, and the server's 60s OPTIONS keep
                    // traffic flowing both ways regardless.
                    if (!useTls) {
                        val now = System.currentTimeMillis()
                        if (now - lastKeepaliveTime >= NAT_KEEPALIVE_INTERVAL_MS) {
                            sendNatKeepalive()
                            lastKeepaliveTime = now
                        }
                    }

                    // The registration refresh is the only SIP request this
                    // gateway makes while idle.  It is authenticated, it is
                    // exactly what a registrar expects, and it doubles as the
                    // liveness check — so there is nothing left for OPTIONS to
                    // do.  Polling with OPTIONS every 15s meant 240
                    // unauthenticated requests an hour, each answered 401 and
                    // recorded as an auth failure, which is the pattern
                    // fail2ban counts; it banned this gateway's IP repeatedly.
                    if (System.currentTimeMillis() - lastRegisterTime > REREGISTER_INTERVAL_MS) {
                        uiLog("Refreshing registration")
                        registered = false
                        if (!register()) {
                            // Failed refresh means the server or the path is
                            // gone; the retry loop above takes over from here.
                            keepaliveFailures++
                            if (keepaliveFailures >= MAX_KEEPALIVE_FAILURES) {
                                uiLog("Registration refresh failed $keepaliveFailures times")
                                keepaliveFailures = 0
                                if (running.get()) onConnectionLost?.invoke()
                            }
                        } else {
                            keepaliveFailures = 0
                        }
                    }
                }
            } catch (e: Exception) {
                uiLog("Monitor error: ${e.message}")
                registered = false
            }
            // Half the shortest thing this loop has to be on time for.  The
            // keepalive interval is enforced by comparing timestamps, but the
            // comparison only happens when this wakes, so the period sets the
            // granularity: at 10s a 25s keepalive actually goes out every 30s.
            Thread.sleep(POLL_INTERVAL_MS)
        }
    }

    /**
     * Refresh the NAT binding without generating a SIP transaction.
     *
     * A bare CRLF is the standard SIP keepalive (RFC 5626 §3.5.1).  It costs
     * the server nothing, is not a request, and cannot be counted as a failed
     * authentication — unlike the OPTIONS this replaces.
     */
    private fun sendNatKeepalive() {
        try {
            sendTo("\r\n\r\n", serverAddress)
        } catch (e: Exception) {
            Log.w(TAG, "NAT keepalive failed: ${e.message}")
        }
    }


    companion object {
        private const val TAG = "SipClient"

        /**
         * Compute `(fromUser, fromDisplayName)` for outbound INVITEs.
         *
         * Flexisip (`sip.linphone.org`) rejects INVITEs whose `From` URI user does
         * not match the authenticated SIP account username, so in cloud Linphone
         * mode we put [username] in the `From` URI and surface caller ID in the
         * display name.  Custom PBX/Asterisk trunks and Local Send keep caller ID
         * in the `From` URI user.
         */
        fun buildInviteFromParts(
            serverDomain: String,
            username: String,
            localSendMode: Boolean,
            callerIdNumber: String?,
            callerIdName: String?
        ): Pair<String, String?> {
            return if (!localSendMode && serverDomain.contains("linphone.org", ignoreCase = true)) {
                val display = callerIdName?.takeIf { it.isNotBlank() } ?: callerIdNumber
                Pair(username, display)
            } else {
                Pair(callerIdNumber ?: username, callerIdName)
            }
        }

        /** True when a local peer was seen within [ttlMs] (default 65s). */
        fun isPeerFresh(lastSeenMs: Long, nowMs: Long, ttlMs: Long = 65_000L): Boolean {
            if (lastSeenMs <= 0L) return false
            val age = nowMs - lastSeenMs
            return age in 0L..ttlMs
        }

        /** Seconds to wait for a REGISTER response before calling it failed. */
        private const val REGISTER_TIMEOUT_SEC = 10L

        /** How long to wait for a MESSAGE's final response before treating it
         *  as unsent and leaving it queued for the next attempt. */
        private const val MESSAGE_TIMEOUT_SEC = 10L

        /** Consecutive failures before asking GatewayService for a new socket. */
        private const val MAX_REGISTER_FAILURES = 3

        /**
         * How often to refresh the registration.
         *
         * The REGISTER advertises `expires=3600` and the server grants it --
         * verified against callagent.pro, whose 200 OK carries `Expires: 3600`
         * (chan_sip's default).  Half the granted lifetime is the conventional
         * refresh point: it leaves a full 30 minutes to notice a failure and
         * retry before the binding actually lapses.
         *
         * The previous ten minutes was not derived from anything the server
         * said -- nothing here reads the granted expiry -- and refreshed six
         * times an hour where twice will do.  Each refresh is challenged, so
         * it also put six 401s an hour in the registrar's auth log per device.
         */
        private const val REREGISTER_INTERVAL_MS = 30 * 60 * 1000L

        /**
         * How often to refresh the NAT binding.
         *
         * Consumer routers time out an idle UDP mapping somewhere around
         * 30-60s; 25s stays under the low end with room to spare.  This is
         * deliberately separate from the monitor loop's own period, which is
         * about how fast a lost registration is noticed and has no business
         * setting how often a packet goes on the wire.  Sending one per
         * 10s iteration meant 360 packets an hour to hold a binding that
         * needs 144.
         *
         * Getting this wrong is not subtle: the server qualifies this peer
         * with OPTIONS every 60s, and those only arrive while the mapping is
         * open.  Too long an interval and Asterisk marks the peer UNREACHABLE
         * and inbound calls stop.
         */
        private const val NAT_KEEPALIVE_INTERVAL_MS = 25_000L

        /**
         * How often the monitor loop wakes.
         *
         * It must divide [NAT_KEEPALIVE_INTERVAL_MS], because the keepalive
         * fires on the first tick at or past the interval -- with a 10s period
         * a 25s keepalive went out every 30s, measured on the wire.  Waking is
         * only a couple of timestamp comparisons; it sends nothing on its own.
         */
        private const val POLL_INTERVAL_MS = 5_000L

        /** Read timeout, so both loops notice stop() promptly. */
        private const val SOCKET_TIMEOUT_MS = 5_000

        /** TLS reconnect backoff: doubles on each failure, resets on success. */
        private const val TLS_BACKOFF_MIN_MS = 5_000L
        private const val TLS_BACKOFF_MAX_MS = 5 * 60 * 1000L

        /**
         * Exponential backoff for REGISTER, shared by every SipClient.
         *
         * It lives in the companion rather than on the instance because a run
         * of failures ends in onConnectionLost() → GatewayService.reconnect(),
         * which discards the SipClient and builds a new one.  With per-instance
         * state the replacement restarted at the shortest delay, so an
         * unreachable server produced a steady REGISTER flood — three packets
         * per attempt, a fresh attempt every ~10s, a full reconnect every ~2
         * minutes — until the server's fail2ban banned the gateway's IP.
         * Keeping the schedule here means a new client picks the cooldown up
         * where the old one left off.
         */
        private object RegisterBackoff {
            private const val MIN_MS = 15_000L

            /** Ceiling on the retry interval.
             *
             *  Half an hour was too cautious in the other direction: a gateway
             *  that loses its registration would sit unreachable for up to
             *  30 minutes, and someone has to notice and re-register by hand.
             *  Five minutes recovers on its own within a few attempts while
             *  still being about a dozen REGISTERs an hour at worst, which is
             *  well under anything that looks like abuse. */
            private const val MAX_MS = 5 * 60 * 1000L

            private var target = ""
            private var delayMs = MIN_MS
            private var nextAttemptAt = 0L

            /** Millis still to wait before a REGISTER may be sent (0 = now). */
            @Synchronized fun holdOffMs(server: String): Long {
                if (server != target) {
                    // Different server (or first use) — start clean.
                    target = server
                    delayMs = MIN_MS
                    nextAttemptAt = 0L
                }
                return (nextAttemptAt - System.currentTimeMillis()).coerceAtLeast(0L)
            }

            /** Clear the cooldown for a deliberate, user-initiated retry.
             *  Backing off exists to stop the gateway hammering a server on
             *  its own; someone pressing "retry" is not that. */
            @Synchronized fun reset() {
                delayMs = MIN_MS
                nextAttemptAt = 0L
            }

            @Synchronized fun onSuccess() {
                delayMs = MIN_MS
                nextAttemptAt = 0L
            }

            /** Doubles the delay (capped) and returns the wait just applied.
             *  Jittered so several gateways on one server don't retry in step. */
            @Synchronized fun onFailure(): Long {
                val wait = (delayMs * (0.8 + Random.nextDouble() * 0.4)).toLong()
                nextAttemptAt = System.currentTimeMillis() + wait
                delayMs = (delayMs * 2).coerceAtMost(MAX_MS)
                return wait
            }
        }
    }
}
