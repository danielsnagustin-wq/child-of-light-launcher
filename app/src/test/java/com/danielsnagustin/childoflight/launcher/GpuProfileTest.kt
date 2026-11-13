package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.GpuDetector
import com.danielsnagustin.childoflight.launcher.model.GpuBackend
import com.danielsnagustin.childoflight.launcher.model.GpuDriver
import com.danielsnagustin.childoflight.launcher.model.GpuInfo
import com.danielsnagustin.childoflight.launcher.model.GpuProfileId
import com.danielsnagustin.childoflight.launcher.model.GpuVendor
import com.danielsnagustin.childoflight.launcher.model.LauncherSettings
import com.danielsnagustin.childoflight.launcher.model.apply60FpsMode
import com.danielsnagustin.childoflight.launcher.model.applyLowRamDefaults
import com.danielsnagustin.childoflight.launcher.model.applySafeMode
import com.danielsnagustin.childoflight.launcher.model.defaultProfileFor
import com.danielsnagustin.childoflight.launcher.model.divergesFromPreset
import com.danielsnagustin.childoflight.launcher.model.isLowRamConservativeVariant
import com.danielsnagustin.childoflight.launcher.model.presetSettings
import com.danielsnagustin.childoflight.launcher.model.preserveManualCacheChoices
import com.danielsnagustin.childoflight.launcher.model.profilesFor
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
        // Ronda 0.2.66 (G11-01): Page Table por defecto en Mali/G99.
        assertEquals("PAGE_TABLE", s.memoryMapping.name)
        assertEquals(true, s.cpuOpt)
        assertEquals("SDL", s.audioBackend.name)
        assertEquals(true, s.ngsEnable)
    }

    @Test
    fun genericQualityPresetIs2xBilinear() {
        // Ronda 0.2.65 (RG-P2-16): el 2x genérico para GPU sin perfil
        // propio (Xclipse, PowerVR, OTHER, UNKNOWN). Mismos valores
        // que G99 Calidad (2x, Bilinear, Page Table, sin HUD,
        // Sistema) con etiqueta neutra: no se ofrece "G99 — Calidad"
        // a quien no tiene un G99 (G-P2-6).
        val s = presetSettings(GpuProfileId.GENERIC_QUALITY)
        assertEquals(2f, s.resolutionMultiplier, 0.001f)
        assertEquals("BILINEAR", s.screenFilter.name)
        // Ronda 0.2.66 (G11-01): Page Table por defecto (antes Double Buffer).
        assertEquals("PAGE_TABLE", s.memoryMapping.name)
        assertEquals("SYSTEM", s.gpuDriver.name)
        assertFalse(s.showFpsOverlay)
        // Ronda 0.2.68 (load-p1): ya NO son idénticos: G99_QUALITY
        // lleva skipShaderPrecompile = true (carga rápida en Mali/G99)
        // y el genérico lo deja en false (upstream: GPU sin perfil
        // propio, sin medición de carga que justifique saltar el
        // precompilado). La igualdad vale salvo esa diferencia.
        assertEquals(
            s.copy(skipShaderPrecompile = true),
            presetSettings(GpuProfileId.G99_QUALITY).copy(activeProfile = GpuProfileId.GENERIC_QUALITY),
        )
        assertFalse(s.skipShaderPrecompile)
    }

    @Test
    fun g99QualityPresetIs2xWithBilinear() {
        // Ronda 0.2.65 (R-P1-8): 2x con Bilinear, sin FSR: a 2x el FSR
        // no ahorra un píxel (2 pasadas compute sobre la imagen ya
        // renderizada) y puede caer en silencio a Bilinear.
        val s = presetSettings(GpuProfileId.G99_QUALITY)
        assertEquals(2f, s.resolutionMultiplier, 0.001f)
        assertEquals("BILINEAR", s.screenFilter.name)
    }

    @Test
    fun maliGenericNeverRecommends2x() {
        val perf = presetSettings(GpuProfileId.MALI_PERFORMANCE)
        val balanced = presetSettings(GpuProfileId.MALI_BALANCED)
        assertEquals(1f, perf.resolutionMultiplier, 0.001f)
        assertEquals(1f, balanced.resolutionMultiplier, 0.001f)
        // Ronda 0.2.66 (G11-01): Page Table por defecto en Mali.
        assertEquals("PAGE_TABLE", perf.memoryMapping.name)
        assertEquals("PAGE_TABLE", balanced.memoryMapping.name)
        // Ronda 0.2.65 (R-P1-5): Mali Rendimiento ya NO se diferencia
        // por la caché de texturas (inerte en Vulkan): ambos presets
        // la llevan ON, igual que G99 Recomendado.
        assertEquals(true, perf.textureCache)
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

    // Ronda 0.2.64 (P1-3 Adreno + H-05): el auto-revert es por
    // resolución EFECTIVA, no por id de perfil: cualquier 2x
    // (Adreno Calidad/Turnip, o un 2x puesto a mano) revierte por
    // debajo de 28 fps; un 1x nunca revierte.
    @Test
    fun autoRevertGeneralizedToAny2xProfile() {
        assertTrue(shouldAutoRevertTo1x(27f, GpuProfileId.ADRENO_QUALITY_TURNIP, true))
        assertFalse(shouldAutoRevertTo1x(28f, GpuProfileId.ADRENO_QUALITY_TURNIP, true))
        assertFalse(shouldAutoRevertTo1x(20f, GpuProfileId.MALI_BALANCED, true))
        assertFalse(shouldAutoRevertTo1x(20f, GpuProfileId.ADRENO_PERFORMANCE, true))
        // Sobrecarga por resolución efectiva (2x manual sobre un perfil 1x).
        assertTrue(shouldAutoRevertTo1x(27f, 2f, true))
        assertFalse(shouldAutoRevertTo1x(27f, 1.5f, true))
        assertFalse(shouldAutoRevertTo1x(27f, 1f, true))
        assertFalse(shouldAutoRevertTo1x(27f, 2f, false))
        assertFalse(shouldAutoRevertTo1x(null, 2f, true))
    }

    // Ronda 0.2.64 (P1-4 Adreno): el modo seguro neutraliza Turnip
    // (driver del sistema) pero conserva el estado instalado para
    // poder reactivarlo al salir.
    @Test
    fun safeModeNeutralizesTurnipButKeepsInstalledState() {
        val withTurnip = LauncherSettings(
            gpuDriver = GpuDriver.TURNIP,
            turnipInstalled = true,
            turnipVersion = "Turnip_v26.0.0_R8_Gmem",
            customDriverName = "Turnip_v26.0.0_R8_Gmem",
        )
        val safe = applySafeMode(withTurnip)
        assertEquals(GpuDriver.SYSTEM, safe.gpuDriver)
        assertTrue(safe.turnipInstalled)
        assertEquals("Turnip_v26.0.0_R8_Gmem", safe.customDriverName)
        assertEquals("Turnip_v26.0.0_R8_Gmem", safe.turnipVersion)
    }

    // Ronda 0.2.64 (V-Sync): el modo 60 FPS YA NO fuerza vSync = true
    // (en Vulkan es inerte; su único mecanismo real es fps-hack).
    // Tampoco lo lleva el preset Adreno Calidad/Turnip.
    @Test
    fun sixtyFpsModeAndTurnipPresetDoNotForceVSync() {
        assertFalse(apply60FpsMode(LauncherSettings(vSync = false)).vSync)
        assertTrue(apply60FpsMode(LauncherSettings(vSync = false)).fpsHack)
        assertFalse(presetSettings(GpuProfileId.ADRENO_QUALITY_TURNIP).vSync)
    }

    // Ronda 0.2.64 (H-07): isMaliG57 anclado a "MALI-G57": un "G57"
    // suelto en otro renderer ya no casa.
    @Test
    fun isMaliG57AnchoredToMaliPrefix() {
        assertTrue(GpuInfo(vendor = GpuVendor.MALI, renderer = "Mali-G57 MC2").isMaliG57)
        assertTrue(GpuInfo(vendor = GpuVendor.MALI, renderer = "Mali G57 MP2").isMaliG57)
        assertFalse(GpuInfo(vendor = GpuVendor.OTHER, renderer = "Foo G57").isMaliG57)
        assertFalse(GpuInfo(vendor = GpuVendor.OTHER, renderer = "G57").isMaliG57)
    }

    // Ronda 0.2.64 (H-03): PowerVR por gama (DXT/CXTP = gama alta).
    @Test
    fun isPowerVrDxtDetectsHighEndPowerVr() {
        assertTrue(GpuInfo(vendor = GpuVendor.POWERVR, renderer = "PowerVR DXT-48-1536").isPowerVrDxt)
        assertTrue(GpuInfo(vendor = GpuVendor.POWERVR, renderer = "Imagination CXTP-48-1536").isPowerVrDxt)
        assertFalse(GpuInfo(vendor = GpuVendor.POWERVR, renderer = "PowerVR Rogue GE8320").isPowerVrDxt)
        assertFalse(GpuInfo(vendor = GpuVendor.MALI, renderer = "Mali-G57 MC2").isPowerVrDxt)
    }

    // Ronda 0.2.64 (P2-4 Mali): pasos intermedios de resolución que
    // el núcleo acepta (resolution-multiplier es float sin límite).
    @Test
    fun resolutionLabelHasIntermediateSteps() {
        assertEquals("1.25x (1200x680)", LauncherSettings(resolutionMultiplier = 1.25f).resolutionLabel)
        assertEquals("1.5x (1440x816)", LauncherSettings(resolutionMultiplier = 1.5f).resolutionLabel)
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
        // Ronda 0.2.66 (G11-01): Page Table por defecto en Mali.
        assertEquals("PAGE_TABLE", s.memoryMapping.name)
        // Ronda 0.2.66: esta aserción estaba obsoleta en 0.2.65
        // (R-P1-5 ya puso textureCache = true en MALI_PERFORMANCE y
        // el test seguía esperando false; nunca se ejecutó porque el
        // run #441 murió en compilación antes de los tests).
        assertEquals(true, s.textureCache)
    }

    @Test
    fun unknownAndOtherGpuNeverLackAProfile() {
        val unknown = GpuInfo(vendor = GpuVendor.UNKNOWN, renderer = "No detectado", vulkanSupported = false)
        assertEquals(GpuProfileId.MALI_PERFORMANCE, defaultProfileFor(unknown))
        assertEquals(GpuVendor.UNKNOWN, GpuDetector.classify("", ""))
        // Resto (Apple, emuladores): OTHER -> Mali seguro.
        val other = GpuInfo(vendor = GpuVendor.OTHER, renderer = "Apple A17", vulkanSupported = true)
        assertEquals(GpuProfileId.MALI_PERFORMANCE, defaultProfileFor(other))
        assertEquals(GpuVendor.OTHER, GpuDetector.classify("Apple A17", "Apple"))
    }

    // Ronda 0.2.64 (H-02): Xclipse (Samsung/AMD RDNA) tiene vendor
    // propio: se clasifica por renderer "Xclipse" o por SoC Exynos,
    // y su perfil por defecto es el Mali seguro (provisional, sin
    // datos de teléfono todavía). Un Exynos con Mali sigue siendo
    // Mali (la rama Mali va primero).
    @Test
    fun xclipseIsClassifiedAndGetsMaliSafeProfile() {
        assertEquals(GpuVendor.XCLIPSE, GpuDetector.classify("Samsung Xclipse 920", "Samsung"))
        assertEquals(GpuVendor.XCLIPSE, GpuDetector.classify("Samsung Xclipse 940", "Samsung"))
        assertEquals(GpuVendor.XCLIPSE, GpuDetector.classify("Vulkan Device", "Samsung", "Exynos 2400"))
        assertEquals(GpuVendor.MALI, GpuDetector.classify("Mali-G78", "ARM", "Exynos 2100"))
        val gpu = GpuInfo(vendor = GpuVendor.XCLIPSE, renderer = "Samsung Xclipse 940", socModel = "Exynos 2400", vulkanSupported = true)
        assertEquals(GpuProfileId.MALI_PERFORMANCE, defaultProfileFor(gpu))
        assertEquals("Xclipse (Samsung Xclipse 940)", gpu.shortLabel)
    }

    // Ronda 0.2.64 (H-01): profilesFor — la lista de presets por GPU.
    // Sin GPU no se ofrece nada; fuera de Adreno nunca se ofrece el
    // preset Turnip; la rama genérica solo ofrece los dos seguros.
    @Test
    fun profilesForListsSafePresetsPerGpu() {
        assertTrue(profilesFor(null).isEmpty())
        val adreno = GpuInfo(vendor = GpuVendor.ADRENO, renderer = "Adreno 730", vulkanSupported = true)
        // Ronda 0.2.65 (G-P2-6): en Adreno SOLO presets Adreno.
        // G99_RECOMMENDED se colaba antes (etiqueta de otro GPU).
        assertEquals(
            listOf(GpuProfileId.ADRENO_PERFORMANCE, GpuProfileId.ADRENO_QUALITY_TURNIP),
            profilesFor(adreno),
        )
        assertEquals(
            listOf(GpuProfileId.G99_RECOMMENDED, GpuProfileId.G99_QUALITY, GpuProfileId.MALI_BALANCED),
            profilesFor(g99Gpu()),
        )
        val mali = GpuInfo(vendor = GpuVendor.MALI, renderer = "Mali-G710", vulkanSupported = true)
        assertEquals(listOf(GpuProfileId.MALI_PERFORMANCE, GpuProfileId.MALI_BALANCED), profilesFor(mali))
        val safe = listOf(GpuProfileId.MALI_PERFORMANCE, GpuProfileId.MALI_BALANCED, GpuProfileId.GENERIC_QUALITY)
        assertEquals(safe, profilesFor(GpuInfo(vendor = GpuVendor.POWERVR, renderer = "PowerVR Rogue GE8320")))
        assertEquals(safe, profilesFor(GpuInfo(vendor = GpuVendor.XCLIPSE, renderer = "Samsung Xclipse 940")))
        assertEquals(safe, profilesFor(GpuInfo(vendor = GpuVendor.OTHER, renderer = "Apple A17")))
        assertEquals(safe, profilesFor(GpuInfo(vendor = GpuVendor.UNKNOWN, renderer = "No detectado")))
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
        // Los valores del preset sí se aplican: 2x con Bilinear
        // (ronda 0.2.65, R-P1-8: ya no 2x con FSR).
        assertEquals(2f, applied.resolutionMultiplier, 0.001f)
        assertEquals("BILINEAR", applied.screenFilter.name)
        assertEquals(GpuProfileId.G99_QUALITY, applied.activeProfile)
    }

    @Test
    fun preserveManualCacheChoicesWithNoCurrentKeepsPreset() {
        val applied = preserveManualCacheChoices(presetSettings(GpuProfileId.MALI_PERFORMANCE), null)
        assertEquals(presetSettings(GpuProfileId.MALI_PERFORMANCE), applied)
        // Ronda 0.2.65 (R-P1-5): Mali Rendimiento lleva la caché de
        // texturas ON, igual que el resto de presets.
        assertTrue(applied.textureCache)
    }

    @Test
    fun lowRamFirstBootOnG99RecommendedIsIdentity() {
        // Ronda 0.2.66 (G11-01, decisión del coordinador): la
        // variante lowRam fuerza Page Table (antes Double Buffer),
        // así que el primer arranque en 4 GB sobre G99 Recomendado
        // ES idéntico al preset: no hay divergencia conservadora que
        // registrar (la UI no muestra "perfil conservador" sin motivo).
        // La caché sigue intacta.
        val firstBoot = applyLowRamDefaults(presetSettings(GpuProfileId.G99_RECOMMENDED))
        assertTrue(firstBoot.textureCache)
        assertEquals(
            presetSettings(GpuProfileId.G99_RECOMMENDED),
            firstBoot,
        )
        assertFalse(isLowRamConservativeVariant(firstBoot))
        assertFalse(divergesFromPreset(firstBoot))
        val applied = preserveManualCacheChoices(firstBoot, LauncherSettings(), lowRam = true)
        assertTrue(applied.textureCache)
        assertFalse(divergesFromPreset(applied))
    }

    @Test
    fun lowRamFirstBootOn2xPresetDropsTo1xAndDiverges() {
        // Sobre un preset 2x la variante conservadora SÍ existe: baja
        // a 1x con Bilinear y queda registrada como divergencia
        // conservadora (la UI lo muestra como "perfil conservador").
        val firstBoot = applyLowRamDefaults(presetSettings(GpuProfileId.G99_QUALITY))
        assertEquals(1f, firstBoot.resolutionMultiplier, 0.001f)
        assertEquals("BILINEAR", firstBoot.screenFilter.name)
        assertTrue(firstBoot.textureCache)
        assertTrue(isLowRamConservativeVariant(firstBoot))
        assertTrue(divergesFromPreset(firstBoot))
        val applied = preserveManualCacheChoices(firstBoot, LauncherSettings(), lowRam = true)
        assertEquals(firstBoot, applied)
        assertTrue(divergesFromPreset(applied))
    }

    @Test
    fun driverOnlyChangeDoesNotCountAsDivergence() {
        // Ronda 0.2.65 (G-P2-9): el driver es ortogonal al preset
        // gráfico (tiene su propia sección y su propio estado). El
        // Turnip incluido del primer arranque se instala encima del
        // preset Rendimiento sin que la UI marque "Perfil modificado".
        val adrenoWithTurnip = presetSettings(GpuProfileId.ADRENO_PERFORMANCE).copy(
            gpuDriver = com.danielsnagustin.childoflight.launcher.model.GpuDriver.TURNIP,
        )
        assertFalse(divergesFromPreset(adrenoWithTurnip))
        val g99WithTurnip = presetSettings(GpuProfileId.G99_RECOMMENDED).copy(
            gpuDriver = com.danielsnagustin.childoflight.launcher.model.GpuDriver.TURNIP,
        )
        assertFalse(divergesFromPreset(g99WithTurnip))
        // Un cambio gráfico SÍ sigue marcando divergencia.
        assertTrue(divergesFromPreset(presetSettings(GpuProfileId.G99_RECOMMENDED).copy(resolutionMultiplier = 2f)))
    }

    @Test
    fun skipShaderPrecompileOnlyChangeCountsAsDivergence() {
        // Ronda 0.2.68 (load-p1): la carga rápida ES campo de preset
        // (los presets Mali/G99 la llevan en true): apagarla solo a
        // ella marca "perfil modificado".
        assertTrue(divergesFromPreset(presetSettings(GpuProfileId.G99_RECOMMENDED).copy(skipShaderPrecompile = false)))
    }

    @Test
    fun g99RecommendedAndMaliPerformanceAreIdenticalExceptProfile() {
        // Ronda 0.2.65 (R-P1-5): la caché de texturas era la ÚNICA
        // diferencia entre ambos presets y ahora los dos la llevan
        // ON: son el mismo preset en efecto (misma resolución, mismo
        // filtro, mismo mapeo, mismas cachés). Mali Rendimiento sigue
        // existiendo como entrada propia para Mali genérico (etiqueta
        // y defaultProfileFor), pero ya no miente con una diferencia
        // que no existe en el teléfono.
        val g99 = presetSettings(GpuProfileId.G99_RECOMMENDED)
        val mali = presetSettings(GpuProfileId.MALI_PERFORMANCE)
        assertEquals(g99, mali.copy(activeProfile = GpuProfileId.G99_RECOMMENDED))
        assertTrue(g99.textureCache)
        assertTrue(mali.textureCache)
    }

    @Test
    fun maliPerformanceOverG99StateKeepsTextureCacheAndMatchesPreset() {
        // Aplicar Mali Rendimiento con la caché manual ON conservada
        // deja textureCache ON. Ronda 0.2.65 (R-P1-5): los presets
        // convergieron (Mali Rendimiento ya lleva la caché ON), así
        // que el resultado COINCIDE con el preset y ya NO se ve como
        // "Perfil modificado": el aviso explícito de R4-P1-13 ya no
        // salta en este camino (sigue saltando cuando el manual sí
        // difiere del preset, ver manualCacheToggleUnderQuality).
        val applied = preserveManualCacheChoices(
            presetSettings(GpuProfileId.MALI_PERFORMANCE),
            presetSettings(GpuProfileId.G99_RECOMMENDED),
        )
        assertTrue(applied.textureCache)
        assertEquals(GpuProfileId.MALI_PERFORMANCE, applied.activeProfile)
        assertFalse(divergesFromPreset(applied))
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
    fun manualCacheToggleUnderRecommendedDivergesButIsNotLowRamVariant() {
        // Apagar la caché a mano bajo G99 Recomendado diverge del
        // preset, pero ya NO es la variante lowRam: ronda 0.2.65
        // (R-P1-5) sacó textureCache de los campos de la variante
        // (inerte en Vulkan) y la variante ya no toca la caché. La
        // ambigüedad documentada en R4-P0-9 ("indistinguible del
        // primer arranque lowRam") ya no existe: apagar la caché es
        // ahora una divergencia manual normal, no conservadora.
        val manual = presetSettings(GpuProfileId.G99_RECOMMENDED).copy(textureCache = false)
        val applied = preserveManualCacheChoices(presetSettings(GpuProfileId.G99_RECOMMENDED), manual)
        assertFalse(applied.textureCache)
        assertTrue(divergesFromPreset(applied))
        assertFalse(isLowRamConservativeVariant(applied))
    }
}
