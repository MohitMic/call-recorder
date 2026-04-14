package com.example.recording.ui

/**
 * Snapshot pushed ~1 s while the service is alive.
 */
data class LiveRecorderStatus(
    val serviceRunning: Boolean,
    /** Our state machine: IDLE / RINGING / ACTIVE */
    val appCallState: String,
    /** Raw TelephonyManager call state name */
    val systemCallState: String,
    val telephonySawOffhookThisCall: Boolean,
    val isRecording: Boolean,
    val recordingElapsedSeconds: Long,
    val currentAudioSource: String,
    /** Anonymised number for the current/last call */
    val currentCallNumber: String = "",
    /** Frozen at moment recording started */
    val hadTelephonyOffhookBeforeRecordingStarted: Boolean?,
    /** True when a live MediaProjection token is available */
    val mediaProjectionReady: Boolean = false,
    /** Short single-line banner */
    val healthBanner: String,
    /** Multi-line detail block */
    val detailText: String
)
