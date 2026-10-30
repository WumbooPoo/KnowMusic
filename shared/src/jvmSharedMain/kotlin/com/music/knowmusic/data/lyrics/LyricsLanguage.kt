package com.music.knowmusic.data.lyrics

/**
 * Script/language shape of a lyrics candidate, used only to reject a
 * *wrong-language* answer — never to rewrite one.
 *
 * ### Why this exists
 *
 * No provider in the chain verifies that the words it returns are in the
 * language of the track it was asked about: BetterLyrics takes whatever its
 * backend parses for a title/artist/duration, and the repository used to
 * accept the first timed answer in priority order. A Japanese track can
 * therefore be answered with Chinese lyrics — as hanzi, or as Title-Cased
 * Pinyin romanization — and, when that answer is word-synced, it won
 * outright over a correct Japanese answer from a lower-priority source.
 * Switching sources by hand fixed it, because the next source answered in
 * Japanese; this classifier automates exactly that switch, inside the race.
 *
 * ### What counts as Chinese, and what deliberately does not
 *
 * Kanji are shared between Japanese and Chinese, so kanji alone never
 * rejects anything. The two suspicious shapes are both kana-less by
 * construction, and a candidate with even one kana character anywhere is
 * Japanese and passes unconditionally:
 *
 *  - **Chinese script**: CJK ideographs with no kana and no Hangul. A
 *    Japanese lyric is kana-bearing in practice — particles and okurigana
 *    run through every verse — so a whole song with zero kana is not a
 *    Japanese lyric that happens to be kanji-heavy, it is a Chinese one.
 *    (The acknowledged residual: a genuinely kana-less Japanese lyric, e.g.
 *    a kanji-only poem, is misclassified — see the never-empty fallback in
 *    [LyricsRepository], which still shows it when nothing else answers.)
 *  - **Chinese romanization**: Latin text that is Title-Cased word after
 *    word, whose tokens are overwhelmingly single Pinyin syllables, with
 *    several syllables no Romaji word can be (zh-/q-/x-initials, tone
 *    diacritics, ü, -ong/-eng/-uang finals). Each condition alone is weak
 *    — English has "song" and "long", Romaji sites sometimes Title-Case —
 *    but together they describe the reported symptom and almost nothing
 *    else: ordinary Romaji is lowercase and its tokens are multi-syllable
 *    words ("watashi", "kokoro"), which fail the single-syllable test, and
 *    ordinary English is sentence-cased and its tokens mostly fail it too.
 *
 * Anything else — Japanese script, Korean, English, Cyrillic, Arabic, a
 * mixed or tiny sample — is not suspicious. When in doubt this returns
 * false: a false negative costs one manual source switch, a false positive
 * costs a correct lyric. The one place a false positive still cannot hurt
 * the panel is the fallback: if every candidate is suspicious (a genuine
 * Chinese song, whose providers all answer in Chinese), the first one still
 * shows — the repository only ever *prefers* a non-suspicious candidate
 * over a suspicious one, it never empties the panel for one.
 *
 * This is pure text classification: no network, no model, no provider
 * knowledge, so it can run on a candidate already in hand without costing
 * a round trip.
 */
object LyricsLanguage {

    /** Kana in a piece of text — hiragana, katakana, or halfwidth katakana. */
    fun containsKana(text: String): Boolean = text.any { it.isKana() }

    /** Kana anywhere in a candidate's lead or background lines. */
    fun containsKana(lines: List<LyricLine>): Boolean =
        lines.any { line ->
            containsKana(line.text) || (line.background?.let { containsKana(it.text) } == true)
        }

    /**
     * True when a whole candidate looks like Chinese lyrics served for a
     * non-Chinese track: hanzi with no kana, or Title-Cased Pinyin. See the
     * class comment for why kanji alone, and Latin alone, are not enough.
     */
    fun isChineseCandidate(lines: List<LyricLine>): Boolean {
        var kana = 0
        var ideographs = 0
        var hangul = 0
        var latinLetters = 0
        var totalLetters = 0
        val latinTokens = mutableListOf<String>()
        lines.forEach { line ->
            listOfNotNull(line.text, line.background?.text).forEach { text ->
                var index = 0
                while (index < text.length) {
                    val char = text[index]
                    when {
                        char.isKana() -> kana++
                        char.isIdeograph() -> ideographs++
                        char.isHangul() -> hangul++
                        char.isLatinLetter() -> latinLetters++
                    }
                    if (char.isLetter()) totalLetters++
                    index++
                }
                latinTokens += latinTokensOf(text)
            }
        }
        // Any kana at all proves Japanese; any Hangul proves the candidate is
        // not a pure Chinese answer either (a mixed-script guide was already
        // stripped upstream, so what remains is content, not clutter).
        if (kana > 0 || hangul > 0) return false
        // Hanzi with no kana: a whole song's worth of ideographs, not a
        // stray CJK character in an otherwise Latin lyric.
        if (ideographs >= MIN_IDEOGRAPHS && ideographs >= latinLetters) return true
        // Latin only, and only in the exact reported shape.
        if (totalLetters < MIN_LATIN_LETTERS || latinTokens.size < MIN_LATIN_TOKENS) return false
        if (latinLetters * 5 < totalLetters * 4) return false
        val capitalized = latinTokens.count { it.isTitleCased() }
        if (capitalized * 5 < latinTokens.size * 3) return false
        val pinyin = latinTokens.count { it.isPinyinSyllable() }
        if (pinyin * 2 < latinTokens.size) return false
        return latinTokens.count { it.isPinyinDistinctive() } >= MIN_DISTINCTIVE_TOKENS
    }

    private const val MIN_IDEOGRAPHS = 8
    private const val MIN_LATIN_LETTERS = 40
    private const val MIN_LATIN_TOKENS = 10
    private const val MIN_DISTINCTIVE_TOKENS = 3

    private fun Char.isKana(): Boolean =
        this in '\u3040'..'\u309F' || this in '\u30A0'..'\u30FF' || this in '\uFF66'..'\uFF9D'

    private fun Char.isIdeograph(): Boolean =
        this in '\u3400'..'\u4DBF' || this in '\u4E00'..'\u9FFF' || this in '\uF900'..'\uFAFF'

    private fun Char.isHangul(): Boolean =
        this in '\uAC00'..'\uD7A3' || this in '\u1100'..'\u11FF' || this in '\u3130'..'\u318F'

    private fun Char.isLatinLetter(): Boolean =
        isLetter() && Character.UnicodeScript.of(code) == Character.UnicodeScript.LATIN

    /** Latin-script words in a line; every other script's words are ignored. */
    private fun latinTokensOf(text: String): List<String> =
        text.split(Regex("[^\\p{L}]+"))
            .filter { token ->
                token.isNotEmpty() && token.all { it.isLatinLetter() }
            }

    private fun String.isTitleCased(): Boolean =
        first().isUpperCase() && drop(1).none { it.isUpperCase() }

    /**
     * One Pinyin syllable, tone marks stripped: an optional initial plus one
     * final from the Pinyin inventory. Multi-syllable Romaji words
     * ("watashi") and most English words ("love", "heart", "night") fail
     * this outright, which is what separates Pinyin's spaced syllables from
     * both. Single letters and particles ("a", "wo", "ni") pass — they are
     * ambiguous alone, which is why the caller also requires the
     * capitalization ratio and several distinctive syllables.
     */
    private fun String.isPinyinSyllable(): Boolean {
        val plain = lowercase().withoutToneMarks()
        return PINYIN_SYLLABLE.matches(plain)
    }

    /**
     * A Pinyin syllable no Romaji word can be: a zh-/q-/x-/ch-/sh-/j-/r-/
     * z-/c-/s-initial (Romaji's "shi"/"chi" are words, not standalone
     * syllable tokens — but a spaced-syllable lyric's "shi" is a syllable,
     * and a Romaji lyric never spaces its syllables), a tone diacritic, ü,
     * or a nasal final Romaji cannot end a syllable with. "song" and "long"
     * count here, which is why this is only one leg of the test, never the
     * whole of it.
     */
    private fun String.isPinyinDistinctive(): Boolean {
        if (any { it.isToneMark() || it == 'ü' || it == 'Ü' }) return true
        val plain = lowercase().withoutToneMarks()
        if (plain.startsWith("zh") || plain.startsWith("q") || plain.startsWith("x") ||
            plain.startsWith("ch") || plain.startsWith("sh") || plain.startsWith("j") ||
            plain.startsWith("r") || plain.startsWith("z") || plain.startsWith("c") ||
            plain.startsWith("s")
        ) return true
        return plain.endsWith("ong") || plain.endsWith("eng") ||
            plain.endsWith("uang") || plain.endsWith("iang") || plain.endsWith("iong")
    }

    private fun Char.isToneMark(): Boolean =
        this in "áàǎāéèěēíìǐīóòǒōúùǔūǖǘǚǜ"

    private fun String.withoutToneMarks(): String = buildString {
        forEach { char ->
            append(
                when (char) {
                    'á', 'à', 'ǎ', 'ā' -> 'a'
                    'é', 'è', 'ě', 'ē' -> 'e'
                    'í', 'ì', 'ǐ', 'ī' -> 'i'
                    'ó', 'ò', 'ǒ', 'ō' -> 'o'
                    'ú', 'ù', 'ǔ', 'ū' -> 'u'
                    'ǖ', 'ǘ', 'ǚ', 'ǜ' -> 'ü'
                    else -> char
                },
            )
        }
    }

    private val PINYIN_SYLLABLE = Regex(
        """^(zh|ch|sh|b|p|m|f|d|t|n|l|g|k|h|j|q|x|r|z|c|s|y|w)?""" +
            """(iang|iong|uang|uai|uan|iao|ian|ing|ang|eng|ong|ai|ei|ao|ou|an|en|er|ia|ie|iu|in|ua|uo|ui|un|ün|a|o|e|i|u|ü)$""",
    )
}
