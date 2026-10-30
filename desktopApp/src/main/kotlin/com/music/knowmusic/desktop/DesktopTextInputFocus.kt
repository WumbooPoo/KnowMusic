package com.music.knowmusic.desktop

/**
 * Whether any text field in the main window currently holds focus.
 *
 * Read by the root Space branch (DesktopApp's DesktopFrame preview handler):
 * while this is true, Space must type a space, never toggle playback. Every
 * text field in the desktop UI sets this from its onFocusChanged — see the
 * call sites. It is intentionally a plain volatile flag, not Compose state:
 * it is only read at key-event time, never during composition, and it must be
 * settable from files that do not share the main composable's scope.
 *
 * Scoped to Space only: other root branches (media keys, F11, Escape) keep
 * working while typing.
 */
internal object DesktopTextInputFocus {

    @Volatile
    var focused: Boolean = false
}
