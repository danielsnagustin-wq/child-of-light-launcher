package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.Vita3kConfigGenerator
import com.danielsnagustin.childoflight.launcher.model.AudioBackend
import com.danielsnagustin.childoflight.launcher.model.GameLanguage
import com.danielsnagustin.childoflight.launcher.model.GpuDriver
import com.danielsnagustin.childoflight.launcher.model.GpuProfileId
import com.danielsnagustin.childoflight.launcher.model.LauncherSettings
import com.danielsnagustin.childoflight.launcher.model.MemoryMapping
import com.danielsnagustin.childoflight.launcher.model.maskBit
import com.danielsnagustin.childoflight.launcher.model.PerformanceOverlayDetail
import com.danielsnagustin.childoflight.launcher.model.PerformanceOverlayPosition
import com.danielsnagustin.childoflight.launcher.model.apply60FpsMode
import com.danielsnagustin.childoflight.launcher.model.applySafeMode
import com.danielsnagustin.childoflight.launcher.model.presetSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests nuevos de la ronda 0.2.66, FASE 0 (arreglos del launcher).
 * Cubren AUD-P0-1 (de rebote, vía el invariante), G11-02, G11-01 y
 * G11-07. La compilación local no es viable en esta VM (ver TOOLS.md):
 * la lógica se verifica por lectura (ver informe de la fase) y CI
 * ejecuta estos tests.
 */
class R11Fase0Test {

    // --- G11-02: el método efectivo se resuelve al generar ---

    @Test
    fun knownMaskAppliesEffectiveMappingMethod() {
        // Máscara que solo tiene Double Buffer (bit 2): pedir Page
        // Table debe emitir Double Buffer (el efectivo), no el pedido.
        val maskDoubleOnly = MemoryMapping.DOUBLE_BUFFER.maskBit()
        val yaml = Vita3kConfigGenerator.generate(
            LauncherSettings(memoryMapping = MemoryMapping.PAGE_TABLE),
            "/data/vita3k",
            memoryMappingMask = maskDoubleOnly,
        )
        assertTrue(yaml.contains("memory-mapping: \"double-buffer\""))
        assertFalse(yaml.contains("memory-mapping: \"page-table\""))
        // Y queda explicado en el propio config.yml.
        assertTrue(yaml.contains("no está en la máscara soportada"))
    }

    @Test
    fun knownMaskKeepsRequestedMethodWhenSupported() {
        // Máscara con Double Buffer + Page Table: el pedido se emite tal cual.
        val mask = MemoryMapping.DOUBLE_BUFFER.maskBit() or MemoryMapping.PAGE_TABLE.maskBit()
        val yaml = Vita3kConfigGenerator.generate(
            LauncherSettings(memoryMapping = MemoryMapping.PAGE_TABLE),
            "/data/vita3k",
            memoryMappingMask = mask,
        )
        assertTrue(yaml.contains("memory-mapping: \"page-table\""))
        // Pedir Native Buffer con una máscara que lo tiene: se emite.
        val nativeMask = mask or MemoryMapping.NATIVE_BUFFER.maskBit()
        val yamlNative = Vita3kConfigGenerator.generate(
            LauncherSettings(memoryMapping = MemoryMapping.NATIVE_BUFFER),
            "/data/vita3k",
            memoryMappingMask = nativeMask,
        )
        assertTrue(yamlNative.contains("memory-mapping: \"native-buffer\""))
        // Pedir Native Buffer con una máscara que NO lo tiene: cae al efectivo.
        val yamlNativeUnsupported = Vita3kConfigGenerator.generate(
            LauncherSettings(memoryMapping = MemoryMapping.NATIVE_BUFFER),
            "/data/vita3k",
            memoryMappingMask = mask,
        )
        assertTrue(yamlNativeUnsupported.contains("memory-mapping: \"double-buffer\""))
    }

    @Test
    fun unknownMaskKeepsRequestedMethod() {
        // Máscara desconocida (null o 0): se deja el pedido, sin afirmar nada.
        for (mask in listOf<Int?>(null, 0)) {
            val yaml = Vita3kConfigGenerator.generate(
                LauncherSettings(memoryMapping = MemoryMapping.NATIVE_BUFFER),
                "/data/vita3k",
                memoryMappingMask = mask,
            )
            assertTrue(yaml.contains("memory-mapping: \"native-buffer\""))
        }
        // Sin máscara (parámetro por defecto): mismo comportamiento.
        val yamlDefault = Vita3kConfigGenerator.generate(
            LauncherSettings(memoryMapping = MemoryMapping.NATIVE_BUFFER),
            "/data/vita3k",
        )
        assertTrue(yamlDefault.contains("memory-mapping: \"native-buffer\""))
    }

    // --- G11-01: default Mali/G99 a Page Table; Adreno sin cambio ---

    @Test
    fun maliAndG99DefaultsArePageTable() {
        assertEquals(MemoryMapping.PAGE_TABLE, LauncherSettings().memoryMapping)
        for (profile in listOf(
            GpuProfileId.G99_RECOMMENDED,
            GpuProfileId.G99_QUALITY,
            GpuProfileId.GENERIC_QUALITY,
            GpuProfileId.MALI_PERFORMANCE,
            GpuProfileId.MALI_BALANCED,
        )) {
            assertEquals(
                "preset $profile debe ser Page Table (G11-01)",
                MemoryMapping.PAGE_TABLE,
                presetSettings(profile).memoryMapping,
            )
        }
    }

    @Test
    fun adrenoPresetsUnchangedStillPageTable() {
        // Adreno YA era Page Table: G11-01 no lo toca.
        assertEquals(MemoryMapping.PAGE_TABLE, presetSettings(GpuProfileId.ADRENO_PERFORMANCE).memoryMapping)
        assertEquals(MemoryMapping.PAGE_TABLE, presetSettings(GpuProfileId.ADRENO_QUALITY_TURNIP).memoryMapping)
    }

    @Test
    fun safeModeAnd60FpsModeStillForceDoubleBuffer() {
        // El modo seguro y el modo 60 FPS SIGUEN forzando Double
        // Buffer a propósito (el menos dependiente del driver): G11-01
        // no los toca.
        assertEquals(MemoryMapping.DOUBLE_BUFFER, applySafeMode(LauncherSettings()).memoryMapping)
        assertEquals(MemoryMapping.DOUBLE_BUFFER, apply60FpsMode(LauncherSettings(fpsHack = true)).memoryMapping)
    }

    // --- G11-07: validation-layer emitido y válido ---

    @Test
    fun validationLayerEmittedAndValid() {
        val yaml = Vita3kConfigGenerator.generate(LauncherSettings(), "/data/vita3k")
        assertTrue(yaml.contains("validation-layer: false"))
        assertTrue(Vita3kConfigGenerator.validateGeneratedConfig(yaml).isEmpty())
    }

    // --- Invariante AUD-P0-1: TODAS las claves que emite generate()
    // pasan validateGeneratedConfig (para que no se repita jamás) ---

    private fun emittedKeys(yaml: String): List<String> = yaml.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { it.substringBefore(": ") }

    @Test
    fun everyEmittedKeyValidates() {
        val variants = listOf(
            "defaults" to LauncherSettings(),
            "G99 recomendado" to presetSettings(GpuProfileId.G99_RECOMMENDED),
            "G99 calidad 2x" to presetSettings(GpuProfileId.G99_QUALITY),
            "genérico 2x" to presetSettings(GpuProfileId.GENERIC_QUALITY),
            "Mali rendimiento" to presetSettings(GpuProfileId.MALI_PERFORMANCE),
            "Mali equilibrado" to presetSettings(GpuProfileId.MALI_BALANCED),
            "Adreno rendimiento" to presetSettings(GpuProfileId.ADRENO_PERFORMANCE),
            "Adreno Turnip" to presetSettings(GpuProfileId.ADRENO_QUALITY_TURNIP),
            "modo seguro" to applySafeMode(LauncherSettings()),
            "modo 60 FPS" to apply60FpsMode(LauncherSettings(fpsHack = true)),
            "Cubeb" to LauncherSettings(audioBackend = AudioBackend.CUBEB),
            "NGS OFF" to LauncherSettings(ngsEnable = false),
            "PSN OFF" to LauncherSettings(psnSignedIn = false),
            "inglés" to LauncherSettings(gameLanguage = GameLanguage.ENGLISH_GB),
            "HUD máximo" to LauncherSettings(
                showFpsOverlay = true,
                performanceOverlayDetail = PerformanceOverlayDetail.MAXIMUM,
                performanceOverlayPosition = PerformanceOverlayPosition.BOTTOM_RIGHT,
            ),
            "Turnip instalado" to LauncherSettings(
                gpuDriver = GpuDriver.TURNIP,
                turnipInstalled = true,
                customDriverName = "Turnip_v26.0.0_R8_Gmem",
            ),
        ) + MemoryMapping.entries.map { mapping ->
            "mapeo $mapping" to LauncherSettings(memoryMapping = mapping)
        }
        for ((name, settings) in variants) {
            val yaml = Vita3kConfigGenerator.generate(settings, "/data/vita3k")
            assertTrue(
                "variante $name: ${Vita3kConfigGenerator.validateGeneratedConfig(yaml)}",
                Vita3kConfigGenerator.validateGeneratedConfig(yaml).isEmpty(),
            )
        }
        // Y con máscara que fuerza el fallback (G11-02): las claves
        // no cambian, solo el valor del mapeo.
        val yamlMasked = Vita3kConfigGenerator.generate(
            LauncherSettings(memoryMapping = MemoryMapping.PAGE_TABLE),
            "/data/vita3k",
            memoryMappingMask = MemoryMapping.DOUBLE_BUFFER.maskBit(),
        )
        assertTrue(Vita3kConfigGenerator.validateGeneratedConfig(yamlMasked).isEmpty())
    }

    @Test
    fun emittedKeySetIsExactlyThe31KnownKeys() {
        // Ronda 0.2.66 (integración, hallazgo Codex paso 1): el set
        // era de 29 y generate() ya emite 31: faltaban
        // audio-device-sample-frames (Fase 2, AUD-P2-1) y
        // exp-audio-sdl (interruptores, R1). Si una fase futura
        // añade otra clave, ESTE test es el que debe crecer con
        // ella (regla de oro AUD-P0-1).
        val expected = setOf(
            "backend-renderer", "resolution-multiplier", "screen-filter",
            "v-sync", "anisotropic-filtering", "async-pipeline-compilation",
            "high-accuracy", "validation-layer", "disable-surface-sync",
            "memory-mapping", "texture-cache", "shader-cache",
            "custom-driver-name", "cpu-opt", "fps-hack", "turbo-mode",
            "file-loading-delay", "audio-backend", "audio-volume",
            "audio-device-sample-frames", "exp-audio-sdl",
            "ngs-enable", "performance-overlay", "performance-overlay-detail",
            "performance-overlay-position", "hashless-texture-cache",
            "show-compile-shaders", "show-welcome", "psn-signed-in",
            "sys-lang", "pref-path",
        )
        assertEquals(31, expected.size)
        val yaml = Vita3kConfigGenerator.generate(
            presetSettings(GpuProfileId.G99_RECOMMENDED),
            "/data/vita3k",
        )
        val keys = emittedKeys(yaml)
        assertTrue("sin duplicados", keys.size == keys.toSet().size)
        assertEquals(expected, keys.toSet())
    }
}
