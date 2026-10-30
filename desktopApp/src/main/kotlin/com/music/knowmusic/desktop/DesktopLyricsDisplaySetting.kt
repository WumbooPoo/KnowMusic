package com.music.knowmusic.desktop

import com.music.knowmusic.ui.player.LyricsLargerText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Round 19 (Apple Music model): which lyric voices the panel shows, and
 * which of the original and the romanization is drawn larger.
 *
 * There is no mode any more. The original is always shown; romanization
 * and translation are independent persisted visibility switches, written
 * by the panel's globe/translate toggles and read by both panels, so the
 * two can never disagree. [LyricsLargerText] is written only by the
 * Settings "Larger text" row and only ever swaps the row's large voice
 * when the original and a Ready romanization are both on screen.
 *
 * This is the switches' one writer/reader: the panel toggles write here
 * (via [DesktopPlayerSettings]'s setters), so there is never a second,
 * per-panel copy to disagree with Settings.
 *
 * Migration from the round 16–18 single-version mode, read once at
 * startup from the legacy `lyrics_display_mode` key and written into the
 * new keys the first time an install still carries it:
 *
 * - "Romanized"  -> romanized on, translated off
 * - "Translated" -> romanized off, translated on
 * - "Combined"   -> both on (the old stacked display, which is also
 *   exactly what the new default panel shows when both switches are on)
 * - "Original", blank, or anything unrecognised -> both off (plain
 *   originals, the old off-state — never a crash)
 *
 * The legacy key is then never written again and never read again once
 * a new key exists: a new key's presence always wins over the legacy
 * value, so a user who flips a switch after migrating can never be
 * dragged back to the legacy value by a later launch. The larger-text
 * choice has no legacy equivalent and defaults to [LyricsLargerText.Lyrics].
 */
internal object DesktopLyricsDisplaySetting {

    private const val LEGACY_KEY = "lyrics_display_mode"
    private const val ROMANIZED_KEY = "lyrics_romanized_visible"
    private const val TRANSLATED_KEY = "lyrics_translated_visible"
    private const val LARGER_KEY = "lyrics_larger_text"

    private fun migrated(): Triple<Boolean, Boolean, LyricsLargerText> {
        val persistence = DesktopPersistence()
        val legacy = persistence.string(LEGACY_KEY, "")
        val romanizedStored = persistence.string(ROMANIZED_KEY, "")
        val translatedStored = persistence.string(TRANSLATED_KEY, "")
        val largerStored = persistence.string(LARGER_KEY, "")
        val romanized = if (romanizedStored.isBlank()) {
            legacy == "Romanized" || legacy == "Combined"
        } else {
            romanizedStored == "true"
        }
        val translated = if (translatedStored.isBlank()) {
            legacy == "Translated" || legacy == "Combined"
        } else {
            translatedStored == "true"
        }
        val larger = runCatching { LyricsLargerText.valueOf(largerStored) }
            .getOrDefault(LyricsLargerText.Lyrics)
        if (romanizedStored.isBlank()) {
            persistence.saveString(ROMANIZED_KEY, romanized.toString())
        }
        if (translatedStored.isBlank()) {
            persistence.saveString(TRANSLATED_KEY, translated.toString())
        }
        if (largerStored.isBlank()) {
            persistence.saveString(LARGER_KEY, larger.name)
        }
        return Triple(romanized, translated, larger)
    }

    private val initial = migrated()

    private val _romanizedVisible = MutableStateFlow(initial.first)
    private val _translatedVisible = MutableStateFlow(initial.second)
    private val _largerText = MutableStateFlow(initial.third)

    /** Whether the romanization row is switched on. */
    val romanizedVisible: StateFlow<Boolean> = _romanizedVisible

    /** Whether the translation row is switched on. */
    val translatedVisible: StateFlow<Boolean> = _translatedVisible

    /** Which voice is drawn larger when both original and romanization show. */
    val largerText: StateFlow<LyricsLargerText> = _largerText

    fun setRomanizedVisible(value: Boolean) {
        _romanizedVisible.value = value
        DesktopPersistence().saveString(ROMANIZED_KEY, value.toString())
    }

    fun setTranslatedVisible(value: Boolean) {
        _translatedVisible.value = value
        DesktopPersistence().saveString(TRANSLATED_KEY, value.toString())
    }

    fun setLargerText(value: LyricsLargerText) {
        _largerText.value = value
        DesktopPersistence().saveString(LARGER_KEY, value.name)
    }

    /** What a dropdown item says. */
    fun describeLargerText(value: LyricsLargerText): String = when (value) {
        LyricsLargerText.Lyrics -> DesktopStrings["lyrics_larger_text_lyrics", "Lyrics"]
        LyricsLargerText.Pronunciation -> DesktopStrings["lyrics_larger_text_pronunciation", "Pronunciation"]
    }
}
