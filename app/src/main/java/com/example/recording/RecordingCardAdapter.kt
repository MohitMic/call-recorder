package com.example.recording

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.recording.model.RecordingDebugReport
import com.google.android.material.button.MaterialButton
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── Data types ────────────────────────────────────────────────────────────────

enum class HealthStatus { GOOD, PARTIAL, SILENT_RISK, UNKNOWN }

data class RecordingItem(
    val file: File,
    val report: RecordingDebugReport?,
    val health: HealthStatus
)

// ── Adapter ───────────────────────────────────────────────────────────────────

class RecordingCardAdapter(
    private val onPlayClick: (RecordingItem) -> Unit,
    private val onDebugClick: (RecordingItem) -> Unit
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
        private val iconContainer  = view.findViewById<View>(R.id.iconContainer)
        private val iconDirection  = view.findViewById<android.widget.ImageView>(R.id.iconDirection)
        private val textNumber     = view.findViewById<TextView>(R.id.textNumber)
        private val textHealthBadge = view.findViewById<TextView>(R.id.textHealthBadge)
        private val textDate       = view.findViewById<TextView>(R.id.textDate)
        private val textCallInfo   = view.findViewById<TextView>(R.id.textCallInfo)
        private val textSourceBadge = view.findViewById<TextView>(R.id.textSourceBadge)
        private val btnPlay        = view.findViewById<MaterialButton>(R.id.btnPlay)
        private val btnDebug       = view.findViewById<MaterialButton>(R.id.btnDebug)

        fun bind(item: RecordingItem) {
            val ctx = itemView.context
            val r = item.report

            // ── Number ──────────────────────────────────────────────────────
            val rawName = item.file.nameWithoutExtension
            val number = if (r != null) r.numberLabel else parseNumberFromFilename(rawName)
            textNumber.text = number.ifBlank { "Unknown number" }

            // ── Date ────────────────────────────────────────────────────────
            textDate.text = formatDate(item.file.lastModified())

            // ── Call info ───────────────────────────────────────────────────
            val direction = when {
                r?.direction == "INCOMING" -> "↓ Incoming"
                r?.direction == "OUTGOING" -> "↑ Outgoing"
                rawName.startsWith("INCOMING") -> "↓ Incoming"
                rawName.startsWith("OUTGOING") -> "↑ Outgoing"
                else -> "?"
            }
            val duration = if (r != null) formatMs(r.durationMillis) else "—"
            val size = formatBytes(item.file.length())
            textCallInfo.text = "$direction  ·  $duration  ·  $size"

            // ── Direction icon ───────────────────────────────────────────────
            val isOutgoing = r?.direction == "OUTGOING" || rawName.startsWith("OUTGOING")
            iconDirection.setImageResource(
                if (isOutgoing) R.drawable.ic_call_outgoing else R.drawable.ic_call_incoming
            )

            // ── Health badge + icon circle colour ────────────────────────────
            applyHealth(ctx, item.health)

            // ── Source badge ────────────────────────────────────────────────
            val source = r?.audioSourceUsed ?: "?"
            applySourceBadge(ctx, source)

            // ── Buttons ──────────────────────────────────────────────────────
            btnPlay.setOnClickListener { onPlayClick(item) }
            btnDebug.setOnClickListener { onDebugClick(item) }
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

            // Coloured circle behind icon
            val circle = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ContextCompat.getColor(ctx, circleColor))
            }
            iconContainer.background = circle

            // Health text badge
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
                source.contains("DUAL") -> Pair(R.color.badge_dual_bg, R.color.badge_dual)
                source.contains("VOICE_CALL") || source.contains("DOWNLINK") ->
                    Pair(R.color.badge_dual_bg, R.color.badge_dual)
                source.contains("VOICE_COMM") || source.contains("VOICE_RECOG") ->
                    Pair(R.color.badge_voice_bg, R.color.badge_voice)
                else -> Pair(R.color.badge_mic_bg, R.color.badge_mic)
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

        private fun parseNumberFromFilename(name: String): String {
            val parts = name.split("__")
            return if (parts.size >= 2) parts[1].split("_").firstOrNull() ?: "" else ""
        }

        private fun formatDate(millis: Long): String {
            val sdf = SimpleDateFormat("dd MMM · HH:mm", Locale.getDefault())
            return sdf.format(Date(millis))
        }

        private fun formatMs(ms: Long): String {
            val totalSec = ms / 1000
            val m = totalSec / 60
            val s = totalSec % 60
            return String.format(Locale.US, "%d:%02d", m, s)
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
                a.file.lastModified() == b.file.lastModified() && a.health == b.health
        }

        private val json = Json { ignoreUnknownKeys = true }

        fun loadItems(context: Context): List<RecordingItem> {
            val dir = File(context.getExternalFilesDir(null), "CallRecordings")
            val files = dir.listFiles { f -> f.isFile && f.extension.equals("mp4", ignoreCase = true) }
                ?.sortedByDescending { it.lastModified() }
                ?.toList() ?: return emptyList()

            return files.map { mp4 ->
                val sidecar = File(mp4.parentFile, "${mp4.nameWithoutExtension}_debug.json")
                val report = if (sidecar.exists()) {
                    runCatching { json.decodeFromString<RecordingDebugReport>(sidecar.readText()) }.getOrNull()
                } else null
                RecordingItem(file = mp4, report = report, health = computeHealth(mp4, report))
            }
        }

        fun computeHealth(file: File, report: RecordingDebugReport?): HealthStatus {
            if (report == null) return HealthStatus.UNKNOWN
            val durationSec = report.durationMillis / 1000L
            val sizeOk = report.outputFileSizeBytes > 30_000L || durationSec <= 5
            val goodSources = setOf("DUAL_CHANNEL", "VOICE_CALL", "VOICE_DOWNLINK")
            return when {
                !sizeOk && durationSec > 10  -> HealthStatus.SILENT_RISK
                report.audioSourceUsed in goodSources && sizeOk -> HealthStatus.GOOD
                report.hadTelephonyOffhookBeforeStart == true && sizeOk -> HealthStatus.GOOD
                sizeOk -> HealthStatus.PARTIAL
                else   -> HealthStatus.SILENT_RISK
            }
        }
    }
}
