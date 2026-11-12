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
        assertEquals(MemoryMapping.PAGE_TABLE, s.memoryMapping)
        assertFalse(s.vSync)
        assertFalse(s.highAccuracy)
        assertFalse(s.surfaceSyncEnabled)
        // Ronda 0.2.65 (R-P1-5): la variante ya no toca textureCache
        // (inerte en Vulkan): queda ON, como en el preset.
        assertTrue(s.textureCache)
        assertTrue(s.shaderCache)
    }

    @Test
    fun lowRamFromAdrenoQualityDropsFxaaAndAnisoKeepsPageTable() {
        // Ronda 0.2.66 (G11-01, decisión del coordinador): lowRam
        // fuerza Page Table (antes Double Buffer): el preset Adreno
        // Calidad ya es Page Table y la variante lo mantiene.
        val s = applyLowRamDefaults(presetSettings(GpuProfileId.ADRENO_QUALITY_TURNIP))
        assertEquals(1f, s.resolutionMultiplier, 0f)
        assertEquals(ScreenFilter.BILINEAR, s.screenFilter)
        assertEquals(1, s.anisotropicFiltering)
        assertEquals(MemoryMapping.PAGE_TABLE, s.memoryMapping)
        assertFalse(s.vSync)
        // Ronda 0.2.65 (R-P1-5): la variante ya no toca textureCache
        // (inerte en Vulkan): queda ON, como en el preset.
        assertTrue(s.textureCache)
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
    fun lowRamOnG99RecommendedIsIdentity() {
        // Ronda 0.2.66 (G11-01, decisión del coordinador): el preset
        // G99 Recomendado ya es 1x + Bilinear + Page Table, así que la
        // variante lowRam NO cambia nada: el primer arranque en 4 GB
        // es idéntico al preset, sin divergencia que registrar (esto
        // es lo que ya decía el KDoc de isLowRamConservativeVariant
        // desde 0.2.65; la Fase 0 lo había roto al dejar Double
        // Buffer en la variante mientras el preset pasaba a Page
        // Table). La red de seguridad es G11-02 (effectiveMapping).
        val base = presetSettings(GpuProfileId.G99_RECOMMENDED)
        val s = applyLowRamDefaults(base)
        assertEquals(base, s)
        assertFalse(isLowRamConservativeVariant(s))
        assertFalse(divergesFromPreset(s))
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
        // Ronda 0.2.65 (R-P1-5): Mali Rendimiento también ON (antes
        // false): en Vulkan la clave es inerte y ya no diferencia
        // presets. El valor sigue siendo explícito por preset.
        assertTrue(presetSettings(GpuProfileId.MALI_PERFORMANCE).textureCache)
        assertTrue(presetSettings(GpuProfileId.MALI_BALANCED).textureCache)
        assertTrue(presetSettings(GpuProfileId.ADRENO_PERFORMANCE).textureCache)
        assertTrue(presetSettings(GpuProfileId.ADRENO_QUALITY_TURNIP).textureCache)
        // Ronda 0.2.65 (RG-P2-16): el 2x genérico también ON.
        assertTrue(presetSettings(GpuProfileId.GENERIC_QUALITY).textureCache)
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
    fun lowRamFirstBootOn2xPresetIsRecordedAsConservativeDivergence() {
        // Primer arranque en 4 GB sobre un preset 2x: baja a 1x con
        // Bilinear y queda registrado como divergencia conservadora
        // (no silenciosa): la UI lo muestra como "perfil
        // conservador" y el mensaje de detección lo dice. Ronda
        // 0.2.66 (G11-01): sobre G99 Recomendado la variante es
        // identidad (ver lowRamOnG99RecommendedIsIdentity); el caso
        // 2x es el que baja resolución además del resto.
        val firstBoot = applyLowRamDefaults(presetSettings(GpuProfileId.G99_QUALITY))
        assertEquals(1f, firstBoot.resolutionMultiplier, 0f)
        assertEquals(ScreenFilter.BILINEAR, firstBoot.screenFilter)
        assertTrue(isLowRamConservativeVariant(firstBoot))
        assertTrue(divergesFromPreset(firstBoot))
    }

    @Test
    fun lowRamOnMaliPerformanceIsIdentity() {
        // Ronda 0.2.66 (G11-01, decisión del coordinador): MALI_PERFORMANCE
        // ya es 1x + Bilinear + Page Table (el preset conservador por
        // sí mismo), así que la variante lowRam es identidad: no hay
        // divergencia que registrar. Esto es lo que ya decía el KDoc
        // de isLowRamConservativeVariant ("si el preset ya es
        // conservador de por sí, como MALI_PERFORMANCE, no hay
        // divergencia que registrar").
        val base = presetSettings(GpuProfileId.MALI_PERFORMANCE)
        val s = applyLowRamDefaults(base)
        assertEquals(MemoryMapping.PAGE_TABLE, s.memoryMapping)
        assertEquals(base, s)
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
