package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.Vita3kConfigGenerator
import com.danielsnagustin.childoflight.launcher.data.decodeSettingsBackup
import com.danielsnagustin.childoflight.launcher.data.encodeSettingsBackup
import com.danielsnagustin.childoflight.launcher.model.GpuProfileId
import com.danielsnagustin.childoflight.launcher.model.LauncherSettings
import com.danielsnagustin.childoflight.launcher.model.UiPreferences
import com.danielsnagustin.childoflight.launcher.model.applySafeMode
import com.danielsnagustin.childoflight.launcher.model.effectiveSettings
import com.danielsnagustin.childoflight.launcher.model.preserveManualCacheChoices
import com.danielsnagustin.childoflight.launcher.model.presetSettings
import com.danielsnagustin.childoflight.launcher.ui.settings.SettingsCategory
import com.danielsnagustin.childoflight.launcher.ui.settings.resetCategorySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ronda 0.2.66 — Parches experimentales: interruptor Audio SDL (R1).
 *
 * REGLA DE ORO DEL VALIDADOR (AUD-P0-1): cada clave nueva que emita
 * generate() DEBE estar en validateGeneratedConfig, o writeConfig()
 * regenera con defaults en cada lanzamiento y ningún ajuste llega al
 * núcleo. Estos tests afirman la propiedad para la clave nueva
 * exp-audio-sdl con el interruptor ON y con OFF.
 *
 * Solo hay UN interruptor en esta ronda: las demás familias (caché de
 * texturas / memoria baja, Double Buffer, polling 60 FPS, Accurate
 * Thread Scheduling) NO tienen interruptor porque su parche no se pudo
 * adaptar limpio al núcleo actual (ver app/src/main/cpp/patches/
 * experimental/): un interruptor sin efecto real no se crea.
 */
class Ronda066ExperimentalSwitchesTest {

    @Test
    fun expAudioSdlOnValidates() {
        val yaml = Vita3kConfigGenerator.generate(
            LauncherSettings(expAudioSdl = true),
            "/data/vita3k",
        )
        assertTrue(yaml.contains("exp-audio-sdl: true"))
        assertTrue(
            "validateGeneratedConfig debe ser vacío con exp-audio-sdl ON: " +
                Vita3kConfigGenerator.validateGeneratedConfig(yaml),
            Vita3kConfigGenerator.validateGeneratedConfig(yaml).isEmpty(),
        )
    }

    @Test
    fun expAudioSdlOffValidates() {
        val yaml = Vita3kConfigGenerator.generate(
            LauncherSettings(expAudioSdl = false),
            "/data/vita3k",
        )
        assertTrue(yaml.contains("exp-audio-sdl: false"))
        assertTrue(
            "validateGeneratedConfig debe ser vacío con exp-audio-sdl OFF: " +
                Vita3kConfigGenerator.validateGeneratedConfig(yaml),
            Vita3kConfigGenerator.validateGeneratedConfig(yaml).isEmpty(),
        )
    }

    @Test
    fun presetsNeverEnableExpAudioSdl() {
        for (profile in GpuProfileId.entries) {
            assertFalse(
                "ningún preset enciende exp-audio-sdl ($profile)",
                presetSettings(profile).expAudioSdl,
            )
        }
    }

    @Test
    fun safeModeForcesExpAudioSdlOff() {
        val forced = applySafeMode(LauncherSettings(expAudioSdl = true))
        assertFalse(forced.expAudioSdl)
        assertFalse(
            effectiveSettings(LauncherSettings(expAudioSdl = true, safeMode = true)).expAudioSdl,
        )
    }

    @Test
    fun resetPerformanceCategoryTurnsExpAudioSdlOff() {
        val reset = resetCategorySettings(
            LauncherSettings(expAudioSdl = true),
            SettingsCategory.PERFORMANCE,
        )
        assertFalse(reset.expAudioSdl)
    }

    @Test
    fun presetApplyPreservesManualExpAudioSdl() {
        // Como hashlessTextureCache: aplicar un preset conserva la
        // elección manual del interruptor experimental (ni lo enciende
        // a escondidas ni apaga una prueba A/B en curso).
        val kept = preserveManualCacheChoices(
            presetSettings(GpuProfileId.G99_RECOMMENDED),
            LauncherSettings(expAudioSdl = true),
        )
        assertTrue(kept.expAudioSdl)
    }

    @Test
    fun backupRoundTripKeepsExpAudioSdl() {
        val settings = LauncherSettings(expAudioSdl = true)
        val json = encodeSettingsBackup(settings, UiPreferences())
        val decoded = decodeSettingsBackup(json, LauncherSettings(), UiPreferences()).getOrThrow()
        assertTrue(decoded.first.expAudioSdl)
        assertEquals(settings, decoded.first)
    }
}
