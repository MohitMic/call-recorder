package com.example.recording

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.recording.AppPrefs.DeviceBrand
import com.example.recording.model.RecordingDebugReport
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── Data types ────────────────────────────────────────────────────────────────

enum class HealthStatus { GOOD, PARTIAL, SILENT_RISK, UNKNOWN }

enum class UploadStatus { UPLOADED, PENDING, FAILED }

data class RecordingItem(
    val file: File,
    val report: RecordingDebugReport?,
    val health: HealthStatus,
    val uploadStatus: UploadStatus,
    val callerName: String,      // resolved contact name or raw number
    val direction: String        // "INCOMING", "OUTGOING", or "UNKNOWN"
)

data class FolderInfo(val path: String, val exists: Boolean, val fileCount: Int)

data class ScanResult(
    val items: List<RecordingItem>,
    val folders: List<FolderInfo>,
    val brandLabel: String
)

// ── Adapter ───────────────────────────────────────────────────────────────────

class RecordingCardAdapter(
    private val onPlayClick: (RecordingItem) -> Unit
) : ListAdapter<RecordingItem, RecordingCardAdapter.ViewHolder>(DIFF) {

    // For filter support — store the full list separately from the displayed list
    private var fullList: List<RecordingItem> = emptyList()
    private var filterDirection: String = "ALL"
    private var searchQuery: String = ""

    fun submitFull(items: List<RecordingItem>) {
        fullList = items
        applyFilters()
    }

    fun setDirectionFilter(dir: String) {   // "ALL", "INCOMING", "OUTGOING"
        filterDirection = dir
        applyFilters()
    }

    fun setSearchQuery(query: String) {
        searchQuery = query.trim().lowercase()
        applyFilters()
    }

    private fun applyFilters() {
        var result = fullList
        if (filterDirection != "ALL") {
            result = result.filter { it.direction == filterDirection }
        }
        if (searchQuery.isNotEmpty()) {
            result = result.filter {
                it.callerName.lowercase().contains(searchQuery) ||
                it.file.name.lowercase().contains(searchQuery)
            }
        }
        submitList(result)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recording_card, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position))

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val textInitials     = view.findViewById<TextView>(R.id.textAvatarInitials)
        private val textCallerName   = view.findViewById<TextView>(R.id.textCallerName)
        private val textDirPill      = view.findViewById<TextView>(R.id.textDirectionPill)
        private val textDurationDate = view.findViewById<TextView>(R.id.textDurationDate)
        private val textUpload       = view.findViewById<TextView>(R.id.textUploadStatus)
        private val btnPlay          = view.findViewById<View>(R.id.btnPlay)

        fun bind(item: RecordingItem) {
            val ctx = itemView.context

            // ── Caller name & avatar ─────────────────────────────────────────
            val displayName = item.callerName.ifBlank { "Unknown" }
            textCallerName.text = displayName
            textInitials.text   = ContactLookup.initials(displayName)
            setAvatarColor(ctx, displayName)

            // ── Direction pill ───────────────────────────────────────────────
            val (pillLabel, pillColorRes) = when (item.direction) {
                "INCOMING" -> "INCOMING" to R.color.incoming_color
                "OUTGOING" -> "OUTGOING" to R.color.outgoing_color
                else       -> "UNKNOWN"  to R.color.health_unknown
            }
            textDirPill.text = pillLabel
            textDirPill.background = pillDrawable(
                ctx, ContextCompat.getColor(ctx, pillColorRes)
            )

            // ── Duration + date ──────────────────────────────────────────────
            val ts        = item.report?.startedAtMillis ?: item.file.lastModified()
            val durationMs = item.report?.durationMillis ?: 0L
            textDurationDate.text = if (durationMs > 0) {
                "${formatMs(durationMs)}  ·  ${formatDate(ts)}"
            } else {
                formatDate(ts)
            }

            // ── Upload status ────────────────────────────────────────────────
            val (uploadText, uploadColor) = when (item.uploadStatus) {
                UploadStatus.UPLOADED -> "Uploaded ✓"            to "#4CAF50"
                UploadStatus.PENDING  -> "Upload pending"         to "#FF9800"
                UploadStatus.FAILED   -> "Upload failed — retrying" to "#EF5350"
            }
            textUpload.text = uploadText
            textUpload.setTextColor(Color.parseColor(uploadColor))

            // ── Play button ──────────────────────────────────────────────────
            btnPlay.setOnClickListener { onPlayClick(item) }
        }

        private fun setAvatarColor(ctx: Context, name: String) {
            val avatarColors = intArrayOf(
                R.color.avatar_1, R.color.avatar_2, R.color.avatar_3,
                R.color.avatar_4, R.color.avatar_5
            )
            val idx = (name.hashCode() and 0x7FFFFFFF) % avatarColors.size
            val circle = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ContextCompat.getColor(ctx, avatarColors[idx]))
            }
            // Set the color on the TextView itself (it fills the FrameLayout)
            textInitials.background = circle
        }

        private fun pillDrawable(ctx: Context, color: Int): GradientDrawable {
            // Semi-transparent pill: 30% alpha on the fill, full-color text
            val fill = Color.argb((0.25 * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))
            return GradientDrawable().apply {
                shape       = GradientDrawable.RECTANGLE
                cornerRadius = 32f
                setColor(fill)
            }
        }

        private fun formatDate(millis: Long): String =
            SimpleDateFormat("dd MMM  HH:mm", Locale.getDefault()).format(Date(millis))

        private fun formatMs(ms: Long): String {
            val s = ms / 1000
            return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<RecordingItem>() {
            override fun areItemsTheSame(a: RecordingItem, b: RecordingItem) =
                a.file.absolutePath == b.file.absolutePath
            override fun areContentsTheSame(a: RecordingItem, b: RecordingItem) =
                a.file.lastModified() == b.file.lastModified() &&
                a.uploadStatus == b.uploadStatus
        }

        private val json = Json { ignoreUnknownKeys = true }

        private val AUDIO_EXTENSIONS = setOf("mp4", "mp3", "m4a", "amr", "aac", "wav", "ogg", "3gp")

        fun scan(context: Context): ScanResult {
            val allFiles = mutableListOf<File>()
            val folderInfos = mutableListOf<FolderInfo>()

            // App-internal recordings folder
            val appDir = File(context.getExternalFilesDir(null), "CallRecordings")
            val appFiles = appDir.listFiles { f -> f.isFile && f.extension.lowercase() in AUDIO_EXTENSIONS }
            appFiles?.let { allFiles.addAll(it) }
            folderInfos.add(
                FolderInfo(appDir.absolutePath, appDir.exists(), appFiles?.size ?: 0)
            )

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
                val exists = dir.exists()
                val files = if (exists) {
                    dir.listFiles { f ->
                        f.isFile && f.extension.lowercase() in AUDIO_EXTENSIONS
                    }
                } else null
                files?.let { allFiles.addAll(it) }
                folderInfos.add(FolderInfo(path, exists, files?.size ?: 0))
            }

            val items = buildItems(context, allFiles)
            return ScanResult(items, folderInfos, brand.label)
        }

        fun loadItems(context: Context): List<RecordingItem> = scan(context).items

        private fun buildItems(context: Context, allFiles: List<File>): List<RecordingItem> {

            if (allFiles.isEmpty()) return emptyList()

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

                val rawName = file.nameWithoutExtension
                val direction = when {
                    report?.direction == "INCOMING"   -> "INCOMING"
                    report?.direction == "OUTGOING"   -> "OUTGOING"
                    rawName.startsWith("INCOMING", ignoreCase = true) -> "INCOMING"
                    rawName.startsWith("OUTGOING", ignoreCase = true) -> "OUTGOING"
                    else -> "UNKNOWN"
                }

                // Resolve phone number to contact name
                val rawNumber = report?.numberLabel?.ifBlank { null }
                    ?: parseNumberFromFilename(rawName)
                val callerName = if (rawNumber != null) {
                    ContactLookup.getDisplayName(context, rawNumber)
                } else {
                    ""
                }

                RecordingItem(
                    file         = file,
                    report       = report,
                    health       = computeHealth(file, report),
                    uploadStatus = uploadStatus,
                    callerName   = callerName,
                    direction    = direction
                )
            }
        }

        private fun parseNumberFromFilename(name: String): String? {
            val parts = name.split("__")
            return if (parts.size >= 2) parts[1].split("_").firstOrNull() else null
        }

        fun computeHealth(file: File, report: RecordingDebugReport?): HealthStatus {
            if (report == null) return HealthStatus.UNKNOWN
            val durationSec = report.durationMillis / 1000L
            val sizeOk = report.outputFileSizeBytes > 30_000L || durationSec <= 5
            val goodSources = setOf("DUAL_CHANNEL", "VOICE_CALL", "VOICE_DOWNLINK")
            return when {
                !sizeOk && durationSec > 10                          -> HealthStatus.SILENT_RISK
                report.audioSourceUsed in goodSources && sizeOk     -> HealthStatus.GOOD
                report.hadTelephonyOffhookBeforeStart == true && sizeOk -> HealthStatus.GOOD
                sizeOk                                               -> HealthStatus.PARTIAL
                else                                                 -> HealthStatus.SILENT_RISK
            }
        }
    }
}
