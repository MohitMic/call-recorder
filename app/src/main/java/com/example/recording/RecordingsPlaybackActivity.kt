package com.example.recording

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

class RecordingsPlaybackActivity : AppCompatActivity() {

    private lateinit var textAvatar: TextView
    private lateinit var textNowPlaying: TextView
    private lateinit var textSub: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var textCurrentTime: TextView
    private lateinit var textTotalTime: TextView
    private lateinit var btnPlayPause: FrameLayout
    private lateinit var iconPlayPause: ImageView
    private lateinit var listView: ListView

    private val handler = Handler(Looper.getMainLooper())
    private var seekTick: Runnable? = null
    private var mediaPlayer: MediaPlayer? = null
    private var userSeeking = false
    private var currentFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recordings_playback)

        textAvatar       = findViewById(R.id.textPlaybackAvatar)
        textNowPlaying   = findViewById(R.id.playbackNowPlaying)
        textSub          = findViewById(R.id.textPlaybackSub)
        seekBar          = findViewById(R.id.playbackSeek)
        textCurrentTime  = findViewById(R.id.textPlaybackCurrentTime)
        textTotalTime    = findViewById(R.id.textPlaybackTotalTime)
        btnPlayPause     = findViewById(R.id.playbackPlayPause)
        iconPlayPause    = findViewById(R.id.iconPlayPause)
        listView         = findViewById(R.id.playbackList)

        // Back button
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        val files = listRecordingFiles()
        if (files.isEmpty()) {
            textNowPlaying.text = getString(R.string.no_recordings_found)
            listView.visibility = View.GONE
            return
        }

        // List adapter with dark background items
        val labels = files.map { it.name }
        listView.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            labels
        )
        listView.setOnItemClickListener { _, _, position, _ ->
            playFile(files[position])
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    mediaPlayer?.seekTo(progress)
                    textCurrentTime.text = formatDuration(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { userSeeking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) { userSeeking = false }
        })

        btnPlayPause.setOnClickListener {
            val mp = mediaPlayer ?: return@setOnClickListener
            if (mp.isPlaying) {
                mp.pause()
                iconPlayPause.setImageResource(R.drawable.ic_play)
                stopSeekUpdates()
            } else {
                mp.start()
                iconPlayPause.setImageResource(R.drawable.ic_pause)
                startSeekUpdates()
            }
        }

        // If launched with a specific file path, auto-play it
        val preSelectedPath = intent.getStringExtra(EXTRA_FILE_PATH)
        if (preSelectedPath != null) {
            val target = files.firstOrNull { it.absolutePath == preSelectedPath }
            if (target != null) {
                val idx = files.indexOf(target)
                listView.smoothScrollToPosition(idx)
                playFile(target)
            }
        }
    }

    override fun onStop()    { super.onStop();    stopPlayback() }
    override fun onDestroy() { stopPlayback(); super.onDestroy() }

    // ── Playback logic ────────────────────────────────────────────────────────

    private fun playFile(file: File) {
        stopPlayback()
        currentFile = file
        textNowPlaying.text = "Loading…"
        btnPlayPause.isEnabled = false

        thread {
            try {
                val mp = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    setDataSource(file.absolutePath)
                    prepare()
                }
                runOnUiThread {
                    mediaPlayer = mp

                    // Resolve contact name for the avatar
                    val rawName = file.nameWithoutExtension
                    val number  = parseNumber(rawName) ?: ""
                    val displayName = if (number.isNotBlank())
                        ContactLookup.getDisplayName(this, number)
                    else
                        rawName

                    bindNowPlaying(displayName, file, mp.duration)

                    mp.setOnCompletionListener {
                        iconPlayPause.setImageResource(R.drawable.ic_play)
                        stopSeekUpdates()
                        seekBar.progress = 0
                        textCurrentTime.text = "0:00"
                    }
                    mp.setOnErrorListener { _, what, extra ->
                        Toast.makeText(this, "Playback error ($what/$extra)", Toast.LENGTH_LONG).show()
                        true
                    }

                    btnPlayPause.isEnabled = true
                    mp.start()
                    iconPlayPause.setImageResource(R.drawable.ic_pause)
                    startSeekUpdates()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    textNowPlaying.text = "Could not open file: ${e.message}"
                    Toast.makeText(this, textNowPlaying.text, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun bindNowPlaying(displayName: String, file: File, durationMs: Int) {
        // Avatar initials + color
        val initials = ContactLookup.initials(displayName)
        textAvatar.text = initials
        val avatarColors = intArrayOf(
            R.color.avatar_1, R.color.avatar_2, R.color.avatar_3,
            R.color.avatar_4, R.color.avatar_5
        )
        val idx = (displayName.hashCode() and 0x7FFFFFFF) % avatarColors.size
        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ContextCompat.getColor(this@RecordingsPlaybackActivity, avatarColors[idx]))
        }
        textAvatar.background = circle

        textNowPlaying.text = displayName.ifBlank { file.nameWithoutExtension }

        // Sub text: direction + duration
        val raw = file.nameWithoutExtension
        val direction = when {
            raw.startsWith("INCOMING", ignoreCase = true) -> "Incoming"
            raw.startsWith("OUTGOING", ignoreCase = true) -> "Outgoing"
            else -> ""
        }
        textSub.text = buildString {
            if (direction.isNotEmpty()) append(direction)
            if (direction.isNotEmpty() && durationMs > 0) append("  ·  ")
            if (durationMs > 0) append(formatDuration(durationMs))
        }

        // Seek bar
        seekBar.max      = durationMs.coerceAtLeast(0)
        seekBar.progress = 0
        seekBar.isEnabled = durationMs > 0
        textCurrentTime.text = "0:00"
        textTotalTime.text   = formatDuration(durationMs)
    }

    private fun startSeekUpdates() {
        stopSeekUpdates()
        val tick = object : Runnable {
            override fun run() {
                val mp = mediaPlayer
                if (mp != null && mp.isPlaying && !userSeeking) {
                    val pos = mp.currentPosition
                    seekBar.progress    = pos
                    textCurrentTime.text = formatDuration(pos)
                }
                handler.postDelayed(this, 500L)
            }
        }
        seekTick = tick
        handler.post(tick)
    }

    private fun stopSeekUpdates() {
        seekTick?.let { handler.removeCallbacks(it) }
        seekTick = null
    }

    private fun stopPlayback() {
        stopSeekUpdates()
        runCatching { mediaPlayer?.stop(); mediaPlayer?.release() }
        mediaPlayer = null
        if (::btnPlayPause.isInitialized) {
            btnPlayPause.isEnabled = false
            iconPlayPause.setImageResource(R.drawable.ic_play)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun listRecordingFiles(): List<File> {
        val allFiles = mutableListOf<File>()

        // App internal
        val appDir = File(getExternalFilesDir(null), "CallRecordings")
        appDir.listFiles { f -> f.isFile }?.let { allFiles.addAll(it) }

        // OEM brand folders
        val brand = AppPrefs.getDeviceBrand(this)
        val oemPaths = if (brand == AppPrefs.DeviceBrand.CUSTOM) {
            val c = AppPrefs.getCustomFolder(this)
            if (c.isBlank()) emptyList() else listOf(c)
        } else {
            brand.folders
        }
        val audioExts = setOf("mp4", "mp3", "m4a", "amr", "aac", "wav", "ogg", "3gp")
        oemPaths.forEach { path ->
            val dir = File(path)
            if (dir.exists()) {
                dir.listFiles { f -> f.isFile && f.extension.lowercase() in audioExts }
                    ?.let { allFiles.addAll(it) }
            }
        }

        return allFiles
            .distinctBy { it.absolutePath }
            .sortedByDescending { it.lastModified() }
    }

    private fun parseNumber(rawName: String): String? {
        val parts = rawName.split("__")
        return if (parts.size >= 2) parts[1].split("_").firstOrNull() else null
    }

    private fun formatDuration(ms: Int): String {
        val totalSec = ms / 1000
        return String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60)
    }

    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"
    }
}
