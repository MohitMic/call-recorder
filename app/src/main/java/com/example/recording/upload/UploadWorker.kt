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

        val payload = Json.decodeFromString<UploadPayload>(payloadRaw)
        val src = File(payload.filePath)
        val failedMarker   = File(src.parentFile, "${src.nameWithoutExtension}.failed")
        val uploadedMarker = File(src.parentFile, "${src.nameWithoutExtension}.uploaded")

        val uploader = FileUploader()
        val success = runCatching { uploader.upload(payload) }.getOrDefault(false)

        return if (success) {
            // Clear failure marker if present, write success marker
            failedMarker.delete()
            uploadedMarker.createNewFile()
            if (deleteAfterUpload) src.delete()
            Result.success()
        } else {
            // Write failure marker so UI shows "Upload Failed" immediately
            runCatching { failedMarker.createNewFile() }
            Result.retry()
        }
    }

    companion object {
        private const val KEY_PAYLOAD            = "payload"
        private const val KEY_DELETE_AFTER_UPLOAD = "deleteAfterUpload"

        fun enqueue(context: Context, payload: UploadPayload) {
            val data = Data.Builder()
                .putString(KEY_PAYLOAD, Json.encodeToString(payload))
                .putBoolean(KEY_DELETE_AFTER_UPLOAD, AppPrefs.isDeleteAfterUpload(context))
                .build()

            // Always upload on any connection — no WiFi-only restriction
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(data)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
