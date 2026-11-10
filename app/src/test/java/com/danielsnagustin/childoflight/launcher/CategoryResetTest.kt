package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.model.AccentColor
import com.danielsnagustin.childoflight.launcher.model.CardSize
import com.danielsnagustin.childoflight.launcher.model.GpuDriver
import com.danielsnagustin.childoflight.launcher.model.GpuProfileId
import com.danielsnagustin.childoflight.launcher.model.GpuVendor
import com.danielsnagustin.childoflight.launcher.model.LauncherSettings
import com.danielsnagustin.childoflight.launcher.model.UiPreferences
import com.danielsnagustin.childoflight.launcher.model.presetSettings
import com.danielsnagustin.childoflight.launcher.ui.settings.SettingsCategory
import com.danielsnagustin.childoflight.launcher.ui.settings.categoryHasResettableSettings
import com.danielsnagustin.childoflight.launcher.ui.settings.guardTurnipDriver
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
        psnSignedIn = false,
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
        // General no se toca: la sesión PSN se conserva apagada.
        assertFalse(reset.psnSignedIn)
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
    fun performanceResetUsesCanonicalPresetTextureCache() {
        // R4-P0-9: "Restablecer esta categoría" en Rendimiento daba
        // textureCache=true (del preset) mientras el primer arranque
        // daba false (lowRam). El valor canónico del preset G99 es
        // true explícito y lowRam solo existe en el primer arranque:
        // el reseteo de categoría, el preset a mano y el auto-revert
        // coinciden ahora en true.
        val withCacheOff = changed.copy(textureCache = false, shaderCache = false, showFpsOverlay = true)
        val reset = resetCategorySettings(withCacheOff, SettingsCategory.PERFORMANCE)
        val preset = presetSettings(GpuProfileId.G99_RECOMMENDED)
        assertTrue(preset.textureCache)
        assertEquals(preset.textureCache, reset.textureCache)
        assertEquals(preset.shaderCache, reset.shaderCache)
        assertEquals(preset.showFpsOverlay, reset.showFpsOverlay)
        // El resto se conserva (la categoría no toca GPU ni audio).
        assertEquals(4f, reset.resolutionMultiplier, 0.001f)
        assertEquals(10, reset.audioVolume)
    }

    @Test
    fun generalResetKeepsUserSwitches() {
        // P2-18: General NO tiene campos de preset: restablecerlo
        // conservaba los interruptores del usuario. Antes los pisaba
        // con el default del preset: reactivaba la PSN simulada
        // aunque el usuario la hubiera apagado, y apagaba la app
        // externa aunque estuviera permitida.
        val reset = resetCategorySettings(changed, SettingsCategory.GENERAL)
        assertTrue(reset.allowExternalVita3k)
        assertFalse(reset.psnSignedIn)
        assertEquals(GpuProfileId.G99_RECOMMENDED, reset.activeProfile)
        assertEquals(4f, reset.resolutionMultiplier, 0.001f)
    }

    @Test
    fun personalizationResetRestoresUiDefaultsButKeepsHiddenPresets() {
        val withHidden = changedUi.copy(hiddenPresets = setOf(GpuProfileId.G99_QUALITY))
        val reset = resetCategoryUiPrefs(withHidden, SettingsCategory.PERSONALIZATION)
        assertEquals(UiPreferences(hiddenPresets = setOf(GpuProfileId.G99_QUALITY)), reset)
        // R4-P2-6: restablecer la personalización NO hace sonar la
        // intro otra vez: homeIntroSeen se conserva como los presets
        // ocultos (no es apariencia).
        val seen = withHidden.copy(homeIntroSeen = true)
        val resetSeen = resetCategoryUiPrefs(seen, SettingsCategory.PERSONALIZATION)
        assertEquals(true, resetSeen.homeIntroSeen)
        assertEquals(setOf(GpuProfileId.G99_QUALITY), resetSeen.hiddenPresets)
        assertEquals(AccentColor.ICE_BLUE, resetSeen.accent)
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
        // P2-18: General tampoco tiene campos de preset que restablecer.
        assertFalse(categoryHasResettableSettings(SettingsCategory.GENERAL))
        assertEquals(changed, resetCategorySettings(changed, SettingsCategory.STORAGE))
        assertTrue(categoryHasResettableSettings(SettingsCategory.GPU))
        assertTrue(categoryHasResettableSettings(SettingsCategory.PERSONALIZATION))
    }

    @Test
    fun guardTurnipDriverOnMaliFallsBackToSystem() {
        // P1-8: en Mali, TURNIP no tiene forma de revertirse desde la
        // UI (no hay chips de driver), así que el reseteo vuelve a Sistema.
        val turnip = changed.copy(gpuDriver = GpuDriver.TURNIP, turnipInstalled = true)
        val guarded = guardTurnipDriver(turnip, GpuVendor.MALI)
        assertEquals(GpuDriver.SYSTEM, guarded.gpuDriver)
        // Solo cambia el driver: el resto se conserva.
        assertEquals(changed.audioVolume, guarded.audioVolume)
        assertEquals(GpuProfileId.G99_RECOMMENDED, guarded.activeProfile)
    }

    @Test
    fun guardTurnipDriverOnAdrenoWithTurnipKeepsIt() {
        val turnip = changed.copy(gpuDriver = GpuDriver.TURNIP, turnipInstalled = true)
        assertEquals(GpuDriver.TURNIP, guardTurnipDriver(turnip, GpuVendor.ADRENO).gpuDriver)
    }

    @Test
    fun guardTurnipDriverOnAdrenoWithoutTurnipFallsBack() {
        val turnip = changed.copy(gpuDriver = GpuDriver.TURNIP, turnipInstalled = false)
        assertEquals(GpuDriver.SYSTEM, guardTurnipDriver(turnip, GpuVendor.ADRENO).gpuDriver)
    }

    @Test
    fun guardTurnipDriverWithUnknownVendorFallsBack() {
        val turnip = changed.copy(gpuDriver = GpuDriver.TURNIP, turnipInstalled = true)
        assertEquals(GpuDriver.SYSTEM, guardTurnipDriver(turnip, null).gpuDriver)
    }

    @Test
    fun guardTurnipDriverLeavesSystemAlone() {
        assertEquals(GpuDriver.SYSTEM, guardTurnipDriver(changed, GpuVendor.MALI).gpuDriver)
    }
}
