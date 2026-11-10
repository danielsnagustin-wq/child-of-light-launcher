package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.backupJsonHasSafeModeEnabled
import com.danielsnagustin.childoflight.launcher.data.encodeSettingsBackup
import com.danielsnagustin.childoflight.launcher.model.GpuProfileId
import com.danielsnagustin.childoflight.launcher.model.LauncherSettings
import com.danielsnagustin.childoflight.launcher.model.PerformanceOverlayDetail
import com.danielsnagustin.childoflight.launcher.model.PerformanceOverlayPosition
import com.danielsnagustin.childoflight.launcher.model.UiPreferences
import com.danielsnagustin.childoflight.launcher.model.preserveManualCacheChoices
import com.danielsnagustin.childoflight.launcher.model.presetSettings
import com.danielsnagustin.childoflight.launcher.ui.preservedPresetFieldsDiffer
import com.danielsnagustin.childoflight.launcher.ui.sanitizeVisiblePath
import com.danielsnagustin.childoflight.launcher.ui.settings.presetCompatibilityNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ronda 5 (worker W2): preservación al aplicar presets, privacidad
 * de la vista previa de config.yml, notas por preset y modo seguro
 * en las copias de seguridad. Solo lógica pura (JVM).
 */
class R5PresetPreservationTest {

    // --- R5-A2-1: detalle y posición del HUD se conservan ---

    @Test
    fun preserveManualCacheChoicesKeepsOverlayDetailAndPosition() {
        val manual = presetSettings(GpuProfileId.G99_RECOMMENDED).copy(
            performanceOverlayDetail = PerformanceOverlayDetail.MAXIMUM,
            performanceOverlayPosition = PerformanceOverlayPosition.BOTTOM_RIGHT,
        )
        val applied = preserveManualCacheChoices(presetSettings(GpuProfileId.G99_QUALITY), manual)
        assertEquals(PerformanceOverlayDetail.MAXIMUM, applied.performanceOverlayDetail)
        assertEquals(PerformanceOverlayPosition.BOTTOM_RIGHT, applied.performanceOverlayPosition)
        // Los valores del preset sí se aplican: 2x con FSR.
        assertEquals(2f, applied.resolutionMultiplier, 0.001f)
        assertEquals(GpuProfileId.G99_QUALITY, applied.activeProfile)
    }

    @Test
    fun preserveManualCacheChoicesLowRamDoesNotKeepOverlayDetailAndPosition() {
        // Primer arranque lowRam: no se conserva nada (la variante
        // conservadora es el valor del preset en ese camino).
        val applied = preserveManualCacheChoices(
            presetSettings(GpuProfileId.G99_RECOMMENDED),
            LauncherSettings(
                performanceOverlayDetail = PerformanceOverlayDetail.MAXIMUM,
                performanceOverlayPosition = PerformanceOverlayPosition.BOTTOM_RIGHT,
            ),
            lowRam = true,
        )
        assertEquals(PerformanceOverlayDetail.MINIMUM, applied.performanceOverlayDetail)
        assertEquals(PerformanceOverlayPosition.TOP_LEFT, applied.performanceOverlayPosition)
    }

    // --- R5-A2-5: qué campos preservados difieren del preset ---

    @Test
    fun preservedFieldsDifferWhenOnlyOverlayDetailDiffers() {
        val current = presetSettings(GpuProfileId.G99_RECOMMENDED).copy(
            performanceOverlayDetail = PerformanceOverlayDetail.MEDIUM,
        )
        assertTrue(preservedPresetFieldsDiffer(current, presetSettings(GpuProfileId.G99_RECOMMENDED)))
    }

    @Test
    fun preservedFieldsDifferWhenOnlyOverlayPositionDiffers() {
        val current = presetSettings(GpuProfileId.G99_RECOMMENDED).copy(
            performanceOverlayPosition = PerformanceOverlayPosition.TOP_RIGHT,
        )
        assertTrue(preservedPresetFieldsDiffer(current, presetSettings(GpuProfileId.G99_RECOMMENDED)))
    }

    @Test
    fun preservedFieldsDoNotDifferWhenNothingPreservedDiffers() {
        val current = presetSettings(GpuProfileId.G99_RECOMMENDED)
        assertFalse(preservedPresetFieldsDiffer(current, presetSettings(GpuProfileId.G99_RECOMMENDED)))
    }

    @Test
    fun preservedFieldsDifferIgnoresNonPreservedFields() {
        // La resolución no es un campo preservado: diferir en ella
        // no es "un cambio manual conservado".
        val current = presetSettings(GpuProfileId.G99_RECOMMENDED).copy(resolutionMultiplier = 2f)
        assertFalse(preservedPresetFieldsDiffer(current, presetSettings(GpuProfileId.G99_RECOMMENDED)))
    }

    // --- R5-A4-2 (P0): la ruta visible nunca lleva el nombre del paquete ---

    @Test
    fun sanitizeVisiblePathReplacesPackageName() {
        val raw = "pref-path: \"/data/user/0/com.danielsnagustin.childoflight.launcher/files/vita3k/vita\""
        assertEquals(
            "pref-path: \"/data/user/0/child-of-light-launcher/files/vita3k/vita\"",
            sanitizeVisiblePath(raw),
        )
    }

    @Test
    fun sanitizeVisiblePathLeavesCleanTextUntouched() {
        assertEquals("sin rutas", sanitizeVisiblePath("sin rutas"))
    }

    // --- R5-A2-8: el modo seguro en las copias de seguridad ---

    @Test
    fun backupJsonHasSafeModeEnabledDetectsOldBackups() {
        val json = encodeSettingsBackup(
            LauncherSettings(safeMode = true),
            UiPreferences(),
        )
        assertTrue(backupJsonHasSafeModeEnabled(json))
    }

    @Test
    fun backupJsonHasSafeModeEnabledIsFalseForNewExports() {
        // La exportación fuerza safeMode=false antes de codificar.
        val json = encodeSettingsBackup(
            LauncherSettings(safeMode = true).copy(safeMode = false),
            UiPreferences(),
        )
        assertTrue(json.contains("\"safeMode\":false"))
        assertFalse(backupJsonHasSafeModeEnabled(json))
    }

    @Test
    fun backupJsonHasSafeModeEnabledHandlesSpacesAndMissingKey() {
        assertTrue(backupJsonHasSafeModeEnabled("{\"format\":\"col-settings-backup\",\"safeMode\": true}"))
        assertFalse(backupJsonHasSafeModeEnabled("{\"format\":\"col-settings-backup\",\"safeMode\":false}"))
        assertFalse(backupJsonHasSafeModeEnabled("{\"format\":\"col-settings-backup\"}"))
        assertFalse(backupJsonHasSafeModeEnabled("{no es json"))
    }

    // --- R5-A2-6: las notas matizan la preservación ---

    @Test
    fun maliPerformanceNoteMentionsPreservedManualChanges() {
        assertTrue(
            presetCompatibilityNote(GpuProfileId.MALI_PERFORMANCE)
                .contains("salvo tus cambios manuales conservados"),
        )
    }

    @Test
    fun g99QualityNoteMentionsPreservedManualChanges() {
        assertTrue(
            presetCompatibilityNote(GpuProfileId.G99_QUALITY)
                .contains("salvo tus cambios manuales conservados"),
        )
    }
}
