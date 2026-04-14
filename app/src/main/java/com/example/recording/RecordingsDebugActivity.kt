package com.example.recording

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.recording.model.RecordingDebugReport
import kotlinx.serialization.json.Json
import java.io.File

class RecordingsDebugActivity : AppCompatActivity() {

    private val json = Json { ignoreUnknownKeys = true }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recordings_debug)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.recording_debug_title)

        val dir = File(getExternalFilesDir(null), "CallRecordings")
        val mp4s = dir.listFiles { f -> f.isFile && f.extension.equals("mp4", true) }
            ?.sortedByDescending { it.lastModified() }
            ?.toList().orEmpty()

        val spinner = findViewById<Spinner>(R.id.recordingSpinner)
        val detail  = findViewById<TextView>(R.id.debugDetail)

        if (mp4s.isEmpty()) {
            detail.text = getString(R.string.no_recordings_found)
            spinner.visibility = View.GONE
            return
        }

        val labels = mp4s.map { it.name }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)

        fun showForIndex(index: Int) {
            if (index !in mp4s.indices) return
            val audio = mp4s[index]
            val sidecar = File(audio.parentFile, "${audio.nameWithoutExtension}_debug.json")
            if (!sidecar.exists()) {
                detail.text = getString(R.string.no_debug_sidecar, sidecar.name)
                return
            }
            val report = runCatching {
                json.decodeFromString<RecordingDebugReport>(sidecar.readText())
            }.getOrElse { ex ->
                detail.text = getString(R.string.debug_parse_error, ex.message ?: ex.toString())
                return
            }
            detail.text = formatReport(report)
        }

        // If launched with a specific file path, pre-select it
        val preSelectedPath = intent.getStringExtra(EXTRA_FILE_PATH)
        val startIndex = if (preSelectedPath != null) {
            mp4s.indexOfFirst { it.absolutePath == preSelectedPath }.coerceAtLeast(0)
        } else 0

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                showForIndex(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        spinner.setSelection(startIndex)
        showForIndex(startIndex)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun formatReport(r: RecordingDebugReport): String = buildString {
        appendLine("File:          ${r.audioFileName}")
        appendLine("Size:          ${r.outputFileSizeBytes} bytes")
        appendLine("Duration:      ${r.durationMillis} ms")
        appendLine("Direction:     ${r.direction}  |  Number: ${r.numberLabel}")
        appendLine("Audio source:  ${r.audioSourceUsed}")
        appendLine("OFFHOOK before start: ${r.hadTelephonyOffhookBeforeStart ?: "n/a"}")
        appendLine("Telephony:     ${r.telephonyListenerMode}")
        appendLine("Device:        ${r.deviceManufacturer} ${r.deviceModel} (API ${r.sdkInt})")
        appendLine("Trigger:       ${r.activeCallTriggerSummary}")
        appendLine()
        appendLine("── Source attempts ──────────────────────────────")
        r.sourceAttempts.forEach {
            appendLine("  ${it.source}: ${it.outcome}${it.error?.let { e -> "  ($e)" } ?: ""}")
        }
        appendLine()
        appendLine("── Timeline (ms offset from recording start) ───")
        val t0 = r.startedAtMillis
        r.timeline.forEach {
            appendLine("  +${it.timeMillis - t0} ms  ${it.message}")
        }
        appendLine()
        appendLine("── Hints ────────────────────────────────────────")
        r.hints.forEach { appendLine("• $it") }
    }

    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"
    }
}
