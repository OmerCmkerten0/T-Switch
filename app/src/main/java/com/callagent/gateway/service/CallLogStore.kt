package com.callagent.gateway.service

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class CallLogEntry(
    val direction: String,  // "IN" or "OUT"
    val number: String,
    val timestamp: Long,    // millis since epoch (call start)
    val durationSec: Long,
    /** "CALL" or "SMS".  Defaulted, so entries written before messages
     *  existed still read back as calls. */
    val type: String = CallLogStore.TYPE_CALL,
    /** Message text, for SMS entries. */
    val text: String = "",

    // ── SMS detail, all defaulted so entries written before these existed
    //    still read back cleanly ──

    /** Server-side message id, and the key delivery reports arrive against. */
    val smsId: String = "",
    /** "GSM7", "UCS2", "8BIT" — what the message was encoded as on the air. */
    val encoding: String = "",
    /** Concatenation parts the message was split into. */
    val parts: Int = 0,
    /** Service centre that handled it, read back from the delivery report. */
    val smsc: String = "",
    /**
     * Delivery state, as the UI should say it: "sent", "delivered",
     * "failed", "pending".  Held here rather than read from [SmsOutbox] on
     * demand, because the outbox is pruned the moment a message is finally
     * reported — by the time anyone taps a delivered message its outbox row
     * is already gone.
     */
    val status: String = "",
    /** Failure detail, where there is one. */
    val error: String = ""
)

object CallLogStore {
    const val TYPE_CALL = "CALL"
    const val TYPE_SMS = "SMS"

    private const val PREFS = "call_log"
    private const val KEY = "entries"

    /**
     * Most entries kept.
     *
     * Every call end re-parsed the whole array, appended one object and wrote
     * the lot back as a single SharedPreferences string, and every UI refresh
     * parsed it again.  Unbounded, that is a string that grows for the life of
     * the gateway — a few thousand calls in, each call end is rewriting
     * megabytes.  The list is a recent-calls view; nothing reads past the top
     * of it.
     */
    private const val MAX_ENTRIES = 500

    // In-memory cache — avoids re-parsing JSON from SharedPreferences on every access
    @Volatile
    private var cachedEntries: List<CallLogEntry>? = null

    // Synchronized with [updateSms]: both are read-modify-write over the same
    // JSON blob, and a delivery report landing while a new entry is being
    // appended would otherwise drop one of the two writes.
    @Synchronized
    fun addEntry(context: Context, entry: CallLogEntry) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        val obj = JSONObject().apply {
            put("dir", entry.direction)
            put("num", entry.number)
            put("ts", entry.timestamp)
            put("dur", entry.durationSec)
            if (entry.type != TYPE_CALL) put("type", entry.type)
            if (entry.text.isNotEmpty()) put("text", entry.text)
            if (entry.smsId.isNotEmpty()) put("smsId", entry.smsId)
            if (entry.encoding.isNotEmpty()) put("enc", entry.encoding)
            if (entry.parts > 0) put("parts", entry.parts)
            if (entry.smsc.isNotEmpty()) put("smsc", entry.smsc)
            if (entry.status.isNotEmpty()) put("status", entry.status)
            if (entry.error.isNotEmpty()) put("err", entry.error)
        }
        arr.put(obj)
        // Oldest first in storage, so trim from the front.
        while (arr.length() > MAX_ENTRIES) arr.remove(0)
        prefs.edit().putString(KEY, arr.toString()).apply()
        cachedEntries = null // invalidate cache
    }

    @Synchronized
    fun getEntries(context: Context): List<CallLogEntry> {
        cachedEntries?.let { return it }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        val entries = (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            CallLogEntry(
                direction = obj.getString("dir"),
                number = obj.getString("num"),
                timestamp = obj.getLong("ts"),
                durationSec = obj.getLong("dur"),
                type = obj.optString("type", TYPE_CALL),
                text = obj.optString("text", ""),
                smsId = obj.optString("smsId", ""),
                encoding = obj.optString("enc", ""),
                parts = obj.optInt("parts", 0),
                smsc = obj.optString("smsc", ""),
                status = obj.optString("status", ""),
                error = obj.optString("err", "")
            )
        }.reversed() // newest first
        cachedEntries = entries
        return entries
    }

    /**
     * Amend the SMS entry carrying [smsId] in place.
     *
     * Delivery reports land long after the row was written — often after a
     * process restart — so the row has to be findable and mutable by id.
     * Returns false when no such entry exists, which is normal once the log
     * has aged past [MAX_ENTRIES].
     */
    @Synchronized
    fun updateSms(
        context: Context,
        smsId: String,
        transform: (CallLogEntry) -> CallLogEntry
    ): Boolean {
        if (smsId.isEmpty()) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        // Newest last in storage, and a repeated id is always the latest one.
        for (i in arr.length() - 1 downTo 0) {
            val obj = arr.getJSONObject(i)
            if (obj.optString("smsId") != smsId) continue
            val updated = transform(
                CallLogEntry(
                    direction = obj.getString("dir"),
                    number = obj.getString("num"),
                    timestamp = obj.getLong("ts"),
                    durationSec = obj.getLong("dur"),
                    type = obj.optString("type", TYPE_CALL),
                    text = obj.optString("text", ""),
                    smsId = obj.optString("smsId", ""),
                    encoding = obj.optString("enc", ""),
                    parts = obj.optInt("parts", 0),
                    smsc = obj.optString("smsc", ""),
                    status = obj.optString("status", ""),
                    error = obj.optString("err", "")
                )
            )
            obj.put("enc", updated.encoding)
            obj.put("parts", updated.parts)
            obj.put("smsc", updated.smsc)
            obj.put("status", updated.status)
            obj.put("err", updated.error)
            prefs.edit().putString(KEY, arr.toString()).apply()
            cachedEntries = null
            return true
        }
        return false
    }

    data class Totals(
        val inCalls: Int, val inDurationSec: Long,
        val outCalls: Int, val outDurationSec: Long
    )

    /** Call counters only — a message is not a call and must not inflate
     *  the totals the status broadcast carries. */
    fun getTotals(context: Context): Totals {
        val entries = getEntries(context).filter { it.type == TYPE_CALL }
        return Totals(
            inCalls = entries.count { it.direction == "IN" },
            inDurationSec = entries.filter { it.direction == "IN" }.sumOf { it.durationSec },
            outCalls = entries.count { it.direction == "OUT" },
            outDurationSec = entries.filter { it.direction == "OUT" }.sumOf { it.durationSec }
        )
    }

    @Synchronized
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, "[]").apply()
        cachedEntries = null // invalidate cache
    }

    private fun normNumberKey(num: String): String {
        val digits = num.filter { it.isDigit() }
        return if (digits.length >= 7) digits.takeLast(10) else num.trim().uppercase()
    }

    /**
     * Synchronizes [CallLogStore] with Android's system CallLog and Telephony SMS
     * providers so calls/SMS placed or received outside the SIP bridge (or prior to
     * opening the app) are always reflected in the Home traffic feed.
     */
    @Synchronized
    fun syncWithSystemProviders(context: Context): Boolean {
        val existing = getEntries(context).toMutableList()
        val additions = mutableListOf<CallLogEntry>()

        fun isDuplicate(cand: CallLogEntry): Boolean {
            val candNum = normNumberKey(cand.number)
            return existing.any { e ->
                e.type == cand.type &&
                    e.direction == cand.direction &&
                    normNumberKey(e.number) == candNum &&
                    kotlin.math.abs(e.timestamp - cand.timestamp) < 120_000L
            } || additions.any { e ->
                e.type == cand.type &&
                    e.direction == cand.direction &&
                    normNumberKey(e.number) == candNum &&
                    kotlin.math.abs(e.timestamp - cand.timestamp) < 120_000L
            }
        }

        // 1. Sync system CallLog.Calls
        runCatching {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.READ_CALL_LOG
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                com.callagent.gateway.RootShell.execWithStatus(
                    "pm grant ${context.packageName} android.permission.READ_CALL_LOG", 3_000
                )
            }
            context.contentResolver.query(
                android.provider.CallLog.Calls.CONTENT_URI,
                arrayOf(
                    android.provider.CallLog.Calls.NUMBER,
                    android.provider.CallLog.Calls.TYPE,
                    android.provider.CallLog.Calls.DATE,
                    android.provider.CallLog.Calls.DURATION
                ),
                null,
                null,
                "${android.provider.CallLog.Calls.DATE} DESC"
            )?.use { cur ->
                val numIdx = cur.getColumnIndex(android.provider.CallLog.Calls.NUMBER)
                val typeIdx = cur.getColumnIndex(android.provider.CallLog.Calls.TYPE)
                val dateIdx = cur.getColumnIndex(android.provider.CallLog.Calls.DATE)
                val durIdx = cur.getColumnIndex(android.provider.CallLog.Calls.DURATION)
                var count = 0
                while (cur.moveToNext() && count < 40) {
                    count++
                    val number = if (numIdx >= 0) cur.getString(numIdx).orEmpty().trim() else ""
                    if (number.isEmpty()) continue
                    val callType = if (typeIdx >= 0) cur.getInt(typeIdx) else android.provider.CallLog.Calls.INCOMING_TYPE
                    val dateMs = if (dateIdx >= 0) cur.getLong(dateIdx) else 0L
                    val durSec = if (durIdx >= 0) cur.getLong(durIdx).coerceAtLeast(0L) else 0L
                    if (dateMs <= 0L) continue
                    val dir = if (callType == android.provider.CallLog.Calls.OUTGOING_TYPE) "OUT" else "IN"
                    val cand = CallLogEntry(
                        direction = dir,
                        number = number,
                        timestamp = dateMs,
                        durationSec = durSec,
                        type = TYPE_CALL
                    )
                    if (!isDuplicate(cand)) additions.add(cand)
                }
            }
        }

        // 2. Sync system Telephony.Sms
        runCatching {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.READ_SMS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                com.callagent.gateway.RootShell.execWithStatus(
                    "pm grant ${context.packageName} android.permission.READ_SMS", 3_000
                )
            }
            context.contentResolver.query(
                android.provider.Telephony.Sms.CONTENT_URI,
                arrayOf(
                    android.provider.Telephony.Sms.ADDRESS,
                    android.provider.Telephony.Sms.BODY,
                    android.provider.Telephony.Sms.DATE,
                    android.provider.Telephony.Sms.TYPE
                ),
                null,
                null,
                "${android.provider.Telephony.Sms.DATE} DESC"
            )?.use { cur ->
                val addrIdx = cur.getColumnIndex(android.provider.Telephony.Sms.ADDRESS)
                val bodyIdx = cur.getColumnIndex(android.provider.Telephony.Sms.BODY)
                val dateIdx = cur.getColumnIndex(android.provider.Telephony.Sms.DATE)
                val typeIdx = cur.getColumnIndex(android.provider.Telephony.Sms.TYPE)
                var count = 0
                while (cur.moveToNext() && count < 40) {
                    count++
                    val addr = if (addrIdx >= 0) cur.getString(addrIdx).orEmpty().trim() else ""
                    val body = if (bodyIdx >= 0) cur.getString(bodyIdx).orEmpty() else ""
                    val dateMs = if (dateIdx >= 0) cur.getLong(dateIdx) else 0L
                    val smsType = if (typeIdx >= 0) cur.getInt(typeIdx) else android.provider.Telephony.Sms.MESSAGE_TYPE_INBOX
                    if (addr.isEmpty() || dateMs <= 0L) continue
                    val dir = if (smsType == android.provider.Telephony.Sms.MESSAGE_TYPE_INBOX) "IN" else "OUT"
                    val cand = CallLogEntry(
                        direction = dir,
                        number = addr,
                        timestamp = dateMs,
                        durationSec = 0L,
                        type = TYPE_SMS,
                        text = body,
                        status = if (dir == "OUT") "sent" else ""
                    )
                    if (!isDuplicate(cand)) additions.add(cand)
                }
            }
        }

        if (additions.isEmpty()) return false

        val merged = (existing + additions).sortedBy { it.timestamp }.takeLast(MAX_ENTRIES)
        val arr = JSONArray()
        for (entry in merged) {
            val obj = JSONObject().apply {
                put("dir", entry.direction)
                put("num", entry.number)
                put("ts", entry.timestamp)
                put("dur", entry.durationSec)
                if (entry.type != TYPE_CALL) put("type", entry.type)
                if (entry.text.isNotEmpty()) put("text", entry.text)
                if (entry.smsId.isNotEmpty()) put("smsId", entry.smsId)
                if (entry.encoding.isNotEmpty()) put("enc", entry.encoding)
                if (entry.parts > 0) put("parts", entry.parts)
                if (entry.smsc.isNotEmpty()) put("smsc", entry.smsc)
                if (entry.status.isNotEmpty()) put("status", entry.status)
                if (entry.error.isNotEmpty()) put("err", entry.error)
            }
            arr.put(obj)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
        cachedEntries = null
        return true
    }
}
