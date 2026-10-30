package com.music.knowmusic.desktop

/**
 * Cross-path dedup for media transport actions.
 *
 * One physical media-key press can arrive twice: once as a Compose key event
 * (the in-app handler) and once through the Windows SMTC session. Acting on
 * both would skip two tracks per press (or toggle-then-set play/pause into
 * the opposite of the intent). This guard suppresses only the cross-path
 * duplicate: the same action from the *other* source within a short window.
 * Rapid presses from a single path still pass, and different actions never
 * suppress each other.
 *
 * The [nowMs] parameter exists for tests (default = wall clock at call time).
 */
internal object DesktopMediaActionGuard {

    const val PLAY_PAUSE = "play_pause"
    const val NEXT = "next"
    const val PREVIOUS = "previous"

    const val SOURCE_COMPOSE = "compose"
    const val SOURCE_SMTC = "smtc"

    const val WINDOW_MS = 300L

    private val lock = Any()
    private var lastAction: String? = null
    private var lastSource: String? = null
    private var lastTimeMs: Long = 0L

    /** Returns true when the action should run, false when it is a cross-path duplicate. */
    fun tryConsume(action: String, source: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        synchronized(lock) {
            val duplicate = action == lastAction && source != lastSource && nowMs - lastTimeMs < WINDOW_MS
            if (!duplicate) {
                lastAction = action
                lastSource = source
                lastTimeMs = nowMs
            }
            return !duplicate
        }
    }
}
