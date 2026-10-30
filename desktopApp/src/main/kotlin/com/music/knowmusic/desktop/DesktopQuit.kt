package com.music.knowmusic.desktop

import kotlin.system.exitProcess

/**
 * The one quit sequence, shared by tray -> Quit and by closing the window.
 *
 * The composition registers the sequence once it owns the playback engine
 * (see the tray binding in DesktopApp); [quit] runs it, or plain
 * [exitProcess] if nothing was registered yet (the window cannot be closed
 * before the first composition, so that fallback is unreachable in practice).
 *
 * The sequence itself is exactly the old tray-Quit order, and the order is
 * load-bearing: engine shutdown first — its join protects the native
 * teardown (FFmpeg/WASAPI) from being unloaded underneath a running audio
 * thread — then SMTC stop, then process exit. Never route a quit through
 * exitApplication() disposal instead: release-without-join reintroduces the
 * teardown crash this order was fixed for.
 */
internal object DesktopQuit {

    @Volatile
    var action: (() -> Unit)? = null

    fun quit() {
        action?.invoke() ?: exitProcess(0)
    }
}
