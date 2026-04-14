package com.example.recording.capture

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Records two audio streams simultaneously and mixes them to MP4:
 *  1. AudioPlaybackCapture (MediaProjection) — captures what the speaker plays
 *     (USAGE_MEDIA / USAGE_GAME / USAGE_UNKNOWN; telephony audio NOT in this set on most OEMs)
 *  2. AudioRecord VOICE_COMMUNICATION — captures our microphone
 *
 * Both streams are mixed (summed + clamped) into a single mono AAC/MP4 file.
 * If playback capture fails to initialise, recording continues with mic-only.
 */
class DualChannelRecorder(
    private val projection: MediaProjection
) {
    data class StartResult(
        val filePath: String,
        val sourceUsed: String,   // "DUAL_CHANNEL" or "DUAL_CHANNEL_MIC_ONLY"
        val captureNote: String
    )

    private val SAMPLE_RATE = 16_000
    private val MIME = MediaFormat.MIMETYPE_AUDIO_AAC
    private val BIT_RATE = 64_000

    private val minBuf = AudioRecord.getMinBufferSize(
        SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
    ).coerceAtLeast(4096)
    private val bufBytes = minBuf * 4
    private val bufSamples = bufBytes / 2

    @Volatile private var running = false
    private var captureThread: Thread? = null
    private var trackIndex = -1
    private var outputPath: String? = null

    @SuppressLint("MissingPermission")
    fun start(outputFile: File): StartResult? {
        if (running) return null

        // ── Microphone AudioRecord ────────────────────────────────────────────
        val mic = AudioRecord(
            android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, bufBytes
        )
        if (mic.state != AudioRecord.STATE_INITIALIZED) {
            mic.release(); Log.w(TAG, "Mic AudioRecord init failed"); return null
        }

        // ── Playback capture AudioRecord ──────────────────────────────────────
        var pb: AudioRecord? = null
        var sourceUsed = "DUAL_CHANNEL_MIC_ONLY"
        var captureNote = "mic-only (playback capture unavailable on this device/OS)"
        try {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val pbCandidate = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(cfg)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(bufBytes)
                .build()
            if (pbCandidate.state == AudioRecord.STATE_INITIALIZED) {
                pb = pbCandidate
                sourceUsed = "DUAL_CHANNEL"
                captureNote = "dual-channel: mic + playback capture (MEDIA/GAME/UNKNOWN usages)"
            } else {
                pbCandidate.release()
                Log.w(TAG, "AudioPlaybackCapture record init failed — mic only")
            }
        } catch (e: Exception) {
            Log.w(TAG, "AudioPlaybackCapture setup failed: ${e.message}")
        }

        // ── MediaCodec AAC encoder ────────────────────────────────────────────
        val fmt = MediaFormat.createAudioFormat(MIME, SAMPLE_RATE, 1).also {
            it.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            it.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            it.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufBytes * 2)
        }
        val codec = try {
            MediaCodec.createEncoderByType(MIME).also {
                it.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                it.start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaCodec init failed", e)
            mic.release(); pb?.release(); return null
        }

        // ── MediaMuxer MP4 container ──────────────────────────────────────────
        outputFile.parentFile?.mkdirs()
        val muxer = try {
            MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (e: Exception) {
            Log.e(TAG, "MediaMuxer init failed", e)
            mic.release(); pb?.release(); codec.stop(); codec.release(); return null
        }

        outputPath = outputFile.absolutePath
        running = true
        mic.startRecording()
        pb?.startRecording()

        val pbRef = pb
        captureThread = Thread({
            runCapture(mic, pbRef, codec, muxer)
        }, "DualChanCapture").also { it.start() }

        Log.i(TAG, "Started — $captureNote → ${outputFile.name}")
        return StartResult(outputFile.absolutePath, sourceUsed, captureNote)
    }

    private fun runCapture(
        mic: AudioRecord, pb: AudioRecord?,
        codec: MediaCodec, muxer: MediaMuxer
    ) {
        val micBuf = ShortArray(bufSamples)
        val pbBuf  = ShortArray(bufSamples)
        val info   = MediaCodec.BufferInfo()
        var presentationUs = 0L
        val usPerSample = 1_000_000.0 / SAMPLE_RATE

        try {
            while (running) {
                val n = mic.read(micBuf, 0, bufSamples)
                if (n <= 0) continue
                pb?.read(pbBuf, 0, n)   // same count; non-blocking if pb is null

                // Mix: sum + clamp, then feed encoder
                val inputIdx = codec.dequeueInputBuffer(10_000)
                if (inputIdx >= 0) {
                    val buf: ByteBuffer? = codec.getInputBuffer(inputIdx)
                    if (buf != null) {
                        buf.clear()
                        for (i in 0 until n) {
                            val m = micBuf[i].toInt()
                            val p = if (pb != null) pbBuf[i].toInt() else 0
                            buf.putShort((m + p).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
                        }
                        codec.queueInputBuffer(inputIdx, 0, n * 2, presentationUs, 0)
                        presentationUs += (n * usPerSample).toLong()
                    } else {
                        codec.queueInputBuffer(inputIdx, 0, 0, presentationUs, 0)
                    }
                }
                drain(codec, muxer, info, 0)
            }

            // Signal EOS
            val eosIdx = codec.dequeueInputBuffer(10_000)
            if (eosIdx >= 0) {
                codec.queueInputBuffer(eosIdx, 0, 0, presentationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            // Drain remaining
            var eos = false
            while (!eos) {
                drain(codec, muxer, info, 100_000)
                eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "Capture error", e)
        } finally {
            runCatching { mic.stop(); mic.release() }
            runCatching { pb?.stop(); pb?.release() }
            runCatching { codec.stop() }
            runCatching { if (trackIndex >= 0) muxer.stop() }
            runCatching { muxer.release() }
            runCatching { codec.release() }
        }
    }

    private fun drain(codec: MediaCodec, muxer: MediaMuxer, info: MediaCodec.BufferInfo, timeoutUs: Long) {
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, timeoutUs)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (trackIndex < 0) {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                    }
                }
                idx >= 0 -> {
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        && trackIndex >= 0 && info.size > 0
                    ) {
                        val buf: ByteBuffer = codec.getOutputBuffer(idx) ?: run {
                            codec.releaseOutputBuffer(idx, false); return
                        }
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        muxer.writeSampleData(trackIndex, buf, info)
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
                else -> return
            }
        }
    }

    fun stop(): String? {
        if (!running) return outputPath
        running = false
        captureThread?.join(4000)
        captureThread = null
        Log.i(TAG, "Stopped → $outputPath")
        return outputPath
    }

    companion object { private const val TAG = "DualChannelRecorder" }
}
