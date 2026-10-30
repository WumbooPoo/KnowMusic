package com.music.knowmusic.desktop

import com.music.knowmusic.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopParityTest {
    @Test
    fun losslessFormatCarriesDisplayMetadata() {
        val format = DesktopStreamFormat(codec = "flac", sampleRateHz = 96_000, bitDepth = 24)

        assertTrue(format.isLossless)
        assertEquals("FLAC · 24-bit · 96 kHz", format.summary)
    }

    @Test
    fun localLibrarySortKeepsStableTitleTieBreakers() {
        val songs = listOf(
            Song("b", "Beta", "Artist", null, localDateAddedSeconds = 2),
            Song("a", "alpha", "Artist", null, localDateAddedSeconds = 1),
        )

        assertEquals(listOf("alpha", "Beta"), songs.sortedWithDesktopSort(DesktopSort.TITLE_ASC).map(Song::title))
        // The old DATE_ADDED (newest first) lives on as DATE_ADDED_DESC.
        assertEquals(listOf("Beta", "alpha"), songs.sortedWithDesktopSort(DesktopSort.DATE_ADDED_DESC).map(Song::title))
    }

    @Test
    fun playlistOrderSortKeepsAndReversesStoredOrder() {
        val songs = listOf(
            Song("c", "Charlie", "Artist", null),
            Song("a", "Alpha", "Artist", null),
            Song("b", "Bravo", "Artist", null),
        )

        assertEquals(listOf("c", "a", "b"), songs.sortedWithDesktopSort(DesktopSort.PLAYLIST_ORDER_ASC).map(Song::videoId))
        assertEquals(listOf("b", "a", "c"), songs.sortedWithDesktopSort(DesktopSort.PLAYLIST_ORDER_DESC).map(Song::videoId))
    }

    @Test
    fun artistSortUsesCombinedArtistStringWithTitleTieBreak() {
        val songs = listOf(
            Song("1", "Zulu", "Beta", null),
            Song("2", "Alpha", "Beta", null),
            Song("3", "Mid", "alpha", null),
        )

        // Case-insensitive on the combined byline; same artist falls back to title.
        assertEquals(listOf("Mid", "Alpha", "Zulu"), songs.sortedWithDesktopSort(DesktopSort.ARTIST_ASC).map(Song::title))
        assertEquals(listOf("Alpha", "Zulu", "Mid"), songs.sortedWithDesktopSort(DesktopSort.ARTIST_DESC).map(Song::title))
    }

    @Test
    fun albumSortKeepsNullsLastInBothDirections() {
        val songs = listOf(
            Song("1", "B-song", "Artist", null, albumName = "Zebra"),
            Song("2", "A-song", "Artist", null, albumName = null),
            Song("3", "C-song", "Artist", null, albumName = "Apple"),
        )

        assertEquals(listOf("C-song", "B-song", "A-song"), songs.sortedWithDesktopSort(DesktopSort.ALBUM_ASC).map(Song::title))
        // DESC reverses the named albums, never the nulls: null stays last.
        assertEquals(listOf("B-song", "C-song", "A-song"), songs.sortedWithDesktopSort(DesktopSort.ALBUM_DESC).map(Song::title))
    }

    @Test
    fun dateSortKeepsNullsLastInBothDirections() {
        val added = listOf(
            Song("1", "New", "Artist", null, localDateAddedSeconds = 10),
            Song("2", "Undated", "Artist", null, localDateAddedSeconds = null),
            Song("3", "Old", "Artist", null, localDateAddedSeconds = 5),
        )
        assertEquals(listOf("Old", "New", "Undated"), added.sortedWithDesktopSort(DesktopSort.DATE_ADDED_ASC).map(Song::title))
        assertEquals(listOf("New", "Old", "Undated"), added.sortedWithDesktopSort(DesktopSort.DATE_ADDED_DESC).map(Song::title))

        val modified = listOf(
            Song("1", "Mid", "Artist", null, localDateModifiedSeconds = 3),
            Song("2", "Undated", "Artist", null, localDateModifiedSeconds = null),
            Song("3", "Fresh", "Artist", null, localDateModifiedSeconds = 9),
        )
        assertEquals(listOf("Mid", "Fresh", "Undated"), modified.sortedWithDesktopSort(DesktopSort.DATE_MODIFIED_ASC).map(Song::title))
        assertEquals(listOf("Fresh", "Mid", "Undated"), modified.sortedWithDesktopSort(DesktopSort.DATE_MODIFIED_DESC).map(Song::title))
    }

    @Test
    fun titleDescendingNeverReversesTies() {
        val songs = listOf(
            Song("v2", "Same", "Artist", null),
            Song("v1", "Apple", "Artist", null),
            Song("v3", "Same", "Artist", null),
        )

        // Equal titles keep their stored order in both directions: a naive
        // .reversed() would flip v2/v3 on DESC, this must not.
        assertEquals(listOf("v1", "v2", "v3"), songs.sortedWithDesktopSort(DesktopSort.TITLE_ASC).map(Song::videoId))
        assertEquals(listOf("v2", "v3", "v1"), songs.sortedWithDesktopSort(DesktopSort.TITLE_DESC).map(Song::videoId))
    }

    @Test
    fun unknownStoredSortValueFallsBackWithoutCrashing() {
        assertEquals(DesktopSort.TITLE_ASC, DesktopSort.parseStored("BOGUS_VALUE", DesktopSort.TITLE_ASC))
        assertEquals(DesktopSort.PLAYLIST_ORDER_ASC, DesktopSort.parseStored(null, DesktopSort.PLAYLIST_ORDER_ASC))
        assertEquals(DesktopSort.TITLE_ASC, DesktopSort.parseStored("", DesktopSort.TITLE_ASC))
    }

    @Test
    fun legacyLocalSortValuesMigrateToNewShape() {
        // Old date values meant newest-first, so they map to the _DESC halves.
        assertEquals(DesktopSort.DATE_ADDED_DESC, DesktopSort.migrateLegacy("DATE_ADDED"))
        assertEquals(DesktopSort.DATE_MODIFIED_DESC, DesktopSort.migrateLegacy("DATE_MODIFIED"))
        // Already in the new shape, blank, or unknown: not a legacy value.
        assertNull(DesktopSort.migrateLegacy("TITLE_ASC"))
        assertNull(DesktopSort.migrateLegacy("TITLE_DESC"))
        assertNull(DesktopSort.migrateLegacy("BOGUS_VALUE"))
        assertNull(DesktopSort.migrateLegacy(null))
    }

    @Test
    fun menuTapFlipsSelectedCriterionAndKeepsLastDirection() {
        val (flipped, directionsAfterFlip) =
            DesktopSort.TITLE_ASC.afterMenuTap(DesktopSortCriterion.TITLE, emptyMap())
        assertEquals(DesktopSort.TITLE_DESC, flipped)
        assertEquals(mapOf(DesktopSortCriterion.TITLE to true), directionsAfterFlip)

        val (picked, directionsAfterPick) =
            flipped.afterMenuTap(DesktopSortCriterion.ARTIST, directionsAfterFlip)
        assertEquals(DesktopSort.ARTIST_ASC, picked)
        assertEquals(
            mapOf(DesktopSortCriterion.TITLE to true, DesktopSortCriterion.ARTIST to false),
            directionsAfterPick,
        )

        // Returning to Title reuses its last direction instead of resetting.
        val (back, _) = picked.afterMenuTap(DesktopSortCriterion.TITLE, directionsAfterPick)
        assertEquals(DesktopSort.TITLE_DESC, back)
    }

    @Test
    fun unknownArtistMetadataDoesNotBlockAQualifiedSourceMatch() {
        val target = Song(
            videoId = "youtube-id",
            title = "Tera Mera Rishta - New Version (From \"Awarapan 2\")",
            artist = "Unknown Artist",
            thumbnailUrl = null,
            durationText = "6:05",
        )
        val sourceCopy = Song(
            videoId = "jiosaavn:track-id",
            title = "Tera Mera Rishta - New Version",
            artist = "Mithoon, Pritam, Sayeed Quadri, Saaj Bhatt, Subodhh Sharma",
            thumbnailUrl = null,
            durationText = "6:05",
        )

        assertEquals(sourceCopy, DesktopTrackMatcher.best(listOf(sourceCopy), target))
    }

    @Test
    fun sourceMatcherRejectsAConflictingArtist() {
        val target = Song("youtube-id", "Tera Mera Rishta", "Mithoon", null, durationText = "6:05")
        val cover = Song("jiosaavn:cover", "Tera Mera Rishta", "Different Artist", null, durationText = "6:05")

        assertEquals(null, DesktopTrackMatcher.best(listOf(cover), target))
    }

    @Test
    fun continuationTokenUnwrapsCommandExecutorCommand() {
        val json = kotlinx.serialization.json.Json.parseToJsonElement(
            """
            {
              "musicPlaylistShelfRenderer": {
                "contents": [
                  {
                    "continuationItemRenderer": {
                      "trigger": "CONTINUATION_TRIGGER_ON_ITEM_PRESCAN_VISIBLE",
                      "continuationEndpoint": {
                        "commandExecutorCommand": {
                          "commands": [
                            { "playlistVotingRefreshPopupCommand": {} },
                            {
                              "continuationCommand": {
                                "token": "TOKEN_IN_EXECUTOR",
                                "request": "CONTINUATION_REQUEST_TYPE_BROWSE"
                              }
                            }
                          ]
                        }
                      }
                    }
                  }
                ]
              }
            }
            """.trimIndent()
        )
        val token = com.music.knowmusic.data.innertube.InnertubeParser.continuationToken(json)
        assertEquals("TOKEN_IN_EXECUTOR", token)
    }

    @Test
    fun continuationTokenIgnoresLaterShelfRenderers() {
        val json = kotlinx.serialization.json.Json.parseToJsonElement(
            """
            {
              "contents": [
                { "continuationItemRenderer": { "button": {} } },
                {
                  "continuationItemRenderer": {
                    "continuationEndpoint": {
                      "continuationCommand": { "token": "OTHER_SHELF" }
                    }
                  }
                }
              ],
              "continuations": [
                { "nextContinuationData": { "continuation": "PAGE_MORE" } }
              ]
            }
            """.trimIndent()
        )
        val token = com.music.knowmusic.data.innertube.InnertubeParser.continuationToken(json)
        assertEquals("PAGE_MORE", token)
    }
}
