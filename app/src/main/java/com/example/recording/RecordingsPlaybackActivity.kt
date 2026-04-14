package com.example.recording

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

class RecordingsPlaybackActivity : AppCompatActivity() {

    private lateinit var nowPlaying: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var playPause: Button
    private lateinit var stopBtn: Button
    private lateinit var listView: ListView

    private val handler = Handler(Looper.getMainLooper())
    private var seekTick: Runnable? = null

    private var mediaPlayer: MediaPlayer? = null
    private var userSeeking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recordings_playback)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.playback_title)

        nowPlaying = findViewById(R.id.playbackNowPlaying)
        seekBar    = findViewById(R.id.playbackSeek)
        playPause  = findViewById(R.id.playbackPlayPause)
        stopBtn    = findViewById(R.id.playbackStop)
        listView   = findViewById(R.id.playbackList)

        val files = listRecordingFiles()
        if (files.isEmpty()) {
            nowPlaying.text = getString(R.string.no_recordings_found)
            listView.visibility = View.GONE
            return
        }

        val labels = files.map { it.name }
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        listView.setOnItemClickListener { _, _, position, _ -> playFile(files[position]) }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) mediaPlayer?.seekTo(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { userSeeking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) { userSeeking = false }
        })

        playPause.setOnClickListener {
            val mp = mediaPlayer ?: return@setOnClickListener
            if (mp.isPlaying) {
                mp.pause()
                playPause.text = getString(R.string.playback_play)
                stopSeekUpdates()
            } else {
                mp.start()
                playPause.text = getString(R.string.playback_pause)
                startSeekUpdates()
            }
        }

        stopBtn.setOnClickListener { stopPlayback(resetUi = true) }

        // If launched from RecordingsFragment with a specific file, auto-play it
        val preSelectedPath = intent.getStringExtra(EXTRA_FILE_PATH)
        if (preSelectedPath != null) {
            val targetFile = files.firstOrNull { it.absolutePath == preSelectedPath }
            if (targetFile != null) {
                val idx = files.indexOf(targetFile)
                listView.setItemChecked(idx, true)
                listView.smoothScrollToPosition(idx)
                playFile(targetFile)
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
    override fun onStop()    { super.onStop();    stopPlayback(resetUi = true) }
    override fun onDestroy() { stopPlayback(resetUi = false); super.onDestroy() }

    private fun listRecordingFiles(): List<File> {
        val dir = File(getExternalFilesDir(null), "CallRecordings")
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isFile && f.extension.equals("mp4", true) }
            ?.sortedByDescending { it.lastModified() }.orEmpty()
    }

    private fun playFile(file: File) {
        stopPlayback(resetUi = false)
        nowPlaying.text = getString(R.string.playback_loading, file.name)
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
                    mp.setOnCompletionListener {
                        playPause.text = getString(R.string.playback_play)
                        stopSeekUpdates()
                        seekBar.progress = 0
                    }
                    mp.setOnErrorListener { _, what, extra ->
                        Toast.makeText(this, getString(R.string.playback_error, what, extra), Toast.LENGTH_LONG).show()
                        true
                    }
                    val duration = mp.duration.coerceAtLeast(0)
                    seekBar.max      = duration
                    seekBar.progress = 0
                    seekBar.isEnabled  = duration > 0
                    playPause.isEnabled = true
                    stopBtn.isEnabled   = true
                    playPause.text = getString(R.string.playback_pause)
                    nowPlaying.text = getString(R.string.playback_now, file.name, formatDuration(duration))
                    mp.start()
                    startSeekUpdates()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    nowPlaying.text = getString(R.string.playback_open_failed, e.message ?: e.toString())
                    Toast.makeText(this, nowPlaying.text, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startSeekUpdates() {
        stopSeekUpdates()
        val tick = object : Runnable {
            override fun run() {
                val mp = mediaPlayer
                if (mp != null && mp.isPlaying && !userSeeking) seekBar.progress = mp.currentPosition
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

    private fun stopPlayback(resetUi: Boolean) {
        stopSeekUpdates()
        runCatching { mediaPlayer?.stop(); mediaPlayer?.release() }
        mediaPlayer = null
        if (resetUi) {
            seekBar.progress = 0
            seekBar.isEnabled   = false
            playPause.isEnabled = false
            stopBtn.isEnabled   = false
            playPause.text = getString(R.string.playback_play)
            nowPlaying.text = getString(R.string.playback_none)
        }
    }

    private fun formatDuration(ms: Int): String {
        val totalSec = ms / 1000
        return String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60)
    }

    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"
    }
}
