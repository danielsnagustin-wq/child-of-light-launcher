package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.resetDefaultsWithCurrent
import com.danielsnagustin.childoflight.launcher.model.GameLanguage
import com.danielsnagustin.childoflight.launcher.model.GpuProfileId
import com.danielsnagustin.childoflight.launcher.model.presetSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ronda 0.2.70 (11-P2-4): "Restablecer ajustes" conserva el idioma,
 * la sesión PSN (simulada) y "Permitir app externa". Solo lógica pura
 * (JVM): resetToDefaults exige DataStore, así que se prueba la
 * fusión pura que usa.
 */
class ResetToDefaultsTest {

    @Test
    fun resetKeepsLanguagePsnAndExternalApp() {
        // Usuario con inglés, PSN apagada y app externa permitida:
        // restablecer todo NO lo devuelve a español / PSN ON / OFF.
        val current = presetSettings(GpuProfileId.G99_RECOMMENDED).copy(
            gameLanguage = GameLanguage.ENGLISH_US,
            psnSignedIn = false,
            allowExternalVita3k = true,
            pkgDeliveredToVita3k = true,
        )
        val reset = resetDefaultsWithCurrent(presetSettings(GpuProfileId.G99_RECOMMENDED), current)
        assertEquals(GameLanguage.ENGLISH_US, reset.gameLanguage)
        assertFalse(reset.psnSignedIn)
        assertTrue(reset.allowExternalVita3k)
        assertTrue(reset.pkgDeliveredToVita3k)
        // Los valores del preset sí se aplican: 1x en G99.
        assertEquals(1f, reset.resolutionMultiplier, 0.001f)
    }

    @Test
    fun resetWithoutCurrentFallsBackToDefaults() {
        val reset = resetDefaultsWithCurrent(presetSettings(GpuProfileId.G99_RECOMMENDED), null)
        assertEquals(GameLanguage.SPANISH, reset.gameLanguage)
        assertTrue(reset.psnSignedIn)
        assertFalse(reset.allowExternalVita3k)
        assertFalse(reset.pkgDeliveredToVita3k)
        assertEquals("", reset.rawgApiKey)
    }

    @Test
    fun resetKeepsRawgApiKey() {
        // Ronda 0.2.69 (RAWG): restablecer no borra la clave RAWG del usuario.
        val current = presetSettings(GpuProfileId.G99_RECOMMENDED).copy(
            rawgApiKey = "clave-de-prueba-123",
        )
        val reset = resetDefaultsWithCurrent(presetSettings(GpuProfileId.G99_RECOMMENDED), current)
        assertEquals("clave-de-prueba-123", reset.rawgApiKey)
    }
}
