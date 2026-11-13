package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.decodeSettingsBackup
import com.danielsnagustin.childoflight.launcher.data.encodeSettingsBackup
import com.danielsnagustin.childoflight.launcher.model.AudioBackend
import com.danielsnagustin.childoflight.launcher.model.LauncherSettings
import com.danielsnagustin.childoflight.launcher.model.MemoryMapping
import com.danielsnagustin.childoflight.launcher.model.UiPreferences
import com.danielsnagustin.childoflight.launcher.model.applySafeMode
import com.danielsnagustin.childoflight.launcher.ui.restoreAudioFixSnapshot
import com.danielsnagustin.childoflight.launcher.ui.restoreSafeModeSnapshot
import com.danielsnagustin.childoflight.launcher.ui.settings.audioFixTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.danielsnagustin.childoflight.launcher.model.GpuDriver
import com.danielsnagustin.childoflight.launcher.model.ScreenFilter
import com.danielsnagustin.childoflight.launcher.ui.settingsForExport

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
    fun safeModeForcesNewExperimentalFields() {
        // R6-P1-09: el modo seguro también neutraliza la caché sin
        // hash, el Page Table y el retraso de carga.
        val before = LauncherSettings(
            hashlessTextureCache = true,
            memoryMapping = MemoryMapping.PAGE_TABLE,
            fileLoadingDelay = 30,
        )
        val forced = applySafeMode(before)
        assertFalse(forced.hashlessTextureCache)
        assertEquals(MemoryMapping.DOUBLE_BUFFER, forced.memoryMapping)
        assertEquals(0, forced.fileLoadingDelay)
    }

    @Test
    fun safeModeRestoreRestoresNewFieldsWhenUntouched() {
        // R6-P1-09: al salir, si el usuario no los tocó durante el
        // modo seguro, vuelven los valores anteriores.
        val before = LauncherSettings(
            hashlessTextureCache = true,
            memoryMapping = MemoryMapping.PAGE_TABLE,
            fileLoadingDelay = 30,
        )
        val snapshot = snapshotViaFile(before)
        val current = applySafeMode(before)
        val restored = restoreSafeModeSnapshot(snapshot, current)
        assertTrue(restored.hashlessTextureCache)
        assertEquals(MemoryMapping.PAGE_TABLE, restored.memoryMapping)
        assertEquals(30, restored.fileLoadingDelay)
    }

    @Test
    fun safeModeRestoreKeepsInterimChangeToNewFields() {
        // R6-P1-09: un cambio hecho DURANTE el modo seguro en un
        // campo nuevo se conserva (misma regla keepIfTouched).
        val before = LauncherSettings(
            hashlessTextureCache = true,
            memoryMapping = MemoryMapping.PAGE_TABLE,
            fileLoadingDelay = 30,
        )
        val snapshot = snapshotViaFile(before)
        val current = applySafeMode(before).copy(
            hashlessTextureCache = true,
            memoryMapping = MemoryMapping.PAGE_TABLE,
            fileLoadingDelay = 15,
        )
        val restored = restoreSafeModeSnapshot(snapshot, current)
        assertTrue(restored.hashlessTextureCache)
        assertEquals(MemoryMapping.PAGE_TABLE, restored.memoryMapping)
        assertEquals(15, restored.fileLoadingDelay)
    }

    @Test
    fun audioFixUndoKeepsTurnipAndInterimChanges() {
        val before = LauncherSettings(
            audioBackend = AudioBackend.CUBEB,
            ngsEnable = false,
            shaderCache = false,
            expAudioSdl = true,
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

        // Solo vuelven los 4 campos de audio.
        assertEquals(AudioBackend.CUBEB, restored.audioBackend)
        assertFalse(restored.ngsEnable)
        assertFalse(restored.shaderCache)
        // Ronda 0.2.70 (5-P2-3): el experimento también vuelve a su valor anterior.
        assertTrue(restored.expAudioSdl)
        // Los 5 campos y los cambios intermedios sobreviven.
        assertTrue(restored.turnipInstalled)
        assertEquals("turnip-25.1.zip", restored.turnipVersion)
        assertEquals("turnip-25.1", restored.customDriverName)
        assertTrue(restored.pkgDeliveredToVita3k)
        assertEquals(3f, restored.resolutionMultiplier, 0.001f)
        assertTrue(restored.fpsHack)
    }

    @Test
    fun safeModeRestoreRestoresCpuOptWhenUntouched() {
        // Ronda 0.2.70 (P1-3): el modo seguro fuerza cpuOpt=true; al salir,
        // si el usuario no lo toco durante el modo seguro, vuelve el valor anterior.
        val before = LauncherSettings(cpuOpt = false)
        val snapshot = snapshotViaFile(before)
        val current = applySafeMode(before)
        assertTrue(current.cpuOpt)
        val restored = restoreSafeModeSnapshot(snapshot, current)
        assertFalse(restored.cpuOpt)
    }

    @Test
    fun restoreCoversEveryFieldApplySafeModeForces() {
        // Ronda 0.2.70 (P1-3, invariante): restoreSafeModeSnapshot debe
        // cubrir todos los campos que applySafeMode sobrescribe. La base
        // pone cada campo forzado en un valor distinto del forzado; si el
        // restore olvida uno, el resultado conserva el forzado y este
        // assertEquals completo lo caza.
        val before = LauncherSettings(
            resolutionMultiplier = 2f,
            screenFilter = ScreenFilter.FXAA,
            anisotropicFiltering = 4,
            vSync = true,
            asyncPipelineCompilation = false,
            highAccuracy = true,
            surfaceSyncEnabled = true,
            audioBackend = AudioBackend.CUBEB,
            ngsEnable = false,
            expAudioSdl = true,
            shaderCache = false,
            cpuOpt = false,
            fpsHack = true,
            hashlessTextureCache = true,
            memoryMapping = MemoryMapping.PAGE_TABLE,
            fileLoadingDelay = 30,
            gpuDriver = GpuDriver.TURNIP,
        )
        val snapshot = snapshotViaFile(before)
        val current = applySafeMode(before)
        val restored = restoreSafeModeSnapshot(snapshot, current)
        assertEquals(before.copy(safeMode = false), restored)
    }

    @Test
    fun exportWithSafeModeOnExportsPreSafeModeValues() {
        // Ronda 0.2.70 (P1-2, OBLIGATORIO): exportar con el modo seguro ON
        // exporta los valores previos al modo seguro, no los forzados.
        val before = LauncherSettings(
            resolutionMultiplier = 2f,
            audioBackend = AudioBackend.CUBEB,
            memoryMapping = MemoryMapping.PAGE_TABLE,
            fpsHack = true,
        )
        val snapshot = snapshotViaFile(before)
        val current = applySafeMode(before)
        val exported = settingsForExport(current, snapshot, null)
        val json = encodeSettingsBackup(exported, UiPreferences())
        val decoded = decodeSettingsBackup(json, LauncherSettings(), UiPreferences()).getOrThrow().first
        assertFalse(decoded.safeMode)
        assertEquals(2f, decoded.resolutionMultiplier, 0.001f)
        assertEquals(AudioBackend.CUBEB, decoded.audioBackend)
        assertEquals(MemoryMapping.PAGE_TABLE, decoded.memoryMapping)
        assertTrue(decoded.fpsHack)
    }

    @Test
    fun exportWithAudioFixPendingExportsPreFixAudioValues() {
        // Ronda 0.2.70 (P1-2, segunda instancia): exportar mientras
        // 'Corregir audio' esta aplicado (antes de 'Deshacer') exporta
        // los valores previos al arreglo, no los transitorios.
        val before = LauncherSettings(audioBackend = AudioBackend.CUBEB, ngsEnable = false, shaderCache = false)
        val snapshot = snapshotViaFile(before)
        val current = audioFixTarget(before)
        val exported = settingsForExport(current, null, snapshot)
        assertEquals(AudioBackend.CUBEB, exported.audioBackend)
        assertFalse(exported.ngsEnable)
        assertFalse(exported.shaderCache)
        assertFalse(exported.safeMode)
    }

    @Test
    fun exportWithAudioFixPendingExportsPreFixExpAudioSdl() {
        // Fusión 0.2.70 (segunda pasada, P1-2 × 5-P2-3): audioFixTarget
        // también apaga expAudioSdl (área 5); exportar con "Corregir
        // audio" pendiente debe exportar el valor previo (ON), no el
        // false forzado. Sin el 4º campo en settingsForExport, este
        // test fallaba (exportaba false).
        val before = LauncherSettings(expAudioSdl = true)
        val snapshot = snapshotViaFile(before)
        val current = audioFixTarget(before)
        assertFalse(current.expAudioSdl)
        val exported = settingsForExport(current, null, snapshot)
        assertTrue(exported.expAudioSdl)
        assertFalse(exported.safeMode)
    }

    @Test
    fun exportWithSafeModeAndAudioFixKeepsPreSafeModeValues() {
        // Ronda 0.2.70 (P1-2, caso combinado): 'Corregir audio' aplicado
        // DURANTE el modo seguro: su snapshot trae los valores forzados;
        // la base (snapshot del modo seguro) ya guarda los reales y no
        // se reintroducen los forzados.
        val before = LauncherSettings(audioBackend = AudioBackend.CUBEB, ngsEnable = false, shaderCache = false, resolutionMultiplier = 2f)
        val safeSnapshot = snapshotViaFile(before)
        val forced = applySafeMode(before)
        val audioSnapshot = snapshotViaFile(forced)
        val exported = settingsForExport(forced, safeSnapshot, audioSnapshot)
        assertEquals(AudioBackend.CUBEB, exported.audioBackend)
        assertFalse(exported.ngsEnable)
        assertFalse(exported.shaderCache)
        assertEquals(2f, exported.resolutionMultiplier, 0.001f)
        assertFalse(exported.safeMode)
    }

    @Test
    fun exportWithAudioFixBeforeSafeModeRestoresPreFixAudio() {
        // Ronda 0.2.70 (P1-2, caso combinado inverso): 'Corregir audio'
        // aplicado ANTES del modo seguro: el snapshot de audio trae los
        // valores previos al arreglo y sí se restauran sobre la base.
        val before = LauncherSettings(audioBackend = AudioBackend.CUBEB, ngsEnable = false, shaderCache = false, resolutionMultiplier = 2f)
        val audioSnapshot = snapshotViaFile(before)
        val fixedAudio = audioFixTarget(before)
        val safeSnapshot = snapshotViaFile(fixedAudio)
        val current = applySafeMode(fixedAudio)
        val exported = settingsForExport(current, safeSnapshot, audioSnapshot)
        assertEquals(AudioBackend.CUBEB, exported.audioBackend)
        assertFalse(exported.ngsEnable)
        assertFalse(exported.shaderCache)
        assertEquals(2f, exported.resolutionMultiplier, 0.001f)
        assertFalse(exported.safeMode)
    }

}
