package com.example.recording

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract

/**
 * Resolves a phone number to the saved contact display name.
 *
 * Results are cached in memory for the lifetime of the process so the
 * ContentResolver is only queried once per unique number per session.
 */
object ContactLookup {

    private val cache = HashMap<String, String>()

    /**
     * Returns the contact display name for [phoneNumber], or the raw number
     * itself if no contact is found.  Returns an empty string for blank input.
     */
    fun getDisplayName(context: Context, phoneNumber: String): String {
        if (phoneNumber.isBlank()) return ""
        return cache.getOrPut(phoneNumber) {
            resolveFromContacts(context, phoneNumber) ?: phoneNumber
        }
    }

    /** Returns initials (up to 2 chars) suitable for a circular avatar. */
    fun initials(name: String): String {
        if (name.isBlank()) return "?"
        val parts = name.trim().split(Regex("\\s+"))
        return when {
            parts.size >= 2 ->
                "${parts[0].first()}${parts[1].first()}".uppercase()
            parts[0].length >= 2 ->
                parts[0].take(2).uppercase()
            else -> parts[0].first().uppercase()
        }
    }

    /** Clears cached results (call when contacts permission is newly granted). */
    fun clearCache() = cache.clear()

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun resolveFromContacts(context: Context, number: String): String? {
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number)
            )
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: SecurityException) {
            // READ_CONTACTS not granted — just use the number
            null
        } catch (_: Exception) {
            null
        }
    }
}
