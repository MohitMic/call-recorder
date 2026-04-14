package com.example.recording

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Tests each AudioSource on this device for a short recording burst
 * and reports which ones are accessible, working, or blocked.
 *
 * Useful for diagnosing OEM-specific audio routing (Vivo FuntouchOS, OxygenOS, etc.).
 */
class CompatibilityScanner(private val context: Context) {

    data class SourceResult(
        val sourceName: String,
        val sourceValue: Int,
        val status: ScanStatus,
        val fileSizeBytes: Long = 0L,
        val errorMessage: String = ""
    )

    enum class ScanStatus {
        WORKS,               // File size > threshold — audio was captured
        ACCESSIBLE_SILENT,   // Recorder started but file is tiny — likely silence/blocked audio
        BLOCKED,             // SecurityException — OS policy prevents access
        FAILED               // Other exception (init error, prepare failed, etc.)
    }

    companion object {
        /** All sources worth testing. Values 2/3/4 require CAPTURE_AUDIO_OUTPUT on stock
         *  Android but may work on Vivo FuntouchOS 15 and some OxygenOS builds. */
        private val SOURCES = listOf(
            "MIC"                  to 1,
            "VOICE_UPLINK"         to 2,
            "VOICE_DOWNLINK"       to 3,
            "VOICE_CALL"           to 4,
            "VOICE_RECOGNITION"    to 6,
            "VOICE_COMMUNICATION"  to 7,
            "UNPROCESSED"          to 9
        )
        private const val RECORD_MS = 1_500L
        private const val WORKS_THRESHOLD_BYTES = 8_000L
    }

    suspend fun scan(onProgress: (String) -> Unit): List<SourceResult> =
        withContext(Dispatchers.IO) {
            val scanDir = File(context.getExternalFilesDir(null), "CallRecordings/compat_scan")
            scanDir.mkdirs()
            val results = mutableListOf<SourceResult>()

            for ((name, value) in SOURCES) {
                onProgress("Testing $name (source $value)…")
                val testFile = File(scanDir, "test_${name}.mp4")
                testFile.delete()

                val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(context)
                } else {
                    @Suppress("DEPRECATION") MediaRecorder()
                }

                try {
                    recorder.setAudioSource(value)
                    recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    recorder.setAudioEncodingBitRate(64_000)
                    recorder.setAudioSamplingRate(44_100)
                    recorder.setOutputFile(testFile.absolutePath)
                    recorder.prepare()
                    recorder.start()
                    delay(RECORD_MS)
                    recorder.stop()

                    val size = if (testFile.exists()) testFile.length() else 0L
                    results.add(
                        SourceResult(
                            sourceName = name,
                            sourceValue = value,
                            status = if (size >= WORKS_THRESHOLD_BYTES) ScanStatus.WORKS
                                     else ScanStatus.ACCESSIBLE_SILENT,
                            fileSizeBytes = size
                        )
                    )
                } catch (e: SecurityException) {
                    results.add(SourceResult(name, value, ScanStatus.BLOCKED,
                        errorMessage = e.message ?: "Security: access denied"))
                } catch (e: Exception) {
                    results.add(SourceResult(name, value, ScanStatus.FAILED,
                        errorMessage = e.message ?: e.javaClass.simpleName))
                } finally {
                    runCatching { recorder.release() }
                    testFile.delete()
                }
            }

            // Clean up scan dir if empty
            scanDir.delete()
            results
        }
}
