package com.music.knowmusic.desktop

import com.music.knowmusic.data.lyrics.LyricLine
import com.music.knowmusic.data.lyrics.LyricWord
import com.music.knowmusic.data.lyrics.toEnhancedLrc
import com.music.knowmusic.data.lyrics.toLrc
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Writer output read back by the player's own reader.
 *
 * Built from minimal hand-made containers rather than from a real download: the point is that
 * each ported tagger and [DesktopEmbeddedLyrics] agree on the layout, which a hand-built header
 * states exactly. The word-timed cases go through [toEnhancedLrc] on a real word-synced line
 * with a background vocal, so the strings under test carry word timings and a backing voice —
 * the round trip must return them byte-identical, not merely present.
 */
class DesktopLyricsTaggingTest {

    private val scratch = Files.createTempDirectory("knowmusic-tagging")

    @AfterTest
    fun cleanUp() {
        scratch.toFile().deleteRecursively()
    }

    /** One word-synced line with a background vocal answering it. */
    private val wordSynced = listOf(
        LyricLine(
            timeMs = 12_000L,
            text = "word timed",
            words = listOf(
                LyricWord(12_000L, 12_500L, "word"),
                LyricWord(12_500L, 13_000L, "timed"),
            ),
            background = LyricLine(
                timeMs = 12_500L,
                text = "backing",
                words = listOf(LyricWord(12_500L, 13_000L, "backing")),
            ),
        ),
    )

    private fun tagOnDisk(name: String, bytes: ByteArray, embeddable: DesktopLyricsTag.Embeddable): String? {
        val file = Files.write(scratch.resolve(name), bytes)
        assertTrue(DesktopLyricsTagger.tagFile(file, embeddable), "tagging $name should succeed")
        // The temp copy must not be left behind beside the finished file.
        val listed = Files.list(scratch).use { stream -> stream.map { it.fileName.toString() }.sorted().toList() }
        assertEquals(listOf(name), listed)
        return DesktopEmbeddedLyrics.read(file)
    }

    // ── MP4 / M4A ────────────────────────────────────────────────────────

    private fun mp4Box(type: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val size = 8 + payload.size
        out.write(byteArrayOf(((size shr 24) and 0xFF).toByte(), ((size shr 16) and 0xFF).toByte(), ((size shr 8) and 0xFF).toByte(), (size and 0xFF).toByte()))
        out.write(type.toByteArray(StandardCharsets.ISO_8859_1))
        out.write(payload)
        return out.toByteArray()
    }

    private fun minimalMp4(): ByteArray =
        mp4Box("ftyp", ByteArray(8)) + mp4Box("moov", ByteArray(0))

    @Test
    fun `an M4A round-trips word timings and background vocals`() {
        val enhanced = wordSynced.toEnhancedLrc()
        // Guard against a vacuous pass: the string under test must actually carry word stamps.
        assertTrue(enhanced.contains("<00:12.00>word"), "enhanced form should carry word timings")
        assertTrue(enhanced.contains("backing"), "enhanced form should carry the background vocal")
        val embeddable = DesktopLyricsTag.Embeddable(wordSynced.toLrc(), enhanced)
        assertEquals(enhanced, tagOnDisk("track.m4a", minimalMp4(), embeddable))
    }

    @Test
    fun `an M4A with line-synced lyrics round-trips the plain field`() {
        val plain = "[00:12.00]plain line"
        val embeddable = DesktopLyricsTag.Embeddable(plain, null)
        assertEquals(plain, tagOnDisk("plain.m4a", minimalMp4(), embeddable))
    }

    // ── FLAC ─────────────────────────────────────────────────────────────

    private fun minimalFlac(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray(StandardCharsets.US_ASCII))
        // Last-block bit set, type 0 (STREAMINFO), 24-bit big-endian length, 34 zero bytes.
        out.write(byteArrayOf(0x80.toByte(), 0, 0, 34))
        out.write(ByteArray(34))
        return out.toByteArray()
    }

    @Test
    fun `a FLAC round-trips word timings and background vocals`() {
        val enhanced = wordSynced.toEnhancedLrc()
        assertTrue(enhanced.contains("<00:12.00>word"))
        val embeddable = DesktopLyricsTag.Embeddable(wordSynced.toLrc(), enhanced)
        assertEquals(enhanced, tagOnDisk("track.flac", minimalFlac(), embeddable))
    }

    @Test
    fun `a FLAC with line-synced lyrics round-trips the plain field`() {
        val plain = "[00:12.00]plain line"
        val embeddable = DesktopLyricsTag.Embeddable(plain, null)
        assertEquals(plain, tagOnDisk("plain.flac", minimalFlac(), embeddable))
    }

    // ── Matroska / WebM ──────────────────────────────────────────────────

    private fun minimalWebm(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()))
        out.write(byteArrayOf(0x85.toByte())) // EBML header size: 1-byte vint, value 5.
        out.write(ByteArray(5))
        out.write(byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67))
        out.write(byteArrayOf(0xFF.toByte())) // Segment size: unknown, so appending needs no resize.
        return out.toByteArray()
    }

    @Test
    fun `a WebM round-trips word timings and background vocals`() {
        val enhanced = wordSynced.toEnhancedLrc()
        assertTrue(enhanced.contains("<00:12.00>word"))
        val embeddable = DesktopLyricsTag.Embeddable(wordSynced.toLrc(), enhanced)
        assertEquals(enhanced, tagOnDisk("track.webm", minimalWebm(), embeddable))
    }

    @Test
    fun `a WebM with line-synced lyrics round-trips the plain field`() {
        val plain = "[00:12.00]plain line"
        val embeddable = DesktopLyricsTag.Embeddable(plain, null)
        assertEquals(plain, tagOnDisk("plain.webm", minimalWebm(), embeddable))
    }

    // ── Fail-soft: the original is never touched on a miss ────────────────

    @Test
    fun `an unrecognised layout leaves the file byte-for-byte untouched`() {
        val garbage = "not a container at all".toByteArray()
        val file = Files.write(scratch.resolve("garbage.m4a"), garbage)
        assertFalse(DesktopLyricsTagger.tagFile(file, DesktopLyricsTag.Embeddable("[00:01.00]x", null)))
        assertTrue(Files.readAllBytes(file).contentEquals(garbage))
    }

    @Test
    fun `a format with no lyrics writer leaves the file untouched`() {
        val bytes = minimalFlac()
        val file = Files.write(scratch.resolve("track.mp3"), bytes)
        assertFalse(DesktopLyricsTagger.tagFile(file, DesktopLyricsTag.Embeddable("[00:01.00]x", null)))
        assertTrue(Files.readAllBytes(file).contentEquals(bytes))
    }

    @Test
    fun `a missing file is a false, not a throw`() {
        assertFalse(
            DesktopLyricsTagger.tagFile(
                scratch.resolve("nothing-here.flac"),
                DesktopLyricsTag.Embeddable("[00:01.00]x", null),
            ),
        )
    }
}
