package com.example.loopplayer

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor

/**
 * One shared player for the whole app (used by the screen and the background service),
 * plus the loop logic.
 *
 * Loop modes:
 *  - OFF:   play once
 *  - TRACK: repeat the whole track (gapless, handled by ExoPlayer)
 *  - AB:    repeat only the section between point A and point B
 */
object LoopPlayer {

    enum class Mode { OFF, TRACK, AB }

    private var exo: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    /** Loop points in milliseconds, or -1 when not set. */
    var pointA: Long = -1L
        private set
    var pointB: Long = -1L
        private set

    var mode: Mode = Mode.OFF
        set(value) {
            field = if (value == Mode.AB && !hasAbPoints()) Mode.OFF else value
            applyMode()
        }

    var loopCount: Int = 0
        private set

    fun hasAbPoints() = pointA >= 0 && pointB > pointA

    @OptIn(UnstableApi::class)
    fun get(context: Context): ExoPlayer {
        exo?.let { return it }

        // Index seeking makes seeks in MP3 files exact, so A-B loops jump to the right spot
        // even for variable-bitrate files.
        val extractors = DefaultExtractorsFactory()
            .setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)

        val player = ExoPlayer.Builder(context.applicationContext)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context.applicationContext, extractors))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged
            .build()

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                // If B is at (or past) the very end, the track may end before we catch it.
                if (state == Player.STATE_ENDED && mode == Mode.AB && hasAbPoints()) {
                    loopCount++
                    player.seekTo(pointA)
                    player.play()
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION && mode == Mode.TRACK) {
                    loopCount++
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) startAbWatcher() else handler.removeCallbacks(abWatcher)
            }
        })

        exo = player
        return player
    }

    fun load(context: Context, uri: Uri, title: String) {
        val player = get(context)
        clearPoints()
        loopCount = 0
        val item = MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .build()
        player.setMediaItem(item)
        player.prepare()
        player.play()
    }

    /** Sets A at the current position. Returns false if it would be after B. */
    fun setA(): Boolean {
        val pos = exo?.currentPosition ?: return false
        if (pointB >= 0 && pos >= pointB) return false
        pointA = pos
        loopCount = 0
        if (hasAbPoints()) mode = Mode.AB
        return true
    }

    /** Sets B at the current position and turns on the A-B loop. Returns false if before A. */
    fun setB(): Boolean {
        val player = exo ?: return false
        val pos = player.currentPosition
        if (pointA < 0) pointA = 0L          // no A yet → loop from the start
        if (pos <= pointA) return false
        pointB = pos
        loopCount = 0
        mode = Mode.AB
        player.seekTo(pointA)
        return true
    }

    /** Moves a loop point by [deltaMs] (for fine tuning). */
    fun nudge(isA: Boolean, deltaMs: Long) {
        val duration = exo?.duration?.takeIf { it > 0 } ?: Long.MAX_VALUE
        if (isA && pointA >= 0) {
            val upper = if (pointB >= 0) pointB - 50 else duration
            pointA = (pointA + deltaMs).coerceIn(0L, upper)
        } else if (!isA && pointB >= 0) {
            pointB = (pointB + deltaMs).coerceIn(pointA + 50, duration)
        }
    }

    fun clearPoints() {
        pointA = -1L
        pointB = -1L
        if (mode == Mode.AB) mode = Mode.OFF
    }

    /** OFF → TRACK → AB (only if points are set) → OFF */
    fun cycleMode() {
        loopCount = 0
        mode = when (mode) {
            Mode.OFF -> Mode.TRACK
            Mode.TRACK -> if (hasAbPoints()) Mode.AB else Mode.OFF
            Mode.AB -> Mode.OFF
        }
    }

    private fun applyMode() {
        val player = exo ?: return
        player.repeatMode = if (mode == Mode.TRACK) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        if (mode == Mode.AB) startAbWatcher() else handler.removeCallbacks(abWatcher)
    }

    // Checks the position very often and jumps back to A when B is reached.
    private val abWatcher = object : Runnable {
        override fun run() {
            val player = exo ?: return
            if (mode != Mode.AB || !hasAbPoints()) return
            if (player.currentPosition >= pointB) {
                loopCount++
                player.seekTo(pointA)
            }
            if (player.isPlaying) handler.postDelayed(this, 10)
        }
    }

    private fun startAbWatcher() {
        handler.removeCallbacks(abWatcher)
        if (mode == Mode.AB) handler.post(abWatcher)
    }
}
