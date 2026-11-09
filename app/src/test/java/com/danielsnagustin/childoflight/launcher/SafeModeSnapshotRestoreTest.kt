package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.decodeSettingsBackup
import com.danielsnagustin.childoflight.launcher.data.encodeSettingsBackup
import com.danielsnagustin.childoflight.launcher.model.AudioBackend
import com.danielsnagustin.childoflight.launcher.model.LauncherSettings
import com.danielsnagustin.childoflight.launcher.model.UiPreferences
import com.danielsnagustin.childoflight.launcher.model.applySafeMode
import com.danielsnagustin.childoflight.launcher.ui.restoreAudioFixSnapshot
import com.danielsnagustin.childoflight.launcher.ui.restoreSafeModeSnapshot
import com.danielsnagustin.childoflight.launcher.ui.settings.audioFixTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R2-P1-08 + R2-P1-09: al apagar el Modo seguro (o deshacer "Corregir
 * audio") la restauración es POR CAMPO, no del LauncherSettings entero.
 * El snapshot pasa por encode/decodeSettingsBackup, que NO guarda 5
 * campos (turnipInstalled, turnipVersion, customDriverName,
 * pkgDeliveredToVita3k, pkgInstalledInVita3k); readSettingsSnapshot
 * decodifica además contra LauncherSettings() por defecto. Una
 * restauración entera los borraría, y también borraría los cambios
 * hechos mientras el modo seguro estaba encendido. Los tests reproducen
 * el recorrido real: snapshot vía encode/decode contra defaults.
 */
class SafeModeSnapshotRestoreTest {

    /** Snapshot tal como lo devuelve readSettingsSnapshot: decodificado contra defaults. */
    private fun snapshotViaFile(settings: LauncherSettings): LauncherSettings =
        decodeSettingsBackup(
            encodeSettingsBackup(settings, UiPreferences()),
            LauncherSettings(),
            UiPreferences(),
        ).getOrThrow().first

    @Test
    fun snapshotLosesTheFiveFields() {
        // Documenta el mecanismo que hace necesaria la fusión: el JSON
        // del snapshot no lleva los 5 campos.
        val before = LauncherSettings(
            turnipInstalled = true,
            turnipVersion = "turnip-25.1.zip",
            customDriverName = "turnip-25.1",
            pkgDeliveredToVita3k = true,
            pkgInstalledInVita3k = true,
        )
        val snapshot = snapshotViaFile(before)
        assertFalse(snapshot.turnipInstalled)
        assertEquals("", snapshot.turnipVersion)
        assertEquals("", snapshot.customDriverName)
        assertFalse(snapshot.pkgDeliveredToVita3k)
        assertFalse(snapshot.pkgInstalledInVita3k)
    }

    @Test
    fun safeModeRestoreKeepsTurnipDeliveryAndInterimChanges() {
        val before = LauncherSettings(
            resolutionMultiplier = 2f,
            vSync = true,
            highAccuracy = true,
            fpsHack = false,
            showFpsOverlay = false,
            turnipInstalled = true,
            turnipVersion = "turnip-25.1.zip",
            customDriverName = "turnip-25.1",
            pkgDeliveredToVita3k = true,
            pkgInstalledInVita3k = true,
        )
        val snapshot = snapshotViaFile(before)
        // Modo seguro encendido + cambios del usuario mientras tanto:
        // resolución a 3x (campo que el modo seguro SÍ toca) y fpsHack +
        // overlay (campos que NO toca).
        val current = applySafeMode(before).copy(
            resolutionMultiplier = 3f,
            fpsHack = true,
            showFpsOverlay = true,
        )
        val restored = restoreSafeModeSnapshot(snapshot, current)

        assertFalse(restored.safeMode)
        // Los 5 campos que el snapshot no guarda sobreviven (vienen de current).
        assertTrue(restored.turnipInstalled)
        assertEquals("turnip-25.1.zip", restored.turnipVersion)
        assertEquals("turnip-25.1", restored.customDriverName)
        assertTrue(restored.pkgDeliveredToVita3k)
        assertTrue(restored.pkgInstalledInVita3k)
        // Los cambios en campos que el modo seguro NO toca se conservan.
        assertTrue(restored.fpsHack)
        assertTrue(restored.showFpsOverlay)
        // P2-15: un cambio hecho DURANTE el modo seguro en un campo que
        // el modo seguro sí toca también se conserva (antes se revertía
        // al snapshot y el mensaje "se conservan" mentía): la resolución
        // a 3x sobrevive porque 3x != el forzado 1x.
        assertEquals(3f, restored.resolutionMultiplier, 0.001f)
        // Los campos tocados por el modo seguro que el usuario NO tocó
        // vuelven a su valor anterior (siguen siendo el forzado).
        assertTrue(restored.vSync)
        assertTrue(restored.highAccuracy)
    }

    @Test
    fun safeModeRestoreRestoresFpsHackWhenUntouched() {
        // P2-14: el modo seguro apaga fps-hack; al salir, si el usuario
        // no lo tocó durante el modo seguro, vuelve el valor anterior.
        val before = LauncherSettings(fpsHack = true, vSync = true)
        val snapshot = snapshotViaFile(before)
        val current = applySafeMode(before)
        assertFalse(current.fpsHack)
        val restored = restoreSafeModeSnapshot(snapshot, current)
        assertTrue(restored.fpsHack)
        assertTrue(restored.vSync)
    }

    @Test
    fun audioFixUndoKeepsTurnipAndInterimChanges() {
        val before = LauncherSettings(
            audioBackend = AudioBackend.CUBEB,
            ngsEnable = false,
            shaderCache = false,
            resolutionMultiplier = 1f,
            fpsHack = false,
            turnipInstalled = true,
            turnipVersion = "turnip-25.1.zip",
            customDriverName = "turnip-25.1",
            pkgDeliveredToVita3k = true,
        )
        val snapshot = snapshotViaFile(before)
        // "Corregir audio" aplicado + cambios del usuario mientras tanto.
        val current = audioFixTarget(before).copy(
            resolutionMultiplier = 3f,
            fpsHack = true,
        )
        val restored = restoreAudioFixSnapshot(snapshot, current)

        // Solo vuelven los 3 campos de audio.
        assertEquals(AudioBackend.CUBEB, restored.audioBackend)
        assertFalse(restored.ngsEnable)
        assertFalse(restored.shaderCache)
        // Los 5 campos y los cambios intermedios sobreviven.
        assertTrue(restored.turnipInstalled)
        assertEquals("turnip-25.1.zip", restored.turnipVersion)
        assertEquals("turnip-25.1", restored.customDriverName)
        assertTrue(restored.pkgDeliveredToVita3k)
        assertEquals(3f, restored.resolutionMultiplier, 0.001f)
        assertTrue(restored.fpsHack)
    }
}
