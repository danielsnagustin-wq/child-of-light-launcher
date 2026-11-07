package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.Vita3kConfigGenerator
import com.danielsnagustin.childoflight.launcher.model.GpuProfileId
import com.danielsnagustin.childoflight.launcher.model.LauncherSettings
import com.danielsnagustin.childoflight.launcher.model.presetSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El generador solo emite claves verificadas en config.h de Vita3K.
 * Los ajustes sin clave verificada van como comentario TODO-VERIFY,
 * nunca como clave inventada.
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
    fun surfaceSyncEnabledInvertsDisableFlag() {
        val yaml = Vita3kConfigGenerator.generate(
            LauncherSettings(surfaceSyncEnabled = true), "/data/vita3k",
        )
        assertTrue(yaml.contains("disable-surface-sync: false"))
    }

    @Test
    fun unverifiedSettingsAreCommentsNotKeys() {
        val yaml = Vita3kConfigGenerator.generate(presetSettings(GpuProfileId.G99_RECOMMENDED), "/data/vita3k")
        // No debe existir ninguna línea "clave: valor" para estos ajustes.
        assertFalse(yaml.lines().any { it.startsWith("frame-limiter:") })
        assertFalse(yaml.lines().any { it.startsWith("frame-skipping:") })
        assertFalse(yaml.lines().any { it.startsWith("accurate-thread-scheduling:") })
        assertFalse(yaml.lines().any { it.startsWith("audio-buffer-size:") })
        // Pero sí deben aparecer como TODO-VERIFY documentados.
        assertTrue(yaml.contains("TODO-VERIFY"))
        assertTrue(yaml.contains("frame-limiter"))
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
}
