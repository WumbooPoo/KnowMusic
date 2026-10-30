package com.music.knowmusic.desktop

import com.music.knowmusic.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The floating pill's window is a second native window, so it cannot read the main
 * window's composition locals or its remembered state. This holder is the bridge,
 * in the exact shape of [DesktopQuit.action]: app-scoped, written by the main
 * composable through `LaunchedEffect`, read by the pill window.
 *
 * Only snapshots cross the bridge — the current song, whether it is playing, and
 * whether it is liked — plus lambdas for the transport actions. The pill never
 * touches the playback engine directly: every action goes through the main
 * window's own functions (`togglePlayPauseFromUser`, `playNext`, `playPrevious`,
 * `toggleLike`), which are the ones that know about the party lock. A party guest
 * tapping the pill therefore gets exactly the same answer as a guest tapping the
 * sheet: locked playback stays locked.
 *
 * The pill publishes nothing to the system: no SMTC session of its own (the main
 * window already publishes one), no media-key handling, no keyboard shortcuts.
 * A space pressed while the pill has focus does nothing — that is a documented
 * limitation, not an oversight, and duplicating the handler into the second
 * window would double-toggle whenever both windows were listening.
 */
internal object DesktopMiniPlayerBridge {

    /** The transport actions, registered once by the main composable. */
    data class Transport(
        val onPlayPause: () -> Unit,
        val onNext: () -> Unit,
        val onPrevious: () -> Unit,
        val onToggleLike: () -> Unit,
        val onExpandMain: () -> Unit,
    )

    @Volatile
    var transport: Transport? = null

    private val _song = MutableStateFlow<Song?>(null)
    private val _isPlaying = MutableStateFlow(false)
    private val _liked = MutableStateFlow(false)

    /** Current song, or null when nothing is loaded (the pill hides then). */
    val song: StateFlow<Song?> = _song

    /** Whether the current song is playing. */
    val isPlaying: StateFlow<Boolean> = _isPlaying

    /** Whether the current song is in the liked set. */
    val liked: StateFlow<Boolean> = _liked

    /**
     * Current playback position in milliseconds, published by the main
     * composable from the playback engine's state (round 30). The floating
     * lyrics card reads it to pick the current line; it is a snapshot like
     * the rest of the bridge, never a handle on the engine, so the card
     * can never drive playback from it.
     */
    val positionMs = MutableStateFlow(0L)

    /**
     * Whether the main window is currently minimized, written by the main window's
     * state observer. Drives the auto-show rule, not the pill directly.
     */
    val mainMinimized = MutableStateFlow(false)

    /**
     * Transient auto-show flag: true from the moment the main window is minimized
     * with a song playing until it is restored or the pill is dismissed. It is
     * deliberately NOT persisted — a fresh launch starts with the pill off.
     */
    val autoShown = MutableStateFlow(false)

    /**
     * True from a dismiss (the pill's own close button) until the main window is
     * restored. While set, the minimize rule cannot resurrect a pill the user
     * just closed — including when playback starts while already minimized.
     * Also transient, never persisted.
     */
    val pillDismissed = MutableStateFlow(false)

    internal fun update(song: Song?, isPlaying: Boolean, liked: Boolean) {
        _song.value = song
        _isPlaying.value = isPlaying
        _liked.value = liked
    }

    /**
     * Hides the pill and keeps it hidden: the master switch goes off, the
     * auto-show flag clears, and the dismiss flag sticks until the main window
     * is restored, so the minimize rule does not immediately put it back while
     * the window is still minimized. Never quits the app.
     */
    fun hide() {
        DesktopWallpaperSettings.setMiniplayerEnabled(false)
        autoShown.value = false
        pillDismissed.value = true
    }
}
