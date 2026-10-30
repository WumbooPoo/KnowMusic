package com.music.knowmusic.desktop

import com.music.knowmusic.data.lyrics.LrcLib
import com.music.knowmusic.data.lyrics.LyricLine
import com.music.knowmusic.data.lyrics.LyricsLanguage
import com.music.knowmusic.data.lyrics.LyricsRepository
import com.music.knowmusic.data.lyrics.LyricsSource
import com.music.knowmusic.data.lyrics.PaxSenix
import com.music.knowmusic.data.lyrics.toEnhancedLrc
import com.music.knowmusic.data.lyrics.toLrc
import com.music.knowmusic.data.lyrics.withBackgroundVocals
import com.music.knowmusic.data.model.Song
import com.music.knowmusic.data.model.durationMillis
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/** A track's lyrics and which provider they came from, by its label. */
data class DesktopLyrics(
    val source: String,
    val lines: List<LyricLine>,
    /**
     * True when every provider answered in a rejected language and this
     * wrong-language answer was shown anyway rather than leaving the panel
     * empty — see [LyricsRepository.Result.languageMismatchFallback]. The
     * drawer still names the answering source, and the row is the current
     * one, so this is bookkeeping for caching and tagging, not display.
     */
    val languageMismatchFallback: Boolean = false,
) {
    val plainText: String get() = lines.joinToString("\n") { it.text }
    val wordSynced: Boolean get() = lines.any(LyricLine::isWordSynced)
}

/**
 * The desktop's lyrics lookup.
 *
 * The providers, the race between them and the parsing are the phone's own
 * [LyricsRepository], shared from `:shared`. What stays here is the desktop's
 * settings — which providers are ticked, in what order, stored by label — and
 * the one source only a desktop has: the words inside a local file.
 */
object DesktopLyricsClient {
    private const val CACHE_TTL_MS = 12L * 60L * 60L * 1_000L

    /**
     * Short TTL for plain-only results and failures: a transient provider miss (503/timeout)
     * must self-heal on next play instead of sticking for 12 h. Synced results keep the full TTL.
     */
    private const val PLAIN_CACHE_TTL_MS = 10L * 60L * 1_000L

    /** Keys the settings screen writes and this reads. */
    internal const val KEY_SYNCED_LYRICS = "synced_lyrics"
    internal const val KEY_PRIORITIZE_SYLLABLES = "prioritize_syllable_lyrics"
    internal const val KEY_LYRICS_BLUR = "lyrics_blur"
    internal const val KEY_LYRICS_SOURCES = "lyrics_sources"

    /**
     * Which sources have ever been put in front of the listener; see
     * [DesktopPersistence.lyricsEnabledSources].
     */
    internal const val KEY_LYRICS_SOURCES_SEEN = "lyrics_sources_seen"
    internal const val KEY_LYRICS_ORDER = "lyrics_source_order"
    /** PaxSenix API key, as on Android (blank = keyless provider only). */
    internal const val KEY_PAXSENIX_API_KEY = "paxsenix_api_key"

    /** One lyric database, as the settings screen needs to describe it. */
    internal data class Source(val name: String, val detail: String, val wordSynced: Boolean)

    /** Every provider the phone has, in the order it asks them out of the box. */
    internal val sources: List<Source> = LyricsSource.offered.map { Source(it.label, it.detail, it.wordSynced) }

    /** The sources that will actually be asked, in the order they are asked. */
    internal fun enabledSources(order: List<String>, enabled: Set<String>): List<String> =
        order.filter { it in enabled }

    private class Cached(val createdAt: Long, val result: Result<DesktopLyrics>)

    /** Bound so a days-long session cannot grow the cache without end; eviction only re-queries. */
    private const val MAX_CACHE_ENTRIES = 200

    private val cache: MutableMap<String, Cached> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Cached>(MAX_CACHE_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>): Boolean =
                size > MAX_CACHE_ENTRIES
        }
    )

    /**
     * The romanization source language per track, for the player host's
     * romanize call — which receives only a video id and lines, not the
     * track's credits. Kana in a title, artist or album name proves the
     * track is Japanese (kana exists in no other language's writing), so
     * that — and only that — is recorded, as "ja". The host applies it
     * only to a lyric that itself contains kana, so it fixes a kanji-only
     * batch inside a Japanese song being auto-detected as Chinese, and
     * never forces Japanese onto a lyric with no Japanese signal at all.
     * Bounded like [cache]: eviction only means the next romanize falls
     * back to auto-detection.
     */
    private val romanizationHints: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(MAX_CACHE_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean =
                size > MAX_CACHE_ENTRIES
        }
    )

    internal fun romanizationHint(videoId: String): String? = romanizationHints[videoId]

    private fun noteRomanizationHint(song: Song) {
        val japanese = LyricsLanguage.containsKana(song.title) ||
            LyricsLanguage.containsKana(song.artist) ||
            (song.albumName?.let { LyricsLanguage.containsKana(it) } == true)
        if (japanese) romanizationHints[song.videoId] = "ja"
    }

    /**
     * The track's lyrics from the configured providers — or, with [only], from that one provider,
     * which is how the player's provider picker asks for a specific source's answer.
     *
     * [onSourceStarted] and [onSourceResult] report each provider's race as it runs, so the song
     * menu's provider drawer can show what each one turned up rather than only the winner. They
     * fire off the IO dispatcher and only for a lookup that actually reaches the network — a cache
     * hit or a local file's embedded words return at once and report nothing.
     */
    suspend fun lookup(
        song: Song,
        durationMs: Long = song.durationMillis(),
        only: String? = null,
        onSourceStarted: ((LyricsSource) -> Unit)? = null,
        onSourceResult: ((LyricsSource, Boolean) -> Unit)? = null,
        /**
         * Fires for a provider whose answer was rejected as wrong-language
         * during an automatic lookup, so the drawer can say so instead of
         * "found". Never fires for a manual pick (validation is off there)
         * or for the silent title-form retry (the drawer reflects the
         * primary query, and a retry skip is only logged).
         */
        onLanguageMismatch: ((LyricsSource) -> Unit)? = null,
    ): Result<DesktopLyrics> {
        val persistence = DesktopPersistence()
        // Off in Settings means no lookup at all rather than a lookup nobody sees.
        if (!persistence.boolean(KEY_SYNCED_LYRICS, true)) {
            return Result.failure(IllegalStateException("Synced lyrics are switched off"))
        }
        val configured = enabledSources(persistence.lyricsSourceOrder(), persistence.lyricsEnabledSources())
            .mapNotNull(::lyricsSourceNamed)
        val asked = only?.let(::lyricsSourceNamed)?.let(::listOf) ?: configured
        // Every source unticked is a state the listener can reach, and it means the same thing as
        // the feature being off.
        if (asked.isEmpty()) return Result.failure(IllegalStateException("No lyrics sources are enabled"))

        // Recorded even on a cache hit or an embedded answer, so the
        // romanize call that follows this lookup has the hint whatever path
        // the lyrics took. A manual search's probe carries the typed query,
        // so its hint comes from the typed title/artist, matching the lyrics
        // actually fetched for it.
        noteRomanizationHint(song)

        // The title and artist are part of the key, not just the video id: the
        // player's manual search asks the same track's providers under a typed
        // query, and a cache keyed only on the id would hand back the earlier
        // automatic answer and look as though the search had done nothing.
        // The key must include the source configuration: without it, changing
        // the enabled sources or their order re-triggers a lookup that hits the
        // cache and returns the previous provider's lines for the current song
        // (round-15 settings audit). A new song still gets a fresh key.
        // Embedded-first (round 17): a local file's own words win over any cached
        // online result. The old order (cache, then embedded) let a cached plain
        // or failure — up to 10 min — or a cached synced result — up to 12 h —
        // mask lyrics that were downloaded or backfilled into the file after the
        // lookup. Embedded reads are local and cheap, so they are not cached: a
        // re-tagged file is picked up on next play instead of serving stale words.
        // Manual provider picks (only != null) still skip embedded — an explicit
        // provider query must not be masked by the file.
        if (only == null) {
            embedded(song)?.let { return Result.success(DesktopLyrics("Downloaded", it)) }
        }

        val cacheKey = "${song.videoId}|$durationMs|${song.title}|${song.artist}|${only.orEmpty()}" +
            "|${asked.joinToString(",") { it.name }}|${persistence.boolean(KEY_PRIORITIZE_SYLLABLES, false)}"
        cache[cacheKey]?.takeIf { entry ->
            // Plain-only and failed lookups get the short TTL: they are the ones a transient
            // miss produces, and a 12 h stick turns one 503 into a day of plain panels.
            // A wrong-language fallback joins them: it is a known-bad answer, and
            // holding it for 12 h would hold the corrected provider answer off
            // for 12 h too.
            val plainOrFailed = entry.result.isFailure ||
                entry.result.getOrNull()?.lines?.all { it.timeMs <= 0L } == true ||
                entry.result.getOrNull()?.languageMismatchFallback == true
            val ttl = if (plainOrFailed) PLAIN_CACHE_TTL_MS else CACHE_TTL_MS
            System.currentTimeMillis() - entry.createdAt < ttl
        }?.let { return it.result }

        // Embedded was already checked before the cache above; only the online path runs here.
            // The PaxSenix key, as on Android: authenticated PaxSenix routes return
            // null without one. Applied per lookup so a key saved mid-session takes
            // effect on next play with no restart; blank keeps keyless behaviour.
            PaxSenix.setApiKey(persistence.string(KEY_PAXSENIX_API_KEY, ""))
            var found = LyricsRepository.lyrics(
                videoId = song.videoId,
                title = song.title,
                artist = song.artist,
                durationMs = durationMs,
                album = song.albumName,
                sources = asked.toSet(),
                order = asked,
                prioritizeSyllableSync = persistence.boolean(KEY_PRIORITIZE_SYLLABLES, false),
                onSourceStarted = onSourceStarted,
                onSourceResult = { source, result -> onSourceResult?.invoke(source, result != null) },
                // Automatic lookups only: a manual pick (only != null) is an
                // explicit query and is never second-guessed. The cache key
                // already contains [only], so a validated automatic answer
                // and an unvalidated manual answer for the same track never
                // share a cache entry.
                validateLanguage = only == null,
                onLanguageMismatch = { source ->
                    DesktopTrackLog.log("  lyrics language skip: ${source.label} for '${song.title}'")
                    onLanguageMismatch?.invoke(source)
                },
            )
            // Suffix retry (round 16, unsynced back-number songs): catalogues index some songs
            // under their English title ("Siren", "Size of the Kaiju") while the player queries the
            // Japanese or combined title ("サイレン - Siren"), and the strict title gates then miss
            // the English hit. Only when the first pass yielded plain-or-null, and only for the
            // automatic lookup (a manual provider pick is an explicit query — never second-guess
            // it). The retry runs silent (no provider callbacks: the drawer reflects the primary
            // query) and is accepted ONLY if it returns synced lines, so it can only upgrade
            // plain→synced, never downgrade a synced result. Residual risk, stated honestly: the
            // retry passes the same artist and duration, and the lrc.red chain gates on duration
            // internally, but a same-title-same-artist wrong recording from a duration-blind
            // source cannot be ruled out from here — the synced-only acceptance bounds it to
            // cases where the first pass already failed.
            // Title-form retry (round 16 suffix + round 17 forms): catalogues may index
            // a song under a different form of its title than the player queries.
            // Each candidate is tried in order and accepted ONLY if it returns synced
            // lines, so a retry can only upgrade plain→synced, never downgrade, and
            // never accept a plain result for a different title form. Only for the
            // automatic lookup (a manual provider pick is an explicit query — never
            // second-guess it) and silent (no provider callbacks: the drawer reflects
            // the primary query). The remembered ISRC is bypassed here so the retry
            // identifies fresh from the candidate title instead of reusing the first
            // title's identify result. Round 18 extends the trigger by one case:
            // a wrong-language fallback is synced, so the old plain-or-null
            // trigger would never have retried it — yet a different title form
            // (e.g. the English suffix title) may be exactly where the
            // Japanese answer is catalogued. The acceptance below stays
            // synced-only; a retried fallback is refused only where it
            // would displace a validated plain incumbent (see
            // [primaryPlainValidated]), so this can only upgrade, never
            // downgrade a correct answer to a wrong-language one.
            // A validated plain incumbent is the one case a retried
            // fallback must not displace: correct words, unsynced, beat
            // wrong-language words, synced. In every other trigger case
            // (nothing found, or a fallback already showing) a retried
            // fallback is never worse than what is on screen, and for a
            // genuine Chinese song it is the sync upgrade the retry exists
            // for — refusing it there would regress Chinese songs whose
            // primary answer was plain.
            val primaryPlainValidated = found != null && !found.languageMismatchFallback &&
                found.lines.all { it.timeMs <= 0L }
            if ((found == null || found.lines.all { it.timeMs <= 0L } ||
                found.languageMismatchFallback) && only == null) {
                for (candidate in retryTitles(song.title)) {
                    val retried = runCatching {
                        LyricsRepository.lyrics(
                            videoId = song.videoId,
                            title = candidate,
                            artist = song.artist,
                            durationMs = durationMs,
                            album = song.albumName,
                            sources = asked.toSet(),
                            order = asked,
                            prioritizeSyllableSync = persistence.boolean(KEY_PRIORITIZE_SYLLABLES, false),
                            ignoreRememberedIsrc = true,
                            // Same validation as the primary race, but silent:
                            // the drawer reflects the primary query, so a
                            // retry skip is logged, not drawn.
                            validateLanguage = true,
                            onLanguageMismatch = { source ->
                                DesktopTrackLog.log("  lyrics language skip (retry): ${source.label} for '${song.title}'")
                            },
                        )
                    }.getOrNull()
                    if (retried != null && retried.lines.any { it.timeMs > 0L } &&
                        (!retried.languageMismatchFallback || !primaryPlainValidated)
                    ) {
                        found = retried
                        break
                    }
                }
            }
            val onlineResult = if (found == null) {
                Result.failure(IllegalStateException("Lyrics unavailable"))
            } else {
                Result.success(
                    DesktopLyrics(found.source.label, found.lines, found.languageMismatchFallback),
                )
            }
            // Backfill (round 16): an online lookup that succeeded for a song playing from a
            // local file writes those lyrics into the file, so the NEXT offline play finds them
            // embedded. Automatic lookups only (a manual provider pick is exploratory, not a
            // tagging decision), once per path per process (an untaggable file would otherwise
            // log a skip on every play). Fail-soft and inline: local tagging is milliseconds
            // for typical tracks, and it never fails the lookup. A
            // wrong-language fallback is never backfilled: embedding it would
            // make the next offline play serve it as "Downloaded" with no
            // provider race left to correct it, and the short cache TTL above
            // is what lets the next online play re-ask instead.
            if (only == null && found != null && !found.languageMismatchFallback) {
                backfillLocalLyrics(song, found.lines)
            }
        cache[cacheKey] = Cached(System.currentTimeMillis(), onlineResult)
        return onlineResult
    }

    /**
     * Title forms to retry when the first pass found plain-or-nothing (round 16
     * suffix + round 17 forms). The catalogue may index a song under the suffix
     * ("サイレン - Siren" → "Siren"), the prefix ("Siren - サイレン" → "Siren"),
     * or a bracketed alternative title ("サイレン (Siren)" → "Siren"). Bracket
     * contents that name a different recording or packaging — version markers,
     * credits, upload labels — are never candidates: searching for them would ask
     * for a different recording, not a different form of this one (the same rule
     * [forLyricsSearch] applies when cleaning). Capped so a heavily bracketed
     * title cannot fan out into many races.
     */
    private fun retryTitles(title: String): List<String> {
        val trimmed = title.trim()
        val out = LinkedHashSet<String>()
        fun add(candidate: String) {
            val clean = candidate.trim()
            if (clean.isNotEmpty() && clean != trimmed) out.add(clean)
        }
        add(title.substringAfterLast(" - ", ""))
        add(title.substringBefore(" - ", ""))
        BRACKET_CONTENT.findAll(title).forEach { match ->
            val content = match.groupValues[1].trim()
            val lower = content.lowercase()
            val excluded = lower in RETRY_TITLE_EXCLUSIONS ||
                lower.startsWith("feat") || lower.startsWith("ft ") ||
                lower.startsWith("featuring") || lower.startsWith("with ") ||
                lower.startsWith("official")
            if (!excluded) add(content)
        }
        return out.take(4)
    }

    private val BRACKET_CONTENT = Regex("""[(（\[]([^)）\]]+)[)）\]]""")

    /** Bracket contents that name a different recording or packaging, never a title variant. */
    private val RETRY_TITLE_EXCLUSIONS = setOf(
        "remix", "live", "acoustic", "remaster", "remastered", "sped up", "slowed",
        "instrumental", "karaoke", "cover", "edit", "mix", "extended", "radio edit",
        "feat", "ft", "featuring", "with", "official", "video", "audio", "mv", "hd",
        "4k", "lyrics", "lyric video", "visualizer", "ver", "version",
    )

    /** Paths already offered a backfill this process, so untaggable files don't log a skip on every play. */
    private val backfillAttempted = ConcurrentHashMap.newKeySet<String>()

    /**
     * Writes an online lookup's lines into the local file they were found for, so the next
     * offline play finds them embedded. Never throws, never fails the lookup that found them.
     */
    private fun backfillLocalLyrics(song: Song, lines: List<LyricLine>) {
        val path = song.localPath?.let { runCatching { java.nio.file.Paths.get(it) }.getOrNull() }
            ?: song.localUri?.let { runCatching { java.nio.file.Paths.get(java.net.URI(it)) }.getOrNull() }
            ?: return
        if (!backfillAttempted.add(path.toString())) return
        val plain = lines.toLrc()
        if (plain.isBlank() || plain.length > 64_000) return
        val enhanced = lines.toEnhancedLrc().takeIf { it.isNotBlank() && it.length <= 128_000 }
        runCatching {
            DesktopLyricsTagger.tagFile(path, DesktopLyricsTag.Embeddable(plain, enhanced))
        }.onFailure {
            DesktopTrackLog.log("  lyrics backfill failed for '$path': ${it.message}")
        }
    }

    /** The words inside the file this track plays from, when it plays from one. */
    private fun embedded(song: Song): List<LyricLine>? {
        val path = song.localPath?.let { runCatching { java.nio.file.Paths.get(it) }.getOrNull() }
            ?: song.localUri?.let { runCatching { java.nio.file.Paths.get(java.net.URI(it)) }.getOrNull() }
            ?: return null
        val raw = DesktopEmbeddedLyrics.read(path) ?: return null
        // The same last pass the network sources get, as on the phone.
        return LrcLib.parseLrc(raw).takeIf { lines -> lines.any { it.text.isNotBlank() } }?.withBackgroundVocals()
    }
}
