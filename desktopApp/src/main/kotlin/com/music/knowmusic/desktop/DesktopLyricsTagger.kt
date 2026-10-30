package com.music.knowmusic.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Writes a download's lyrics into its own file, without ever risking the download.
 *
 * The desktop half of the phone's `MediaTagger` embed, cut down to lyrics only: title, artist,
 * album and cover art are deliberately not written here — that is a separate follow-up, and
 * smuggling it into a lyrics fix is how a lyrics regression gets a metadata one riding along.
 * The three container writers are the phone's byte-level taggers, ported verbatim apart from
 * package and owner strings ([DesktopMp4Tagger], [DesktopFlacTagger], [DesktopWebmTagger]); each
 * one hands back its input unchanged when it does not recognise the layout, and that is treated
 * here as "nothing safe to do", never as a reason to guess.
 *
 * The fail-soft bargain, in order:
 *
 *  1. The original file is never rewritten in place. Tagging happens on a temp copy beside it.
 *  2. The tagged copy is verified by reading it back through [DesktopEmbeddedLyrics.read] — the
 *     same reader the player uses — which must return non-null lyrics text. A writer and a
 *     reader that disagree produce a file that claims to be tagged and plays untagged; the
 *     verification is what catches that before the original is touched.
 *  3. Only then is the copy moved over the original, atomically where the filesystem allows it
 *     (same-directory rename, mirroring the download's own move), with a plain replace as the
 *     fallback for filesystems that refuse an atomic move.
 *  4. Any failure at any step — tag error, verification failure, move error — discards the temp
 *     copy, leaves the original byte-for-byte untouched, and writes one [DesktopTrackLog] line.
 *     Tagging never fails a download: this returns a Boolean, it never throws for a tagging
 *     reason, and the caller keeps its success either way.
 *
 * Formats without a writer here ([DesktopMp4Tagger] covers m4a, [DesktopFlacTagger] flac,
 * [DesktopWebmTagger] webm) are skipped, not forced: an mp3, ogg or wav download keeps playing
 * exactly as it did before this file existed, and the skip is logged so it reads as a decision
 * rather than a silent gap.
 */
internal object DesktopLyricsTagger {

    /** Tag [path] with [embeddable]; true only when the file on disk now carries the lyrics. */
    fun tagFile(path: Path, embeddable: DesktopLyricsTag.Embeddable): Boolean {
        val extension = path.fileName.toString().substringAfterLast('.', "").lowercase()
        if (extension !in TAGGABLE) {
            DesktopTrackLog.log("  lyrics not embedded in '$path': .$extension has no lyrics writer here")
            return false
        }
        val parent = path.parent
        if (parent == null) {
            DesktopTrackLog.log("  lyrics not embedded in '$path': no parent directory for a temp copy")
            return false
        }
        val temp = parent.resolve(".${path.fileName}.tagging")
        return try {
            // Tagging holds ~2× the file in memory (original + tagged copy); the app heap is
            // 256 MB, so a multi-hundred-MB file could OOM the player mid-tag. Skip those.
            if (runCatching { Files.size(path) }.getOrDefault(0L) > MAX_TAG_FILE_BYTES) {
                DesktopTrackLog.log("  lyrics not embedded in '$path': file too large to tag safely")
                return false
            }
            val original = runCatching { Files.readAllBytes(path) }.getOrNull()
            if (original == null) {
                DesktopTrackLog.log("  lyrics not embedded in '$path': could not read the download")
                return false
            }
            // A FLAC that already carries comments is managed by another tagger: the lyrics-only
            // rewrite would drop its title/artist/cover along with the old comment block, so
            // keep it untouched instead. Fresh streaming downloads have no comment block.
            if (extension == "flac" && DesktopFlacTagger.hasCommentBlock(original)) {
                DesktopTrackLog.log("  lyrics not embedded in '$path': existing FLAC metadata kept, not overwritten")
                return false
            }
            val tagged = when (extension) {
                "m4a" -> DesktopMp4Tagger.tag(
                    original,
                    title = "",
                    artist = "",
                    album = null,
                    lyrics = embeddable.plain,
                    cover = null,
                    coverIsPng = false,
                    wordLyrics = embeddable.enhanced,
                )
                "flac" -> DesktopFlacTagger.tag(
                    original,
                    title = "",
                    artist = "",
                    album = null,
                    lyrics = embeddable.plain,
                    cover = null,
                    coverMime = "image/jpeg",
                    wordLyrics = embeddable.enhanced,
                )
                else -> DesktopWebmTagger.tag(
                    original,
                    title = "",
                    artist = "",
                    album = null,
                    lyrics = embeddable.plain,
                    cover = null,
                    coverMime = "image/jpeg",
                    wordLyrics = embeddable.enhanced,
                )
            }
            // Every tagger hands back the same array reference when there was nothing safe to
            // do — cheaper than a byte comparison, and exact. A byte-identical result is the
            // same verdict by another route: nothing was written, so there is nothing to verify
            // or move.
            if (tagged === original || tagged.contentEquals(original)) {
                DesktopTrackLog.log("  lyrics not embedded in '$path': unrecognised .$extension layout")
                return false
            }
            Files.write(temp, tagged)
            // The reader the player itself uses, on the copy — not on the original, which must
            // not be touched until this passes.
            val verified = DesktopEmbeddedLyrics.read(temp)
            if (verified.isNullOrBlank()) {
                DesktopTrackLog.log("  lyrics not embedded in '$path': tagged copy failed verification")
                return false
            }
            runCatching {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }.getOrElse {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
            }
            DesktopTrackLog.log("  lyrics embedded in '$path'")
            true
        } catch (failure: Throwable) {
            DesktopTrackLog.log("  lyrics not embedded in '$path': ${failure.message}")
            false
        } finally {
            // A leftover temp copy is litter, not damage: the original was never touched, and the
            // next download's early-return check keys on the final target, not on this name.
            runCatching { Files.deleteIfExists(temp) }
        }
    }

    /** Files larger than this are not tagged: the ~2× in-memory hold would risk the 256 MB heap. */
    private const val MAX_TAG_FILE_BYTES = 128L * 1024L * 1024L

    /** The extensions the phone's `MediaTagger` tags, which is the same set ported here. */
    private val TAGGABLE = setOf("m4a", "webm", "flac")
}
