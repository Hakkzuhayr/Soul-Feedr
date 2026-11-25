package com.example.loopplayer

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Typeface
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import java.io.File

class MainActivity : AppCompatActivity(), Downloader.Listener {

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
    private lateinit var urlInput: EditText
    private lateinit var btnDownload: Button
    private lateinit var btnCancelDownload: Button
    private lateinit var dlProgress: ProgressBar
    private lateinit var dlStatus: TextView
    private lateinit var trackList: LinearLayout
    private lateinit var libraryEmpty: TextView
    private lateinit var libraryHeader: TextView
    private var tracks: List<Downloader.Saved> = emptyList()

    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) { /* not persistable; fine for this session */ }
            open(uri)
        }
    }

    // Notifications (player controls) + reading music (so saved songs survive reinstalling)
    private val askPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refreshLibrary() }

    // Android asks "Allow Loop Player to delete this?" for songs saved by an older install
    private val deleteRequest =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { refreshLibrary() }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = refreshPlayButton()
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            refreshTitle()
            refreshHighlight()
        }
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
        urlInput = findViewById(R.id.urlInput)
        btnDownload = findViewById(R.id.btnDownload)
        btnCancelDownload = findViewById(R.id.btnCancelDownload)
        dlProgress = findViewById(R.id.dlProgress)
        dlStatus = findViewById(R.id.dlStatus)
        trackList = findViewById(R.id.trackList)
        libraryEmpty = findViewById(R.id.libraryEmpty)
        libraryHeader = findViewById(R.id.libraryHeader)

        btnDownload.setOnClickListener { startDownload(urlInput.text?.toString()) }
        urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { startDownload(urlInput.text?.toString()); true } else false
        }
        btnCancelDownload.setOnClickListener { Downloader.cancel() }

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

        val wanted = if (Build.VERSION.SDK_INT >= 33)
            listOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_MEDIA_AUDIO)
        else
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) askPermissions.launch(missing.toTypedArray())

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
        Downloader.listener = this
        onDownloadUpdate()
        refreshLibrary()
    }

    override fun onStop() {
        if (Downloader.listener === this) Downloader.listener = null
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
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data?.let { open(it) }
            // Shared from the YouTube app (Share → Loop Player)
            Intent.ACTION_SEND -> {
                val url = Downloader.extractUrl(intent.getStringExtra(Intent.EXTRA_TEXT))
                if (url != null) {
                    urlInput.setText(url)
                    startDownload(url)
                }
            }
        }
    }

    private fun open(uri: Uri, title: String = displayName(uri)) {
        LoopPlayer.load(this, uri, title)
        refreshAll()
    }

    // ---------------------------------------------------------------- downloader

    private fun startDownload(text: String?) {
        val url = Downloader.extractUrl(text)
        if (url == null) {
            toast(getString(R.string.dl_no_link))
            return
        }
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(urlInput.windowToken, 0)
        Downloader.start(this, url)
    }

    override fun onDownloadUpdate() {
        val running = Downloader.running
        btnDownload.isEnabled = !running
        btnCancelDownload.visibility = if (running) View.VISIBLE else View.GONE
        dlProgress.visibility = if (running) View.VISIBLE else View.GONE
        val p = Downloader.progress
        dlProgress.isIndeterminate = p < 0f
        if (p >= 0f) dlProgress.progress = p.toInt()
        dlStatus.text = Downloader.status
        dlStatus.visibility = if (Downloader.status.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onDownloadFinished(saved: Downloader.Saved) {
        // Just store it in "My music" — you choose when to play it.
        urlInput.setText("")
        refreshLibrary()
        toast(getString(R.string.lib_added, saved.title))
    }

    // ---------------------------------------------------------------- my music list

    private fun refreshLibrary() {
        tracks = try { Downloader.listDownloads(this) } catch (e: Exception) { emptyList() }
        trackList.removeAllViews()
        for (track in tracks) {
            val row = layoutInflater.inflate(R.layout.item_track, trackList, false)
            row.findViewById<TextView>(R.id.trackName).text = track.title
            row.setOnClickListener { open(track.uri, track.title) }
            row.findViewById<ImageButton>(R.id.trackDelete).setOnClickListener { confirmDelete(track) }
            row.tag = track.uri
            trackList.addView(row)
        }
        libraryEmpty.visibility = if (tracks.isEmpty()) View.VISIBLE else View.GONE
        libraryHeader.text = if (tracks.isEmpty()) getString(R.string.lib_header)
            else getString(R.string.lib_header_count, tracks.size)
        refreshHighlight()
    }

    /** Shows which song in the list is the one loaded in the player. */
    private fun refreshHighlight() {
        if (!::trackList.isInitialized) return
        val current = player.currentMediaItem?.localConfiguration?.uri
        for (i in 0 until trackList.childCount) {
            val row = trackList.getChildAt(i)
            val active = row.tag == current
            val color = ContextCompat.getColor(this, if (active) R.color.moon_gold else R.color.moon)
            row.findViewById<TextView>(R.id.trackName).apply {
                setTextColor(color)
                setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
            }
            row.findViewById<ImageView>(R.id.trackIcon).setColorFilter(
                ContextCompat.getColor(this, if (active) R.color.moon_gold else R.color.moon_dim)
            )
        }
    }

    private fun confirmDelete(track: Downloader.Saved) {
        AlertDialog.Builder(this)
            .setTitle(R.string.lib_delete_title)
            .setMessage(getString(R.string.lib_delete_msg, track.title))
            .setPositiveButton(R.string.lib_delete) { _, _ -> deleteTrack(track) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deleteTrack(track: Downloader.Saved) {
        if (player.currentMediaItem?.localConfiguration?.uri == track.uri) {
            player.stop()
            player.clearMediaItems()
            LoopPlayer.clearPoints()
            refreshAll()
        }
        try {
            if (track.uri.scheme == "file") {
                track.uri.path?.let { File(it).delete() }
            } else {
                contentResolver.delete(track.uri, null, null)
            }
            refreshLibrary()
        } catch (e: SecurityException) {
            // The file was saved by an earlier install: Android must ask you first.
            val sender = when {
                Build.VERSION.SDK_INT >= 30 ->
                    MediaStore.createDeleteRequest(contentResolver, listOf(track.uri)).intentSender
                Build.VERSION.SDK_INT == 29 && e is RecoverableSecurityException ->
                    e.userAction.actionIntent.intentSender
                else -> null
            }
            if (sender != null) deleteRequest.launch(IntentSenderRequest.Builder(sender).build())
        }
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
