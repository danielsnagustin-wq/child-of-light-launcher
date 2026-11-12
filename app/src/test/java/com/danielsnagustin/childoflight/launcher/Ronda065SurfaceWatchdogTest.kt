package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.core.shouldFireBlackScreenWatchdog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.vita3k.emulator.shouldDedupeFreeDown

/**
 * Ronda 0.2.65 (área superficie / ciclo de vida del juego):
 * decisiones puras nuevas de EmbeddedGameActivity y EmuSurface.
 *
 * - P0-3: el watchdog de pantalla negra solo dispara sin ninguna
 *   señal de arranque (ni on_launch ni primer frame entregado ni FPS
 *   mayor que 0). Si el juego confirmó on_launch, no dispara aunque
 *   el FPS sea 0: el juego está en curso (cargando) y matarlo sería
 *   peor que esperar.
 * - O-P2-4: el dedupe de DOWN libre descarta solo el segundo envío
 *   del mismo toque (menos de 100 ms, casi misma posición); un toque
 *   nuevo (fuera de la ventana o lejos) pasa siempre.
 */
class Ronda065SurfaceWatchdogTest {

    @Test
    fun watchdogFiresWithNoLaunchSignalAndNoFps() {
        assertTrue(shouldFireBlackScreenWatchdog(gameRunningSinceMillis = 0L, firstFrameLogged = false, fps = 0f))
        assertTrue(shouldFireBlackScreenWatchdog(gameRunningSinceMillis = 0L, firstFrameLogged = false, fps = -1f))
    }

    @Test
    fun watchdogDoesNotFireAfterOnLaunchEvenWithZeroFps() {
        assertFalse(shouldFireBlackScreenWatchdog(gameRunningSinceMillis = 12_000L, firstFrameLogged = false, fps = 0f))
    }

    @Test
    fun watchdogDoesNotFireWhenFramesWereDelivered() {
        assertFalse(shouldFireBlackScreenWatchdog(gameRunningSinceMillis = 0L, firstFrameLogged = true, fps = 0f))
        assertFalse(shouldFireBlackScreenWatchdog(gameRunningSinceMillis = 0L, firstFrameLogged = false, fps = 30f))
    }

    @Test
    fun duplicateDownWithinWindowAndRadiusIsDeduped() {
        assertTrue(shouldDedupeFreeDown(eventTimeMillis = 1050L, x = 100f, y = 200f, lastEventTimeMillis = 1000L, lastX = 100f, lastY = 200f))
        // Casi misma posición (3 px en x, 4 px en y) también es duplicado.
        assertTrue(shouldDedupeFreeDown(eventTimeMillis = 1050L, x = 103f, y = 204f, lastEventTimeMillis = 1000L, lastX = 100f, lastY = 200f))
    }

    @Test
    fun downAtOrAfterWindowEdgeIsNotDeduped() {
        // Justo 100 ms después ya es un toque nuevo (la ventana es < 100 ms).
        assertFalse(shouldDedupeFreeDown(eventTimeMillis = 1100L, x = 100f, y = 200f, lastEventTimeMillis = 1000L, lastX = 100f, lastY = 200f))
        assertFalse(shouldDedupeFreeDown(eventTimeMillis = 5000L, x = 100f, y = 200f, lastEventTimeMillis = 1000L, lastX = 100f, lastY = 200f))
    }

    @Test
    fun downFarFromPreviousIsNotDeduped() {
        assertFalse(shouldDedupeFreeDown(eventTimeMillis = 1050L, x = 200f, y = 200f, lastEventTimeMillis = 1000L, lastX = 100f, lastY = 200f))
    }

    @Test
    fun downWithNoPreviousIsNotDeduped() {
        assertFalse(shouldDedupeFreeDown(eventTimeMillis = 1050L, x = 100f, y = 200f, lastEventTimeMillis = -1L, lastX = -1f, lastY = -1f))
    }
}
