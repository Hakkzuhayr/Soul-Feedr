package com.example.loopplayer

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Downloads the audio of a YouTube (or other supported site) link and converts it to MP3,
 * using yt-dlp + ffmpeg bundled by the youtubedl-android library.
 *
 * Saved MP3s go to Music/LoopPlayer on the phone (Android 10+), so other apps can see them too.
 */
object Downloader {

    interface Listener {
        fun onDownloadUpdate()
        fun onDownloadFinished(saved: Saved)
    }

    data class Saved(val uri: Uri, val title: String)

    /** All state is read/written on the main thread. */
    var running = false
        private set
    var progress = 0f          // 0..100, or -1 when unknown
        private set
    var status = ""
        private set
    var listener: Listener? = null

    private const val PROCESS_ID = "loopplayer-download"
    private const val FOLDER = "LoopPlayer"
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var initialized = false

    private val urlRegex = Regex("""https?://\S+""")

    /** Pulls the first link out of text (YouTube's "Share" adds extra words sometimes). */
    fun extractUrl(text: String?): String? = text?.let { urlRegex.find(it)?.value }

    fun start(context: Context, url: String) {
        if (running) return
        val app = context.applicationContext
        running = true
        progress = -1f
        status = app.getString(R.string.dl_preparing)
        notifyUpdate()

        executor.execute {
            try {
                val saved = downloadMp3(app, url)
                main.post {
                    running = false
                    progress = 100f
                    status = app.getString(R.string.dl_done, saved.title)
                    notifyUpdate()
                    listener?.onDownloadFinished(saved)
                }
            } catch (e: YoutubeDL.CanceledException) {
                finish(app.getString(R.string.dl_canceled))
            } catch (e: InterruptedException) {
                finish(app.getString(R.string.dl_canceled))
            } catch (e: Exception) {
                finish(app.getString(R.string.dl_failed, shortError(e)))
            }
        }
    }

    fun cancel() {
        if (running) YoutubeDL.getInstance().destroyProcessById(PROCESS_ID)
    }

    /** MP3s this app has downloaded, newest first. */
    fun listDownloads(context: Context): List<Saved> {
        val result = mutableListOf<Saved>()
        if (Build.VERSION.SDK_INT >= 29) {
            val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME),
                "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?",
                arrayOf("${Environment.DIRECTORY_MUSIC}/$FOLDER%"),
                "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val uri = ContentUris.withAppendedId(collection, c.getLong(0))
                    result += Saved(uri, c.getString(1).removeSuffix(".mp3"))
                }
            }
        } else {
            legacyDir(context).listFiles { f -> f.extension.equals("mp3", true) }
                ?.sortedByDescending { it.lastModified() }
                ?.forEach { result += Saved(Uri.fromFile(it), it.nameWithoutExtension) }
        }
        return result
    }

    // ---------------------------------------------------------------- background work

    private fun downloadMp3(app: Context, url: String): Saved {
        ensureInit(app)
        val workDir = File(app.cacheDir, "download").apply { deleteRecursively(); mkdirs() }
        try {
            try {
                runYtDlp(workDir, url)
            } catch (e: YoutubeDLException) {
                // YouTube changes often and breaks old yt-dlp versions → update once and retry.
                post(-1f, app.getString(R.string.dl_updating))
                try {
                    YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE)
                } catch (updateError: Exception) {
                    throw e
                }
                workDir.listFiles()?.forEach { it.deleteRecursively() }
                runYtDlp(workDir, url)
            }
            val mp3 = workDir.listFiles { f -> f.extension.equals("mp3", true) }
                ?.maxByOrNull { it.lastModified() }
                ?: throw IOException("No MP3 file was produced")
            post(-1f, app.getString(R.string.dl_saving))
            return saveToLibrary(app, mp3)
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun ensureInit(app: Context) {
        if (initialized) return
        synchronized(this) {
            if (!initialized) {
                YoutubeDL.getInstance().init(app)
                FFmpeg.getInstance().init(app)
                initialized = true
            }
        }
    }

    private fun runYtDlp(workDir: File, url: String) {
        val request = YoutubeDLRequest(url).apply {
            addOption("--no-playlist")              // a link inside a playlist → just that video
            addOption("-x")                         // audio only
            addOption("--audio-format", "mp3")
            addOption("--audio-quality", "0")       // best quality
            addOption("-o", File(workDir, "%(title).120B.%(ext)s").absolutePath)
        }
        YoutubeDL.getInstance().execute(request, PROCESS_ID) { percent, _, line ->
            post(percent, line.ifBlank { null })
        }
    }

    private fun saveToLibrary(app: Context, file: File): Saved {
        val title = file.nameWithoutExtension
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = app.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$FOLDER")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Could not create the file in Music")
            try {
                resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                    ?: throw IOException("Could not write the file")
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return Saved(uri, title)
        } else {
            val dest = File(legacyDir(app), file.name)
            file.copyTo(dest, overwrite = true)
            return Saved(Uri.fromFile(dest), title)
        }
    }

    private fun legacyDir(context: Context): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), FOLDER).apply { mkdirs() }

    // ---------------------------------------------------------------- helpers

    private fun post(percent: Float, line: String?) = main.post {
        if (!running) return@post
        progress = percent
        if (line != null) status = line
        notifyUpdate()
    }

    private fun finish(message: String) = main.post {
        running = false
        progress = 0f
        status = message
        notifyUpdate()
    }

    private fun notifyUpdate() = listener?.onDownloadUpdate()

    private fun shortError(e: Exception): String {
        val text = e.message.orEmpty()
        return text.lines().lastOrNull { it.startsWith("ERROR") }
            ?: text.lines().firstOrNull { it.isNotBlank() }?.take(200)
            ?: e.javaClass.simpleName
    }
}
