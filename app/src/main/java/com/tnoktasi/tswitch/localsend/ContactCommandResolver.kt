package com.tnoktasi.tswitch.localsend

import kotlin.math.min

data class ContactEntry(
    val name: String,
    val number: String
)

sealed class ContactResolution {
    data class ExecuteCall(val contact: ContactEntry) : ContactResolution()
    data class ExecuteSms(val contact: ContactEntry, val text: String) : ContactResolution()
    data class AskConfirmation(val candidates: List<ContactEntry>, val promptMessage: String) : ContactResolution()
    data class Cancelled(val message: String) : ContactResolution()
    data class NotFound(val query: String, val message: String) : ContactResolution()
    object NotHandled : ContactResolution()
}

/**
 * Turkish-aware fuzzy contact matcher and interactive confirmation session
 * for terminal `!ara <name>` and `!sms <name> <text>` commands.
 */
class ContactCommandResolver(
    private val pendingTtlMs: Long = DEFAULT_PENDING_TTL_MS,
    private val maxCandidates: Int = 5
) {
    private enum class PendingKind { CALL, SMS }

    private data class PendingSession(
        val kind: PendingKind,
        val candidates: List<ContactEntry>,
        val smsText: String?,
        val createdAtMs: Long
    )

    private data class ScoredContact(
        val contact: ContactEntry,
        val tier: Int, // 0 = exact full name, 1 = exact token/substring, 2 = fuzzy typo
        val distance: Int
    )

    @Volatile
    private var pending: PendingSession? = null

    fun hasPending(nowMs: Long = System.currentTimeMillis()): Boolean {
        val p = pending ?: return false
        if (nowMs - p.createdAtMs > pendingTtlMs) {
            pending = null
            return false
        }
        return true
    }

    fun clearPending() {
        pending = null
    }

    fun resolveCall(
        query: String,
        contacts: List<ContactEntry>,
        nowMs: Long = System.currentTimeMillis()
    ): ContactResolution = resolve(PendingKind.CALL, query, null, contacts, nowMs)

    fun resolveSms(
        query: String,
        smsText: String,
        contacts: List<ContactEntry>,
        nowMs: Long = System.currentTimeMillis()
    ): ContactResolution = resolve(PendingKind.SMS, query, smsText, contacts, nowMs)

    fun handlePendingReply(
        rawReply: String,
        nowMs: Long = System.currentTimeMillis()
    ): ContactResolution {
        val session = pending ?: return ContactResolution.NotHandled
        if (nowMs - session.createdAtMs > pendingTtlMs) {
            pending = null
            return ContactResolution.NotHandled
        }

        val normalized = normalizeTurkish(rawReply)
        if (normalized in CANCEL_WORDS) {
            pending = null
            return ContactResolution.Cancelled("❌ İşlem iptal edildi.")
        }

        val selected: ContactEntry? = when {
            normalized in CONFIRM_WORDS && session.candidates.size == 1 -> session.candidates[0]
            else -> {
                val idx = normalized.toIntOrNull()
                if (idx != null && idx in 1..session.candidates.size) {
                    session.candidates[idx - 1]
                } else {
                    null
                }
            }
        }

        if (selected == null) return ContactResolution.NotHandled
        pending = null
        return when (session.kind) {
            PendingKind.CALL -> ContactResolution.ExecuteCall(selected)
            PendingKind.SMS -> ContactResolution.ExecuteSms(selected, session.smsText.orEmpty())
        }
    }

    private fun resolve(
        kind: PendingKind,
        rawQuery: String,
        smsText: String?,
        contacts: List<ContactEntry>,
        nowMs: Long
    ): ContactResolution {
        pending = null
        val normQuery = normalizeTurkish(rawQuery)
        if (normQuery.isEmpty()) {
            return ContactResolution.NotFound(rawQuery, "❌ Kişi adı boş olamaz.")
        }

        val uniqueContacts = contacts
            .filter { it.name.isNotBlank() && it.number.isNotBlank() }
            .distinctBy { "${normalizeTurkish(it.name)}|${it.number.filter { c -> c.isDigit() || c == '+' }}" }

        val scored = uniqueContacts.mapNotNull { scoreContact(normQuery, it) }
            .sortedWith(compareBy<ScoredContact> { it.tier }.thenBy { it.distance })

        if (scored.isEmpty()) {
            return ContactResolution.NotFound(
                rawQuery,
                "❌ Rehberde '$rawQuery' için eşleşme bulunamadı."
            )
        }

        // Exact full-name match wins unambiguously if unique
        val exactFull = scored.filter { it.tier == 0 }
        if (exactFull.size == 1) {
            val hit = exactFull[0].contact
            return if (kind == PendingKind.CALL) {
                ContactResolution.ExecuteCall(hit)
            } else {
                ContactResolution.ExecuteSms(hit, smsText.orEmpty())
            }
        }

        // Exact token/substring match (no typo) executes immediately when unambiguous (single match)
        val exactNonFuzzy = scored.filter { it.tier <= 1 }
        if (exactNonFuzzy.size == 1) {
            val hit = exactNonFuzzy[0].contact
            return if (kind == PendingKind.CALL) {
                ContactResolution.ExecuteCall(hit)
            } else {
                ContactResolution.ExecuteSms(hit, smsText.orEmpty())
            }
        }

        // Otherwise ask confirmation (either multiple exact/substring hits, or fuzzy typo hit(s))
        val candidates = (if (exactNonFuzzy.isNotEmpty()) exactNonFuzzy else scored)
            .take(maxCandidates)
            .map { it.contact }

        pending = PendingSession(kind, candidates, smsText, nowMs)
        val actionLabel = if (kind == PendingKind.CALL) "aransın" else "SMS gönderilsin"
        val prompt = buildString {
            if (candidates.size == 1) {
                appendLine("❓ Bunu mu demek istediniz? ($actionLabel)")
                appendLine("1) ${candidates[0].name} (${candidates[0].number})")
                append("Onaylamak için 'evet' veya '1', vazgeçmek için 'iptal' yazın.")
            } else {
                appendLine("📇 '$rawQuery' için birden fazla kişi bulundu. Hangisi $actionLabel?")
                candidates.forEachIndexed { idx, c ->
                    appendLine("${idx + 1}) ${c.name} (${c.number})")
                }
                append("Seçmek için 1-${candidates.size} arası rakam, vazgeçmek için 'iptal' yazın.")
            }
        }
        return ContactResolution.AskConfirmation(candidates, prompt)
    }

    private fun scoreContact(normQuery: String, contact: ContactEntry): ScoredContact? {
        val normName = normalizeTurkish(contact.name)
        if (normName.isEmpty()) return null
        if (normName == normQuery) return ScoredContact(contact, tier = 0, distance = 0)

        val tokens = normName.split(' ').filter { it.isNotEmpty() }
        if (tokens.any { it == normQuery }) {
            return ScoredContact(contact, tier = 1, distance = 0)
        }
        if (normName.contains(normQuery)) {
            return ScoredContact(contact, tier = 1, distance = 1)
        }

        val maxDist = if (normQuery.length <= 4) 1 else 2
        val fullDist = levenshtein(normQuery, normName)
        val bestTokenDist = tokens.minOfOrNull { levenshtein(normQuery, it) } ?: Int.MAX_VALUE
        val bestDist = min(fullDist, bestTokenDist)
        if (bestDist <= maxDist) {
            return ScoredContact(contact, tier = 2, distance = bestDist)
        }
        return null
    }

    companion object {
        const val DEFAULT_PENDING_TTL_MS = 60_000L

        private val CONFIRM_WORDS = setOf("evet", "e", "ok", "tamam", "onay", "yes", "y")
        private val CANCEL_WORDS = setOf("iptal", "hayir", "h", "no", "n", "0", "vazgec")

        fun normalizeTurkish(raw: String): String {
            val sb = StringBuilder(raw.length)
            var lastWasSpace = true
            for (ch in raw.trim()) {
                val mapped = when (ch) {
                    'İ', 'I', 'ı', 'i' -> 'i'
                    'Ş', 'ş' -> 's'
                    'Ğ', 'ğ' -> 'g'
                    'Ç', 'ç' -> 'c'
                    'Ö', 'ö' -> 'o'
                    'Ü', 'ü' -> 'u'
                    else -> ch.lowercaseChar()
                }
                if (mapped.isWhitespace()) {
                    if (!lastWasSpace) {
                        sb.append(' ')
                        lastWasSpace = true
                    }
                } else {
                    sb.append(mapped)
                    lastWasSpace = false
                }
            }
            return sb.toString().trim()
        }

        private fun levenshtein(a: String, b: String): Int {
            if (a == b) return 0
            if (a.isEmpty()) return b.length
            if (b.isEmpty()) return a.length
            var prev = IntArray(b.length + 1) { it }
            var curr = IntArray(b.length + 1)
            for (i in 1..a.length) {
                curr[0] = i
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    curr[j] = min(min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
                }
                val tmp = prev
                prev = curr
                curr = tmp
            }
            return prev[b.length]
        }
    }
}
