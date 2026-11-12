package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.launcher.Vita3KLauncher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests nuevos de la ronda 0.2.65, coordinador (R-P1-2, guarda
 * anti-relanzamiento): la espera solo corre mientras el hilo SDL viejo
 * siga vivo y antes del tope de 5 s. No se toca ningún test compartido.
 */
class R10RelaunchGuardTest {

    @Test
    fun waitsWhileThreadAliveBeforeTimeout() {
        assertTrue(Vita3KLauncher.shouldKeepWaitingForOldSDLThread(true, 0))
        assertTrue(Vita3KLauncher.shouldKeepWaitingForOldSDLThread(true, 4_999))
    }

    @Test
    fun stopsWaitingWhenThreadDies() {
        assertFalse(Vita3KLauncher.shouldKeepWaitingForOldSDLThread(false, 0))
        assertFalse(Vita3KLauncher.shouldKeepWaitingForOldSDLThread(false, 4_999))
    }

    @Test
    fun stopsWaitingAtTimeoutEvenIfThreadAlive() {
        assertFalse(Vita3KLauncher.shouldKeepWaitingForOldSDLThread(true, 5_000))
        assertFalse(Vita3KLauncher.shouldKeepWaitingForOldSDLThread(true, 30_000))
    }

    @Test
    fun timeoutIsFiveSeconds() {
        assertEquals(5_000L, Vita3KLauncher.OLD_SDL_THREAD_WAIT_TIMEOUT_MS)
    }
}
