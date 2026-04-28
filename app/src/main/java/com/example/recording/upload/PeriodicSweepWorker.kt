package com.example.recording.upload

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.recording.AppPrefs
import com.example.recording.AppPrefs.DeviceBrand
import com.example.recording.model.UploadPayload
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs every 15 minutes (minimum WorkManager allows) and enqueues uploads for
 * any audio files in the OEM call-recording folders that don't yet have a
 * `.uploaded` marker.
 *
 * This is the safety net for when Android (especially Vivo / Xiaomi / OnePlus)
 * kills our foreground service and the FileObserver-based watcher stops
 * noticing new files. With this worker registered, any recording will reach
 * the admin panel within ~15 minutes regardless of whether the app is open or
 * the foreground service is alive.
 *
 * Survives:
 *   • Foreground service being killed
 *   • App being swiped from recents
 *   • Device reboot (re-enqueued by BootCompletedReceiver)
 *   • Network outage (queued by WorkManager, runs when network returns)
 *
 * Does NOT survive:
 *   • App being uninstalled
 *   • User explicitly clearing app data
 *   • Some OEM "extreme battery saver" modes that block all WorkManager
 *     execution — there's no software fix for that, only user setting change
 */
class PeriodicSweepWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val brand = AppPrefs.getDeviceBrand(ctx)
        val paths = if (brand == DeviceBrand.CUSTOM) {
            val custom = AppPrefs.getCustomFolder(ctx)
            if (custom.isBlank()) emptyList() else listOf(custom)
        } else {
            brand.folders
        }

        var enqueued = 0
        paths.forEach { path ->
            val dir = File(path)
            if (!dir.exists()) return@forEach
            val files = dir.listFiles { f ->
                f.isFile && isAudioFile(f.name)
            } ?: return@forEach

            files.forEach { file ->
                val uploadedMark = File(file.parentFile, "${file.nameWithoutExtension}.uploaded")
                if (uploadedMark.exists()) return@forEach
                if (file.length() == 0L) return@forEach

                val phone = extractNumber(file.name) ?: "Unknown"
                val timestamp = extractTimestamp(file.name) ?: file.lastModified()

                UploadWorker.enqueue(
                    context = ctx,
                    payload = UploadPayload(
                        filePath        = file.absolutePath,
                        number          = phone,
                        direction       = "UNKNOWN",
                        timestampMillis = timestamp,
                        durationMillis  = 0L,
                        sourceUsed      = "OEM_NATIVE",
                    ),
                )
                enqueued++
            }
        }

        Log.i(TAG, "Periodic sweep done — enqueued $enqueued file(s) for upload")
        return Result.success()
    }

    private fun isAudioFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".mp3") || lower.endsWith(".m4a") ||
               lower.endsWith(".amr") || lower.endsWith(".aac") ||
               lower.endsWith(".wav") || lower.endsWith(".ogg") ||
               lower.endsWith(".3gp") || lower.endsWith(".mp4")
    }

    private fun extractNumber(name: String): String? =
        Regex("""(\+?\d{7,15})""").find(name)?.value

    private fun extractTimestamp(name: String): Long? {
        // YYYYMMDD_HHMMSS or YYYY-MM-DD-HH-MM-SS
        val patterns = listOf(
            Regex("""(\d{4})(\d{2})(\d{2})_(\d{2})(\d{2})(\d{2})"""),
            Regex("""(\d{4})-(\d{2})-(\d{2})-(\d{2})-(\d{2})-(\d{2})"""),
            Regex("""(\d{4})-(\d{2})-(\d{2}) (\d{2})-(\d{2})-(\d{2})"""),
        )
        for (p in patterns) {
            val m = p.find(name) ?: continue
            val (yr, mo, dy, hr, mn, sc) = m.destructured
            return runCatching {
                java.util.Calendar.getInstance().apply {
                    set(yr.toInt(), mo.toInt() - 1, dy.toInt(), hr.toInt(), mn.toInt(), sc.toInt())
                    set(java.util.Calendar.MILLISECOND, 0)
                }.timeInMillis
            }.getOrNull()
        }
        return null
    }

    companion object {
        private const val TAG = "PeriodicSweep"
        private const val WORK_NAME = "periodic_oem_sweep"

        /**
         * Idempotent — schedules the worker if not already scheduled.
         * Call from MainActivity onCreate AND BootCompletedReceiver so that
         * after either event the worker is guaranteed to be running.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<PeriodicSweepWorker>(
                15, TimeUnit.MINUTES, // repeat interval — 15 min is Android's hard minimum
                5,  TimeUnit.MINUTES, // flex window — actual run can be up to 5 min late
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP, // don't restart if already scheduled
                request,
            )
            Log.i(TAG, "Periodic sweep scheduled (15 min interval)")
        }
    }
}
