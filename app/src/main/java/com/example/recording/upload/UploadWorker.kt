package com.example.recording.upload

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.recording.AppPrefs
import com.example.recording.model.UploadPayload
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.TimeUnit

class UploadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val payloadRaw = inputData.getString(KEY_PAYLOAD) ?: return Result.failure()
        val deleteAfterUpload = inputData.getBoolean(KEY_DELETE_AFTER_UPLOAD, false)
        val supabaseUrl = inputData.getString(KEY_SUPABASE_URL) ?: ""
        val supabaseKey = inputData.getString(KEY_SUPABASE_KEY) ?: ""

        val payload = Json.decodeFromString<UploadPayload>(payloadRaw)

        val uploader = FileUploader(supabaseUrl = supabaseUrl, supabaseKey = supabaseKey)
        val success = runCatching { uploader.upload(payload) }.getOrDefault(false)
        if (!success) return Result.retry()

        if (deleteAfterUpload) {
            File(payload.filePath).delete()
        }
        return Result.success()
    }

    companion object {
        private const val KEY_PAYLOAD = "payload"
        private const val KEY_DELETE_AFTER_UPLOAD = "deleteAfterUpload"
        private const val KEY_SUPABASE_URL = "supabaseUrl"
        private const val KEY_SUPABASE_KEY = "supabaseKey"

        fun enqueue(
            context: Context,
            payload: UploadPayload,
            requireUnmetered: Boolean,
            deleteAfterUpload: Boolean
        ) {
            val data = Data.Builder()
                .putString(KEY_PAYLOAD, Json.encodeToString(payload))
                .putBoolean(KEY_DELETE_AFTER_UPLOAD, deleteAfterUpload)
                .putString(KEY_SUPABASE_URL, AppPrefs.getSupabaseUrl(context))
                .putString(KEY_SUPABASE_KEY, AppPrefs.getSupabaseKey(context))
                .build()

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(
                    if (requireUnmetered) NetworkType.UNMETERED else NetworkType.CONNECTED
                )
                .build()

            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(data)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
