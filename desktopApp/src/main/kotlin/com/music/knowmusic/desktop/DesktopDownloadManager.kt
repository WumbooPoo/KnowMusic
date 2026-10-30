package com.music.knowmusic.desktop

import com.music.knowmusic.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** Downloads resolved audio into the user's own KnowMusic folder. */
object DesktopDownloadManager {
    private val activeJobs = ConcurrentHashMap<String, Job>()

    /** Keeps an interrupted transfer in place and resumes it when the user tries again. */
    suspend fun download(
        song: Song,
        quality: String = DesktopPersistence().string("download_quality", "LOSSLESS"),
        onProgress: (downloaded: Long, total: Long?) -> Unit = { _, _ -> },
    ): Result<Song> {
        val job = currentCoroutineContext()[Job]
        if (job != null) activeJobs[song.videoId] = job
        // Set once the transfer is actually going to happen; cancelled on any failure below so a
        // failed download does not leave a lookup running for its 30-second ceiling.
        var lyricsLookup: Deferred<DesktopLyricsTag.Embeddable?>? = null
        return try {
            require(song.localPath == null) { "This track is already local" }
            // The download path used to log nothing on start or success — a
            // slow or stuck download was indistinguishable from a dead one.
            // These lines are what the next test reads back from the log.
            val startedMs = System.currentTimeMillis()
            // "STANDARD" is not a DesktopAudioQuality entry, so ceiling() would
            // silently fall back to the streaming rung — an unrelated setting
            // deciding downloads (round-15 settings audit). Normalize at the
            // download boundary only; playback's ceiling() is untouched. HIGH
            // matches how the addon/module tier maps already treat STANDARD.
            val effectiveQuality = if (quality.equals("STANDARD", ignoreCase = true)) "HIGH" else quality
            DesktopTrackLog.log("downloading '${song.title}' — resolving at $effectiveQuality")
            val stream = DesktopMusicSources.resolve(song, effectiveQuality, forDownload = true).getOrThrow()
            DesktopTrackLog.log(
                "  resolved via ${DesktopMusicSources.sourceNameFor(stream)}" +
                    " (${stream.transport ?: "direct"}, ${stream.format.summary.ifBlank { "unstated format" }})",
            )
            val directory = Path.of(System.getProperty("user.home"), "Music", "KnowMusic")
            Files.createDirectories(directory)
            val safeName = buildString {
                append(song.artist)
                append(" - ")
                append(song.title)
            }.replace(ILLEGAL_FILENAME, "_").trim().take(180).ifBlank { song.videoId }
            // A manifest is an index of the audio, not the audio: fetched as a file it saved a few
            // KB of XML as `.flac`. Its segments are fMP4, so it can only become FLAC or M4A.
            val manifest = DesktopManifestDownload.isManifest(stream)
            val extension = stream.format.fileExtension().let { if (manifest && it != "flac") "m4a" else it }
            val target = directory.resolve("$safeName.$extension")
            val temporary = directory.resolve(".$safeName.$extension.part")
            if (Files.isRegularFile(target) && Files.size(target) > 0L) {
                DesktopTrackLog.log("  already on disk: $target")
                return Result.success(song.copy(localUri = target.toUri().toString(), localPath = target.toString()))
            }

            // The lyrics lookup starts now so the transfer pays for it, as on the phone: by the
            // time either branch below has bytes to tag, the answer is normally already waiting.
            // It is awaited only after the move, and only its result is used — a failure or a
            // null never touches the download's success (see tagDownloadedLyrics). Deliberately
            // not started above: a file already on disk is a previous download, and backfilling
            // those is a separate job, not something this hook should do on the side.
            lyricsLookup = CoroutineScope(currentCoroutineContext()).async { DesktopLyricsTag.forTrack(song) }

            if (manifest) {
                // Segments are fetched and joined whole, so there is no partial file to resume.
                Files.deleteIfExists(temporary)
                Files.newOutputStream(temporary).buffered().use { output ->
                    DesktopManifestDownload.save(
                        stream,
                        extension,
                        output,
                        tempDir = java.io.File(System.getProperty("java.io.tmpdir")),
                        userAgent = DOWNLOAD_USER_AGENT,
                    ) { done, total -> onProgress(done, total) }
                }
                check(Files.size(temporary) > 0L) { "Download failed: nothing was sent" }
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
                DesktopTrackLog.log(
                    "  downloaded '${song.title}' — ${Files.size(target)} bytes" +
                        " in ${System.currentTimeMillis() - startedMs}ms → $target",
                )
                tagDownloadedLyrics(target, lyricsLookup)
                return Result.success(song.copy(localUri = target.toUri().toString(), localPath = target.toString()))
            }

            val existing = if (Files.isRegularFile(temporary)) Files.size(temporary) else 0L
            val request = DesktopDownloadHttp.request(
                stream.url,
                DOWNLOAD_USER_AGENT,
                stream.headers,
                rangeFrom = existing.takeIf { it > 0L },
            )
            DesktopDownloadHttp.client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Download failed (HTTP ${response.code})" }
                val body = response.body
                val append = existing > 0L && response.code == HTTP_PARTIAL
                val offset = if (append) existing else 0L
                val total = response.header("Content-Range")
                    ?.substringAfterLast('/')
                    ?.toLongOrNull()
                    ?.takeIf { it > 0L }
                    ?: body.contentLength().takeIf { it > 0L }?.let { it + offset }
                if (!append && existing > 0L) Files.deleteIfExists(temporary)
                Files.newOutputStream(
                    temporary,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    if (append) StandardOpenOption.APPEND else StandardOpenOption.TRUNCATE_EXISTING,
                ).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var downloaded = offset
                        onProgress(downloaded, total)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            output.write(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, total)
                        }
                    }
                }
            }
            check(Files.size(temporary) > 0L) { "Download failed: nothing was sent" }
            runCatching {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }.getOrElse {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
            DesktopTrackLog.log(
                "  downloaded '${song.title}' — ${Files.size(target)} bytes" +
                    " in ${System.currentTimeMillis() - startedMs}ms → $target",
            )
            tagDownloadedLyrics(target, lyricsLookup)
            Result.success(song.copy(localUri = target.toUri().toString(), localPath = target.toString()))
        } catch (cancelled: CancellationException) {
            lyricsLookup?.cancel()
            throw cancelled
        } catch (failure: Throwable) {
            lyricsLookup?.cancel()
            Result.failure(failure)
        } finally {
            if (job != null) activeJobs.remove(song.videoId, job)
        }
    }

    fun cancel(videoId: String) {
        activeJobs[videoId]?.cancel(CancellationException("Download cancelled"))
    }

    /**
     * Awaits the lookup started before the transfer and tags [target] with it, if there is
     * anything to tag.
     *
     * This runs after the move, never before it, and it cannot change the download's outcome:
     * a null (feature off, no lyrics, lookup failed, blank or over-cap answer) logs one line and
     * returns, and [DesktopLyricsTagger.tagFile] itself never throws for a tagging reason. The
     * one exception is cancellation, which is rethrown rather than swallowed — a download being
     * cancelled mid-tagging reports as cancelled, with the fully-downloaded file already on disk
     * (so the next attempt takes the already-on-disk path). The download-success log line above
     * is written before this runs, so the log still shows the download completing even when
     * tagging then reports a failure.
     */
    private suspend fun tagDownloadedLyrics(
        target: Path,
        lookup: Deferred<DesktopLyricsTag.Embeddable?>?,
    ) {
        val embeddable = try {
            lookup?.await()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            DesktopTrackLog.log("  lyrics not embedded in '$target': lookup failed: ${failure.message}")
            null
        }
        if (embeddable == null) {
            DesktopTrackLog.log("  no lyrics to embed in '$target'")
            return
        }
        DesktopLyricsTagger.tagFile(target, embeddable)
    }

    /**
     * The file saved for [song], or null when the record has outlived it.
     *
     * A download record is a claim about a folder this app does not own: the listener is expected
     * to manage it with a file manager, so an entry can name a file that has since been moved or
     * deleted. Android checks before it answers for the same reason — without it the player is
     * handed a path that is simply not there and the track refuses to play, saying only "could not
     * open stream".
     */
    fun savedFile(song: Song): Path? {
        val path = song.localPath?.let { runCatching { Path.of(it) }.getOrNull() }
            ?: song.localUri?.takeIf { it.startsWith("file:") }
                ?.let { runCatching { Path.of(URI(it)) }.getOrNull() }
            ?: return null
        return path.takeIf { Files.isRegularFile(it) && runCatching { Files.size(it) > 0 }.getOrDefault(false) }
    }

    /** Whether [song] is really on this computer, rather than merely recorded as being. */
    fun isSaved(song: Song): Boolean = savedFile(song) != null

    /**
     * The records that still name a file, with the rest dropped.
     *
     * A claim that has just been shown to be false is not worth keeping to be shown false again on
     * the next play.
     */
    fun verified(downloads: List<Song>): List<Song> = downloads.filter(::isSaved)

    /** Deletes the file saved for [song]. Returns whether there was one to delete. */
    fun delete(song: Song): Boolean {
        val file = savedFile(song) ?: return false
        return runCatching { Files.deleteIfExists(file) }
            .onFailure { DesktopTrackLog.log("could not delete the download of '${song.title}': ${it.message}") }
            .getOrDefault(false)
    }

    private fun DesktopStreamFormat.fileExtension(): String {
        val codec = codec.orEmpty().substringAfterLast('/').substringBefore(';').lowercase()
        return when (codec) {
            "flac" -> "flac"
            "wav", "wave" -> "wav"
            "alac" -> "m4a"
            "aac" -> "m4a"
            "mp3" -> "mp3"
            "ogg", "opus" -> "ogg"
            "webm" -> "webm"
            else -> "m4a"
        }
    }

    private const val HTTP_PARTIAL = 206
    private const val DOWNLOAD_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
    private val ILLEGAL_FILENAME = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")
}
