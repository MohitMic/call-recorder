package com.example.recording

import android.app.AlertDialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.recording.AppPrefs
import com.example.recording.AppPrefs.DeviceBrand
import com.example.recording.model.RecordingDebugReport
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── Data types ────────────────────────────────────────────────────────────────

enum class HealthStatus { GOOD, PARTIAL, SILENT_RISK, UNKNOWN }

enum class UploadStatus { UPLOADED, PENDING, FAILED, NOT_CONFIGURED }

data class RecordingItem(
    val file: File,
    val report: RecordingDebugReport?,
    val health: HealthStatus,
    val uploadStatus: UploadStatus
)

// ── Adapter ───────────────────────────────────────────────────────────────────

class RecordingCardAdapter(
    private val onPlayClick: (RecordingItem) -> Unit
) : ListAdapter<RecordingItem, RecordingCardAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recording_card, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val iconContainer   = view.findViewById<View>(R.id.iconContainer)
        private val iconDirection   = view.findViewById<ImageView>(R.id.iconDirection)
        private val textNumber      = view.findViewById<TextView>(R.id.textNumber)
        private val textHealthBadge = view.findViewById<TextView>(R.id.textHealthBadge)
        private val textDate        = view.findViewById<TextView>(R.id.textDate)
        private val textCallInfo    = view.findViewById<TextView>(R.id.textCallInfo)
        private val textDevice      = view.findViewById<TextView>(R.id.textDevice)
        private val textUploadStatus = view.findViewById<TextView>(R.id.textUploadStatus)
        private val textSourceBadge = view.findViewById<TextView>(R.id.textSourceBadge)
        private val btnPlay         = view.findViewById<Button>(R.id.btnPlay)
        private val btnDetails      = view.findViewById<Button>(R.id.btnDebug)

        fun bind(item: RecordingItem) {
            val ctx = itemView.context
            val r = item.report
            val rawName = item.file.nameWithoutExtension

            // ── Number ──────────────────────────────────────────────────────
            val number = r?.numberLabel?.ifBlank { null }
                ?: parseNumberFromFilename(rawName)
            textNumber.text = if (number.isNullOrBlank()) "Unknown number" else number

            // ── Date ────────────────────────────────────────────────────────
            val ts = r?.startedAtMillis ?: item.file.lastModified()
            textDate.text = formatDate(ts)

            // ── Direction / duration / size ──────────────────────────────────
            val direction = when {
                r?.direction == "INCOMING" -> "Incoming"
                r?.direction == "OUTGOING" -> "Outgoing"
                rawName.startsWith("INCOMING") -> "Incoming"
                rawName.startsWith("OUTGOING") -> "Outgoing"
                else -> "Unknown"
            }
            val duration = if (r != null) formatMs(r.durationMillis) else "—"
            val size = formatBytes(item.file.length())
            textCallInfo.text = "$direction  |  $duration  |  $size"

            // ── Device ──────────────────────────────────────────────────────
            if (r != null) {
                textDevice.text = "${r.deviceManufacturer} ${r.deviceModel}  |  Android API ${r.sdkInt}"
                textDevice.visibility = View.VISIBLE
            } else {
                textDevice.visibility = View.GONE
            }

            // ── Direction icon ───────────────────────────────────────────────
            val isOutgoing = r?.direction == "OUTGOING" || rawName.startsWith("OUTGOING")
            iconDirection.setImageResource(
                if (isOutgoing) R.drawable.ic_call_outgoing else R.drawable.ic_call_incoming
            )

            // ── Health badge + icon circle ────────────────────────────────────
            applyHealth(ctx, item.health)

            // ── Upload status ────────────────────────────────────────────────
            val (uploadText, uploadColor) = when (item.uploadStatus) {
                UploadStatus.UPLOADED       -> "Uploaded" to "#2E7D32"
                UploadStatus.PENDING        -> "Upload pending" to "#E65100"
                UploadStatus.FAILED         -> "Upload failed — retrying" to "#B71C1C"
                UploadStatus.NOT_CONFIGURED -> "Server not set" to "#9E9E9E"
            }
            textUploadStatus.text = uploadText
            textUploadStatus.setTextColor(android.graphics.Color.parseColor(uploadColor))

            // ── Source badge ─────────────────────────────────────────────────
            val source = r?.audioSourceUsed ?: "?"
            applySourceBadge(ctx, source)

            // ── Buttons ───────────────────────────────────────────────────────
            btnPlay.setOnClickListener { onPlayClick(item) }
            btnDetails.setOnClickListener { showDetailsDialog(ctx, item) }
        }

        private fun showDetailsDialog(ctx: Context, item: RecordingItem) {
            val r = item.report
            val sb = StringBuilder()

            sb.appendLine("FILE")
            sb.appendLine("  Name:  ${item.file.name}")
            sb.appendLine("  Path:  ${item.file.absolutePath}")
            sb.appendLine("  Size:  ${formatBytes(item.file.length())}")
            sb.appendLine()

            if (r != null) {
                sb.appendLine("CALL")
                sb.appendLine("  Number:     ${r.numberLabel.ifBlank { "Unknown" }}")
                sb.appendLine("  Direction:  ${r.direction}")
                sb.appendLine("  Date:       ${formatDate(r.startedAtMillis)}")
                sb.appendLine("  Duration:   ${formatMs(r.durationMillis)}")
                sb.appendLine()
                sb.appendLine("DEVICE")
                sb.appendLine("  ${r.deviceManufacturer} ${r.deviceModel}")
                sb.appendLine("  Android API ${r.sdkInt}")
                sb.appendLine()
                sb.appendLine("RECORDING")
                sb.appendLine("  Source used:  ${r.audioSourceUsed}")
                sb.appendLine("  Trigger:      ${r.activeCallTriggerSummary}")
                sb.appendLine("  Telephony:    ${r.telephonyListenerMode}")
                sb.appendLine("  OFFHOOK before start: ${r.hadTelephonyOffhookBeforeStart ?: "n/a"}")
                sb.appendLine()
                sb.appendLine("SOURCE ATTEMPTS")
                r.sourceAttempts.forEach {
                    val err = if (it.error != null) "  (${it.error})" else ""
                    sb.appendLine("  ${it.source}: ${it.outcome}$err")
                }
                sb.appendLine()
                sb.appendLine("UPLOAD")
                sb.appendLine("  Status: ${when (item.uploadStatus) {
                    UploadStatus.UPLOADED       -> "Uploaded to server"
                    UploadStatus.PENDING        -> "Queued / not yet uploaded"
                    UploadStatus.FAILED         -> "Upload failed — WorkManager is retrying"
                    UploadStatus.NOT_CONFIGURED -> "No server configured"
                }}")
                if (r.hints.isNotEmpty()) {
                    sb.appendLine()
                    sb.appendLine("HINTS")
                    r.hints.forEach { sb.appendLine("  - $it") }
                }
            } else {
                sb.appendLine("No debug info available for this recording.")
            }

            AlertDialog.Builder(ctx)
                .setTitle("Recording Details")
                .setMessage(sb.toString().trimEnd())
                .setPositiveButton("OK", null)
                .show()
        }

        private fun applyHealth(ctx: Context, health: HealthStatus) {
            val (bgColor, textColor, label) = when (health) {
                HealthStatus.GOOD        -> Triple(R.color.health_good_bg,    R.color.health_good,    ctx.getString(R.string.health_good))
                HealthStatus.PARTIAL     -> Triple(R.color.health_partial_bg, R.color.health_partial, ctx.getString(R.string.health_partial))
                HealthStatus.SILENT_RISK -> Triple(R.color.health_risk_bg,    R.color.health_risk,    ctx.getString(R.string.health_risk))
                HealthStatus.UNKNOWN     -> Triple(R.color.health_unknown_bg, R.color.health_unknown, ctx.getString(R.string.health_unknown))
            }
            val circleColor = when (health) {
                HealthStatus.GOOD        -> R.color.health_good
                HealthStatus.PARTIAL     -> R.color.health_partial
                HealthStatus.SILENT_RISK -> R.color.health_risk
                HealthStatus.UNKNOWN     -> R.color.health_unknown
            }
            val circle = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ContextCompat.getColor(ctx, circleColor))
            }
            iconContainer.background = circle
            val badgeBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 24f
                setColor(ContextCompat.getColor(ctx, bgColor))
            }
            textHealthBadge.background = badgeBg
            textHealthBadge.setTextColor(ContextCompat.getColor(ctx, textColor))
            textHealthBadge.text = label
        }

        private fun applySourceBadge(ctx: Context, source: String) {
            val (bgRes, fgRes) = when {
                source.contains("DUAL")       -> Pair(R.color.badge_dual_bg,  R.color.badge_dual)
                source.contains("VOICE_CALL") || source.contains("DOWNLINK") ->
                                                 Pair(R.color.badge_dual_bg,  R.color.badge_dual)
                source.contains("VOICE_COMM") || source.contains("VOICE_RECOG") ->
                                                 Pair(R.color.badge_voice_bg, R.color.badge_voice)
                else                          -> Pair(R.color.badge_mic_bg,   R.color.badge_mic)
            }
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 24f
                setColor(ContextCompat.getColor(ctx, bgRes))
            }
            textSourceBadge.background = bg
            textSourceBadge.setTextColor(ContextCompat.getColor(ctx, fgRes))
            textSourceBadge.text = source.replace("_", " ")
        }

        private fun parseNumberFromFilename(name: String): String? {
            val parts = name.split("__")
            return if (parts.size >= 2) parts[1].split("_").firstOrNull() else null
        }

        private fun formatDate(millis: Long): String =
            SimpleDateFormat("dd MMM yyyy  HH:mm:ss", Locale.getDefault()).format(Date(millis))

        private fun formatMs(ms: Long): String {
            val s = ms / 1000; return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
        }

        private fun formatBytes(bytes: Long): String = when {
            bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
            bytes >= 1_000     -> String.format(Locale.US, "%.0f KB", bytes / 1_000.0)
            else               -> "$bytes B"
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<RecordingItem>() {
            override fun areItemsTheSame(a: RecordingItem, b: RecordingItem) =
                a.file.absolutePath == b.file.absolutePath
            override fun areContentsTheSame(a: RecordingItem, b: RecordingItem) =
                a.file.lastModified() == b.file.lastModified() &&
                a.health == b.health && a.uploadStatus == b.uploadStatus
        }

        private val json = Json { ignoreUnknownKeys = true }

        private val AUDIO_EXTENSIONS = setOf("mp4", "mp3", "m4a", "amr", "aac", "wav", "ogg", "3gp")

        fun loadItems(context: Context): List<RecordingItem> {
            // Collect files from app-internal folder AND configured OEM folders
            val allFiles = mutableListOf<File>()

            // App-internal recordings
            val appDir = File(context.getExternalFilesDir(null), "CallRecordings")
            appDir.listFiles { f -> f.isFile && f.extension.lowercase() in AUDIO_EXTENSIONS }
                ?.let { allFiles.addAll(it) }

            // OEM brand folders
            val brand = AppPrefs.getDeviceBrand(context)
            val oemPaths = if (brand == DeviceBrand.CUSTOM) {
                val c = AppPrefs.getCustomFolder(context)
                if (c.isBlank()) emptyList() else listOf(c)
            } else {
                brand.folders
            }
            oemPaths.forEach { path ->
                val dir = File(path)
                if (dir.exists()) {
                    dir.listFiles { f ->
                        f.isFile && f.extension.lowercase() in AUDIO_EXTENSIONS
                    }?.let { allFiles.addAll(it) }
                }
            }

            if (allFiles.isEmpty()) return emptyList()

            // De-duplicate by absolute path and sort newest first
            val files = allFiles
                .distinctBy { it.absolutePath }
                .sortedByDescending { it.lastModified() }

            return files.map { file ->
                val sidecar      = File(file.parentFile, "${file.nameWithoutExtension}_debug.json")
                val uploadedMark = File(file.parentFile, "${file.nameWithoutExtension}.uploaded")
                val failedMark   = File(file.parentFile, "${file.nameWithoutExtension}.failed")

                val report = if (sidecar.exists()) {
                    runCatching {
                        json.decodeFromString<RecordingDebugReport>(sidecar.readText())
                    }.getOrNull()
                } else null

                val uploadStatus = when {
                    uploadedMark.exists() -> UploadStatus.UPLOADED
                    failedMark.exists()   -> UploadStatus.FAILED
                    else                  -> UploadStatus.PENDING
                }

                RecordingItem(
                    file         = file,
                    report       = report,
                    health       = computeHealth(file, report),
                    uploadStatus = uploadStatus
                )
            }
        }

        fun computeHealth(file: File, report: RecordingDebugReport?): HealthStatus {
            if (report == null) return HealthStatus.UNKNOWN
            val durationSec = report.durationMillis / 1000L
            val sizeOk = report.outputFileSizeBytes > 30_000L || durationSec <= 5
            val goodSources = setOf("DUAL_CHANNEL", "VOICE_CALL", "VOICE_DOWNLINK")
            return when {
                !sizeOk && durationSec > 10              -> HealthStatus.SILENT_RISK
                report.audioSourceUsed in goodSources && sizeOk -> HealthStatus.GOOD
                report.hadTelephonyOffhookBeforeStart == true && sizeOk -> HealthStatus.GOOD
                sizeOk                                   -> HealthStatus.PARTIAL
                else                                     -> HealthStatus.SILENT_RISK
            }
        }
    }
}
