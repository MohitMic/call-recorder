package com.example.recording.model

import kotlinx.serialization.Serializable

@Serializable
data class DebugTimelineEntry(
    val timeMillis: Long,
    val message: String
)

@Serializable
data class SourceAttempt(
    val source: String,
    val outcome: String,
    val error: String? = null
)

@Serializable
data class RecordingDebugReport(
    val schemaVersion: Int = 1,
    val audioFileName: String,
    val audioFilePath: String,
    val deviceManufacturer: String,
    val deviceModel: String,
    val sdkInt: Int,
    val telephonyListenerMode: String,
    val direction: String,
    val numberLabel: String,
    val startedAtMillis: Long,
    val stoppedAtMillis: Long,
    val durationMillis: Long,
    val audioSourceUsed: String,
    val sourceAttempts: List<SourceAttempt>,
    val activeCallTriggerSummary: String,
    val timeline: List<DebugTimelineEntry>,
    val outputFileSizeBytes: Long,
    val hints: List<String>,
    val hadTelephonyOffhookBeforeStart: Boolean? = null
)
