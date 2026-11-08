package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.DeviceMemory
import com.danielsnagustin.childoflight.launcher.model.FrameLimiter
import com.danielsnagustin.childoflight.launcher.model.GpuProfileId
import com.danielsnagustin.childoflight.launcher.model.MemoryMapping
import com.danielsnagustin.childoflight.launcher.model.ScreenFilter
import com.danielsnagustin.childoflight.launcher.model.applyLowRamDefaults
import com.danielsnagustin.childoflight.launcher.model.presetSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Perfil conservador de RAM baja (auditoría RAM ≤ 4 GB, 2026-11-07).
 * Puro (sin Android): applyLowRamDefaults solo transforma
 * LauncherSettings; DeviceMemory.isLowRamDevice (ActivityManager)
 * no se testea aquí — solo su umbral, que es una constante.
 */
class LowRamProfileTest {

    @Test
    fun lowRamThresholdIs4GiB() {
        assertEquals(4L * 1024L * 1024L * 1024L, DeviceMemory.LOW_RAM_THRESHOLD_BYTES)
    }

    @Test
    fun lowRamFromG99QualityForces1xBilinear() {
        val s = applyLowRamDefaults(presetSettings(GpuProfileId.G99_QUALITY))
        assertEquals(1f, s.resolutionMultiplier, 0f)
        assertEquals(ScreenFilter.BILINEAR, s.screenFilter)
        assertEquals(1, s.anisotropicFiltering)
        assertEquals(MemoryMapping.DOUBLE_BUFFER, s.memoryMapping)
        assertFalse(s.vSync)
        assertFalse(s.highAccuracy)
        assertFalse(s.surfaceSyncEnabled)
        assertFalse(s.textureCache)
        assertTrue(s.shaderCache)
        assertEquals(FrameLimiter.FPS_30, s.frameLimiter)
    }

    @Test
    fun lowRamFromAdrenoQualityDropsFxaaAnisoAndPageTable() {
        val s = applyLowRamDefaults(presetSettings(GpuProfileId.ADRENO_QUALITY_TURNIP))
        assertEquals(1f, s.resolutionMultiplier, 0f)
        assertEquals(ScreenFilter.BILINEAR, s.screenFilter)
        assertEquals(1, s.anisotropicFiltering)
        assertEquals(MemoryMapping.DOUBLE_BUFFER, s.memoryMapping)
        assertFalse(s.vSync)
        assertFalse(s.textureCache)
        assertTrue(s.shaderCache)
    }

    @Test
    fun lowRamPreservesNonMemorySettings() {
        val base = presetSettings(GpuProfileId.MALI_PERFORMANCE)
        val s = applyLowRamDefaults(base)
        // Frame skipping es de GPU, no de RAM: se conserva el del preset.
        assertEquals(1, s.frameSkipping)
        assertEquals(base.backend, s.backend)
        assertTrue(s.cpuOpt)
        assertEquals(base.audioBackend, s.audioBackend)
        assertFalse(s.safeMode)
        assertEquals(base.activeProfile, s.activeProfile)
    }

    @Test
    fun lowRamIsIdempotent() {
        val once = applyLowRamDefaults(presetSettings(GpuProfileId.G99_QUALITY))
        assertEquals(once, applyLowRamDefaults(once))
    }

    @Test
    fun lowRamAlreadyConservativePresetIsUnchanged() {
        val base = presetSettings(GpuProfileId.G99_RECOMMENDED)
        val s = applyLowRamDefaults(base)
        assertEquals(1f, s.resolutionMultiplier, 0f)
        assertEquals(ScreenFilter.BILINEAR, s.screenFilter)
        // Lo único que cambia en el preset recomendado es la caché de texturas.
        assertFalse(s.textureCache)
        assertEquals(base.copy(textureCache = false), s)
    }
}
