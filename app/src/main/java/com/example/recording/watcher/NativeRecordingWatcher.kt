package com.example.recording.watcher

import android.content.Context
import android.os.FileObserver
import android.util.Log
import com.example.recording.AppPrefs
import com.example.recording.AppPrefs.DeviceBrand
import com.example.recording.model.UploadPayload
import com.example.recording.upload.UploadWorker
import java.io.File

/**
 * Watches OEM call-recording folders (Xiaomi/MIUI, Vivo/FuntouchOS) for new files
 * and enqueues them for upload to Supabase.
 *
 * No recording is done here — we rely entirely on the OEM's built-in recorder
 * which captures both sides with full system privileges.
 */
class NativeRecordingWatcher(private val context: Context) {

    private val activeObservers = mutableListOf<FileObserver>()

    /** Resolve which folder paths to watch based on current brand preference. */
    private fun resolvePaths(): List<String> {
        val brand = AppPrefs.getDeviceBrand(context)
        return if (brand == DeviceBrand.CUSTOM) {
            val custom = AppPrefs.getCustomFolder(context)
            if (custom.isBlank()) emptyList() else listOf(custom)
        } else {
            brand.folders
        }
    }

    fun start() {
        stop()
        val candidatePaths = resolvePaths()
        val watching = mutableListOf<String>()
        candidatePaths.forEach { path ->
            val dir = File(path)
            if (!dir.exists()) dir.mkdirs()
            val observer = buildObserver(dir)
            observer.startWatching()
            activeObservers.add(observer)
            if (dir.exists()) watching.add(path)
        }
        Log.i(TAG, "Watching ${watching.size} OEM folder(s): $watching")
    }

    fun stop() {
        activeObservers.forEach { runCatching { it.stopWatching() } }
        activeObservers.clear()
        Log.i(TAG, "Stopped all folder watchers")
    }

    /** Returns all OEM folders that actually exist on this device. */
    fun detectedFolders(): List<String> =
        resolvePaths().filter { File(it).exists() }

    @Suppress("DEPRECATION")
    private fun buildObserver(dir: File): FileObserver {
        val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            object : FileObserver(dir, mask) {
                override fun onEvent(event: Int, path: String?) = handleEvent(dir, path)
            }
        } else {
            object : FileObserver(dir.absolutePath, mask) {
                override fun onEvent(event: Int, path: String?) = handleEvent(dir, path)
            }
        }
    }

    private fun handleEvent(dir: File, fileName: String?) {
        if (fileName == null) return
        if (!isAudioFile(fileName)) return

        val file = File(dir, fileName)
        Log.i(TAG, "New OEM recording detected: ${file.absolutePath}")

        // Wait for the OEM recorder to fully flush and close the file.
        // We poll up to 5 seconds; most recorders finish within 1-2s of CLOSE_WRITE.
        Thread {
            waitForFileStable(file)
            enqueueUpload(file)
        }.also { it.isDaemon = true }.start()
    }

    private fun waitForFileStable(file: File, maxWaitMs: Long = 5_000) {
        var lastSize = -1L
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (System.currentTimeMillis() < deadline) {
            val size = file.length()
            if (size > 0 && size == lastSize) return
            lastSize = size
            Thread.sleep(500)
        }
        Log.w(TAG, "File size did not stabilise within ${maxWaitMs}ms: ${file.name}")
    }

    private fun enqueueUpload(file: File) {
        if (!file.exists() || file.length() == 0L) {
            Log.w(TAG, "Skipping empty/missing file: ${file.name}")
            return
        }

        // Parse metadata from filename — most OEMs embed timestamp and number.
        // Fallback: use file last-modified and "Unknown" number.
        val meta = parseOemFilename(file.name)

        Log.i(TAG, "Enqueueing upload: ${file.name} " +
            "number=${meta.number} direction=${meta.direction} ts=${meta.timestampMs}")

        UploadWorker.enqueue(
            context = context,
            payload = UploadPayload(
                filePath        = file.absolutePath,
                number          = meta.number,
                direction       = meta.direction,
                timestampMillis = meta.timestampMs,
                durationMillis  = 0L,
                sourceUsed      = "OEM_NATIVE"
            )
        )
    }

    // ── Filename parsing ──────────────────────────────────────────────────────
    //
    // MIUI example:  +919876543210_20240418_112345.m4a
    //                Call_2024-04-18-11-23-45_+919876543210.amr
    // Vivo example:  20240418_112345_+919876543210.mp3
    //                Record_112345_+919876543210.mp3
    // Fallback:      file last-modified timestamp, number = "Unknown"

    private data class FileMeta(val number: String, val direction: String, val timestampMs: Long)

    private fun parseOemFilename(name: String): FileMeta {
        val ts = extractTimestamp(name) ?: File("/storage/emulated/0").lastModified()
            .takeIf { it > 0 } ?: System.currentTimeMillis()
        val number = extractNumber(name) ?: "Unknown"
        return FileMeta(number = number, direction = "UNKNOWN", timestampMs = ts)
    }

    private fun extractNumber(name: String): String? {
        // Match phone numbers: optional + then 7-15 digits
        val m = Regex("""(\+?\d{7,15})""").find(name) ?: return null
        return m.value
    }

    private fun extractTimestamp(name: String): Long? {
        // YYYYMMDD_HHMMSS or YYYY-MM-DD-HH-MM-SS
        val patterns = listOf(
            Regex("""(\d{4})(\d{2})(\d{2})_(\d{2})(\d{2})(\d{2})"""),
            Regex("""(\d{4})-(\d{2})-(\d{2})-(\d{2})-(\d{2})-(\d{2})""")
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

    private fun isAudioFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".mp3") || lower.endsWith(".m4a") ||
               lower.endsWith(".amr") || lower.endsWith(".aac") ||
               lower.endsWith(".wav") || lower.endsWith(".ogg") ||
               lower.endsWith(".3gp")
    }

    companion object {
        private const val TAG = "NativeRecordingWatcher"
    }
}
