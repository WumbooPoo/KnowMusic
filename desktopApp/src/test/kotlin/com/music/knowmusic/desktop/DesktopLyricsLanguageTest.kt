package com.music.knowmusic.desktop

import com.music.knowmusic.data.lyrics.LyricLine
import com.music.knowmusic.data.lyrics.LyricsLanguage
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Language validation for the automatic lookup: what it rejects, and what it must never reject. */
class DesktopLyricsLanguageTest {

    private fun lines(vararg texts: String) = texts.mapIndexed { index, text ->
        LyricLine(timeMs = index * 1000L, text = text)
    }

    @Test
    fun japaneseWithKanaAlwaysPassesEvenWhenKanjiHeavy() {
        assertFalse(
            LyricsLanguage.isChineseCandidate(
                lines(
                    "未来の僕らが描いた空の下で",
                    "君の声がまだ心に響いてる",
                    "涙を越えて歩き出す",
                ),
            ),
        )
    }

    @Test
    fun kanjiOnlyWithNoKanaIsChineseScript() {
        assertFalse(LyricsLanguage.containsKana(lines("中国人民站起来了", "世界是我们的未来")))
        assertTrue(
            LyricsLanguage.isChineseCandidate(
                lines(
                    "中国人民站起来了",
                    "世界是我们的未来",
                    "青春岁月如歌声响",
                ),
            ),
        )
    }

    @Test
    fun titleCasedPinyinIsChineseRomanized() {
        assertTrue(
            LyricsLanguage.isChineseCandidate(
                lines(
                    "Wo Ai Ni Zhong Guo",
                    "Qing Chun Shi Jie Shui Xian",
                    "Zai Zhe Ge Mei Li De Shi Guang",
                ),
            ),
        )
    }

    @Test
    fun lowercaseRomajiPasses() {
        assertFalse(
            LyricsLanguage.isChineseCandidate(
                lines(
                    "watashi no kokoro wa anata no soba ni",
                    "mirai wo egaku sora no shita de",
                    "namida wo koete arukidasu",
                ),
            ),
        )
    }

    @Test
    fun titleCasedRomajiWordsPassBecauseTheyAreNotSingleSyllables() {
        assertFalse(
            LyricsLanguage.isChineseCandidate(
                lines(
                    "Watashi No Kokoro Wa Anata No Soba Ni",
                    "Mirai Wo Egaku Sora No Shita De",
                ),
            ),
        )
    }

    @Test
    fun englishLyricsPass() {
        assertFalse(
            LyricsLanguage.isChineseCandidate(
                lines(
                    "I love you, my heart is on fire tonight",
                    "We are singing under the same old sky",
                    "Hold on through the dark until morning light",
                ),
            ),
        )
    }

    @Test
    fun tinyOrMixedSamplesAreNeverRejected() {
        assertFalse(LyricsLanguage.isChineseCandidate(lines("Wo Ai")))
        assertFalse(LyricsLanguage.isChineseCandidate(lines("你好世界", "hello world")))
        assertFalse(LyricsLanguage.isChineseCandidate(emptyList()))
    }

    @Test
    fun kanaDetectionCoversHiraganaKatakanaAndHalfwidth() {
        assertTrue(LyricsLanguage.containsKana("あいうえお"))
        assertTrue(LyricsLanguage.containsKana("アイウエオ"))
        assertTrue(LyricsLanguage.containsKana("ｱｲｳｴｵ"))
        assertFalse(LyricsLanguage.containsKana("Siren"))
        assertFalse(LyricsLanguage.containsKana("中国"))
    }
}
