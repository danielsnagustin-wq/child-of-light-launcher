package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.core.nextMidGameNoPresentStreakMs
import com.danielsnagustin.childoflight.launcher.core.shouldFireMidGameNoPresentDialog
import com.danielsnagustin.childoflight.launcher.core.shouldWarnNoPresent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tercera pasada (R-2): detector de pantalla negra EN MITAD DEL
 * JUEGO, independiente de frame_profile. Funciones de decisión
 * puras: racha de \"emula pero no presenta\" con el contador de
 * presents (nextMidGameNoPresentStreakMs) y disparo del diálogo
 * de recuperación (shouldFireMidGameNoPresentDialog). El aviso
 * reutiliza shouldWarnNoPresent (probado en Ronda070).
 */
class TerceraPasadaWatchdogTest {

    // --- Racha de mitad de partida ---

    @Test
    fun midGameStreakAccumulatesWhileEmulatingWithoutPresenting() {
        // Primer tick válido: solo establece la línea base (0).
        var streak = nextMidGameNoPresentStreakMs(0L, -1L, 100L, -1L, 5_000L, 30f, false, 1_000L)
        assertEquals(0L, streak)
        // Contador y timestamp congelados, emulando a 30 fps: suma.
        repeat(60) {
            streak = nextMidGameNoPresentStreakMs(streak, 100L, 100L, 5_000L, 5_000L, 30f, false, 1_000L)
        }
        assertEquals(60_000L, streak)
        assertTrue(shouldFireMidGameNoPresentDialog(streak, 60_000L, false))
    }

    @Test
    fun midGameStreakResetsWhenAPresentArrives() {
        // Avanza el contador: resetea.
        assertEquals(0L, nextMidGameNoPresentStreakMs(45_000L, 100L, 101L, 5_000L, 5_000L, 30f, false, 1_000L))
        // Avanza solo el timestamp: también resetea.
        assertEquals(0L, nextMidGameNoPresentStreakMs(45_000L, 100L, 100L, 5_000L, 5_001L, 30f, false, 1_000L))
    }

    @Test
    fun midGameStreakResetsWhenNotEmulating() {
        // FPS 0: el juego no emula; no es \"emula pero no presenta\".
        assertEquals(0L, nextMidGameNoPresentStreakMs(20_000L, 100L, 100L, 5_000L, 5_000L, 0f, false, 1_000L))
        // FPS -1 (sin puente) y NaN: también resetean, no suman.
        assertEquals(0L, nextMidGameNoPresentStreakMs(20_000L, 100L, 100L, 5_000L, 5_000L, -1f, false, 1_000L))
        assertEquals(0L, nextMidGameNoPresentStreakMs(20_000L, 100L, 100L, 5_000L, 5_000L, Float.NaN, false, 1_000L))
    }

    @Test
    fun midGameStreakFreezesWhilePaused() {
        // Pausado: ni suma ni resetea (congela, como la etapa 1 en
        // segundo plano). El menú de pausa / segundo plano no debe
        // ni consumir el plazo ni borrar la racha.
        assertEquals(20_000L, nextMidGameNoPresentStreakMs(20_000L, 100L, 100L, 5_000L, 5_000L, 30f, true, 1_000L))
        assertEquals(0L, nextMidGameNoPresentStreakMs(0L, 100L, 100L, 5_000L, 5_000L, 30f, true, 1_000L))
    }

    @Test
    fun midGameStreakIsInertWhenCounterIsUnknown() {
        // Contador -1 (sin núcleo o sin parche): detector inerte,
        // resetea a 0 para no arrastrar una racha vieja.
        assertEquals(0L, nextMidGameNoPresentStreakMs(30_000L, 100L, -1L, 5_000L, 5_000L, 30f, false, 1_000L))
        assertEquals(0L, nextMidGameNoPresentStreakMs(30_000L, -1L, -1L, -1L, -1L, 30f, false, 1_000L))
    }

    // --- Aviso (reutiliza shouldWarnNoPresent, umbral 15 s) ---

    @Test
    fun midGameWarnFiresAt15sOncePerStreak() {
        assertFalse(shouldWarnNoPresent(14_000L, false, 15_000L))
        assertTrue(shouldWarnNoPresent(15_000L, false, 15_000L))
        assertFalse(shouldWarnNoPresent(30_000L, true, 15_000L))
    }

    // --- Diálogo de recuperación (umbral 60 s) ---

    @Test
    fun midGameDialogFiresAt60sOnlyOncePerStreak() {
        assertFalse(shouldFireMidGameNoPresentDialog(59_000L, 60_000L, false))
        assertTrue(shouldFireMidGameNoPresentDialog(60_000L, 60_000L, false))
        assertTrue(shouldFireMidGameNoPresentDialog(90_000L, 60_000L, false))
        // Ya mostrado en esta racha: no se re-muestra (\"Seguir
        // esperando\" resetea dialogShown y rearma).
        assertFalse(shouldFireMidGameNoPresentDialog(90_000L, 60_000L, true))
    }
}
