package com.example.recording.upload

import android.util.Log
import com.example.recording.Config
import com.example.recording.model.UploadPayload
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Uploads a call recording to Cloudinary (audio storage) and inserts a
 * metadata row into the Supabase `recordings` table.
 *
 * Cloudinary — unsigned upload
 * ─────────────────────────────
 *   POST https://api.cloudinary.com/v1_1/{cloud_name}/video/upload
 *   Body: multipart/form-data
 *     file          – the audio file
 *     upload_preset – name of an unsigned upload preset
 *
 *   Audio files (amr, m4a, aac, etc.) must use resource_type=video in
 *   Cloudinary's terminology — the /video/upload endpoint accepts all
 *   non-image media types.
 *
 * Supabase metadata table (run once in SQL editor):
 * ──────────────────────────────────────────────────
 *   create table if not exists recordings (
 *     id             uuid primary key default gen_random_uuid(),
 *     file_name      text not null,
 *     cloudinary_url text,
 *     number         text,
 *     direction      text,
 *     recorded_at    timestamptz,
 *     duration_ms    bigint,
 *     source_used    text,
 *     created_at     timestamptz default now()
 *   );
 *   alter table recordings enable row level security;
 *   create policy "insert_anon" on recordings for insert to anon with check (true);
 */
class FileUploader(
    private val client: OkHttpClient = OkHttpClient()
) {

    /**
     * Returns true when both the Cloudinary upload and the Supabase metadata
     * insert succeed.  The caller should retry on false.
     */
    fun upload(payload: UploadPayload): Boolean {
        val file = File(payload.filePath)
        if (!file.exists()) {
            Log.w(TAG, "File not found: ${payload.filePath}")
            return true   // nothing to upload — don't retry forever
        }

        if (Config.CLOUDINARY_CLOUD_NAME == "YOUR_CLOUD_NAME") {
            Log.w(TAG, "Cloudinary not configured — skipping upload")
            return true   // treat as success so we don't queue retries with bad config
        }

        val cloudinaryUrl = uploadToCloudinary(file)
        if (cloudinaryUrl == null) {
            Log.w(TAG, "Cloudinary upload failed — will retry")
            return false
        }

        // If we already inserted a Supabase row for this URL, treat it as done
        // (handles the case where Cloudinary upload succeeded on a previous run
        // but Supabase failed and we're retrying).
        val alreadyInSupabase = runCatching { existsInSupabase(cloudinaryUrl) }
            .getOrDefault(false)
        if (alreadyInSupabase) {
            Log.i(TAG, "Supabase row already exists for $cloudinaryUrl")
            return true
        }

        val supabaseOk = runCatching { insertMetadata(payload, cloudinaryUrl) }
            .getOrElse {
                Log.w(TAG, "Supabase insert threw: ${it.message}")
                false
            }
        if (!supabaseOk) {
            Log.w(TAG, "Supabase insert failed — will retry. Cloudinary URL preserved: $cloudinaryUrl")
            return false
        }

        // Final verification: SELECT the row back through the anon role —
        // exactly the same path the admin panel uses to display recordings.
        // Only mark this file as uploaded if the admin panel can actually see
        // it. Catches the case where INSERT is allowed by RLS but SELECT isn't.
        val visibleInAdmin = runCatching { existsInSupabase(cloudinaryUrl) }
            .getOrDefault(false)
        if (!visibleInAdmin) {
            Log.w(TAG, "Inserted but not visible to anon SELECT — will retry. " +
                "Check RLS policies on `recordings`. URL: $cloudinaryUrl")
            return false
        }
        Log.i(TAG, "Verified row is visible in admin panel: $cloudinaryUrl")
        return true
    }

    /** Returns true if a row with this cloudinary_url already exists in Supabase. */
    private fun existsInSupabase(cloudinaryUrl: String): Boolean {
        if (Config.SUPABASE_URL.isBlank() || Config.SUPABASE_KEY.isBlank()) return false
        val encoded = java.net.URLEncoder.encode(cloudinaryUrl, "UTF-8")
        val url = "${Config.SUPABASE_URL.trimEnd('/')}/rest/v1/recordings" +
                  "?select=id&cloudinary_url=eq.$encoded&limit=1"
        val request = Request.Builder()
            .url(url)
            .addHeader("apikey",        Config.SUPABASE_KEY)
            .addHeader("Authorization", "Bearer ${Config.SUPABASE_KEY}")
            .get()
            .build()
        return client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@use false
            val body = resp.body?.string() ?: "[]"
            body.trim() != "[]" && body.trim().isNotEmpty()
        }
    }

    // ── Cloudinary multipart upload ───────────────────────────────────────────

    private fun uploadToCloudinary(file: File): String? {
        val url = "https://api.cloudinary.com/v1_1/${Config.CLOUDINARY_CLOUD_NAME}/video/upload"

        val mime = when (file.extension.lowercase()) {
            "mp3"  -> "audio/mpeg"
            "m4a"  -> "audio/mp4"
            "amr"  -> "audio/amr"
            "aac"  -> "audio/aac"
            "wav"  -> "audio/wav"
            "ogg"  -> "audio/ogg"
            "3gp"  -> "audio/3gpp"
            else   -> "audio/mp4"
        }

        val fileBody  = file.asRequestBody(mime.toMediaTypeOrNull())
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, fileBody)
            .addFormDataPart("upload_preset", Config.CLOUDINARY_UPLOAD_PRESET)
            // `folder` is allowed on unsigned presets; `public_id` often is not.
            .addFormDataPart("folder", "call_recordings")
            .build()

        val request = Request.Builder()
            .url(url)
            .post(multipart)
            .build()

        return client.newCall(request).execute().use { response ->
            val body = response.body?.string()
            if (!response.isSuccessful) {
                Log.w(TAG, "Cloudinary upload failed: HTTP ${response.code} ${response.message} — body=$body")
                return@use null
            }
            if (body == null) {
                Log.w(TAG, "Cloudinary returned empty body")
                return@use null
            }
            Log.i(TAG, "Cloudinary upload OK for ${file.name}")
            runCatching {
                JSONObject(body).getString("secure_url")
            }.getOrElse {
                Log.w(TAG, "Could not parse Cloudinary secure_url from response: $body")
                null
            }
        }
    }

    // ── Supabase metadata insert ──────────────────────────────────────────────

    private fun insertMetadata(payload: UploadPayload, cloudinaryUrl: String): Boolean {
        if (Config.SUPABASE_URL.isBlank() || Config.SUPABASE_KEY.isBlank()) return false

        val url = "${Config.SUPABASE_URL.trimEnd('/')}/rest/v1/recordings"

        val json = JSONObject().apply {
            put("file_name",      File(payload.filePath).name)
            put("cloudinary_url", cloudinaryUrl)
            put("number",         payload.number.ifBlank { null })
            put("direction",      payload.direction)
            put("recorded_at",    iso8601(payload.timestampMillis))
            put("duration_ms",    payload.durationMillis)
            put("source_used",    payload.sourceUsed)
            put("device_id",      payload.deviceId.ifBlank { null })
            put("device_label",   payload.deviceLabel.ifBlank { null })
        }.toString()

        val body    = json.toRequestBody("application/json".toMediaTypeOrNull())
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${Config.SUPABASE_KEY}")
            .addHeader("apikey",        Config.SUPABASE_KEY)
            .addHeader("Prefer",        "return=minimal")
            .post(body)
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errBody = response.body?.string()
                Log.w(TAG, "Supabase insert failed: HTTP ${response.code} ${response.message} — body=$errBody")
            } else {
                Log.i(TAG, "Supabase insert OK for ${File(payload.filePath).name}")
            }
            response.isSuccessful
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun iso8601(epochMillis: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(epochMillis))
    }

    companion object {
        private const val TAG = "FileUploader"
    }
}
