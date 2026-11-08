package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.model.AccentColor
import com.danielsnagustin.childoflight.launcher.model.CardSize
import com.danielsnagustin.childoflight.launcher.model.GpuProfileId
import com.danielsnagustin.childoflight.launcher.model.LauncherSettings
import com.danielsnagustin.childoflight.launcher.model.UiPreferences
import com.danielsnagustin.childoflight.launcher.model.presetSettings
import com.danielsnagustin.childoflight.launcher.ui.settings.SettingsCategory
import com.danielsnagustin.childoflight.launcher.ui.settings.categoryHasResettableSettings
import com.danielsnagustin.childoflight.launcher.ui.settings.resetCategorySettings
import com.danielsnagustin.childoflight.launcher.ui.settings.resetCategoryUiPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Restauración individual por categoría (Ola 3, Etapa 9b). Funciones
 * puras: solo se restablecen los campos de la categoría, el resto se
 * conserva.
 */
class CategoryResetTest {

    private val changed = LauncherSettings(
        activeProfile = GpuProfileId.G99_RECOMMENDED,
        backend = com.danielsnagustin.childoflight.launcher.model.GpuBackend.OPENGL,
        resolutionMultiplier = 4f,
        audioVolume = 10,
        audioBackend = com.danielsnagustin.childoflight.launcher.model.AudioBackend.CUBEB,
        cpuOpt = false,
        fpsHack = true,
        fileLoadingDelay = 500,
        showFpsOverlay = true,
        safeMode = true,
        closeBackgroundApps = false,
        allowExternalVita3k = true,
        hideTouchWhenGamepadConnected = false,
    )
    private val changedUi = UiPreferences(
        accent = AccentColor.LAVENDER,
        cardSize = CardSize.LARGE,
    )

    @Test
    fun gpuResetRestoresOnlyGpuFields() {
        val reset = resetCategorySettings(changed, SettingsCategory.GPU)
        val preset = presetSettings(GpuProfileId.G99_RECOMMENDED)
        assertEquals(preset.backend, reset.backend)
        assertEquals(preset.resolutionMultiplier, reset.resolutionMultiplier, 0.001f)
        assertEquals(preset.memoryMapping, reset.memoryMapping)
        // Audio, CPU, controles y diagnóstico se conservan.
        assertEquals(10, reset.audioVolume)
        assertFalse(reset.cpuOpt)
        assertTrue(reset.fpsHack)
        assertTrue(reset.safeMode)
        assertTrue(reset.allowExternalVita3k)
    }

    @Test
    fun cpuResetRestoresOnlyCpuFields() {
        val reset = resetCategorySettings(changed, SettingsCategory.CPU)
        assertTrue(reset.cpuOpt)
        assertFalse(reset.fpsHack)
        assertEquals(0, reset.fileLoadingDelay)
        assertEquals(4f, reset.resolutionMultiplier, 0.001f)
        assertEquals(10, reset.audioVolume)
    }

    @Test
    fun audioResetRestoresOnlyAudioFields() {
        val reset = resetCategorySettings(changed, SettingsCategory.AUDIO)
        assertEquals(100, reset.audioVolume)
        assertEquals(com.danielsnagustin.childoflight.launcher.model.AudioBackend.SDL, reset.audioBackend)
        assertEquals(4f, reset.resolutionMultiplier, 0.001f)
        assertFalse(reset.cpuOpt)
    }

    @Test
    fun diagnosticsResetRestoresOnlyDiagnosticsFields() {
        val reset = resetCategorySettings(changed, SettingsCategory.DIAGNOSTICS)
        assertTrue(reset.closeBackgroundApps)
        assertFalse(reset.safeMode)
        assertTrue(reset.fpsHack)
        assertEquals(10, reset.audioVolume)
    }

    @Test
    fun generalResetRestoresOnlyMotorSwitch() {
        val reset = resetCategorySettings(changed, SettingsCategory.GENERAL)
        assertFalse(reset.allowExternalVita3k)
        assertEquals(GpuProfileId.G99_RECOMMENDED, reset.activeProfile)
        assertEquals(4f, reset.resolutionMultiplier, 0.001f)
    }

    @Test
    fun personalizationResetRestoresUiDefaultsButKeepsHiddenPresets() {
        val withHidden = changedUi.copy(hiddenPresets = setOf(GpuProfileId.G99_QUALITY))
        val reset = resetCategoryUiPrefs(withHidden, SettingsCategory.PERSONALIZATION)
        assertEquals(UiPreferences(hiddenPresets = setOf(GpuProfileId.G99_QUALITY)), reset)
        // Las demás categorías no tocan UiPreferences.
        assertEquals(withHidden, resetCategoryUiPrefs(withHidden, SettingsCategory.GPU))
        // Y Personalización no toca LauncherSettings.
        assertEquals(changed, resetCategorySettings(changed, SettingsCategory.PERSONALIZATION))
    }

    @Test
    fun storageHasNothingToReset() {
        // NOTA (2026-11-08): el firmware ya no es una categoría de
        // Ajustes (vive en Importar, con el juego y los DLCs), así que
        // ya no hay rama FIRMWARE que probar aquí.
        assertFalse(categoryHasResettableSettings(SettingsCategory.STORAGE))
        assertEquals(changed, resetCategorySettings(changed, SettingsCategory.STORAGE))
        assertTrue(categoryHasResettableSettings(SettingsCategory.GPU))
        assertTrue(categoryHasResettableSettings(SettingsCategory.PERSONALIZATION))
    }
}
