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

        // Sweep existing files: any audio file without a `.uploaded` marker
        // gets enqueued. Handles the case where calls were recorded while the
        // service was off, or before the user installed/configured the app.
        Thread {
            sweepExistingFiles(candidatePaths)
        }.also { it.isDaemon = true }.start()
    }

    private fun sweepExistingFiles(paths: List<String>) {
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
                enqueueUpload(file)
                enqueued++
            }
        }
        Log.i(TAG, "Sweep enqueued $enqueued pending file(s) for upload")
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

        // Generic OEM-agnostic parser: handles Vivo (with parens + spaces),
        // MIUI (underscores), OnePlus (mixed), and falls back gracefully.
        val parsed = OemFilenameParser.parse(file.name, file.lastModified())

        Log.i(TAG, "Enqueueing upload: ${file.name} → " +
            "caller=${parsed.caller} phone=${parsed.phoneNumber} ts=${parsed.timestampMs}")

        UploadWorker.enqueue(
            context = context,
            payload = UploadPayload(
                filePath        = file.absolutePath,
                number          = parsed.caller,
                direction       = "UNKNOWN",
                timestampMillis = parsed.timestampMs,
                durationMillis  = 0L,
                sourceUsed      = "OEM_NATIVE"
            )
        )
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
