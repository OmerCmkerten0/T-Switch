package com.callagent.gateway.localsend

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.callagent.gateway.RootShell
import com.callagent.gateway.gsm.GsmCallManager
import com.callagent.gateway.service.CallLogEntry
import com.callagent.gateway.service.CallLogStore
import com.callagent.gateway.service.GatewayService
import com.callagent.gateway.sip.SipClient
import com.callagent.gateway.sms.OutboundSms
import com.callagent.gateway.sms.SmsOutbox
import com.callagent.gateway.sms.SmsSender
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Manages the "Local Send" Zoiper fake-SMS command/terminal channel ("0000")
 * and real-time critical battery alerts (sent on every 1% battery drop).
 *
 * All messages here travel over local SIP MESSAGE (RFC 3428) directly between
 * T-Switch and Zoiper over Hotspot/LAN without ever touching the GSM modem or
 * spending any carrier SMS.
 */
object LocalSendController {
    private const val TAG = "LocalSendController"

    /** Virtual sender number that appears as the command/terminal profile in Zoiper. */
    const val TERMINAL_NUMBER = "0000"
    const val TERMINAL_DISPLAY_NAME = "T-Switch Terminal"

    private val TERMINAL_TARGETS = setOf(
        "0000", "tswitch", "t-switch", "9999", "cmd", "terminal", "bash", "root"
    )

    const val DEFAULT_CRITICAL_BATTERY_PERCENT = 15

    const val DEFAULT_CUSTOM_COMMANDS =
        "!uptime = uptime\n" +
        "!ag = ip -4 addr show | grep -E 'inet '\n" +
        "!hafiza = free -m\n" +
        "!sicaklik = dumpsys battery | grep temperature"

    private val registeredReceiver = AtomicBoolean(false)
    @Volatile private var appContext: Context? = null
    @Volatile private var activeSipProvider: (() -> SipClient?)? = null
    @Volatile private var logCallback: ((String) -> Unit)? = null

    /** Last battery percentage for which we sent a critical alert (-1 = none). */
    @Volatile private var lastAlertedPercent: Int = -1
    @Volatile private var lastKnownPercent: Int = -1
    @Volatile private var lastKnownCharging: Boolean = false
    @Volatile private var lastKnownTempTenths: Int = 0
    @Volatile private var lastKnownVoltageMv: Int = 0
    @Volatile private var lastWelcomePeerIp: String = ""
    @Volatile private var pendingCarrierQueryUntilMs: Long = 0L

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_BATTERY_CHANGED) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val pct = if (level >= 0) (level * 100) / scale else return
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL ||
                plugged != 0
            val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
            val voltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)

            lastKnownPercent = pct
            lastKnownCharging = isCharging
            lastKnownTempTenths = tempTenths
            lastKnownVoltageMv = voltageMv

            val prefs = context.getSharedPreferences("gateway", Context.MODE_PRIVATE)
            val localSendOn = prefs.getBoolean("local_send_enabled", false)
            if (!localSendOn) {
                lastAlertedPercent = -1
                return
            }

            val criticalThreshold = prefs.getInt(
                "critical_battery_threshold",
                DEFAULT_CRITICAL_BATTERY_PERCENT
            ).coerceIn(3, 50)

            if (isCharging) {
                if (lastAlertedPercent != -1) {
                    val prev = lastAlertedPercent
                    lastAlertedPercent = -1
                    val msg = "🔌 [BATARYA ŞARJDA — %$pct]\n" +
                        "T-Switch şarja takıldı (Önceki kritik seviye: %$prev).\n" +
                        "Kritik batarya uyarıları durduruldu."
                    logCallback?.invoke("BATTERY: Charger connected at $pct%, clearing critical state")
                    sendFakeSmsToZoiper(context, msg, logToHistory = true)
                }
                return
            }

            // Discharging and at or below critical threshold:
            // Send a fake SMS on EVERY 1% drop!
            if (pct <= criticalThreshold) {
                if (lastAlertedPercent == -1 || pct < lastAlertedPercent) {
                    lastAlertedPercent = pct
                    val tempStr = if (tempTenths > 0) "${tempTenths / 10}.${tempTenths % 10}°C" else "—"
                    val voltStr = if (voltageMv > 0) "${voltageMv}mV" else "—"
                    val alertText =
                        "🚨 [KRİTİK BATARYA UYARISI: %$pct]\n" +
                        "T-Switch cihazının bataryası %$pct seviyesine düştü!\n" +
                        "• Durum: Deşarj Oluyor (Şarjda Değil)\n" +
                        "• Sıcaklık / Voltaj: $tempStr / $voltStr\n" +
                        "• Kritik Eşik: %$criticalThreshold\n" +
                        "⚠️ Hotspot ve GSM köprüsünün kapanmaması için lütfen cihazı hemen şarja takın!"
                    logCallback?.invoke("BATTERY CRITICAL: $pct% — sending fake SMS alert to Zoiper")
                    sendFakeSmsToZoiper(context, alertText, logToHistory = true)
                }
            } else {
                // Above threshold
                lastAlertedPercent = -1
            }
        }
    }

    /**
     * Starts listening for battery changes and binds the current SipClient provider.
     */
    fun attach(
        context: Context,
        sipProvider: () -> SipClient?,
        logger: (String) -> Unit
    ) {
        val appCtx = context.applicationContext
        appContext = appCtx
        activeSipProvider = sipProvider
        logCallback = logger

        if (registeredReceiver.compareAndSet(false, true)) {
            try {
                val sticky = appCtx.registerReceiver(
                    batteryReceiver,
                    IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                )
                sticky?.let { batteryReceiver.onReceive(appCtx, it) }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register batteryReceiver: ${e.message}")
            }
        }
    }

    fun detach() {
        val ctx = appContext ?: return
        if (registeredReceiver.compareAndSet(true, false)) {
            try {
                ctx.unregisterReceiver(batteryReceiver)
            } catch (_: Exception) {}
        }
        lastAlertedPercent = -1
        lastWelcomePeerIp = ""
    }

    fun triggerPushWakeIfNeeded(callerNumber: String): Boolean {
        val ctx = appContext ?: return false
        if (!PushWakeController.isConfigured(ctx)) return false
        PushWakeController.triggerWakeAsync(ctx, callerNumber)
        return true
    }

    fun sendWelcomeIfNeeded(context: Context, force: Boolean = false): Boolean {
        val sip = activeSipProvider?.invoke() ?: return false
        val peer = sip.resolveLocalPeerAddress(context) ?: return false
        onZoiperPeerReady(context, peer.first, peer.second, forceWelcome = force)
        return true
    }

    /**
     * Called when a Zoiper peer registers or sends its first packet in Local Send mode.
     * Automatically seeds the "0000" (T-Switch Terminal) conversation in Zoiper.
     */
    fun onZoiperPeerReady(context: Context, peerIp: String, peerPort: Int, forceWelcome: Boolean = false) {
        val peerKey = "$peerIp:$peerPort"
        if (!forceWelcome && peerKey == lastWelcomePeerIp) return
        lastWelcomePeerIp = peerKey
        thread(name = "localsend-welcome") {
            try {
                // Wait briefly so Zoiper finishes its REGISTER 200 OK state transition
                Thread.sleep(350)
                val batt = readBatteryQuick(context)
                val welcome = buildString {
                    appendLine("⚡ T-Switch Terminal [Local Send Aktif]")
                    appendLine("Bu kanal sahte SMS (SIP MESSAGE) üzerinden çalışır, GSM SMS harcamaz.")
                    appendLine("• Bağlı Cihaz: $peerIp:$peerPort")
                    appendLine("• Batarya: $batt")
                    appendLine("")
                    appendLine("📌 Hazır Komutlar:")
                    appendLine("!batarya -> Batarya ve şarj raporu")
                    appendLine("!ekran -> Ekran açık/kapalı durumu (!ekran ac / kapat)")
                    appendLine("!kiosk -> Kiosk & Güç/Veri Tasarruf Modu Aç/Kapat")
                    appendLine("!gb -> Turkcell'e KALAN SMS atıp güncel GB sor")
                    appendLine("!internet -> Mobil veri + Hotspot interneti Aç/Kapat")
                    appendLine("!geneldurum -> Tüm kritik sistemlerin raporu")
                    appendLine("!snoop -> Çağrı hoparlör dinlemesini Aç/Kapat")
                    appendLine("!yardim -> Tüm komutlar ve özel kısayollar")
                    append("$ <komut> -> Doğrudan root bash komutu (veya bu sohbete doğrudan komut yazın)")
                }
                sendFakeSmsToZoiper(context, welcome, logToHistory = false)
                // If battery is already critical when Zoiper connects, immediately fire the alert too
                val prefs = context.getSharedPreferences("gateway", Context.MODE_PRIVATE)
                val threshold = prefs.getInt("critical_battery_threshold", DEFAULT_CRITICAL_BATTERY_PERCENT)
                if (!lastKnownCharging && lastKnownPercent in 1..threshold) {
                    lastAlertedPercent = lastKnownPercent
                    val alertText =
                        "🚨 [KRİTİK BATARYA UYARISI: %$lastKnownPercent]\n" +
                        "T-Switch cihazının şarjı şu an %$lastKnownPercent seviyesinde (Şarjda Değil)!\n" +
                        "Lütfen cihazı şarja takın!"
                    sendFakeSmsToZoiper(context, alertText, logToHistory = true)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Welcome message failed: ${e.message}")
            }
        }
    }

    /**
     * Returns true if an incoming SIP MESSAGE from Zoiper is addressed to the
     * virtual T-Switch Terminal ("0000", "tswitch", etc.) OR starts with `!` or `$`.
     */
    fun isTerminalCommand(targetUser: String, body: String): Boolean {
        val cleanTarget = targetUser.trim().lowercase()
        val cleanBody = body.trim()
        if (cleanTarget in TERMINAL_TARGETS) return true
        if (cleanBody.startsWith("!") || cleanBody.startsWith("$")) return true
        return false
    }

    /**
     * Executes a command received from Zoiper over the fake SMS channel and sends
     * the response back to Zoiper as a fake SMS from `0000` ("T-Switch Terminal").
     */
    fun handleTerminalMessage(context: Context, targetUser: String, rawBody: String) {
        val cmdLine = rawBody.trim()
        if (cmdLine.isEmpty()) return

        runCatching {
            CallLogStore.addEntry(
                context,
                CallLogEntry(
                    direction = "OUT",
                    number = "$TERMINAL_NUMBER ($TERMINAL_DISPLAY_NAME)",
                    timestamp = System.currentTimeMillis(),
                    durationSec = 0,
                    type = CallLogStore.TYPE_SMS,
                    text = cmdLine,
                    status = "command"
                )
            )
        }
        logCallback?.invoke("TERMINAL CMD from Zoiper [$targetUser]: $cmdLine")

        thread(name = "localsend-cmd") {
            val reply = try {
                executeCommand(context, cmdLine)
            } catch (e: Exception) {
                "❌ Komut hatası: ${e.javaClass.simpleName}: ${e.message}"
            }
            sendFakeSmsToZoiper(context, reply, logToHistory = true)
        }
    }

    private fun executeCommand(context: Context, input: String): String {
        val trimmed = input.trim()
        val firstToken = trimmed.substringBefore(' ').lowercase()
        val restArgs = trimmed.substringAfter(' ', "").trim()

        // 1. Check built-in `!` commands (also work without `!` when sent inside 0000 chat)
        when (firstToken) {
            "!batarya", "batarya", "!battery", "!pil" -> {
                return buildBatteryReport(context)
            }
            "!ekran", "ekran", "!screen" -> {
                return when (restArgs.lowercase()) {
                    "ac", "aç", "on", "1" -> KioskController.setScreenOn(context, true)
                    "kapat", "kapa", "off", "0" -> KioskController.setScreenOn(context, false)
                    else -> KioskController.buildScreenStatusReport(context)
                }
            }
            "!kiosk", "kiosk" -> {
                val targetState = when (restArgs.lowercase()) {
                    "ac", "aç", "on", "1" -> true
                    "kapat", "kapa", "off", "0" -> false
                    else -> !KioskController.isKioskEnabled(context)
                }
                return KioskController.applyKioskMode(context, targetState)
            }
            "!debloat", "debloat", "!hizlandir", "hizlandir" -> {
                return KioskController.debloatAndBoost(context)
            }
            "!gb", "gb", "!kalan", "kalan", "!turkcell", "turkcell" -> {
                return queryTurkcellRemainingGb(context, restArgs)
            }
            "!dns", "dns", "!mdns", "mdns", "!domain" -> {
                LocalMdnsResponder.announceNow(context)
                return "🌐 [Yerel Sabit Domain]\n• ${LocalMdnsResponder.statusSummary(context)}\n• Kullanım: Linphone veya yerel ağda `tswitch.local` yazarak bağlanabilirsiniz."
            }
            "!push", "push" -> {
                val lowerArgs = restArgs.lowercase()
                return when {
                    lowerArgs.startsWith("sifre ") || lowerArgs.startsWith("şifre ") || lowerArgs.startsWith("pass ") -> {
                        val pw = restArgs.substringAfter(' ').trim()
                        if (pw.isEmpty()) "⚠️ Kullanım: `!push sifre <t-switch_pusher_sifresi>`"
                        else {
                            PushWakeController.setPassword(context, pw)
                            "✅ `t-switch_pusher@sip.linphone.org` şifresi kaydedildi ve Hibrit Apple PushKit AKTİF edildi!\nTest için `!push test` yazabilirsiniz."
                        }
                    }
                    lowerArgs == "ac" || lowerArgs == "aç" || lowerArgs == "on" -> {
                        PushWakeController.setEnabled(context, true)
                        PushWakeController.statusReport(context)
                    }
                    lowerArgs == "kapat" || lowerArgs == "off" -> {
                        PushWakeController.setEnabled(context, false)
                        PushWakeController.statusReport(context)
                    }
                    lowerArgs == "test" -> {
                        if (!PushWakeController.isConfigured(context)) {
                            "⚠️ Önce şifreyi girin: `!push sifre <t-switch_pusher_sifresi>`"
                        } else {
                            PushWakeController.triggerWakeAsync(context, "T-Switch Test", holdMs = 5_000L)
                            "🔔 `t-switch_receiver@sip.linphone.org` adresine 5 sn'lik Apple PushKit uyandırma testi gönderildi!"
                        }
                    }
                    else -> PushWakeController.statusReport(context)
                }
            }
            "!internet", "internet", "!net", "!veri" -> {
                return HotspotHelper.toggleInternetSharing(context)
            }
            "!geneldurum", "geneldurum", "!durum", "!status" -> {
                return buildGeneralStatusReport(context)
            }
            "!snoop", "!dinle", "snoop" -> {
                val prefs = context.getSharedPreferences("gateway", Context.MODE_PRIVATE)
                val next = !prefs.getBoolean("call_monitor", false)
                prefs.edit().putBoolean("call_monitor", next).apply()
                val intent = Intent(context, GatewayService::class.java).apply {
                    action = GatewayService.ACTION_MONITOR
                    putExtra(GatewayService.EXTRA_MONITOR_ON, next)
                }
                runCatching { ContextCompat.startForegroundService(context, intent) }
                return if (next) {
                    "🔊 [T-Switch] Çağrı Hoparlör Dinleme (Snoop): AÇILDI ✅"
                } else {
                    "🔇 [T-Switch] Çağrı Hoparlör Dinleme (Snoop): KAPATILDI ❌"
                }
            }
            "!yardim", "yardim", "!help", "help", "?" -> {
                return buildHelpReport(context)
            }
        }

        // 2. Check user-defined custom commands from Settings (`!name = shell_command`)
        val customMap = loadCustomCommands(context)
        val matchedCustom = customMap[firstToken] ?: customMap["!$firstToken"]
        if (matchedCustom != null) {
            val fullShellCmd = if (restArgs.isNotEmpty()) "$matchedCustom $restArgs" else matchedCustom
            return runRootBash(fullShellCmd)
        }

        // 3. Explicit shell prefix (`$ <cmd>` or `!sh <cmd>` or `!bash <cmd>`)
        if (trimmed.startsWith("$")) {
            val shellCmd = trimmed.removePrefix("$").trim()
            if (shellCmd.isEmpty()) return "⚠️ Kullanım: $ <terminal_komutu>"
            return runRootBash(shellCmd)
        }
        if (firstToken == "!sh" || firstToken == "!bash" || firstToken == "!cmd") {
            if (restArgs.isEmpty()) return "⚠️ Kullanım: $firstToken <terminal_komutu>"
            return runRootBash(restArgs)
        }

        // 4. If it started with `!` but wasn't a built-in or custom alias, strip `!` and run in bash,
        // or if sent directly inside the 0000 chat without `!`, run directly as a bash command!
        val rawShell = if (trimmed.startsWith("!")) trimmed.removePrefix("!").trim() else trimmed
        return runRootBash(rawShell)
    }

    private fun runRootBash(shellCmd: String): String {
        val t0 = System.currentTimeMillis()
        val (exitCode, out) = RootShell.execWithStatus(shellCmd, timeoutMs = 15_000)
        val elapsed = System.currentTimeMillis() - t0
        val cleanOut = out.trim().let {
            if (it.length > 1400) it.take(1400) + "\n…(çıktı kısaltıldı)"
            else it.ifEmpty { "(çıktı yok)" }
        }
        return "💻 $ $shellCmd\n" +
            "────────────────────\n" +
            "$cleanOut\n" +
            "────────────────────\n" +
            "[rc=$exitCode · ${elapsed}ms]"
    }

    fun loadCustomCommands(context: Context): Map<String, String> {
        val prefs = context.getSharedPreferences("gateway", Context.MODE_PRIVATE)
        val raw = prefs.getString("custom_commands", DEFAULT_CUSTOM_COMMANDS) ?: DEFAULT_CUSTOM_COMMANDS
        val map = linkedMapOf<String, String>()
        for (line in raw.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val eq = trimmed.indexOf('=')
            if (eq <= 0) continue
            val key = trimmed.substring(0, eq).trim().lowercase().let {
                if (it.startsWith("!")) it else "!$it"
            }
            val cmd = trimmed.substring(eq + 1).trim()
            if (key.length > 1 && cmd.isNotEmpty()) {
                map[key] = cmd
            }
        }
        return map
    }

    private fun readBatteryQuick(context: Context): String {
        val sticky = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: lastKnownPercent
        val scale = (sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100).coerceAtLeast(1)
        val pct = if (level >= 0) (level * 100) / scale else -1
        val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val stateStr = if (plugged != 0) "Şarjda ⚡" else "Deşarj"
        return if (pct >= 0) "%$pct ($stateStr)" else "Bilinmiyor"
    }

    fun buildBatteryReport(context: Context): String {
        val sticky = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: lastKnownPercent
        val scale = (sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100).coerceAtLeast(1)
        val pct = if (level >= 0) (level * 100) / scale else -1
        val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val tempTenths = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: lastKnownTempTenths
        val voltageMv = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: lastKnownVoltageMv
        val health = sticky?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1

        val prefs = context.getSharedPreferences("gateway", Context.MODE_PRIVATE)
        val crit = prefs.getInt("critical_battery_threshold", DEFAULT_CRITICAL_BATTERY_PERCENT)

        val plugName = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC Adaptör ⚡"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB Kablo 🔌"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Kablosuz Şarj ⚡"
            else -> "Şarjda Değil (Batarya)"
        }
        val statusName = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "Şarj Oluyor"
            BatteryManager.BATTERY_STATUS_FULL -> "Tam Dolu (%100)"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "Deşarj Oluyor"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Şarj Olmuyor"
            else -> "Bilinmiyor"
        }
        val healthName = when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "İyi"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Aşırı Isınmış!"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Ölü"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Yüksek Voltaj"
            else -> "Normal"
        }
        val tempStr = if (tempTenths > 0) "${tempTenths / 10}.${tempTenths % 10} °C" else "—"
        val icon = when {
            plugged != 0 -> "🔌"
            pct <= crit -> "🚨"
            pct <= 30 -> "⚠️"
            else -> "🔋"
        }

        return buildString {
            appendLine("$icon [T-Switch Batarya Raporu]")
            appendLine("• Seviye       : %$pct (Kritik Eşik: %$crit)")
            appendLine("• Durum        : $statusName")
            appendLine("• Güç Kaynağı  : $plugName")
            appendLine("• Sıcaklık     : $tempStr")
            appendLine("• Voltaj       : ${if (voltageMv > 0) "$voltageMv mV" else "—"}")
            append("• Pil Sağlığı  : $healthName")
        }
    }

    @Suppress("MissingPermission")
    fun buildGeneralStatusReport(context: Context): String {
        val prefs = context.getSharedPreferences("gateway", Context.MODE_PRIVATE)
        val hs = HotspotHelper.detectHotspot(context)
        val sip = activeSipProvider?.invoke()
        val peer = sip?.registeredPeer
        val dataOn = HotspotHelper.isMobileDataEnabled(context)

        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val sm = context.getSystemService(SubscriptionManager::class.java)
        val sims = runCatching { sm?.activeSubscriptionInfoList.orEmpty() }.getOrDefault(emptyList())

        val simLines = if (sims.isEmpty()) {
            val op = tm?.networkOperatorName?.ifEmpty { "Yok" } ?: "Yok"
            "  - SIM: $op"
        } else {
            sims.joinToString("\n") { info ->
                val slot = info.simSlotIndex + 1
                val carrier = info.carrierName ?: info.displayName ?: "?"
                val num = prefs.getString("own_number_slot_${info.simSlotIndex}", "").orEmpty()
                    .ifEmpty { prefs.getString("own_number", "").orEmpty() }
                    .ifEmpty { "Numara tanımsız" }
                "  - SIM $slot ($carrier): $num"
            }
        }

        val callState = GsmCallManager.currentNumber?.let { "Aktif Çağrı ($it)" } ?: "Boşta (IDLE)"
        val rootState = RootShell.rootState()
        val codec = prefs.getString("codec", "g722")?.uppercase() ?: "G722"
        val snoop = if (prefs.getBoolean("call_monitor", false)) "Açık" else "Kapalı"
        val uptimeMin = SystemClock.elapsedRealtime() / 60_000L
        val uptimeStr = "${uptimeMin / 60}s ${uptimeMin % 60}dk"

        val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager
        val isDialer = if (telecom?.defaultDialerPackage == context.packageName) "Evet ✅" else "Hayır ❌"

        return buildString {
            appendLine("📊 [T-Switch Genel Durum Raporu]")
            appendLine("• Cihaz        : ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})")
            appendLine("• Çalışma Süresi: $uptimeStr")
            appendLine("• Batarya      : ${readBatteryQuick(context)}")
            appendLine("── Ağ & Local Send ──")
            appendLine("• Hotspot IP   : ${hs.ipAddress} (${hs.ifaceName.ifEmpty { "—" }})")
            appendLine("• Sabit Domain : ${LocalMdnsResponder.statusSummary(context)}")
            appendLine("• Zoiper İstemci: ${peer?.let { "${it.first}:${it.second} ✅" } ?: "Kayıtlı/ARP (${hs.connectedClients.joinToString().ifEmpty { "Yok" }})"}")
            appendLine("• Mobil Veri   : ${if (dataOn) "Açık 🌐" else "Kapalı 📴"}")
            appendLine("── GSM & SIM ──")
            appendLine(simLines)
            appendLine("• Çağrı Durumu : $callState")
            appendLine("• Varsayılan Tel: $isDialer")
            appendLine("── Ses & Sistem ──")
            appendLine("• Root (su)    : $rootState")
            appendLine("• Ekran        : ${if (KioskController.isScreenOn(context)) "AÇIK ⚠️" else "KAPALI 🌑"}")
            appendLine("• Kiosk Modu   : ${if (KioskController.isKioskEnabled(context)) "AÇIK 🔒" else "KAPALI"}")
            append("• Kodek / Snoop: $codec / $snoop")
        }
    }

    /**
     * Sends an automated carrier SMS (default: `KALAN` to `2200` for Turkcell) and opens
     * a 3-minute window to forward the carrier's reply directly to the `0000` Linphone chat.
     */
    @Suppress("MissingPermission")
    fun queryTurkcellRemainingGb(context: Context, args: String = ""): String {
        val parts = args.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val shortCode = parts.getOrNull(0)?.takeIf { it.all { ch -> ch.isDigit() } } ?: "2200"
        val smsBody = when {
            parts.size >= 2 -> parts.drop(1).joinToString(" ")
            parts.size == 1 && !parts[0].all { it.isDigit() } -> parts[0]
            else -> "KALAN"
        }

        val sm = context.getSystemService(SubscriptionManager::class.java)
        val subId = runCatching {
            sm?.activeSubscriptionInfoList?.firstOrNull()?.subscriptionId
                ?: SubscriptionManager.getDefaultSmsSubscriptionId()
        }.getOrDefault(SubscriptionManager.INVALID_SUBSCRIPTION_ID)

        val sms = OutboundSms(
            id = "gb-${System.currentTimeMillis()}",
            to = shortCode,
            text = smsBody,
            subId = subId
        )
        SmsOutbox.add(context, sms)
        if (!SmsOutbox.claimForDispatch(context, sms.id)) {
            Log.w(TAG, "queryTurkcellRemainingGb: outbox entry ${sms.id} already claimed, skipping duplicate dispatch")
            return "❌ Operatör SMS'i ($shortCode → \"$smsBody\") zaten gönderim kuyruğunda."
        }
        pendingCarrierQueryUntilMs = System.currentTimeMillis() + 180_000L
        val ok = SmsSender.dispatch(context, sms)
        runCatching {
            CallLogStore.addEntry(
                context,
                CallLogEntry(
                    direction = "OUT",
                    number = shortCode,
                    timestamp = System.currentTimeMillis(),
                    durationSec = 0L,
                    type = CallLogStore.TYPE_SMS,
                    text = smsBody,
                    smsId = sms.id,
                    status = if (ok) "sent" else "failed"
                )
            )
        }
        return if (ok) {
            "📶 [Turkcell GB Sorgusu Gönderildi]\n" +
                "• Hedef: $shortCode | Mesaj: \"$smsBody\"\n" +
                "⏳ Operatör yanıtı geldiğinde otomatik olarak buraya (0000) düşecek."
        } else {
            pendingCarrierQueryUntilMs = 0L
            "❌ Operatör SMS'i ($shortCode → \"$smsBody\") gönderilemedi. SIM/hat durumunu kontrol edin."
        }
    }

    /**
     * Called by [com.callagent.gateway.sms.SmsReceiver] when an incoming GSM SMS arrives.
     * If a carrier balance/GB query is active or the sender is Turkcell (`2200`/`2222`/`TURKCELL`),
     * mirrors the reply straight into the `0000` Linphone terminal chat.
     */
    fun onIncomingCarrierSms(context: Context, sender: String, text: String) {
        val prefs = context.getSharedPreferences("gateway", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("local_send_enabled", false)) return
        val upper = sender.uppercase()
        val isCarrier = upper.contains("TURKCELL") || upper == "2200" || upper == "2222" || upper == "532"
        val queryActive = System.currentTimeMillis() <= pendingCarrierQueryUntilMs
        if (!queryActive && !isCarrier) return
        if (queryActive && isCarrier) {
            pendingCarrierQueryUntilMs = 0L
        }
        val msg = "📶 [Operatör SMS — $sender]\n$text"
        sendFakeSmsToZoiper(context, msg, logToHistory = true)
    }

    private fun buildHelpReport(context: Context): String {
        val custom = loadCustomCommands(context)
        return buildString {
            appendLine("🛠️ [T-Switch Komut Kanalı Yardım]")
            appendLine("• !batarya     : Pil yüzdesi, sıcaklık ve şarj durumu")
            appendLine("• !ekran       : Ekran açık/kapalı durumu (!ekran ac / !ekran kapat)")
            appendLine("• !kiosk       : Kiosk & Veri/Pil Tasarruf modunu Aç/Kapat")
            appendLine("• !gb          : Turkcell'e KALAN SMS atıp güncel GB bilgisini getirir")
            appendLine("• !internet    : Mobil veri + Hotspot internet paylaşımı Aç/Kapat")
            appendLine("• !geneldurum  : Tüm kritik fonksiyonların detaylı raporu")
            appendLine("• !snoop       : Hoparlörden canlı dinlemeyi Aç/Kapat")
            appendLine("• $ <komut>    : Root kabuğunda bash komutu çalıştırır")
            if (custom.isNotEmpty()) {
                appendLine("── Özel Komutlarınız ──")
                for ((k, v) in custom) {
                    appendLine("• $k -> $v")
                }
            }
        }
    }

    /**
     * Dispatches a SIP MESSAGE from `0000` ("T-Switch Terminal") directly to the
     * connected Zoiper client over LAN/Hotspot.
     */
    fun sendFakeSmsToZoiper(
        context: Context,
        body: String,
        logToHistory: Boolean = false
    ): Boolean {
        if (logToHistory) {
            runCatching {
                CallLogStore.addEntry(
                    context,
                    CallLogEntry(
                        direction = "IN",
                        number = "$TERMINAL_NUMBER ($TERMINAL_DISPLAY_NAME)",
                        timestamp = System.currentTimeMillis(),
                        durationSec = 0,
                        type = CallLogStore.TYPE_SMS,
                        text = body,
                        status = "sent (local)"
                    )
                )
            }
            val firstLine = body.lineSequence().firstOrNull().orEmpty()
            logCallback?.invoke("TERMINAL SMS → Zoiper: $firstLine")
        }

        val sip = activeSipProvider?.invoke() ?: return false
        if (!sip.localSendMode) {
            val receiverUser = PushWakeController.getReceiverUser(context)
            val targetUri = "sip:$receiverUser@${sip.serverDomain}"
            thread(name = "localsend-fakesms") {
                val code = sip.sendSipMessage(
                    targetUri = targetUri,
                    fromUser = sip.username,
                    body = body,
                    fromDisplayName = TERMINAL_DISPLAY_NAME
                )
                Log.i(TAG, "Fake SMS via SIP server ($targetUri) answered $code")
            }
            return true
        }

        val targetAddress = sip.resolveLocalPeerAddress(context) ?: run {
            Log.w(TAG, "sendFakeSmsToZoiper: No Zoiper peer address found yet")
            return false
        }
        val peerUser = sip.registeredPeerUser.ifEmpty { "zoiper" }
        val targetUri = "sip:$peerUser@${targetAddress.first}:${targetAddress.second}"

        thread(name = "localsend-fakesms") {
            val code = sip.sendSipMessageToAddress(
                targetUri = targetUri,
                targetAddress = targetAddress,
                fromUser = TERMINAL_NUMBER,
                fromDisplayName = TERMINAL_DISPLAY_NAME,
                body = body
            )
            Log.i(TAG, "Fake SMS to Zoiper ($targetUri) answered $code")
        }
        return true
    }
}
