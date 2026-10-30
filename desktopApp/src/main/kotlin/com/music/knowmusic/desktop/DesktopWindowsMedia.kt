package com.music.knowmusic.desktop

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Windows' System Media Transport Controls — the volume flyout's media card,
 * the lock screen, and the media keys.
 *
 * The Windows counterpart of [DesktopMprisController], and the same contract:
 * the player hands it state, it hands back button presses. What it talks to is
 * WinRT, which the JVM cannot reach, so the work happens in `bitchord_smtc.dll`
 * beside the analyser.
 *
 * Absent, everything here is a no-op. The library is only built by MSVC, so a
 * Linux cross-build of the Windows archive has no SMTC in it — the installers,
 * which CI builds on Windows, do.
 */
internal object DesktopWindowsMedia {

    private val started = AtomicBoolean(false)

    private val shutdownHookAdded = AtomicBoolean(false)

    @Volatile
    private var controller: Controller? = null

    /** What a media key does, filled in by the player. */
    internal class Controller(
        val onPlay: () -> Unit,
        val onPause: () -> Unit,
        val onNext: () -> Unit,
        val onPrevious: () -> Unit,
        val onStop: () -> Unit,
        /** Moves playback to a position the shell's scrubber was dragged to, in milliseconds. */
        val onSeek: (Long) -> Unit,
    )

    private val available: Boolean by lazy {
        if (!DesktopPlatform.isWindows) return@lazy false
        runCatching { DesktopAnalysisRuntime.loadNative(LIBRARY) }
            .onFailure { DesktopTrackLog.log("Windows media controls unavailable: ${it.message}") }
            .isSuccess
    }

    fun start(controller: Controller) {
        // Held whether or not the library is there, so what a button does is
        // decided in one place and can be checked without Windows.
        this.controller = controller
        if (!available || !started.compareAndSet(false, true)) return
        // A mismatched native symbol used to escape as an UnsatisfiedLinkError out of a
        // DisposableEffect; fail like the unavailable case instead (log + no session).
        val startedOk = runCatching { nativeStart() }
            .onFailure { DesktopTrackLog.log("Windows media controls failed: ${it.message}") }
            .getOrDefault(false)
        if (!startedOk) {
            started.set(false)
            DesktopTrackLog.log("Windows media controls could not be created")
            return
        }
        // Quitting from the tray is exitProcess, which never reaches the window's onDispose. The
        // library's pump thread was then still running when the DLL unloaded, and destroying a
        // joinable std::thread aborts the process — every quit ended in a native crash.
        if (shutdownHookAdded.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(Thread(::stop, "knowmusic-smtc-stop"))
        }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        controller = null
        runCatching { nativeStop() }
    }

    /** Publishes the current track. Called on every change the shell would show. */
    fun publish(state: DesktopPlaybackState) {
        if (!started.get()) return
        val song = state.song
        runCatching {
            nativeUpdate(
                song?.title.orEmpty(),
                song?.artist.orEmpty(),
                song?.albumName.orEmpty(),
                song?.thumbnailUrl.orEmpty(),
                state.isPlaying,
                state.positionMs,
                state.durationMs,
            )
        }
    }

    /** Called from the native side when a media key or the shell's card is used. */
    @JvmStatic
    fun onButton(button: Int) {
        val target = controller ?: return
        // One physical press can also arrive as a Compose key event; the guard
        // drops only that cross-path duplicate (see DesktopMediaActionGuard).
        when (button) {
            BUTTON_PLAY, BUTTON_PAUSE -> {
                if (DesktopMediaActionGuard.tryConsume(DesktopMediaActionGuard.PLAY_PAUSE, DesktopMediaActionGuard.SOURCE_SMTC)) {
                    if (button == BUTTON_PLAY) target.onPlay() else target.onPause()
                }
            }
            BUTTON_NEXT -> {
                if (DesktopMediaActionGuard.tryConsume(DesktopMediaActionGuard.NEXT, DesktopMediaActionGuard.SOURCE_SMTC)) {
                    target.onNext()
                }
            }
            BUTTON_PREVIOUS -> {
                if (DesktopMediaActionGuard.tryConsume(DesktopMediaActionGuard.PREVIOUS, DesktopMediaActionGuard.SOURCE_SMTC)) {
                    target.onPrevious()
                }
            }
            BUTTON_STOP -> target.onStop()
        }
    }

    /** Called from the native side when the shell's scrubber is dragged to a new position. */
    @JvmStatic
    fun onSeek(positionMs: Long) {
        controller?.onSeek(positionMs)
    }

    private external fun nativeStart(): Boolean
    private external fun nativeStop()
    private external fun nativeUpdate(
        title: String,
        artist: String,
        album: String,
        artUrl: String,
        isPlaying: Boolean,
        positionMs: Long,
        durationMs: Long,
    )

    private const val LIBRARY = "bitchord_smtc"

    private const val BUTTON_PLAY = 0
    private const val BUTTON_PAUSE = 1
    private const val BUTTON_NEXT = 2
    private const val BUTTON_PREVIOUS = 3
    private const val BUTTON_STOP = 4
}
