package com.music.knowmusic.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.knowmusic.data.model.ROW_ART_PX
import com.music.knowmusic.ui.icons.KnowMusicIcons

/**
 * The floating miniplayer pill (round 23, Arvin request): a second, undecorated,
 * always-on-top window, top-centre of the screen, at most 440 dp wide — the same
 * pill the main window already draws in its compact chrome, lifted out of it.
 *
 * Everything here is pointer-driven and goes through [DesktopMiniPlayerBridge]:
 * cover/title expand the main window, love/prev/play/next call the main window's
 * party-lock-aware transport functions, and the close button only hides the pill.
 * Nothing here touches the playback engine, SMTC, the media-key guard, the tray,
 * or [DesktopQuit] — closing this window can never quit the app, and the pill
 * never publishes playback state of its own.
 *
 * The close button appears only while the pointer hovers the pill, in a slot that
 * is always reserved, so the row never reflows when it appears. Round 24 adds a
 * hide button beside it on the same pattern: it slides the pill to its nearest
 * screen edge, leaving a strip to hover back, while close still dismisses until
 * restore. Round 30 adds a lyrics button on the same hover-only pattern, before
 * the hide slot: it toggles the floating synced-lyrics card, and its tint shows
 * whether the card is open. The pill has no
 * text fields and no keyboard shortcuts: a space pressed while it has focus does
 * nothing (see the bridge's comment — duplicating the handler would double-toggle).
 *
 * Requires a [MaterialTheme] from the caller (the pill window has its own
 * composition, so the main window's theme does not carry over) and a
 * `LocalContentColor` of white, both set where the window is declared.
 */
@Composable
internal fun DesktopMiniPlayerWindow(
    onHideToEdge: () -> Unit,
    lyricsCardOpen: Boolean,
    onToggleLyricsCard: () -> Unit,
) {
    val song by DesktopMiniPlayerBridge.song.collectAsState()
    val isPlaying by DesktopMiniPlayerBridge.isPlaying.collectAsState()
    val liked by DesktopMiniPlayerBridge.liked.collectAsState()
    val wallpaperPath by DesktopWallpaperSettings.wallpaperPath.collectAsState()
    val miniplayerOpacity by DesktopWallpaperSettings.wallpaperOpacityMiniplayer.collectAsState()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val currentSong = song
    val transport = DesktopMiniPlayerBridge.transport
    if (currentSong == null || transport == null) return
    val shape = RoundedCornerShape(percent = 50)
    // The opacity sliders only apply while a wallpaper is set; without one the pill
    // is exactly the main window's bar glass, as before.
    val glassAlpha = if (wallpaperPath.isNotBlank()) miniplayerOpacity else 1f
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .clip(shape)
                .desktopBarGlass(shape, alphaMultiplier = glassAlpha)
                .hoverable(interaction)
                .clickable(onClick = transport.onExpandMain)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DesktopArtwork(
                currentSong.thumbnailUrl,
                Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)),
                px = ROW_ART_PX,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    currentSong.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    currentSong.artist,
                    color = DesktopSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = transport.onToggleLike) {
                Icon(
                    if (liked) KnowMusicIcons.HeartFilled else KnowMusicIcons.Heart,
                    DesktopStrings["widget_like", "Like"],
                    tint = if (liked) DesktopAccent else DesktopSecondary,
                )
            }
            IconButton(onClick = transport.onPrevious) {
                Icon(Icons.Rounded.FastRewind, DesktopStrings["widget_previous", "Previous"], tint = Color.White)
            }
            IconButton(onClick = transport.onPlayPause) {
                Icon(
                    if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    if (isPlaying) DesktopStrings["widget_pause", "Pause"] else DesktopStrings["widget_play", "Play"],
                    tint = Color.White,
                )
            }
            IconButton(onClick = transport.onNext) {
                Icon(Icons.Rounded.FastForward, DesktopStrings["widget_next", "Next"], tint = Color.White)
            }
            // Lyrics slot (round 30), same hover-only reserved-slot pattern
            // as hide and close: the row never shifts, and the pill keeps
            // its 440 dp width. Tapping toggles the floating synced-lyrics
            // card; the accent tint says whether the card is open. It sits
            // before hide, so the two dismiss actions stay the rightmost.
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                if (hovered) {
                    IconButton(onClick = onToggleLyricsCard) {
                        Icon(
                            KnowMusicIcons.MusicNote,
                            DesktopStrings["miniplayer_lyrics", "Show lyrics"],
                            tint = if (lyricsCardOpen) DesktopAccent else Color.White,
                        )
                    }
                }
            }
            // Own hide slot, same pattern as the close slot: always
            // reserved, icon only while hovering, so the row never
            // shifts. Hide slides the pill to its nearest screen edge
            // (leaving a strip to hover back); close dismisses it until
            // restore — the two are deliberately separate, and close
            // stays the rightmost slot.
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                if (hovered) {
                    IconButton(onClick = onHideToEdge) {
                        Icon(Icons.Rounded.VisibilityOff, DesktopStrings["miniplayer_hide", "Hide miniplayer"], tint = Color.White)
                    }
                }
            }
            // Reserved slot, sized to the button's own footprint: the row never
            // shifts when the button appears, and nothing overflows the pill.
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                if (hovered) {
                    IconButton(onClick = { DesktopMiniPlayerBridge.hide() }) {
                        Icon(Icons.Rounded.Close, DesktopStrings["miniplayer_close", "Hide miniplayer"], tint = Color.White)
                    }
                }
            }
        }
    }
}
