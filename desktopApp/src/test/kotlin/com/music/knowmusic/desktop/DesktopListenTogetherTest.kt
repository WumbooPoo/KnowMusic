package com.music.knowmusic.desktop

import com.music.knowmusic.data.listentogether.JamInvite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopListenTogetherTest {

    @Test
    fun `server address is canonicalized before it is persisted`() {
        assertEquals(
            "https://party.example.com/base",
            DesktopListenTogether.normalizeServerUrl(" PARTY.Example.com/base/ ").getOrThrow(),
        )
        assertEquals("", DesktopListenTogether.normalizeServerUrl("  ").getOrThrow())
    }

    @Test
    fun `built-in fallback is the same server the phone and web default to`() {
        // Round 19: a typed code carries no server, so the joiner looks on
        // ITS OWN idle server. The web default is this api backend and the
        // phone's observed behaviour is consistent with it looking here
        // too (typed code missed a Render party, ?server= link hit it);
        // the desktop fallback was Render, which is why a typed code
        // missed while a ?server= link hit. Render stays reachable as an
        // explicit server (see the invite-link tests below), but it must
        // not be the default again.
        // (Holds only when no knowmusic.listentogether.server property is
        // set — a build-time property still wins, by design.)
        assertEquals(
            "https://api.bitchord.kushagrasingh.in",
            DesktopListenTogether.DEFAULT_SERVER,
        )
    }

    @Test
    fun `invite link names the server that holds the party`() {
        // Round 18: a bare ORIGIN/invite/<CODE> link made the joiner look on
        // its own default server, so a desktop party on Render was "no party"
        // on a phone whose default is upstream's api backend.
        assertEquals(
            "https://bitchord.kushagrasingh.in/invite/ABC123" +
                "?server=https%3A%2F%2Fbitchord-listen-together.onrender.com",
            DesktopListenTogether.inviteUrlFor("abc123", "https://bitchord-listen-together.onrender.com"),
        )
        assertEquals(
            "https://bitchord.kushagrasingh.in/invite/ABC123" +
                "?server=https%3A%2F%2Fparty.example.com%2Fbase",
            DesktopListenTogether.inviteUrlFor("ABC123", "https://party.example.com/base/"),
        )
    }

    @Test
    fun `invite link without a known server stays a plain upstream link`() {
        assertEquals(JamInvite.url("ABC123"), DesktopListenTogether.inviteUrlFor("ABC123", null))
        assertEquals(JamInvite.url("ABC123"), DesktopListenTogether.inviteUrlFor("ABC123", "  "))
    }

    @Test
    fun `invite link with a server still parses back to its code`() {
        val link = DesktopListenTogether.inviteUrlFor(
            "abc123",
            "https://bitchord-listen-together.onrender.com",
        )
        assertEquals("ABC123", JamInvite.parse(link))
    }

    @Test
    fun `unsafe or ambiguous server addresses are rejected`() {
        listOf(
            "ftp://party.example.com",
            "https://user:pass@party.example.com",
            "https://party.example.com?secret=yes",
            "https://party.example.com/#fragment",
            "https://party.example.com/a/../b",
            "party example.com",
            "https://party.example.com:99999",
        ).forEach { address ->
            assertTrue(
                DesktopListenTogether.normalizeServerUrl(address).isFailure,
                "$address should not be accepted",
            )
        }
    }
}
