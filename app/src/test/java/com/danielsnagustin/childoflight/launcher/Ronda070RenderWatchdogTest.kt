package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.core.shouldFireSecondStageWatchdog
import com.danielsnagustin.childoflight.launcher.core.shouldWarnNoPresent
import com.danielsnagustin.childoflight.launcher.core.updateNoPresentStreakMs
import com.danielsnagustin.childoflight.launcher.data.clearShaderCacheIn
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Ronda 0.2.70 (área 9, renderizado):
 *
 * - P1-7: watchdog de SEGUNDA ETAPA (on_launch confirmado pero
 *   ningún frame presentado) y aviso de "emula pero no presenta"
 *   en el sondeo a 4 Hz. Lógica pura con el contador simulado
 *   (getPresentedFrameCount / getLastPresentTimeNs).
 * - 9-P2-6: la limpieza de caché de shaders borra también los
 *   pipeline-cache-vk*.dat huérfanos de versiones viejas (el
 *   vigente, vk15, y los de otras máscaras/títulos van dentro del
 *   mismo árbol cache/shaders que se borra entero).
 */
class Ronda070RenderWatchdogTest {

    // --- Watchdog de segunda etapa ---

    @Test
    fun secondStageFiresWhenLaunchedButNothingPresented() {
        assertTrue(shouldFireSecondStageWatchdog(gameRunningSinceMillis = 12_000L, presentedCount = 0L, lastPresentTimeNs = 0L))
        assertTrue(shouldFireSecondStageWatchdog(gameRunningSinceMillis = 12_000L, presentedCount = 0L, lastPresentTimeNs = -1L))
    }

    @Test
    fun secondStageDoesNotFireWithoutOnLaunch() {
        assertFalse(shouldFireSecondStageWatchdog(gameRunningSinceMillis = 0L, presentedCount = 0L, lastPresentTimeNs = 0L))
        assertFalse(shouldFireSecondStageWatchdog(gameRunningSinceMillis = 0L, presentedCount = 0L, lastPresentTimeNs = -1L))
    }

    @Test
    fun secondStageDoesNotFireWhenAtLeastOneFramePresented() {
        assertFalse(shouldFireSecondStageWatchdog(gameRunningSinceMillis = 12_000L, presentedCount = 5L, lastPresentTimeNs = 123L))
        assertFalse(shouldFireSecondStageWatchdog(gameRunningSinceMillis = 12_000L, presentedCount = 3L, lastPresentTimeNs = 0L))
        assertFalse(shouldFireSecondStageWatchdog(gameRunningSinceMillis = 12_000L, presentedCount = 0L, lastPresentTimeNs = 456L))
    }

    @Test
    fun secondStageDoesNotFireWhenCounterIsUnknown() {
        // -1 = sin núcleo o sin parche del gancho nº 1: "desconocido",
        // no "sin presents". Disparar aquí mataría un juego que sí
        // presenta por un contador ausente (falso positivo).
        assertFalse(shouldFireSecondStageWatchdog(gameRunningSinceMillis = 12_000L, presentedCount = -1L, lastPresentTimeNs = -1L))
        assertFalse(shouldFireSecondStageWatchdog(gameRunningSinceMillis = 12_000L, presentedCount = -1L, lastPresentTimeNs = 0L))
    }

    // --- Racha de "emula pero no presenta" ---

    @Test
    fun noPresentStreakAccumulatesWhileEmulatingWithoutPresenting() {
        var streak = 0L
        repeat(40) { streak = updateNoPresentStreakMs(streak, presentedPerSecondLast = 0.0, emulatedFpsLast = 30.0, tickMs = 250L) }
        assertEquals(10_000L, streak)
        assertTrue(shouldWarnNoPresent(streak, alreadyWarned = false, warnAfterMs = 10_000L))
    }

    @Test
    fun noPresentStreakResetsWhenPresentingOrNotEmulating() {
        assertEquals(0L, updateNoPresentStreakMs(5_000L, presentedPerSecondLast = 30.0, emulatedFpsLast = 30.0, tickMs = 250L))
        assertEquals(0L, updateNoPresentStreakMs(5_000L, presentedPerSecondLast = 0.0, emulatedFpsLast = 0.5, tickMs = 250L))
        assertEquals(0L, updateNoPresentStreakMs(5_000L, presentedPerSecondLast = 0.0, emulatedFpsLast = 0.0, tickMs = 250L))
        // Un solo tick con presents/s >= 1 corta una racha de 9,75 s.
        assertEquals(0L, updateNoPresentStreakMs(9_750L, presentedPerSecondLast = 1.0, emulatedFpsLast = 30.0, tickMs = 250L))
    }

    @Test
    fun noPresentStreakIgnoresNonFiniteSamples() {
        assertEquals(0L, updateNoPresentStreakMs(5_000L, presentedPerSecondLast = Double.NaN, emulatedFpsLast = 30.0, tickMs = 250L))
        assertEquals(0L, updateNoPresentStreakMs(5_000L, presentedPerSecondLast = 0.0, emulatedFpsLast = Double.POSITIVE_INFINITY, tickMs = 250L))
    }

    @Test
    fun noPresentWarnFiresOnceAtThreshold() {
        assertFalse(shouldWarnNoPresent(streakMs = 9_750L, alreadyWarned = false, warnAfterMs = 10_000L))
        assertTrue(shouldWarnNoPresent(streakMs = 10_000L, alreadyWarned = false, warnAfterMs = 10_000L))
        assertTrue(shouldWarnNoPresent(streakMs = 20_000L, alreadyWarned = false, warnAfterMs = 10_000L))
        assertFalse(shouldWarnNoPresent(streakMs = 20_000L, alreadyWarned = true, warnAfterMs = 10_000L))
    }

    // --- 9-P2-6: huérfanos de pipeline cache ---

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun clearShaderCacheDeletesOrphanPipelineCaches() {
        val vitaRoot = tmp.newFolder("vita3k")
        // shader_cache viejo (1 archivo).
        File(vitaRoot, "shader_cache").mkdirs()
        File(vitaRoot, "shader_cache/old.spv").writeText("x")
        // Árbol real del pin: cache/shaders/<title>/<self>/mask-<n>/
        // con el vigente (vk15) y huérfanos (vk14, vk16).
        val maskDir = File(vitaRoot, "cache/shaders/PCSB00598/PCSB00598/mask-3")
        maskDir.mkdirs()
        File(maskDir, "pipeline-cache-vk14.dat").writeText("viejo")
        File(maskDir, "pipeline-cache-vk15.dat").writeText("vigente")
        File(maskDir, "pipeline-cache-vk16.dat").writeText("futuro")
        File(maskDir, "shader.spv").writeText("spv")
        // Otro título con su propio huérfano.
        val otherMask = File(vitaRoot, "cache/shaders/PCSE00000/PCSE00000/mask-0")
        otherMask.mkdirs()
        File(otherMask, "pipeline-cache-vk14.dat").writeText("viejo")
        // Esto NO se toca: caché genérica y partidas.
        File(vitaRoot, "cache/other.txt").apply { parentFile.mkdirs(); writeText("generica") }
        File(vitaRoot, "vita/savedata").mkdirs()
        File(vitaRoot, "vita/savedata/slot0.bin").writeText("partida")

        val result = clearShaderCacheIn(vitaRoot)

        assertEquals(6, result.deletedFiles)
        assertEquals(2, result.deletedDirs)
        assertFalse(File(maskDir, "pipeline-cache-vk14.dat").exists())
        assertFalse(File(maskDir, "pipeline-cache-vk15.dat").exists())
        assertFalse(File(maskDir, "pipeline-cache-vk16.dat").exists())
        assertFalse(File(vitaRoot, "cache/shaders").exists())
        assertFalse(File(vitaRoot, "shader_cache").exists())
        assertTrue(File(vitaRoot, "cache/other.txt").exists())
        assertTrue(File(vitaRoot, "vita/savedata/slot0.bin").exists())
    }

    @Test
    fun clearShaderCacheOnEmptyRootDeletesNothing() {
        val vitaRoot = tmp.newFolder("vita3k-vacio")
        val result = clearShaderCacheIn(vitaRoot)
        assertEquals(0, result.deletedFiles)
        assertEquals(0, result.deletedDirs)
        // Segunda llamada: idempotente.
        val again = clearShaderCacheIn(vitaRoot)
        assertEquals(0, again.deletedFiles)
        assertEquals(0, again.deletedDirs)
    }
}
