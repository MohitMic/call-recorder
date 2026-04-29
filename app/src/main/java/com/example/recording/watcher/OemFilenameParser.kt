package com.example.recording.watcher

/**
 * Generic, OEM-agnostic parser for call-recording filenames.
 *
 * Filenames vary wildly across phones:
 *   "Yashdeep Ji(00919588259935)_20260427112733.m4a"
 *   "Arjun Ji (Sanju & Paravati) 2026-03-30 03-51-42.amr"
 *   "+918107565674 2026-03-27 03-52-51.mp3"
 *   "Call_+919876543210_20240418_112345.amr"
 *   "9571091011_20240425_180704.m4a"
 *
 * Strategy (matches scripts/backfill.js):
 *   1. Extract every plausible timestamp candidate, validate by year/month/day
 *      bounds + Date round-trip; keep the most-detailed valid one.
 *   2. Strip date-shaped chunks from the filename, then take the longest
 *      digit run as the phone number and the residual text as the contact
 *      name. Whichever is more useful is the "caller" we display.
 */
object OemFilenameParser {

    data class Parsed(
        /** Best display string for the caller — contact name if available, else phone, else "Unknown" */
        val caller: String,
        /** Raw phone number if found, else null */
        val phoneNumber: String?,
        /** Epoch millis. Falls back to the file's lastModified or system time. */
        val timestampMs: Long,
    )

    fun parse(fileName: String, fileLastModifiedMs: Long): Parsed {
        val ts = extractTimestamp(fileName) ?: fileLastModifiedMs.takeIf { it > 0 }
            ?: System.currentTimeMillis()

        val phone = extractPhone(fileName)
        val caller = extractCaller(fileName, phone) ?: phone ?: "Unknown"

        return Parsed(caller = caller, phoneNumber = phone, timestampMs = ts)
    }

    // ── Timestamp ────────────────────────────────────────────────────────────

    /** Returns epoch millis for the best date+time candidate found. */
    private fun extractTimestamp(name: String): Long? {
        data class C(val y: Int, val mo: Int, val d: Int, val h: Int, val mi: Int, val s: Int, val score: Int)
        val candidates = mutableListOf<C>()

        // Pattern 1: separator-style "YYYY?MM?DD?HH?MM?SS" — any non-digit between
        val sepRe = Regex("""(\d{4})\D(\d{1,2})\D(\d{1,2})\D(\d{1,2})\D(\d{1,2})\D(\d{1,2})""")
        sepRe.findAll(name).forEach { m ->
            val (y, mo, d, h, mi, s) = m.destructured
            candidates += C(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.toInt(), 6)
        }

        // Pattern 2: contiguous YYYYMMDDHHMMSS — only as a standalone digit run
        val c14 = Regex("""(?<!\d)(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})(?!\d)""")
        c14.findAll(name).forEach { m ->
            val (y, mo, d, h, mi, s) = m.destructured
            candidates += C(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.toInt(), 6)
        }

        // Pattern 3: contiguous YYYYMMDD only
        val c8 = Regex("""(?<!\d)(\d{4})(\d{2})(\d{2})(?!\d)""")
        c8.findAll(name).forEach { m ->
            val (y, mo, d) = m.destructured
            candidates += C(y.toInt(), mo.toInt(), d.toInt(), 0, 0, 0, 3)
        }

        var best: C? = null
        for (c in candidates) {
            if (c.y < 2000 || c.y > 2099) continue
            if (c.mo !in 1..12) continue
            if (c.d  !in 1..31) continue
            if (c.h  !in 0..23) continue
            if (c.mi !in 0..59) continue
            if (c.s  !in 0..59) continue
            // Round-trip through Calendar to catch impossible dates (Feb 30, etc.)
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getDefault()).apply {
                clear()
                set(c.y, c.mo - 1, c.d, c.h, c.mi, c.s)
            }
            if (cal.get(java.util.Calendar.MONTH) != c.mo - 1 ||
                cal.get(java.util.Calendar.DAY_OF_MONTH) != c.d) continue
            if (best == null || c.score > best.score) best = c
        }
        return best?.let {
            java.util.Calendar.getInstance(java.util.TimeZone.getDefault()).apply {
                clear()
                set(it.y, it.mo - 1, it.d, it.h, it.mi, it.s)
            }.timeInMillis
        }
    }

    // ── Phone & caller name ───────────────────────────────────────────────────

    private fun extractPhone(name: String): String? {
        // Strip extension first so the regex can't grab digits there
        val noExt = name.substringBeforeLast('.', name)
        // Strip date-shaped digit runs so they aren't mistaken for phone numbers
        val withoutDates = noExt
            .replace(Regex("""\d{4}\D\d{1,2}\D\d{1,2}\D\d{1,2}\D\d{1,2}\D\d{1,2}"""), " ")
            .replace(Regex("""\d{4}\D\d{1,2}\D\d{1,2}"""), " ")
            .replace(Regex("""(?<!\d)\d{14}(?!\d)"""), " ")
            .replace(Regex("""(?<!\d)\d{12}(?!\d)"""), " ")
            .replace(Regex("""(?<!\d)\d{8}(?!\d)"""), " ")
        // Phone candidate: 7–15 digit run (with optional + or 00 prefix)
        val m = Regex("""(?<!\d)(\+?\d{7,15})(?!\d)""").find(withoutDates) ?: return null
        return m.value
    }

    private fun extractCaller(name: String, phone: String?): String? {
        var s = name.substringBeforeLast('.', name)
        // Remove date-shaped chunks
        s = s.replace(Regex("""\d{4}\D\d{1,2}\D\d{1,2}\D\d{1,2}\D\d{1,2}\D\d{1,2}"""), " ")
        s = s.replace(Regex("""(?<!\d)\d{14}(?!\d)"""), " ")
        s = s.replace(Regex("""(?<!\d)\d{12}(?!\d)"""), " ")
        s = s.replace(Regex("""(?<!\d)\d{8}(?!\d)"""), " ")
        // Remove the phone number itself if we found one
        if (phone != null) s = s.replace(phone, " ")
        // Drop the parens, underscores, separator junk
        s = s.replace(Regex("""[()_]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', ',', '_')
        // Reject if empty or reduces to digits/punctuation only
        if (s.isBlank()) return null
        if (Regex("""^[\s\-_,+\d]*$""").matches(s)) return null
        return s
    }
}
