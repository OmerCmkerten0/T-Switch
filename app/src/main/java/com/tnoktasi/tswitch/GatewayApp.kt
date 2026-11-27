package com.tnoktasi.tswitch

import android.app.Application
import android.os.StrictMode
import androidx.appcompat.app.AppCompatDelegate

class GatewayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Allow short UDP socket sends on main thread (for GSM call callbacks)
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().permitAll().build())
        applyThemeFromPrefs(this)
    }

    companion object {
        fun applyThemeFromPrefs(context: android.content.Context) {
            val prefs = context.getSharedPreferences("gateway", MODE_PRIVATE)
            applyThemeMode(prefs.getString("theme_mode", "system") ?: "system")
        }

        fun applyThemeMode(mode: String) {
            when (mode) {
                "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            }
        }
    }
}
