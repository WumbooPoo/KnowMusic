package com.music.knowmusic.ui

/** What the current track's provider picker already knows without another request. */
enum class LyricsProviderState {
    NOT_FETCHED,
    FETCHING,
    FOUND,
    NOT_FOUND,
    /**
     * The provider did answer, but language validation rejected its lyrics
     * as Chinese for a non-Chinese track and another provider's answer won.
     * The row stays tappable: a manual pick bypasses validation, so this is
     * a skipped answer, not a missing one.
     */
    SKIPPED_LANGUAGE_MISMATCH,
}
