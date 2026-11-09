package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.GpuDetector
import com.danielsnagustin.childoflight.launcher.model.GpuBackend
import com.danielsnagustin.childoflight.launcher.model.GpuInfo
import com.danielsnagustin.childoflight.launcher.model.GpuProfileId
import com.danielsnagustin.childoflight.launcher.model.GpuVendor
import com.danielsnagustin.childoflight.launcher.model.LauncherSettings
import com.danielsnagustin.childoflight.launcher.model.applyLowRamDefaults
import com.danielsnagustin.childoflight.launcher.model.applySafeMode
import com.danielsnagustin.childoflight.launcher.model.defaultProfileFor
import com.danielsnagustin.childoflight.launcher.model.divergesFromPreset
import com.danielsnagustin.childoflight.launcher.model.isLowRamConservativeVariant
import com.danielsnagustin.childoflight.launcher.model.presetSettings
import com.danielsnagustin.childoflight.launcher.model.preserveManualCacheChoices
import com.danielsnagustin.childoflight.launcher.model.resolveBackend
import com.danielsnagustin.childoflight.launcher.model.shouldAutoRevertTo1x
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuProfileTest {

    private fun g99Gpu() = GpuInfo(
        vendor = GpuVendor.MALI,
        renderer = "Mali-G57 MC2",
        socModel = "MT6789",
        vulkanSupported = true,
        vulkanVersionLabel = "Vulkan 1.1+",
    )

    @Test
    fun g99IsDetectedAsUserVerifiedDevice() {
        val gpu = g99Gpu()
        assertTrue(gpu.isHelioG99)
        assertTrue(gpu.isMaliG57)
        assertTrue(gpu.isUserVerifiedDevice)
    }

    @Test
    fun defaultProfileForG99IsRecommended() {
        assertEquals(GpuProfileId.G99_RECOMMENDED, defaultProfileFor(g99Gpu()))
    }

    @Test
    fun g99RecommendedPresetValues() {
        val s = presetSettings(GpuProfileId.G99_RECOMMENDED)
        assertEquals(1f, s.resolutionMultiplier, 0.001f)
        assertEquals(false, s.vSync)
        assertEquals(true, s.asyncPipelineCompilation)
        assertEquals(false, s.surfaceSyncEnabled)
        assertEquals("DOUBLE_BUFFER", s.memoryMapping.name)
        assertEquals(true, s.cpuOpt)
        assertEquals("SDL", s.audioBackend.name)
        assertEquals(true, s.ngsEnable)
    }

    @Test
    fun g99QualityPresetIs2xWithFsr() {
        val s = presetSettings(GpuProfileId.G99_QUALITY)
        assertEquals(2f, s.resolutionMultiplier, 0.001f)
        assertEquals("FSR", s.screenFilter.name)
    }

    @Test
    fun maliGenericNeverRecommends2x() {
        val perf = presetSettings(GpuProfileId.MALI_PERFORMANCE)
        val balanced = presetSettings(GpuProfileId.MALI_BALANCED)
        assertEquals(1f, perf.resolutionMultiplier, 0.001f)
        assertEquals(1f, balanced.resolutionMultiplier, 0.001f)
        assertEquals("DOUBLE_BUFFER", perf.memoryMapping.name)
        // Mali Rendimiento redefine su diferencia real: sin caché de
        // texturas (libera RAM); Equilibrado la conserva.
        assertEquals(false, perf.textureCache)
        assertEquals(true, balanced.textureCache)
    }

    @Test
    fun adrenoQualityUsesTurnipAndPageTable() {
        val s = presetSettings(GpuProfileId.ADRENO_QUALITY_TURNIP)
        assertEquals(2f, s.resolutionMultiplier, 0.001f)
        assertEquals("PAGE_TABLE", s.memoryMapping.name)
        assertEquals("TURNIP", s.gpuDriver.name)
    }

    @Test
    fun autoRevertOnlyForG99QualityBelow28() {
        assertTrue(shouldAutoRevertTo1x(27f, GpuProfileId.G99_QUALITY, true))
        assertFalse(shouldAutoRevertTo1x(28f, GpuProfileId.G99_QUALITY, true))
        assertFalse(shouldAutoRevertTo1x(30f, GpuProfileId.G99_QUALITY, true))
        assertFalse(shouldAutoRevertTo1x(20f, GpuProfileId.G99_RECOMMENDED, true))
        assertFalse(shouldAutoRevertTo1x(20f, GpuProfileId.G99_QUALITY, false))
        assertFalse(shouldAutoRevertTo1x(null, GpuProfileId.G99_QUALITY, true))
    }

    @Test
    fun safeModeForcesSafeValues() {
        val s = applySafeMode(LauncherSettings(resolutionMultiplier = 4f, highAccuracy = true))
        assertEquals(1f, s.resolutionMultiplier, 0.001f)
        assertEquals(false, s.highAccuracy)
        assertEquals("SDL", s.audioBackend.name)
        assertEquals(true, s.safeMode)
        // P2-14: el modo seguro apaga fps-hack (con 60 FPS ON el
        // config.yml emitía fps-hack: true + v-sync: false).
        assertEquals(false, s.fpsHack)
    }

    // --- Compatibilidad por GPU (auditoría 2026-11-07) ---

    @Test
    fun powerVrIsClassifiedAndGetsMaliSafeProfile() {
        assertEquals(GpuVendor.POWERVR, GpuDetector.classify("PowerVR Rogue GE8320", "Imagination Technologies"))
        assertEquals(GpuVendor.POWERVR, GpuDetector.classify("", "Imagination Technologies"))
        val gpu = GpuInfo(vendor = GpuVendor.POWERVR, renderer = "PowerVR Rogue GE8320", vulkanSupported = true)
        assertEquals(GpuProfileId.MALI_PERFORMANCE, defaultProfileFor(gpu))
        val s = presetSettings(GpuProfileId.MALI_PERFORMANCE)
        assertEquals(1f, s.resolutionMultiplier, 0.001f)
        assertEquals("DOUBLE_BUFFER", s.memoryMapping.name)
        assertEquals(false, s.textureCache)
    }

    @Test
    fun unknownAndOtherGpuNeverLackAProfile() {
        val unknown = GpuInfo(vendor = GpuVendor.UNKNOWN, renderer = "No detectado", vulkanSupported = false)
        assertEquals(GpuProfileId.MALI_PERFORMANCE, defaultProfileFor(unknown))
        assertEquals(GpuVendor.UNKNOWN, GpuDetector.classify("", ""))
        // Xclipse (Exynos/AMD) y resto: OTHER -> Mali seguro.
        val other = GpuInfo(vendor = GpuVendor.OTHER, renderer = "Xclipse 920", vulkanSupported = true)
        assertEquals(GpuProfileId.MALI_PERFORMANCE, defaultProfileFor(other))
        assertEquals(GpuVendor.OTHER, GpuDetector.classify("Xclipse 920", "Samsung"))
    }

    @Test
    fun resolveBackendFallsBackToOpenGlWithoutVulkan() {
        val noVulkan = GpuInfo(vendor = GpuVendor.MALI, renderer = "Mali-G57 MC2", vulkanSupported = false)
        val withVulkan = noVulkan.copy(vulkanSupported = true)
        val vulkanSettings = LauncherSettings(backend = GpuBackend.VULKAN)
        assertEquals(GpuBackend.OPENGL, resolveBackend(vulkanSettings, noVulkan))
        assertEquals(GpuBackend.VULKAN, resolveBackend(vulkanSettings, withVulkan))
        // Sin GPU detectada no se cambia nada; OpenGL manual se respeta.
        assertEquals(GpuBackend.VULKAN, resolveBackend(vulkanSettings, null))
        assertEquals(GpuBackend.OPENGL, resolveBackend(LauncherSettings(backend = GpuBackend.OPENGL), noVulkan))
    }

    @Test
    fun turnipPresetIsTheOnlyOneWithTurnipDriver() {
        // Turnip solo en ADRENO_QUALITY_TURNIP; ningún preset Mali
        // lo activa (en Mali no funciona).
        for (profile in GpuProfileId.entries) {
            val driver = presetSettings(profile).gpuDriver.name
            if (profile == GpuProfileId.ADRENO_QUALITY_TURNIP) {
                assertEquals("TURNIP", driver)
            } else {
                assertEquals("SYSTEM", driver)
            }
        }
    }

    // --- R4-P1-13: aplicar un preset conserva las cachés y el overlay
    // manuales del usuario (antes los pisaba en silencio). La fusión
    // vive en la función pura preserveManualCacheChoices, que es lo
    // que SettingsRepository.applyPreset aplica (DataStore no corre
    // en tests JVM).

    @Test
    fun applyPresetPreservesManualCacheChoices() {
        val manual = presetSettings(GpuProfileId.G99_RECOMMENDED).copy(
            textureCache = false,
            shaderCache = false,
            showFpsOverlay = true,
            hashlessTextureCache = true,
            showCompileShaders = true,
        )
        val applied = preserveManualCacheChoices(presetSettings(GpuProfileId.G99_QUALITY), manual)
        assertFalse(applied.textureCache)
        assertFalse(applied.shaderCache)
        assertTrue(applied.showFpsOverlay)
        assertTrue(applied.hashlessTextureCache)
        assertTrue(applied.showCompileShaders)
        // Los valores del preset sí se aplican: 2x con FSR.
        assertEquals(2f, applied.resolutionMultiplier, 0.001f)
        assertEquals("FSR", applied.screenFilter.name)
        assertEquals(GpuProfileId.G99_QUALITY, applied.activeProfile)
    }

    @Test
    fun preserveManualCacheChoicesWithNoCurrentKeepsPreset() {
        val applied = preserveManualCacheChoices(presetSettings(GpuProfileId.MALI_PERFORMANCE), null)
        assertEquals(presetSettings(GpuProfileId.MALI_PERFORMANCE), applied)
        assertFalse(applied.textureCache)
    }

    @Test
    fun lowRamFirstBootDoesNotPreserveFactoryCacheDefault() {
        // Primer arranque en 4 GB: el current son los defaults de
        // fábrica (caché ON) y la variante conservadora la apaga;
        // conservarla la volvería a ON y rompería la cadena R4-P0-9.
        val firstBoot = applyLowRamDefaults(presetSettings(GpuProfileId.G99_RECOMMENDED))
        val applied = preserveManualCacheChoices(firstBoot, LauncherSettings(), lowRam = true)
        assertFalse(applied.textureCache)
        assertTrue(isLowRamConservativeVariant(applied))
        assertTrue(divergesFromPreset(applied))
    }

    @Test
    fun g99RecommendedAndMaliPerformanceDifferOnlyInTextureCache() {
        // Verificado campo a campo: la única diferencia real entre
        // ambos presets es textureCache (true frente a false).
        val g99 = presetSettings(GpuProfileId.G99_RECOMMENDED)
        val mali = presetSettings(GpuProfileId.MALI_PERFORMANCE)
        assertEquals(g99.copy(textureCache = false), mali.copy(activeProfile = GpuProfileId.G99_RECOMMENDED))
        assertTrue(g99.textureCache)
        assertFalse(mali.textureCache)
    }

    @Test
    fun maliPerformanceOverG99StateKeepsTextureCacheAndDiverges() {
        // Aplicar Mali Rendimiento con la caché manual ON conservada
        // deja textureCache ON: el resultado coincide con G99 salvo
        // la etiqueta, y ESO se ve como "Perfil modificado" (el aviso
        // explícito obligatorio de R4-P1-13).
        val applied = preserveManualCacheChoices(
            presetSettings(GpuProfileId.MALI_PERFORMANCE),
            presetSettings(GpuProfileId.G99_RECOMMENDED),
        )
        assertTrue(applied.textureCache)
        assertEquals(GpuProfileId.MALI_PERFORMANCE, applied.activeProfile)
        assertTrue(divergesFromPreset(applied))
        assertFalse(isLowRamConservativeVariant(applied))
    }

    @Test
    fun manualCacheToggleUnderQualityDivergesAndIsNotLowRamVariant() {
        // Apagar la caché a mano y aplicar el mismo preset Calidad:
        // se conserva OFF y el indicador muestra "Perfil modificado"
        // (no es la variante lowRam: esa también bajaría a 1x).
        val manual = presetSettings(GpuProfileId.G99_QUALITY).copy(textureCache = false)
        val applied = preserveManualCacheChoices(presetSettings(GpuProfileId.G99_QUALITY), manual)
        assertFalse(applied.textureCache)
        assertTrue(divergesFromPreset(applied))
        assertFalse(isLowRamConservativeVariant(applied))
    }

    @Test
    fun manualCacheToggleUnderRecommendedMatchesLowRamVariant() {
        // Apagar la caché a mano bajo G99 Recomendado es
        // indistinguible del primer arranque lowRam (en ese preset
        // la caché es lo único que cambia la variante): el indicador
        // muestra "Perfil conservador (poca RAM)". Ambigüedad ya
        // documentada en R4-P0-9, no introducida aquí.
        val manual = presetSettings(GpuProfileId.G99_RECOMMENDED).copy(textureCache = false)
        val applied = preserveManualCacheChoices(presetSettings(GpuProfileId.G99_RECOMMENDED), manual)
        assertFalse(applied.textureCache)
        assertTrue(divergesFromPreset(applied))
        assertTrue(isLowRamConservativeVariant(applied))
    }
}
