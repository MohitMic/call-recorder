package com.example.recording.model

import com.example.recording.debug.SessionDebugCollector
import kotlinx.serialization.Serializable

enum class CallDirection {
    INCOMING,
    OUTGOING,
    UNKNOWN
}

enum class CallLifecycleState {
    IDLE,
    RINGING,
    ACTIVE
}

@Serializable
data class UploadPayload(
    val filePath: String,
    val number: String,
    val direction: String,
    val timestampMillis: Long,
    val durationMillis: Long,
    val sourceUsed: String
)

data class ActiveCallSession(
    val number: String,
    val direction: CallDirection,
    val startedAt: Long,
    val sourceUsed: String,
    val sourceAttempts: List<SourceAttempt>,
    val filePath: String,
    val activeEntryDetail: String,
    val telephonyListenerMode: String,
    val hadTelephonyOffhookBeforeStart: Boolean,
    val debug: SessionDebugCollector
)
