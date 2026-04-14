package com.example.recording.upload

import android.util.Log
import com.example.recording.model.UploadPayload
import okhttp3.MediaType.Companion.toMediaTypeOrNull
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
 * Uploads a call recording to Supabase Storage and inserts a metadata row
 * into the `recordings` PostgREST table.
 *
 * Required Supabase setup (run once in the SQL editor):
 * ─────────────────────────────────────────────────────
 *   -- Storage bucket (or create via Dashboard → Storage → New bucket)
 *   insert into storage.buckets (id, name, public)
 *   values ('call-recordings', 'call-recordings', false);
 *
 *   -- Metadata table
 *   create table recordings (
 *     id            uuid primary key default gen_random_uuid(),
 *     file_name     text not null,
 *     storage_path  text not null,
 *     number        text,
 *     direction     text,
 *     recorded_at   timestamptz,
 *     duration_ms   bigint,
 *     source_used   text,
 *     created_at    timestamptz default now()
 *   );
 *
 *   -- Allow anon key to insert (RLS)
 *   alter table recordings enable row level security;
 *   create policy "insert_anon" on recordings for insert to anon with check (true);
 *
 *   -- Allow anon key to upload to the bucket (Storage policy via Dashboard or SQL)
 *   -- Dashboard → Storage → call-recordings → Policies → New policy → allow INSERT for anon
 * ─────────────────────────────────────────────────────
 */
class FileUploader(
    private val supabaseUrl: String,
    private val supabaseKey: String,
    private val bucketName: String = "call-recordings",
    private val client: OkHttpClient = OkHttpClient()
) {

    /**
     * Returns true when both the storage upload and the metadata insert succeed.
     * The caller should retry on false (WorkManager handles exponential backoff).
     */
    fun upload(payload: UploadPayload): Boolean {
        if (supabaseUrl.isBlank() || supabaseKey.isBlank()) {
            Log.w(TAG, "Supabase URL or key not configured — skipping upload")
            // Return true so WorkManager doesn't retry forever with bad config
            return true
        }
        val file = File(payload.filePath)
        if (!file.exists()) {
            Log.w(TAG, "File not found: ${payload.filePath}")
            return true  // nothing to upload, don't retry
        }

        val storagePath = file.name   // e.g. "INCOMING__12345_20240101_123456.mp4"

        return uploadToStorage(file, storagePath) && insertMetadata(payload, storagePath)
    }

    // PUT /storage/v1/object/{bucket}/{storagePath}
    private fun uploadToStorage(file: File, storagePath: String): Boolean {
        val url = "${supabaseUrl.trimEnd('/')}/storage/v1/object/$bucketName/$storagePath"
        val body = file.asRequestBody("audio/mp4".toMediaTypeOrNull())
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $supabaseKey")
            .addHeader("apikey", supabaseKey)
            .addHeader("x-upsert", "true")   // overwrite on retry
            .put(body)
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "Storage upload failed: ${response.code} ${response.message}")
            }
            response.isSuccessful
        }
    }

    // POST /rest/v1/recordings
    private fun insertMetadata(payload: UploadPayload, storagePath: String): Boolean {
        val url = "${supabaseUrl.trimEnd('/')}/rest/v1/recordings"

        val isoDate = iso8601(payload.timestampMillis)
        val json = JSONObject().apply {
            put("file_name", File(payload.filePath).name)
            put("storage_path", storagePath)
            put("number", payload.number.ifBlank { null })
            put("direction", payload.direction)
            put("recorded_at", isoDate)
            put("duration_ms", payload.durationMillis)
            put("source_used", payload.sourceUsed)
        }.toString()

        val body = json.toRequestBody("application/json".toMediaTypeOrNull())
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $supabaseKey")
            .addHeader("apikey", supabaseKey)
            .addHeader("Prefer", "return=minimal")
            .post(body)
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "Metadata insert failed: ${response.code} ${response.message}")
            }
            response.isSuccessful
        }
    }

    private fun iso8601(epochMillis: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(epochMillis))
    }

    companion object {
        private const val TAG = "FileUploader"
    }
}
