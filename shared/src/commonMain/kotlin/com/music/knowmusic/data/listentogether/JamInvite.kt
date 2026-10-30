package com.music.knowmusic.data.listentogether

/**
 * The public invite URL for a party, and the only shape that is accepted back.
 *
 * Shared because both applications have to agree on it exactly: a link built on one and opened on
 * the other must resolve to the same code, and a link that is not this shape must be refused on
 * both rather than half-parsed into a join attempt.
 *
 * NOTE (round 14, issue 4): the origin is deliberately upstream's
 * `bitchord.kushagrasingh.in`, NOT a renamed `knowmusic.*` host. The
 * round-13 rebrand created `knowmusic.kushagrasingh.in`, which does not
 * exist, so generated invite links were unopenable. This origin is only a
 * link format — the app never issues a network request to it — and
 * upstream's web app is the only front-end serving `/invite/<CODE>`.
 * Parties on any server still use this origin: see
 * `DesktopListenTogether.inviteUrl`, which appends `?server=<server>` so the
 * joiner looks on the server that actually holds the party (round 18).
 * `parse` below strips any query, so a `?server=` link still parses to
 * the code exactly like a plain one.
 */
object JamInvite {

    const val ORIGIN = "https://bitchord.kushagrasingh.in"

    /** How many characters a party code carries. */
    const val CODE_LENGTH = 6

    /** The normalized party code, for the public invite URL shape only. */
    fun parse(value: String?): String? {
        val text = value?.trim() ?: return null
        val prefix = "$ORIGIN/invite/"
        if (!text.startsWith(prefix, ignoreCase = true)) return null
        val code = text.removePrefix(prefix).substringBefore('?').substringBefore('#')
        if (code.length != CODE_LENGTH || !code.all(Char::isLetterOrDigit)) return null
        return code.uppercase()
    }

    fun url(code: String): String = "$ORIGIN/invite/${code.uppercase()}"
}
