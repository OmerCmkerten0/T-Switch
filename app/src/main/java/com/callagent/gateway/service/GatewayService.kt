package com.callagent.gateway.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.graphics.drawable.Icon
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.telephony.TelephonyManager
import com.callagent.gateway.sms.OutboundSms
import com.callagent.gateway.sms.PendingSms
import com.callagent.gateway.sms.SmsOutbox
import com.callagent.gateway.sms.SmsSender
import com.callagent.gateway.sms.SmsStore
import android.util.Log
import com.callagent.gateway.BuildConfig
import com.callagent.gateway.GatewayApp
import com.callagent.gateway.MainActivity
import com.callagent.gateway.R
import com.callagent.gateway.RootShell
import com.callagent.gateway.bridge.CallOrchestrator
import com.callagent.gateway.gsm.GsmCallManager
import com.callagent.gateway.localsend.CommandAuthorization
import com.callagent.gateway.localsend.HotspotHelper
import com.callagent.gateway.localsend.InboundMessageAction
import com.callagent.gateway.localsend.LocalIpPinController
import com.callagent.gateway.localsend.LocalSendController
import com.callagent.gateway.localsend.PushWakeController
import com.callagent.gateway.localsend.RoutingDecision
import com.callagent.gateway.localsend.RoutingMode
import com.callagent.gateway.localsend.RoutingPolicy
import com.callagent.gateway.net.StunClient
import com.callagent.gateway.sip.SipClient
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Foreground service: keeps the SIP client registered 24/7.
 *
 * Holds a WiFi lock and wake lock to prevent the device from
 * sleeping and dropping the SIP registration.
 */
class GatewayService : Service() {

    private var sipClient: SipClient? = null
    @Volatile private var cloudSipClient: SipClient? = null
    @Volatile private var cloudStartAttemptAtMs: Long = 0L
    private val cloudRecoveryLock = Any()
    private var orchestrator: CallOrchestrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /** Saved config for reconnect */
    private var cfgServer = ""
    private var cfgPort = 5060
    private var cfgUser = ""
    private var cfgPass = ""
    private var currentLocalIp = ""
    /** Underlying interface IP before any /32 hotspot alias pin, for change detection. */
    private var currentNetworkIp = ""
    @Volatile private var activeRoutingDecision: RoutingDecision =
        RoutingPolicy.resolve(null, 5060, false)

    // ── Call tracking ───────────────────────────────────
    private var onlineSince = 0L
    private var incomingCalls = 0
    private var incomingDurationSec = 0L
    private var outgoingCalls = 0
    private var outgoingDurationSec = 0L
    private var currentCallStart = 0L
    private var currentCallIncoming = true
    private var currentCallNumber = ""

    /** When the call first appeared, bridged or not.
     *
     *  currentCallStart only becomes non-zero once the bridge reaches BRIDGED,
     *  so every attempt that failed before that — an inbound call the SIP side
     *  rejected, an outbound number that never connected, a caller who hung up
     *  while it was ringing — used to leave no trace in the log at all.  Those
     *  are the calls most worth having a record of. */
    private var currentAttemptStart = 0L

    /** Prevents concurrent startGateway / reconnect threads */
    private val initializing = AtomicBoolean(false)

    /**
     * Bumped on every bring-up.  A SIP init is slow — Magisk `su` can take
     * seconds, STUN can take seconds more — and the stale-init recovery
     * releases [initializing] after 90s whether or not that thread has
     * finished.  Without a generation the late thread published its own
     * SipClient over the newer one and the older client was never stopped:
     * it kept its socket on :5060 (SO_REUSEADDR lets several bind), kept its
     * monitor loop, and every REGISTER it sent timed out because the kernel
     * delivered the response to one socket only.  Each timeout escalated the
     * process-wide REGISTER backoff, so a healthy client ended up held off
     * for five minutes at a time.  The device had four live SipClients.
     */
    private val initGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    /** When [initializing] was last set, so a stuck flag can be detected.
     *
     *  If the init thread dies or hangs, this flag stays true forever, and then
     *  nothing can ever bring the gateway back: reconnect()'s compareAndSet
     *  always fails and startGateway()'s guard always skips.  The gateway sits
     *  silent — no REGISTER at all — until someone restarts the app by hand. */
    @Volatile private var initializingSince = 0L

    /** Whether the speaker monitor is on, for the notification's action label. */
    @Volatile private var monitorOn = false

    /** Whether the agent is muted towards the caller. */
    @Volatile private var agentMuted = false

    // ── Network change detection ────────────────────────
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** Last transport we logged, so the constant capability churn does not
     *  fill the log with identical lines. */
    private var lastTransport = ""

    /**
     * Describe the network actually carrying our traffic — "WiFi", "LTE",
     * "5G" — and log it when it changes.  A WiFi drop that hands over to
     * cellular, or an LTE re-attach, is exactly the kind of event that
     * explains a re-REGISTER after the fact, and none of it was visible:
     * transport changes only ever reached logcat.
     */
    private fun logTransportIfChanged() {
        val desc = try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val active = cm.activeNetwork
            val caps = active?.let { cm.getNetworkCapabilities(it) }
            when {
                caps == null -> "none"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> mobileGeneration()
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "other"
            }
        } catch (e: Exception) {
            "unknown (${e.message})"
        }
        if (desc == lastTransport) return
        val previous = lastTransport
        lastTransport = desc
        // First observation is the baseline, not a transition.
        if (previous.isEmpty()) broadcastLog("NET: on $desc")
        else broadcastLog("NET: $previous → $desc")
    }

    /** LTE / 5G / 3G for the data connection, mirroring the home view's label. */
    private fun mobileGeneration(): String = try {
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        when (tm.dataNetworkType) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G"
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
            else -> "Mobile"
        }
    } catch (_: SecurityException) {
        "Mobile"
    } catch (_: Exception) {
        "Mobile"
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "Network available")
                logTransportIfChanged()
                checkNetworkChanged()
            }
            override fun onLost(network: Network) {
                Log.i(TAG, "Network lost")
                // During an active GSM call, cellular data goes SUSPENDED which
                // fires onLost.  This is normal Android behavior — do NOT tear
                // down the bridge.  WiFi still carries SIP/RTP traffic.
                val busy = orchestrator?.bridgeState?.let {
                    it != CallOrchestrator.BridgeState.IDLE
                } ?: false
                if (busy) {
                    Log.i(TAG, "Skipping reconnect — call in progress (${orchestrator?.bridgeState})")
                    broadcastLog("NET: lost (ignored — call active)")
                    return
                }
                logTransportIfChanged()
                // Don't reconnect on the strength of onLost alone.  This
                // fires whenever any network goes away — cellular settling
                // after boot, mobile data dropping while WiFi carries the
                // registration perfectly well — and each one rebuilt the
                // socket and sent a fresh REGISTER for nothing.
                // checkNetworkChanged() reconnects only if the local IP
                // actually moved or the registration is genuinely gone.
                checkNetworkChanged()
                scheduleScreenOffRescue()
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                logTransportIfChanged()
                checkNetworkChanged()
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, cb)
        networkCallback = cb
    }

    private val rescueHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var rescueScheduled = false

    /**
     * Wake the screen briefly when the gateway has been left with no network
     * while it is off.  Some phones never search for Wi-Fi with the screen
     * off: Android 9 leaves that to hardware network offload (PNO), which
     * older chips such as the S4 Mini's do not have.  It dropped Wi-Fi at
     * 22:59 and sat offline for 22 minutes, until someone picked it up; every
     * call in between was missed.  Turning the screen on for a moment is
     * what makes the framework scan and reconnect.  Repeats every minute
     * while the network stays gone.
     */
    private fun scheduleScreenOffRescue() {
        if (rescueScheduled) return
        // In Kiosk mode, keep the physical IPS TFT screen strictly OFF.
        if (com.callagent.gateway.localsend.KioskController.isKioskEnabled(this)) return
        rescueScheduled = true
        rescueHandler.postDelayed({
            rescueScheduled = false
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val online = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            if (online) { rescueFirst = true; return@postDelayed }
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isInteractive) {
                broadcastLog("NET: no network with the screen off — waking it to rescan")
                @Suppress("DEPRECATION")
                pm.newWakeLock(
                    PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "gateway:rescan"
                ).acquire(15_000)
            }
            scheduleScreenOffRescue()
        }, if (rescueFirst) 20_000L else 60_000L)
        rescueFirst = false
    }
    private var rescueFirst = true

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        networkCallback = null
    }

    private val networkCheckRunning = AtomicBoolean(false)
    private val contactResolver = com.callagent.gateway.localsend.ContactCommandResolver()
    @Volatile private var cloudWelcomeSent = false

    private fun resolveCurrentRouting(
        prefs: android.content.SharedPreferences,
        rawServer: String? = prefs.getString("server", ""),
        port: Int = prefs.getInt("port", 5060)
    ): RoutingDecision {
        val cleaned = rawServer?.trim().orEmpty().let { if (it == "0.0.0.0") "" else it }
        val tls = prefs.getBoolean("sip_tls", false)
        val pinnedIp = prefs.getString("pinned_local_ip", LocalIpPinController.DEFAULT_PINNED_IP)
        val localSipEnabled = prefs.getBoolean("local_sip_mode_enabled", false)
        return RoutingPolicy.resolve(
            customSipServer = cleaned,
            customSipPort = port,
            customSipTls = tls,
            pinnedLocalIp = pinnedIp,
            localSipModeEnabled = localSipEnabled
        )
    }

    private fun stopSipClients() {
        orchestrator?.stop()
        sipClient?.stop()
        synchronized(cloudRecoveryLock) {
            cloudSipClient?.stop()
            cloudSipClient = null
            cloudStartAttemptAtMs = 0L
        }
        orchestrator = null
        sipClient = null
    }

    private fun ensureForeground(
        state: NotifState = notifState,
        statusText: String = notifStatusText
    ) {
        val notification = buildNotification(state, statusText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                startForeground(
                    activeNotificationId(),
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            }.onFailure {
                startForeground(activeNotificationId(), notification)
            }
        } else {
            startForeground(activeNotificationId(), notification)
        }
    }

    private fun checkNetworkChanged() {
        // Skip if no prior IP (first start handles its own init)
        if (currentLocalIp.isEmpty()) return
        val prefs = getSharedPreferences("gateway", MODE_PRIVATE)
        val decision = resolveCurrentRouting(prefs, cfgServer, cfgPort)
        val isHybrid = decision.mode == RoutingMode.HYBRID_LOCAL_IP
        if (!isHybrid && decision.customServer.isEmpty()) return
        // During an active call, skip network checks — cellular SUSPENDED
        // is normal and WiFi handles SIP/RTP traffic.
        val busy = orchestrator?.bridgeState?.let {
            it != CallOrchestrator.BridgeState.IDLE
        } ?: false
        if (busy) return
        if (!networkCheckRunning.compareAndSet(false, true)) return
        thread(name = "net-check", isDaemon = true) {
            try {
                val newIp = if (isHybrid) {
                    val hs = HotspotHelper.detectHotspotFast(this)
                    when {
                        hs.active && hs.ipAddress != "0.0.0.0" -> hs.ipAddress
                        hs.wifiIpAddress.isNotEmpty() -> hs.wifiIpAddress
                        else -> getLocalIp()
                    }
                } else {
                    getLocalIp()
                }
                if (newIp != "0.0.0.0") {
                    val baselineIp = currentNetworkIp.ifEmpty { currentLocalIp }
                    val ipChanged = newIp != baselineIp
                    val notRegistered = sipClient?.registered != true
                    if (ipChanged || notRegistered) {
                        if (ipChanged) {
                            broadcastLog("NET: IP changed $baselineIp → $newIp, reconnecting")
                            com.callagent.gateway.localsend.LocalMdnsResponder.announceNow(this)
                        } else {
                            broadcastLog("NET: registration lost, reconnecting")
                        }
                        reconnect()
                        return@thread
                    }
                }
                recoverHybridCloudSipIfNeeded()
            } finally {
                networkCheckRunning.set(false)
            }
        }
    }

    private fun recoverHybridCloudSipIfNeeded() {
        if (stopped || initializing.get()) return
        val localSip = sipClient ?: return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork
        val hasInternet = net?.let { cm.getNetworkCapabilities(it) }
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val pushConfigured = PushWakeController.isConfigured(this)

        synchronized(cloudRecoveryLock) {
            if (stopped || initializing.get() || sipClient !== localSip) return
            val existing = cloudSipClient
            val action = decideCloudSipRecovery(
                routingMode = activeRoutingDecision.mode,
                pushConfigured = pushConfigured,
                hasInternet = hasInternet,
                hasPrimaryLocalClient = true,
                cloudExists = existing != null,
                cloudRunning = existing?.isRunning == true,
                cloudRegistered = existing?.registered == true,
                nowMs = System.currentTimeMillis(),
                lastAttemptMs = cloudStartAttemptAtMs
            )
            when (action) {
                CloudRecoveryAction.NONE -> return
                CloudRecoveryAction.RESTART_STALE -> {
                    broadcastLog("CLOUD-SIP: restarting unregistered cloud companion on active internet")
                    runCatching { existing?.stop() }
                    if (cloudSipClient === existing) {
                        cloudSipClient = null
                    }
                }
                CloudRecoveryAction.START_NEW -> {
                    broadcastLog("CLOUD-SIP: internet active — starting cloud companion")
                }
            }
            val fallbackIp = getLocalIp().takeIf { it != "0.0.0.0" } ?: currentLocalIp.ifEmpty { "0.0.0.0" }
            startHybridCloudCompanion(
                localFallbackIp = fallbackIp,
                activeNetwork = net,
                expectedLocalSip = localSip,
                gen = initGeneration.get()
            )
        }
    }

    private fun startHybridCloudCompanion(
        localFallbackIp: String,
        activeNetwork: Network?,
        expectedLocalSip: SipClient,
        gen: Int
    ) {
        if (!PushWakeController.isConfigured(this)) return
        val cloudDomain = PushWakeController.getDomain(this)
        val cloudUser = PushWakeController.getPusherUser(this)
        val cloudPass = PushWakeController.getPassword(this)
        val cloudLocalIp = getLocalIp().takeIf { it != "0.0.0.0" } ?: localFallbackIp
        val cloud = SipClient(
            username = cloudUser,
            password = cloudPass,
            serverDomain = cloudDomain,
            serverPort = 5061,
            localIp = cloudLocalIp,
            localPort = 5063,
            network = activeNetwork,
            publicIp = cloudLocalIp,
            useTls = true,
            srtpRequested = false,
            localSendMode = false
        )
        cloud.logListener = { msg -> broadcastLog("CLOUD-SIP: $msg") }
        cloud.onSmsRequestWithPeer = { m, peer ->
            onSmsSendRequest(m, isLocalTransport = false, sourcePeer = peer)
        }
        cloud.onSmsRequest = { m ->
            onSmsSendRequest(m, isLocalTransport = false, sourcePeer = null)
        }
        cloud.listener = object : SipClient.Listener {
            override fun onRegistered() {
                broadcastLog("CLOUD-SIP: registered ($cloudUser@$cloudDomain:5061)")
                flushSmsQueue("cloud-registered")
            }
            override fun onRegistrationFailed() {}
            override fun onIncomingCall(call: com.callagent.gateway.sip.SipCall) {
                call.reject(486, "Busy Here")
            }
            override fun onCallTerminated(call: com.callagent.gateway.sip.SipCall) {}
        }
        synchronized(cloudRecoveryLock) {
            if (gen != initGeneration.get() || sipClient !== expectedLocalSip || stopped) {
                return
            }
            cloudStartAttemptAtMs = System.currentTimeMillis()
            cloudSipClient = cloud
            runCatching { cloud.start() }
                .onFailure { e ->
                    Log.w(TAG, "Cloud companion SIP start failed: ${e.message}")
                    broadcastLog("CLOUD-SIP: start deferred (${e.message})")
                    runCatching { cloud.stop() }
                    if (cloudSipClient === cloud) {
                        cloudSipClient = null
                    }
                }
        }
    }

    private fun reconnect() {
        val prefs = getSharedPreferences("gateway", MODE_PRIVATE)
        val decision = resolveCurrentRouting(prefs, cfgServer, cfgPort)
        val isHybrid = decision.mode == RoutingMode.HYBRID_LOCAL_IP
        if (stopped || (!isHybrid && decision.customServer.isEmpty())) return
        clearStaleInitializing()
        if (!initializing.compareAndSet(false, true)) {
            Log.i(TAG, "Reconnect skipped — already initializing")
            return
        }
        initializingSince = System.currentTimeMillis()
        onlineSince = 0L
        broadcastLog("SIP: reconnecting")
        broadcastStatus("STARTING", "Reconnecting...")
        updateNotification(NotifState.WARN, "Connecting")

        // Tear down existing clients
        stopSipClients()

        val gen = initGeneration.incrementAndGet()
        thread(name = "gateway-reconnect") {
            try {
                initSipClient(gen)
            } finally {
                initializing.set(false)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerNetworkCallback()
        RootShell.init()
        LocalSendController.attach(this, { sipClient }, { broadcastLog(it) }, { cloudSipClient })
        com.callagent.gateway.localsend.LocalMdnsResponder.start(this)
        thread(name = "notif-setup") {
            applyNotificationVisibility()
            silenceDefaultSmsApp()
        }
        Log.i(TAG, "GatewayService created")
    }

    /**
     * Report whether the gateway still holds the default-dialer role.
     *
     * Losing it is silent and total: Telecom stops binding GsmCallService, so
     * no incoming GSM call is ever seen, while SIP stays registered and the
     * app looks perfectly healthy.  Checked at every bring-up so the log
     * carries the answer without anyone having to go looking for it.
     */
    private fun checkDefaultDialer(): Boolean {
        return try {
            val tm = getSystemService(Context.TELECOM_SERVICE) as android.telecom.TelecomManager
            val holder = tm.defaultDialerPackage
            val held = holder == packageName
            if (held) {
                broadcastLog("Default dialer: yes")
            } else {
                broadcastLog(
                    "WARNING: not the default dialer (${holder ?: "none"}) — " +
                        "incoming GSM calls will not reach the gateway"
                )
            }
            held
        } catch (e: Exception) {
            broadcastLog("Default dialer check failed: ${e.message}")
            false
        }
    }

    /**
     * Report whether incoming SMS can reach us at all.
     *
     * Without RECEIVE_SMS the broadcast is simply never delivered — no error,
     * no receiver call, nothing to notice — so the gateway would forward calls
     * perfectly while quietly dropping every message.
     */
    private fun checkSmsPermission() {
        // Self-heal with root, the way RECORD_AUDIO's appop is forced.  The
        // Magisk module's grant loop runs before PackageManager is up, so a
        // newly added permission never takes there — and the failure is
        // invisible: incoming SMS is simply never delivered, outgoing is
        // refused.
        if (!hasReceiveSms()) grantViaRoot("android.permission.RECEIVE_SMS")
        if (!hasSendSms()) grantViaRoot("android.permission.SEND_SMS")

        val queued = SmsStore.pending(this).size
        if (hasReceiveSms()) {
            broadcastLog("SMS receive: ready${if (queued > 0) " ($queued queued)" else ""}")
        } else {
            broadcastLog("WARNING: RECEIVE_SMS not granted — incoming SMS will be dropped")
        }
        if (hasSendSms()) {
            broadcastLog("SMS send: ready")
        } else {
            broadcastLog("WARNING: SEND_SMS not granted — send requests will be refused")
        }
    }

    private fun grantViaRoot(permission: String) {
        broadcastLog("$permission not granted — granting via root")
        try {
            RootShell.exec("pm grant $packageName $permission", 8000)
        } catch (e: Exception) {
            Log.w(TAG, "pm grant $permission failed: ${e.message}")
        }
    }

    private fun hasReceiveSms(): Boolean =
        checkSelfPermission(android.Manifest.permission.RECEIVE_SMS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Release the init flag if it has been held implausibly long. */
    private fun clearStaleInitializing() {
        if (initializing.get() &&
            System.currentTimeMillis() - initializingSince > INIT_STALE_MS
        ) {
            val heldFor = (System.currentTimeMillis() - initializingSince) / 1000
            Log.w(TAG, "init flag held ${heldFor}s — treating as stale and clearing")
            broadcastLog("Recovering from stuck initialisation (${heldFor}s)")
            initializing.set(false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always satisfy Android O+ startForegroundService() contract before
        // any action branch (including ACTION_MONITOR or early stopSelf()).
        val initialText = if (sipClient == null && !initializing.get()) "Başlatılıyor…" else notifStatusText
        val initialState = if (sipClient == null && !initializing.get()) NotifState.WARN else notifState
        ensureForeground(initialState, initialText)
        clearStaleInitializing()
        when (intent?.action) {
            ACTION_START -> startGateway(intent)
            ACTION_STOP -> stopGateway()
            ACTION_RELOAD_STATS -> reloadStats()
            ACTION_STATUS -> broadcastCurrentStatus()
            ACTION_RECONNECT -> {
                // Tapping the offline pill should act immediately.  If there is
                // a client, ask it to register now; if there is not, the
                // gateway is down and needs bringing up.
                val sip = sipClient
                broadcastStatus("STARTING", "Retrying…")
                if (sip != null && !stopped) {
                    sip.retryNow()
                } else {
                    // No client — a previous reconnect nulled it and never
                    // finished.  Force a fresh one rather than letting the
                    // init guard swallow the request.
                    initializing.set(false)
                    stopped = false
                    reconnect()
                }
            }
            ACTION_APPLY_CONFIG -> applyConfigChange()
            ACTION_SMS_SEND -> {
                dispatchOutbox()
            }
            ACTION_SMS_REPORT -> {
                reportOutbox(intent.getStringExtra(EXTRA_SMS_ID))
            }
            ACTION_SMS_FLUSH -> {
                flushSmsQueue("received")
            }
            ACTION_DIAL -> dialFromDialler(intent)
            ACTION_MUTE_AGENT -> {
                agentMuted = if (intent.hasExtra(EXTRA_MUTE_ON)) {
                    intent.getBooleanExtra(EXTRA_MUTE_ON, false)
                } else {
                    !agentMuted
                }
                orchestrator?.setAgentMuted(agentMuted)
                broadcastStatus(
                    orchestrator?.bridgeState?.name ?: "IDLE",
                    if (agentMuted) "Agent muted" else "Agent unmuted"
                )
            }
            ACTION_MONITOR -> {
                // No extra means "toggle", which is what the notification
                // action sends; the in-call screen passes an explicit value.
                // Toggle from the call's real state, not a copy kept here:
                // Call monitor switches Snoop on without going through this.
                monitorOn = if (intent.hasExtra(EXTRA_MONITOR_ON)) {
                    intent.getBooleanExtra(EXTRA_MONITOR_ON, false)
                } else {
                    !(orchestrator?.isMonitoring ?: monitorOn)
                }
                orchestrator?.setMonitorEnabled(monitorOn)
                updateNotification(NotifState.OK)
            }
            ACTION_SEND_TERMINAL_WELCOME -> {
                thread(name = "terminal-welcome") {
                    sipClient?.resolveLocalPeerAddress()
                    val ok = LocalSendController.sendWelcomeIfNeeded(this, force = true)
                    if (!ok) {
                        broadcastLog("TERMINAL: Zoiper istemcisi bulunamadı — Hotspot ve Zoiper bağlantısını kontrol edin")
                    }
                }
            }
            else -> startGateway(intent)
        }
        return START_STICKY
    }

    /**
     * Re-read the saved configuration and rebuild the SIP client with it.
     *
     * ACTION_RECONNECT deliberately only asks the *existing* client to
     * register again, which is right for the status pill but wrong for a
     * settings save: server, port, credentials and the STUN choice are all
     * read once at bring-up, so editing them and pressing SAVE changed
     * nothing until the next restart.
     */
    private fun applyConfigChange() {
        val prefs = getSharedPreferences("gateway", MODE_PRIVATE)
        // Battery protection is independent of SIP validity, so refresh it
        // before checking whether the SIP fields are complete.
        // Re-post first, so toggling the status bar setting takes effect now
        // rather than at the next restart -- the channel is chosen when the
        // notification is built.  Done before the validity check below, since
        // the setting is independent of whether SIP is configured.
        // Remove before re-posting.  A notification's channel is fixed when it
        // is first posted: re-posting the same id on a different channel is
        // silently ignored, so toggling the status bar setting appeared to do
        // nothing until the service happened to restart.  Measured both ways
        // -- quiet to normal and back -- and neither moved without this.
        runCatching {
            ensureForeground(notifState, notifStatusText)
            cancelStaleNotification()
        }.onFailure { Log.w(TAG, "Could not re-post notification: ${it.message}") }
        thread(name = "notif-visibility") {
            applyNotificationVisibility()
            silenceDefaultSmsApp()
        }

        com.callagent.gateway.sip.SipBuilder.codecMode =
            prefs.getString("codec", "g722") ?: "g722"
        com.callagent.gateway.gsm.GsmCallManager.agentVolumeStep =
            prefs.getInt("agent_vol_step", 0)

        val rawServer = prefs.getString("server", "").orEmpty().trim()
        val savedPort = resolveEffectivePort(intentPort = -1, savedPort = prefs.getInt("port", 5060))
        val decision = resolveCurrentRouting(prefs, rawServer, savedPort)
        val isHybrid = decision.mode == RoutingMode.HYBRID_LOCAL_IP
        val isCloudOnly = decision.mode == RoutingMode.CLOUD_ONLY_LINPHONE
        prefs.edit().putBoolean("local_send_enabled", isHybrid).apply()

        activeRoutingDecision = decision
        if (isCloudOnly) {
            cfgServer = PushWakeController.getDomain(this)
            cfgPort = 5061
            cfgUser = PushWakeController.getPusherUser(this)
            cfgPass = PushWakeController.getPassword(this)
        } else {
            cfgServer = decision.customServer
            cfgPort = decision.customPort
            cfgUser = prefs.getString("user", "").orEmpty().trim()
            cfgPass = prefs.getString("pass", "").orEmpty()
        }
        if (isHybrid) {
            if (cfgUser.isEmpty()) cfgUser = "tswitch"
        } else if (!isCloudOnly && (cfgServer.isEmpty() || cfgUser.isEmpty())) {
            broadcastLog("ERROR: Missing server or username")
            broadcastStatus("ERROR", "Missing SIP configuration")
            return
        }
        broadcastLog("Config changed (mode=${decision.mode}, pinnedIp=${decision.pinnedLocalIp}) — rebuilding SIP client")
        // A save is an explicit instruction, so it outranks a bring-up that
        // is already in flight; the generation counter makes discarding that
        // one safe.
        stopped = false
        initializing.set(false)
        reconnect()
    }

    // ── Received SMS → SIP ──────────────────────────────

    /** One flush at a time: arrival, registration and the retry timer can all
     *  fire at once, and sending the same message twice is worse than late. */
    private val smsFlushing = AtomicBoolean(false)
    @Volatile private var smsRetryScheduled = false

    /**
     * Hand every queued SMS to the server over the registration that is
     * already up, oldest first.
     *
     * A message leaves the queue only on a 2xx.  Anything else — no response,
     * a 4xx, SIP not registered — leaves it on disk for the next attempt,
     * because the broadcast that delivered it is not repeatable.
     */
    private fun flushSmsQueue(reason: String) {
        if (!smsFlushing.compareAndSet(false, true)) return
        thread(name = "sms-flush") {
            try {
                val queue = SmsStore.pending(this)
                if (queue.isEmpty()) return@thread
                val sip = sipClient
                val cloud = cloudSipClient
                val canDeliver = (sip != null && sip.registered) || (cloud != null && cloud.registered)
                if (!canDeliver) {
                    broadcastLog("SMS: ${queue.size} queued, waiting for registration")
                    scheduleSmsRetry()
                    return@thread
                }
                broadcastLog("SMS: forwarding ${queue.size} message(s) [$reason]")
                // The default SMS app keeps its own copy and shows it unread.
                // Nobody reads this screen, so an unread badge just
                // accumulates for ever.  Done here rather than in the
                // receiver: the default app writes its row when it handles
                // SMS_DELIVER, which may not have happened yet at that point.
                markInboxRead()
                var failed = false
                for (sms in queue) {
                    val code = sendSmsOverSip(sip, sms)
                    if (code == 200 || code == 202) {
                        SmsStore.remove(this, sms.id)
                        broadcastLog("SMS: ${sms.id} from ${sms.from} accepted ($code)")
                    } else {
                        SmsStore.markAttempt(this, sms.id)
                        broadcastLog(
                            "SMS: ${sms.id} from ${sms.from} not accepted " +
                                "(${if (code == 0) "no response" else code.toString()}) — queued"
                        )
                        failed = true
                        break   // keep order; a later one is no more likely to land
                    }
                }
                if (failed) scheduleSmsRetry()
            } catch (e: Exception) {
                Log.e(TAG, "SMS flush failed: ${e.message}", e)
                scheduleSmsRetry()
            } finally {
                smsFlushing.set(false)
            }
        }
    }

    private fun scheduleSmsRetry() {
        if (smsRetryScheduled) return
        smsRetryScheduled = true
        thread(name = "sms-retry") {
            try {
                Thread.sleep(SMS_RETRY_MS)
            } catch (_: InterruptedException) {
                return@thread
            } finally {
                smsRetryScheduled = false
            }
            if (stopped) return@thread
            flushSmsQueue("retry")
            sweepOutboxReports()
        }
    }

    /**
     * The wire format, in one place so it can be read against the server's
     * dialplan.  Request-URI addresses the SIM's own number where the SIM
     * reports one, falling back to the configured own number and then to the
     * SIP account — the same routing key an inbound *call* uses, so the server
     * can map an SMS to an assistant exactly as it maps a call.
     */
    private fun sendSmsOverSip(sip: SipClient?, sms: PendingSms): Int {
        val configuredOwn = ownNumberForSub(sms.subId)
        val target = sms.to.ifEmpty { configuredOwn }.ifEmpty { cfgUser }
        val headers = mutableListOf(
            "X-SMS-Id: ${sms.id}",
            "X-SMS-From: ${sms.from}",
            "X-SMS-To: $target",
            "X-SMS-Received: ${smsTimeFormat.get()!!.format(java.util.Date(sms.receivedAt))}",
            "X-SMS-Parts: ${sms.parts}"
        )
        if (sms.subId >= 0) headers += "X-SMS-Sim-Sub: ${sms.subId}"
        if (sms.slot >= 0) headers += "X-SMS-Sim-Slot: ${sms.slot}"
        if (sms.carrier.isNotEmpty()) headers += "X-SMS-Sim-Carrier: ${sms.carrier}"
        if (sms.attempts > 0) headers += "X-SMS-Attempt: ${sms.attempts + 1}"

        if (activeRoutingDecision.mode == RoutingMode.CLOUD_ONLY_LINPHONE) {
            if (sip == null || !sip.registered) return 0
            val receiverUser = PushWakeController.getReceiverUser(this)
            val cloudDomain = PushWakeController.getDomain(this)
            val pusherUser = PushWakeController.getPusherUser(this)
            val cloudCode = sip.sendSipMessage(
                targetUri = "sip:$receiverUser@$cloudDomain",
                fromUser = pusherUser,
                fromDisplayName = sms.from.ifEmpty { "SMS" },
                body = "📩 [${sms.from}]\n${sms.text}",
                extraHeaders = headers
            )
            if (cloudCode in 200..299) {
                broadcastLog("SMS: ${sms.id} delivered via Linphone cloud ($receiverUser@$cloudDomain)")
            }
            return cloudCode
        }

        if (activeRoutingDecision.mode == RoutingMode.HYBRID_LOCAL_IP || sip?.localSendMode == true) {
            var localCode = 0
            if (sip != null && sip.isLocalPeerFresh()) {
                val peer = sip.registeredPeer ?: sip.resolveLocalPeerAddress(this)
                if (peer != null) {
                    val peerUser = sip.registeredPeerUser.ifEmpty { target }
                    localCode = sip.sendSipMessageToAddress(
                        targetAddress = peer,
                        targetUri = "sip:$peerUser@${peer.first}:${peer.second}",
                        fromUser = sms.from.ifEmpty { "unknown" },
                        body = sms.text,
                        extraHeaders = headers
                    )
                    if (localCode in 200..299) {
                        return localCode
                    }
                }
            }
            val cloud = cloudSipClient
            if (cloud != null && cloud.registered) {
                val receiverUser = PushWakeController.getReceiverUser(this)
                val cloudDomain = PushWakeController.getDomain(this)
                val pusherUser = PushWakeController.getPusherUser(this)
                val cloudCode = cloud.sendSipMessage(
                    targetUri = "sip:$receiverUser@$cloudDomain",
                    fromUser = pusherUser,
                    fromDisplayName = sms.from.ifEmpty { "SMS" },
                    body = "📩 [${sms.from}]\n${sms.text}",
                    extraHeaders = headers
                )
                if (cloudCode in 200..299) {
                    broadcastLog("SMS: ${sms.id} delivered via cloud fallback ($receiverUser@$cloudDomain)")
                }
                return cloudCode
            }
            return localCode
        }

        if (sip == null) return 0
        val targetUri = "sip:$target@$cfgServer"
        return sip.sendSipMessage(
            targetUri = targetUri,
            fromUser = sms.from.ifEmpty { "unknown" },
            body = sms.text,
            extraHeaders = headers
        )
    }

    private fun extractSipDomain(headerValue: String?): String? {
        if (headerValue.isNullOrBlank()) return null
        val sipIdx = headerValue.indexOf("sips:", ignoreCase = true)
            .takeIf { it >= 0 }
            ?: headerValue.indexOf("sip:", ignoreCase = true)
        val searchFrom = if (sipIdx >= 0) sipIdx + 4 else 0
        val atIdx = headerValue.indexOf('@', searchFrom)
        if (atIdx < 0) return null
        return headerValue.substring(atIdx + 1)
            .substringBefore('>')
            .substringBefore(';')
            .substringBefore(':')
            .substringBefore('?')
            .trim()
            .ifEmpty { null }
    }

    // ── SIP → SMS ───────────────────────────────────────

    /**
     * A MESSAGE from the server or LAN peer asking us to execute a command,
     * initiate a callback, or send an outbound GSM SMS.
     *
     * Runs on the SIP receive thread, so it does nothing slow: the request is
     * authorized via [CommandAuthorization.classify], validated, written to the
     * outbox and answered.
     */
    fun onSmsSendRequest(
        msg: com.callagent.gateway.sip.SipMessage,
        isLocalTransport: Boolean = sipClient?.localSendMode == true,
        sourcePeer: Pair<String, Int>? = null
    ): Pair<Int, List<String>> {
        val type = msg.contentType?.lowercase().orEmpty()
        val decodedBody = when (val d = CommandAuthorization.decodeMessageBody(msg.contentType, msg.body)) {
            is CommandAuthorization.DecodedMessageBody.Text -> d.text
            is CommandAuthorization.DecodedMessageBody.IgnoreControl -> return 200 to emptyList()
            is CommandAuthorization.DecodedMessageBody.Unsupported -> {
                broadcastLog("SMS send refused: unsupported Content-Type '${d.contentType}'")
                return 415 to emptyList()
            }
        }
        val target = (msg.header("x-sms-to")
            ?: msg.requestUri?.let { msg.extractUser(it) }
            ?: msg.to?.let { msg.extractUser(it) })
            ?.trim().orEmpty()
        val fromUser = msg.from?.let { msg.extractUser(it) }
        val fromDomain = extractSipDomain(msg.from)
        broadcastLog("SIP MESSAGE: from=$fromUser@$fromDomain to=$target ct='${type.ifEmpty { "text/plain" }}' local=$isLocalTransport")
        val pairedReceiverUser = PushWakeController.getReceiverUser(this)
        val allowedCloudDomains = setOfNotNull(
            PushWakeController.getDomain(this).takeIf { it.isNotBlank() },
            cfgServer.takeIf { it.isNotBlank() && it != "0.0.0.0" }
        )

        val action = CommandAuthorization.classify(
            isLocalTransport = isLocalTransport,
            target = target,
            fromUser = fromUser,
            fromDomain = fromDomain,
            sourcePeer = sourcePeer,
            registeredLocalPeer = sipClient?.registeredPeer,
            pairedReceiverUser = pairedReceiverUser,
            allowedCloudDomains = allowedCloudDomains,
            gatewayUser = cfgUser,
            body = decodedBody
        )

        return when (action) {
            is InboundMessageAction.CallbackCall -> {
                contactResolver.clearPending()
                triggerCallbackFromMessage(action.destinationNumber, null, preferCloud = !isLocalTransport)
            }
            is InboundMessageAction.ContactCallQuery -> {
                resolveContactActionAsync(
                    query = action.nameQuery,
                    smsText = null,
                    msg = msg,
                    preferCloud = !isLocalTransport
                )
                200 to emptyList()
            }
            is InboundMessageAction.OutboundSmsCommand -> {
                contactResolver.clearPending()
                enqueueOutboundGsmSms(msg, action.destinationNumber, action.text)
            }
            is InboundMessageAction.ContactSmsQuery -> {
                resolveContactActionAsync(
                    query = action.nameQuery,
                    smsText = action.text,
                    msg = msg,
                    preferCloud = !isLocalTransport
                )
                200 to emptyList()
            }
            is InboundMessageAction.TerminalCommand -> {
                if (contactResolver.hasPending()) {
                    when (val pendingRes = contactResolver.handlePendingReply(action.commandText)) {
                        is com.callagent.gateway.localsend.ContactResolution.ExecuteCall -> {
                            triggerCallbackFromMessage(
                                pendingRes.contact.number,
                                pendingRes.contact.name,
                                preferCloud = !isLocalTransport
                            )
                            return 200 to emptyList()
                        }
                        is com.callagent.gateway.localsend.ContactResolution.ExecuteSms -> {
                            enqueueOutboundGsmSms(msg, pendingRes.contact.number, pendingRes.text)
                            LocalSendController.sendFakeSmsToZoiper(
                                this,
                                "✉️ SMS gönderiliyor: ${pendingRes.contact.name} (${pendingRes.contact.number})",
                                logToHistory = false,
                                preferCloud = !isLocalTransport
                            )
                            return 200 to emptyList()
                        }
                        is com.callagent.gateway.localsend.ContactResolution.Cancelled -> {
                            LocalSendController.sendFakeSmsToZoiper(
                                this,
                                pendingRes.message,
                                logToHistory = false,
                                preferCloud = !isLocalTransport
                            )
                            return 200 to emptyList()
                        }
                        else -> {
                            // Not a confirmation reply — fall through to normal terminal command
                        }
                    }
                }
                LocalSendController.handleTerminalMessage(
                    context = this,
                    targetUser = target,
                    rawBody = action.commandText,
                    preferCloud = !isLocalTransport
                )
                200 to emptyList()
            }
            is InboundMessageAction.DirectGsmSms -> {
                val isPublicCloud = !isLocalTransport && (
                    activeRoutingDecision.mode == RoutingMode.HYBRID_LOCAL_IP ||
                        activeRoutingDecision.mode == RoutingMode.CLOUD_ONLY_LINPHONE ||
                        cfgServer.contains("linphone.org", ignoreCase = true) ||
                        fromDomain?.contains("linphone.org", ignoreCase = true) == true
                    )
                if (isPublicCloud) {
                    if (!isAuthorizedCloudSmsSender(fromUser, fromDomain, pairedReceiverUser, allowedCloudDomains)) {
                        broadcastLog("SMS send refused (403): unauthorized public-cloud sender '$fromUser@$fromDomain'")
                        return 403 to emptyList()
                    }
                }
                enqueueOutboundGsmSms(msg, action.destinationNumber, action.text)
            }
            is InboundMessageAction.Reject -> {
                broadcastLog("SIP MESSAGE refused (${action.statusCode}): ${action.reason}")
                action.statusCode to emptyList()
            }
        }
    }

    private fun triggerCallbackFromMessage(
        destinationNumber: String,
        contactName: String?,
        preferCloud: Boolean
    ): Pair<Int, List<String>> {
        if (!PushWakeController.isConfigured(this)) {
            broadcastLog("CALLBACK refused (503): PushKit not configured")
            return 503 to emptyList()
        }
        val orch = orchestrator
        if (orch == null) {
            broadcastLog("CALLBACK refused (503): orchestrator not ready")
            return 503 to emptyList()
        }
        return if (orch.startCloudCallback(destinationNumber)) {
            val label = if (!contactName.isNullOrBlank()) "$contactName ($destinationNumber)" else destinationNumber
            broadcastLog("CALLBACK: started cloud callback to $label")
            LocalSendController.sendFakeSmsToZoiper(
                this,
                "📞 Aranıyor: $label — önce iPhone çalacak, açtığınızda karşı taraf aranacak.",
                logToHistory = false,
                preferCloud = preferCloud
            )
            200 to emptyList()
        } else {
            broadcastLog("CALLBACK refused (486): bridge busy ($destinationNumber)")
            LocalSendController.sendFakeSmsToZoiper(
                this,
                "⚠️ Hat şu an meşgul, arama başlatılamadı ($destinationNumber).",
                logToHistory = false,
                preferCloud = preferCloud
            )
            486 to emptyList()
        }
    }

    private fun resolveContactActionAsync(
        query: String,
        smsText: String?,
        msg: com.callagent.gateway.sip.SipMessage,
        preferCloud: Boolean
    ) {
        thread(name = "contact-resolve") {
            val contacts = loadDeviceContactsForTerminal()
            val res = if (smsText == null) {
                contactResolver.resolveCall(query, contacts)
            } else {
                contactResolver.resolveSms(query, smsText, contacts)
            }
            when (res) {
                is com.callagent.gateway.localsend.ContactResolution.ExecuteCall -> {
                    triggerCallbackFromMessage(res.contact.number, res.contact.name, preferCloud)
                }
                is com.callagent.gateway.localsend.ContactResolution.ExecuteSms -> {
                    val (code, _) = enqueueOutboundGsmSms(msg, res.contact.number, res.text)
                    val reply = if (code in 200..299) {
                        "✉️ SMS gönderiliyor: ${res.contact.name} (${res.contact.number})"
                    } else {
                        "❌ SMS gönderilemedi (HTTP/SIP $code): ${res.contact.name} (${res.contact.number})"
                    }
                    LocalSendController.sendFakeSmsToZoiper(this, reply, logToHistory = false, preferCloud = preferCloud)
                }
                is com.callagent.gateway.localsend.ContactResolution.AskConfirmation -> {
                    LocalSendController.sendFakeSmsToZoiper(
                        this,
                        res.promptMessage,
                        logToHistory = false,
                        preferCloud = preferCloud
                    )
                }
                is com.callagent.gateway.localsend.ContactResolution.NotFound -> {
                    LocalSendController.sendFakeSmsToZoiper(
                        this,
                        res.message,
                        logToHistory = false,
                        preferCloud = preferCloud
                    )
                }
                else -> {}
            }
        }
    }

    private fun loadDeviceContactsForTerminal(): List<com.callagent.gateway.localsend.ContactEntry> {
        val result = mutableListOf<com.callagent.gateway.localsend.ContactEntry>()
        try {
            val uri = android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext()) {
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty().trim() else ""
                    val num = if (numIdx >= 0) cursor.getString(numIdx).orEmpty().trim() else ""
                    if (name.isNotEmpty() && num.isNotEmpty()) {
                        result.add(com.callagent.gateway.localsend.ContactEntry(name, num))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "loadDeviceContactsForTerminal failed: ${e.message}")
        }
        return result
    }

    private fun enqueueOutboundGsmSms(
        msg: com.callagent.gateway.sip.SipMessage,
        target: String,
        rawText: String
    ): Pair<Int, List<String>> {
        // Reduce to ASCII before anything measures or stores the text, so the
        // part count, the encoding and the log all describe what actually
        // goes out rather than what the server sent.
        //
        // Only where that is possible, though: text in a script the 7-bit
        // alphabet does not have goes out as UCS-2 whatever we do, and UCS-2
        // is not what the modem mis-decodes.  Folding it would turn a Russian,
        // Hebrew or Arabic message that would have arrived intact into a row
        // of '?', so it is sent through unchanged even with the setting on.
        val foldToAscii = getSharedPreferences("gateway", MODE_PRIVATE)
            .getBoolean("translit_ascii", false)
        val folded =
            if (foldToAscii) com.callagent.gateway.sms.Transliterate.toAsciiOrNull(rawText) else null
        val text = folded ?: rawText
        if (folded != null && folded != rawText) {
            broadcastLog("SMS send: transliterated to ASCII (${rawText.length} -> ${text.length} chars)")
        } else if (foldToAscii && folded == null) {
            broadcastLog("SMS send: no ASCII spelling for this text — sending it unchanged as UCS-2")
        }
        if (target.isEmpty() || text.isEmpty()) {
            broadcastLog("SMS send refused: missing recipient or body")
            return 400 to emptyList()
        }
        if (!hasSendSms()) {
            // 503 rather than 4xx: the server should try this one again once
            // the permission is in place, not give up on it.
            broadcastLog("SMS send refused: SEND_SMS not granted")
            return 503 to emptyList()
        }

        val id = msg.header("x-sms-id")?.trim().takeUnless { it.isNullOrEmpty() }
            ?: SmsStore.newId()
        // What this message will actually cost, answered in the 202 rather
        // than after the fact: one character outside GSM-7 forces the whole
        // message to UCS-2, which halves a part from 160 characters to 70 —
        // an 88-character reply that would have been one part becomes two.
        // The sender can only act on that if it is told before it commits.
        val cost = measure(text)

        val existing = SmsOutbox.get(this, id)
        if (existing != null) {
            // The server repeated a request whose response it did not see.
            broadcastLog("SMS send: $id already accepted — not sending twice")
            return 202 to cost
        }

        val subId = resolveSubscription(
            msg.header("x-sms-sim-sub")?.trim()?.toIntOrNull(),
            msg.header("x-sms-sim-slot")?.trim()?.toIntOrNull()
        )
        val measured = measureSms(text)
        SmsOutbox.add(
            this,
            OutboundSms(
                id = id, to = target, text = text, subId = subId,
                parts = measured?.parts ?: 0,
                encoding = measured?.encoding ?: ""
            )
        )
        CallLogStore.addEntry(
            this,
            CallLogEntry(
                direction = "OUT",
                number = target,
                timestamp = System.currentTimeMillis(),
                durationSec = 0,
                type = CallLogStore.TYPE_SMS,
                text = text,
                smsId = id,
                encoding = measured?.encoding ?: "",
                parts = measured?.parts ?: 0,
                status = "pending"
            )
        )
        broadcastLog("SMS send: $id to $target accepted (${text.length} chars, sub=$subId)")

        dispatchOutbox()
        return 202 to cost
    }

    /** How the message will go out: parts it splits into, and its encoding. */
    data class SmsMeasure(val parts: Int, val encoding: String)

    /**
     * Measure once and use it twice — the 202 headers tell the server, and
     * the call-log entry keeps it for the detail view.  Recomputing at
     * display time would be measuring a different thing: what the text would
     * encode as now, not what was actually sent.
     */
    private fun measureSms(text: String): SmsMeasure? = try {
        // [0] parts, [1] code units used, [2] remaining, [3] encoding
        val m = android.telephony.SmsMessage.calculateLength(text, false)
        // SmsMessage.ENCODING_7BIT = 1, ENCODING_8BIT = 2, ENCODING_16BIT = 3
        SmsMeasure(
            parts = m[0],
            encoding = when (m[3]) {
                1 -> "GSM7"
                2 -> "8BIT"
                3 -> "UCS2"
                else -> "UNKNOWN"
            }
        )
    } catch (e: Exception) {
        Log.w(TAG, "Could not measure message: ${e.message}")
        null
    }

    /** Parts and encoding, as headers for the 202. */
    private fun measure(text: String): List<String> {
        val m = measureSms(text) ?: return emptyList()
        return listOf("X-SMS-Parts: ${m.parts}", "X-SMS-Encoding: ${m.encoding}")
    }

    /** Which SIM to send from: an explicit subscription wins, then a slot, then
     *  whatever the platform considers default. */
    private fun resolveSubscription(subId: Int?, slot: Int?): Int {
        if (subId != null && subId >= 0) return subId
        if (slot != null && slot >= 0) {
            try {
                val sm = getSystemService(android.telephony.SubscriptionManager::class.java)
                @Suppress("MissingPermission")
                val info = sm?.activeSubscriptionInfoList?.firstOrNull { it.simSlotIndex == slot }
                if (info != null) return info.subscriptionId
                broadcastLog("SMS send: no active SIM in slot $slot — using default")
            } catch (e: Exception) {
                Log.w(TAG, "Slot lookup failed: ${e.message}")
            }
        }
        return android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID
    }

    private fun hasSendSms(): Boolean =
        checkSelfPermission(android.Manifest.permission.SEND_SMS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /** One dispatch pass at a time, with a re-run for anything that arrived
     *  while it was running — a burst of requests otherwise starts a pass per
     *  request, all walking the same queue. */
    private val smsDispatching = AtomicBoolean(false)
    @Volatile private var smsDispatchAgain = false

    /** Hand anything not yet given to the modem to the modem. */
    private fun dispatchOutbox() {
        if (!smsDispatching.compareAndSet(false, true)) {
            smsDispatchAgain = true
            return
        }
        thread(name = "sms-dispatch") {
            try {
                do {
                    smsDispatchAgain = false
                    for (sms in SmsOutbox.all(this)) {
                        // The claim is what makes this safe, not the loop:
                        // whoever wins it is the only one that sends.
                        if (!SmsOutbox.claimForDispatch(this, sms.id)) continue
                        if (!SmsSender.dispatch(this, sms)) {
                            // Nothing will call back; say so now rather than
                            // leaving the server waiting for a report that
                            // cannot come.
                            reportOutbox(sms.id)
                        }
                    }
                } while (smsDispatchAgain)
            } finally {
                smsDispatching.set(false)
            }
        }
    }

    /**
     * Tell the server what has become of a message.
     *
     * Two reports at most: one when the network has taken it (or refused it),
     * and one when the delivery report arrives.  Carriers that do not return
     * status reports simply never produce the second, which is why the first
     * is not held back waiting for it.
     */
    /** Re-report anything the server has not acknowledged yet.  A report that
     *  was refused or lost is no less true for it. */
    private fun sweepOutboxReports() {
        thread(name = "sms-report-sweep") {
            for (sms in SmsOutbox.all(this)) {
                if (sms.finalReported) continue
                reportOutboxNow(sms.id)
            }
            SmsOutbox.prune(this)
        }
    }

    private fun reportOutbox(id: String?) {
        if (id == null) return
        thread(name = "sms-report") { reportOutboxNow(id) }
    }

    private fun reportOutboxNow(id: String) {
        run {
            val sms = SmsOutbox.get(this, id) ?: return
            val parts = maxOf(sms.parts, 1)
            val sentDone = sms.sentOk + sms.sentFailed >= parts
            val deliveryDone = sms.deliveredOk + sms.deliveredFailed >= parts

            if (sentDone && !sms.submitReported) {
                val failed = sms.sentFailed > 0
                if (sendSmsReport(sms, if (failed) "failed" else "submitted")) {
                    SmsOutbox.update(this, id) {
                        // A failed send is terminal: no delivery report follows.
                        it.copy(submitReported = true, finalReported = failed)
                    }
                } else {
                    scheduleSmsRetry()
                }
            }
            val current = SmsOutbox.get(this, id) ?: return
            if (deliveryDone && current.submitReported && !current.finalReported) {
                val event = if (current.deliveredFailed > 0) "undelivered" else "delivered"
                if (sendSmsReport(current, event)) {
                    SmsOutbox.update(this, id) { it.copy(finalReported = true) }
                } else {
                    scheduleSmsRetry()
                }
            }
            SmsOutbox.prune(this)
        }
    }

    /**
     * The gateway's own number for the SIM that carried this message.
     *
     * With one SIM this is just own_number.  With more than one it has to be
     * the number of the SIM the message actually arrived on or left by, or
     * the server maps it to the wrong assistant -- both SIMs reach the same
     * gateway, and only the number distinguishes them.  Falls back to the
     * single legacy value whenever the SIM cannot be resolved.
     */
    private fun ownNumberForSub(subId: Int): String {
        val prefs = getSharedPreferences("gateway", MODE_PRIVATE)
        val fallback = prefs.getString("own_number", "")?.trim().orEmpty()
        if (subId < 0) return fallback
        val slot = runCatching {
            getSystemService(android.telephony.SubscriptionManager::class.java)
                ?.getActiveSubscriptionInfo(subId)?.simSlotIndex
        }.getOrNull() ?: return fallback
        return prefs.getString("own_number_slot_$slot", "")?.trim()
            ?.ifEmpty { null } ?: fallback
    }

    /**
     * Clear the unread state on the default SMS app's copy of inbound
     * messages.
     *
     * The gateway is not the default SMS app -- Google Messages stays that,
     * because its copy is a useful independent record -- and the SMS provider
     * only accepts writes from the app that is.  So the ContentResolver
     * attempt is expected to fail on most devices and root does the work; the
     * direct attempt is kept first for the case where it does not.
     */
    private fun markInboxRead() {
        val values = android.content.ContentValues().apply {
            put("read", 1)
            put("seen", 1)
        }
        val direct = runCatching {
            contentResolver.update(
                android.provider.Telephony.Sms.Inbox.CONTENT_URI,
                values,
                "read = 0 OR seen = 0",
                null
            )
        }.getOrNull() ?: -1
        if (direct > 0) {
            Log.i(TAG, "Marked $direct inbox message(s) read")
            return
        }
        // Straight at the database, as root.  Going through the provider does
        // not work and does not say so: it accepts writes only from the
        // default SMS app and silently reports success to everyone else, so
        // `content update` returns rc=0 and changes nothing.  The path moved
        // to /data/user_de in the device-encrypted split, so try both.
        val out = RootShell.execForOutput(
            "for d in /data/user_de/0 /data/data; do " +
                "db=\$d/com.android.providers.telephony/databases/mmssms.db; " +
                "if [ -f \"\$db\" ]; then " +
                "sqlite3 \"\$db\" " +
                "\"UPDATE sms SET read=1, seen=1 WHERE read=0 OR seen=0;\"; " +
                "break; fi; done 2>&1",
            timeoutMs = 8000
        )
        if (out.isNotBlank()) Log.w(TAG, "markInboxRead: $out")
    }

    /** One report, as a SIP MESSAGE with a JSON body. */
    private fun sendSmsReport(sms: OutboundSms, event: String): Boolean {
        val sip = sipClient
        if (sip == null || !sip.registered) {
            broadcastLog("SMS report $event for ${sms.id} deferred — not registered")
            return false
        }
        // In hybrid/local mode there is no Asterisk report endpoint; mark reported so outbox prunes cleanly.
        if (sip.localSendMode || cfgServer.isBlank()) {
            return true
        }
        val own = ownNumberForSub(sms.subId).ifEmpty { cfgUser }
        val nowIso = smsTimeFormat.get()!!.format(java.util.Date())
        val body = org.json.JSONObject().apply {
            put("id", sms.id)
            put("event", event)
            put("to", sms.to)
            put("parts", maxOf(sms.parts, 1))
            put("sentOk", sms.sentOk)
            put("sentFailed", sms.sentFailed)
            put("deliveredOk", sms.deliveredOk)
            put("deliveredFailed", sms.deliveredFailed)
            if (sms.status.isNotEmpty()) put("status", sms.status)
            if (sms.lastError.isNotEmpty()) put("reason", sms.lastError)
            // The service centre and the on-air encoding are only known once
            // the message has actually gone out, so the report is the first
            // chance to tell the server either.
            if (sms.smsc.isNotEmpty()) put("smsc", sms.smsc)
            if (sms.encoding.isNotEmpty()) put("encoding", sms.encoding)
            put("at", nowIso)
        }.toString()

        val headers = mutableListOf(
            "X-SMS-Id: ${sms.id}",
            "X-SMS-Event: $event",
            "X-SMS-To: ${sms.to}",
            "X-SMS-Parts: ${maxOf(sms.parts, 1)}",
            "X-SMS-At: $nowIso"
        )
        if (sms.status.isNotEmpty()) headers += "X-SMS-Status: ${sms.status}"
        if (sms.lastError.isNotEmpty()) headers += "X-SMS-Reason: ${sms.lastError}"
        if (sms.smsc.isNotEmpty()) headers += "X-SMS-Smsc: ${sms.smsc}"
        if (sms.encoding.isNotEmpty()) headers += "X-SMS-Encoding: ${sms.encoding}"

        // text/plain, not application/json: chan_sip refuses anything else on
        // an out-of-call MESSAGE — measured, it answered 415.  The body is
        // still JSON for anyone who wants to parse it, but every field is in
        // an X-SMS-* header too, so SIP_HEADER() alone is enough.
        val code = sip.sendSipMessage(
            targetUri = "sip:$own@$cfgServer",
            fromUser = own,
            body = body,
            extraHeaders = headers,
            contentType = "text/plain;charset=UTF-8"
        )
        val ok = code == 200 || code == 202
        broadcastLog(
            "SMS report $event for ${sms.id}: " +
                if (ok) "acknowledged" else "not acknowledged (${if (code == 0) "no response" else code})"
        )
        return ok
    }

    private fun dialFromDialler(intent: Intent?) {
        val number = intent?.getStringExtra(EXTRA_NUMBER) ?: return
        orchestrator?.initiateDiallerCall(number)
            ?: broadcastLog("ERROR: Gateway not running — cannot bridge to SIP")
    }

    /**
     * Re-broadcast the current state.
     *
     * The UI is only ever told about state *changes*, so an Activity that
     * starts while the service is already running never hears anything and
     * shows its default "offline" until the next call.  This gives it a way
     * to ask.
     */
    private fun broadcastCurrentStatus() {
        val state = orchestrator?.bridgeState ?: CallOrchestrator.BridgeState.IDLE
        val registered = sipClient?.registered == true
        val info = when {
            stopped -> "Stopped"
            state == CallOrchestrator.BridgeState.IDLE && registered -> "SIP registered"
            state == CallOrchestrator.BridgeState.IDLE -> "Connecting"
            else -> state.name
        }
        broadcastStatus(if (stopped) "STOPPED" else state.name, info)
    }

    private fun reloadStats() {
        val totals = CallLogStore.getTotals(this)
        incomingCalls = totals.inCalls
        incomingDurationSec = totals.inDurationSec
        outgoingCalls = totals.outCalls
        outgoingDurationSec = totals.outDurationSec
        broadcastStatus(orchestrator?.bridgeState?.name ?: "IDLE", "Stats reloaded")
    }

    private fun startGateway(intent: Intent?) {
        // Guard: if the gateway is already running (SIP client exists and
        // we're not in stopped state), don't tear it down and restart.
        // This prevents redundant ACTION_START intents (e.g. from the
        // Activity opening, START_STICKY restart, or BootReceiver) from
        // killing an active SIP registration or call bridge.
        //
        // `initializing` covers the window before sipClient is assigned:
        // initSipClient() runs on the gateway-init thread, so two intents
        // arriving back to back (START_STICKY redelivery with a null intent
        // + the Activity's autoStartGateway) would both see a null sipClient
        // and each bring up their own client — two sockets on :5060 and two
        // REGISTERs.
        if (!stopped && (sipClient != null || initializing.get())) {
            Log.i(TAG, "startGateway: already running, skipping restart")
            // Broadcast current state so the Activity picks up the live status
            val state = orchestrator?.bridgeState ?: CallOrchestrator.BridgeState.IDLE
            val registered = sipClient?.registered == true
            val info = when (state) {
                CallOrchestrator.BridgeState.IDLE ->
                    if (registered) "SIP registered" else "Connected"
                else -> state.name
            }
            broadcastStatus(state.name, info)
            return
        }

        // Clean up any existing clients before starting new ones
        stopSipClients()
        stopped = false

        // Load call stats from persistent store so counters survive restarts
        onlineSince = 0L
        val totals = CallLogStore.getTotals(this)
        incomingCalls = totals.inCalls
        incomingDurationSec = totals.inDurationSec
        outgoingCalls = totals.outCalls
        outgoingDurationSec = totals.outDurationSec
        currentCallStart = 0L
        currentAttemptStart = 0L

        val prefs = getSharedPreferences("gateway", MODE_PRIVATE)
        val rawServer = (intent?.getStringExtra(EXTRA_SERVER) ?: prefs.getString("server", "") ?: "").trim()
        val intentPort = intent?.getIntExtra(EXTRA_PORT, -1) ?: -1
        val savedPort = prefs.getInt("port", 5060)
        val port = resolveEffectivePort(intentPort, savedPort)
        val username = (intent?.getStringExtra(EXTRA_USER) ?: prefs.getString("user", "") ?: "").trim()
        val password = intent?.getStringExtra(EXTRA_PASS) ?: prefs.getString("pass", "") ?: ""

        val decision = resolveCurrentRouting(prefs, rawServer, port)
        val isHybrid = decision.mode == RoutingMode.HYBRID_LOCAL_IP
        val isCloudOnly = decision.mode == RoutingMode.CLOUD_ONLY_LINPHONE
        prefs.edit().putBoolean("local_send_enabled", isHybrid).apply()

        if (!isHybrid && !isCloudOnly && (decision.customServer.isEmpty() || username.isEmpty())) {
            Log.e(TAG, "Missing SIP configuration")
            broadcastLog("ERROR: Missing server or username")
            broadcastStatus("ERROR", "Missing SIP configuration")
            stopSelf()
            return
        }

        // Save for restart if non-placeholder custom SIP values were provided
        if (!isHybrid && !isCloudOnly && decision.customServer.isNotEmpty()) {
            prefs.edit()
                .putString("server", decision.customServer)
                .putInt("port", decision.customPort)
                .putString("user", username)
                .putString("pass", password)
                .apply()
        }

        // Codec preference is a property of the SDP we build, so it has to be
        // in place before the first INVITE goes out.
        com.callagent.gateway.sip.SipBuilder.codecMode =
            prefs.getString("codec", "g722") ?: "g722"

        // The agent's level into the GSM uplink, as a step away from what the
        // device profile asks for.  Read here so a change applies on save
        // rather than waiting for the next call.
        com.callagent.gateway.gsm.GsmCallManager.agentVolumeStep =
            prefs.getInt("agent_vol_step", 0)

        activeRoutingDecision = decision
        if (isCloudOnly) {
            cfgServer = PushWakeController.getDomain(this)
            cfgPort = 5061
            cfgUser = PushWakeController.getPusherUser(this)
            cfgPass = PushWakeController.getPassword(this)
        } else {
            cfgServer = decision.customServer
            cfgPort = decision.customPort
            cfgUser = if (isHybrid && username.isEmpty()) "tswitch" else username
            cfgPass = password
        }

        notifStatusText = "Connecting"
        ensureForeground(NotifState.WARN, "Connecting")
        acquireLocks()
        initializing.set(true)
        initializingSince = System.currentTimeMillis()

        // Run network I/O off the main thread (Android blocks sockets on main thread)
        val gen = initGeneration.incrementAndGet()
        thread(name = "gateway-init") {
            try {
                // Force-allow RECORD_AUDIO BEFORE SIP registration.
                // Magisk su takes 4+ seconds on first invocation (root server
                // startup).  Must complete before any calls can arrive.
                forceAllowRecordAudio()
                initSipClient(gen)
            } finally {
                initializing.set(false)
            }
        }
    }

    /** Shared SIP init — called from both startGateway and reconnect threads. */
    private fun initSipClient(gen: Int) {
        /** True while this thread is still the newest bring-up. */
        fun current() = gen == initGeneration.get()

        if (!current()) {
            Log.w(TAG, "initSipClient: superseded before start (gen $gen)")
            return
        }

        // Previous clients must be gone before another socket is bound to
        // :5060.  reconnect() already does this, but startGateway() and the
        // stale-init recovery reach here without it.
        if (sipClient != null || cloudSipClient != null) {
            Log.w(TAG, "initSipClient: stopping previous SIP client(s)")
            broadcastLog("SIP: stopping previous client before rebind")
            stopSipClients()
        }

        checkDefaultDialer()
        checkSmsPermission()

        val prefs = getSharedPreferences("gateway", MODE_PRIVATE)
        val decision = resolveCurrentRouting(prefs, cfgServer, cfgPort)
        activeRoutingDecision = decision
        val isHybrid = decision.mode == RoutingMode.HYBRID_LOCAL_IP
        prefs.edit().putBoolean("local_send_enabled", isHybrid).apply()
        val activeNetwork = (getSystemService(Context.CONNECTIVITY_SERVICE)
            as ConnectivityManager).activeNetwork

        val localIp: String
        val publicIp: String
        val useTls: Boolean
        val bindPort: Int
        val sipServerDomain: String
        val sipServerPort: Int

        if (isHybrid) {
            val pinApplied = LocalIpPinController.applyForCurrentNetwork(this, decision.pinnedLocalIp)
            broadcastLog("IP PIN: ${decision.pinnedLocalIp} (applied=$pinApplied)")

            val hs = HotspotHelper.detectHotspot(this)
            val detectedNetIp = when {
                hs.active && hs.ipAddress != "0.0.0.0" -> hs.ipAddress
                hs.wifiIpAddress.isNotEmpty() -> hs.wifiIpAddress
                else -> getLocalIp()
            }
            currentNetworkIp = detectedNetIp
            localIp = if (pinApplied) decision.pinnedLocalIp else detectedNetIp
            currentLocalIp = localIp
            publicIp = localIp
            useTls = false
            bindPort = 5060
            sipServerDomain = localIp
            sipServerPort = 5060
            cfgServer = ""
            if (cfgUser.isEmpty()) cfgUser = "tswitch"
            broadcastLog(
                "HYBRID LOCAL IP: Hotspot=${hs.active} (${hs.ifaceName}) " +
                    "WiFi=${hs.wifiIpAddress.ifEmpty { "-" }} IP=$localIp:5060"
            )
        } else {
            // Custom SIP server mode: tear down cloud companion and clear local IP pin.
            synchronized(cloudRecoveryLock) {
                cloudSipClient?.stop()
                cloudSipClient = null
                cloudStartAttemptAtMs = 0L
            }
            LocalIpPinController.clearPin(decision.pinnedLocalIp)

            localIp = getLocalIp()
            currentNetworkIp = localIp
            currentLocalIp = localIp
            broadcastLog("Local IP: $localIp")

            // STUN: discover public IP for NAT traversal.  Optional, because a
            // SIP server on the same network needs no public address at all —
            // advertising one there would point the server at the far side of a
            // NAT it never has to cross.
            val useStun = prefs.getBoolean("use_stun", true)
            val stunResult = if (!useStun) null else try {
                StunClient.discover(network = activeNetwork)
            } catch (e: Exception) {
                Log.e(TAG, "STUN exception: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
            publicIp = stunResult?.publicIp ?: localIp
            when {
                !useStun ->
                    broadcastLog("STUN off — direct SIP, advertising $localIp")
                stunResult != null ->
                    broadcastLog("STUN public IP: ${stunResult.publicIp}:${stunResult.publicPort}")
                else ->
                    broadcastLog("STUN failed, using local IP for SDP")
            }

            if (stopped) return
            if (!current()) {
                Log.w(TAG, "initSipClient: superseded during STUN (gen $gen)")
                return
            }

            if (decision.mode == RoutingMode.CLOUD_ONLY_LINPHONE) {
                cfgServer = PushWakeController.getDomain(this)
                cfgPort = 5061
                cfgUser = PushWakeController.getPusherUser(this)
                cfgPass = PushWakeController.getPassword(this)
                useTls = true
                sipServerDomain = cfgServer
                sipServerPort = 5061
                broadcastLog("CLOUD ONLY MODE: TLS to $sipServerDomain:$sipServerPort (user=$cfgUser)")
            } else {
                cfgServer = decision.customServer
                cfgPort = decision.customPort
                useTls = decision.customUseTls
                sipServerDomain = decision.customServer
                sipServerPort = decision.customPort
                if (useTls) broadcastLog("SIP transport: TLS to $sipServerDomain:$sipServerPort")
            }
            bindPort = 5062
        }

        val sip = SipClient(
            username = cfgUser,
            password = cfgPass,
            serverDomain = sipServerDomain,
            serverPort = sipServerPort,
            localIp = localIp,
            localPort = bindPort,
            network = if (isHybrid) null else activeNetwork,
            publicIp = publicIp,
            useTls = useTls,
            srtpRequested = if (isHybrid) false else prefs.getBoolean("srtp_enabled", false),
            localSendMode = isHybrid
        )
        if (isHybrid) {
            sip.onLocalPeerRegistered = { user, addr ->
                LocalSendController.onZoiperPeerReady(this, addr.first, addr.second)
                broadcastStatus(
                    orchestrator?.bridgeState?.name ?: "IDLE",
                    "Local Send: $user@${addr.first}"
                )
            }
        }
        sipClient = sip

        val orch = CallOrchestrator(this, sip)
        orch.updateRoutingDecision(decision)
        orch.listener = object : CallOrchestrator.OrchestratorListener {
            override fun onStateChanged(state: CallOrchestrator.BridgeState, info: String) {
                Log.i(TAG, "Bridge: $state - $info")
                // The call lifecycle only ever reached logcat and the status
                // pill; the log viewer showed SIP and audio lines with no
                // record of the call they belonged to.
                broadcastLog("CALL: ${state.name}${if (info.isBlank()) "" else " — $info"}")
                val registered = sip.registered

                // Track online time
                if (registered && onlineSince == 0L) {
                    onlineSince = System.currentTimeMillis()
                    // Anything that arrived while SIP was down goes now, and
                    // any report the server never acknowledged goes again.
                    flushSmsQueue("registered")
                    sweepOutboxReports()
                } else if (!registered && state == CallOrchestrator.BridgeState.IDLE) {
                    onlineSince = 0L
                }

                // Track call direction and number
                when (state) {
                    CallOrchestrator.BridgeState.GSM_RINGING -> {
                        currentCallIncoming = true
                        currentCallNumber = info.removePrefix("GSM call from ")
                        if (currentAttemptStart == 0L) currentAttemptStart = System.currentTimeMillis()
                    }
                    CallOrchestrator.BridgeState.GSM_DIALING -> {
                        currentCallIncoming = false
                        currentCallNumber = info.removePrefix("Dialing ")
                        if (currentAttemptStart == 0L) currentAttemptStart = System.currentTimeMillis()
                    }
                    else -> {}
                }

                // Track call start / end
                if (state == CallOrchestrator.BridgeState.BRIDGED && currentCallStart == 0L) {
                    currentCallStart = System.currentTimeMillis()
                    if (currentCallIncoming) incomingCalls++ else outgoingCalls++
                }
                if (state == CallOrchestrator.BridgeState.IDLE &&
                    (currentCallStart != 0L || currentAttemptStart != 0L)
                ) {
                    // A zero duration is how the list already renders an
                    // unconnected call ("Not connected", red dash), so a failed
                    // attempt needs no new field — only an entry.
                    val dur =
                        if (currentCallStart != 0L)
                            (System.currentTimeMillis() - currentCallStart) / 1000
                        else 0L
                    if (currentCallStart != 0L) {
                        if (currentCallIncoming) incomingDurationSec += dur
                        else outgoingDurationSec += dur
                    }
                    CallLogStore.addEntry(this@GatewayService, CallLogEntry(
                        direction = if (currentCallIncoming) "IN" else "OUT",
                        // Timestamp the call from when it arrived or was dialled,
                        // not from when the bridge came up — an attempt that never
                        // bridged has no other time to show.
                        number = currentCallNumber,
                        timestamp = if (currentAttemptStart != 0L) currentAttemptStart
                                    else currentCallStart,
                        durationSec = dur
                    ))
                    currentCallStart = 0L
                    currentAttemptStart = 0L
                    currentCallNumber = ""
                }

                // Map bridge state to notification status text
                val (notifState, statusText) = when (state) {
                    CallOrchestrator.BridgeState.IDLE ->
                        if (registered) NotifState.OK to "Connected"
                        else NotifState.WARN to "Connecting"
                    CallOrchestrator.BridgeState.GSM_DIALING ->
                        NotifState.OK to "Dialing"
                    CallOrchestrator.BridgeState.BRIDGED ->
                        NotifState.OK to "In-Call"
                    CallOrchestrator.BridgeState.GSM_RINGING,
                    CallOrchestrator.BridgeState.GSM_ANSWERED,
                    CallOrchestrator.BridgeState.SIP_CALLING,
                    CallOrchestrator.BridgeState.SIP_RINGING ->
                        NotifState.OK to "In-Call"
                    CallOrchestrator.BridgeState.TEARING_DOWN ->
                        NotifState.OK to "In-Call"
                }
                updateNotification(notifState, statusText)
                broadcastStatus(state.name, info)
                if (state == CallOrchestrator.BridgeState.IDLE &&
                    registered &&
                    activeRoutingDecision.mode == RoutingMode.CLOUD_ONLY_LINPHONE &&
                    !cloudWelcomeSent
                ) {
                    cloudWelcomeSent = true
                    LocalSendController.sendFakeSmsToZoiper(
                        this@GatewayService,
                        "🟢 T-Switch Bulut Terminal Hazır!\n" +
                            "Komutları doğrudan bu sohbete yazın:\n" +
                            "• !ara <numara veya ad>\n" +
                            "• !sms <numara veya ad> <mesaj>\n" +
                            "• !durum  • !batarya  • !yardim",
                        logToHistory = false,
                        preferCloud = true
                    )
                }
            }

            override fun onError(error: String) {
                Log.e(TAG, "Orchestrator error: $error")
                broadcastLog("ERROR: $error")
                broadcastStatus("ERROR", error)
            }

            override fun onRtpStats(stats: String) {
                broadcastLog("RTP: $stats")
            }
        }
        orchestrator = orch
        orch.start()

        sip.logListener = { msg -> broadcastLog("SIP: $msg") }
        sip.onSmsRequestWithPeer = { m, peer ->
            onSmsSendRequest(m, isLocalTransport = isHybrid, sourcePeer = peer)
        }
        sip.onSmsRequest = { m ->
            onSmsSendRequest(m, isLocalTransport = isHybrid, sourcePeer = null)
        }
        GsmCallManager.logCallback = { msg -> broadcastLog("AUDIO: $msg") }
        RootShell.statusCallback = { msg -> broadcastLog("ROOT: $msg") }
        sip.onConnectionLost = { reconnect() }

        // Last check before anything binds a socket: if a newer bring-up has
        // started meanwhile, this client must not exist at all.
        if (!current() || sipClient !== sip) {
            Log.w(TAG, "initSipClient: superseded before start (gen $gen) — discarding")
            orch.stop()
            return
        }

        try {
            sip.start()
            if (isHybrid) {
                onlineSince = System.currentTimeMillis()
                updateNotification(NotifState.OK, "Local Send ($localIp)")
                broadcastLog("[v${BuildConfig.VERSION_NAME}] Hybrid Local IP aktif: $localIp:5060 (Zoiper/Linphone bekleniyor)")
                broadcastStatus("IDLE", "Local Send ($localIp:5060)")
                if (current() && PushWakeController.isConfigured(this)) {
                    startHybridCloudCompanion(
                        localFallbackIp = localIp,
                        activeNetwork = activeNetwork,
                        expectedLocalSip = sip,
                        gen = gen
                    )
                }
                thread(name = "localsend-arp-probe") {
                    try {
                        Thread.sleep(1500)
                        val peer = sip.resolveLocalPeerAddress()
                        if (peer != null) {
                            LocalSendController.onZoiperPeerReady(this, peer.first, peer.second)
                            broadcastStatus("IDLE", "Local Send: Zoiper @ ${peer.first}")
                        }
                    } catch (_: Exception) {}
                }
            } else {
                broadcastLog("[v${BuildConfig.VERSION_NAME}] SIP client started, registering with $sipServerDomain:$sipServerPort")
                broadcastStatus("STARTING", "Registering with $sipServerDomain")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SIP client: ${e.message}", e)
            broadcastLog("ERROR: SIP start failed — ${e.message}")
            broadcastStatus("ERROR", "SIP start failed: ${e.message}")
        }
    }

    @Volatile private var stopped = false

    private fun stopGateway() {
        if (stopped) return
        stopped = true
        onlineSince = 0L
        Log.i(TAG, "Stopping gateway")
        stopSipClients()
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        broadcastStatus("STOPPED", "Gateway stopped")
    }

    override fun onDestroy() {
        rescueHandler.removeCallbacksAndMessages(null)
        rescueScheduled = false
        cloudSipClient?.stop()
        cloudSipClient = null
        com.callagent.gateway.localsend.LocalMdnsResponder.stop()
        LocalSendController.detach()
        unregisterNetworkCallback()
        stopGateway()
        super.onDestroy()
    }

    // ── Notification ────────────────────────────────────

    /**
     * One channel, at minimum importance.
     *
     * A foreground service must keep a notification and Android will not let
     * that go, so the gateway always has one entry in the shade -- that part
     * is the platform's, not a setting.  Minimum importance keeps it silent
     * and at the bottom of the shade, which is as unobtrusive as it gets
     * while still showing the gateway's state.
     *
     * The old normal-importance channel is deleted rather than left behind,
     * so it stops appearing in the system's per-app notification settings on
     * devices that ran an earlier build.
     */
    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID_QUIET,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = getString(R.string.channel_description)
                setShowBadge(false)
            }
        )
        runCatching { nm.deleteNotificationChannel(CHANNEL_ID) }
    }

    /**
     * Undo the notification suspension an earlier build applied.
     *
     * Suspending the package's notifications did hide the icon, but it is a
     * blunt instrument -- it swallows everything the app might ever post --
     * and it is unnecessary now that the icon draws nothing.  That state
     * survives app updates, so clear it unconditionally on start.
     */
    private fun applyNotificationVisibility() {
        RootShell.exec("cmd notification unsuspend_package $packageName 2>/dev/null", 5000)
    }

    /**
     * Stop the default SMS app announcing messages the gateway has forwarded.
     *
     * Done here rather than only in the Magisk module so it holds regardless
     * of the module's state, and follows the SMS role if it changes.  The
     * package is asked for, never assumed: hardcoding Google Messages meant
     * this silently did nothing on a LineageOS build, which ships
     * com.android.messaging instead.
     */
    private fun silenceDefaultSmsApp() {
        val cmd = buildString {
            append("d=\$(settings get secure sms_default_application 2>/dev/null | tr -d '\\r'); ")
            append("case \"\$d\" in null|'') d=\"\";; esac; ")
            append("for p in \$d com.google.android.apps.messaging com.android.messaging; do ")
            append("[ -n \"\$p\" ] || continue; ")
            append("pm path \"\$p\" >/dev/null 2>&1 || continue; ")
            append("pm revoke \"\$p\" android.permission.POST_NOTIFICATIONS 2>/dev/null; ")
            append("cmd appops set \"\$p\" POST_NOTIFICATION ignore 2>/dev/null; ")
            append("cmd notification suspend_package \"\$p\" 2>/dev/null; ")
            append("done")
        }
        RootShell.exec(cmd, 8000)
    }

    /** The notification id in use.  See [cancelStaleNotification]. */
    private fun activeNotificationId(): Int = NOTIFICATION_ID_QUIET

    /**
     * Drop the notification id an earlier build used.
     *
     * A notification's channel is fixed when it is first posted, so the id
     * that was bound to the normal-importance channel cannot be reused for a
     * silent one; it is abandoned and cancelled instead of carried forward.
     */
    private fun cancelStaleNotification() {
        runCatching { getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) }
    }

    private fun activeChannelId(): String = CHANNEL_ID_QUIET

    private enum class NotifState { OK, WARN, ERROR }

    /** Current notification status text, kept in sync with bridge/SIP state. */
    private var notifStatusText = "Connecting"
    /** Last state the notification was built with, so re-entering the
     *  foreground for an SMS does not rewrite what the user sees. */
    @Volatile private var notifState = NotifState.WARN

    private fun buildNotification(state: NotifState = NotifState.ERROR, statusText: String = notifStatusText): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // An empty icon when the status bar is turned off.  The quiet channel
        // is not enough on its own: Android forces a foreground service's
        // notification up to at least LOW importance whatever the channel
        // says, and LOW still draws a glyph.  The icon is the part it does
        // not override, so the setting is honoured by drawing nothing.  The
        // notification itself stays, because the platform requires it, and
        // still carries the status text in the shade.
        // The state icon, in the status bar and in the shade.  An invisible
        // icon did keep the gateway out of the status bar, but the shade entry
        // is not removable either way -- Android requires a foreground service
        // to hold one -- and that entry with a blank space where its icon
        // belongs looks broken rather than discreet.  So it carries the real
        // icon, which also makes the gateway's state readable at a glance.
        // One dot for every state, coloured by state.
        //
        // The colour only shows in the notification shade: the status bar
        // draws small icons monochrome, tinting them to match the bar, so the
        // dot reads white up there no matter what colour is set.  Shape was
        // what distinguished the states in the status bar before; a dot trades
        // that for a quieter icon.
        val icon = R.drawable.ic_notif_dot
        val accent = when (state) {
            NotifState.OK -> 0xFF16A34A.toInt()      // green — registered
            NotifState.WARN -> 0xFFEAB308.toInt()    // amber — connecting or in transition
            NotifState.ERROR -> 0xFFDC2626.toInt()   // red — stopped or failed
        }
        // No actions.  The notification carries the gateway's status and
        // nothing else: it is a background service on an unattended handset,
        // and a button there is one nobody is present to press.  Listen-in is
        // reached from the app itself.  ACTION_MONITOR still exists and is
        // still handled, so anything already bound to it keeps working.

        return Notification.Builder(this, activeChannelId())
            .setContentTitle(statusText)
            .setSmallIcon(icon)
            .setColor(accent)
            .setContentIntent(pi)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
            .apply { flags = flags or Notification.FLAG_NO_CLEAR }
    }

    private fun updateNotification(state: NotifState = NotifState.ERROR, statusText: String? = null) {
        if (statusText != null) notifStatusText = statusText
        notifState = state
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(activeNotificationId(), buildNotification(state))
    }

    // ── Wake / WiFi locks ───────────────────────────────

    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gateway:sip").apply {
            acquire()
        }

        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "gateway:wifi").apply {
            acquire()
        }
        Log.i(TAG, "Wake + WiFi locks acquired")
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock = null
    }

    // ── Broadcast to MainActivity ────────────────────────

    private fun broadcastStatus(state: String, info: String) {
        val isHybrid = activeRoutingDecision.mode == RoutingMode.HYBRID_LOCAL_IP
        val peer = sipClient?.registeredPeer
        val intent = Intent(STATUS_ACTION).apply {
            setPackage(packageName)
            putExtra("state", state)
            putExtra("info", info)
            // Whether we are actually registered, as a fact rather than as a
            // string the UI has to recognise.  The pill used to infer this by
            // comparing info to the literal "SIP registered", so every state
            // change carrying any other text — "GSM call ended", for one —
            // read as offline while the registration was perfectly alive.
            putExtra("registered", sipClient?.registered == true)
            putExtra("local_send", isHybrid)
            putExtra("routing_mode", activeRoutingDecision.mode.name)
            putExtra("tls_active", sipClient?.let { it.useTls && !it.localSendMode } == true)
            putExtra("srtp_active", sipClient?.srtpEnabled == true)
            putExtra("call_incoming", currentCallIncoming)
            putExtra("local_ip", currentLocalIp)
            putExtra("local_peer", if (peer != null) "${peer.first}:${peer.second}" else "")
            putExtra("online_since", onlineSince)
            // When the bridge came up, so the UI can show elapsed time rather
            // than time-since-it-noticed.  It only learns of a call when a
            // state change is broadcast, which is not when the call started.
            putExtra("call_start", currentCallStart)
            putExtra("in_calls", incomingCalls)
            putExtra("in_duration", incomingDurationSec)
            putExtra("out_calls", outgoingCalls)
            putExtra("out_duration", outgoingDurationSec)
        }
        sendBroadcast(intent)
    }

    private fun broadcastLog(msg: String) {
        // Buffer for replay when activity resumes (receiver is only active in
        // foreground).  Stamped here, not on replay: the UI used to timestamp
        // as it appended, so every line buffered while the app was closed —
        // the whole unattended history, which is the part worth reading —
        // collapsed onto the moment the app was opened.
        // Mirrored to logcat as well: everything the Logs screen shows is then
        // greppable over adb, which is how these get read when something has
        // already gone wrong and the app is not in front of anyone.
        Log.i(TAG, "LOG: $msg")
        val stamped = "${bufferTimeFormat.get()!!.format(java.util.Date())}  $msg"
        synchronized(logBuffer) {
            logBuffer.add(stamped)
            if (logBuffer.size > LOG_BUFFER_SIZE) logBuffer.removeAt(0)
        }
        val intent = Intent(LOG_ACTION).apply {
            setPackage(packageName)
            putExtra("msg", msg)
        }
        sendBroadcast(intent)
    }

    // ── Network ─────────────────────────────────────────

    private fun getLocalIp(): String {
        // Ask for the address of the network that actually carries our
        // traffic.  Enumerating interfaces and taking the first non-loopback
        // IPv4 could hand back the cellular rmnet address while SIP was
        // running over WiFi — so cellular attaching or detaching read as "the
        // local IP changed" and forced a reconnect and a fresh REGISTER that
        // WiFi never needed.  The active network's link address is the one the
        // socket will bind through.
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val active = cm.activeNetwork
            val link = active?.let { cm.getLinkProperties(it) }
            link?.linkAddresses?.firstOrNull { la ->
                la.address is Inet4Address && !la.address.isLoopbackAddress
            }?.address?.hostAddress?.let { return it }
        } catch (e: Exception) {
            Log.w(TAG, "Active-network IP unavailable: ${e.message}")
        }

        // Fallback: interface scan, WiFi first for the same reason.
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
                .toList()
                .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
                .let { java.util.Collections.enumeration(it) }
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress ?: continue
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get local IP: ${e.message}")
        }
        return "0.0.0.0"
    }

    /**
     * Force-allow RECORD_AUDIO via appops using root (Magisk).
     *
     * Android's AppOpsService can revoke RECORD_AUDIO (app op 27) for
     * background apps even when runtime permission is granted.  On the
     * second call the screen is off and the system may deny AudioRecord.
     *
     * Called SYNCHRONOUSLY on the gateway-init thread BEFORE initSipClient().
     * This ensures the appops command completes before SIP registration,
     * so no incoming calls can arrive while it's still pending.
     *
     * On cold boot, the appops service is NOT available for ~45-60 seconds
     * after the kernel starts.  This method BLOCKS until the service appears,
     * intentionally delaying SIP registration.  No calls can arrive until
     * both appops is verified AND SIP is registered.
     *
     * CRITICAL: Must use --uid flag to set the UID-level mode.
     * `appops set <pkg>` sets the package mode, but AudioFlinger checks
     * the UID mode (set by PermissionController).  UID mode overrides
     * package mode, so without --uid the allow is ineffective on cold boot.
     */
    private fun forceAllowRecordAudio() {
        try {
            val pkg = packageName

            // Wait for appops service to become available (cold boot).
            // Blocking here is intentional — initSipClient() must not run
            // until appops is set, because SIP registration makes us
            // reachable for incoming calls and AudioRecord will be denied
            // without the permission.  Polls every 3s for up to 90s.
            val maxWaitMs = 90_000L
            val pollMs = 3_000L
            val waitStart = System.currentTimeMillis()
            while (System.currentTimeMillis() - waitStart < maxWaitMs) {
                val uidProbe = if (Build.VERSION.SDK_INT >= 29) "--uid " else ""
                val probe = RootShell.execForOutput(
                    "appops get ${uidProbe}$pkg RECORD_AUDIO 2>&1"
                )
                if (!probe.contains("Can't find service", ignoreCase = true)) {
                    val waited = System.currentTimeMillis() - waitStart
                    if (waited > 100) {
                        Log.i(TAG, "appops service ready after ${waited}ms")
                        broadcastLog("System services ready (${waited / 1000}s)")
                    }
                    break
                }
                val elapsed = (System.currentTimeMillis() - waitStart) / 1000
                Log.i(TAG, "appops service not ready (${elapsed}s), waiting...")
                broadcastLog("Waiting for system services... (${elapsed}s)")
                Thread.sleep(pollMs)
            }

            val t0 = System.currentTimeMillis()
            val autoRevoke = if (Build.VERSION.SDK_INT >= 30)
                "appops set $pkg AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore 2>&1; " else ""
            val uidFlag = if (Build.VERSION.SDK_INT >= 29) "--uid " else ""
            val result = RootShell.execForOutput(
                "killall com.google.android.permissioncontroller 2>/dev/null; " +
                "killall com.android.permissioncontroller 2>/dev/null; " +
                "pm grant $pkg android.permission.RECORD_AUDIO 2>&1; " +
                autoRevoke +
                "appops set ${uidFlag}$pkg RECORD_AUDIO allow 2>&1; " +
                "appops set $pkg RECORD_AUDIO allow 2>&1; " +
                "killall com.google.android.permissioncontroller 2>/dev/null; " +
                "killall com.android.permissioncontroller 2>/dev/null; " +
                "appops get ${uidFlag}$pkg RECORD_AUDIO 2>&1"
            )
            val elapsed = System.currentTimeMillis() - t0
            val allowed = result.contains("allow", ignoreCase = true)
            Log.i(TAG, "appops RECORD_AUDIO: [$result] ok=$allowed (${elapsed}ms)")
            broadcastLog("appops RECORD_AUDIO: ok=$allowed (${elapsed}ms)")

            if (!allowed) {
                val fb = RootShell.execForOutput(
                    "cmd appops set ${uidFlag}$pkg RECORD_AUDIO allow 2>&1; " +
                    "cmd appops set $pkg RECORD_AUDIO allow 2>&1; " +
                    "cmd appops get ${uidFlag}$pkg RECORD_AUDIO 2>&1"
                )
                Log.w(TAG, "appops fallback cmd: [$fb]")
                broadcastLog("appops fallback: [$fb]")
            } else {
                Log.d(TAG, "appops RECORD_AUDIO verified: allow")
            }
        } catch (e: Exception) {
            Log.w(TAG, "appops force-allow failed (non-root?): ${e.message}")
            broadcastLog("appops RECORD_AUDIO failed: ${e.message}")
        }
    }

    internal enum class CloudRecoveryAction {
        NONE,
        START_NEW,
        RESTART_STALE
    }

    companion object {
        private const val TAG = "GatewayService"
        private const val LOG_BUFFER_SIZE = 200

        /**
         * Resolves the effective SIP port from an optional Intent extra and the persisted port.
         * A missing/invalid [intentPort] (e.g. `-1`) preserves a valid [savedPort] (such as `5061`)
         * instead of clobbering it back to `5060`.
         */
        fun resolveEffectivePort(intentPort: Int, savedPort: Int, defaultPort: Int = 5060): Int = when {
            intentPort in 1..65535 -> intentPort
            savedPort in 1..65535 -> savedPort
            else -> defaultPort
        }

        /** Ring buffer of recent log messages — survives activity pause/resume. */
        private val bufferTimeFormat: ThreadLocal<java.text.SimpleDateFormat> =
            ThreadLocal.withInitial {
                java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            }

        val logBuffer = mutableListOf<String>()

        /** Drain buffered logs.  Returns all messages and clears the buffer. */
        /**
         * Everything buffered, without consuming it.
         *
         * The old drain cleared as it read, so a second reader — an activity
         * recreated mid-session, say — got nothing, and the Logs screen came
         * up blank precisely when there was something to look at.
         */
        fun logSnapshot(): List<String> = synchronized(logBuffer) { logBuffer.toList() }

        fun clearLogBuffer() = synchronized(logBuffer) { logBuffer.clear() }

        fun drainLogBuffer(): List<String> = synchronized(logBuffer) {
            val copy = logBuffer.toList()
            logBuffer.clear()
            copy
        }

        /** Longest plausible time to bring a SIP client up; past this the
         *  init flag is assumed stuck rather than genuinely in progress. */
        private const val INIT_STALE_MS = 90_000L

        const val CHANNEL_ID = "gateway_channel"
        const val CHANNEL_ID_QUIET = "gateway_channel_quiet"
        const val NOTIFICATION_ID = 1
        /** Same notification, silent channel — see [activeNotificationId]. */
        const val NOTIFICATION_ID_QUIET = 2
        const val ACTION_START = "com.callagent.gateway.START"
        const val ACTION_STOP = "com.callagent.gateway.STOP"
        const val ACTION_RELOAD_STATS = "com.callagent.gateway.RELOAD_STATS"
        const val ACTION_STATUS = "com.callagent.gateway.STATUS_REQUEST"
        const val ACTION_RECONNECT = "com.callagent.gateway.RECONNECT"
        const val ACTION_DIAL = "com.callagent.gateway.DIAL"
        const val ACTION_MONITOR = "com.callagent.gateway.MONITOR"
        const val EXTRA_MONITOR_ON = "monitor_on"
        const val ACTION_MUTE_AGENT = "com.callagent.gateway.MUTE_AGENT"
        const val EXTRA_MUTE_ON = "mute_on"
        const val EXTRA_SERVER = "server"
        const val EXTRA_PORT = "port"
        const val EXTRA_USER = "user"
        const val EXTRA_PASS = "pass"
        const val EXTRA_NUMBER = "number"
        const val STATUS_ACTION = "com.callagent.gateway.STATUS"
        const val LOG_ACTION = "com.callagent.gateway.LOG"
        const val ACTION_APPLY_CONFIG = "com.callagent.gateway.APPLY_CONFIG"
        const val ACTION_SEND_TERMINAL_WELCOME = "com.callagent.gateway.SEND_TERMINAL_WELCOME"
        const val ACTION_SMS_FLUSH = "com.callagent.gateway.SMS_FLUSH"
        const val ACTION_SMS_SEND = "com.callagent.gateway.SMS_SEND"
        const val ACTION_SMS_REPORT = "com.callagent.gateway.SMS_REPORT"
        const val EXTRA_SMS_ID = "sms_id"

        /** How long to wait before retrying a message the server did not take. */
        private const val SMS_RETRY_MS = 30_000L

        /** X-SMS-Received: ISO 8601 UTC, so the server does not have to guess
         *  at the gateway's local time zone. */
        private val smsTimeFormat: ThreadLocal<java.text.SimpleDateFormat> =
            ThreadLocal.withInitial {
                java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }
            }

        /**
         * Ask the running gateway to forward whatever SMS are queued.
         *
         * Called from the SMS receiver, which has already put the message on
         * disk — so if the service is not up, or is killed on the way, nothing
         * is lost: the queue is flushed again as soon as SIP registers.
         */
        /** A send result or delivery report landed — let the service tell the
         *  server about it. */
        fun reportSmsProgress(context: Context, id: String) {
            val intent = Intent(context, GatewayService::class.java).apply {
                action = ACTION_SMS_REPORT
                putExtra(EXTRA_SMS_ID, id)
            }
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Could not wake gateway for SMS report: ${e.message}")
            }
        }

        fun deliverQueuedSms(context: Context) {
            val intent = Intent(context, GatewayService::class.java).apply {
                action = ACTION_SMS_FLUSH
            }
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Could not wake gateway for SMS: ${e.message}")
            }
        }

        fun start(context: Context, server: String, port: Int, user: String, pass: String) {
            val intent = Intent(context, GatewayService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SERVER, server)
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_USER, user)
                putExtra(EXTRA_PASS, pass)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, GatewayService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        /**
         * Validates that a public-cloud `DirectGsmSms` sender matches BOTH the
         * paired receiver SIP username and one of the allowed cloud SIP domains,
         * blocking federated SIP domain spoofing (e.g. `receiver_user@evil.com`).
         */
        fun isAuthorizedCloudSmsSender(
            fromUser: String?,
            fromDomain: String?,
            pairedReceiverUser: String,
            allowedDomains: Set<String>
        ): Boolean {
            val expectedUser = pairedReceiverUser.trim()
            val actualUser = fromUser?.trim()
            val actualDomain = fromDomain?.trim()
            if (expectedUser.isEmpty() || actualUser.isNullOrEmpty() || actualDomain.isNullOrEmpty()) {
                return false
            }
            if (!actualUser.equals(expectedUser, ignoreCase = true)) {
                return false
            }
            return allowedDomains.any { domain ->
                val trimmed = domain.trim()
                trimmed.isNotEmpty() && trimmed.equals(actualDomain, ignoreCase = true)
            }
        }

        internal fun decideCloudSipRecovery(
            routingMode: RoutingMode,
            pushConfigured: Boolean,
            hasInternet: Boolean,
            hasPrimaryLocalClient: Boolean,
            cloudExists: Boolean,
            cloudRunning: Boolean,
            cloudRegistered: Boolean,
            nowMs: Long,
            lastAttemptMs: Long,
            restartCooldownMs: Long = 10_000L
        ): CloudRecoveryAction {
            if (routingMode != RoutingMode.HYBRID_LOCAL_IP ||
                !pushConfigured ||
                !hasInternet ||
                !hasPrimaryLocalClient
            ) {
                return CloudRecoveryAction.NONE
            }
            if (cloudRegistered) return CloudRecoveryAction.NONE
            if (!cloudExists || !cloudRunning) return CloudRecoveryAction.START_NEW
            return if (nowMs - lastAttemptMs >= restartCooldownMs) {
                CloudRecoveryAction.RESTART_STALE
            } else {
                CloudRecoveryAction.NONE
            }
        }
    }
}
