package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.Vita3kConfigGenerator
import com.danielsnagustin.childoflight.launcher.model.AudioBackend
import com.danielsnagustin.childoflight.launcher.model.GpuBackend
import com.danielsnagustin.childoflight.launcher.model.GpuDriver
import com.danielsnagustin.childoflight.launcher.model.GpuInfo
import com.danielsnagustin.childoflight.launcher.model.GpuProfileId
import com.danielsnagustin.childoflight.launcher.model.GpuVendor
import com.danielsnagustin.childoflight.launcher.model.LauncherSettings
import com.danielsnagustin.childoflight.launcher.model.MemoryMapping
import com.danielsnagustin.childoflight.launcher.model.PerformanceOverlayDetail
import com.danielsnagustin.childoflight.launcher.model.PerformanceOverlayPosition
import com.danielsnagustin.childoflight.launcher.model.ScreenFilter
import com.danielsnagustin.childoflight.launcher.model.presetSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El generador solo emite claves verificadas en config.h de Vita3K.
 * Los ajustes sin clave verificada no se emiten en absoluto: no
 * existen en el modelo de ajustes (se eliminaron por no tener
 * efecto real, auditoría de funcionalidad 2026-11-08).
 */
class Vita3kConfigGeneratorTest {

    @Test
    fun g99RecommendedEmitsVerifiedKeys() {
        val yaml = Vita3kConfigGenerator.generate(presetSettings(GpuProfileId.G99_RECOMMENDED), "/data/vita3k")
        assertTrue(yaml.contains("backend-renderer: \"Vulkan\""))
        assertTrue(yaml.contains("resolution-multiplier: 1.0"))
        assertTrue(yaml.contains("screen-filter: \"Bilinear\""))
        assertTrue(yaml.contains("v-sync: false"))
        assertTrue(yaml.contains("anisotropic-filtering: 1"))
        assertTrue(yaml.contains("async-pipeline-compilation: true"))
        assertTrue(yaml.contains("high-accuracy: false"))
        // Surface Sync Disabled -> disable-surface-sync: true (inverso verificado).
        assertTrue(yaml.contains("disable-surface-sync: true"))
        assertTrue(yaml.contains("memory-mapping: \"double-buffer\""))
        assertTrue(yaml.contains("audio-backend: \"SDL\""))
        assertTrue(yaml.contains("audio-volume: 100"))
        assertTrue(yaml.contains("ngs-enable: true"))
        assertTrue(yaml.contains("cpu-opt: true"))
        assertTrue(yaml.contains("fps-hack: false"))
        assertTrue(yaml.contains("performance-overlay: false"))
    }

    @Test
    fun fpsHackForces60FpsPackage() {
        // Base adversa: 2x, FSR, sin VSync, page-table, cachés OFF (como el
        // perfil de gama baja): el modo 60 debe imponer su paquete completo.
        val yaml = Vita3kConfigGenerator.generate(
            LauncherSettings(
                resolutionMultiplier = 2f,
                screenFilter = ScreenFilter.FSR,
                vSync = false,
                memoryMapping = MemoryMapping.PAGE_TABLE,
                textureCache = false,
                shaderCache = false,
                fpsHack = true,
            ), "/data/vita3k",
        )
        assertTrue(yaml.contains("fps-hack: true"))
        assertTrue(yaml.contains("v-sync: true"))
        assertTrue(yaml.contains("resolution-multiplier: 1.0"))
        assertTrue(yaml.contains("screen-filter: \"Bilinear\""))
        assertTrue(yaml.contains("memory-mapping: \"double-buffer\""))
        assertTrue(yaml.contains("texture-cache: true"))
        assertTrue(yaml.contains("shader-cache: true"))
        assertTrue(yaml.contains("async-pipeline-compilation: true"))
        assertTrue(yaml.contains("high-accuracy: false"))
        assertTrue(yaml.contains("disable-surface-sync: true"))
        assertTrue(yaml.contains("cpu-opt: true"))
        assertTrue(yaml.contains("ngs-enable: true"))
    }

    @Test
    fun hashlessTextureCacheDefaultsOffAndEmitsWhenEnabled() {
        // Default (preset G99): OFF, como en upstream (default false,
        // config.h:308). El consumo está verificado en el renderer
        // (texture/cache.cpp): true = use_protect, las texturas grandes
        // se vigilan por protección de páginas en vez de re-hashear.
        val yamlDefault = Vita3kConfigGenerator.generate(presetSettings(GpuProfileId.G99_RECOMMENDED), "/data/vita3k")
        assertTrue(yamlDefault.contains("hashless-texture-cache: false"))
        val yamlOn = Vita3kConfigGenerator.generate(
            LauncherSettings(hashlessTextureCache = true), "/data/vita3k",
        )
        assertTrue(yamlOn.contains("hashless-texture-cache: true"))
    }

    @Test
    fun performanceOverlayDetailAndPositionEmitted() {
        // Defaults de upstream (config.h:362,364): MINIMUM = 0,
        // TOP_LEFT = 0.
        val yamlDefault = Vita3kConfigGenerator.generate(presetSettings(GpuProfileId.G99_RECOMMENDED), "/data/vita3k")
        assertTrue(yamlDefault.contains("performance-overlay-detail: 0"))
        assertTrue(yamlDefault.contains("performance-overlay-position: 0"))
        // Base adversa: máximo detalle, esquina opuesta.
        val yamlMax = Vita3kConfigGenerator.generate(
            LauncherSettings(
                showFpsOverlay = true,
                performanceOverlayDetail = PerformanceOverlayDetail.MAXIMUM,
                performanceOverlayPosition = PerformanceOverlayPosition.BOTTOM_RIGHT,
            ), "/data/vita3k",
        )
        assertTrue(yamlMax.contains("performance-overlay: true"))
        assertTrue(yamlMax.contains("performance-overlay-detail: 3"))
        assertTrue(yamlMax.contains("performance-overlay-position: 5"))
    }

    @Test
    fun compileShadersHiddenByDefault() {
        // El launcher oculta los mensajes por defecto (pantalla limpia),
        // aunque upstream los muestre por defecto (config.h:306, true).
        val yamlDefault = Vita3kConfigGenerator.generate(presetSettings(GpuProfileId.G99_RECOMMENDED), "/data/vita3k")
        assertTrue(yamlDefault.contains("show-compile-shaders: false"))
        val yamlShown = Vita3kConfigGenerator.generate(
            LauncherSettings(showCompileShaders = true), "/data/vita3k",
        )
        assertTrue(yamlShown.contains("show-compile-shaders: true"))
    }

    @Test
    fun surfaceSyncEnabledInvertsDisableFlag() {
        val yaml = Vita3kConfigGenerator.generate(
            LauncherSettings(surfaceSyncEnabled = true), "/data/vita3k",
        )
        assertTrue(yaml.contains("disable-surface-sync: false"))
    }

    @Test
    fun removedUnverifiedSettingsAreNeverEmitted() {
        val yaml = Vita3kConfigGenerator.generate(presetSettings(GpuProfileId.G99_RECOMMENDED), "/data/vita3k")
        // Los ajustes sin clave verificada se eliminaron del modelo:
        // no debe existir ninguna línea "clave: valor" ni mención
        // para ellos en el YAML generado.
        assertFalse(yaml.lines().any { it.startsWith("frame-limiter:") })
        assertFalse(yaml.lines().any { it.startsWith("frame-skipping:") })
        assertFalse(yaml.lines().any { it.startsWith("accurate-thread-scheduling:") })
        assertFalse(yaml.lines().any { it.startsWith("audio-buffer-size:") })
        assertFalse(yaml.contains("TODO-VERIFY"))
        assertFalse(yaml.contains("frame-limiter"))
    }

    @Test
    fun quality2xEmitsResolution20() {
        val yaml = Vita3kConfigGenerator.generate(presetSettings(GpuProfileId.G99_QUALITY), "/data/vita3k")
        assertTrue(yaml.contains("resolution-multiplier: 2.0"))
        assertTrue(yaml.contains("screen-filter: \"FSR\""))
    }

    @Test
    fun safeModeForcesSafeValues() {
        val yaml = Vita3kConfigGenerator.generate(
            LauncherSettings(safeMode = true, resolutionMultiplier = 4f), "/data/vita3k",
        )
        assertTrue(yaml.contains("resolution-multiplier: 1.0"))
        assertTrue(yaml.contains("audio-backend: \"SDL\""))
        assertTrue(yaml.contains("MODO SEGURO"))
    }

    // --- Compatibilidad por GPU (auditoría 2026-11-07) ---

    @Test
    fun turnipIsOnlyEmittedWhenDriverIsTurnip() {
        // Bug real: antes bastaba turnipInstalled + nombre guardado para
        // emitir custom-driver-name, así que cambiar a "Sistema" no
        // desactivaba Turnip. Ahora manda gpuDriver.
        val installed = LauncherSettings(
            gpuDriver = GpuDriver.SYSTEM,
            turnipInstalled = true,
            customDriverName = "turnip-24.1.0",
        )
        val yamlSystem = Vita3kConfigGenerator.generate(installed, "/data/vita3k")
        assertTrue(yamlSystem.contains("custom-driver-name: \"\""))
        assertFalse(yamlSystem.contains("turnip-24.1.0"))

        val active = installed.copy(gpuDriver = GpuDriver.TURNIP)
        val yamlTurnip = Vita3kConfigGenerator.generate(active, "/data/vita3k")
        assertTrue(yamlTurnip.contains("custom-driver-name: \"turnip-24.1.0\""))
    }

    @Test
    fun memoryMappingStringsAreVerifiedUpstream() {
        // Verificado contra el núcleo (pin 3e43bc80, renderer.cpp
        // VKState::late_init): los 4 strings se comparan exactos;
        // "off" cae en Disabled por fall-through. Ninguno debe salir
        // como TODO-VERIFY.
        val pageTable = Vita3kConfigGenerator.generate(
            LauncherSettings(memoryMapping = MemoryMapping.PAGE_TABLE), "/data/vita3k",
        )
        assertTrue(pageTable.contains("memory-mapping: \"page-table\""))
        val nativeBuffer = Vita3kConfigGenerator.generate(
            LauncherSettings(memoryMapping = MemoryMapping.NATIVE_BUFFER), "/data/vita3k",
        )
        assertTrue(nativeBuffer.contains("memory-mapping: \"native-buffer\""))
        val externalHost = Vita3kConfigGenerator.generate(
            LauncherSettings(memoryMapping = MemoryMapping.EXTERNAL_HOST), "/data/vita3k",
        )
        assertTrue(externalHost.contains("memory-mapping: \"external-host\""))
        val off = Vita3kConfigGenerator.generate(
            LauncherSettings(memoryMapping = MemoryMapping.OFF), "/data/vita3k",
        )
        assertTrue(off.contains("memory-mapping: \"off\""))
        assertFalse(pageTable.contains("TODO-VERIFY: string exacto de memory-mapping"))
    }

    @Test
    fun cubebAudioBackendIsVerifiedUpstream() {
        // Verificado contra el núcleo (pin 3e43bc80, audio.cpp
        // AudioState::set_backend): acepta exactamente "SDL" y "Cubeb".
        val yaml = Vita3kConfigGenerator.generate(
            LauncherSettings(audioBackend = AudioBackend.CUBEB), "/data/vita3k",
        )
        assertTrue(yaml.contains("audio-backend: \"Cubeb\""))
        assertFalse(yaml.contains("TODO-VERIFY: string exacto de Cubeb"))
    }

    @Test
    fun vulkanFallsBackToOpenGlWhenPhoneHasNoVulkan() {
        val noVulkan = GpuInfo(vendor = GpuVendor.MALI, renderer = "Mali-G57 MC2", vulkanSupported = false)
        val yaml = Vita3kConfigGenerator.generate(
            LauncherSettings(backend = GpuBackend.VULKAN), "/data/vita3k", noVulkan,
        )
        assertTrue(yaml.contains("backend-renderer: \"OpenGL\""))
        assertFalse(yaml.contains("backend-renderer: \"Vulkan\""))

        val withVulkan = noVulkan.copy(vulkanSupported = true, vulkanVersionLabel = "Vulkan 1.1+")
        val yamlVulkan = Vita3kConfigGenerator.generate(
            LauncherSettings(backend = GpuBackend.VULKAN), "/data/vita3k", withVulkan,
        )
        assertTrue(yamlVulkan.contains("backend-renderer: \"Vulkan\""))
    }

    @Test
    fun showWelcomeFalseAlwaysEmitted() {
        // Verificado en el pin (config.h:380): show-welcome existe,
        // bool, default true. El launcher siempre emite false: es un
        // port, no muestra la bienvenida del emulador. Su único
        // consumo verificado es la GUI Qt (main_window.cpp:1031-1037);
        // en Android/SDL ni siquiera se ejecuta ese diálogo.
        val yaml = Vita3kConfigGenerator.generate(
            LauncherSettings(), "/data/vita3k",
        )
        assertTrue(yaml.contains("show-welcome: false"))
        assertFalse(yaml.contains("show-welcome: true"))
    }

    @Test
    fun displayInfoMessageNeverEmittedAsRealKey() {
        // display-info-message NO existe en config.h (lectura completa
        // 0-465, pin 3e43bc80): nunca debe salir, ni como clave real
        // ni como comentario.
        val yaml = Vita3kConfigGenerator.generate(
            LauncherSettings(), "/data/vita3k",
        )
        assertFalse(yaml.contains("display-info-message: "))
        assertFalse(yaml.contains("display-info-message"))
    }

    @Test
    fun psnSignedInAlwaysEmitted() {
        // Verificado en el pin (config.h:398): psn-signed-in existe,
        // es INT, default false. Lo consumen sceNpCheckCallback y
        // sceNpGetServiceState (SceNpManager.cpp:131 y :149): con 0
        // el núcleo responde siempre SIGNED_OUT y los juegos que
        // preguntan el estado de sesión antes de activar sus DLC
        // nunca los aplican. El launcher emite 1 siempre: inicio de
        // sesión EMULADO, sin cuenta real ni red. Es int, no bool:
        // se emite "1", nunca "true" ni "0".
        val yaml = Vita3kConfigGenerator.generate(
            LauncherSettings(), "/data/vita3k",
        )
        assertTrue(yaml.contains("psn-signed-in: 1"))
        assertFalse(yaml.contains("psn-signed-in: 0"))
        assertFalse(yaml.contains("psn-signed-in: true"))
    }
}
