package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.SettingsRepository
import com.danielsnagustin.childoflight.launcher.data.decodeSettingsBackup
import com.danielsnagustin.childoflight.launcher.data.encodeSettingsBackup
import com.danielsnagustin.childoflight.launcher.model.AccentColor
import com.danielsnagustin.childoflight.launcher.model.CardSize
import com.danielsnagustin.childoflight.launcher.model.CardTransparency
import com.danielsnagustin.childoflight.launcher.model.GpuProfileId
import com.danielsnagustin.childoflight.launcher.model.HomeSection
import com.danielsnagustin.childoflight.launcher.model.IconSize
import com.danielsnagustin.childoflight.launcher.model.LauncherSettings
import com.danielsnagustin.childoflight.launcher.model.UiPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Copia de seguridad de ajustes (Ola 3, Etapa 9a). Funciones puras:
 * codificar y decodificar JSON sin librería externa.
 */
class SettingsBackupTest {

    private val settings = LauncherSettings(
        activeProfile = GpuProfileId.G99_QUALITY,
        resolutionMultiplier = 2f,
        audioVolume = 42,
        fpsHack = true,
        touchOverlayOpacity = 0.5f,
        // Ronda 0.2.70 (4-P2-2): el round-trip debe fijar también la
        // carga rápida y el audio SDL experimental; con el default
        // false en ambos lados, perderlos en encode/decode no se notaba.
        skipShaderPrecompile = true,
        expAudioSdl = true,
    )
    private val ui = UiPreferences(
        accent = AccentColor.TURQUOISE,
        cardSize = CardSize.LARGE,
        cardTransparency = CardTransparency.TRANSLUCENT,
        iconSize = IconSize.SMALL,
    )

    @Test
    fun roundTripKeepsSettingsAndUi() {
        val json = encodeSettingsBackup(settings, ui)
        val decoded = decodeSettingsBackup(json, LauncherSettings(), UiPreferences()).getOrThrow()
        assertEquals(settings, decoded.first)
        assertEquals(ui, decoded.second)
    }

    // La clave RAWG (ronda 0.2.69) debe sobrevivir a exportar e importar la copia de seguridad.
    @Test
    fun roundTripKeepsRawgApiKey() {
        val withKey = LauncherSettings(rawgApiKey = "clave-de-prueba-123")
        val json = encodeSettingsBackup(withKey, UiPreferences())
        val decoded = decodeSettingsBackup(json, LauncherSettings(), UiPreferences()).getOrThrow()
        assertEquals("clave-de-prueba-123", decoded.first.rawgApiKey)
    }

    @Test
    fun missingListKeysKeepCurrentValues() {
        // P2-36: una clave ausente en el backup conserva el valor
        // actual en vez de resetear el campo (antes, importar un
        // backup viejo sin estas claves borraba los perfiles ocultos,
        // el orden de secciones y los presets ocultos del usuario).
        val json = encodeSettingsBackup(LauncherSettings(), UiPreferences())
            .replace(Regex("\"touchOverlayHiddenProfileIds\":\"[^\"]*\",?"), "")
            .replace(Regex("\"sectionOrder\":\"[^\"]*\",?"), "")
            .replace(Regex("\"hiddenPresets\":\"[^\"]*\",?"), "")
        val currentSettings = LauncherSettings(touchOverlayHiddenProfileIds = setOf("perfil-x"))
        val currentUi = UiPreferences(
            sectionOrder = listOf(HomeSection.LOGROS_CARD, HomeSection.GAME_CARD),
            hiddenPresets = setOf(GpuProfileId.G99_QUALITY),
        )
        val decoded = decodeSettingsBackup(json, currentSettings, currentUi).getOrThrow()
        assertEquals(setOf("perfil-x"), decoded.first.touchOverlayHiddenProfileIds)
        assertEquals(listOf(HomeSection.LOGROS_CARD, HomeSection.GAME_CARD), decoded.second.sectionOrder)
        assertEquals(setOf(GpuProfileId.G99_QUALITY), decoded.second.hiddenPresets)
    }

    @Test
    fun invalidJsonFailsWithoutApplying() {
        val result = decodeSettingsBackup("{no es json", LauncherSettings(), UiPreferences())
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("No se aplicó nada") == true)
    }

    @Test
    fun wrongFormatFails() {
        val result = decodeSettingsBackup(
            "{\"format\":\"otra-cosa\",\"version\":1}",
            LauncherSettings(),
            UiPreferences(),
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun unsupportedVersionFails() {
        val result = decodeSettingsBackup(
            "{\"format\":\"col-settings-backup\",\"version\":99}",
            LauncherSettings(),
            UiPreferences(),
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun outOfRangeValuesAreClampedLikeTheRepository() {
        val json = "{\"format\":\"col-settings-backup\",\"version\":1," +
            "\"resolutionMultiplier\":99,\"anisotropicFiltering\":3," +
            "\"audioVolume\":500,\"fileLoadingDelay\":-5," +
            "\"touchOverlayOpacity\":5.0}"
        val decoded = decodeSettingsBackup(json, LauncherSettings(), UiPreferences()).getOrThrow()
        assertEquals(4f, decoded.first.resolutionMultiplier, 0.001f)
        assertEquals(1, decoded.first.anisotropicFiltering)
        assertEquals(100, decoded.first.audioVolume)
        assertEquals(0, decoded.first.fileLoadingDelay)
        assertEquals(1f, decoded.first.touchOverlayOpacity, 0.001f)
    }

    @Test
    fun editedFileLoadingDelayAboveThirtyIsClamped() {
        // R7-P2-03: un backup editado a mano con 99999 ms dejaría el
        // juego inutilizable (el núcleo duerme esos ms en cada
        // sceIoOpen): el tope es 30, como el slider de la pantalla
        // de CPU. El mismo clamp lo aplican save() y la lectura de
        // DataStore (SettingsRepository.clampFileLoadingDelay).
        val json = "{\"format\":\"col-settings-backup\",\"version\":1," +
            "\"fileLoadingDelay\":99999}"
        val decoded = decodeSettingsBackup(json, LauncherSettings(), UiPreferences()).getOrThrow()
        assertEquals(30, decoded.first.fileLoadingDelay)
    }

    @Test
    fun fileLoadingDelayClampIsSharedBySaveDecodeAndRead() {
        assertEquals(0, SettingsRepository.clampFileLoadingDelay(-5))
        assertEquals(0, SettingsRepository.clampFileLoadingDelay(0))
        assertEquals(15, SettingsRepository.clampFileLoadingDelay(15))
        assertEquals(30, SettingsRepository.clampFileLoadingDelay(30))
        assertEquals(30, SettingsRepository.clampFileLoadingDelay(99999))
    }

    @Test
    fun editedResolutionSnapsToOfferedValue() {
        // R2-S04: un backup editado a mano con 1.7x no llega al
        // config.yml: se ajusta al valor que ofrece la UI más
        // cercano (1.5x). 1.5x SÍ lo ofrece la UI ("1.5x (1440x816)")
        // y se queda en 1.5, así que el caso de ajuste es 1.7 -> 1.5.
        // Lo de 1.5 -> 1x solo era verdad cuando el snap era
        // {0.5, 1, 2, 3, 4}; ahora el snap es {0.5, 1, 1.25, 1.5,
        // 2, 3, 4} (SettingsRepository.clampResolution).
        val json = "{\"format\":\"col-settings-backup\",\"version\":1," +
            "\"resolutionMultiplier\":1.7}"
        val decoded = decodeSettingsBackup(json, LauncherSettings(), UiPreferences()).getOrThrow()
        assertEquals(1.5f, decoded.first.resolutionMultiplier, 0.001f)
        val json2 = "{\"format\":\"col-settings-backup\",\"version\":1," +
            "\"resolutionMultiplier\":2.6}"
        val decoded2 = decodeSettingsBackup(json2, LauncherSettings(), UiPreferences()).getOrThrow()
        assertEquals(3f, decoded2.first.resolutionMultiplier, 0.001f)
    }

    @Test
    fun unknownKeysAreIgnoredAndMissingFieldsKeepCurrent() {
        val json = "{\"format\":\"col-settings-backup\",\"version\":1," +
            "\"claveQueNoExiste\":123,\"audioVolume\":77}"
        val decoded = decodeSettingsBackup(json, settings, ui).getOrThrow()
        assertEquals(77, decoded.first.audioVolume)
        assertEquals(2f, decoded.first.resolutionMultiplier, 0.001f)
        assertEquals(CardSize.LARGE, decoded.second.cardSize)
    }

    @Test
    fun unknownEnumFallsBackToCurrent() {
        val json = "{\"format\":\"col-settings-backup\",\"version\":1,\"backend\":\"NO_EXISTE\"}"
        val decoded = decodeSettingsBackup(json, settings, UiPreferences()).getOrThrow()
        assertEquals(settings.backend, decoded.first.backend)
    }

    @Test
    fun stateFieldsAreNotInTheBackup() {
        val withState = settings.copy(
            pkgDeliveredToVita3k = true,
            pkgInstalledInVita3k = true,
            customDriverName = "turnip-test",
            turnipInstalled = true,
            turnipVersion = "26.0.0",
        )
        val json = encodeSettingsBackup(withState, ui)
        assertFalse(json.contains("pkgInstalledInVita3k"))
        assertFalse(json.contains("turnipInstalled"))
        val decoded = decodeSettingsBackup(json, LauncherSettings(), UiPreferences()).getOrThrow()
        assertFalse(decoded.first.pkgInstalledInVita3k)
        assertFalse(decoded.first.turnipInstalled)
    }
}
