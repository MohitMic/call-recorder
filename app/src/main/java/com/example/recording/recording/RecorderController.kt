package com.example.recording.recording

import android.content.Context
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import com.example.recording.capture.DualChannelRecorder
import com.example.recording.capture.MediaProjectionStore
import com.example.recording.model.CallDirection
import com.example.recording.model.SourceAttempt
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages recorder lifecycle with a multi-tier source fallback chain:
 *
 *  1. DualChannelRecorder  — AudioPlaybackCapture + mic (needs MediaProjection)
 *  2. VOICE_CALL (4)       — Both sides; blocked on stock Android 10+ via
 *                            CAPTURE_AUDIO_OUTPUT, but works on Vivo FuntouchOS 15
 *                            and several OxygenOS 16 builds whose audio HAL relaxes
 *                            the permission check for user-installed apps.
 *  3. VOICE_DOWNLINK (3)   — Speaker-side only; same OEM-relaxed path.
 *  4. VOICE_UPLINK (2)     — Mic-side from telephony stack; same OEM-relaxed path.
 *  5. VOICE_COMMUNICATION  — Reliable mic capture on all devices (current side only).
 *  6. VOICE_RECOGNITION    — Alternative mic mode.
 *  7. MIC                  — Absolute last resort.
 *
 * Sources 2/3/4 throw SecurityException or RuntimeException on devices that
 * enforce the permission.  Those failures are caught, logged, and the next
 * source is tried — so the chain degrades safely on any device.
 */
class RecorderController(private val context: Context) {

    data class StartResult(
        val filePath: String,
        val sourceUsed: String,
        val sourceAttempts: List<SourceAttempt>
    )

    private var mediaRecorder: MediaRecorder? = null
    private var dualRecorder: DualChannelRecorder? = null
    private var currentPath: String? = null

    // When speakerphone is forced on for recording, remember the prior state so we can restore it.
    private var priorSpeakerphoneState: Boolean? = null
    private var speakerphoneWasForced: Boolean = false

    fun start(
        direction: CallDirection,
        number: String,
        forceSpeakerphone: Boolean = false
    ): Pair<StartResult?, List<SourceAttempt>> {
        if (mediaRecorder != null || dualRecorder != null) return Pair(null, emptyList())

        // Force speakerphone BEFORE starting the recorder so the mic captures the
        // far-end voice routed through the loudspeaker. Skip if a BT headset is
        // already handling call audio — in that case SCO gives better quality.
        if (forceSpeakerphone && !isBluetoothScoConnected()) {
            applyForcedSpeakerphone(true)
        }

        val targetFile = createFile(direction, number)
        val attempts = mutableListOf<SourceAttempt>()

        // ── Tier 1: Dual-channel (AudioPlaybackCapture + mic) ────────────────
        val projection = MediaProjectionStore.projection
        if (projection != null) {
            val dual = DualChannelRecorder(projection)
            try {
                val result = dual.start(targetFile)
                if (result != null) {
                    dualRecorder = dual
                    currentPath = result.filePath
                    attempts.add(SourceAttempt(result.sourceUsed, "OK", result.captureNote))
                    Log.i(TAG, "Tier 1 OK: ${result.sourceUsed}")
                    return Pair(StartResult(result.filePath, result.sourceUsed, attempts), attempts)
                } else {
                    attempts.add(SourceAttempt("DUAL_CHANNEL", "FAIL", "DualChannelRecorder returned null"))
                    Log.w(TAG, "Tier 1: DualChannelRecorder.start() returned null")
                }
            } catch (e: Exception) {
                attempts.add(SourceAttempt("DUAL_CHANNEL", "FAIL", e.message ?: e.javaClass.simpleName))
                Log.w(TAG, "Tier 1: DualChannelRecorder threw", e)
            }
        } else {
            Log.i(TAG, "Tier 1 skipped — no MediaProjection token")
        }

        // ── Tiers 2–7: MediaRecorder source fallback chain ───────────────────
        //
        //  Sources 2/3/4: OEM-extended telephony capture.
        //    - Vivo FuntouchOS 15: VOICE_CALL (4) often works without root.
        //    - OxygenOS 16 (OnePlus 13/Open): VOICE_DOWNLINK (3) or VOICE_CALL (4)
        //      may succeed depending on the specific ROM build.
        //    - Stock Android 10+: these throw SecurityException or RuntimeException
        //      ("setAudioSource failed"). We catch both and move on.
        //
        //  NOTE: Bluetooth SCO state is detected here; when a BT headset is
        //  connected the VOICE_COMMUNICATION source already routes through SCO
        //  on most Android builds, giving improved remote-party capture without
        //  any additional API calls.
        val btScoNote = if (isBluetoothScoConnected()) " [BT-SCO active]" else ""

        // Source order. When speakerphone is forced, we put raw MIC ahead of
        // VOICE_COMMUNICATION — the latter applies AEC that treats speakerphone
        // output as echo and cancels the far-end audio, producing a silent file.
        val orderedSources: List<Int> = if (speakerphoneWasForced) {
            listOf(
                SOURCE_VOICE_CALL,
                SOURCE_VOICE_DOWNLINK,
                SOURCE_VOICE_UPLINK,
                MediaRecorder.AudioSource.MIC,                 // raw mic: far-end audible via speaker
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.VOICE_COMMUNICATION  // last resort: AEC may silence remote side
            )
        } else {
            listOf(
                SOURCE_VOICE_CALL,
                SOURCE_VOICE_DOWNLINK,
                SOURCE_VOICE_UPLINK,
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC
            )
        }

        for (source in orderedSources) {
            val sourceName = sourceToName(source)
            val recorder = buildRecorder()
            try {
                recorder.setAudioSource(source)
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setAudioEncodingBitRate(96_000)
                recorder.setAudioSamplingRate(44_100)
                recorder.setOutputFile(targetFile.absolutePath)
                recorder.prepare()
                recorder.start()

                mediaRecorder = recorder
                currentPath = targetFile.absolutePath
                val note = if (source <= SOURCE_VOICE_CALL) "OEM-extended source$btScoNote"
                           else "standard source$btScoNote"
                attempts.add(SourceAttempt(sourceName, "OK", note))
                Log.i(TAG, "Recorder started: $sourceName ($note)")
                return Pair(
                    StartResult(targetFile.absolutePath, sourceName, attempts),
                    attempts
                )
            } catch (ex: SecurityException) {
                // Expected on stock Android for sources 2/3/4
                val msg = "SECURITY: ${ex.message ?: "access denied"}"
                attempts.add(SourceAttempt(sourceName, "BLOCKED", msg))
                Log.i(TAG, "$sourceName blocked (SecurityException) — trying next source")
                recorder.release()
            } catch (ex: Exception) {
                val msg = ex.message ?: ex.javaClass.simpleName
                attempts.add(SourceAttempt(sourceName, "FAIL", msg))
                Log.w(TAG, "$sourceName failed: $msg")
                recorder.release()
            }
        }

        Log.e(TAG, "All sources exhausted — recording failed")
        if (speakerphoneWasForced) applyForcedSpeakerphone(false)
        return Pair(null, attempts)
    }

    fun stopAndRelease(): String? {
        dualRecorder?.let {
            val path = it.stop()
            dualRecorder = null
            val saved = currentPath
            currentPath = null
            if (speakerphoneWasForced) applyForcedSpeakerphone(false)
            return path ?: saved
        }
        mediaRecorder?.let { recorder ->
            runCatching { recorder.stop() }
                .onFailure { Log.w(TAG, "MediaRecorder stop failed cleanly", it) }
            recorder.release()
            mediaRecorder = null
            val path = currentPath
            currentPath = null
            if (speakerphoneWasForced) applyForcedSpeakerphone(false)
            return path
        }
        if (speakerphoneWasForced) applyForcedSpeakerphone(false)
        return currentPath
    }

    fun activeSourceName(): String = when {
        dualRecorder != null  -> "DUAL_CHANNEL"
        mediaRecorder != null -> "MediaRecorder"
        else -> "none"
    }

    fun isActive(): Boolean = mediaRecorder != null || dualRecorder != null

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context)
        else @Suppress("DEPRECATION") MediaRecorder()

    /**
     * Returns true when Bluetooth SCO is connected (headset in call).
     * When true, VOICE_COMMUNICATION will route through the BT SCO stream,
     * which on many OEMs provides better remote-party capture than the
     * speakerphone path.
     */
    private fun isBluetoothScoConnected(): Boolean {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.isBluetoothScoOn
        } catch (_: Exception) { false }
    }

    /**
     * Force loudspeaker on before starting capture so the microphone can pick up
     * the far-end audio via the phone's own speaker — the only non-root way to
     * get both call sides on stock Android 10+. Does NOT touch audio mode; the
     * telephony stack owns MODE_IN_CALL during a cellular call.
     *
     * `enable=true` stores the prior speakerphone state and turns it on.
     * `enable=false` restores the prior state and clears tracking flags.
     */
    private fun applyForcedSpeakerphone(enable: Boolean) {
        val am = try {
            context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        } catch (e: Exception) {
            Log.w(TAG, "AudioManager unavailable — cannot toggle speakerphone", e)
            return
        }
        if (enable) {
            priorSpeakerphoneState = runCatching { am.isSpeakerphoneOn }.getOrNull()
            runCatching {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = true
            }.onFailure { Log.w(TAG, "setSpeakerphoneOn(true) failed", it) }
            speakerphoneWasForced = true
            Log.i(TAG, "Speakerphone forced ON for recording (prior=$priorSpeakerphoneState)")
        } else {
            val prior = priorSpeakerphoneState
            if (prior != null) {
                runCatching {
                    @Suppress("DEPRECATION")
                    am.isSpeakerphoneOn = prior
                }.onFailure { Log.w(TAG, "restore speakerphone failed", it) }
                Log.i(TAG, "Speakerphone restored to prior state=$prior")
            }
            priorSpeakerphoneState = null
            speakerphoneWasForced = false
        }
    }

    private fun createFile(direction: CallDirection, number: String): File {
        val dir = File(context.getExternalFilesDir(null), "CallRecordings")
        dir.mkdirs()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safe = number.ifBlank { "Unknown" }.replace("[^0-9+]".toRegex(), "")
            .take(15)
        return File(dir, "${direction.name}__${safe}_$ts.mp4")
    }

    private fun sourceToName(source: Int): String = when (source) {
        SOURCE_VOICE_UPLINK                          -> "VOICE_UPLINK"
        SOURCE_VOICE_DOWNLINK                        -> "VOICE_DOWNLINK"
        SOURCE_VOICE_CALL                            -> "VOICE_CALL"
        MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
        MediaRecorder.AudioSource.VOICE_RECOGNITION   -> "VOICE_RECOGNITION"
        MediaRecorder.AudioSource.MIC                 -> "MIC"
        else                                          -> "SOURCE_$source"
    }

    companion object {
        private const val TAG = "RecorderController"

        // AudioSource constants not exposed in public API but valid on many OEMs
        const val SOURCE_VOICE_UPLINK   = 2
        const val SOURCE_VOICE_DOWNLINK = 3
        const val SOURCE_VOICE_CALL     = 4
    }
}
