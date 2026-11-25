package com.example.loopplayer

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

class MainActivity : AppCompatActivity() {

    private lateinit var player: ExoPlayer
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val handler = Handler(Looper.getMainLooper())
    private var userSeeking = false

    private lateinit var title: TextView
    private lateinit var seekBar: LoopSeekBar
    private lateinit var timeNow: TextView
    private lateinit var timeTotal: TextView
    private lateinit var playPause: ImageButton
    private lateinit var loopMode: Button
    private lateinit var abInfo: TextView
    private lateinit var loopCounter: TextView

    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) { /* not persistable; fine for this session */ }
            open(uri)
        }
    }

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = refreshPlayButton()
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = refreshTitle()
        override fun onPlaybackStateChanged(playbackState: Int) = refreshAll()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        player = LoopPlayer.get(this)

        title = findViewById(R.id.trackTitle)
        seekBar = findViewById(R.id.seekBar)
        timeNow = findViewById(R.id.timeNow)
        timeTotal = findViewById(R.id.timeTotal)
        playPause = findViewById(R.id.btnPlayPause)
        loopMode = findViewById(R.id.btnLoopMode)
        abInfo = findViewById(R.id.abInfo)
        loopCounter = findViewById(R.id.loopCounter)

        findViewById<Button>(R.id.btnOpen).setOnClickListener {
            pickAudio.launch(arrayOf("audio/mpeg", "audio/*"))
        }
        playPause.setOnClickListener {
            if (player.mediaItemCount == 0) {
                pickAudio.launch(arrayOf("audio/mpeg", "audio/*"))
            } else if (player.isPlaying) {
                player.pause()
            } else {
                if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                player.play()
            }
        }
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { seekBy(-5_000) }
        findViewById<ImageButton>(R.id.btnForward).setOnClickListener { seekBy(5_000) }

        loopMode.setOnClickListener { LoopPlayer.cycleMode(); refreshLoopUi() }
        findViewById<Button>(R.id.btnSetA).setOnClickListener {
            if (!LoopPlayer.setA()) toast(getString(R.string.a_after_b))
            refreshLoopUi()
        }
        findViewById<Button>(R.id.btnSetB).setOnClickListener {
            if (!LoopPlayer.setB()) toast(getString(R.string.b_before_a))
            refreshLoopUi()
        }
        findViewById<Button>(R.id.btnClear).setOnClickListener { LoopPlayer.clearPoints(); refreshLoopUi() }
        findViewById<Button>(R.id.btnAMinus).setOnClickListener { nudge(true, -100) }
        findViewById<Button>(R.id.btnAPlus).setOnClickListener { nudge(true, 100) }
        findViewById<Button>(R.id.btnBMinus).setOnClickListener { nudge(false, -100) }
        findViewById<Button>(R.id.btnBPlus).setOnClickListener { nudge(false, 100) }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) timeNow.text = formatTime(progress.toLong())
            }
            override fun onStartTrackingTouch(sb: SeekBar) { userSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                userSeeking = false
                player.seekTo(sb.progress.toLong())
            }
        })

        player.addListener(playerListener)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (savedInstanceState == null) handleViewIntent(intent)
        refreshAll()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        // Connecting a controller starts the background service (notification + screen-off playback).
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        handler.post(ticker)
    }

    override fun onStop() {
        handler.removeCallbacks(ticker)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    override fun onDestroy() {
        player.removeListener(playerListener)
        super.onDestroy()
    }

    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) intent.data?.let { open(it) }
    }

    private fun open(uri: Uri) {
        LoopPlayer.load(this, uri, displayName(uri))
        refreshAll()
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getString(0)
            }
        } catch (e: Exception) { /* fall back to the URI name */ }
        return uri.lastPathSegment ?: getString(R.string.unknown_track)
    }

    private fun seekBy(deltaMs: Long) {
        if (player.mediaItemCount == 0) return
        val dur = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + deltaMs).coerceIn(0, dur))
    }

    private fun nudge(isA: Boolean, deltaMs: Long) {
        LoopPlayer.nudge(isA, deltaMs)
        refreshLoopUi()
    }

    // Updates the progress bar ~10 times a second while the screen is visible.
    private val ticker = object : Runnable {
        override fun run() {
            refreshProgress()
            loopCounter.text = if (LoopPlayer.mode != LoopPlayer.Mode.OFF)
                getString(R.string.loop_count, LoopPlayer.loopCount) else ""
            handler.postDelayed(this, 100)
        }
    }

    private fun refreshAll() {
        refreshTitle()
        refreshPlayButton()
        refreshProgress()
        refreshLoopUi()
    }

    private fun refreshTitle() {
        title.text = player.mediaMetadata.title ?: getString(R.string.no_track)
    }

    private fun refreshPlayButton() {
        playPause.setImageResource(
            if (player.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
    }

    private fun refreshProgress() {
        val dur = player.duration
        if (dur > 0) {
            if (seekBar.max != dur.toInt()) {
                seekBar.max = dur.toInt()
                refreshLoopUi()
            }
            timeTotal.text = formatTime(dur)
        } else {
            seekBar.max = 0
            timeTotal.text = formatTime(0)
        }
        if (!userSeeking) {
            seekBar.progress = player.currentPosition.toInt()
            timeNow.text = formatTime(player.currentPosition)
        }
    }

    private fun refreshLoopUi() {
        loopMode.text = getString(
            when (LoopPlayer.mode) {
                LoopPlayer.Mode.OFF -> R.string.loop_off
                LoopPlayer.Mode.TRACK -> R.string.loop_track
                LoopPlayer.Mode.AB -> R.string.loop_ab
            }
        )
        val a = LoopPlayer.pointA
        val b = LoopPlayer.pointB
        abInfo.text = getString(
            R.string.ab_info,
            if (a >= 0) formatTime(a) else "--",
            if (b >= 0) formatTime(b) else "--"
        )
        seekBar.loopStart = a.toInt()
        seekBar.loopEnd = b.toInt()
    }

    private fun formatTime(ms: Long): String {
        val totalTenths = ms.coerceAtLeast(0) / 100
        val tenths = totalTenths % 10
        val totalSec = totalTenths / 10
        return "%d:%02d.%d".format(totalSec / 60, totalSec % 60, tenths)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
