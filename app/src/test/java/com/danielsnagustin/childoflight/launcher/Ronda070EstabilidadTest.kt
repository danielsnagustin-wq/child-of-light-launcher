package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.core.Vita3kCoreBridge
import com.danielsnagustin.childoflight.launcher.core.isAliveForUiWork
import com.danielsnagustin.childoflight.launcher.core.nextWatchdogRemainingMs
import com.danielsnagustin.childoflight.launcher.core.shouldFireColdInitWatchdog
import com.danielsnagustin.childoflight.launcher.core.shouldRelaunchDeadSDLThread
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ronda 0.2.70 (Área 10, Estabilidad / sin crashes): decisiones puras
 * nuevas de EmbeddedGameActivity, Vita3kCoreBridge y Vita3KLauncher.
 *
 * - P1-8 / 10-P2-2: guarda de vida para los callbacks del hilo SDL.
 * - 10-P2-1: el plazo del watchdog solo corre en primer plano.
 * - P1-9: temporizador de vigilancia del init en frío.
 * - P1-10: relanzamiento de SDL_main tras reentrada rápida.
 * - P1-11: serialización destroy/init (Mutex) + token de generación.
 *
 * Las guardas de Activity no se pueden instanciar en JVM (Android):
 * lo que se prueba es la decisión pura que los call sites usan; que
 * la guarda exista y se ejecute en el camino es verificación por
 * lectura del diff (ver el informe de la ronda).
 */
class Ronda070EstabilidadTest {

    // P1-8 / 10-P2-2: isAliveForUiWork.

    @Test
    fun aliveUiWorkOnlyWhenNeitherFinishingNorDestroyed() {
        assertTrue(isAliveForUiWork(isFinishing = false, isDestroyed = false))
        assertFalse(isAliveForUiWork(isFinishing = true, isDestroyed = false))
        assertFalse(isAliveForUiWork(isFinishing = false, isDestroyed = true))
        assertFalse(isAliveForUiWork(isFinishing = true, isDestroyed = true))
    }

    // 10-P2-1: nextWatchdogRemainingMs.

    @Test
    fun watchdogSliceCountsOnlyWhileResumed() {
        assertEquals(179_000L, nextWatchdogRemainingMs(remainingMs = 180_000L, elapsedMs = 1_000L, isResumed = true))
        assertEquals(180_000L, nextWatchdogRemainingMs(remainingMs = 180_000L, elapsedMs = 1_000L, isResumed = false))
    }

    @Test
    fun watchdogRemainingNeverGoesBelowZero() {
        assertEquals(0L, nextWatchdogRemainingMs(remainingMs = 500L, elapsedMs = 1_000L, isResumed = true))
        assertEquals(0L, nextWatchdogRemainingMs(remainingMs = 0L, elapsedMs = 1_000L, isResumed = true))
    }

    // P1-9: shouldFireColdInitWatchdog.

    @Test
    fun coldInitWatchdogFiresOnlyWhenInitHasNotReturnedAfterTimeout() {
        assertTrue(shouldFireColdInitWatchdog(initDone = false, elapsedMs = 30_000L, timeoutMs = 30_000L))
        assertTrue(shouldFireColdInitWatchdog(initDone = false, elapsedMs = 60_000L, timeoutMs = 30_000L))
        assertFalse(shouldFireColdInitWatchdog(initDone = false, elapsedMs = 29_999L, timeoutMs = 30_000L))
        assertFalse(shouldFireColdInitWatchdog(initDone = false, elapsedMs = 0L, timeoutMs = 30_000L))
    }

    @Test
    fun coldInitWatchdogDoesNotFireWhenInitReturned() {
        assertFalse(shouldFireColdInitWatchdog(initDone = true, elapsedMs = 60_000L, timeoutMs = 30_000L))
    }

    // P1-10: shouldRelaunchDeadSDLThread.

    @Test
    fun relaunchOnlyWhenThreadDeadAndNoGameRunning() {
        assertTrue(shouldRelaunchDeadSDLThread(isThreadAlive = false, isAppRunning = false, gameConfirmed = false))
        assertFalse(shouldRelaunchDeadSDLThread(isThreadAlive = true, isAppRunning = false, gameConfirmed = false))
        assertFalse(shouldRelaunchDeadSDLThread(isThreadAlive = false, isAppRunning = true, gameConfirmed = false))
        assertFalse(shouldRelaunchDeadSDLThread(isThreadAlive = false, isAppRunning = false, gameConfirmed = true))
    }

    // P1-11: generación de sesión.

    @Test
    fun staleGenerationCodeIsDistinct() {
        assertEquals(-5, Vita3kCoreBridge.SESSION_STALE)
        assertEquals(0, Vita3kCoreBridge.OK)
        assertEquals(-1, Vita3kCoreBridge.NOT_IMPLEMENTED)
    }

    @Test
    fun destroyWithStaleGenerationIsNoopWithoutNative() {
        // En JVM no hay .so: init() nunca tiene éxito y la generación
        // sigue en 0. Una generación distinta (1) es obsoleta y
        // devuelve SESSION_STALE sin llamar a nativo; la generación
        // actual (0) sigue el camino normal (sin núcleo: NOT_IMPLEMENTED).
        assertEquals(Vita3kCoreBridge.SESSION_STALE, Vita3kCoreBridge.destroySessionIfGeneration(1))
        assertEquals(Vita3kCoreBridge.NOT_IMPLEMENTED, Vita3kCoreBridge.destroySessionIfGeneration(Vita3kCoreBridge.currentSessionGeneration()))
    }

    @Test
    fun sessionLockSerializesTwoHolders() = runBlocking {
        val order = Collections.synchronizedList(mutableListOf<String>())
        val acquired = CompletableDeferred<Unit>()
        val first = async {
            Vita3kCoreBridge.withSessionLock {
                order.add("first-in")
                acquired.complete(Unit)
                delay(100)
                order.add("first-out")
            }
        }
        val second = async {
            // Espera a que el primero tenga el Mutex: el segundo no
            // puede entrar hasta que el primero salga.
            acquired.await()
            Vita3kCoreBridge.withSessionLock { order.add("second-in") }
        }
        first.await()
        second.await()
        assertEquals(listOf("first-in", "first-out", "second-in"), order)
    }
}
