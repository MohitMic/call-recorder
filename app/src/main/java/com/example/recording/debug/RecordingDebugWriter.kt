package com.example.recording.debug

import com.example.recording.model.RecordingDebugReport
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

object RecordingDebugWriter {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    fun writeNextToRecording(audioFile: File, report: RecordingDebugReport) {
        val sidecar = File(audioFile.parentFile, audioFile.nameWithoutExtension + "_debug.json")
        sidecar.writeText(json.encodeToString(report))
    }
}
