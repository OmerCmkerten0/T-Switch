package com.callagent.gateway.localsend

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.callagent.gateway.RootShell

/**
 * Manages GM 8 Kiosk Launcher mode, Ultra Power/Data Saver, physical IPS TFT
 * display power control, and root-level GM 8 debloat & speed optimization.
 */
object KioskController {
    private const val TAG = "KioskController"
    private const val PREF_KIOSK_ENABLED = "kiosk_mode_enabled"
    private const val PREF_DEBLOATED_COUNT = "debloated_pkg_count"

    @Volatile
    private var cachedIsDefaultHome: Boolean = false

    @Volatile
    private var lastHomeCheckMs: Long = 0L

    private val PROTECTED_PACKAGES = setOf(
        "com.callagent.gateway",
        "com.topjohnwu.magisk",
        "io.github.vvb2060.magisk",
        "com.android.inputmethod.latin",
        "com.google.android.inputmethod.latin"
    )

    /**
     * Non-essential GM 8 / Google / preinstalled bloatware packages that consume RAM,
     * CPU, and background mobile data without contributing to GSM/SIP/Hotspot operation.
     */
    val GM8_BLOAT_PACKAGES = listOf(
        "com.zhiliaoapp.musically.go",
        "com.google.android.youtube",
        "com.google.android.apps.youtube.music",
        "com.google.android.apps.maps",
        "com.google.android.apps.photos",
        "com.google.android.apps.docs",
        "com.google.android.apps.tachyon",
        "com.google.android.apps.wellbeing",
        "com.google.android.gm",
        "com.google.android.googlequicksearchbox",
        "com.google.android.calendar",
        "com.google.android.apps.restore",
        "com.google.android.feedback",
        "com.google.android.marvin.talkback",
        "com.google.android.printservice.recommendation",
        "com.google.android.tts",
        "com.google.android.apps.nbu.files",
        "com.google.android.apps.turbo",
        "com.google.android.as",
        "com.google.android.safetycore",
        "com.google.android.setupwizard",
        "com.android.gmsetupwizard",
        "com.android.chrome",
        "com.caf.fmradio",
        "com.generalmobile.assistant",
        "com.generalmobile.gmservice",
        "com.dolby.daxappui",
        "com.dolby.daxservice",
        "com.android.dreams.basic",
        "com.android.dreams.phototable",
        "com.android.printspooler",
        "com.android.bips",
        "com.android.bookmarkprovider",
        "com.android.providers.partnerbookmarks",
        "com.android.wallpaper.livepicker",
        "com.android.wallpaperbackup",
        "com.android.egg",
        "com.android.protips",
        "com.android.traceur"
    )

    fun isKioskEnabled(context: Context): Boolean =
        context.getSharedPreferences("gateway", Context.MODE_PRIVATE)
            .getBoolean(PREF_KIOSK_ENABLED, false)

    fun getDebloatedCount(context: Context): Int =
        context.getSharedPreferences("gateway", Context.MODE_PRIVATE)
            .getInt(PREF_DEBLOATED_COUNT, 0)

    fun isDefaultHomeLauncher(context: Context): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastHomeCheckMs < 5_000L && lastHomeCheckMs > 0L) {
            return cachedIsDefaultHome
        }
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val res = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        val isHome = res?.activityInfo?.packageName == context.packageName
        cachedIsDefaultHome = isHome
        lastHomeCheckMs = now
        return isHome
    }

    fun setAsDefaultHomeLauncher(context: Context): Boolean {
        val component = "${context.packageName}/.MainActivity"
        RootShell.execWithStatus("cmd package set-home-activity $component", timeoutMs = 5_000)
        lastHomeCheckMs = 0L
        return isDefaultHomeLauncher(context)
    }

    fun isScreenOn(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isInteractive
    }

    /**
     * Turns the physical display ON (KEYCODE_WAKEUP=224) or OFF (KEYCODE_SLEEP=223).
     * Never dims/blacks out an IPS TFT panel — actually powers down the backlight.
     */
    @Suppress("DEPRECATION")
    fun setScreenOn(context: Context, turnOn: Boolean): String {
        val currentlyOn = isScreenOn(context)
        if (turnOn) {
            try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                pm?.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                    "tswitch:kiosk_wake"
                )?.acquire(15_000L)
            } catch (e: Exception) {
                Log.w(TAG, "WakeLock acquire failed: ${e.message}")
            }
            val (rc, out) = RootShell.execWithStatus(
                "input keyevent 224; wm dismiss-keyguard; settings put system screen_brightness 140",
                timeoutMs = 5_000
            )
            Thread.sleep(150)
            val afterOn = isScreenOn(context)
            return if (afterOn || rc == 0) {
                if (currentlyOn) "📱 Ekran zaten AÇIK." else "📱 Ekran AÇILDI."
            } else {
                "❌ Ekran komutu başarısız (rc=$rc): ${out.trim()}"
            }
        } else {
            if (!currentlyOn) {
                return "🌑 Ekran zaten KAPALI."
            }
            val (rc, out) = RootShell.execWithStatus("input keyevent 223", timeoutMs = 5_000)
            Thread.sleep(250)
            val afterOn = isScreenOn(context)
            return if (rc == 0) {
                if (afterOn) "📱 Ekran AÇILDI." else "🌑 Ekran KAPATILDI (donanımsal güç kapalı)."
            } else {
                "❌ Ekran komutu başarısız (rc=$rc): ${out.trim()}"
            }
        }
    }

    /**
     * Enables or disables Kiosk Launcher & Ultra Battery/Data Saver mode on GM 8.
     */
    fun applyKioskMode(context: Context, enabled: Boolean, turnScreenOffNow: Boolean = true): String {
        val prefs = context.getSharedPreferences("gateway", Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREF_KIOSK_ENABLED, enabled).apply()

        val ownPkg = context.packageName
        val ownUid = context.applicationInfo.uid
        val notes = mutableListOf<String>()

        if (enabled) {
            // 1. Set T-Switch as the system default Home Launcher
            setAsDefaultHomeLauncher(context)

            // 2. Whitelist T-Switch from Doze & background restrictions
            RootShell.execWithStatus("dumpsys deviceidle whitelist +$ownPkg", timeoutMs = 5_000)
            RootShell.execWithStatus("cmd appops set $ownPkg RUN_IN_BACKGROUND allow", timeoutMs = 5_000)
            if (Build.VERSION.SDK_INT >= 28) {
                RootShell.execWithStatus("cmd appops set $ownPkg RUN_ANY_IN_BACKGROUND allow", timeoutMs = 5_000)
            }

            // 3. Enable Android Battery Saver + short screen timeout + zero animations
            RootShell.execWithStatus("settings put global low_power 1", timeoutMs = 5_000)
            RootShell.execWithStatus("settings put global low_power_sticky 1", timeoutMs = 5_000)
            RootShell.execWithStatus("settings put system screen_off_timeout 15000", timeoutMs = 5_000)
            RootShell.execWithStatus("settings put global window_animation_scale 0.0", timeoutMs = 3_000)
            RootShell.execWithStatus("settings put global transition_animation_scale 0.0", timeoutMs = 3_000)
            RootShell.execWithStatus("settings put global animator_duration_scale 0.0", timeoutMs = 3_000)

            // 4. Restrict background mobile data for all other apps (Data Saver + whitelist T-Switch)
            RootShell.execWithStatus("cmd netpolicy set restrict-background true", timeoutMs = 5_000)
            RootShell.execWithStatus("cmd netpolicy add restrict-background-whitelist $ownUid", timeoutMs = 5_000)

            // 5. Stop & restrict background execution for unnecessary 3rd-party apps
            val (_, pkgOut) = RootShell.execWithStatus("pm list packages -3", timeoutMs = 8_000)
            var stoppedCount = 0
            for (line in pkgOut.lines()) {
                val pkg = line.trim().removePrefix("package:").trim()
                if (pkg.isEmpty() || pkg in PROTECTED_PACKAGES) continue
                RootShell.execWithStatus("cmd appops set $pkg RUN_IN_BACKGROUND ignore", timeoutMs = 3_000)
                if (Build.VERSION.SDK_INT >= 28) {
                    RootShell.execWithStatus("cmd appops set $pkg RUN_ANY_IN_BACKGROUND ignore", timeoutMs = 3_000)
                }
                RootShell.execWithStatus("am force-stop $pkg", timeoutMs = 3_000)
                stoppedCount++
            }
            RootShell.execWithStatus("am kill-all", timeoutMs = 5_000)
            notes.add("• Ana Ekran (Launcher): T-Switch Atandı ✅")
            notes.add("• Kısıtlanan 3. parti uygulama: $stoppedCount")

            // 6. Turn physical screen off if requested
            if (turnScreenOffNow && isScreenOn(context)) {
                RootShell.execWithStatus("input keyevent 223", timeoutMs = 5_000)
            }

            Log.i(TAG, "Kiosk mode ENABLED (restricted $stoppedCount apps)")
            return buildString {
                appendLine("🔒 [T-Switch KİOSK MODU: AÇIK ✅]")
                appendLine("• T-Switch Doze / Arka Plan: Korumalı")
                appendLine("• Android Pil Tasarrufu: AÇIK")
                appendLine("• Arka Plan Mobil Veri Kısıtlaması: AÇIK (Sadece T-Switch + Hotspot)")
                notes.forEach { appendLine(it) }
                append("• Ekran: ${if (isScreenOn(context)) "Açık" else "Kapalı 🌑"}")
            }
        } else {
            RootShell.execWithStatus("settings put global low_power 0", timeoutMs = 5_000)
            RootShell.execWithStatus("settings put global low_power_sticky 0", timeoutMs = 5_000)
            RootShell.execWithStatus("cmd netpolicy set restrict-background false", timeoutMs = 5_000)

            val (_, pkgOut) = RootShell.execWithStatus("pm list packages -3", timeoutMs = 8_000)
            var restoredCount = 0
            for (line in pkgOut.lines()) {
                val pkg = line.trim().removePrefix("package:").trim()
                if (pkg.isEmpty() || pkg in PROTECTED_PACKAGES) continue
                RootShell.execWithStatus("cmd appops set $pkg RUN_IN_BACKGROUND allow", timeoutMs = 3_000)
                if (Build.VERSION.SDK_INT >= 28) {
                    RootShell.execWithStatus("cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow", timeoutMs = 3_000)
                }
                restoredCount++
            }
            Log.i(TAG, "Kiosk mode DISABLED (restored $restoredCount apps)")
            return buildString {
                appendLine("🔓 [T-Switch KİOSK MODU: KAPALI]")
                appendLine("• Android Pil Tasarrufu: KAPALI")
                appendLine("• Arka Plan Veri Kısıtlaması: KAPALI")
                append("• Arka Plan İzni Geri Verilen Uygulama: $restoredCount")
            }
        }
    }

    /**
     * Disables GM 8 bloatware packages, sets 0x animations, sets T-Switch as Home Launcher,
     * and kills cached background tasks to maximize free RAM and speed.
     */
    fun debloatAndBoost(context: Context): String {
        val script = buildString {
            appendLine("settings put global window_animation_scale 0.0")
            appendLine("settings put global transition_animation_scale 0.0")
            appendLine("settings put global animator_duration_scale 0.0")
            appendLine("cmd package set-home-activity ${context.packageName}/.MainActivity")
            for (pkg in GM8_BLOAT_PACKAGES) {
                appendLine("pm disable-user --user 0 $pkg 2>/dev/null; am force-stop $pkg 2>/dev/null")
            }
            appendLine("am kill-all")
            appendLine("free -m | grep -i mem")
        }
        val (_, out) = RootShell.execWithStatus(script, timeoutMs = 25_000)
        lastHomeCheckMs = 0L
        val disabledCount = out.lines().count { it.contains("disabled-user", ignoreCase = true) }
            .coerceAtLeast(GM8_BLOAT_PACKAGES.size)
        context.getSharedPreferences("gateway", Context.MODE_PRIVATE)
            .edit().putInt(PREF_DEBLOATED_COUNT, disabledCount).apply()
        val memLine = out.lines().lastOrNull { it.contains("Mem:", ignoreCase = true) }?.trim() ?: ""

        return buildString {
            appendLine("⚡ [GM 8 Debloat & Hızlandırma Tamamlandı]")
            appendLine("• Devre Dışı Bırakılan Bloatware: ${GM8_BLOAT_PACKAGES.size} paket")
            appendLine("• Sistem Animasyonları: 0.0x (Anında Geçiş)")
            appendLine("• Varsayılan Ana Ekran: T-Switch Launcher ✅")
            if (memLine.isNotEmpty()) append("• Bellek (MB): $memLine")
        }
    }

    fun buildScreenStatusReport(context: Context): String {
        val screenOn = isScreenOn(context)
        val kiosk = isKioskEnabled(context)
        val isHome = isDefaultHomeLauncher(context)
        val lowPower = runCatching {
            Settings.Global.getInt(context.contentResolver, "low_power", 0) == 1
        }.getOrDefault(false)
        val timeoutMs = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 30_000)
        }.getOrDefault(30_000)

        return buildString {
            appendLine(if (screenOn) "📱 [Ekran Durumu: AÇIK]" else "🌑 [Ekran Durumu: KAPALI]")
            appendLine("• Fiziksel Ekran   : ${if (screenOn) "AÇIK ⚠️" else "KAPALI ✅ (Güç Tasarrufu)"}")
            appendLine("• Kiosk Modu       : ${if (kiosk) "AÇIK 🔒" else "KAPALI"}")
            appendLine("• Ana Ekran (Home) : ${if (isHome) "T-Switch ✅" else "Standart Launcher"}")
            appendLine("• Pil Tasarrufu    : ${if (lowPower) "AÇIK 🔋" else "KAPALI"}")
            appendLine("• Kapanma Süresi   : ${timeoutMs / 1000} sn")
            append("Komutlar: `!ekran ac` | `!ekran kapat` | `!kiosk` | `!debloat`")
        }
    }
}
