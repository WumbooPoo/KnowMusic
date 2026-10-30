package com.music.knowmusic.desktop

import com.music.knowmusic.data.lyrics.LyricsRepository
import com.music.knowmusic.data.lyrics.toEnhancedLrc
import com.music.knowmusic.data.lyrics.toLrc
import com.music.knowmusic.data.model.Song
import com.music.knowmusic.data.model.durationMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The lyrics to write into a track [DesktopDownloadManager] is about to save, as LRC text.
 *
 * A desktop port of the phone's `LyricsTag`: the same lookup the player does —
 * [LyricsRepository], the same providers, the same user's pick of which of them may be asked —
 * turned into the one pair of strings [DesktopLyricsTagger] can hand a container. A download is
 * the moment to do it: the lyrics are fetched over the connection that was already good enough
 * to pull the audio down, and they land in the file rather than in a cache, so they survive the
 * app being cleared and travel with the track if it is copied off the computer.
 *
 * Adapted from Android, not copied blindly:
 *
 *  - `AppSettings` does not exist on desktop. The gate is the same pair of settings read the way
 *    [DesktopLyricsClient] reads them: [DesktopLyricsClient.KEY_SYNCED_LYRICS] (default on, as
 *    the player reads it) and the ticked sources in their saved order, via
 *    [DesktopPersistence.lyricsEnabledSources] and [DesktopPersistence.lyricsSourceOrder], matched
 *    to providers by [lyricsSourceNamed] exactly as the player's lookup does. A source the user
 *    unticked is not contacted here either.
 *  - The phone's `Log` calls became [DesktopTrackLog] lines, so a skipped or failed embed is
 *    visible in the same log the download itself writes.
 *  - Everything else is the phone's bargain, unchanged: the 30-second wait ceiling, the 64,000
 *    character cap on what a third party's response may contribute to a kept file, the blank
 *    rejection, and never throwing for anything but cancellation. A download that has otherwise
 *    succeeded must not be undone by a lyrics server being down.
 */
internal object DesktopLyricsTag {

    /**
     * The two forms of one track's lyrics.
     *
     * [plain] is what goes in the container's own lyrics field, where every other player looks.
     * [enhanced] is the same lines with their word timings kept, in a field only this app reads —
     * null when the source was line-synced and there was nothing extra to say. See
     * [toEnhancedLrc] for why they are two fields rather than one.
     */
    internal class Embeddable(val plain: String, val enhanced: String?)

    /**
     * An LRC document pair for [song], or null when there is nothing worth writing.
     *
     * Null covers four different things on purpose, because the caller does the same thing for
     * all of them: the feature is off, the track has no lyrics published, the lookup failed, or
     * the answer was unusable.
     */
    suspend fun forTrack(song: Song): Embeddable? {
        val persistence = DesktopPersistence()
        val asked = if (persistence.boolean(DesktopLyricsClient.KEY_SYNCED_LYRICS, true)) {
            DesktopLyricsClient.enabledSources(persistence.lyricsSourceOrder(), persistence.lyricsEnabledSources())
                .mapNotNull(::lyricsSourceNamed)
        } else {
            emptyList()
        }
        if (asked.isEmpty()) return null

        // Album and playlist rows do not always carry a duration. That must not make bulk
        // downloads the one path that never saves lyrics: the title, artist and album still give
        // the providers a useful match, whereas a track played once gets its duration backfilled
        // by the player and would otherwise appear to be the only downloadable one with words.
        // Providers that need a runtime treat zero as unknown.
        val durationMs = song.durationMillis()
        if (durationMs <= 0L) {
            DesktopTrackLog.log("no duration for ${song.videoId}; looking up lyrics by title and artist")
        }

        val found = try {
            withTimeoutOrNull(LOOKUP_MS) {
                LyricsRepository.lyrics(
                    videoId = song.videoId,
                    title = song.title,
                    artist = song.artist,
                    durationMs = durationMs,
                    album = song.albumName,
                    sources = asked.toSet(),
                    order = asked,
                    prioritizeSyllableSync = persistence.boolean(
                        DesktopLyricsClient.KEY_PRIORITIZE_SYLLABLES,
                        false,
                    ),
                    // A download keeps its file: the same language validation
                    // as the player's automatic lookup, so a wrong-language
                    // answer is not written into a file the listener keeps.
                    validateLanguage = true,
                    onLanguageMismatch = { source ->
                        DesktopTrackLog.log("  lyrics language skip: ${source.label} for '${song.title}'")
                    },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DesktopTrackLog.log("no lyrics for ${song.videoId}: ${e.message}")
            return null
        } ?: return null

        // A fallback that survived only because every provider answered in
        // the rejected language is not embedded either: the file would then
        // serve it offline as "Downloaded" with no race left to correct it,
        // and the player re-asks on the next online play instead.
        if (found.languageMismatchFallback) {
            DesktopTrackLog.log("  lyrics language fallback for ${song.videoId}; not embedding")
            return null
        }

        // A result made only of blank lines is what an instrumental, or a provider that answered
        // with timing and no words, comes back as. Those convert to a column of bare timestamps,
        // which is not lyrics — and is not blank either, so the length check below would let it
        // through.
        if (found.lines.none { it.text.isNotBlank() }) return null

        val lrc = found.lines.toLrc()
        if (lrc.length > MAX_LRC_CHARS) {
            // Every byte of this is about to be copied into a file the user keeps, and the size
            // is decided by a third party's response body. A cap is the difference between a bad
            // answer costing the lyrics and one costing the download's file size.
            DesktopTrackLog.log("lyrics for ${song.videoId} are ${lrc.length} chars; not embedding")
            return null
        }
        if (lrc.isBlank()) return null
        // Capped the same way and for the same reason as the plain form; the word stamps roughly
        // double it, so it gets its own headroom rather than sharing the budget and pushing the
        // portable field out.
        val enhanced = found.lines.toEnhancedLrc().takeIf {
            it.isNotBlank() && it.length <= MAX_LRC_CHARS * 2
        }
        DesktopTrackLog.log(
            "embedding ${found.source.label} lyrics for ${song.videoId}" +
                if (enhanced != null) " (word-synced)" else "",
        )
        return Embeddable(plain = lrc, enhanced = enhanced)
    }

    /**
     * The most LRC one track may contribute to its own file.
     *
     * A long song's stamped sheet runs to a few thousand characters, so this is an order of
     * magnitude clear of anything genuine — it is a ceiling on a malformed or hostile response,
     * not a judgement about songs.
     */
    private const val MAX_LRC_CHARS = 64_000

    /**
     * How long the lookup may hold a finished download up.
     *
     * Usually nothing: [DesktopDownloadManager] starts this before the transfer, so by the time
     * there are bytes to tag the answer has normally been waiting a while. This is the ceiling
     * for the case where it hasn't — a short track on a fast connection, or a service that has
     * stopped answering — and it is finite because the alternative is a saved file the user can
     * see in the queue, complete, held back on a lyric server.
     *
     * Note that it bounds the *wait*, not the request. Cancelling a download mid-lookup cannot
     * interrupt a blocking socket read, so the queue still waits out whichever HTTP call is in
     * flight (its own timeouts, not this one) before it moves on.
     */
    private const val LOOKUP_MS = 30_000L
}
