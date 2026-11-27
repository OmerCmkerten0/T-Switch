package com.callagent.gateway

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioManager
import android.widget.SeekBar
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.net.Uri
import android.net.wifi.WifiManager
import android.app.role.RoleManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.callagent.gateway.localsend.HotspotHelper
import com.callagent.gateway.localsend.LocalIpPinController
import com.callagent.gateway.localsend.RoutingDecision
import com.callagent.gateway.localsend.RoutingMode
import com.callagent.gateway.localsend.RoutingPolicy
import com.callagent.gateway.service.CallLogEntry
import com.callagent.gateway.service.CallLogStore
import com.callagent.gateway.sms.SmsOutbox
import com.callagent.gateway.service.GatewayService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    // Settings-tab views
    private lateinit var tvLog: TextView
    private lateinit var svLog: ScrollView

    // Dialler-tab views

    // Calls-tab views
    private var callLogFilter = "IN"

    // Pre-cached call log lists (built once, swapped on filter change)
    private var cachedInEntries: List<CallLogEntry> = emptyList()
    private var cachedOutEntries: List<CallLogEntry> = emptyList()
    private var callLogBuiltIn = false
    private var callLogBuiltOut = false

    // In-memory contacts cache + ContentObserver state
    @Volatile private var cachedContacts: List<Pair<String, String>> = emptyList()
    private val contactNameCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var contactsLoaded = false
    @Volatile private var contactsDirty = true
    private var lastRenderedContactsQuery: String? = null
    private var contactsObserver: android.database.ContentObserver? = null
    private val contactsLoading = java.util.concurrent.atomic.AtomicBoolean(false)

    // Cached signal dBm to avoid blocking UI thread on allCellInfo binder calls
    @Volatile private var cachedSignalDbm: Int? = null
    @Volatile private var lastSignalProbeMs: Long = 0L

    // Coalesced UI refresh for bursts of LOG_ACTION broadcasts
    private var logUiRefreshPending = false
    private val coalescedLogUiRefresh = Runnable {
        logUiRefreshPending = false
        if (currentTab == "home") {
            renderHomeTraffic()
            refreshHomeDashboard()
        } else if (currentTab == "config") {
            refreshLocalSendCard()
            refreshKioskCard()
        }
    }

    // Tab containers + bottom bar
    private lateinit var tabbedRoot: LinearLayout
    private lateinit var tabHome: View
    private lateinit var tabConfig: View
    private lateinit var tvHomeStatusPill: TextView
    private lateinit var tvHomeTlsBadge: TextView
    private lateinit var tvHomeSrtpBadge: TextView
    private lateinit var tvHomeMonitorBadge: TextView
    private lateinit var tvNetMobile: TextView
    private lateinit var tvNetWifi: TextView
    private lateinit var tvHomeLocalSendStatus: TextView
    private lateinit var btnHomeLocalSendToggle: Button
    private lateinit var btnHomeTerminalPing: Button
    private var lastLocalPeer = ""
    private var lastServiceLocalIp = ""
    private var lastSystemLogSyncMs = 0L
    private val netHandler = Handler(Looper.getMainLooper())
    private val netRunnable = object : Runnable {
        override fun run() {
            refreshNetworkInfo()
            refreshHomeDashboard()
            refreshLocalSendCard()
            refreshKioskCard()
            // Signal and link speed drift constantly; five seconds is often
            // enough to be useful without being a battery drain.
            netHandler.postDelayed(this, 5000)
        }
    }
    private lateinit var homeCallCard: View
    private lateinit var tvHomeCallDirection: TextView
    private lateinit var tvHomeCallTimer: TextView
    private lateinit var tvHomeCallFrom: TextView
    private lateinit var tvHomeCallTo: TextView
    private lateinit var btnHomeMute: Button
    private lateinit var btnHomeSnoop: Button
    private lateinit var btnHomeEnd: Button
    private lateinit var homeTrafficList: LinearLayout
    private lateinit var tvHomeTrafficEmpty: TextView
    private lateinit var btnFilterAll: Button
    private lateinit var btnFilterIncoming: Button
    private lateinit var btnFilterOutgoing: Button
    private lateinit var btnFilterSms: Button
    /** "all" | "in" | "out" | "sms" — which entries the home list shows.  A missed call
     *  is an inbound one that never carried audio, so it belongs under
     *  Incoming rather than in a category of its own. */
    private var callFilter = "all"
    private var agentMuted = false

    private lateinit var tabLogs: LinearLayout
    private var currentTab = ""

    // In-call views
    private lateinit var inCallView: LinearLayout
    private lateinit var tvInCallStatus: TextView
    private lateinit var tvInCallNumber: TextView
    private lateinit var tvInCallTimer: TextView
    private lateinit var btnInCallEnd: Button
    private lateinit var btnInCallMonitor: Button
    private var monitoring = false
    /** True while a call is bridged, so SNOOP is only offered when it can work. */
    private var callLive = false
    /** Whether the gateway is actually registered, as opposed to merely
     *  running.  Drives the pill and gates the manual retry. */
    private var gatewayOnline = false
    /** Registration state as reported by the service, rather than guessed from
     *  the status text.  See GatewayService.broadcastStatus. */
    private var sipRegistered = false
    private var inCallOpen = false
    private var inCallOpenTime = 0L
    private var viewBeforeInCall = "dialer"
    private var callStartTime = 0L
    /** When the service says the bridge came up.  Authoritative: the activity
     *  can be started, stopped and restarted several times during one call. */
    private var serviceCallStart = 0L
    private var lastGsmPollState = -1
    private val callTimerHandler = Handler(Looper.getMainLooper())
    private val callTimerRunnable = object : Runnable {
        override fun run() {
            if (callStartTime > 0) {
                val elapsedMs = System.currentTimeMillis() - callStartTime
                val elapsed = elapsedMs / 1000
                val t = if (elapsed >= 3600) {
                    String.format(
                        "%d:%02d:%02d", elapsed / 3600, (elapsed % 3600) / 60, elapsed % 60
                    )
                } else {
                    String.format("%02d:%02d", elapsed / 60, elapsed % 60)
                }
                tvInCallTimer.text = t
                if (::tvHomeCallTimer.isInitialized) tvHomeCallTimer.text = t
                // Tick on the second boundary of the call, not 1000ms after
                // whenever this happened to run: a flat delay accumulates the
                // handler's own latency, so the display drifts off the real
                // elapsed time and eventually skips or repeats a second.
                callTimerHandler.postDelayed(this, 1000 - (elapsedMs % 1000))
            }
        }
    }
    /**
     * Arm the call timer against the service's start time.
     *
     * Two things were wrong with starting it from the UI's own clock.  The
     * activity only hears about a call when a state change is broadcast, so
     * reopening the app mid-call restarted the count at 00:00; and the ticker
     * is cancelled in onPause but was only ever re-armed when callStartTime
     * was still zero, so coming back to a live call showed a frozen number.
     * Re-arming unconditionally is safe — the pending callback is removed
     * first — and the elapsed time is now real in both cases.
     */
    /** Keep a call button's icon in step with its label — the two describe
     *  the same action, so they have to change together. */
    private fun setCallButtonState(button: Button, label: String, iconRes: Int) {
        button.text = label
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(0, iconRes, 0, 0)
    }

    private fun startCallTimer() {
        callStartTime = if (serviceCallStart > 0) serviceCallStart else System.currentTimeMillis()
        callTimerHandler.removeCallbacks(callTimerRunnable)
        callTimerRunnable.run()
    }

    private val gsmPollRunnable = object : Runnable {
        override fun run() {
            if (!inCallOpen) return
            val call = com.callagent.gateway.gsm.GsmCallManager.activeCall
            val state = com.callagent.gateway.gsm.GsmCallManager.activeCallState
            if (call != null && state != lastGsmPollState) {
                lastGsmPollState = state
                when (state) {
                    android.telecom.Call.STATE_CONNECTING -> tvInCallStatus.text = "Calling..."
                    android.telecom.Call.STATE_DIALING -> tvInCallStatus.text = "Ringing..."
                    android.telecom.Call.STATE_RINGING -> tvInCallStatus.text = "Ringing..."
                    android.telecom.Call.STATE_ACTIVE -> {
                        if (running) {
                            tvInCallStatus.text = "Connecting..."
                        } else {
                            tvInCallStatus.text = "Connected"
                            if (callStartTime == 0L) {
                                callStartTime = System.currentTimeMillis()
                                tvInCallTimer.text = "00:00"
                                tvInCallTimer.visibility = View.VISIBLE
                                callTimerRunnable.run()
                            }
                        }
                    }
                    android.telecom.Call.STATE_DISCONNECTED -> {
                        scheduleInCallClose()
                        return
                    }
                }
            } else if (call == null && lastGsmPollState != -1) {
                // GSM call was seen by poll but is now gone — call ended
                scheduleInCallClose()
                return
            } else if (call == null && lastGsmPollState == -1) {
                // Never saw a GSM call — failed dial or slow setup
                // Safety timeout to avoid stuck screen
                if (System.currentTimeMillis() - inCallOpenTime > 8000) {
                    closeInCallScreen()
                    return
                }
            }
            callTimerHandler.postDelayed(this, 500)
        }
    }

    private var running = false
    private var gsmCallActive = false
    private var currentCallIncoming = true
    private var onlineSince = 0L

    private val contactsSearchHandler = Handler(Looper.getMainLooper())
    private var pendingContactsSearch: Runnable? = null

    private fun resolveRoutingFromPrefs(prefs: android.content.SharedPreferences): RoutingDecision {
        val rawServer = prefs.getString("server", "").orEmpty().trim()
        val effectiveServer = if (rawServer == "0.0.0.0") "" else rawServer
        return RoutingPolicy.resolve(
            customSipServer = effectiveServer,
            customSipPort = prefs.getInt("port", 5060),
            customSipTls = prefs.getBoolean("sip_tls", false),
            pinnedLocalIp = prefs.getString("pinned_local_ip", LocalIpPinController.DEFAULT_PINNED_IP),
            localSipModeEnabled = prefs.getBoolean("local_sip_mode_enabled", false)
        )
    }

    private fun safeSignalStrength(tm: TelephonyManager?): android.telephony.SignalStrength? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { tm?.signalStrength }.getOrNull()
        } else {
            null
        }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                GatewayService.STATUS_ACTION -> {
                    val state = intent.getStringExtra("state") ?: return
                    val info = intent.getStringExtra("info") ?: ""
                    sipRegistered = intent.getBooleanExtra("registered", sipRegistered)
                    serviceCallStart = intent.getLongExtra("call_start", 0L)
                    currentCallIncoming = intent.getBooleanExtra("call_incoming", currentCallIncoming)
                    lastLocalPeer = intent.getStringExtra("local_peer") ?: lastLocalPeer
                    lastServiceLocalIp = intent.getStringExtra("local_ip") ?: lastServiceLocalIp
                    updateStatus(state, info)
                    refreshHomeDashboard()
                    refreshLocalSendCard()

                    val newOnlineSince = intent.getLongExtra("online_since", 0L)
                    if (newOnlineSince != onlineSince) {
                        onlineSince = newOnlineSince
                    }

                    val wasRunning = running
                    running = state != "STOPPED" && state != "ERROR"

                    val callActive = state in listOf(
                        "GSM_RINGING", "GSM_ANSWERED", "SIP_CALLING",
                        "SIP_RINGING", "BRIDGED", "GSM_DIALING", "TEARING_DOWN"
                    )
                    if (callActive != gsmCallActive) {
                        gsmCallActive = callActive
                    }

                    // No pop-up on an incoming call: the home view's live call
                    // card is the in-call UI now, and the old full-screen view
                    // appearing over it was just confusing.

                    if (inCallOpen) {
                        when (state) {
                            "GSM_DIALING" -> tvInCallStatus.text = "Calling..."
                            "GSM_ANSWERED", "SIP_CALLING" -> tvInCallStatus.text = "Connecting..."
                            "SIP_RINGING" -> tvInCallStatus.text = "Ringing..."
                            "BRIDGED" -> {
                                tvInCallStatus.text = "Connected"
                                tvInCallTimer.visibility = View.VISIBLE
                                startCallTimer()
                            }
                            "TEARING_DOWN" -> tvInCallStatus.text = "Ending..."
                            "IDLE" -> {
                                scheduleInCallClose()
                            }
                        }
                    }

                    appendLog("[$state] $info")
                }
                GatewayService.LOG_ACTION -> {
                    val msg = intent.getStringExtra("msg") ?: return
                    appendLog(msg)
                    if (msg.contains("FALLBACK")) {
                        showLocalFallbackDialogIfNeeded()
                    }
                    if (msg.startsWith("TERMINAL") ||
                        msg.startsWith("BATTERY") ||
                        msg.startsWith("SMS:") ||
                        msg.startsWith("CALL:") ||
                        msg.contains("Local Send") ||
                        msg.contains("FALLBACK")
                    ) {
                        if ((currentTab == "home" || currentTab == "config") && !logUiRefreshPending) {
                            logUiRefreshPending = true
                            netHandler.postDelayed(coalescedLogUiRefresh, 600L)
                        }
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("current_tab", currentTab)
        outState.putString("call_filter", callFilter)
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Portrait lock, enforced at runtime as well as in the manifest.
        // A priv-app APK replaced in place is not always re-parsed by
        // PackageManager, so the manifest's screenOrientation can silently
        // stay at its previous value (dumpsys reports UNSPECIFIED).  Asking
        // for it here is immune to that staleness.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        setContentView(R.layout.activity_main)
        ensureLinphoneAndLocalSendDefaults()

        // Always keep local fallback state listener registered across the entire Activity lifecycle
        com.callagent.gateway.gsm.GsmCallManager.onLocalFallbackStateChanged = { _, _ ->
            runOnUiThread {
                showLocalFallbackDialogIfNeeded()
            }
        }

        // Tab containers
        tabbedRoot = findViewById(R.id.tabbedRoot)
        tabHome = findViewById(R.id.tabHome)
        tabConfig = findViewById(R.id.tabConfig)
        findViewById<View>(R.id.cardHomeDashboard)?.setOnClickListener { openConfigView() }
        findViewById<View>(R.id.btnConfigBack).setOnClickListener { switchTab("home") }
        findViewById<View>(R.id.btnCfgSave).setOnClickListener { saveConfigFromView() }
        findViewById<View>(R.id.btnCfgClearRecents).setOnClickListener { confirmClearRecents() }
        setupPushConfigCard()
        tvHomeStatusPill = findViewById(R.id.tvHomeStatusPill)
        tvHomeTlsBadge = findViewById(R.id.tvHomeTlsBadge)
        tvHomeSrtpBadge = findViewById(R.id.tvHomeSrtpBadge)
        tvHomeMonitorBadge = findViewById(R.id.tvHomeMonitorBadge)
        tvHomeMonitorBadge.setOnClickListener { toggleCallMonitor() }
        refreshMonitorBadge()
        tvNetMobile = findViewById(R.id.tvNetMobile)
        tvNetWifi = findViewById(R.id.tvNetWifi)
        tvHomeLocalSendStatus = findViewById(R.id.tvHomeLocalSendStatus)
        btnHomeLocalSendToggle = findViewById(R.id.btnHomeLocalSendToggle)
        btnHomeTerminalPing = findViewById(R.id.btnHomeTerminalPing)
        btnHomeLocalSendToggle.setOnClickListener { toggleLocalSendFromHome() }
        btnHomeTerminalPing.setOnClickListener {
            startService(Intent(this, GatewayService::class.java).apply {
                action = GatewayService.ACTION_SEND_TERMINAL_WELCOME
            })
            Toast.makeText(
                this,
                "0000 (T-Switch Terminal) mesajı Zoiper'e gönderiliyor",
                Toast.LENGTH_SHORT
            ).show()
        }
        refreshLocalSendCard()
        setupKioskHomeCard()
        homeCallCard = findViewById(R.id.homeCallCard)
        tvHomeCallDirection = findViewById(R.id.tvHomeCallDirection)
        tvHomeCallTimer = findViewById(R.id.tvHomeCallTimer)
        tvHomeCallFrom = findViewById(R.id.tvHomeCallFrom)
        tvHomeCallTo = findViewById(R.id.tvHomeCallTo)
        btnHomeMute = findViewById(R.id.btnHomeMute)
        btnHomeSnoop = findViewById(R.id.btnHomeSnoop)
        btnHomeEnd = findViewById(R.id.btnHomeEnd)
        homeTrafficList = findViewById(R.id.homeTrafficList)
        tvHomeTrafficEmpty = findViewById(R.id.tvHomeTrafficEmpty)
        btnFilterAll = findViewById(R.id.btnFilterAll)
        btnFilterIncoming = findViewById(R.id.btnFilterIncoming)
        btnFilterOutgoing = findViewById(R.id.btnFilterOutgoing)
        btnFilterSms = findViewById(R.id.btnFilterSms)
        btnFilterAll.setOnClickListener { setCallFilter("all") }
        btnFilterIncoming.setOnClickListener { setCallFilter("in") }
        btnFilterOutgoing.setOnClickListener { setCallFilter("out") }
        btnFilterSms.setOnClickListener { setCallFilter("sms") }
        tvNetMobile.setOnClickListener { showLinkDetails(mobile = true) }
        tvNetWifi.setOnClickListener { showLinkDetails(mobile = false) }
        findViewById<View>(R.id.btnHomeMenu).setOnClickListener { openConfigView() }
        // Tapping the status pill retries the connection, the way the old
        // settings screen's reconnect button did.
        tvHomeStatusPill.setOnClickListener {
            if (gatewayOnline) {
                // Already registered — reconnecting would drop a working
                // registration and send SIP the server did not need.
                Toast.makeText(this, "Registered", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            appendLog("Reconnect requested")
            Toast.makeText(this, "Reconnecting…", Toast.LENGTH_SHORT).show()
            startService(Intent(this, GatewayService::class.java).apply {
                action = GatewayService.ACTION_RECONNECT
            })
        }
        btnHomeMute.setOnClickListener { toggleAgentMute() }
        btnHomeSnoop.setOnClickListener { toggleMonitor() }
        btnHomeEnd.setOnClickListener { endCallFromInCallScreen() }


        // Named and versioned at the top of settings.  It is the first thing
        // asked for when a change appears not to have taken, and this deploy
        // path can leave the running build and the file on disk disagreeing.
        findViewById<TextView>(R.id.tvCfgVersion).text = "v${BuildConfig.VERSION_NAME}"

        // Logs view — opened from the Settings header, back returns there
        // rather than to home, so the icon behaves like a drill-down.
        tabLogs = findViewById(R.id.tabLogs)
        findViewById<View>(R.id.btnCfgLogs).setOnClickListener { switchTab("logs") }
        findViewById<View>(R.id.btnLogsBack).setOnClickListener { switchTab("config") }
        findViewById<View>(R.id.btnLogsCopy).setOnClickListener { copyLog() }
        findViewById<View>(R.id.btnLogsClear).setOnClickListener { clearLog() }

        // Logs-view views
        tvLog = findViewById(R.id.tvLog)
        svLog = findViewById(R.id.svLog)

        // Clear the view, but keep whatever the service has buffered: onResume
        // drains it into the view a moment later.  Discarding it here threw
        // away exactly the lines worth reading — the ones from before anyone
        // opened the app, which is when the gateway runs unattended.
        tvLog.text = ""

        // In-call views
        inCallView = findViewById(R.id.inCallView)
        tvInCallStatus = findViewById(R.id.tvInCallStatus)
        tvInCallNumber = findViewById(R.id.tvInCallNumber)
        tvInCallTimer = findViewById(R.id.tvInCallTimer)
        btnInCallEnd = findViewById(R.id.btnInCallEnd)
        btnInCallEnd.setOnClickListener { endCallFromInCallScreen() }
        btnInCallMonitor = findViewById(R.id.btnInCallMonitor)
        btnInCallMonitor.setOnClickListener { toggleMonitor() }

        requestPermissions()
        requestBatteryOptimizationExemption()
        requestDefaultDialerRole()
        registerContactsObserver()
        preloadContactsAsync()

        // Nothing is visible until a tab is selected — switchTab() returns
        // early when the requested tab is already current, so the initial
        // state has to be applied explicitly.
        val restoredTab = savedInstanceState?.getString("current_tab") ?: "home"
        val restoredFilter = savedInstanceState?.getString("call_filter") ?: "all"
        if (restoredTab == "config") {
            openConfigView()
        } else {
            switchTab(restoredTab)
        }
        setCallFilter(restoredFilter)

        showLocalFallbackDialogIfNeeded()

        // Auto-start gateway if autoconnect enabled and credentials configured
        autoStartGateway()
    }

    private fun autoStartGateway() {
        if (running) return
        val prefs = getSharedPreferences("gateway", MODE_PRIVATE)
        if (!prefs.getBoolean("autoconnect", true)) return
        val decision = resolveRoutingFromPrefs(prefs)
        val isHybrid = decision.mode == RoutingMode.HYBRID_LOCAL_IP
        val isCloudOnly = decision.mode == RoutingMode.CLOUD_ONLY_LINPHONE
        val user = prefs.getString("user", "").orEmpty().trim()
        if (isCloudOnly && !com.callagent.gateway.localsend.PushWakeController.isConfigured(this)) return
        if (!isHybrid && !isCloudOnly && (decision.customServer.isEmpty() || user.isEmpty())) return
        val pass = prefs.getString("pass", "").orEmpty()
        GatewayService.start(
            this,
            if (isHybrid || isCloudOnly) "" else decision.customServer,
            decision.customPort,
            if ((isHybrid || isCloudOnly) && user.isEmpty()) "tswitch" else user,
            pass
        )
        running = true
        appendLog(
            when (decision.mode) {
                RoutingMode.CLOUD_ONLY_LINPHONE -> "Auto-starting Cloud-Only Linphone gateway (sip.linphone.org:5061 TLS)"
                RoutingMode.HYBRID_LOCAL_IP -> "Auto-starting Hybrid Local IP gateway (${decision.pinnedLocalIp}:5060)"
                RoutingMode.CUSTOM_SIP_WITH_PUSH_WAKE -> "Auto-starting custom SIP gateway: $user@${decision.customServer}:${decision.customPort}"
            }
        )
    }

    /** One-tap toggle on the Settings card to enable/disable secondary Local SIP mode. */
    private fun toggleLocalSendFromHome() {
        val prefs = getSharedPreferences("gateway", MODE_PRIVATE)
        val currentDecision = resolveRoutingFromPrefs(prefs)
        val currentlyHybrid = currentDecision.mode == RoutingMode.HYBRID_LOCAL_IP
        val nextLocalSip = !currentlyHybrid
        prefs.edit()
            .putBoolean("local_sip_mode_enabled", nextLocalSip)
            .putBoolean("local_send_enabled", nextLocalSip)
            .apply()
        findViewById<CheckBox>(R.id.cbCfgLocalSend)?.isChecked = nextLocalSip
        refreshLocalSendCard()
        refreshKioskCard()
        val msg = if (nextLocalSip) {
            "İkincil Yerel SIP Modu açıldı (${currentDecision.pinnedLocalIp}:5060)"
        } else {
            "Bulut Tek Hesap Modu aktif (sip.linphone.org TLS)"
        }
        appendLog(msg)
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        if (running) {
            startService(Intent(this, GatewayService::class.java).apply {
                action = GatewayService.ACTION_APPLY_CONFIG
            })
        } else {
            autoStartGateway()
        }
    }

    private fun refreshLocalSendCard() {
        if (!::tvHomeLocalSendStatus.isInitialized) return
        val prefs = getSharedPreferences("gateway", MODE_PRIVATE)
        val decision = resolveRoutingFromPrefs(prefs)
        val enabled = decision.mode == RoutingMode.HYBRID_LOCAL_IP
        val hs = HotspotHelper.detectHotspot(this)
        val ip = when {
            lastServiceLocalIp.isNotEmpty() && lastServiceLocalIp != "0.0.0.0" -> lastServiceLocalIp
            hs.active && hs.ipAddress != "0.0.0.0" -> hs.ipAddress
            hs.wifiIpAddress.isNotEmpty() -> hs.wifiIpAddress
            else -> decision.pinnedLocalIp
        }
        val crit = prefs.getInt("critical_battery_threshold", 15)
        if (enabled) {
            btnHomeLocalSendToggle.text = "AKTİF"
            btnHomeLocalSendToggle.backgroundTintList =
                ColorStateList.valueOf(Color.parseColor("#16A34A"))
            btnHomeLocalSendToggle.setTextColor(Color.WHITE)
            btnHomeTerminalPing.visibility = View.VISIBLE
            val peerDesc = if (lastLocalPeer.isNotEmpty()) {
                "Zoiper: $lastLocalPeer"
            } else if (hs.active) {
                "Hotspot Açık · İstemci bekleniyor"
            } else if (hs.wifiIpAddress.isNotEmpty()) {
                "WiFi (${hs.wifiIpAddress}) · Sabit: ${decision.pinnedLocalIp}"
            } else {
                "Hotspot/WiFi bekleniyor ($ip:5060)"
            }
            tvHomeLocalSendStatus.text =
                "SIP: $ip:5060 · $peerDesc · Kanal: 0000 · Kritik: ≤%$crit"
        } else {
            btnHomeLocalSendToggle.text = "YEREL'E DÖN"
            btnHomeLocalSendToggle.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent))
            btnHomeLocalSendToggle.setTextColor(ContextCompat.getColor(this, R.color.accent_on))
            btnHomeTerminalPing.visibility = View.GONE
            tvHomeLocalSendStatus.text =
                "Özel SIP Modu (${decision.customServer}:${decision.customPort}) · PushKit: Yalnızca Uyandırma"
        }
    }

    // ── Tab Navigation ───────────────────────────────────

    /**
     * Show one of the top-level views.  Navigation is the header menu on the
     * home view now; there is no bottom bar.
     */
    private fun switchTab(tab: String) {
        if (tab == currentTab) return
        currentTab = tab

        tabHome.visibility = if (tab == "home") View.VISIBLE else View.GONE
        findViewById<View>(R.id.tabDialer)?.visibility = if (tab == "dialer") View.VISIBLE else View.GONE
        findViewById<View>(R.id.tabContacts)?.visibility = if (tab == "contacts") View.VISIBLE else View.GONE
        tabConfig.visibility = if (tab == "config") View.VISIBLE else View.GONE
        tabLogs.visibility = if (tab == "logs") View.VISIBLE else View.GONE

        val activeBg = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent))
        val activeFg = ContextCompat.getColor(this, R.color.accent_on)
        val idleBg = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.btn_secondary))
        val idleFg = ContextCompat.getColor(this, R.color.text_primary)

        listOf(
            "home" to findViewById<Button>(R.id.btnNavHome),
            "dialer" to findViewById<Button>(R.id.btnNavDialer),
            "contacts" to findViewById<Button>(R.id.btnNavContacts),
            "config" to findViewById<Button>(R.id.btnNavConfig)
        ).forEach { (key, btn) ->
            val selected = (key == tab) || (key == "config" && tab == "logs")
            val fgColor = if (selected) activeFg else idleFg
            btn?.backgroundTintList = if (selected) activeBg else idleBg
            btn?.setTextColor(fgColor)
            if (btn != null) {
                androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(
                    btn,
                    ColorStateList.valueOf(fgColor)
                )
            }
        }

        when (tab) {
            "home" -> refreshHome()
            "config" -> {
                refreshLocalSendCard()
                refreshKioskCard()
            }
            "contacts" -> loadContactsList(findViewById<EditText>(R.id.etContactsSearch)?.text?.toString().orEmpty())
            "logs" -> {
                val buffered = GatewayService.logSnapshot()
                tvLog.text = if (buffered.isNotEmpty()) {
                    buffered.joinToString("\n", postfix = "\n")
                } else {
                    "(Henüz log kaydı yok)\n"
                }
                svLog.post { svLog.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
    }

    /**
     * Settings as a full screen rather than a dialog — there are enough fields
     * that a modal is cramped, and a screen gives room to explain them.
     */
    // ── Own number, per SIM ─────────────────────────────

    /** One active SIM, as Settings needs to describe it. */
    private data class SimInfo(val slot: Int, val subId: Int, val caption: String)

    /** Own-number fields on screen, paired with the SIM slot each belongs to.
     *  Slot -1 is the no-SIM-readable case, bound to the legacy key. */
    private val ownNumberFields = mutableListOf<Pair<Int, EditText>>()

    private fun ownNumberKey(slot: Int) =
        if (slot < 0) "own_number" else "own_number_slot_$slot"

    /**
     * The SIMs that are actually live, in slot order.
     *
     * Slots the hardware has but which hold no SIM are deliberately absent:
     * a triple-SIM handset carrying one SIM needs one number, not three
     * fields two of which can only ever be wrong.
     */
    @SuppressLint("MissingPermission")
    private fun activeSims(): List<SimInfo> = try {
        val sm = getSystemService(SubscriptionManager::class.java)
        val tm = getSystemService(TelephonyManager::class.java)
        (sm?.activeSubscriptionInfoList ?: emptyList()).map { info ->
            val slot = info.simSlotIndex
            val carrier = (info.carrierName ?: info.displayName ?: "").toString().trim()
            // The IMEI belongs to the radio, so it names the slot; the ICCID
            // names the card in it.  Either tells two otherwise identical
            // rows apart, so show whichever the platform will hand over.
            val ident = runCatching { tm?.getImei(slot) }.getOrNull()
                ?.takeIf { it.isNotBlank() }?.let { "IMEI $it" }
                ?: info.iccId?.takeIf { it.isNotBlank() }
                    ?.let { "ICCID \u2026${it.takeLast(6)}" }
                ?: ""
            SimInfo(
                slot = slot,
                subId = info.subscriptionId,
                caption = listOfNotNull(
                    "SIM ${slot + 1}",
                    carrier.ifEmpty { null },
                    ident.ifEmpty { null }
                ).joinToString("  \u00b7  ")
            )
        }.sortedBy { it.slot }
    } catch (e: Exception) {
        android.util.Log.w("MainActivity", "Could not enumerate SIMs: ${e.message}")
        emptyList()
    }

    private fun buildOwnNumberFields(prefs: android.content.SharedPreferences) {
        val container = findViewById<LinearLayout>(R.id.llCfgOwnNumbers)
        container.removeAllViews()
        ownNumberFields.clear()

        val sims = activeSims()
        if (sims.isEmpty()) {
            // Nothing readable — still offer one field on the legacy key, so a
            // device that will not describe its SIMs stays configurable.
            val row = layoutInflater.inflate(R.layout.item_sim_number, container, false)
            val et = row.findViewById<EditText>(R.id.etSimNumber)
            et.setText(prefs.getString("own_number", ""))
            row.findViewById<TextView>(R.id.tvSimCaption).text = "No active SIM detected"
            container.addView(row)
            ownNumberFields += -1 to et
            return
        }

        val lowest = sims.first().slot
        for (sim in sims) {
            val row = layoutInflater.inflate(R.layout.item_sim_number, container, false)
            val et = row.findViewById<EditText>(R.id.etSimNumber)
            val stored = prefs.getString(ownNumberKey(sim.slot), "").orEmpty()
            // First run after upgrading there are no per-slot values yet; the
            // one legacy number belongs to whichever SIM was in use, so offer
            // it on the lowest active slot rather than making it be retyped.
            et.setText(
                stored.ifEmpty {
                    if (sim.slot == lowest) prefs.getString("own_number", "").orEmpty() else ""
                }
            )
            row.findViewById<TextView>(R.id.tvSimCaption).text = sim.caption
            container.addView(row)
            ownNumberFields += sim.slot to et
        }
    }

    private fun ensureLinphoneAndLocalSendDefaults() {
        com.callagent.gateway.localsend.PushWakeController.ensureDefaults(this)
        val prefs = getSharedPreferences("gateway", MODE_PRIVATE)
        val ed = prefs.edit()
        var changed = false

        val rawSrv = prefs.getString("server", "").orEmpty().trim()
        // Blank, legacy callagent.pro, placeholder 0.0.0.0, or sip.linphone.org stay in HYBRID_LOCAL_IP
        val normalizedSrv = if (rawSrv.equals("callagent.pro", ignoreCase = true) ||
            rawSrv.equals("sip.linphone.org", ignoreCase = true) ||
            rawSrv == "0.0.0.0"
        ) {
            ""
        } else {
            rawSrv
        }
        if (normalizedSrv != rawSrv) {
            ed.putString("server", normalizedSrv)
            changed = true
        }

        val pinned = prefs.getString("pinned_local_ip", "").orEmpty().trim()
        if (pinned.isEmpty()) {
            ed.putString("pinned_local_ip", LocalIpPinController.DEFAULT_PINNED_IP)
            changed = true
        }

        val decision = RoutingPolicy.resolve(
            customSipServer = normalizedSrv,
            customSipPort = prefs.getInt("port", 5060),
            customSipTls = prefs.getBoolean("sip_tls", false),
            pinnedLocalIp = pinned.ifEmpty { LocalIpPinController.DEFAULT_PINNED_IP },
            localSipModeEnabled = prefs.getBoolean("local_sip_mode_enabled", false)
        )
        val shouldBeLocalSend = decision.mode == RoutingMode.HYBRID_LOCAL_IP
        if (prefs.getBoolean("local_send_enabled", !shouldBeLocalSend) != shouldBeLocalSend) {
            ed.putBoolean("local_send_enabled", shouldBeLocalSend)
            changed = true
        }
        if (changed) ed.apply()
    }

    private fun setupPushConfigCard() {
        val btnSave = findViewById<Button>(R.id.btnCfgPushSave) ?: return
        val btnTest = findViewById<Button>(R.id.btnCfgPushTest)

        btnSave.setOnClickListener {
            savePushFieldsFromUi()
            refreshPushConfigCard()
            refreshHomeDashboard()
            if (running) {
                startService(Intent(this, GatewayService::class.java).apply {
                    action = GatewayService.ACTION_APPLY_CONFIG
                })
            } else {
                autoStartGateway()
            }
            Toast.makeText(this, "Linphone PushKit bilgileri kaydedildi", Toast.LENGTH_SHORT).show()
        }

        btnTest?.setOnClickListener {
            savePushFieldsFromUi()
            refreshPushConfigCard()
            refreshHomeDashboard()
            if (!com.callagent.gateway.localsend.PushWakeController.isConfigured(this)) {
                Toast.makeText(
                    this,
                    "Önce gönderici SIP hesabı, şifresi ve alıcı hesabını girin",
                    Toast.LENGTH_LONG
                ).show()
                findViewById<EditText>(R.id.etCfgPushPusher)?.requestFocus()
            } else {
                val receiver = com.callagent.gateway.localsend.PushWakeController.getReceiverUser(this)
                val domain = com.callagent.gateway.localsend.PushWakeController.getDomain(this)
                appendLog("PushKit Test: $receiver@$domain uyandırılıyor (10sn)")
                Toast.makeText(
                    this,
                    "$domain üzerinden PushKit sinyali gönderiliyor…",
                    Toast.LENGTH_SHORT
                ).show()
                com.callagent.gateway.localsend.PushWakeController.triggerWakeAsync(
                    this,
                    "T-Switch Test",
                    holdMs = 10_000L,
                    onStatus = { statusMsg ->
                        runOnUiThread {
                            appendLog("PushKit: $statusMsg")
                            Toast.makeText(this, statusMsg, Toast.LENGTH_LONG).show()
                        }
                    }
                )
            }
        }
        refreshPushConfigCard()
    }

    private fun savePushFieldsFromUi() {
        val enabled = findViewById<CheckBox>(R.id.cbCfgPushEnabled)?.isChecked ?: true
        val domain = findViewById<EditText>(R.id.etCfgPushDomain)?.text?.toString()?.trim().orEmpty()
        val pusher = findViewById<EditText>(R.id.etCfgPushPusher)?.text?.toString()?.trim().orEmpty()
        val pass = findViewById<EditText>(R.id.etCfgPushPass)?.text?.toString()?.trim().orEmpty()
        val receiver = findViewById<EditText>(R.id.etCfgPushReceiver)?.text?.toString()?.trim().orEmpty()
        com.callagent.gateway.localsend.PushWakeController.saveConfig(
            this,
            enabled = enabled,
            domain = domain,
            pusherUser = pusher,
            pusherPass = pass,
            receiverUser = receiver
        )
        val prefs = getSharedPreferences("gateway", MODE_PRIVATE)
        if (prefs.getString("server", "") == "sip.linphone.org" && pass.isNotBlank()) {
            val curSipPass = prefs.getString("pass", "").orEmpty().trim()
            if (curSipPass.isEmpty() || curSipPass == "1234") {
                prefs.edit().putString("pass", pass).apply()
            }
            val etSipPass = findViewById<EditText>(R.id.etCfgPass)
            val uiSipPass = etSipPass?.text?.toString()?.trim().orEmpty()
            if (uiSipPass.isEmpty() || uiSipPass == "1234") {
                etSipPass?.setText(pass)
            }
        }
    }

    private fun refreshPushConfigCard() {
        val badge = findViewById<TextView>(R.id.tvCfgPushStatusBadge) ?: return
        val enabled = com.callagent.gateway.localsend.PushWakeController.isEnabled(this)
        val configured = com.callagent.gateway.localsend.PushWakeController.isConfigured(this)
        val hasUsers = com.callagent.gateway.localsend.PushWakeController.getPusherUser(this).isNotBlank() &&
            com.callagent.gateway.localsend.PushWakeController.getReceiverUser(this).isNotBlank()
        when {
            configured -> {
                badge.text = "Aktif ✅"
                badge.setTextColor(Color.parseColor("#34D399"))
            }
            enabled && !hasUsers -> {
                badge.text = "Hesap Girin ⚠️"
                badge.setTextColor(Color.parseColor("#FBBF24"))
            }
            enabled -> {
                badge.text = "Şifre Girin ⚠️"
                badge.setTextColor(Color.parseColor("#FBBF24"))
            }
            else -> {
                badge.text = "Kapalı"
                badge.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            }
        }
    }

    private fun openConfigView() {
        val prefs = getSharedPreferences("gateway", MODE_PRIVATE)
        val decision = resolveRoutingFromPrefs(prefs)
        val displayServer = if (decision.mode == RoutingMode.CUSTOM_SIP_WITH_PUSH_WAKE) decision.customServer else ""
        findViewById<EditText>(R.id.etCfgServer).setText(displayServer)
        findViewById<EditText>(R.id.etCfgPort).setText(prefs.getInt("port", 5060).toString())
        findViewById<EditText>(R.id.etCfgUser).setText(prefs.getString("user", ""))
        findViewById<EditText>(R.id.etCfgPass).setText(prefs.getString("pass", ""))
        findViewById<EditText>(R.id.etCfgPinnedIp)?.setText(decision.pinnedLocalIp)
        buildOwnNumberFields(prefs)

        findViewById<CheckBox>(R.id.cbCfgPushEnabled)?.isChecked =
            com.callagent.gateway.localsend.PushWakeController.isEnabled(this)
        findViewById<EditText>(R.id.etCfgPushDomain)?.setText(
            com.callagent.gateway.localsend.PushWakeController.getDomain(this)
        )
        findViewById<EditText>(R.id.etCfgPushPusher)?.setText(
            com.callagent.gateway.localsend.PushWakeController.getPusherUser(this)
        )
        findViewById<EditText>(R.id.etCfgPushPass)?.setText(
            com.callagent.gateway.localsend.PushWakeController.getPassword(this)
        )
        findViewById<EditText>(R.id.etCfgPushReceiver)?.setText(
            com.callagent.gateway.localsend.PushWakeController.getReceiverUser(this)
        )
        refreshPushConfigCard()

        // Theme mode (System / Light / Dark)
        val rgTheme = findViewById<RadioGroup>(R.id.rgCfgTheme)
        rgTheme.setOnCheckedChangeListener(null)
        findViewById<RadioButton>(
            when (prefs.getString("theme_mode", "system")) {
                "light" -> R.id.rbThemeLight
                "dark" -> R.id.rbThemeDark
                else -> R.id.rbThemeSystem
            }
        ).isChecked = true
        rgTheme.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rbThemeLight -> "light"
                R.id.rbThemeDark -> "dark"
                else -> "system"
            }
            prefs.edit().putString("theme_mode", mode).apply()
            GatewayApp.applyThemeMode(mode)
        }

        // Local Send & Virtual Command Terminal settings (synced from RoutingPolicy)
        findViewById<CheckBox>(R.id.cbCfgLocalSend).isChecked =
            prefs.getBoolean("local_sip_mode_enabled", false)
        findViewById<EditText>(R.id.etCfgCritBattery).setText(
            prefs.getInt("critical_battery_threshold", 15).toString()
        )
        findViewById<EditText>(R.id.etCfgCustomCmds).setText(
            prefs.getString("custom_commands", "!uptime = uptime\n!ip = ip -4 addr show")
        )

        findViewById<CheckBox>(R.id.cbCfgAutoconnect).isChecked =
            prefs.getBoolean("autoconnect", true)
        findViewById<CheckBox>(R.id.cbCfgUseStun).isChecked =
            prefs.getBoolean("use_stun", true)
        findViewById<CheckBox>(R.id.cbCfgTranslit).isChecked =
            prefs.getBoolean("translit_ascii", false)
        findViewById<CheckBox>(R.id.cbCfgCallMonitor).isChecked =
            prefs.getBoolean("call_monitor", false)
        val cbTls = findViewById<CheckBox>(R.id.cbCfgTls)
        val cbSrtp = findViewById<CheckBox>(R.id.cbCfgSrtp)
        cbTls.isChecked = prefs.getBoolean("sip_tls", false)
        cbSrtp.isChecked = prefs.getBoolean("srtp_enabled", false)

        // SRTP follows TLS in the UI as well as in the code.  The setting is
        // disabled rather than hidden so it is visible that audio encryption
        // exists and what it depends on -- a hidden control just looks like a
        // missing feature.
        fun syncSrtpEnabled() {
            cbSrtp.isEnabled = cbTls.isChecked
            findViewById<TextView>(R.id.tvCfgSrtpHint).text = if (cbTls.isChecked) {
                "SDES-keyed SRTP (RFC 3711). Audio is encrypted when the server agrees; " +
                    "if it answers without SRTP the call continues unencrypted and the log says so."
            } else {
                "Requires TLS. The keys travel inside the SIP signalling, so over plain UDP " +
                    "they would be readable by anyone on the path."
            }
        }
        syncSrtpEnabled()
        cbTls.setOnCheckedChangeListener { _, checked ->
            syncSrtpEnabled()
            // Move the port with the transport, the way every other SIP client
            // does.  TLS on 5060 does not fail cleanly: the plaintext port
            // never answers a handshake, so it hangs until "SSL handshake
            // timed out" -- which reads as a certificate or network fault and
            // sends you looking in entirely the wrong place.
            // Only the two well-known defaults are touched; a custom port is
            // left exactly as typed.
            val portField = findViewById<EditText>(R.id.etCfgPort)
            val current = portField.text.toString().trim().toIntOrNull()
            if (checked && current == 5060) {
                portField.setText("5061")
                Toast.makeText(this, "Port switched to 5061 for TLS", Toast.LENGTH_SHORT).show()
            } else if (!checked && current == 5061) {
                portField.setText("5060")
                Toast.makeText(this, "Port switched back to 5060", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<RadioButton>(
            when (prefs.getString("codec", "g722")) {
                "g711" -> R.id.rbCodecG711
                "both" -> R.id.rbCodecBoth
                else -> R.id.rbCodecG722
            }
        ).isChecked = true

        // -3..+3 as a 0..6 slider, so 0 sits in the middle.
        val agentVol = findViewById<SeekBar>(R.id.sbCfgAgentVolume)
        val agentVolLabel = findViewById<TextView>(R.id.tvCfgAgentVolume)
        fun stepText(step: Int) = if (step > 0) "+$step" else step.toString()
        agentVol.progress = prefs.getInt("agent_vol_step", 0).coerceIn(-3, 3) + 3
        agentVolLabel.text = stepText(agentVol.progress - 3)
        agentVol.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                agentVolLabel.text = stepText(value - 3)
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })

        refreshLocalSendCard()
        refreshKioskCard()
        switchTab("config")
    }

    private fun setupKioskHomeCard() {
        val btnKiosk = findViewById<Button>(R.id.btnHomeKioskToggle) ?: return
        val btnDebloat = findViewById<Button>(R.id.btnHomeDebloat)
        val btnGb = findViewById<Button>(R.id.btnHomeQueryGb)
        val btnScreenOff = findViewById<Button>(R.id.btnHomeScreenOff)

        btnKiosk.setOnClickListener {
            val next = !com.callagent.gateway.localsend.KioskController.isKioskEnabled(this)
            Toast.makeText(
                this,
                if (next) "Kiosk Modu & Ana Ekran aktif ediliyor…" else "Kiosk Modu kapatılıyor…",
                Toast.LENGTH_SHORT
            ).show()
            kotlin.concurrent.thread(name = "ui-kiosk-toggle") {
                val report = com.callagent.gateway.localsend.KioskController.applyKioskMode(
                    this,
                    enabled = next,
                    turnScreenOffNow = false
                )
                runOnUiThread {
                    appendLog(report)
                    refreshKioskCard()
                    Toast.makeText(
                        this,
                        if (next) "Kiosk & Launcher Modu Aktif" else "Kiosk Modu Kapatıldı",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        btnDebloat?.setOnClickListener {
            Toast.makeText(this, "GM 8 Debloat & Hızlandırma çalışıyor…", Toast.LENGTH_SHORT).show()
            kotlin.concurrent.thread(name = "ui-debloat") {
                val report = com.callagent.gateway.localsend.KioskController.debloatAndBoost(this)
                runOnUiThread {
                    appendLog(report)
                    refreshKioskCard()
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Debloat & Hızlandırma")
                        .setMessage(report)
                        .setPositiveButton("Tamam", null)
                        .show()
                }
            }
        }

        btnGb?.setOnClickListener {
            kotlin.concurrent.thread(name = "ui-gb-query") {
                val msg = com.callagent.gateway.localsend.LocalSendController.queryTurkcellRemainingGb(this)
                runOnUiThread {
                    appendLog(msg)
                    Toast.makeText(this, msg.lineSequence().firstOrNull() ?: "SMS gönderildi", Toast.LENGTH_LONG).show()
                }
            }
        }

        btnScreenOff?.setOnClickListener {
            Toast.makeText(this, "Ekran kapatılıyor…", Toast.LENGTH_SHORT).show()
            kotlin.concurrent.thread(name = "ui-screen-off") {
                Thread.sleep(250)
                com.callagent.gateway.localsend.KioskController.setScreenOn(this, false)
            }
        }

        refreshKioskCard()
        setupBottomNavAndDialer()
        setupLocalInCallScreen()
    }

    private fun refreshKioskCard() {
        val tvTitle = findViewById<TextView>(R.id.tvHomeKioskTitle) ?: return
        val tvSub = findViewById<TextView>(R.id.tvHomeKioskSub) ?: return
        val btnKiosk = findViewById<Button>(R.id.btnHomeKioskToggle) ?: return

        val kioskOn = com.callagent.gateway.localsend.KioskController.isKioskEnabled(this)
        val isHome = com.callagent.gateway.localsend.KioskController.isDefaultHomeLauncher(this)
        val debloated = com.callagent.gateway.localsend.KioskController.getDebloatedCount(this)
        val hs = HotspotHelper.detectHotspot(this)
        val ip = hs.ipAddress.takeIf { it != "0.0.0.0" } ?: lastServiceLocalIp.ifEmpty { "—" }

        tvTitle.text = if (kioskOn) {
            "Kiosk Launcher [AKTİF${if (isHome) " · ANA EKRAN" else ""}]"
        } else {
            "Kiosk Launcher & Otomasyon"
        }

        val debloatTag = if (debloated > 0) "Debloat: $debloated paket" else "Debloat: Hazır"
        tvSub.text = "tswitch.local → $ip · Fallback: Açık · $debloatTag"

        if (kioskOn) {
            btnKiosk.text = "Kiosk Açık"
            btnKiosk.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#16A34A"))
            btnKiosk.setTextColor(Color.WHITE)
        } else {
            btnKiosk.text = "Kiosk Aç"
            btnKiosk.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent))
            btnKiosk.setTextColor(ContextCompat.getColor(this, R.color.accent_on))
        }
    }

    /**
     * Empty the traffic list — calls and messages together, since both are
     * rows in the same log.
     *
     * Confirmed first: it is not recoverable, and the log is the only record
     * the gateway keeps of what it has handled.
     */
    private fun confirmClearRecents() {
        val count = try { CallLogStore.getEntries(this).size } catch (_: Exception) { 0 }
        if (count == 0) {
            Toast.makeText(this, "Nothing to clear", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Clear recents?")
            .setMessage("Removes all $count calls and messages from the list. This cannot be undone.")
            .setPositiveButton("Clear") { _, _ ->
                // Off the UI thread: clearing rewrites the stored blob, and
                // the list is rebuilt from disk straight afterwards.
                Thread {
                    try { CallLogStore.clear(this) } catch (_: Exception) {}
                    runOnUiThread {
                        if (currentTab == "home") refreshHome()
                        Toast.makeText(this, "Recents cleared", Toast.LENGTH_SHORT).show()
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveConfigFromView() {
        // Save PushKit fields BEFORE reading etCfgPass so any synced password is never overwritten.
        savePushFieldsFromUi()
        refreshPushConfigCard()

        val rawServer = findViewById<EditText>(R.id.etCfgServer).text.toString().trim()
        val port = findViewById<EditText>(R.id.etCfgPort).text.toString().trim().toIntOrNull() ?: 5060
        val user = findViewById<EditText>(R.id.etCfgUser).text.toString().trim()
        val pass = findViewById<EditText>(R.id.etCfgPass).text.toString().trim()
        val pinnedIpInput = findViewById<EditText>(R.id.etCfgPinnedIp)?.text?.toString()?.trim().orEmpty()
        // The lowest active SIM's number is the one everything that does not
        // know which SIM it is dealing with will use.
        val own = ownNumberFields.firstOrNull()?.second?.text?.toString()?.trim().orEmpty()
        val auto = findViewById<CheckBox>(R.id.cbCfgAutoconnect).isChecked
        val useStun = findViewById<CheckBox>(R.id.cbCfgUseStun).isChecked
        val translit = findViewById<CheckBox>(R.id.cbCfgTranslit).isChecked
        val callMonitor = findViewById<CheckBox>(R.id.cbCfgCallMonitor).isChecked
        val tls = findViewById<CheckBox>(R.id.cbCfgTls).isChecked
        // Stored as asked for, but only ever acted on with TLS — so turning
        // TLS off and on again does not silently lose the audio setting.
        val srtp = findViewById<CheckBox>(R.id.cbCfgSrtp).isChecked
        val agentVolStep = findViewById<SeekBar>(R.id.sbCfgAgentVolume).progress - 3
        val codec = when (findViewById<RadioGroup>(R.id.rgCfgCodec).checkedRadioButtonId) {
            R.id.rbCodecG711 -> "g711"
            R.id.rbCodecBoth -> "both"
            else -> "g722"
        }
        val themeMode = when (findViewById<RadioGroup>(R.id.rgCfgTheme).checkedRadioButtonId) {
            R.id.rbThemeLight -> "light"
            R.id.rbThemeDark -> "dark"
            else -> "system"
        }
        val critBattery = findViewById<EditText>(R.id.etCfgCritBattery)
            .text.toString().trim().toIntOrNull()?.coerceIn(1, 95) ?: 15
        val customCmds = findViewById<EditText>(R.id.etCfgCustomCmds).text.toString().trim()

        val localSipEnabled = findViewById<CheckBox>(R.id.cbCfgLocalSend)?.isChecked ?: false
        val decision = RoutingPolicy.resolve(
            customSipServer = if (rawServer == "0.0.0.0") "" else rawServer,
            customSipPort = port,
            customSipTls = tls,
            pinnedLocalIp = pinnedIpInput,
            localSipModeEnabled = localSipEnabled
        )
        val isHybrid = decision.mode == RoutingMode.HYBRID_LOCAL_IP
        val isCloudOnly = decision.mode == RoutingMode.CLOUD_ONLY_LINPHONE
        val storedServer = if (isHybrid || isCloudOnly) "" else decision.customServer

        if (!isHybrid && !isCloudOnly && (storedServer.isEmpty() || user.isEmpty())) {
            Toast.makeText(
                this,
                "Özel SIP sunucusu için kullanıcı adı gerekli (veya Bulut/Yerel mod için sunucuyu boş bırakın)",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        getSharedPreferences("gateway", MODE_PRIVATE).edit()
            .putString("server", storedServer)
            .putInt("port", decision.customPort)
            .putString("user", user)
            .putString("pass", pass)
            .putString("pinned_local_ip", decision.pinnedLocalIp)
            .putBoolean("local_sip_mode_enabled", localSipEnabled)
            .putString("own_number", own)
            .also { ed ->
                ownNumberFields.forEach { (slot, et) ->
                    ed.putString(ownNumberKey(slot), et.text.toString().trim())
                }
            }
            .putBoolean("autoconnect", auto)
            .putBoolean("use_stun", useStun)
            .putBoolean("translit_ascii", translit)
            .putBoolean("call_monitor", callMonitor)
            .putBoolean("sip_tls", if (isHybrid) tls else if (isCloudOnly) true else decision.customUseTls)
            .putBoolean("srtp_enabled", srtp)
            .putString("codec", codec)
            .putInt("agent_vol_step", agentVolStep)
            .putString("theme_mode", themeMode)
            .putBoolean("local_send_enabled", isHybrid)
            .putInt("critical_battery_threshold", critBattery)
            .putString("custom_commands", customCmds)
            .apply()
        GatewayApp.applyThemeMode(themeMode)
        refreshLocalSendCard()
        appendLog(
            "Config saved: mode=${decision.mode} pinned=${decision.pinnedLocalIp} " +
                "custom=${storedServer.ifEmpty { "none" }}:${decision.customPort} " +
                "(user=${user.ifEmpty { "tswitch" }}, critBat=$critBattery%, " +
                "theme=$themeMode, own=${own.ifEmpty { "auto" }}, " +
                "codec=$codec, stun=${if (useStun) "on" else "off"}, " +
                "tls=${if (!isHybrid && decision.customUseTls) "on" else "off"}, " +
                "srtp=${if (!isHybrid && srtp && decision.customUseTls) "on" else "off"}, " +
                "ascii=${if (translit) "on" else "off"}, " +
                "monitor=${if (callMonitor) "on" else "off"}, " +
                "agent volume ${if (agentVolStep > 0) "+$agentVolStep" else "$agentVolStep"})"
        )
        Toast.makeText(this, "Kaydedildi — yeniden bağlanılıyor", Toast.LENGTH_SHORT).show()
        // Apply immediately rather than waiting for the next restart.  This
        // has to rebuild the client, not just re-register: the server, port,
        // credentials and STUN choice are all read at bring-up.
        if (running) {
            startService(Intent(this, GatewayService::class.java).apply {
                action = GatewayService.ACTION_APPLY_CONFIG
            })
        } else {
            GatewayService.start(
                this,
                storedServer,
                decision.customPort,
                if (isHybrid && user.isEmpty()) "tswitch" else user,
                pass
            )
            running = true
        }
        switchTab("home")
    }

    /** Cut the agent's audio to the caller.  The call stays up; this only
     *  silences what the agent is sending, for when it says something wrong. */
    private fun toggleAgentMute() {
        agentMuted = !agentMuted
        startService(Intent(this, GatewayService::class.java).apply {
            action = GatewayService.ACTION_MUTE_AGENT
            putExtra(GatewayService.EXTRA_MUTE_ON, agentMuted)
        })
        setCallButtonState(
            btnHomeMute,
            if (agentMuted) "Unmute" else "Mute",
            if (agentMuted) R.drawable.ic_fa_volume_high else R.drawable.ic_fa_volume_xmark
        )
        appendLog(if (agentMuted) "Agent muted to caller" else "Agent unmuted")
    }

    /**
     * Show or hide the active-call card and keep the header pill in step with
     * the gateway's state.
     */
    private fun updateHomeCall(state: String, info: String) {
        if (!::homeCallCard.isInitialized) return

        val cfg = getSharedPreferences("gateway", MODE_PRIVATE)
        val decision = resolveRoutingFromPrefs(cfg)
        val localSendOn = decision.mode == RoutingMode.HYBRID_LOCAL_IP
        // "Online" means registered (or listening in Local Send mode), not merely
        // running: while connecting or retrying the gateway cannot take a call,
        // and the pill should say so — it is also the tap target for a manual retry.
        val online = when (state) {
            "STOPPED", "ERROR", "STARTING" -> false
            "IDLE" -> sipRegistered || localSendOn
            else -> true            // any call state means registration held
        }
        gatewayOnline = online
        val connecting = !online && (state == "STARTING" || state == "IDLE")
        val pillColor = Color.parseColor(
            when { online -> "#34D399"; connecting -> "#FBBF24"; else -> "#F87171" }
        )
        androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(
            tvHomeStatusPill, ColorStateList.valueOf(pillColor)
        )
        tvHomeStatusPill.text = when {
            online && localSendOn -> "Aktif · Yerel"
            online -> "Kayıtlı"
            connecting -> "Bağlanıyor"
            else -> "Kapalı"
        }
        tvHomeStatusPill.setTextColor(pillColor)
        tvHomeStatusPill.contentDescription =
            when { online -> "Online"; connecting -> "Connecting"; else -> "Offline" }

        // Only show TLS/SRTP badges when active SIP mode actually uses TLS/SRTP
        // (not in HYBRID_LOCAL_IP local UDP mode).
        val tlsOn = decision.mode == RoutingMode.CLOUD_ONLY_LINPHONE || (!localSendOn && decision.customUseTls)
        tvHomeTlsBadge.visibility = if (tlsOn) View.VISIBLE else View.GONE

        tvHomeSrtpBadge.visibility =
            if (tlsOn && cfg.getBoolean("srtp_enabled", false)) View.VISIBLE else View.GONE

        refreshMonitorBadge()

        if (state == "BRIDGED") {
            homeCallCard.visibility = View.VISIBLE
            val number = com.callagent.gateway.gsm.GsmCallManager.currentNumber ?: info
            tvHomeCallFrom.text = number
            val dest = getSharedPreferences("gateway", MODE_PRIVATE)
                .getString("own_number", "") ?: ""
            tvHomeCallTo.text = if (dest.isNotEmpty()) "Connected to $dest" else "Connected"
            tvHomeCallDirection.text = if (currentCallIncoming) "GSM → SIP" else "SIP → GSM"
            startCallTimer()
        } else {
            homeCallCard.visibility = View.GONE
            if (!inCallOpen) {
                callStartTime = 0L
                callTimerHandler.removeCallbacks(callTimerRunnable)
            }
            // Mute is per-call; do not carry it into the next one.
            if (agentMuted) {
                agentMuted = false
                if (::btnHomeMute.isInitialized) {
                    setCallButtonState(btnHomeMute, "Mute", R.drawable.ic_fa_volume_xmark)
                }
            }
            // Snoop likewise — the monitor lives with the RTP session and dies
            // with the call, but the flag was only ever cleared by the old
            // full-screen in-call view, which nothing opens any more.  So the
            // button came up saying "Stop" on the next call and the first tap
            // turned off something that was already off.
            if (monitoring) {
                monitoring = false
                updateMonitorButtons()
            }
            renderHomeTraffic()
        }
    }

    /** Repaint the home view from current state. */
    private fun refreshHome() {
        renderHomeTraffic()
        refreshNetworkInfo()
        refreshHomeDashboard()
        refreshLocalSendCard()
        refreshKioskCard()
    }

    @SuppressLint("MissingPermission")
    private fun refreshHomeDashboard() {
        val tvSip = findViewById<TextView>(R.id.tvDashSipState) ?: return
        val tvPush = findViewById<TextView>(R.id.tvDashPushState)
        val tvSim = findViewById<TextView>(R.id.tvDashSimBattery)
        val tvSync = findViewById<TextView>(R.id.tvDashSyncBadge)

        val prefs = getSharedPreferences("gateway", MODE_PRIVATE)
        val decision = resolveRoutingFromPrefs(prefs)
        val localSend = decision.mode == RoutingMode.HYBRID_LOCAL_IP
        val hs = HotspotHelper.detectHotspot(this)
        val ip = when {
            lastServiceLocalIp.isNotEmpty() && lastServiceLocalIp != "0.0.0.0" -> lastServiceLocalIp
            hs.active && hs.ipAddress != "0.0.0.0" -> hs.ipAddress
            hs.wifiIpAddress.isNotEmpty() -> hs.wifiIpAddress
            else -> decision.pinnedLocalIp
        }

        tvSip.text = when (decision.mode) {
            RoutingMode.HYBRID_LOCAL_IP -> {
                val peerInfo = when {
                    lastLocalPeer.isNotEmpty() -> "Bağlı: $lastLocalPeer ✅"
                    hs.connectedClients.isNotEmpty() -> "İstemci: ${hs.connectedClients.first()} · Kayıt bekliyor"
                    else -> "Dinleniyor ($ip:5060)"
                }
                "SIP / Hibrit Yerel: $ip:5060 · $peerInfo"
            }
            RoutingMode.CLOUD_ONLY_LINPHONE -> {
                val pushDomain = prefs.getString("push_domain", "sip.linphone.org")
                    .orEmpty().trim().ifEmpty { "sip.linphone.org" }
                val pushUser = prefs.getString("push_pusher_user", "").orEmpty().trim()
                    .ifEmpty { prefs.getString("user", "").orEmpty().trim() }
                "Bulut SIP (Tek Hesap): $pushUser@$pushDomain:5061 · ${if (gatewayOnline) "Kayıtlı ✅" else "Bağlanıyor…"}"
            }
            RoutingMode.CUSTOM_SIP_WITH_PUSH_WAKE -> {
                val usr = prefs.getString("user", "").orEmpty()
                "Özel SIP: $usr@${decision.customServer}:${decision.customPort} · ${if (gatewayOnline) "Kayıtlı ✅" else "Bağlanıyor…"}"
            }
        }

        tvPush?.text = "PushKit: ${com.callagent.gateway.localsend.PushWakeController.dashboardSummary(this)}"

        val tm = runCatching { getSystemService(TELEPHONY_SERVICE) as TelephonyManager }.getOrNull()
        val op = tm?.networkOperatorName?.takeIf { it.isNotBlank() } ?: "SIM"
        val rawNetType = tm?.dataNetworkType?.takeIf { it != TelephonyManager.NETWORK_TYPE_UNKNOWN }
            ?: tm?.voiceNetworkType
            ?: TelephonyManager.NETWORK_TYPE_UNKNOWN
        val netLabel = networkTypeName(rawNetType).takeIf { it != "Unknown" } ?: "GSM"
        val dbm = signalDbm(safeSignalStrength(tm), tm)
        val dbmStr = if (dbm != null && dbm != Int.MAX_VALUE) " ${dbm}dBm" else ""
        val simNum = prefs.getString("own_number_slot_0", "").orEmpty()
            .ifEmpty { prefs.getString("own_number", "").orEmpty() }
            .ifEmpty { "Numara tanımsız" }

        val stickyBat = runCatching {
            registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val level = stickyBat?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = (stickyBat?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100).coerceAtLeast(1)
        val pct = if (level >= 0) (level * 100) / scale else -1
        val plugged = (stickyBat?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val batStr = if (pct >= 0) "Pil %$pct${if (plugged) " ⚡" else ""}" else "Pil —"

        tvSim?.text = "Hat & Pil: $op $netLabel$dbmStr · $simNum · $batStr"
        tvSync?.text = "● Canlı ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}"
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun signalDbm(
        ss: android.telephony.SignalStrength?,
        tm: android.telephony.TelephonyManager? = null
    ): Int? {
        if (ss != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ss.cellSignalStrengths.firstOrNull()?.dbm?.takeIf { it != Int.MAX_VALUE }?.let {
                    cachedSignalDbm = it
                    return it
                }
            }
            val asu = ss.gsmSignalStrength
            if (asu in 0..31) {
                val dbm = -113 + 2 * asu
                cachedSignalDbm = dbm
                return dbm
            }
            // On Android 8/9 LTE, gsmSignalStrength is 99 while SignalStrength.getDbm() holds LTE RSRP.
            val reflected = runCatching {
                ss.javaClass.getMethod("getDbm").invoke(ss) as? Int
            }.getOrNull()
            if (reflected != null && reflected in -150..-30) {
                cachedSignalDbm = reflected
                return reflected
            }
        }
        val isMainThread = Looper.myLooper() == Looper.getMainLooper()
        if (isMainThread) {
            val now = System.currentTimeMillis()
            if (now - lastSignalProbeMs > 10_000L) {
                lastSignalProbeMs = now
                kotlin.concurrent.thread(name = "signal-probe", isDaemon = true) {
                    val cellDbm = runCatching {
                        val mgr = tm ?: (getSystemService(TELEPHONY_SERVICE) as? android.telephony.TelephonyManager)
                        val cells = mgr?.allCellInfo
                        val cell = cells?.firstOrNull { it.isRegistered } ?: cells?.firstOrNull()
                        when (cell) {
                            is CellInfoLte -> cell.cellSignalStrength.dbm
                            is CellInfoWcdma -> cell.cellSignalStrength.dbm
                            is CellInfoGsm -> cell.cellSignalStrength.dbm
                            else -> null
                        }
                    }.getOrNull()
                    if (cellDbm != null && cellDbm in -150..-30) {
                        cachedSignalDbm = cellDbm
                    }
                }
            }
            return cachedSignalDbm
        }
        val cellDbm = runCatching {
            val mgr = tm ?: (getSystemService(TELEPHONY_SERVICE) as? android.telephony.TelephonyManager)
            val cells = mgr?.allCellInfo
            val cell = cells?.firstOrNull { it.isRegistered } ?: cells?.firstOrNull()
            when (cell) {
                is CellInfoLte -> cell.cellSignalStrength.dbm
                is CellInfoWcdma -> cell.cellSignalStrength.dbm
                is CellInfoGsm -> cell.cellSignalStrength.dbm
                else -> null
            }
        }.getOrNull()
        return cellDbm?.takeIf { it in -150..-30 }?.also { cachedSignalDbm = it } ?: cachedSignalDbm
    }

    @SuppressLint("MissingPermission")
    private fun refreshNetworkInfo() {
        if (!::tvNetMobile.isInitialized) return

        tvNetMobile.text = try {
            val tm = getSystemService(TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            val name = tm.networkOperatorName?.ifEmpty { "Servis Yok" } ?: "Servis Yok"
            val netType = tm.dataNetworkType.takeIf {
                it != android.telephony.TelephonyManager.NETWORK_TYPE_UNKNOWN
            } ?: tm.voiceNetworkType
            val type = when (netType) {
                android.telephony.TelephonyManager.NETWORK_TYPE_NR -> "5G"
                android.telephony.TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
                android.telephony.TelephonyManager.NETWORK_TYPE_HSPAP,
                android.telephony.TelephonyManager.NETWORK_TYPE_HSPA,
                android.telephony.TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
                android.telephony.TelephonyManager.NETWORK_TYPE_EDGE,
                android.telephony.TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
                android.telephony.TelephonyManager.NETWORK_TYPE_UNKNOWN -> "GSM"
                else -> "GSM"
            }
            val dbm = signalDbm(safeSignalStrength(tm), tm)
            if (dbm != null && dbm != Int.MAX_VALUE) "$type $name ${dbm}dBm"
            else "$type $name"
        } catch (e: Exception) {
            "mobile: n/a"
        }

        tvNetWifi.text = try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
            val cm = getSystemService(ConnectivityManager::class.java)
            val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            val onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            val hs = HotspotHelper.detectHotspot(this)
            val prefs = getSharedPreferences("gateway", MODE_PRIVATE)
            val decision = resolveRoutingFromPrefs(prefs)
            val isHybrid = decision.mode == RoutingMode.HYBRID_LOCAL_IP
            @Suppress("DEPRECATION")
            val info = wm.connectionInfo
            when {
                hs.active -> {
                    val peerTag = if (lastLocalPeer.isNotEmpty()) "Bağlı" else decision.pinnedLocalIp
                    "Hotspot ${hs.ipAddress} · $peerTag"
                }
                hs.wifiIpAddress.isNotEmpty() || (wm.isWifiEnabled && onWifi) -> {
                    @Suppress("DEPRECATION")
                    val raw = info?.ssid?.trim('"').orEmpty()
                    val ssid =
                        if (raw.isEmpty() || raw.contains("unknown", true)) "WiFi" else raw
                    @Suppress("DEPRECATION")
                    val freq = info?.frequency ?: 0
                    @Suppress("DEPRECATION")
                    val speed = info?.linkSpeed ?: -1
                    @Suppress("DEPRECATION")
                    val rssi = info?.rssi ?: 0
                    val band = if (freq > 4000) "5G" else "2.4G"
                    val ipTag = if (isHybrid) decision.pinnedLocalIp else hs.wifiIpAddress
                    if (ipTag.isNotEmpty()) "$ssid $band ${rssi}dBm · $ipTag"
                    else if (speed > 0) "$ssid $band ${speed}Mbps ${rssi}dBm"
                    else "$ssid $band ${rssi}dBm"
                }
                !wm.isWifiEnabled -> {
                    if (isHybrid) "${decision.pinnedLocalIp}:5060 · Yerel"
                    else "WiFi Kapalı"
                }
                else -> {
                    if (isHybrid) "${decision.pinnedLocalIp}:5060 · Yerel"
                    else "WiFi Bağlı Değil"
                }
            }
        } catch (e: Exception) {
            "wifi: n/a"
        }
    }

    /**
     * Everything we can learn about one of the two links.
     *
     * What the framework can answer is shown immediately; the parts that need
     * a shell — MAC addresses, cell identity — and the pings are appended as
     * they arrive, so the dialog is never blank while a ping runs.
     *
     * SSID, BSSID and cell identity are gated behind location permission for
     * an ordinary app.  This one has root instead, so it reads them from the
     * system rather than holding a permission a gateway has no business with.
     */
    /**
     * Everything known about one message, on tapping its row.
     *
     * Read from the call-log entry rather than [SmsOutbox]: the outbox is
     * pruned as soon as a message is finally reported, so for exactly the
     * messages that succeeded it holds nothing.  The outbox is still consulted
     * for one that is mid-flight, and for rows written before the log carried
     * these fields.
     */
    private fun showSmsDetails(entry: CallLogEntry) {
        val outgoing = entry.direction != "IN"
        val own = getSharedPreferences("gateway", MODE_PRIVATE)
            .getString("own_number", "").orEmpty()

        // A message still in flight has fresher paperwork in the outbox.
        val live = entry.smsId.takeIf { it.isNotEmpty() }
            ?.let { runCatching { SmsOutbox.get(this, it) }.getOrNull() }

        val parts = maxOf(entry.parts, live?.parts ?: 0)
        val status = entry.status.ifEmpty {
            when {
                live == null -> ""
                live.deliveredOk > 0 -> "delivered"
                live.sentOk > 0 -> "sent"
                live.dispatched -> "pending"
                else -> "queued"
            }
        }
        val error = entry.error.ifEmpty { live?.lastError.orEmpty() }

        fun dash(v: String) = v.ifEmpty { "\u2014" }
        fun row(label: String, value: String) =
            label.padEnd(9) + ": " + dash(value) + "\n"

        val stamp = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date(entry.timestamp))

        val header = StringBuilder()
            .append(row("Direction", if (outgoing) "Outgoing" else "Incoming"))
            .append(row("From", if (outgoing) own else entry.number))
            .append(row("To", if (outgoing) entry.number else own))
            .append(row("SMSC", entry.smsc))
            .append(row("Date", stamp))
            .append(row("Format", entry.encoding))
            .append(
                row(
                    "Length",
                    "${entry.text.length} chars" +
                        if (parts > 0) ", $parts part${if (parts == 1) "" else "s"}" else ""
                )
            )

        // Delivery status is the outbound half of the story; an inbound
        // message has already arrived by definition, so claiming a state for
        // it would be inventing one.
        if (outgoing) {
            header.append(row("Status", status))
            if (error.isNotEmpty()) header.append(row("Error", error))
        }

        val body = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setPadding(48, 24, 48, 24)
            setTextIsSelectable(true)
            // A blank line is enough to separate the fields from the message;
            // the fields are aligned and the body is not, so the boundary
            // reads without a rule.
            text = header.toString() + "\n" + entry.text.ifEmpty { "(no text)" }
        }

        AlertDialog.Builder(this)
            .setTitle("Message detail")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("Close", null)
            .setNeutralButton("Copy") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(
                    android.content.ClipData.newPlainText("SMS detail", body.text)
                )
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showLinkDetails(mobile: Boolean) {
        val body = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setPadding(48, 24, 48, 24)
            text = if (mobile) mobileDetailsFast() else wifiDetailsFast()
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (mobile) "Mobile network" else "WiFi")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("Close", null)
            .show()

        fun append(line: String) = runOnUiThread {
            if (dialog.isShowing) body.append(line)
        }

        Thread({
            if (mobile) {
                // Cell identity is location data as far as Android is
                // concerned: dumpsys blanks it even for root, so the only way
                // to read it is getAllCellInfo() with ACCESS_FINE_LOCATION.
                // The Magisk module grants that on boot, so no prompt appears.
                append("\n" + describeCells())
            } else {
                val extra = RootShell.execForOutput(
                    "echo MAC=$(cat /sys/class/net/wlan0/address 2>/dev/null); " +
                    "echo GW=$(ip route get 8.8.8.8 2>/dev/null | grep -oE 'via [0-9.]+' | awk '{print $2}')",
                    timeoutMs = 8000
                )
                val f = extra.lines().mapNotNull {
                    val i = it.indexOf('=')
                    if (i > 0) it.substring(0, i) to it.substring(i + 1).trim() else null
                }.toMap()
                val gw = f["GW"].orEmpty()
                append(
                    "Phone MAC    : ${f["MAC"]?.ifEmpty { null } ?: "—"}\n" +
                    "Router IP    : ${gw.ifEmpty { "—" }}\n"
                )
                if (gw.isNotEmpty()) {
                    val mac = RootShell.execForOutput(
                        "ip neigh show $gw 2>/dev/null | grep -oE '([0-9a-f]{2}:){5}[0-9a-f]{2}' | head -1",
                        timeoutMs = 5000
                    ).trim()
                    append("Router MAC   : ${mac.ifEmpty { "—" }}\n")
                }

                append("\n— reachability —\n")
                if (gw.isNotEmpty()) append("Router  : ${pingAvg(gw)}\n")
                val server = getSharedPreferences("gateway", MODE_PRIVATE)
                    .getString("server", "") ?: ""
                if (server.isNotEmpty()) append("SIP srv : ${pingAvg(server)}  ($server)\n")
            }
        }, "link-details").start()
    }

    /** Mobile facts the framework answers instantly. */
    @SuppressLint("MissingPermission")
    private fun mobileDetailsFast(): String = buildString {
        try {
            val tm = getSystemService(TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            appendLine("Operator     : ${tm.networkOperatorName.ifEmpty { "—" }}")
            appendLine("MCC/MNC      : ${tm.networkOperator.ifEmpty { "—" }}")
            appendLine("Country      : ${tm.networkCountryIso.uppercase().ifEmpty { "—" }}")
            appendLine("SIM operator : ${tm.simOperatorName.ifEmpty { "—" }}")
            appendLine("SIM state    : ${simStateName(tm.simState)}")
            appendLine("Roaming      : ${if (tm.isNetworkRoaming) "yes" else "no"}")
            appendLine("Data network : ${networkTypeName(tm.dataNetworkType)}")
            appendLine("Voice network: ${networkTypeName(tm.voiceNetworkType)}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                safeSignalStrength(tm)?.cellSignalStrengths?.forEachIndexed { i, c ->
                    appendLine(
                        "Signal[$i]    : ${c.dbm} dBm, level ${c.level}/4 " +
                            "(${c.javaClass.simpleName.removePrefix("CellSignalStrength")})"
                    )
                }
            } else {
                signalDbm(safeSignalStrength(tm), tm)?.let { appendLine("Signal       : $it dBm") }
            }
        } catch (e: Exception) {
            appendLine("telephony: ${e.message}")
        }
    }

    /** WiFi facts the framework answers instantly. */
    private fun wifiDetailsFast(): String = buildString {
        try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE)
                as android.net.wifi.WifiManager
            appendLine("Enabled      : ${if (wm.isWifiEnabled) "yes" else "no"}")
            @Suppress("DEPRECATION")
            val info = wm.connectionInfo
            // Real SSID/BSSID: these read "<unknown ssid>" / 02:00:00:00:00:00
            // without location, which the Magisk module now grants on boot.
            @Suppress("DEPRECATION")
            val ssidRaw = info?.ssid?.trim('"').orEmpty()
            appendLine(
                "SSID         : " +
                    if (ssidRaw.isEmpty() || ssidRaw.contains("unknown", true)) "—" else ssidRaw
            )
            @Suppress("DEPRECATION")
            val bssid = info?.bssid.orEmpty()
            appendLine(
                "BSSID (AP)   : " +
                    if (bssid.isEmpty() || bssid.startsWith("02:00:00")) "—" else bssid
            )
            appendLine("Security     : ${wifiSecurityName(info)}")
            @Suppress("DEPRECATION")
            val freq = info?.frequency ?: 0
            @Suppress("DEPRECATION")
            appendLine("Link speed   : ${info?.linkSpeed ?: -1} Mbps")
            appendLine("Frequency    : $freq MHz (${if (freq > 4000) "5 GHz" else "2.4 GHz"})")
            @Suppress("DEPRECATION")
            appendLine("RSSI         : ${info?.rssi ?: 0} dBm")

            // IP and DNS come from LinkProperties: no root, no permission, and
            // it reports what the network actually resolved with.
            val cm = getSystemService(ConnectivityManager::class.java)
            val lp = cm?.activeNetwork?.let { cm.getLinkProperties(it) }
            val ip = lp?.linkAddresses?.firstOrNull { it.address.hostAddress?.contains('.') == true }
            appendLine("IP address   : ${ip?.address?.hostAddress ?: "—"}")
            val dns = lp?.dnsServers?.mapNotNull { it.hostAddress }?.joinToString(", ")
            appendLine("DNS          : ${dns?.ifEmpty { null } ?: "—"}")
        } catch (e: Exception) {
            appendLine("wifi: ${e.message}")
        }
    }

    /**
     * Serving cell and neighbours.  Can take a moment — the radio is polled —
     * so it is called off the main thread.
     */
    @SuppressLint("MissingPermission")
    private fun describeCells(): String {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return "needs location permission (granted by the Magisk module on boot)"

        return try {
            val tm = getSystemService(TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            val cells = tm.allCellInfo
            if (cells.isNullOrEmpty()) return "none reported"
            cells.take(6).joinToString("\n") { describeCell(it) }
        } catch (e: Exception) {
            "unavailable: ${e.message}"
        }
    }

    /**
     * One cell as aligned `name : value` lines, matching the rest of the
     * dialog.  Int.MAX_VALUE is the API's "unknown", not a real reading, so it
     * is shown as a dash rather than a nine-digit number.
     */
    private fun describeCell(info: android.telephony.CellInfo): String = buildString {
        fun row(name: String, value: String) = appendLine(name.padEnd(13) + ": " + value)
        fun num(x: Int) = if (x == Int.MAX_VALUE) "—" else x.toString()
        fun sig(s: android.telephony.CellSignalStrength) = "${s.dbm} dBm (${s.level}/4)"

        val role = if (info.isRegistered) "serving cell" else "neighbour"
        // Subject-less `when` so the CellInfoNr branch can carry an SDK guard.
        // The class is API 29, and an `is` test against a class the platform
        // does not have is itself the hazard — it resolves the type before any
        // guard inside the branch could run.
        when {
            info is android.telephony.CellInfoLte -> {
                val id = info.cellIdentity
                row("Type", "LTE ($role)")
                row("Cell ID", num(id.ci))
                row("PCI", num(id.pci))
                row("TAC", num(id.tac))
                row("EARFCN", num(id.earfcn))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    row("MCC/MNC", "${id.mccString ?: "—"}/${id.mncString ?: "—"}")
                    id.operatorAlphaLong?.toString()?.takeIf { it.isNotBlank() }
                        ?.let { row("Carrier", it) }
                } else {
                    @Suppress("DEPRECATION")
                    row("MCC/MNC", "${num(id.mcc)}/${num(id.mnc)}")
                }
                row("Signal", sig(info.cellSignalStrength))
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                info is android.telephony.CellInfoNr -> {
                val id = info.cellIdentity as? android.telephony.CellIdentityNr
                row("Type", "5G NR ($role)")
                row("NCI", id?.nci?.toString() ?: "—")
                row("PCI", num(id?.pci ?: Int.MAX_VALUE))
                row("TAC", num(id?.tac ?: Int.MAX_VALUE))
                row("NRARFCN", num(id?.nrarfcn ?: Int.MAX_VALUE))
                row("MCC/MNC", "${id?.mccString ?: "—"}/${id?.mncString ?: "—"}")
                row("Signal", sig(info.cellSignalStrength))
            }
            info is android.telephony.CellInfoWcdma -> {
                val id = info.cellIdentity
                row("Type", "WCDMA ($role)")
                row("Cell ID", num(id.cid))
                row("LAC", num(id.lac))
                row("PSC", num(id.psc))
                row("UARFCN", num(id.uarfcn))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    row("MCC/MNC", "${id.mccString ?: "—"}/${id.mncString ?: "—"}")
                } else {
                    @Suppress("DEPRECATION")
                    row("MCC/MNC", "${num(id.mcc)}/${num(id.mnc)}")
                }
                row("Signal", sig(info.cellSignalStrength))
            }
            info is android.telephony.CellInfoGsm -> {
                val id = info.cellIdentity
                row("Type", "GSM ($role)")
                row("Cell ID", num(id.cid))
                row("LAC", num(id.lac))
                row("ARFCN", num(id.arfcn))
                row("BSIC", num(id.bsic))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    row("MCC/MNC", "${id.mccString ?: "—"}/${id.mncString ?: "—"}")
                } else {
                    @Suppress("DEPRECATION")
                    row("MCC/MNC", "${num(id.mcc)}/${num(id.mnc)}")
                }
                row("Signal", sig(info.cellSignalStrength))
            }
            else -> {
                row("Type", "${info.javaClass.simpleName.removePrefix("CellInfo")} ($role)")
                // The CellInfo base class only grew getCellSignalStrength() in
                // API 30; every branch above reads it off its own subclass,
                // which has had it since 17.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    row("Signal", sig(info.cellSignalStrength))
                }
            }
        }
    }

    /**
     * The link's security type — the cipher in use, not the passphrase.
     * The stored key is deliberately not shown: it is the network's actual
     * credential and a diagnostics panel is the wrong place for it.
     */
    private fun wifiSecurityName(info: android.net.wifi.WifiInfo?): String {
        if (info == null) return "—"
        // getCurrentSecurityType() is API 31.  The catch below does not stand in
        // for a version check: a missing method raises NoSuchMethodError, which
        // is an Error rather than an Exception, so it would pass straight
        // through and take the dialog down.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return "—"
        return try {
            when (info.currentSecurityType) {
                android.net.wifi.WifiInfo.SECURITY_TYPE_OPEN -> "open (none)"
                android.net.wifi.WifiInfo.SECURITY_TYPE_WEP -> "WEP"
                android.net.wifi.WifiInfo.SECURITY_TYPE_PSK -> "WPA/WPA2-PSK"
                android.net.wifi.WifiInfo.SECURITY_TYPE_EAP -> "WPA-EAP"
                android.net.wifi.WifiInfo.SECURITY_TYPE_SAE -> "WPA3-SAE"
                android.net.wifi.WifiInfo.SECURITY_TYPE_OWE -> "OWE (enhanced open)"
                android.net.wifi.WifiInfo.SECURITY_TYPE_WAPI_PSK -> "WAPI-PSK"
                android.net.wifi.WifiInfo.SECURITY_TYPE_WAPI_CERT -> "WAPI-CERT"
                android.net.wifi.WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE -> "WPA3-Enterprise"
                android.net.wifi.WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT ->
                    "WPA3-Enterprise 192-bit"
                android.net.wifi.WifiInfo.SECURITY_TYPE_PASSPOINT_R1_R2 -> "Passpoint R1/R2"
                android.net.wifi.WifiInfo.SECURITY_TYPE_PASSPOINT_R3 -> "Passpoint R3"
                else -> "unknown"
            }
        } catch (e: Exception) {
            "—"
        }
    }

    /** Average round-trip to a host, or why it failed. */
    private fun pingAvg(host: String): String {
        val out = RootShell.execForOutput("ping -c 3 -W 2 $host 2>&1 | tail -2", timeoutMs = 12000)
        val avg = Regex("= [0-9.]+/([0-9.]+)/").find(out)?.groupValues?.getOrNull(1)
        val loss = Regex("([0-9]+)% packet loss").find(out)?.groupValues?.getOrNull(1)
        return when {
            avg != null -> "$avg ms avg" + (loss?.let { ", $it% loss" } ?: "")
            loss == "100" -> "no reply (100% loss)"
            else -> out.lines().firstOrNull { it.isNotBlank() } ?: "unreachable"
        }
    }

    private fun simStateName(state: Int): String = when (state) {
        android.telephony.TelephonyManager.SIM_STATE_READY -> "ready"
        android.telephony.TelephonyManager.SIM_STATE_ABSENT -> "absent"
        android.telephony.TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN required"
        android.telephony.TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK required"
        android.telephony.TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "network locked"
        android.telephony.TelephonyManager.SIM_STATE_NOT_READY -> "not ready"
        else -> "unknown($state)"
    }

    private fun setCallFilter(filter: String) {
        callFilter = filter
        val on = ContextCompat.getColor(this, R.color.accent)
        val onText = ContextCompat.getColor(this, R.color.accent_on)
        val off = ContextCompat.getColor(this, R.color.btn_secondary)
        val offText = ContextCompat.getColor(this, R.color.text_primary)
        for ((btn, name) in listOf(
            btnFilterAll to "all", btnFilterIncoming to "in", btnFilterOutgoing to "out",
            btnFilterSms to "sms"
        )) {
            val active = name == filter
            btn.backgroundTintList = ColorStateList.valueOf(if (active) on else off)
            btn.setTextColor(if (active) onText else offText)
        }
        renderHomeTraffic()
    }

    /** Recent calls on the home view, newest first. */
    private fun renderHomeTraffic(fromSync: Boolean = false) {
        if (!fromSync && System.currentTimeMillis() - lastSystemLogSyncMs > 3_000L) {
            lastSystemLogSyncMs = System.currentTimeMillis()
            kotlin.concurrent.thread(name = "sync-system-logs") {
                val changed = runCatching { CallLogStore.syncWithSystemProviders(this) }.getOrDefault(false)
                if (changed) {
                    runOnUiThread { renderHomeTraffic(fromSync = true) }
                }
            }
        }

        val all = try { CallLogStore.getEntries(this) } catch (_: Exception) { emptyList() }
        val entries = when (callFilter) {
            "in" -> all.filter { it.direction == "IN" }
            "out" -> all.filter { it.direction != "IN" }
            "sms" -> all.filter { it.type == CallLogStore.TYPE_SMS }
            else -> all
        }
        homeTrafficList.removeAllViews()
        tvHomeTrafficEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE

        for (e in entries.take(35)) {
            val row = layoutInflater.inflate(R.layout.item_call_row, homeTrafficList, false)
            val incoming = e.direction == "IN"
            row.findViewById<ImageView>(R.id.ivRowIcon).setImageResource(
                if (incoming) R.drawable.ic_call_incoming else R.drawable.ic_call_outgoing
            )
            val contactName = resolveContactName(e.number)
            row.findViewById<TextView>(R.id.tvRowNumber).text =
                if (!contactName.isNullOrBlank() && contactName != e.number) {
                    "$contactName (${e.number})"
                } else {
                    e.number
                }
            val sms = e.type == CallLogStore.TYPE_SMS
            row.findViewById<TextView>(R.id.tvRowSub).text = when {
                sms -> e.text.replace('\n', ' ').trim().ifEmpty { "(metin yok)" }
                e.durationSec > 0 -> if (incoming) "Gelen Arama · Bağlandı" else "Giden Arama · Bağlandı"
                else -> if (incoming) "Cevapsız / Bağlanmadı" else "Arama · Cevapsız"
            }
            val dur = row.findViewById<TextView>(R.id.tvRowDuration)
            when {
                sms -> {
                    dur.text = "SMS"
                    dur.setTextColor(Color.parseColor("#60A5FA"))
                }
                e.durationSec > 0 -> {
                    dur.text = String.format("%02d:%02d", e.durationSec / 60, e.durationSec % 60)
                    dur.setTextColor(Color.parseColor("#34D399"))
                }
                else -> {
                    dur.text = "—"
                    dur.setTextColor(Color.parseColor("#F87171"))
                }
            }
            row.findViewById<TextView>(R.id.tvRowTime).text = rowTimeLabel(e.timestamp)
            row.isClickable = true
            row.isFocusable = true
            if (sms) {
                row.setOnClickListener { showSmsDetails(e) }
            } else {
                row.setOnClickListener {
                    val cleanNum = e.number.substringBefore(" (").trim()
                    AlertDialog.Builder(this)
                        .setTitle(contactName ?: cleanNum)
                        .setItems(arrayOf("GSM ile Ara ($cleanNum)", "SMS Gönder", "Numarayı Kopyala")) { _, which ->
                            when (which) {
                                0 -> startDirectLocalCall(cleanNum)
                                1 -> showQuickSmsDialog(cleanNum, contactName)
                                2 -> {
                                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("number", cleanNum))
                                    Toast.makeText(this, "Kopyalandı: $cleanNum", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        .show()
                }
            }
            homeTrafficList.addView(row)
        }
    }

    /**
     * When a call or SMS happened, as a row label.  Anything not from today
     * used to say just "Earlier", so a call from yesterday and one from last
     * month looked the same.  Today: the time; any earlier day: the full date
     * and time without the year, e.g. "12 Oct 12:32:22".
     */
    private fun rowTimeLabel(timestamp: Long): String {
        val then = java.util.Date(timestamp)
        val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
        return if (day.format(then) == day.format(java.util.Date())) {
            java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(then)
        } else {
            java.text.SimpleDateFormat("d MMM HH:mm:ss", java.util.Locale.ENGLISH).format(then)
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (inCallOpen) {
            return // must use END CALL
        } else if (currentTab == "logs") {
            switchTab("config")
        } else if (currentTab != "home") {
            switchTab("home")
        } else {
            // As Kiosk / Home Launcher, stay on the Home screen when Back is pressed.
            return
        }
    }

    private var localCallStartTimeMs = 0L
    private var localDtmfOpen = false
    private val localTimerHandler = Handler(Looper.getMainLooper())
    private val localTimerRunnable = object : Runnable {
        override fun run() {
            val tvTimer = findViewById<TextView>(R.id.tvLocalCallTimer) ?: return
            val call = com.callagent.gateway.gsm.GsmCallManager.localFallbackCall ?: return
            if (call.state == android.telecom.Call.STATE_ACTIVE && localCallStartTimeMs > 0L) {
                val sec = ((System.currentTimeMillis() - localCallStartTimeMs) / 1000L).coerceAtLeast(0L)
                tvTimer.text = String.format("%02d:%02d", sec / 60, sec % 60)
                localTimerHandler.postDelayed(this, 1000L)
            }
        }
    }

    private fun setupBottomNavAndDialer() {
        findViewById<View>(R.id.btnNavHome)?.setOnClickListener { switchTab("home") }
        findViewById<View>(R.id.btnNavDialer)?.setOnClickListener { switchTab("dialer") }
        findViewById<View>(R.id.btnNavContacts)?.setOnClickListener { switchTab("contacts") }
        findViewById<View>(R.id.btnNavConfig)?.setOnClickListener { openConfigView() }

        val etDial = findViewById<EditText>(R.id.etDialerInput)
        val btnBack = findViewById<Button>(R.id.btnDialerBackspace)
        val glPad = findViewById< android.widget.GridLayout>(R.id.glDialerPad)
        val btnCall = findViewById<Button>(R.id.btnDialerCall)
        val btnSms = findViewById<Button>(R.id.btnDialerSms)

        btnBack?.setOnClickListener {
            val cur = etDial?.text?.toString().orEmpty()
            if (cur.isNotEmpty()) {
                etDial?.setText(cur.dropLast(1))
                etDial?.setSelection(etDial.text.length)
            }
        }
        btnBack?.setOnLongClickListener {
            etDial?.setText("")
            true
        }

        val keys = listOf(
            "1" to "", "2" to "ABC", "3" to "DEF",
            "4" to "GHI", "5" to "JKL", "6" to "MNO",
            "7" to "PQRS", "8" to "TUV", "9" to "WXYZ",
            "*" to "", "0" to "+", "#" to ""
        )
        glPad?.removeAllViews()
        keys.forEach { (digit, sub) ->
            val b = Button(this).apply {
                text = if (sub.isNotEmpty()) "$digit\n$sub" else digit
                textSize = 18f
                isAllCaps = false
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                background = ContextCompat.getDrawable(context, R.drawable.soft_btn_bg)
                backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.btn_secondary))
                stateListAnimator = null
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = 0
                    height = 0
                    columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                    rowSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                    setMargins(8, 8, 8, 8)
                }
                setOnClickListener {
                    etDial?.append(digit)
                }
                if (digit == "0") {
                    setOnLongClickListener {
                        etDial?.append("+")
                        true
                    }
                }
            }
            glPad?.addView(b)
        }

        btnCall?.setOnClickListener {
            val num = etDial?.text?.toString()?.trim().orEmpty()
            if (num.isEmpty()) {
                Toast.makeText(this, "Lütfen aranacak numarayı girin", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startDirectLocalCall(num)
        }

        btnSms?.setOnClickListener {
            val num = etDial?.text?.toString()?.trim().orEmpty()
            showQuickSmsDialog(num, resolveContactName(num))
        }

        findViewById<Button>(R.id.btnContactsNewSms)?.setOnClickListener {
            showQuickSmsDialog("", null)
        }

        findViewById<EditText>(R.id.etContactsSearch)?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString().orEmpty()
                pendingContactsSearch?.let { contactsSearchHandler.removeCallbacks(it) }
                val task = Runnable { loadContactsList(query) }
                pendingContactsSearch = task
                contactsSearchHandler.postDelayed(task, 200L)
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun startDirectLocalCall(number: String) {
        val ok = com.callagent.gateway.gsm.GsmCallManager.placeLocalPhoneCall(this, number)
        if (!ok) {
            Toast.makeText(this, "Arama başlatılamadı (SIM/Hat kontrol edin)", Toast.LENGTH_SHORT).show()
        } else {
            showLocalFallbackDialogIfNeeded(pendingDialNumber = number)
        }
    }

    private fun showQuickSmsDialog(prefillNumber: String, contactName: String?) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(42, 24, 42, 12)
        }
        val etTo = EditText(this).apply {
            hint = "Alıcı Numara (05xx...)"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setText(prefillNumber)
        }
        val etBody = EditText(this).apply {
            hint = "SMS mesajınızı yazın..."
            minLines = 3
        }
        container.addView(etTo)
        container.addView(etBody)

        val title = if (!contactName.isNullOrBlank()) "SMS Gönder: $contactName" else "GSM SMS Gönder"
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(container)
            .setPositiveButton("Gönder") { _, _ ->
                val to = etTo.text?.toString()?.trim().orEmpty()
                val text = etBody.text?.toString()?.trim().orEmpty()
                if (to.isEmpty() || text.isEmpty()) {
                    Toast.makeText(this, "Numara ve mesaj boş olamaz", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                kotlin.concurrent.thread(name = "ui-send-sms") {
                    val sm = getSystemService(SubscriptionManager::class.java)
                    val subId = runCatching {
                        sm?.activeSubscriptionInfoList?.firstOrNull()?.subscriptionId
                            ?: SubscriptionManager.getDefaultSmsSubscriptionId()
                    }.getOrDefault(SubscriptionManager.INVALID_SUBSCRIPTION_ID)
                    val sms = com.callagent.gateway.sms.OutboundSms(
                        id = "manual-${System.currentTimeMillis()}",
                        to = to,
                        text = text,
                        subId = subId
                    )
                    com.callagent.gateway.sms.SmsOutbox.add(this, sms)
                    val sent = if (com.callagent.gateway.sms.SmsOutbox.claimForDispatch(this, sms.id)) {
                        com.callagent.gateway.sms.SmsSender.dispatch(this, sms)
                    } else {
                        false
                    }
                    runCatching {
                        CallLogStore.addEntry(
                            this,
                            CallLogEntry(
                                direction = "OUT",
                                number = to,
                                timestamp = System.currentTimeMillis(),
                                durationSec = 0L,
                                type = CallLogStore.TYPE_SMS,
                                text = text,
                                smsId = sms.id,
                                status = if (sent) "sent" else "failed"
                            )
                        )
                    }
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            if (sent) "SMS gönderildi ($to)" else "SMS gönderilemedi",
                            Toast.LENGTH_SHORT
                        ).show()
                        refreshHome()
                    }
                }
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun registerContactsObserver() {
        if (contactsObserver != null) return
        val obs = object : android.database.ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                contactsDirty = true
                lastRenderedContactsQuery = null
                preloadContactsAsync(forceReload = true)
            }
        }
        runCatching {
            contentResolver.registerContentObserver(
                android.provider.ContactsContract.Contacts.CONTENT_URI,
                true,
                obs
            )
            contentResolver.registerContentObserver(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                true,
                obs
            )
            contactsObserver = obs
        }
    }

    private fun preloadContactsAsync(forceReload: Boolean = false) {
        if (contactsLoaded && !contactsDirty && !forceReload) return
        if (!contactsLoading.compareAndSet(false, true)) return
        kotlin.concurrent.thread(name = "preload-contacts", isDaemon = true) {
            try {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    RootShell.execWithStatus("pm grant $packageName android.permission.READ_CONTACTS", 4_000)
                }
                runOnUiThread { registerContactsObserver() }
                val contacts = mutableListOf<Pair<String, String>>()
                val nameMap = HashMap<String, String>()
                val seen = HashSet<String>()
                runCatching {
                    contentResolver.query(
                        android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        arrayOf(
                            android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                            android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
                        ),
                        null,
                        null,
                        "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
                    )?.use { cur ->
                        val nameIdx = cur.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                        val numIdx = cur.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
                        while (cur.moveToNext()) {
                            val name = if (nameIdx >= 0) cur.getString(nameIdx).orEmpty().trim() else ""
                            val num = if (numIdx >= 0) cur.getString(numIdx).orEmpty().trim() else ""
                            if (num.isEmpty()) continue
                            val digits = num.filter { it.isDigit() }
                            if (name.isNotEmpty()) {
                                nameMap.putIfAbsent(num, name)
                                if (digits.isNotEmpty()) nameMap.putIfAbsent(digits, name)
                                if (digits.length >= 7) nameMap.putIfAbsent(digits.takeLast(10), name)
                            }
                            val normKey = "$name|${num.filter { it.isDigit() || it == '+' }}"
                            if (!seen.add(normKey)) continue
                            contacts.add(name.ifEmpty { num } to num)
                        }
                    }
                }
                cachedContacts = contacts
                contactNameCache.clear()
                contactNameCache.putAll(nameMap)
                contactsLoaded = true
                contactsDirty = false
                lastRenderedContactsQuery = null
                runOnUiThread {
                    if (currentTab == "contacts") {
                        val q = findViewById<EditText>(R.id.etContactsSearch)?.text?.toString().orEmpty()
                        loadContactsList(q)
                    } else if (currentTab == "home") {
                        renderHomeTraffic(fromSync = true)
                    }
                }
            } finally {
                contactsLoading.set(false)
            }
        }
    }

    private fun loadContactsList(query: String) {
        val container = findViewById<LinearLayout>(R.id.llContactsList) ?: return
        val normQuery = query.trim()

        if (contactsLoaded && !contactsDirty) {
            if (normQuery == lastRenderedContactsQuery && container.childCount > 0) {
                return
            }
            val filtered = if (normQuery.isBlank()) {
                cachedContacts.take(250)
            } else {
                cachedContacts.filter { (name, num) ->
                    name.contains(normQuery, ignoreCase = true) ||
                        num.contains(normQuery, ignoreCase = true)
                }.take(250)
            }
            renderContactsViews(container, filtered, normQuery)
            return
        }

        if (cachedContacts.isNotEmpty()) {
            val filtered = if (normQuery.isBlank()) {
                cachedContacts.take(250)
            } else {
                cachedContacts.filter { (name, num) ->
                    name.contains(normQuery, ignoreCase = true) ||
                        num.contains(normQuery, ignoreCase = true)
                }.take(250)
            }
            renderContactsViews(container, filtered, normQuery)
        }
        preloadContactsAsync(forceReload = true)
    }

    private fun renderContactsViews(
        container: LinearLayout,
        contacts: List<Pair<String, String>>,
        normQuery: String
    ) {
        lastRenderedContactsQuery = normQuery
        val dp = resources.displayMetrics.density
        container.removeAllViews()
        if (contacts.isEmpty()) {
            val empty = TextView(this).apply {
                text = if (normQuery.isBlank()) "Rehberde kayıtlı kişi bulunamadı." else "Eşleşen kişi bulunamadı."
                textSize = 13f
                setTextColor(ContextCompat.getColor(context, R.color.text_hint))
                setPadding(8, 24, 8, 24)
            }
            container.addView(empty)
            return
        }
        for ((name, num) in contacts) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = ContextCompat.getDrawable(context, R.drawable.card_bg)
                setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (10 * dp).toInt() }
            }
            val infoCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            infoCol.addView(TextView(this).apply {
                text = name
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            })
            infoCol.addView(TextView(this).apply {
                text = num
                textSize = 12f
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            })
            val btnRowCall = Button(this).apply {
                text = "Ara"
                textSize = 11f
                isAllCaps = false
                setTextColor(Color.WHITE)
                background = ContextCompat.getDrawable(context, R.drawable.pill_bg)
                backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.call_green))
                setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_call_filled, 0, 0, 0)
                androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(this, ColorStateList.valueOf(Color.WHITE))
                compoundDrawablePadding = (5 * dp).toInt()
                stateListAnimator = null
                minWidth = 0
                minHeight = 0
                setPadding((12 * dp).toInt(), (6 * dp).toInt(), (14 * dp).toInt(), (6 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    (36 * dp).toInt()
                ).apply { marginEnd = (6 * dp).toInt() }
                setOnClickListener { startDirectLocalCall(num) }
            }
            val btnRowSms = Button(this).apply {
                text = "SMS"
                textSize = 11f
                isAllCaps = false
                setTextColor(Color.WHITE)
                background = ContextCompat.getDrawable(context, R.drawable.pill_bg)
                backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.sms_blue))
                setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_sms_filled, 0, 0, 0)
                androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(this, ColorStateList.valueOf(Color.WHITE))
                compoundDrawablePadding = (5 * dp).toInt()
                stateListAnimator = null
                minWidth = 0
                minHeight = 0
                setPadding((12 * dp).toInt(), (6 * dp).toInt(), (14 * dp).toInt(), (6 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    (36 * dp).toInt()
                )
                setOnClickListener { showQuickSmsDialog(num, name) }
            }
            row.addView(infoCol)
            row.addView(btnRowCall)
            row.addView(btnRowSms)
            container.addView(row)
        }
    }

    private fun resolveContactName(number: String): String? {
        val clean = number.trim()
        if (clean.isEmpty() || clean == "unknown") return null
        contactNameCache[clean]?.let { return it }
        val digits = clean.filter { it.isDigit() }
        if (digits.isNotEmpty()) {
            contactNameCache[digits]?.let { return it }
            if (digits.length >= 7) {
                contactNameCache[digits.takeLast(10)]?.let { return it }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (!contactsLoaded) preloadContactsAsync()
            return null
        }
        return runCatching {
            val uri = android.net.Uri.withAppendedPath(
                android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                android.net.Uri.encode(clean)
            )
            contentResolver.query(
                uri,
                arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cur ->
                if (cur.moveToFirst()) cur.getString(0) else null
            }
        }.getOrNull()?.also { resolved ->
            if (resolved.isNotBlank()) {
                contactNameCache[clean] = resolved
                if (digits.isNotEmpty()) contactNameCache[digits] = resolved
                if (digits.length >= 7) contactNameCache[digits.takeLast(10)] = resolved
            }
        }
    }

    private fun setupLocalInCallScreen() {
        val glDtmf = findViewById<android.widget.GridLayout>(R.id.glLocalInCallDtmf)
        val dtmfKeys = listOf('1', '2', '3', '4', '5', '6', '7', '8', '9', '*', '0', '#')
        glDtmf?.removeAllViews()
        dtmfKeys.forEach { digit ->
            val b = Button(this).apply {
                text = digit.toString()
                textSize = 20f
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                background = ContextCompat.getDrawable(context, R.drawable.soft_btn_bg)
                backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.btn_secondary))
                stateListAnimator = null
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = 0
                    height = 0
                    columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                    rowSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                    setMargins(8, 8, 8, 8)
                }
                setOnClickListener {
                    com.callagent.gateway.gsm.GsmCallManager.sendLocalDtmf(digit)
                    Toast.makeText(context, "Tuş: $digit", Toast.LENGTH_SHORT).show()
                }
            }
            glDtmf?.addView(b)
        }

        findViewById<Button>(R.id.btnLocalSpeaker)?.setOnClickListener {
            val next = !com.callagent.gateway.gsm.GsmCallManager.localSpeakerOn
            com.callagent.gateway.gsm.GsmCallManager.setLocalSpeakerphone(this, next)
            updateLocalInCallControls()
        }

        findViewById<Button>(R.id.btnLocalMute)?.setOnClickListener {
            val next = !com.callagent.gateway.gsm.GsmCallManager.localMicMuted
            com.callagent.gateway.gsm.GsmCallManager.setLocalMicMute(this, next)
            updateLocalInCallControls()
        }

        findViewById<Button>(R.id.btnLocalKeypad)?.setOnClickListener {
            localDtmfOpen = !localDtmfOpen
            updateLocalInCallControls()
        }

        findViewById<Button>(R.id.btnLocalAnswer)?.setOnClickListener {
            val call = com.callagent.gateway.gsm.GsmCallManager.localFallbackCall ?: return@setOnClickListener
            com.callagent.gateway.gsm.GsmCallManager.stopFallbackRingtone()
            com.callagent.gateway.gsm.GsmCallManager.answerCall(call)
            showLocalFallbackDialogIfNeeded()
        }

        findViewById<Button>(R.id.btnLocalReject)?.setOnClickListener {
            val call = com.callagent.gateway.gsm.GsmCallManager.localFallbackCall
            if (call != null) {
                com.callagent.gateway.gsm.GsmCallManager.stopFallbackRingtone()
                com.callagent.gateway.gsm.GsmCallManager.rejectCall(call)
                if (call.state == android.telecom.Call.STATE_DISCONNECTED) {
                    com.callagent.gateway.gsm.GsmCallManager.clearLocalFallback(restoreScreenIfKiosk = true)
                }
            } else {
                com.callagent.gateway.gsm.GsmCallManager.clearLocalFallback(restoreScreenIfKiosk = true)
            }
            showLocalFallbackDialogIfNeeded()
        }

        findViewById<Button>(R.id.btnLocalHangup)?.setOnClickListener {
            val call = com.callagent.gateway.gsm.GsmCallManager.localFallbackCall
                ?: com.callagent.gateway.gsm.GsmCallManager.activeCall
            if (call != null) {
                com.callagent.gateway.gsm.GsmCallManager.hangupCall(call)
                if (call !== com.callagent.gateway.gsm.GsmCallManager.localFallbackCall ||
                    call.state == android.telecom.Call.STATE_DISCONNECTED
                ) {
                    com.callagent.gateway.gsm.GsmCallManager.clearLocalFallback(restoreScreenIfKiosk = true)
                }
            } else {
                com.callagent.gateway.gsm.GsmCallManager.clearLocalFallback(restoreScreenIfKiosk = true)
            }
            showLocalFallbackDialogIfNeeded()
        }

        com.callagent.gateway.gsm.GsmCallManager.onLocalFallbackStateChanged = { _, _ ->
            runOnUiThread {
                showLocalFallbackDialogIfNeeded()
            }
        }
    }

    private fun updateLocalInCallControls() {
        val btnSpeaker = findViewById<Button>(R.id.btnLocalSpeaker) ?: return
        val btnMute = findViewById<Button>(R.id.btnLocalMute) ?: return
        val btnKeypad = findViewById<Button>(R.id.btnLocalKeypad) ?: return
        val glDtmf = findViewById<View>(R.id.glLocalInCallDtmf)
        val vSpacer = findViewById<View>(R.id.vLocalCallSpacer)

        val spkOn = com.callagent.gateway.gsm.GsmCallManager.localSpeakerOn
        val muteOn = com.callagent.gateway.gsm.GsmCallManager.localMicMuted
        val idleFg = ContextCompat.getColor(this, R.color.text_primary)

        val spkFg = if (spkOn) Color.WHITE else idleFg
        btnSpeaker.text = if (spkOn) "Hoparlör: Açık" else "Hoparlör"
        btnSpeaker.backgroundTintList = ColorStateList.valueOf(
            if (spkOn) ContextCompat.getColor(this, R.color.sms_blue) else ContextCompat.getColor(this, R.color.btn_secondary)
        )
        btnSpeaker.setTextColor(spkFg)
        androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(
            btnSpeaker, ColorStateList.valueOf(spkFg)
        )

        val muteFg = if (muteOn) Color.WHITE else idleFg
        btnMute.text = if (muteOn) "Sessiz: Açık" else "Sessiz"
        btnMute.backgroundTintList = ColorStateList.valueOf(
            if (muteOn) Color.parseColor("#D97706") else ContextCompat.getColor(this, R.color.btn_secondary)
        )
        btnMute.setTextColor(muteFg)
        androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(
            btnMute, ColorStateList.valueOf(muteFg)
        )

        val keyFg = if (localDtmfOpen) Color.WHITE else idleFg
        btnKeypad.backgroundTintList = ColorStateList.valueOf(
            if (localDtmfOpen) ContextCompat.getColor(this, R.color.call_green) else ContextCompat.getColor(this, R.color.btn_secondary)
        )
        btnKeypad.setTextColor(keyFg)
        androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(
            btnKeypad, ColorStateList.valueOf(keyFg)
        )

        glDtmf?.visibility = if (localDtmfOpen) View.VISIBLE else View.GONE
        vSpacer?.visibility = if (localDtmfOpen) View.GONE else View.VISIBLE
    }

    @Suppress("DEPRECATION")
    private fun showLocalFallbackDialogIfNeeded(pendingDialNumber: String? = null) {
        val localView = findViewById<View>(R.id.localInCallView) ?: return
        com.callagent.gateway.gsm.GsmCallManager.onLocalFallbackStateChanged = { _, _ ->
            runOnUiThread {
                showLocalFallbackDialogIfNeeded()
            }
        }
        val call = com.callagent.gateway.gsm.GsmCallManager.localFallbackCall
        if (call == null && pendingDialNumber == null) {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            localView.visibility = View.GONE
            localTimerHandler.removeCallbacks(localTimerRunnable)
            localCallStartTimeMs = 0L
            localDtmfOpen = false
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        val number = if (call != null) {
            com.callagent.gateway.gsm.GsmCallManager.callerOf(call).first
        } else {
            pendingDialNumber.orEmpty()
        }
        val contactName = resolveContactName(number)
        val reason = intent?.getStringExtra("local_fallback_reason").orEmpty()
        val state = call?.state ?: android.telecom.Call.STATE_DIALING
        val isRinging = state == android.telecom.Call.STATE_RINGING
        val isActive = state == android.telecom.Call.STATE_ACTIVE
        val isHolding = state == android.telecom.Call.STATE_HOLDING

        localView.visibility = View.VISIBLE
        localView.bringToFront()
        findViewById<TextView>(R.id.tvLocalCallBadge)?.text = when {
            isRinging -> "GELEN ARAMA · GM 8"
            isActive -> "GÖRÜŞME AKTİF · GM 8"
            isHolding -> "BEKLETİLİYOR · GM 8"
            else -> "ARANIYOR · GM 8"
        }
        findViewById<TextView>(R.id.tvLocalCallName)?.text = contactName ?: number
        findViewById<TextView>(R.id.tvLocalCallNumber)?.text = if (contactName != null) number else "GM 8 Doğrudan Hat"
        findViewById<TextView>(R.id.tvLocalCallReason)?.text =
            if (reason.isNotEmpty() && isRinging) "Sebep: $reason" else ""

        findViewById<View>(R.id.llLocalRingingActions)?.visibility = if (isRinging) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btnLocalHangup)?.visibility = if (isRinging) View.GONE else View.VISIBLE

        val tvTimer = findViewById<TextView>(R.id.tvLocalCallTimer)
        when {
            isActive -> {
                if (localCallStartTimeMs == 0L) localCallStartTimeMs = System.currentTimeMillis()
                localTimerHandler.removeCallbacks(localTimerRunnable)
                localTimerRunnable.run()
            }
            isHolding -> {
                localTimerHandler.removeCallbacks(localTimerRunnable)
                tvTimer?.text = "Bekletiliyor"
            }
            else -> {
                localCallStartTimeMs = 0L
                localTimerHandler.removeCallbacks(localTimerRunnable)
                tvTimer?.text = if (isRinging) "Çalıyor…" else "Bağlanıyor…"
            }
        }

        updateLocalInCallControls()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showLocalFallbackDialogIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        showLocalFallbackDialogIfNeeded()
        val filter = IntentFilter().apply {
            addAction(GatewayService.STATUS_ACTION)
            addAction(GatewayService.LOG_ACTION)
        }
        // NOT_EXPORTED: the service sends these with setPackage(), so nothing
        // outside the app has any business delivering them — exported, any
        // installed app could feed the UI fabricated status and log lines.
        ContextCompat.registerReceiver(this, statusReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        // Replay any log messages buffered while activity was paused
        // Re-render from the service's buffer rather than consuming it: the
        // lines are already stamped with when each event happened, and
        // replacing the view means a recreated activity shows the full recent
        // history instead of whatever it happened to witness.
        val buffered = GatewayService.logSnapshot()
        if (buffered.isNotEmpty()) {
            tvLog.text = buffered.joinToString("\n", postfix = "\n")
            svLog.post { svLog.fullScroll(ScrollView.FOCUS_DOWN) }
        }

        // The old full-screen in-call view is superseded by the live call
        // card on the home view; it is no longer opened automatically.

        if (inCallOpen) {
            if (com.callagent.gateway.gsm.GsmCallManager.activeCall == null &&
                System.currentTimeMillis() - inCallOpenTime > 2000) {
                closeInCallScreen()
            } else {
                callTimerHandler.removeCallbacks(callTimerRunnable)
                if (callStartTime > 0) callTimerRunnable.run()
                callTimerHandler.removeCallbacks(gsmPollRunnable)
                callTimerHandler.postDelayed(gsmPollRunnable, 500)
            }
        }

        // Refresh the traffic list if it is the visible one.  This used to be
        // guarded on the old Calls tab, so after that tab went the home list
        // stopped being refreshed here at all and showed a stale view until
        // something else rebuilt it.
        if (currentTab == "home") {
            refreshHome()
        }

        // Re-apply the chip highlight: the visual state is set in code, so it
        // has to be restored whenever the view comes back.
        if (::btnFilterAll.isInitialized) setCallFilter(callFilter)

        netHandler.removeCallbacks(netRunnable)
        netRunnable.run()

        // Ask the service where it is.  Status is only pushed on change, so
        // opening the app onto an already-running gateway would otherwise show
        // "Offline" until something happened.
        startService(Intent(this, GatewayService::class.java).apply {
            action = GatewayService.ACTION_STATUS
        })
    }

    override fun onPause() {
        super.onPause()
        callTimerHandler.removeCallbacks(callTimerRunnable)
        callTimerHandler.removeCallbacks(gsmPollRunnable)
        localTimerHandler.removeCallbacks(localTimerRunnable)
        contactsSearchHandler.removeCallbacksAndMessages(null)
        pendingContactsSearch = null
        netHandler.removeCallbacks(netRunnable)
        netHandler.removeCallbacks(coalescedLogUiRefresh)
        logUiRefreshPending = false
        unregisterReceiver(statusReceiver)
    }

    override fun onDestroy() {
        com.callagent.gateway.gsm.GsmCallManager.onLocalFallbackStateChanged = null
        localTimerHandler.removeCallbacksAndMessages(null)
        contactsSearchHandler.removeCallbacksAndMessages(null)
        contactsObserver?.let { obs ->
            runCatching { contentResolver.unregisterContentObserver(obs) }
        }
        contactsObserver = null
        super.onDestroy()
    }

    // ── Config Dialog ────────────────────────────────────

    // ── Info Dialog ─────────────────────────────────────

    @SuppressLint("MissingPermission")
    // ── Gateway Support Checks ─────────────────────────

    private fun runGatewayChecks(container: LinearLayout, onDone: () -> Unit) {
        val dp = resources.displayMetrics.density
        val greenColor = Color.parseColor("#16A34A")
        val redColor = Color.parseColor("#DC2626")
        val grayColor = Color.parseColor("#6B7280")

        fun addSectionHeader(title: String) {
            val tv = TextView(this).apply {
                text = title
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.primary))
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, (8 * dp).toInt(), 0, (2 * dp).toInt())
            }
            container.addView(tv)
        }

        fun addResultRow(label: String, passed: Boolean, detail: String = "") {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (3 * dp).toInt(), 0, (3 * dp).toInt())
            }
            val icon = TextView(this).apply {
                text = if (passed) "\u2713" else "\u2717"
                textSize = 14f
                setTextColor(if (passed) greenColor else redColor)
                layoutParams = LinearLayout.LayoutParams((20 * dp).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val tvLabel = TextView(this).apply {
                text = label
                textSize = 13f
                setTextColor(if (passed) greenColor else redColor)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(icon)
            row.addView(tvLabel)
            if (detail.isNotEmpty()) {
                val tvDetail = TextView(this).apply {
                    text = detail
                    textSize = 11f
                    setTextColor(grayColor)
                }
                row.addView(tvDetail)
            }
            container.addView(row)
        }

        Thread {
            data class CheckResult(val label: String, val passed: Boolean, val detail: String = "")
            val results = mutableListOf<CheckResult>()

            val hasRecordAudio = ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            val hasPhoneState = ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_PHONE_STATE
            ) == PackageManager.PERMISSION_GRANTED

            val hasAnswerCalls = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ANSWER_PHONE_CALLS
            ) == PackageManager.PERMISSION_GRANTED

            val hasCallPhone = ContextCompat.checkSelfPermission(
                this, Manifest.permission.CALL_PHONE
            ) == PackageManager.PERMISSION_GRANTED

            val hasCaptureOutput = ContextCompat.checkSelfPermission(
                this, "android.permission.CAPTURE_AUDIO_OUTPUT"
            ) == PackageManager.PERMISSION_GRANTED

            results.add(CheckResult("RECORD_AUDIO", hasRecordAudio))
            results.add(CheckResult("CAPTURE_AUDIO_OUTPUT", hasCaptureOutput, if (hasCaptureOutput) "Magisk" else "needs Magisk"))
            results.add(CheckResult("ANSWER_PHONE_CALLS", hasAnswerCalls))
            results.add(CheckResult("CALL_PHONE", hasCallPhone))
            results.add(CheckResult("READ_PHONE_STATE", hasPhoneState))

            val telecomMgr = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val isDefaultDialer = packageName == telecomMgr.defaultDialerPackage
            results.add(CheckResult("Default Dialer", isDefaultDialer, if (isDefaultDialer) "" else "required for InCallService"))

            data class SourceTest(val source: Int, val name: String, val rate: Int)
            val sources = listOf(
                SourceTest(MediaRecorder.AudioSource.VOICE_DOWNLINK, "VOICE_DOWNLINK", 8000),
                SourceTest(MediaRecorder.AudioSource.VOICE_UPLINK, "VOICE_UPLINK", 8000),
                SourceTest(MediaRecorder.AudioSource.VOICE_CALL, "VOICE_CALL", 8000),
                SourceTest(MediaRecorder.AudioSource.VOICE_RECOGNITION, "VOICE_RECOGNITION", 8000),
                SourceTest(MediaRecorder.AudioSource.VOICE_COMMUNICATION, "VOICE_COMMUNICATION", 8000),
                SourceTest(MediaRecorder.AudioSource.MIC, "MIC", 8000)
            )

            val sourceResults = mutableListOf<CheckResult>()
            for (src in sources) {
                var ok = false
                var detail = ""
                try {
                    val minBuf = AudioRecord.getMinBufferSize(
                        src.rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                    )
                    if (minBuf > 0) {
                        val rec = AudioRecord(
                            src.source, src.rate,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            minBuf.coerceAtLeast(4096)
                        )
                        if (rec.state == AudioRecord.STATE_INITIALIZED) {
                            try {
                                rec.startRecording()
                                val buf = ByteArray(320)
                                val read = rec.read(buf, 0, buf.size)
                                ok = read > 0
                                if (!ok) detail = "read=$read"
                                rec.stop()
                            } catch (e: Exception) {
                                detail = e.message?.take(30) ?: "start failed"
                            }
                        } else {
                            detail = "init failed"
                        }
                        rec.release()
                    } else {
                        detail = "invalid buffer"
                    }
                } catch (e: Exception) {
                    detail = e.message?.take(30) ?: "error"
                }
                sourceResults.add(CheckResult(src.name, ok, detail))
            }

            val aecAvail = AcousticEchoCanceler.isAvailable()
            val nsAvail = NoiseSuppressor.isAvailable()

            data class PropCheck(val prop: String, val expected: String, val label: String)
            val propChecks = listOf(
                PropCheck("voice.record.conc.disabled", "false", "Concurrent recording"),
                PropCheck("voice.playback.conc.disabled", "false", "Concurrent playback"),
                PropCheck("voice.voip.conc.disabled", "false", "Concurrent VoIP")
            )
            val propResults = mutableListOf<CheckResult>()
            for (pc in propChecks) {
                val value = try {
                    @Suppress("PrivateApi")
                    val cls = Class.forName("android.os.SystemProperties")
                    val get = cls.getMethod("get", String::class.java, String::class.java)
                    get.invoke(null, pc.prop, "") as String
                } catch (_: Exception) { "" }
                val ok = value == pc.expected
                propResults.add(CheckResult(pc.label, ok, if (value.isNotEmpty()) "$value" else "not set"))
            }

            val hasRoot = try {
                // Modern Magisk doesn't place su at fixed paths — try executing it.
                val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                val exitCode = proc.waitFor()
                proc.destroy()
                exitCode == 0
            } catch (_: Exception) {
                // Fallback: check legacy paths
                try {
                    java.io.File("/system/bin/su").exists() ||
                        java.io.File("/system/xbin/su").exists() ||
                        java.io.File("/sbin/su").exists()
                } catch (_: Exception) { false }
            }

            val hasUsableSource = sourceResults.any { it.passed }
            val hasDownlink = sourceResults.firstOrNull { it.label == "VOICE_DOWNLINK" }?.passed == true

            runOnUiThread {
                addSectionHeader("Permissions")
                for (r in results) addResultRow(r.label, r.passed, r.detail)

                addSectionHeader("Audio Sources")
                for (r in sourceResults) addResultRow(r.label, r.passed, r.detail)

                addSectionHeader("Audio Effects")
                addResultRow("AcousticEchoCanceler", aecAvail)
                addResultRow("NoiseSuppressor", nsAvail)

                addSectionHeader("System Properties")
                for (r in propResults) addResultRow(r.label, r.passed, r.detail)

                addSectionHeader("System")
                addResultRow("Root (su)", hasRoot, if (hasRoot) "" else "needed for Magisk")

                val divider = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, (1 * dp).toInt()
                    ).apply { topMargin = (8 * dp).toInt(); bottomMargin = (8 * dp).toInt() }
                    setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.border_card))
                }
                container.addView(divider)

                val gatewayReady = hasRecordAudio && isDefaultDialer && hasUsableSource && hasCaptureOutput
                val verdict = TextView(this).apply {
                    text = if (gatewayReady) {
                        val src = if (hasDownlink) "VOICE_DOWNLINK" else
                            sourceResults.firstOrNull { it.passed }?.label ?: "?"
                        "\u2713 Gateway supported (capture: $src)"
                    } else {
                        val missing = mutableListOf<String>()
                        if (!hasRecordAudio) missing.add("RECORD_AUDIO")
                        if (!hasCaptureOutput) missing.add("CAPTURE_AUDIO_OUTPUT")
                        if (!isDefaultDialer) missing.add("Default Dialer")
                        if (!hasUsableSource) missing.add("audio source")
                        "\u2717 Not ready: missing ${missing.joinToString(", ")}"
                    }
                    textSize = 13f
                    setTextColor(if (gatewayReady) greenColor else redColor)
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }
                container.addView(verdict)

                onDone()
            }
        }.start()
    }

    private fun networkTypeName(type: Int): String = when (type) {
        TelephonyManager.NETWORK_TYPE_GPRS,
        TelephonyManager.NETWORK_TYPE_EDGE,
        TelephonyManager.NETWORK_TYPE_CDMA,
        TelephonyManager.NETWORK_TYPE_1xRTT,
        TelephonyManager.NETWORK_TYPE_IDEN -> "2G"
        TelephonyManager.NETWORK_TYPE_UMTS,
        TelephonyManager.NETWORK_TYPE_EVDO_0,
        TelephonyManager.NETWORK_TYPE_EVDO_A,
        TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_EVDO_B,
        TelephonyManager.NETWORK_TYPE_EHRPD,
        TelephonyManager.NETWORK_TYPE_HSPAP -> "3G"
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_NR -> "5G NR"
        else -> "Unknown"
    }

    // ── Call Log (Calls Tab) ─────────────────────────────

    /** Pre-load both IN and OUT lists off the main thread so tab switching is instant */
    /** Show the already-cached list for the current filter — runs on UI thread, no I/O */
    // ── Dialler ──────────────────────────────────────────

    // ── In-Call Screen ───────────────────────────────────

    private var inCallCloseScheduled = false

    /** Show "Call ended" briefly then close the in-call screen */
    private fun scheduleInCallClose() {
        if (!inCallOpen || inCallCloseScheduled) return
        inCallCloseScheduled = true
        tvInCallStatus.text = "Call ended"
        callTimerHandler.removeCallbacks(gsmPollRunnable)
        callTimerHandler.postDelayed({ closeInCallScreen() }, 1500)
    }

    /** Listen in on the live call through the speaker.  The microphone is not
     *  touched — it stays muted, so the room is never audible to either side. */
    private fun toggleMonitor() {
        monitoring = !monitoring
        startService(Intent(this, GatewayService::class.java).apply {
            action = GatewayService.ACTION_MONITOR
            putExtra(GatewayService.EXTRA_MONITOR_ON, monitoring)
        })
        updateMonitorButtons()
        appendLog(if (monitoring) "Snoop on — both sides on the speaker" else "Snoop off")
    }

    /**
     * The Call monitor badge is always in the header and is the setting's
     * switch: red while every call is put on the speaker, grey while not —
     * the same value as Settings → Call monitor.
     */
    private fun refreshMonitorBadge() {
        if (!::tvHomeMonitorBadge.isInitialized) return
        val on = getSharedPreferences("gateway", MODE_PRIVATE).getBoolean("call_monitor", false)
        val tint = ContextCompat.getColor(this, if (on) R.color.monitor_badge else R.color.text_secondary)
        androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(
            tvHomeMonitorBadge, ColorStateList.valueOf(tint)
        )
        tvHomeMonitorBadge.contentDescription =
            if (on) "Call monitor on: every call plays on the speaker. Tap to turn off."
            else "Call monitor off. Tap to put every call on the speaker."
    }

    /** Badge tap: flip Call monitor, and take the call in progress with it. */
    private fun toggleCallMonitor() {
        val prefs = getSharedPreferences("gateway", MODE_PRIVATE)
        val on = !prefs.getBoolean("call_monitor", false)
        prefs.edit().putBoolean("call_monitor", on).apply()
        // Here, not in refreshMonitorBadge: that runs on every status update
        // and would undo an unsaved tick in the settings screen.
        findViewById<CheckBox>(R.id.cbCfgCallMonitor)?.isChecked = on
        refreshMonitorBadge()
        if (callLive && monitoring != on) toggleMonitor()
        appendLog(if (on) "Call monitor on — every call on the speaker" else "Call monitor off")
        Toast.makeText(this, if (on) "Call monitor on" else "Call monitor off", Toast.LENGTH_SHORT).show()
    }

    private fun updateMonitorButtons() {
        if (::btnInCallMonitor.isInitialized) {
            btnInCallMonitor.text = if (monitoring) "SNOOP OFF" else "LISTEN IN"
        }
        if (::btnHomeSnoop.isInitialized) {
            setCallButtonState(
                btnHomeSnoop,
                // "Off", not "Stop": it switches the speaker off, it does not
                // stop anything about the call.
                if (monitoring) "Off" else "Snoop",
                if (monitoring) R.drawable.ic_fa_volume_xmark else R.drawable.ic_fa_headphones
            )
        }
    }

    private fun openInCallScreen(number: String) {
        inCallOpen = true
        inCallOpenTime = System.currentTimeMillis()
        inCallCloseScheduled = false
        callStartTime = 0L
        lastGsmPollState = -1
        tvInCallNumber.text = number
        tvInCallStatus.text = "Calling..."
        tvInCallTimer.visibility = View.GONE
        viewBeforeInCall = currentTab
        // Hide tabs, show in-call overlay
        tabbedRoot.visibility = View.GONE
        inCallView.visibility = View.VISIBLE
        callTimerHandler.removeCallbacks(gsmPollRunnable)
        callTimerHandler.postDelayed(gsmPollRunnable, 500)
    }

    private fun closeInCallScreen() {
        if (!inCallOpen) return
        inCallOpen = false
        // The monitor lives with the RTP session, which ends with the call, so
        // only the button label needs resetting for the next one.
        monitoring = false
        updateMonitorButtons()
        callTimerHandler.removeCallbacks(callTimerRunnable)
        callTimerHandler.removeCallbacks(gsmPollRunnable)
        callStartTime = 0L
        lastGsmPollState = -1
        inCallView.visibility = View.GONE
        tabbedRoot.visibility = View.VISIBLE
        gsmCallActive = false
        // Refresh the traffic list on returning from a call, so the call that
        // just ended is there.
        if (currentTab == "home") {
            refreshHome()
        }
    }

    private fun endCallFromInCallScreen() {
        val call = com.callagent.gateway.gsm.GsmCallManager.activeCall
        if (call != null) {
            tvInCallStatus.text = "Ending..."
            com.callagent.gateway.gsm.GsmCallManager.hangupCall()
        } else {
            closeInCallScreen()
        }
    }

    // ── Gateway Control ──────────────────────────────────

    private fun startGateway() {
        val prefs = getSharedPreferences("gateway", MODE_PRIVATE)
        val server = prefs.getString("server", "") ?: ""
        val port = prefs.getInt("port", 5060)
        val user = prefs.getString("user", "") ?: ""
        val pass = prefs.getString("pass", "") ?: ""

        if (server.isEmpty() || user.isEmpty()) {
            appendLog("ERROR: Open config and set server + username first")
            return
        }

        GatewayService.start(this, server, port, user, pass)

        running = true

        appendLog("Starting gateway: $user@$server:$port")
    }

    private fun stopGateway() {
        GatewayService.stop(this)
        running = false
    }

    private fun updateStatus(state: String, info: String) {
        // SNOOP only does anything while audio is flowing, so it follows the
        // bridge state rather than being permanently tappable.
        val wasLive = callLive
        callLive = state == "BRIDGED"
        // Call monitor: the service has already switched Snoop on for this
        // call (CallOrchestrator), so the button has to say Off from the start.
        if (callLive && !wasLive &&
            getSharedPreferences("gateway", MODE_PRIVATE).getBoolean("call_monitor", false)) {
            monitoring = true
        }
        updateMonitorButtons()
        updateHomeCall(state, info)

    }

    private fun appendLog(msg: String) {
        appendLogRaw("${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}  $msg")
    }

    /** Append a line that already carries its own timestamp. */
    private fun appendLogRaw(line: String) {
        runOnUiThread {
            tvLog.append("$line\n")
            svLog.post { svLog.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun clearLog() {
        tvLog.text = ""
        GatewayService.clearLogBuffer()
        Toast.makeText(this, "Log cleared", Toast.LENGTH_SHORT).show()
    }

    private fun copyLog() {
        val logText = tvLog.text.toString()
        if (logText.isEmpty()) {
            Toast.makeText(this, "Log is empty", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("callagent log", logText))
        Toast.makeText(this, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    // ── Helpers ──────────────────────────────────────────

    private fun formatDuration(totalSeconds: Long): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    private fun formatDurationCompact(totalSeconds: Long): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }

    private fun resolveThemeColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return ContextCompat.getColor(this, tv.resourceId)
    }

    // ── Permissions ─────────────────────────────────────

    private fun requestPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS
            // Deliberately not location.  A gateway has no business asking for
            // it, and the two things that use it are cosmetic: the cell-id
            // readout in the info dialog, and the WiFi SSID (getSSID() has
            // returned "<unknown ssid>" without location since Android 8.1).
            // Both degrade to a placeholder instead.
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            perms.add(Manifest.permission.READ_PHONE_NUMBERS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_PERMS)
        }
    }

    private fun requestDefaultDialerRole() {
        val tm = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        if (packageName == tm.defaultDialerPackage) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = getSystemService(Context.ROLE_SERVICE) as RoleManager
            if (rm.isRoleAvailable(RoleManager.ROLE_DIALER) &&
                !rm.isRoleHeld(RoleManager.ROLE_DIALER)
            ) {
                startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER), REQ_DEFAULT_DIALER)
            }
        } else {
            @Suppress("DEPRECATION")
            val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
            }
            startActivityForResult(intent, REQ_DEFAULT_DIALER)
        }
    }

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_DEFAULT_DIALER) {
            if (resultCode == RESULT_OK) {
                appendLog("Set as default phone app")
            } else {
                appendLog("WARN: Not set as default phone app — GSM call handling disabled")
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                registerContactsObserver()
                preloadContactsAsync(forceReload = true)
            }
            val denied = permissions.zip(grantResults.toTypedArray())
                .filter { it.second != PackageManager.PERMISSION_GRANTED }
                .map { it.first.substringAfterLast('.') }
            if (denied.isNotEmpty()) {
                appendLog("WARN: Denied permissions: ${denied.joinToString()}")
            }
        }
    }

    companion object {
        private const val REQ_PERMS = 100
        private const val REQ_DEFAULT_DIALER = 101
        private const val MAX_CALL_LOG = 20
    }
}
