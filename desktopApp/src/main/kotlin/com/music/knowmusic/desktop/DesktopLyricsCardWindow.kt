package com.music.knowmusic.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.knowmusic.data.lyrics.LyricLine
import com.music.knowmusic.data.lyrics.LyricsLanguage
import com.music.knowmusic.data.lyrics.LyricsTranslation

/**
 * The floating synced-lyrics card (round 30, Crm request): a third window,
 * opened by the pill's lyrics button, sitting under the pill at the top
 * centre of the screen. It shows the previous line, the current line in
 * bold, and the next two lines — the small window a floating player
 * needs, without opening the main window.
 *
 * This window is a second composition like the pill's, so it cannot read
 * the main window's remembered lyrics or playback state. It gets both
 * across the same shapes the pill uses: the song and the playback
 * position come from [DesktopMiniPlayerBridge], and the lyrics come
 * from its own [DesktopLyricsClient.lookup] call, which shares the
 * client's cache with the main window's lookup — so opening the card
 * for the song already playing usually costs no second fetch. The
 * lyrics offset the song menu offers applies here too, through
 * [DesktopPlayerSettings.lyricsOffsetMs].
 *
 * Deliberately v1-small: line-level highlight only (word-synced lines
 * still read as whole lines), and display only — tapping a line does
 * not seek, because the bridge carries no seek action. Round 31 shows
 * the romanized reading directly whenever one is available (the same
 * romanize call the main panel's romanization toggle uses, with the
 * same kana hint from the lookup, so a Japanese lyric reads as romaji
 * and an already-Latin lyric or a failed romanization simply keeps
 * the original text); the original lines show first and are swapped
 * for the romanized ones when they arrive, so the card never waits
 * blank on the network. The card has no
 * drag and no auto-hide: it stays at its fixed spot under the pill
 * until it is closed with its own close button, the pill's lyrics
 * button again, or the window's close request. Closing or hiding the
 * pill does not close the card, and restoring the main window does
 * not either — the close button is always visible, so the card is
 * never stuck open with its toggle out of reach.
 *
 * Round 31 also trims the card to what it shows: three lines only —
 * the one already passed on top, the one being sung in the middle,
 * the next one below — in a narrower, shorter window, so no empty
 * space hangs under the lines the way the four-line, 440 dp card's did.
 *
 * Round 32 (Crm 13:54 WIB 27 Okt: the kanji is missing) shows both
 * readings: every lyric keeps its original line (kanji/kana) with the
 * romaji reading under it, the way the main panel's romanized view
 * does. The sync clock still reads the original lines' timings, and
 * the romaji is paired by line index, so a romanized answer that
 * somehow came back shorter simply leaves that pair single. The
 * window grows with the second row — 220 dp tall with romaji, 160 dp
 * without — through the [onRomajiAvailable] callback, so the card
 * never carries the empty space a fixed tall window would leave on
 * a Latin-only song.
 *
 * Requires a [MaterialTheme] from the caller and a `LocalContentColor`
 * of white, both set where the window is declared, exactly like the
 * pill's window.
 */
@Composable
internal fun DesktopLyricsCardWindow(
    onClose: () -> Unit,
    onRomajiAvailable: (Boolean) -> Unit,
) {
    val song by DesktopMiniPlayerBridge.song.collectAsState()
    val positionMs by DesktopMiniPlayerBridge.positionMs.collectAsState()
    val lyricsOffsetMs by DesktopPlayerSettings.lyricsOffsetMs.collectAsState()
    val wallpaperPath by DesktopWallpaperSettings.wallpaperPath.collectAsState()
    val miniplayerOpacity by DesktopWallpaperSettings.wallpaperOpacityMiniplayer.collectAsState()
    val currentSong = song ?: return
    var lyrics by remember { mutableStateOf<DesktopLyrics?>(null) }
    var lyricsFailed by remember { mutableStateOf(false) }
    // The lines actually shown, in two parallel rows: the originals
    // (kanji/kana) and, when one comes back, their romaji reading.
    // The sync clock reads the originals' timings; the romaji is
    // paired by line index, so it can never shift the sync.
    var originalLines by remember { mutableStateOf<List<LyricLine>>(emptyList()) }
    var romanizedLines by remember { mutableStateOf<List<LyricLine>?>(null) }
    LaunchedEffect(currentSong.videoId) {
        lyrics = null
        lyricsFailed = false
        originalLines = emptyList()
        romanizedLines = null
        val found = DesktopLyricsClient.lookup(currentSong).getOrNull()
        lyrics = found
        lyricsFailed = found == null
        val original = found?.lines.orEmpty()
        originalLines = original
        if (original.isNotEmpty()) {
            val hint = DesktopLyricsClient.romanizationHint(currentSong.videoId)
                ?.takeIf { LyricsLanguage.containsKana(original) }
            when (val romanized = LyricsTranslation.romanize(currentSong.videoId, original, "en", hint)) {
                is LyricsTranslation.RomanizationResult.Romanized -> romanizedLines = romanized.lines
                else -> Unit
            }
        }
    }
    val hasRomaji = romanizedLines != null
    LaunchedEffect(hasRomaji) {
        onRomajiAvailable(hasRomaji)
    }
    val shape = RoundedCornerShape(16.dp)
    // Same glass rule as the pill: the opacity slider only applies
    // while a wallpaper is set; without one the card is the main
    // window's bar glass, as before.
    val glassAlpha = if (wallpaperPath.isNotBlank()) miniplayerOpacity else 1f
    val lines = originalLines
    val synced = lines.any { it.timeMs > 0L }
    // The line being sung: the last synced line whose start has passed,
    // at the offset-adjusted clock. Before the first line, the first
    // line is the one coming up, so it reads as current.
    val effectivePositionMs = positionMs + lyricsOffsetMs.toLong()
    val currentIndex = if (synced) {
        lines.indexOfLast { it.timeMs > 0L && it.timeMs <= effectivePositionMs }
            .takeIf { it >= 0 } ?: 0
    } else {
        -1
    }
    // One line already passed on top, the one being sung in the middle,
    // the next one below — a fixed three-line window, so the card never
    // scrolls and never reflows as lines pass. Each position renders
    // its original line plus its romaji reading when one exists.
    val startIndex = if (synced) (currentIndex - 1).coerceAtLeast(0) else 0
    val visibleIndices = if (synced) {
        (startIndex until minOf(lines.size, startIndex + 3)).toList()
    } else {
        (0 until minOf(lines.size, 3)).toList()
    }
    Column(
        Modifier
            .fillMaxSize()
            .clip(shape)
            .desktopBarGlass(shape, alphaMultiplier = glassAlpha)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    currentSong.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                )
                lyrics?.let {
                    Text(
                        it.source,
                        color = DesktopSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Rounded.Close, DesktopStrings["miniplayer_lyrics_close", "Close lyrics"], tint = Color.White)
            }
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            when {
                lyrics == null && !lyricsFailed -> {
                    Text(
                        DesktopStrings["miniplayer_lyrics_loading", "Loading lyrics…"],
                        color = DesktopSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                lines.isEmpty() -> {
                    Text(
                        DesktopStrings["miniplayer_lyrics_unavailable", "Lyrics unavailable"],
                        color = DesktopSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                else -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        visibleIndices.forEach { index ->
                            val line = lines[index]
                            val isCurrent = synced && index == currentIndex
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    line.text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isCurrent) Color.White else DesktopSecondary,
                                    style = if (isCurrent) {
                                        MaterialTheme.typography.bodyLarge
                                    } else {
                                        MaterialTheme.typography.bodyMedium
                                    },
                                )
                                romanizedLines?.getOrNull(index)?.text?.let { romaji ->
                                    Text(
                                        romaji,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = if (isCurrent) Color.White else DesktopSecondary,
                                        style = if (isCurrent) {
                                            MaterialTheme.typography.bodyMedium
                                        } else {
                                            MaterialTheme.typography.bodySmall
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
