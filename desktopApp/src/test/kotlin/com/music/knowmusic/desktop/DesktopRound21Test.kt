package com.music.knowmusic.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Round 21: cross-path media dedup (guard) and the shared quit entry point.
 *
 * The guard tests run entirely on the JVM: [DesktopMediaActionGuard.tryConsume]
 * takes its clock as a parameter, so window expiry is tested without sleeps.
 * The quit test only exercises dispatch (an installed action runs, an absent
 * one is not invoked here — the exitProcess fallback is unreachable in a test
 * by construction, since the test always installs an action first).
 */
class DesktopRound21Test {

    @Test
    fun crossPathDuplicateIsSuppressed() {
        // One physical press arriving via SMTC and then via Compose: second is dropped.
        assertTrue(DesktopMediaActionGuard.tryConsume(DesktopMediaActionGuard.PLAY_PAUSE, DesktopMediaActionGuard.SOURCE_SMTC, 1000L))
        assertFalse(DesktopMediaActionGuard.tryConsume(DesktopMediaActionGuard.PLAY_PAUSE, DesktopMediaActionGuard.SOURCE_COMPOSE, 1100L))
    }

    @Test
    fun samePathRapidPressesPass() {
        // Two deliberate skips from one path must both run — only the other path's
        // echo is suppressed.
        assertTrue(DesktopMediaActionGuard.tryConsume(DesktopMediaActionGuard.NEXT, DesktopMediaActionGuard.SOURCE_COMPOSE, 2000L))
        assertTrue(DesktopMediaActionGuard.tryConsume(DesktopMediaActionGuard.NEXT, DesktopMediaActionGuard.SOURCE_COMPOSE, 2050L))
    }

    @Test
    fun differentActionsDoNotSuppressEachOther() {
        assertTrue(DesktopMediaActionGuard.tryConsume(DesktopMediaActionGuard.NEXT, DesktopMediaActionGuard.SOURCE_SMTC, 3000L))
        assertTrue(DesktopMediaActionGuard.tryConsume(DesktopMediaActionGuard.PREVIOUS, DesktopMediaActionGuard.SOURCE_COMPOSE, 3050L))
    }

    @Test
    fun windowExpiryPasses() {
        assertTrue(DesktopMediaActionGuard.tryConsume(DesktopMediaActionGuard.PLAY_PAUSE, DesktopMediaActionGuard.SOURCE_SMTC, 4000L))
        // 1000 ms later: past the 300 ms window, the same action from the other path runs.
        assertTrue(DesktopMediaActionGuard.tryConsume(DesktopMediaActionGuard.PLAY_PAUSE, DesktopMediaActionGuard.SOURCE_COMPOSE, 5000L))
    }

    @Test
    fun quitDispatchRunsInstalledAction() {
        var called = false
        DesktopQuit.action = { called = true }
        try {
            DesktopQuit.quit()
            assertTrue(called)
        } finally {
            DesktopQuit.action = null
        }
        assertEquals(null, DesktopQuit.action)
    }
}
