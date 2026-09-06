package com.mlg.player

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.audiofx.Visualizer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var visualizerView: VisualizerView
    private lateinit var listPlaylist: ListView
    private lateinit var seekBar: SeekBar
    private lateinit var txtTime: TextView
    private lateinit var txtTitle: TextView
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnStop: ImageButton
    private lateinit var btnPrev: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var btnShuffle: ImageButton
    private lateinit var btnLoop: Button
    private lateinit var btnPickFolder: Button

    private var mediaPlayer: MediaPlayer? = null
    private var audioVisualizer: Visualizer? = null

    private val tracks = mutableListOf<DocumentFile>()
    private var playOrder = mutableListOf<Int>()
    private var currentIndex = -1

    private var shuffleOn = false
    // 0 = off, 1 = loop all, 2 = loop track
    private var loopMode = 0

    private val handler = Handler(Looper.getMainLooper())
    private var seeking = false

    private val openFolderLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                loadFolder(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        setContentView(R.layout.activity_main)

        visualizerView = findViewById(R.id.visualizerView)
        listPlaylist = findViewById(R.id.listPlaylist)
        seekBar = findViewById(R.id.seekBar)
        txtTime = findViewById(R.id.txtTime)
        txtTitle = findViewById(R.id.txtTrackTitle)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnStop = findViewById(R.id.btnStop)
        btnPrev = findViewById(R.id.btnPrev)
        btnNext = findViewById(R.id.btnNext)
        btnShuffle = findViewById(R.id.btnShuffle)
        btnLoop = findViewById(R.id.btnLoop)
        btnPickFolder = findViewById(R.id.btnPickFolder)

        requestAudioPermission()

        btnPickFolder.setOnClickListener { openFolderLauncher.launch(null) }

        listPlaylist.setOnItemClickListener { _, _, position, _ ->
            currentIndex = playOrder.indexOf(position).let { if (it == -1) position else position }
            playTrackAt(position)
        }

        btnPlayPause.setOnClickListener { togglePlayPause() }
        btnStop.setOnClickListener { stopPlayback() }
        btnPrev.setOnClickListener { playPrev() }
        btnNext.setOnClickListener { playNext(userSkipped = true) }
        btnShuffle.setOnClickListener {
            shuffleOn = !shuffleOn
            rebuildPlayOrder()
            btnShuffle.alpha = if (shuffleOn) 1f else 0.4f
            Toast.makeText(this, "Shuffle: ${if (shuffleOn) "ON" else "OFF"}", Toast.LENGTH_SHORT).show()
        }
        btnLoop.setOnClickListener {
            loopMode = (loopMode + 1) % 3
            btnLoop.text = when (loopMode) {
                1 -> "LOOP: ALL"
                2 -> "LOOP: TRACK"
                else -> "LOOP: OFF"
            }
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(sb: SeekBar?) { seeking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                mediaPlayer?.seekTo(sb?.progress ?: 0)
                seeking = false
            }
        })

        btnShuffle.alpha = 0.4f
        startProgressLoop()
    }

    private fun requestAudioPermission() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) needed.add(Manifest.permission.RECORD_AUDIO)
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1001)
        }
    }

    private fun loadFolder(treeUri: Uri) {
        tracks.clear()
        val root = DocumentFile.fromTreeUri(this, treeUri) ?: return
        val audioFiles = root.listFiles().filter {
            it.isFile && (it.type?.startsWith("audio/") == true ||
                it.name?.lowercase(Locale.ROOT)?.let { n ->
                    n.endsWith(".mp3") || n.endsWith(".wav") || n.endsWith(".flac") || n.endsWith(".ogg") || n.endsWith(".m4a")
                } == true)
        }
        tracks.addAll(audioFiles)

        val names = tracks.map { it.name ?: "unknown" }
        listPlaylist.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names).also {
            // make text visible on black background
        }
        (listPlaylist.adapter as ArrayAdapter<*>).let { }
        applyNeonListStyle(names)

        rebuildPlayOrder()
        if (tracks.isNotEmpty()) {
            currentIndex = 0
            playTrackAt(playOrder[0])
        }
    }

    private fun applyNeonListStyle(names: List<String>) {
        listPlaylist.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, names) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.setTextColor(android.graphics.Color.parseColor("#39FF14"))
                v.setBackgroundColor(android.graphics.Color.parseColor("#000000"))
                return v
            }
        }
    }

    private fun rebuildPlayOrder() {
        playOrder = if (shuffleOn) {
            tracks.indices.toMutableList().apply { shuffle() }
        } else {
            tracks.indices.toMutableList()
        }
    }

    private fun playTrackAt(index: Int) {
        if (index !in tracks.indices) return
        releasePlayer()

        val doc = tracks[index]
        mediaPlayer = MediaPlayer().apply {
            setDataSource(this@MainActivity, doc.uri)
            setOnPreparedListener {
                start()
                seekBar.max = duration
                setupVisualizerEffect(audioSessionId)
                btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
            }
            setOnCompletionListener {
                when (loopMode) {
                    2 -> playTrackAt(index) // loop same track
                    else -> playNext(userSkipped = false)
                }
            }
            prepareAsync()
        }
        currentIndex = index
        txtTitle.text = (doc.name ?: "MLG TRACK").uppercase(Locale.ROOT)
        visualizerView.triggerTrackChangeBurst()
    }

    private fun setupVisualizerEffect(sessionId: Int) {
        audioVisualizer?.release()
        try {
            audioVisualizer = Visualizer(sessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                        fft?.let { visualizerView.updateFft(it) }
                    }
                }, Visualizer.getMaxCaptureRate() / 2, false, true)
                enabled = true
            }
        } catch (e: Exception) {
            // some devices restrict Visualizer; fail silently, player still works
        }
    }

    private fun togglePlayPause() {
        val mp = mediaPlayer ?: run {
            if (tracks.isNotEmpty()) playTrackAt(playOrder.firstOrNull() ?: 0)
            return
        }
        if (mp.isPlaying) {
            mp.pause()
            btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
        } else {
            mp.start()
            btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
        }
    }

    private fun stopPlayback() {
        mediaPlayer?.stop()
        releasePlayer()
        btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
        seekBar.progress = 0
        txtTime.text = "00:00 / 00:00"
    }

    private fun playNext(userSkipped: Boolean) {
        if (tracks.isEmpty()) return
        val posInOrder = playOrder.indexOf(currentIndex)
        val nextPos = posInOrder + 1
        if (nextPos < playOrder.size) {
            playTrackAt(playOrder[nextPos])
        } else {
            if (loopMode == 1 || userSkipped) {
                rebuildPlayOrder()
                playTrackAt(playOrder.first())
            } else {
                stopPlayback()
            }
        }
    }

    private fun playPrev() {
        if (tracks.isEmpty()) return
        val posInOrder = playOrder.indexOf(currentIndex)
        val prevPos = posInOrder - 1
        if (prevPos >= 0) {
            playTrackAt(playOrder[prevPos])
        } else {
            playTrackAt(playOrder.last())
        }
    }

    private fun releasePlayer() {
        audioVisualizer?.release()
        audioVisualizer = null
        mediaPlayer?.setOnCompletionListener(null)
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun startProgressLoop() {
        handler.post(object : Runnable {
            override fun run() {
                mediaPlayer?.let { mp ->
                    if (!seeking) {
                        try {
                            seekBar.progress = mp.currentPosition
                            txtTime.text = "${formatTime(mp.currentPosition)} / ${formatTime(mp.duration)}"
                        } catch (_: IllegalStateException) {}
                    }
                }
                handler.postDelayed(this, 500)
            }
        })
    }

    private fun formatTime(ms: Int): String {
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return String.format(Locale.ROOT, "%02d:%02d", m, s)
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
    }
}
