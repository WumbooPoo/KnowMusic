package com.music.knowmusic.desktop

import com.music.knowmusic.data.spotify.SpotifyImportTrack
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A public Apple Music playlist, read the way the web player reads it: no sign-in, no
 * subscription, just the catalog API with the web player's own bearer.
 *
 * Why this route and not the page's HTML: the playlist page is a static shell until its
 * JavaScript runs, so a plain GET returns no tracks — but the logged-out web player renders
 * the full list, which means the catalog API answers a public playlist with only the web
 * player's bearer. The tracks it returns are mapped onto [SpotifyImportTrack] so the import
 * reuses Spotify's YouTube Music matching and playlist creation unchanged.
 *
 * One thing this cannot prove from here: whether a user playlist id (`pl.u-…`) resolves
 * through the catalog endpoint the way a curator playlist does. If it 404s, the fetch errors
 * as not found or not public rather than failing silently, and that is the one live check
 * left for the first real import.
 */
object DesktopAppleMusicImporter {

    /** A playlist link reduced to what the catalog API needs: its storefront and its id. */
    data class PlaylistRef(val storefront: String, val id: String)

    private const val AMP = "https://amp-api.music.apple.com/v1/catalog"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** A playlist id: `pl.` plus the owner's opaque suffix. */
    private val PLAYLIST_ID = Regex("""pl\.[A-Za-z0-9_-]+""")

    private fun isAppleMusicHost(host: String) =
        host == "music.apple.com" || host.endsWith(".music.apple.com") ||
            host == "embed.music.apple.com" || host.endsWith(".embed.music.apple.com")

    /**
     * Parses a raw user string or link into a playlist reference, or null when it isn't one.
     * The storefront comes from the link itself (`/id/playlist/…` → `id`); a link without a
     * two-letter country segment falls back to this machine's storefront, the way Canvas does.
     */
    fun extractPlaylist(input: String): PlaylistRef? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        val uri = runCatching { java.net.URI(trimmed) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
        if (!isAppleMusicHost(uri.host?.lowercase().orEmpty())) return null
        val segments = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
        val playlistIdx = segments.indexOf("playlist")
        if (playlistIdx == -1 || playlistIdx + 1 >= segments.size) return null
        val id = segments.last().substringBefore("?").takeIf { PLAYLIST_ID.matches(it) } ?: return null
        val storefront = segments.firstOrNull()?.takeIf { it.length == 2 }?.lowercase()
            ?: Locale.getDefault().country.takeIf { it.length == 2 }?.lowercase(Locale.ROOT)
            ?: "us"
        return PlaylistRef(storefront, id)
    }

    /**
     * The playlist's name and its tracks, in order. Pages through the track relationship's
     * `next` link until it runs out; a page that fails mid-walk stops the walk with what has
     * already been read rather than throwing the whole import away.
     */
    suspend fun fetchPlaylistTracks(ref: PlaylistRef): Pair<String, List<SpotifyImportTrack>> =
        withContext(Dispatchers.IO) {
            val bearer = DesktopAppleMusicCanvas.webPlayerToken()
                ?: error("Could not read Apple Music's web player token. Check the connection and try again.")
            val headers = mapOf(
                "Authorization" to "Bearer $bearer",
                "Origin" to "https://music.apple.com",
                "Referer" to "https://music.apple.com/",
            )
            val first = "$AMP/${ref.storefront}/playlists/${ref.id}?include=tracks&limit[tracks]=100"
            val (status, body) = canvasGetDetailed(first, headers)
            if (status != 200 || body == null) {
                if (status == -1) error("Could not reach Apple Music. Check the connection and try again.")
                error("Could not read that Apple Music playlist (HTTP $status). Make sure the playlist is public.")
            }
            val root = parse(body) ?: error("Could not read that Apple Music playlist.")
            val playlist = root["data"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: error("That Apple Music playlist was not found or is not public.")
            val name = playlist["attributes"]?.jsonObject?.get("name")?.jsonPrimitive?.content
                ?: "Imported Apple Music Playlist"
            val tracks = mutableListOf<SpotifyImportTrack>()
            var next = readTracks(
                playlist["relationships"]?.jsonObject?.get("tracks")?.jsonObject,
                tracks,
            )
            // The track list is capped at 100 per page; a long playlist walks its `next` links.
            // 100 pages is 10,000 tracks, past any playlist worth importing by hand.
            var pages = 1
            while (next != null && pages < MAX_PAGES) {
                val url = if (next.startsWith("http")) next else "https://amp-api.music.apple.com$next"
                val (pageStatus, pageBody) = canvasGetDetailed(url, headers)
                if (pageStatus != 200 || pageBody == null) break
                next = readTracks(parse(pageBody), tracks)
                pages++
            }
            if (tracks.isEmpty()) {
                error("No tracks found in that Apple Music playlist.")
            }
            Pair(name, tracks)
        }

    /** Reads one track page into [out]; returns its `next` link, or null when it is the last. */
    private fun readTracks(page: JsonObject?, out: MutableList<SpotifyImportTrack>): String? {
        val data = page?.get("data")?.jsonArray ?: JsonArray(emptyList())
        for (element in data) {
            val attrs = element.jsonObject["attributes"]?.jsonObject ?: continue
            val title = attrs["name"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (title.isEmpty()) continue
            out.add(
                SpotifyImportTrack(
                    title = title,
                    artist = attrs["artistName"]?.jsonPrimitive?.content?.trim().orEmpty(),
                ),
            )
        }
        return page?.get("next")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
    }

    private fun parse(body: String): JsonObject? =
        runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()

    private const val MAX_PAGES = 100
}
