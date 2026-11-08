package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.decodeSettingsBackup
import com.danielsnagustin.childoflight.launcher.data.encodeSettingsBackup
import com.danielsnagustin.childoflight.launcher.model.AccentColor
import com.danielsnagustin.childoflight.launcher.model.CardSize
import com.danielsnagustin.childoflight.launcher.model.CardTransparency
import com.danielsnagustin.childoflight.launcher.model.GpuProfileId
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
    fun editedResolutionSnapsToOfferedValue() {
        // R2-S04: un backup editado a mano con 1.5x no llega al
        // config.yml: se ajusta al valor que ofrece la UI (1x).
        val json = "{\"format\":\"col-settings-backup\",\"version\":1," +
            "\"resolutionMultiplier\":1.5}"
        val decoded = decodeSettingsBackup(json, LauncherSettings(), UiPreferences()).getOrThrow()
        assertEquals(1f, decoded.first.resolutionMultiplier, 0.001f)
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
