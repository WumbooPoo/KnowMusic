package com.music.knowmusic.desktop

/**
 * The public "apps open right now" count; see `Presence`.
 *
 * DISABLED for KnowMusic (round 14, issue 4), deliberately, and not reverted
 * to upstream: the only server that answers the presence endpoint is
 * upstream's (`api.bitchord.kushagrasingh.in`), and the round-13 mechanical
 * rebrand pointed the ping at `api.knowmusic.kushagrasingh.in`, a host that
 * does not exist — hence the `presence ping failed: No such host is known`
 * lines in the round-13 laptop log. Reverting would silently report every
 * KnowMusic install (install UUID + platform, every few minutes) to
 * upstream's server and inflate upstream's public live count, for a figure
 * nothing in this app displays. Disabling costs nothing visible: no UI reads
 * the count, and the Discord Rich Presence in [DesktopDiscordRpc] is a
 * separate local-IPC mechanism that this does not touch.
 *
 * To re-enable when KnowMusic has its own presence backend: restore the old
 * body (construct `Presence`, collect `DesktopWindowVisibility.visible`,
 * add the shutdown hook) and point `Presence.ENDPOINT` at that backend —
 * never back at upstream's host.
 */
internal object DesktopPresence {

    fun install() {
        // No-op: KnowMusic has no presence backend. See the KDoc above.
    }
}
