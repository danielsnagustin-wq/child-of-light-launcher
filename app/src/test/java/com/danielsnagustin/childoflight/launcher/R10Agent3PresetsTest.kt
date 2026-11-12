package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.SettingsRepository
import com.danielsnagustin.childoflight.launcher.data.Vita3kConfigGenerator
import com.danielsnagustin.childoflight.launcher.model.GpuBackend
import com.danielsnagustin.childoflight.launcher.model.GpuInfo
import com.danielsnagustin.childoflight.launcher.model.GpuProfileId
import com.danielsnagustin.childoflight.launcher.model.GpuVendor
import com.danielsnagustin.childoflight.launcher.model.LauncherSettings
import com.danielsnagustin.childoflight.launcher.model.MemoryMapping
import com.danielsnagustin.childoflight.launcher.model.apply60FpsMode
import com.danielsnagustin.childoflight.launcher.model.applyLowRamDefaults
import com.danielsnagustin.childoflight.launcher.model.applySafeMode
import com.danielsnagustin.childoflight.launcher.model.averageBenchmarkFps
import com.danielsnagustin.childoflight.launcher.model.isLowRamConservativeVariant
import com.danielsnagustin.childoflight.launcher.model.presetSettings
import com.danielsnagustin.childoflight.launcher.model.profilesFor
import com.danielsnagustin.childoflight.launcher.model.resolveBackend
import com.danielsnagustin.childoflight.launcher.model.resolveBackendReason
import com.danielsnagustin.childoflight.launcher.model.shouldAutoRevertTo1x
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests nuevos de la ronda 0.2.65, agente de arreglo 3/6 (render /
 * presets / config). Cubren solo los hallazgos de ese agente; los
 * tests compartidos que codificaban el comportamiento anterior
 * (GpuProfileTest, LowRamProfileTest, BenchmarkSamplingTest,
 * LogicAuditFixesTest) NO se editan: el coordinador debe
 * actualizarlos (ver informe del agente).
 */
class R10Agent3PresetsTest {

    // R-P1-5: texture-cache ya no diferencia MALI_PERFORMANCE de
    // G99_RECOMMENDED (inerte en Vulkan) ni lo toca la variante low-RAM.
    @Test
    fun maliPerformanceNoLongerDiffersByTextureCache() {
        assertTrue(presetSettings(GpuProfileId.MALI_PERFORMANCE).textureCache)
        assertTrue(presetSettings(GpuProfileId.G99_RECOMMENDED).textureCache)
        assertEquals(
            presetSettings(GpuProfileId.G99_RECOMMENDED).copy(activeProfile = GpuProfileId.MALI_PERFORMANCE),
            presetSettings(GpuProfileId.MALI_PERFORMANCE),
        )
    }

    @Test
    fun lowRamVariantNoLongerTouchesTextureCache() {
        val s = applyLowRamDefaults(presetSettings(GpuProfileId.G99_RECOMMENDED))
        assertTrue(s.textureCache)
        assertTrue(s.shaderCache)
        // Ronda 0.2.66 (G11-01, decisión del coordinador): la variante
        // fuerza Page Table (antes Double Buffer), así que sobre
        // G99 Recomendado la variante ES el preset: no hay divergencia
        // conservadora que registrar; la caché sigue sin tocarse.
        assertEquals(MemoryMapping.PAGE_TABLE, s.memoryMapping)
        assertEquals(presetSettings(GpuProfileId.G99_RECOMMENDED), s)
        assertFalse(isLowRamConservativeVariant(s))
        // Sobre un preset 2x sí lo es (baja a 1x con Bilinear).
        val quality = applyLowRamDefaults(presetSettings(GpuProfileId.G99_QUALITY))
        assertEquals(1f, quality.resolutionMultiplier, 0.001f)
        assertTrue(isLowRamConservativeVariant(quality))
    }

    // R-P1-8 + R-P1-17: G99 Calidad a 2x con Bilinear, sin HUD.
    @Test
    fun g99QualityIs2xBilinearWithoutHud() {
        val s = presetSettings(GpuProfileId.G99_QUALITY)
        assertEquals(2f, s.resolutionMultiplier, 0.001f)
        assertEquals("BILINEAR", s.screenFilter.name)
        assertFalse(s.showFpsOverlay)
    }

    // R-P1-9: Mali Equilibrado con Bilinear por defecto.
    @Test
    fun maliBalancedIsBilinearByDefault() {
        val s = presetSettings(GpuProfileId.MALI_BALANCED)
        assertEquals("BILINEAR", s.screenFilter.name)
        assertEquals(1f, s.resolutionMultiplier, 0.001f)
    }

    // RG-P2-6: Adreno Calidad/Turnip enciende el overlay FPS.
    @Test
    fun adrenoQualityTurnipEnablesFpsOverlay() {
        assertTrue(presetSettings(GpuProfileId.ADRENO_QUALITY_TURNIP).showFpsOverlay)
    }

    // R-P1-10 + G-P2-8: modo seguro fuerza cpuOpt y driver Sistema.
    @Test
    fun safeModeForcesCpuOptAndSystemDriver() {
        val s = applySafeMode(LauncherSettings(cpuOpt = false, gpuDriver = com.danielsnagustin.childoflight.launcher.model.GpuDriver.TURNIP))
        assertTrue(s.cpuOpt)
        assertEquals("SYSTEM", s.gpuDriver.name)
    }

    // RC-P2-4: modo 60 FPS fuerza fileLoadingDelay = 0.
    @Test
    fun sixtyFpsModeForcesFileLoadingDelayZero() {
        val s = apply60FpsMode(LauncherSettings(fileLoadingDelay = 30, fpsHack = false))
        assertEquals(0, s.fileLoadingDelay)
        assertTrue(s.fpsHack)
    }

    // G-P2-6: Adreno no ofrece etiquetas de otro GPU.
    @Test
    fun profilesForAdrenoHasNoG99Preset() {
        val adreno = GpuInfo(vendor = GpuVendor.ADRENO, renderer = "Adreno 730", vulkanSupported = true)
        assertEquals(
            listOf(GpuProfileId.ADRENO_PERFORMANCE, GpuProfileId.ADRENO_QUALITY_TURNIP),
            profilesFor(adreno),
        )
    }

    // G-P2-11: isPowerVrDxt exige vendor PowerVR/Imagination además del token.
    @Test
    fun isPowerVrDxtRequiresPowerVrVendor() {
        assertTrue(GpuInfo(vendor = GpuVendor.POWERVR, renderer = "PowerVR DXT-48-1536").isPowerVrDxt)
        assertTrue(GpuInfo(vendor = GpuVendor.POWERVR, renderer = "Imagination CXTP-48-1536").isPowerVrDxt)
        assertFalse(GpuInfo(vendor = GpuVendor.POWERVR, renderer = "PowerVR Rogue GE8320").isPowerVrDxt)
        // Un "DXT" en el renderer de otra GPU ya no casa.
        assertFalse(GpuInfo(vendor = GpuVendor.MALI, renderer = "Mali DXT-48").isPowerVrDxt)
        assertFalse(GpuInfo(vendor = GpuVendor.MALI, renderer = "Mali-G57 MC2").isPowerVrDxt)
    }

    // RG-P2-12: resolveBackend consulta la versión Vulkan reportada.
    @Test
    fun resolveBackendConsultsReportedVulkanVersion() {
        val settings = LauncherSettings(backend = GpuBackend.VULKAN)
        // Booleano true pero etiqueta "Sin Vulkan": contradicción -> OpenGL.
        val stale = GpuInfo(vendor = GpuVendor.MALI, renderer = "Mali-G57 MC2", vulkanSupported = true, vulkanVersionLabel = "Sin Vulkan")
        assertFalse(stale.hasUsableVulkan)
        assertEquals(GpuBackend.OPENGL, resolveBackend(settings, stale))
        assertTrue(resolveBackendReason(settings, stale)!!.contains("Sin Vulkan"))
        // Camino normal G99: Vulkan 1.1+ -> Vulkan.
        val g99 = GpuInfo(vendor = GpuVendor.MALI, renderer = "Mali-G57 MC2", socModel = "MT6789", vulkanSupported = true, vulkanVersionLabel = "Vulkan 1.1+")
        assertTrue(g99.hasUsableVulkan)
        assertEquals(GpuBackend.VULKAN, resolveBackend(settings, g99))
        assertNull(resolveBackendReason(settings, g99))
        // Sin GPU detectada no se cambia nada.
        assertEquals(GpuBackend.VULKAN, resolveBackend(settings, null))
    }

    // R-P1-19: la mediana no revierte por un tirón de shaders.
    @Test
    fun medianBenchmarkIgnoresShaderCompilationDip() {
        val samples = List(25) { 30f } + List(5) { 10f }
        assertEquals(30f, averageBenchmarkFps(samples)!!, 0.001f)
        assertFalse(shouldAutoRevertTo1x(averageBenchmarkFps(samples), GpuProfileId.G99_QUALITY, true))
        // Y un 2x realmente lento sí revierte (mediana 20 < 28).
        val slow = List(30) { 20f }
        assertEquals(20f, averageBenchmarkFps(slow)!!, 0.001f)
        assertTrue(shouldAutoRevertTo1x(averageBenchmarkFps(slow), GpuProfileId.G99_QUALITY, true))
        // Par de muestras: media de las dos centrales.
        assertEquals(45f, averageBenchmarkFps(listOf(30f, 60f))!!, 0.001f)
    }

    // W-P1-1: 1.25x y 1.5x ya no se recortan en el snap.
    @Test
    fun clampResolutionKeepsIntermediateSteps() {
        assertEquals(1.25f, SettingsRepository.clampResolution(1.25f), 0.001f)
        assertEquals(1.5f, SettingsRepository.clampResolution(1.5f), 0.001f)
        assertEquals(1.25f, SettingsRepository.clampResolution(1.2f), 0.001f)
        assertEquals(1.25f, SettingsRepository.clampResolution(1.375f), 0.001f)
        assertEquals(1.5f, SettingsRepository.clampResolution(1.75f), 0.001f)
        assertEquals(1f, SettingsRepository.clampResolution(Float.NaN))
        assertEquals(0.5f, SettingsRepository.clampResolution(0f))
        assertEquals(4f, SettingsRepository.clampResolution(99f))
    }

    // T-P2-4: validación del config generado.
    @Test
    fun generatedConfigValidatesAndSanitizes() {
        val yaml = Vita3kConfigGenerator.generate(presetSettings(GpuProfileId.G99_RECOMMENDED), "/data/vita3k")
        assertTrue(Vita3kConfigGenerator.validateGeneratedConfig(yaml).isEmpty())
        assertTrue(Vita3kConfigGenerator.validateGeneratedConfig("clave-inventada: true\n").isNotEmpty())
        assertTrue(Vita3kConfigGenerator.validateGeneratedConfig("v-sync: quizá\n").isNotEmpty())
        assertTrue(Vita3kConfigGenerator.validateGeneratedConfig("audio-volume: 500\n").isNotEmpty())
        assertTrue(Vita3kConfigGenerator.validateGeneratedConfig("resolution-multiplier: 9.0\n").isNotEmpty())
        // Saneado: valores inválidos van al default seguro, nunca al YAML.
        val bad = LauncherSettings(audioVolume = 500, fileLoadingDelay = -5, resolutionMultiplier = 99f, anisotropicFiltering = 3)
        assertTrue(Vita3kConfigGenerator.validateSettings(bad).size == 4)
        val safe = Vita3kConfigGenerator.sanitizedSettings(bad)
        assertEquals(100, safe.audioVolume)
        assertEquals(0, safe.fileLoadingDelay)
        assertEquals(4f, safe.resolutionMultiplier, 0.001f)
        assertEquals(1, safe.anisotropicFiltering)
        val safeYaml = Vita3kConfigGenerator.generate(bad, "/data/vita3k")
        assertTrue(Vita3kConfigGenerator.validateGeneratedConfig(safeYaml).isEmpty())
        assertTrue(safeYaml.contains("audio-volume: 100"))
        assertTrue(safeYaml.contains("resolution-multiplier: 4.0"))
    }
}
