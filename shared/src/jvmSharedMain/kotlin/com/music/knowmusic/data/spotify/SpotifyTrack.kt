package com.music.knowmusic.data.spotify

/**
 * A track from a public playlist, Spotify's or Apple Music's, before it is matched to YouTube
 * Music. It used to live beside the Spotify Library client; the Library, its login and its Canvas
 * are gone — a Free app owner gets a 403 on every Spotify API call, so there is no login left to
 * keep — but the importers still need a track to match, so the model stays on its own here.
 */
data class SpotifyTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Int,
    val imageUrl: String?,
)
