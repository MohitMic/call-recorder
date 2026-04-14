package com.example.recording

import android.content.Context

object AppPrefs {
    private const val PREFS_NAME = "call_recorder_prefs"
    private const val KEY_SUPABASE_URL = "supabase_url"
    private const val KEY_SUPABASE_KEY = "supabase_anon_key"
    private const val KEY_WIFI_ONLY = "upload_wifi_only"
    private const val KEY_DELETE_AFTER = "delete_after_upload"

    fun getSupabaseUrl(context: Context): String =
        prefs(context).getString(KEY_SUPABASE_URL, DEFAULT_SUPABASE_URL) ?: DEFAULT_SUPABASE_URL

    fun setSupabaseUrl(context: Context, url: String) =
        prefs(context).edit().putString(KEY_SUPABASE_URL, url.trim()).apply()

    fun getSupabaseKey(context: Context): String =
        prefs(context).getString(KEY_SUPABASE_KEY, DEFAULT_SUPABASE_KEY) ?: DEFAULT_SUPABASE_KEY

    fun setSupabaseKey(context: Context, key: String) =
        prefs(context).edit().putString(KEY_SUPABASE_KEY, key.trim()).apply()

    fun isWifiOnly(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WIFI_ONLY, true)

    fun setWifiOnly(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_WIFI_ONLY, value).apply()

    fun isDeleteAfterUpload(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DELETE_AFTER, false)

    fun setDeleteAfterUpload(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_DELETE_AFTER, value).apply()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    const val DEFAULT_SUPABASE_URL = "https://tahyfwvlnlufqkzknbcm.supabase.co"
    const val DEFAULT_SUPABASE_KEY = "sb_publishable_VPkvLBuf_p8ltI4LH0bTuw_PHp6rLYn"
}
