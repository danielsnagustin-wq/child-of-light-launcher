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
import com.danielsnagustin.childoflight.launcher.ui.settings.turnipKeptNote
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
    fun gpuResetKeepsTurnipWhenInstalled() {
        // R5-A2-10: usuario Adreno con Turnip instalado y activo que
        // restablece la GPU con el perfil G99 (preset SYSTEM): el
        // driver NO se suelta sin aviso. Antes el preset lo pisaba a
        // SYSTEM y no había forma de recuperarlo desde la UI sin
        // reinstalar el zip.
        val turnip = changed.copy(gpuDriver = GpuDriver.TURNIP, turnipInstalled = true)
        val reset = resetCategorySettings(turnip, SettingsCategory.GPU)
        assertEquals(GpuDriver.TURNIP, reset.gpuDriver)
        // El resto de GPU sí se restablece al preset G99.
        val preset = presetSettings(GpuProfileId.G99_RECOMMENDED)
        assertEquals(preset.backend, reset.backend)
        assertEquals(preset.resolutionMultiplier, reset.resolutionMultiplier, 0.001f)
    }

    @Test
    fun turnipKeptNoteMentionsConservedTurnip() {
        // R6-P2-09: reset GPU con Turnip activo e instalado y preset
        // SYSTEM (G99): el mensaje debe avisar de que Turnip se
        // conservó, no decir solo "valores del preset".
        val turnip = changed.copy(gpuDriver = GpuDriver.TURNIP, turnipInstalled = true)
        val reset = resetCategorySettings(turnip, SettingsCategory.GPU)
        val preset = presetSettings(GpuProfileId.G99_RECOMMENDED)
        assertEquals(GpuDriver.TURNIP, reset.gpuDriver)
        assertEquals(GpuDriver.SYSTEM, preset.gpuDriver)
        assertTrue(turnipKeptNote(reset, preset).contains("Turnip se conservó"))
    }

    @Test
    fun turnipKeptNoteEmptyWhenNothingKept() {
        // Sin Turnip activo, o con preset Turnip, no hay nada que
        // avisar: la nota es vacía.
        val preset = presetSettings(GpuProfileId.G99_RECOMMENDED)
        val systemReset = resetCategorySettings(changed, SettingsCategory.GPU)
        assertEquals("", turnipKeptNote(systemReset, preset))
        val turnipPreset = presetSettings(GpuProfileId.ADRENO_QUALITY_TURNIP)
        val turnipReset = resetCategorySettings(
            changed.copy(activeProfile = GpuProfileId.ADRENO_QUALITY_TURNIP, gpuDriver = GpuDriver.TURNIP, turnipInstalled = true),
            SettingsCategory.GPU,
        )
        assertEquals("", turnipKeptNote(turnipReset, turnipPreset))
    }

    @Test
    fun gpuResetDropsTurnipWhenNotInstalled() {
        // Sin Turnip instalado, TURNIP no se conserva: el preset
        // SYSTEM manda (la guarda guardTurnipDriver haría lo mismo).
        val turnip = changed.copy(gpuDriver = GpuDriver.TURNIP, turnipInstalled = false)
        val reset = resetCategorySettings(turnip, SettingsCategory.GPU)
        assertEquals(GpuDriver.SYSTEM, reset.gpuDriver)
    }

    @Test
    fun gpuResetAppliesPresetTurnipForTurnipProfile() {
        // Perfil ADRENO_QUALITY_TURNIP con driver SYSTEM: el reset
        // aplica el TURNIP del preset (no se conserva el SYSTEM
        // anterior: la conservación solo protege un TURNIP activo).
        val system = changed.copy(
            activeProfile = GpuProfileId.ADRENO_QUALITY_TURNIP,
            gpuDriver = GpuDriver.SYSTEM,
            turnipInstalled = true,
        )
        val reset = resetCategorySettings(system, SettingsCategory.GPU)
        assertEquals(GpuDriver.TURNIP, reset.gpuDriver)
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
    fun audioResetKeepsManualAudioChoices() {
        // Ronda 0.2.70 (11-P1-4): restablecer Audio YA NO devuelve
        // volumen/backend/NGS a 100/SDL/ON: presetSettings() nunca los
        // asigna y son elecciones manuales que aplicar un preset
        // conserva (mergePresetWithUserChoices).
        val reset = resetCategorySettings(changed, SettingsCategory.AUDIO)
        assertEquals(10, reset.audioVolume)
        assertEquals(com.danielsnagustin.childoflight.launcher.model.AudioBackend.CUBEB, reset.audioBackend)
        assertEquals(4f, reset.resolutionMultiplier, 0.001f)
        assertFalse(reset.cpuOpt)
        val ngsOff = resetCategorySettings(changed.copy(ngsEnable = false), SettingsCategory.AUDIO)
        assertFalse(ngsOff.ngsEnable)
    }

    @Test
    fun diagnosticsResetRestoresOnlyDiagnosticsFields() {
        val reset = resetCategorySettings(changed, SettingsCategory.DIAGNOSTICS)
        // Ronda 0.2.70 (11-P1-4): cerrar apps se conserva (el usuario
        // lo apagó a propósito en el fixture): ya no se reactiva.
        assertFalse(reset.closeBackgroundApps)
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
        val withCacheOff = changed.copy(textureCache = false, shaderCache = false, showFpsOverlay = true, skipShaderPrecompile = false)
        val reset = resetCategorySettings(withCacheOff, SettingsCategory.PERFORMANCE)
        val preset = presetSettings(GpuProfileId.G99_RECOMMENDED)
        assertTrue(preset.textureCache)
        assertEquals(preset.textureCache, reset.textureCache)
        assertEquals(preset.shaderCache, reset.shaderCache)
        assertEquals(preset.showFpsOverlay, reset.showFpsOverlay)
        // Ronda 0.2.68 (load-p1): el reset de Rendimiento también
        // devuelve la carga rápida al valor del preset (true en G99).
        assertTrue(preset.skipShaderPrecompile)
        assertEquals(preset.skipShaderPrecompile, reset.skipShaderPrecompile)
        // El resto se conserva (la categoría no toca GPU ni audio).
        assertEquals(4f, reset.resolutionMultiplier, 0.001f)
        assertEquals(10, reset.audioVolume)
    }

    @Test
    fun performanceResetKeepsManualAutoRevertAndPerformanceModeOff() {
        // Ronda 0.2.70 (11-P1-4, test OBLIGATORIO): restablecer
        // Rendimiento con autoRevertTo1xIfBelow28Fps=false y
        // performanceMode=false manuales NO los reactiva. Reactivar
        // el auto-revert en silencio revertiría a 1x tras el próximo
        // benchmark por debajo de 28 fps sin que el usuario lo pidiera.
        val manualOff = changed.copy(autoRevertTo1xIfBelow28Fps = false, performanceMode = false)
        val reset = resetCategorySettings(manualOff, SettingsCategory.PERFORMANCE)
        assertFalse(reset.autoRevertTo1xIfBelow28Fps)
        assertFalse(reset.performanceMode)
        // Las cachés sí vuelven al valor del preset.
        assertEquals(presetSettings(GpuProfileId.G99_RECOMMENDED).textureCache, reset.textureCache)
    }

    @Test
    fun gpuResetKeepsManualTurboMode() {
        // Ronda 0.2.70 (11-P1-4): el modo turbo es una elección manual
        // del driver (ningún preset lo enciende): restablecer la GPU
        // no lo apaga.
        val reset = resetCategorySettings(changed.copy(turboMode = true), SettingsCategory.GPU)
        assertTrue(reset.turboMode)
    }

    @Test
    fun controlsResetKeepsManualOverlayChoices() {
        // Ronda 0.2.70 (11-P1-4): restablecer Controles YA NO borra
        // la opacidad, el neón ni los perfiles con el overlay oculto
        // que el usuario configuró a mano.
        val manual = changed.copy(
            touchOverlayOpacity = 0.5f,
            touchOverlayNeonGlow = false,
            touchOverlayHiddenProfileIds = setOf(GpuProfileId.G99_RECOMMENDED.name),
        )
        val reset = resetCategorySettings(manual, SettingsCategory.CONTROLS)
        assertEquals(0.5f, reset.touchOverlayOpacity, 0.001f)
        assertFalse(reset.touchOverlayNeonGlow)
        assertEquals(setOf(GpuProfileId.G99_RECOMMENDED.name), reset.touchOverlayHiddenProfileIds)
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
