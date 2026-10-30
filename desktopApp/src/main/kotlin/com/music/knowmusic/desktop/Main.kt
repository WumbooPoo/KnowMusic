package com.music.knowmusic.desktop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import com.music.knowmusic.ui.player.PlayerPlatform
import com.music.knowmusic.data.DebugLog
import com.music.knowmusic.data.TrackLog
import com.music.knowmusic.data.innertube.InnerTubeXResolver
import com.music.knowmusic.data.innertube.StreamResolver
import com.music.knowmusic.data.innertube.potoken.PoTokenGenerator
import com.music.knowmusic.data.lyrics.LyricsTranslation
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import knowmusic.desktopapp.generated.resources.Res
import knowmusic.desktopapp.generated.resources.logo
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.Toolkit
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.painterResource

fun main() {
    // JavaCPP's decoder gate reads the WHOLE process working set (JVM + Skiko
    // + JavaFX WebView), not just its own allocations, and its default cap is
    // derived from the heap — 1024M here. A normal session (one song, a
    // PoToken WebView, browsed artwork) legitimately passes that, and from
    // then on every decoder open fails with "Physical memory usage is too
    // high" until the process restarts, for every source including local
    // files. Raise the ceiling to 3 GiB: a ceiling, not a reservation — it
    // only stops runaway growth, well short of this machine's 8 GB. Set here,
    // first, before any JavaCPP class loads, and again as a JVM arg in
    // desktopApp/build.gradle.kts so it also lands in KnowMusic.cfg.
    System.setProperty("org.bytedeco.javacpp.maxphysicalbytes", "3221225472")
    // Before any WebView exists, so the sign-in window can start over with an empty jar.
    DesktopWebCookies.install()
    DesktopWindowVisibility.install()
    // The player is the phone's, from the shared UI module; this is what it reads underneath.
    PlayerPlatform.install(DesktopPlayerHost)
    com.music.knowmusic.ui.AppUi.install(DesktopAppUiHost)
    // The data layer both apps share logs through here, and keeps translated
    // lyrics beside the rest of the desktop's cache.
    DebugLog.sink = DebugLog.Sink { level, tag, message, error ->
        DesktopTrackLog.log("$tag/$level: $message" + (error?.let { " (${it.message})" } ?: ""))
    }
    LyricsTranslation.cacheDir = DesktopMediaCache.directory.toFile()
    // Desktop follows its own language picker (zh-Hant / zh-Hans preserved).
    com.music.knowmusic.data.innertube.Innertube.appLanguage = { DesktopStrings.resolvedTag() }
    // YouTube playback is the phone's StreamResolver over InnerTubeX, with BotGuard PoTokens
    // minted in JavaFX's WebView where the phone uses Android's.
    TrackLog.echo = { level, tag, message, error ->
        DesktopTrackLog.log("$tag/$level: $message" + (error?.let { " (${it.message})" } ?: ""))
    }
    StreamResolver.maxKbps = {
        DesktopStreamClient.ceiling.get() ?: DesktopSourceRegistry.ceiling(null).maxKbps
    }
    InnerTubeXResolver.init(
        filesDir = DesktopMediaCache.directory.toFile(),
        store = DesktopInnerTubeXStore,
        poTokenProvider = PoTokenGenerator(
            createMinter = { DesktopPoTokenWebView.getNewPoTokenGenerator() },
            available = { DesktopPoTokenWebView.available },
        ).asTokenProvider(),
    )
    // The public "apps open right now" count; see Presence.
    DesktopPresence.install()
    desktopMain()
}

private fun desktopMain() = application {
    // Closing the window quits the app (see DesktopQuit); the tray icon is for
    // controls and restore while the window is open, not a place the app hides
    // after closing.
    val visible by DesktopWindowVisibility.visible.collectAsState()
    val state = rememberWindowState(width = 1_080.dp, height = 680.dp)
    LaunchedEffect(visible) {
        if (visible && state.isMinimized) {
            state.isMinimized = false
        }
    }
    // Hoisted so the player can fill the screen and the caption buttons can maximize — see
    // [DesktopWindowMode]. Fullscreen on Windows is a native borderless window that covers the
    // taskbar, so it needs no placement change here; only ordinary maximize does.
    val placement by DesktopWindowMode.placement.collectAsState()
    LaunchedEffect(placement) { state.placement = placement }
    // ...and back, for the times the window is moved between placements by something that is not
    // us; see [DesktopWindowMode.adopt].
    LaunchedEffect(state) {
        snapshotFlow { state.placement }.collect(DesktopWindowMode::adopt)
    }
    // The floating pill's auto-show rule reads this: minimized with a song
    // playing shows the pill, restore hides it again (unless its own switch
    // is on). Written here, next to the placement observer, because this is
    // the one place that sees the native window state change.
    LaunchedEffect(state) {
        snapshotFlow { state.isMinimized }.collect { DesktopMiniPlayerBridge.mainMinimized.value = it }
    }
    val maximized by DesktopWindowMode.maximized.collectAsState()
    LaunchedEffect(maximized) {
        if (DesktopPlatform.isMac) {
            DesktopMacFrame.updateCornerRadius(maximized)
        }
    }
    Window(
        onCloseRequest = { DesktopQuit.quit() },
        visible = visible,
        title = "KnowMusic",
        icon = painterResource(Res.drawable.logo),
        state = state,
        // No system title bar on macOS, where the application draws its own instead — see
        // [DesktopWindowChrome]. Windows uses the system title bar, so the window behaves
        // like an ordinary desktop app (drag, resize, snap, minimize/maximize/close).
        undecorated = DesktopPlatform.drawsOwnWindowFrame,
        // For Mica and Acrylic behind the sidebar and top bar: only a transparent window lets
        // DWM's material show through the chrome. Fixed at creation, so it is on wherever the
        // material is possible and the chosen one can be switched live — see [DesktopWindowBackdrop].
        transparent = DesktopWindowBackdrop.available,
        resizable = true,
    ) {
        val composeWindow = window
        val openingSize = remember { state.size }
        LaunchedEffect(composeWindow) {
            DesktopWindowVisibility.attachWindow(composeWindow)
            // AWT's default is white, and it is what shows for the frame or two a moved or resized
            // window takes to repaint: a white band along its edges.
            // Not over a transparent window, whose clear background is what the material shows
            // through; DWM moves that window, so there is no band to hide.
            if (!DesktopWindowBackdrop.available) {
                composeWindow.background = java.awt.Color.BLACK
                composeWindow.contentPane.background = java.awt.Color.BLACK
            } else {
                composeWindow.background = java.awt.Color(0, 0, 0, 0)
                composeWindow.contentPane.background = java.awt.Color(0, 0, 0, 0)
                (composeWindow.contentPane as? javax.swing.JComponent)?.isOpaque = false
                composeWindow.rootPane.isOpaque = false
            }
            // AWT measures this in device pixels while Compose's window state is in dp. Keeping
            // the scale in the conversion makes the usable minimum consistent on every display.
            val transform = composeWindow.graphicsConfiguration.defaultTransform
            composeWindow.minimumSize = Dimension(
                (MIN_WINDOW_WIDTH_DP * transform.scaleX).roundToInt(),
                (MIN_WINDOW_HEIGHT_DP * transform.scaleY).roundToInt(),
            )
            // Setting AWT's minimum during the first composition can resize the native peer to
            // that minimum without updating Compose's state. Compose would then keep laying out a
            // 1080dp surface inside a 900dp window, cropping the right edge and putting its resize
            // boundary off-screen until a maximize/restore cycle synchronised the two. Re-assert
            // the requested opening size on the native peer so both layers start in agreement.
            composeWindow.size = Dimension(
                (openingSize.width.value * transform.scaleX).roundToInt(),
                (openingSize.height.value * transform.scaleY).roundToInt(),
            )
            // Never open past the monitor's usable work area (taskbar excluded), whatever the
            // display scale: a window that starts off-screen can only be rescued by shortcuts.
            val displayBounds = composeWindow.graphicsConfiguration.bounds
            val displayInsets = Toolkit.getDefaultToolkit().getScreenInsets(composeWindow.graphicsConfiguration)
            val workWidth = displayBounds.width - displayInsets.left - displayInsets.right
            val workHeight = displayBounds.height - displayInsets.top - displayInsets.bottom
            val clampedWidth = minOf(composeWindow.size.width, workWidth)
            val clampedHeight = minOf(composeWindow.size.height, workHeight)
            if (clampedWidth != composeWindow.size.width || clampedHeight != composeWindow.size.height) {
                composeWindow.size = Dimension(clampedWidth, clampedHeight)
            }
            // Center the window in the usable work area: a fresh launch should land
            // in the middle of the screen, not cascaded off to one side.
            composeWindow.setLocation(
                displayBounds.x + displayInsets.left + (workWidth - clampedWidth) / 2,
                displayBounds.y + displayInsets.top + (workHeight - clampedHeight) / 2,
            )
            if (DesktopPlatform.isWindows && DesktopPlatform.drawsOwnWindowFrame && DesktopWindowsFrame.install("KnowMusic")) {
                DesktopWindowBackdrop.apply()
            } else if (DesktopPlatform.isMac && DesktopMacFrame.install(composeWindow)) {
                DesktopWindowBackdrop.apply()
            }
        }
        val actions = remember {
            DesktopWindowActions(
                minimize = {
                    if (!DesktopWindowsFrame.minimize()) state.isMinimized = true
                },
                toggleMaximize = {
                    if (!DesktopWindowsFrame.toggleMaximize()) {
                        // An undecorated AWT window otherwise maximizes to the monitor bounds on
                        // Windows when the native frame bridge is unavailable. Give AWT the
                        // monitor's usable work area before Compose switches the placement.
                        if (DesktopPlatform.isWindows && !DesktopWindowMode.maximized.value) {
                            val screen = composeWindow.graphicsConfiguration.bounds
                            val insets = Toolkit.getDefaultToolkit().getScreenInsets(
                                composeWindow.graphicsConfiguration,
                            )
                            composeWindow.maximizedBounds = Rectangle(
                                screen.x + insets.left,
                                screen.y + insets.top,
                                screen.width - insets.left - insets.right,
                                screen.height - insets.top - insets.bottom,
                            )
                        }
                        DesktopWindowMode.toggleMaximized()
                    }
                },
                // Closing quits, exactly like the system's close button (see DesktopQuit).
                close = { DesktopQuit.quit() },
            )
        }
        CompositionLocalProvider(
            LocalDesktopWindowActions provides actions,
            LocalDesktopWindowScope provides this,
        ) {
            KnowMusicDesktopApp()
        }
    }
    // Auto-show state machine for the pill (round 23). The pill appears on its
    // own when the main window is minimized with a song playing — whether it
    // was already playing at minimize time or starts while minimized — and
    // hides again on restore. Pausing while minimized does NOT hide it (the
    // user may have paused from the pill itself, and it must stay to resume
    // from), and dismissing with the pill's own close button sticks until
    // restore. The master switch is independent: when it is on, the pill stays
    // whatever the window does.
    LaunchedEffect(Unit) {
        var wasMinimized = false
        var wasPlaying = false
        combine(
            DesktopMiniPlayerBridge.mainMinimized,
            DesktopMiniPlayerBridge.song,
            DesktopMiniPlayerBridge.isPlaying,
        ) { minimized, song, playing -> Triple(minimized, song, playing) }
            .collect { (minimized, song, playing) ->
                if (!minimized) {
                    if (wasMinimized) {
                        DesktopMiniPlayerBridge.autoShown.value = false
                        DesktopMiniPlayerBridge.pillDismissed.value = false
                    }
                } else if (song != null && playing && !DesktopMiniPlayerBridge.pillDismissed.value) {
                    if (!wasMinimized || !wasPlaying) {
                        DesktopMiniPlayerBridge.autoShown.value = true
                    }
                }
                wasMinimized = minimized
                wasPlaying = playing
            }
    }
    // The floating pill window (round 23) — smoke-test-first structure: this
    // window exists independently of the bridge, and its content renders
    // nothing until the main composable registers song + transport. Closing
    // it only hides the pill; it never quits the app. Its content has no haze
    // scope, so its glass is the solid bar-glass fallback — the pill floats
    // over other apps, where the main window's haze could not apply anyway.
    val pillSong by DesktopMiniPlayerBridge.song.collectAsState()
    val pillEnabled by DesktopWallpaperSettings.miniplayerEnabled.collectAsState()
    val pillAutoShown by DesktopMiniPlayerBridge.autoShown.collectAsState()
    val pillVisible = (pillEnabled || pillAutoShown) && pillSong != null
    val pillState = rememberWindowState(width = 440.dp, height = 64.dp)
    // Floating lyrics card (round 30): toggled by the pill's lyrics
    // button, open until closed from the card itself, the pill button
    // again, or the window's close request. It is not persisted — a
    // fresh launch starts with the card closed, like the pill's
    // auto-show flag.
    var lyricsCardOpen by remember { mutableStateOf(false) }
    val lyricsCardVisible = lyricsCardOpen && pillSong != null
    val lyricsCardState = rememberWindowState(width = 400.dp, height = 160.dp)
    // Round 32: the card reports whether a romaji row exists, and the
    // window grows to fit the second row (220 dp) or stays compact
    // (160 dp) — so a Latin-only song never leaves a tall empty card.
    var cardHasRomaji by remember { mutableStateOf(false) }
    LaunchedEffect(cardHasRomaji) {
        lyricsCardState.size = DpSize(400.dp, if (cardHasRomaji) 220.dp else 160.dp)
    }
    // Round 31: where the pill actually sits when shown, recorded by
    // the pill's own placement effect. The card reads this instead of
    // recomputing the centre itself, so it lands exactly under the
    // pill whatever display or insets the pill was placed on.
    var pillShownSpot by remember { mutableStateOf<IntOffset?>(null) }
    var pillShownSize by remember { mutableStateOf<IntSize?>(null) }
    Window(
        onCloseRequest = { DesktopMiniPlayerBridge.hide() },
        visible = pillVisible,
        title = "KnowMusic",
        state = pillState,
        undecorated = true,
        transparent = true,
        resizable = false,
    ) {
        val pillWindow = window
        // Shared pill geometry, computed once per composition from the
        // same graphics configuration the placement math already used.
        // The placement effect and the hide animation below both read
        // these, so the shown, hidden and dragged positions can never
        // disagree about the screen they are on. (A display-config
        // change mid-session is the one exception: like the placement
        // itself, these are not recomputed until restart.)
        val pillGc = pillWindow.graphicsConfiguration
        val pillTransform = pillGc.defaultTransform
        val pillDisplayBounds = pillGc.bounds
        val pillDisplayInsets = Toolkit.getDefaultToolkit().getScreenInsets(pillGc)
        val pillWorkWidth = pillDisplayBounds.width - pillDisplayInsets.left - pillDisplayInsets.right
        val pillWidthPx = (pillState.size.width.value * pillTransform.scaleX).roundToInt()
        val pillHeightPx = (pillState.size.height.value * pillTransform.scaleY).roundToInt()
        val pillCenterX = pillDisplayBounds.x + pillDisplayInsets.left + (pillWorkWidth - pillWidthPx) / 2
        val pillShownY = pillDisplayBounds.y + pillDisplayInsets.top + (MINIPLAYER_TOP_GAP_DP * pillTransform.scaleY).roundToInt()
        // Only this much of the pill stays on screen when hidden, on
        // whichever edge it hid to.
        val pillStripPx = (MINIPLAYER_STRIP_DP * pillTransform.scaleY).roundToInt()
        LaunchedEffect(pillWindow) {
            // Compose's Window exposes no always-on-top or top-centre placement
            // in this repo's usage, so both go through AWT, the same way the
            // main window's own placement already does above. Sizes come from
            // the Compose state, not the native peer, so the math holds even
            // if the native window has not been sized yet.
            pillWindow.isAlwaysOnTop = true
            pillWindow.setLocation(pillCenterX, pillShownY)
            pillWindow.minimumSize = Dimension(pillWidthPx, pillHeightPx)
            // Round 31: record where the pill actually sits when shown,
            // so the lyrics card can anchor exactly under it.
            pillShownSpot = IntOffset(pillCenterX, pillShownY)
            pillShownSize = IntSize(pillWidthPx, pillHeightPx)
        }
        // Hide-to-edge state (round 24, Crm 08:30 WIB 26 Oct). pillHideTarget
        // == null means "shown": the window sits at pillPreHide, or at the
        // default top-centre spot when nothing was hidden yet. Non-null means
        // "hidden": the window sits at that edge position, leaving only a
        // MINIPLAYER_STRIP_DP strip on screen. The pill's own hide button
        // (in DesktopMiniPlayerWindow) records where the pill is now and
        // picks the nearest edge — top, left, or right, never the bottom:
        // a low pill hides to its nearest horizontal side instead (left
        // half of the screen -> left, right half -> right; ties go left).
        // The default top-centre spot is nearest the top, so a
        // never-dragged pill hides upward, as originally planned. Reveal
        // returns to the recorded pre-hide spot, never to top-centre.
        // Restart resets to the shown spot (this state is deliberately
        // not persisted).
        val pillAnimX = remember { Animatable(pillCenterX.toFloat()) }
        val pillAnimY = remember { Animatable(pillShownY.toFloat()) }
        // Shared by the hide sync below and the drag handler: both mutate
        // the same Animatable values, so one scope owns all of it.
        val pillDragScope = rememberCoroutineScope()
        var pillPreHide by remember { mutableStateOf<IntOffset?>(null) }
        var pillHideTarget by remember { mutableStateOf<IntOffset?>(null) }
        // Round 25 (Crm 16:01 WIB 26 Oct: pill at top-centre hid to the
        // RIGHT instead of up). Root cause class, not a wrong distance
        // formula: the old code read the pill's position from the
        // remembered Animatable values, but the native window has other
        // movers — the one-shot setLocation at first show, and the OS's
        // own placement when the window first appears — which never
        // update those values. Whenever the remembered values disagreed
        // with where the pill visibly sat, the "nearest edge" was
        // computed from the wrong spot and the pill slid the wrong way.
        // The fix makes the native window the source of truth: the
        // direction is computed from pillWindow.x/y/width/height at
        // click time, the Animatable values are snapped to that spot
        // first (so the slide starts exactly where the pill visibly
        // is, with no jump), and only then is the edge target set.
        val pillHideToEdge: () -> Unit = {
            if (pillHideTarget == null) {
                val curX = pillWindow.x
                val curY = pillWindow.y
                val curW = pillWindow.width
                val curH = pillWindow.height
                pillPreHide = IntOffset(curX, curY)
                val workLeft = pillDisplayBounds.x + pillDisplayInsets.left
                val workTop = pillDisplayBounds.y + pillDisplayInsets.top
                val distTop = curY - workTop
                val distLeft = curX - workLeft
                val distRight = workLeft + pillWorkWidth - (curX + curW)
                val target = when {
                    distTop <= distLeft && distTop <= distRight ->
                        IntOffset(curX, workTop + pillStripPx - curH)
                    distLeft <= distRight ->
                        IntOffset(workLeft + pillStripPx - curW, curY)
                    else ->
                        IntOffset(workLeft + pillWorkWidth - pillStripPx, curY)
                }
                // stop() di DALAM launch: di versi Compose ini stop()
                // adalah suspend function, jadi tidak boleh dipanggil
                // dari badan lambda biasa — hanya dari dalam coroutine.
                pillDragScope.launch {
                    pillAnimX.stop()
                    pillAnimY.stop()
                    pillAnimX.snapTo(curX.toFloat())
                    pillAnimY.snapTo(curY.toFloat())
                    pillHideTarget = target
                }
            }
        }
        // Reveal: hovering the visible strip slides the pill back. The
        // strip is part of the window, so hovering it still reaches this
        // window's hover detector even though most of the window is
        // off-screen. The pointer can never already be over the strip
        // right after a hide: the hide button sits ~72 dp from the
        // pill's edge, so the pointer ends up clear of the strip once
        // the pill has slid away.
        val pillHoverInteraction = remember { MutableInteractionSource() }
        val pillHovered by pillHoverInteraction.collectIsHoveredAsState()
        LaunchedEffect(pillHovered) {
            if (pillHovered && pillHideTarget != null) {
                pillHideTarget = null
            }
        }
        // The slide itself: one effect owns both axes, and the AWT window
        // moves per frame from these Animatable values — never a single
        // setLocation jump, and never an offset of the content inside the
        // window. Only one axis ever changes per hide/reveal (top hides
        // move Y only, side hides move X only), so the sequential
        // animateTo calls still read as a single straight slide; the
        // unchanged axis returns immediately.
        // Keyed on pillHideTarget ONLY (round 25): the hide lambda sets
        // pillPreHide synchronously and then snaps the Animatable values
        // before setting the target, so the effect must not restart on
        // the preHide change alone — that restart would animate from
        // the stale values and jump the window before the sync lands.
        // Reveal still works: hovering the strip nulls pillHideTarget,
        // which restarts this effect with pillPreHide as its target.
        LaunchedEffect(pillHideTarget) {
            val target = pillHideTarget ?: pillPreHide ?: IntOffset(pillCenterX, pillShownY)
            pillAnimX.animateTo(target.x.toFloat(), tween(300, easing = FastOutSlowInEasing))
            pillAnimY.animateTo(target.y.toFloat(), tween(300, easing = FastOutSlowInEasing))
        }
        // Every animated value lands on the native window, so the slide
        // effect is the only writer of the pill's position.
        LaunchedEffect(pillAnimX, pillAnimY) {
            snapshotFlow { pillAnimX.value to pillAnimY.value }.collect { (x, y) ->
                pillWindow.setLocation(x.roundToInt(), y.roundToInt())
            }
        }
        MaterialTheme(colorScheme = desktopColorScheme(), typography = desktopTypography()) {
            CompositionLocalProvider(LocalContentColor provides Color.White) {
                // Pill hide-to-edge (round 24, Crm 08:30 WIB 26 Oct).
                // Drag was removed (round 29, Crm 01:17 WIB 27 Okt: still
                // glitching after three fixes) — the pill stays at its
                // default top-centre spot, and hiding is the only way it
                // leaves that spot. The Box carries the strip-hover
                // detector (reveal); nothing here ever hides or moves
                // on its own.
                Box(
                    Modifier
                        .fillMaxSize()
                        .hoverable(pillHoverInteraction)
                ) {
                    DesktopMiniPlayerWindow(
                        onHideToEdge = pillHideToEdge,
                        lyricsCardOpen = lyricsCardOpen,
                        onToggleLyricsCard = { lyricsCardOpen = !lyricsCardOpen },
                    )
                }
            }
        }
    }
    // Floating synced-lyrics card window (round 30, revised round 31):
    // the third window, same undecorated, transparent, always-on-top
    // shape as the pill. Round 31 anchors it to the pill's recorded
    // shown spot instead of recomputing the centre: same centre line
    // as the pill, its top edge one small gap under the pill's bottom
    // edge, so it sits exactly below the pill. It has no drag and no
    // slide: the position is set when it opens and never written
    // again, except that the effect re-runs once the pill's spot is
    // recorded. If the spot is somehow still unknown, it falls back
    // to the round-30 centred math. One log line records the placed
    // rectangle, so a future geometry complaint can be checked
    // against numbers instead of screenshots.
    Window(
        onCloseRequest = { lyricsCardOpen = false },
        visible = lyricsCardVisible,
        title = "KnowMusic Lyrics",
        state = lyricsCardState,
        undecorated = true,
        transparent = true,
        resizable = false,
    ) {
        val cardWindow = window
        val cardGc = cardWindow.graphicsConfiguration
        val cardTransform = cardGc.defaultTransform
        val cardDisplayBounds = cardGc.bounds
        val cardDisplayInsets = Toolkit.getDefaultToolkit().getScreenInsets(cardGc)
        val cardWorkWidth = cardDisplayBounds.width - cardDisplayInsets.left - cardDisplayInsets.right
        val cardWidthPx = (lyricsCardState.size.width.value * cardTransform.scaleX).roundToInt()
        val cardHeightPx = (lyricsCardState.size.height.value * cardTransform.scaleY).roundToInt()
        val cardGapPx = (MINIPLAYER_CARD_GAP_DP * cardTransform.scaleY).roundToInt()
        val spot = pillShownSpot
        val size = pillShownSize
        val cardX = if (spot != null && size != null) {
            spot.x + (size.width - cardWidthPx) / 2
        } else {
            cardDisplayBounds.x + cardDisplayInsets.left + (cardWorkWidth - cardWidthPx) / 2
        }
        val cardY = if (spot != null && size != null) {
            spot.y + size.height + cardGapPx
        } else {
            cardDisplayBounds.y + cardDisplayInsets.top +
                (MINIPLAYER_TOP_GAP_DP * cardTransform.scaleY).roundToInt() +
                (64.0 * cardTransform.scaleY).roundToInt() +
                cardGapPx
        }
        LaunchedEffect(cardWindow, pillShownSpot, pillShownSize) {
            cardWindow.isAlwaysOnTop = true
            cardWindow.setLocation(cardX, cardY)
            cardWindow.minimumSize = Dimension(cardWidthPx, cardHeightPx)
            DesktopTrackLog.log("  lyrics card placed: x=$cardX y=$cardY w=$cardWidthPx h=$cardHeightPx")
        }
        MaterialTheme(colorScheme = desktopColorScheme(), typography = desktopTypography()) {
            CompositionLocalProvider(LocalContentColor provides Color.White) {
                Box(Modifier.fillMaxSize()) {
                    DesktopLyricsCardWindow(
                        onClose = { lyricsCardOpen = false },
                        onRomajiAvailable = { cardHasRomaji = it },
                    )
                }
            }
        }
    }
}

private const val MIN_WINDOW_WIDTH_DP = 900.0
private const val MIN_WINDOW_HEIGHT_DP = 600.0

/** Gap from the top work-area edge to the floating pill when shown, in dp. 6 dp is
 * almost flush with the top without quite touching it (Crm 08:30 WIB 26 Oct). */
private const val MINIPLAYER_TOP_GAP_DP = 6.0

/** How much of the pill stays visible on screen when hidden to an edge, in dp. */
private const val MINIPLAYER_STRIP_DP = 12.0

/** Gap between the pill's bottom edge and the floating lyrics card, in dp. */
private const val MINIPLAYER_CARD_GAP_DP = 8.0
