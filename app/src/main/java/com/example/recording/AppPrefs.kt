package com.example.recording

import android.content.Context

object AppPrefs {
    private const val PREFS_NAME = "call_recorder_prefs"

    // Recording behaviour
    private const val KEY_DELETE_AFTER         = "delete_after_upload"
    private const val KEY_FORCE_SPEAKER        = "force_speakerphone_on_record"

    // OEM folder selection
    private const val KEY_DEVICE_BRAND         = "oem_device_brand"
    private const val KEY_CUSTOM_FOLDER        = "oem_custom_folder"

    // UI
    private const val KEY_NIGHT_MODE           = "night_mode"

    // ── Recording behaviour ───────────────────────────────────────────────────

    fun isDeleteAfterUpload(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DELETE_AFTER, false)

    fun setDeleteAfterUpload(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_DELETE_AFTER, value).apply()

    fun isForceSpeakerphone(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FORCE_SPEAKER, true)

    fun setForceSpeakerphone(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_FORCE_SPEAKER, value).apply()

    // ── Night mode ────────────────────────────────────────────────────────────

    /** Returns true when the user has enabled dark/night mode (default = true). */
    fun isNightMode(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NIGHT_MODE, true)

    fun setNightMode(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_NIGHT_MODE, value).apply()

    // ── OEM folder selection ──────────────────────────────────────────────────

    /** Which brand preset is selected. Default = AUTO so we watch all known folders. */
    fun getDeviceBrand(context: Context): DeviceBrand =
        runCatching {
            DeviceBrand.valueOf(
                prefs(context).getString(KEY_DEVICE_BRAND, DeviceBrand.AUTO.name)!!
            )
        }.getOrDefault(DeviceBrand.AUTO)

    fun setDeviceBrand(context: Context, brand: DeviceBrand) =
        prefs(context).edit().putString(KEY_DEVICE_BRAND, brand.name).apply()

    /** Custom folder path, used when DeviceBrand == CUSTOM. */
    fun getCustomFolder(context: Context): String =
        prefs(context).getString(KEY_CUSTOM_FOLDER, "") ?: ""

    fun setCustomFolder(context: Context, path: String) =
        prefs(context).edit().putString(KEY_CUSTOM_FOLDER, path.trim()).apply()

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Brand enum ────────────────────────────────────────────────────────────

    enum class DeviceBrand(val label: String, val folders: List<String>) {
        AUTO(
            "Auto-detect (recommended)",
            listOf(
                "/storage/emulated/0/MIUI/sound_recorder/call_rec",
                "/storage/emulated/0/Recordings/Call Recording",
                "/storage/emulated/0/Music/Call Recording",
                "/storage/emulated/0/Record/Call",
                "/storage/emulated/0/Sounds/CallRecord",
                "/storage/emulated/0/CallRecording",
                "/storage/emulated/0/Recordings/CallRecording"
            )
        ),
        XIAOMI_REDMI_POCO(
            "Xiaomi / Redmi / POCO",
            listOf(
                "/storage/emulated/0/MIUI/sound_recorder/call_rec",
                "/storage/emulated/0/Recordings/Call Recording",
                "/storage/emulated/0/Music/Call Recording"
            )
        ),
        VIVO(
            "Vivo",
            listOf(
                "/storage/emulated/0/Record/Call",
                "/storage/emulated/0/Sounds/CallRecord"
            )
        ),
        ONEPLUS_OPPO(
            "OnePlus / Oppo",
            listOf(
                "/storage/emulated/0/CallRecording",
                "/storage/emulated/0/Recordings/CallRecording"
            )
        ),
        CUSTOM(
            "Custom folder",
            emptyList() // resolved at runtime via getCustomFolder()
        );

        companion object {
            fun labels(): Array<String> = values().map { it.label }.toTypedArray()
        }
    }
}
