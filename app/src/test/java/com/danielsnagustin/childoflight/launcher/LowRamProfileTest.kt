package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.DeviceMemory
import com.danielsnagustin.childoflight.launcher.model.GpuProfileId
import com.danielsnagustin.childoflight.launcher.model.MemoryMapping
import com.danielsnagustin.childoflight.launcher.model.ScreenFilter
import com.danielsnagustin.childoflight.launcher.model.applyLowRamDefaults
import com.danielsnagustin.childoflight.launcher.model.divergesFromPreset
import com.danielsnagustin.childoflight.launcher.model.isLowRamConservativeVariant
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

    // R4-P0-9: el mismo preset daba 3 valores de texture-cache según el
    // camino (primer arranque lowRam false, aplicar a mano true,
    // restablecer categoría true). Valor canónico EXPLÍCITO por preset.

    @Test
    fun g99RecommendedPresetHasExplicitTextureCacheTrue() {
        assertTrue(presetSettings(GpuProfileId.G99_RECOMMENDED).textureCache)
    }

    @Test
    fun everyPresetHasExplicitTextureCacheValue() {
        assertTrue(presetSettings(GpuProfileId.G99_RECOMMENDED).textureCache)
        assertTrue(presetSettings(GpuProfileId.G99_QUALITY).textureCache)
        assertFalse(presetSettings(GpuProfileId.MALI_PERFORMANCE).textureCache)
        assertTrue(presetSettings(GpuProfileId.MALI_BALANCED).textureCache)
        assertTrue(presetSettings(GpuProfileId.ADRENO_PERFORMANCE).textureCache)
        assertTrue(presetSettings(GpuProfileId.ADRENO_QUALITY_TURNIP).textureCache)
    }

    @Test
    fun freshlyAppliedPresetDoesNotDiverge() {
        for (profile in GpuProfileId.entries) {
            assertFalse(
                "preset $profile recién aplicado no debe divergir de sí mismo",
                divergesFromPreset(presetSettings(profile)),
            )
            assertFalse(
                "preset $profile no es variante lowRam (es el canónico)",
                isLowRamConservativeVariant(presetSettings(profile)),
            )
        }
    }

    @Test
    fun lowRamFirstBootIsRecordedAsConservativeDivergence() {
        // Primer arranque en 4 GB: solo cambia la caché de texturas
        // respecto al canónico, y queda registrado como divergencia
        // conservadora (no silenciosa): la UI lo muestra como
        // "perfil conservador" y el mensaje de detección lo dice.
        val firstBoot = applyLowRamDefaults(presetSettings(GpuProfileId.G99_RECOMMENDED))
        assertTrue(isLowRamConservativeVariant(firstBoot))
        assertTrue(divergesFromPreset(firstBoot))
    }

    @Test
    fun lowRamVariantOfAlreadyConservativePresetIsNotADivergence() {
        // MALI_PERFORMANCE ya es conservador de por sí: lowRam no
        // cambia nada y no hay divergencia que registrar.
        val s = applyLowRamDefaults(presetSettings(GpuProfileId.MALI_PERFORMANCE))
        assertFalse(isLowRamConservativeVariant(s))
        assertFalse(divergesFromPreset(s))
    }

    @Test
    fun manualTextureCacheToggleDivergesButIsNotLowRamVariant() {
        // El usuario apaga la caché a mano desde Rendimiento: diverge
        // del preset, pero NO es la variante conservadora del primer
        // arranque (esa también baja resolución/filtro si hace falta;
        // aquí solo cambió la caché y el resto sigue siendo el preset).
        val manual = presetSettings(GpuProfileId.G99_QUALITY).copy(textureCache = false)
        assertTrue(divergesFromPreset(manual))
        assertFalse(isLowRamConservativeVariant(manual))
    }

    @Test
    fun userPreservedFieldsDoNotCountAsDivergence() {
        // Audio, PSN y overlay son gustos del usuario que applyPreset
        // conserva: cambiarlos no es "perfil modificado".
        val tweaked = presetSettings(GpuProfileId.G99_RECOMMENDED).copy(
            audioVolume = 10,
            psnSignedIn = false,
            showTouchOverlayInGame = false,
            performanceMode = false,
            autoRevertTo1xIfBelow28Fps = false,
        )
        assertFalse(divergesFromPreset(tweaked))
    }
}
