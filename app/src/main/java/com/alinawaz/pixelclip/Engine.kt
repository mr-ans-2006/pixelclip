package com.alinawaz.pixelclip

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.farimarwat.commons.UpdateChannel
import com.farimarwat.commons.YoutubeDLRequest
import com.farimarwat.library.YoutubeDL
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class VideoInfo(
    val title: String,
    val thumbnail: String?,
    val uploader: String?,
    val durationSec: Int,
    val site: String?
)

/** Thrown when a video has no English captions - PixelClip v1 cannot process it. */
class NoCaptionsException : Exception("No captions on this video - v1 needs captions. Try another video.")

object Engine {

    @Volatile
    var ready: Boolean = false
        private set

    suspend fun init(context: Context) = suspendCancellableCoroutine { cont ->
        YoutubeDL.init(
            appContext = context.applicationContext,
            withFfmpeg = true,
            withAria2c = false,
            onSuccess = {
                ready = true
                if (cont.isActive) cont.resume(Unit)
            },
            onError = { e ->
                if (cont.isActive) {
                    cont.resumeWithException(
                        RuntimeException(e.message ?: "Engine failed to start", e)
                    )
                }
            }
        )
    }

    suspend fun update(context: Context): String {
        var result: String? = null
        var failure: Throwable? = null
        YoutubeDL.updateYoutubeDL(
            appContext = context.applicationContext,
            updateChannel = UpdateChannel.STABLE,
            onSuccess = { status -> result = status.name },
            onError = { e -> failure = e }
        )
        failure?.let {
            throw RuntimeException(it.message ?: "Update failed", it)
        }
        return result ?: "DONE"
    }

    suspend fun fetchInfo(url: String): VideoInfo = suspendCancellableCoroutine { cont ->
        if (!ready) {
            cont.resumeWithException(
                IllegalStateException("Engine is still starting - wait for ENGINE READY at the top")
            )
            return@suspendCancellableCoroutine
        }
        val req = YoutubeDLRequest(url)
        req.addOption("--no-playlist")
        req.addOption("--no-warnings")
        YoutubeDL.getInfo(
            request = req,
            onSuccess = { info ->
                if (cont.isActive) {
                    cont.resume(
                        VideoInfo(
                            title = info.title?.takeIf { it.isNotBlank() }
                                ?: info.fulltitle?.takeIf { it.isNotBlank() }
                                ?: "Unknown title",
                            thumbnail = info.thumbnail?.takeIf { it.isNotBlank() },
                            uploader = info.uploader?.takeIf { it.isNotBlank() },
                            durationSec = info.duration,
                            site = (info.extractorKey ?: info.extractor)
                                ?.takeIf { it.isNotBlank() }
                        )
                    )
                }
            },
            onError = { e ->
                if (cont.isActive) {
                    cont.resumeWithException(
                        RuntimeException(e.message ?: "Could not read this link", e)
                    )
                }
            }
        )
    }

    /**
     * Downloads ONLY the subtitles (manual or auto-generated, English) as a VTT
     * file into the cache. No video data is downloaded. Throws
     * [NoCaptionsException] when the video has no English captions at all.
     *
     * v1.1: asks yt-dlp for just "en" + "en-orig" instead of the old "en.*"
     * wildcard (the wildcard made yt-dlp request many English caption tracks
     * at once and YouTube rate-limited the burst with HTTP 429). Also pauses
     * between subtitle requests, and if YouTube still answers 429, waits 15s
     * and retries once before showing a friendly error.
     */
    suspend fun downloadSubtitles(context: Context, url: String): File {
        try {
            return downloadSubtitlesOnce(context, url)
        } catch (e: Exception) {
            if (e is NoCaptionsException) throw e
            if (!isRateLimitError(e)) throw e
            delay(15_000)
            try {
                return downloadSubtitlesOnce(context, url)
            } catch (e2: Exception) {
                if (e2 is NoCaptionsException) throw e2
                if (isRateLimitError(e2)) {
                    throw IllegalStateException(
                        "YouTube is limiting caption downloads right now (429). " +
                            "Wait 2-3 minutes and try again."
                    )
                }
                throw e2
            }
        }
    }

    private fun isRateLimitError(e: Exception): Boolean {
        val msg = e.message ?: return false
        return msg.contains("429") || msg.contains("Too Many Requests", ignoreCase = true)
    }

    private suspend fun downloadSubtitlesOnce(context: Context, url: String): File =
        suspendCancellableCoroutine { cont ->
            if (!ready) {
                cont.resumeWithException(
                    IllegalStateException("Engine is still starting - wait for ENGINE READY at the top")
                )
                return@suspendCancellableCoroutine
            }
            val outDir = File(context.cacheDir, "subs").apply { mkdirs() }
            outDir.listFiles()?.forEach { it.delete() }
            val req = YoutubeDLRequest(url)
            req.addOption("--skip-download")
            req.addOption("--write-subs")
            req.addOption("--write-auto-subs")
            req.addOption("--sub-langs", "en,en-orig")
            req.addOption("--sleep-subtitles", "2")
            req.addOption("--sub-format", "vtt")
            req.addOption("--no-playlist")
            req.addOption("--no-warnings")
            req.addOption("-o", File(outDir, "%(id)s.%(ext)s").absolutePath)
            YoutubeDL.download(
                request = req,
                pId = "pixelclip-subs",
                progressCallBack = { _, _, _ -> },
                onStartProcess = { },
                onEndProcess = { resp ->
                    if (cont.isActive) {
                        val vtt = (outDir.listFiles()?.toList() ?: emptyList())
                            .filter { it.name.endsWith(".vtt") }
                            .maxByOrNull { it.length() }
                        when {
                            vtt != null -> cont.resume(vtt)
                            // yt-dlp exits 0 even when a video simply has no subtitles,
                            // so "no file" is the real no-captions signal.
                            resp.exitCode == 0 -> cont.resumeWithException(NoCaptionsException())
                            else -> cont.resumeWithException(
                                IllegalStateException(
                                    resp.err?.takeIf { it.isNotBlank() } ?: "Could not get captions"
                                )
                            )
                        }
                    }
                },
                onError = { e ->
                    if (cont.isActive) {
                        cont.resumeWithException(
                            RuntimeException(e.message ?: "Could not get captions", e)
                        )
                    }
                }
            )
        }

    /**
     * Downloads ONLY the [startSec]-[endSec] section of the video (max 720p) and
     * converts it to a vertical 1080x1920 MP4 in the cache.
     *
     * The Boom build used by PixelDL does not expose a verified direct FFmpeg
     * execute API, so the crop is done by yt-dlp's own FFmpeg post-processing:
     * --recode-video mp4 runs the FFmpegVideoConvertor post-processor, and the
     * --postprocessor-args below hand it the scale/crop filter. See BUILD_NOTES.md.
     */
    suspend fun downloadSection(
        context: Context,
        url: String,
        index: Int,
        startSec: Double,
        endSec: Double,
        onProgress: (Float, String) -> Unit
    ): File = suspendCancellableCoroutine { cont ->
        if (!ready) {
            cont.resumeWithException(
                IllegalStateException("Engine is still starting - wait for ENGINE READY at the top")
            )
            return@suspendCancellableCoroutine
        }
        val outDir = File(context.cacheDir, "sections").apply { mkdirs() }
        val before = outDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        val section = "*${trimSec(startSec)}-${trimSec(endSec)}"
        val req = YoutubeDLRequest(url)
        req.addOption("-f", "bv*[height<=720]+ba/b[height<=720]")
        req.addOption("--download-sections", section)
        req.addOption("--force-keyframes-at-cuts")
        req.addOption("--merge-output-format", "mp4")
        req.addOption("--recode-video", "mp4")
        req.addOption(
            "--postprocessor-args",
            "VideoConvertor:-vf scale=1080:1920:force_original_aspect_ratio=increase,crop=1080:1920"
        )
        req.addOption("--no-playlist")
        req.addOption("--no-mtime")
        req.addOption("-o", File(outDir, "clip$index-%(id)s.%(ext)s").absolutePath)
        YoutubeDL.download(
            request = req,
            pId = "pixelclip-cut",
            progressCallBack = { progress, _, line -> onProgress(progress, line) },
            onStartProcess = { },
            onEndProcess = { resp ->
                if (cont.isActive) {
                    if (resp.exitCode == 0) {
                        val newFiles = (outDir.listFiles()?.toList() ?: emptyList())
                            .filter {
                                it.name !in before &&
                                    !it.name.endsWith(".part") &&
                                    !it.name.endsWith(".ytdl")
                            }
                        val file = newFiles.maxByOrNull { it.length() }
                        if (file != null) {
                            cont.resume(file)
                        } else {
                            cont.resumeWithException(
                                IllegalStateException("Cut finished but no clip file was found")
                            )
                        }
                    } else {
                        cont.resumeWithException(
                            IllegalStateException(
                                resp.err?.takeIf { it.isNotBlank() } ?: "Cut failed"
                            )
                        )
                    }
                }
            },
            onError = { e ->
                if (cont.isActive) {
                    cont.resumeWithException(
                        RuntimeException(e.message ?: "Cut failed", e)
                    )
                }
            }
        )
    }

    /** Copies a finished clip into the phone's Movies/PixelClip so any player can see it. */
    fun saveToGallery(context: Context, src: File): Uri =
        saveMedia(context, src, "video/mp4", Environment.DIRECTORY_MOVIES)

    /** Saves a clip's .srt sidecar next to it in Movies/PixelClip. */
    fun saveSrtToGallery(context: Context, src: File): Uri =
        saveMedia(context, src, "application/x-subrip", Environment.DIRECTORY_MOVIES)

    private fun saveMedia(context: Context, src: File, mime: String, publicDirName: String): Uri {
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, src.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$publicDirName/PixelClip")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values)
                ?: throw IllegalStateException("Could not save to the gallery")
            val stream = resolver.openOutputStream(uri)
                ?: throw IllegalStateException("Could not write the file")
            stream.use { out -> src.inputStream().use { input -> input.copyTo(out) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            src.delete()
            return uri
        } else {
            val publicDir = File(
                Environment.getExternalStoragePublicDirectory(publicDirName),
                "PixelClip"
            ).apply { mkdirs() }
            val dest = File(publicDir, src.name)
            src.copyTo(dest, overwrite = true)
            src.delete()
            MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf(mime), null)
            return FileProvider.getUriForFile(context, "com.alinawaz.pixelclip.fileprovider", dest)
        }
    }

    private fun trimSec(sec: Double): String =
        if (sec == sec.toLong().toDouble()) sec.toLong().toString()
        else String.format(java.util.Locale.US, "%.2f", sec)
}
