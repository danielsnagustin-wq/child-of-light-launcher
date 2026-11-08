package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.FirmwareStatus
import com.danielsnagustin.childoflight.launcher.model.DlcInfo
import com.danielsnagustin.childoflight.launcher.model.GameStatus
import com.danielsnagustin.childoflight.launcher.model.TitleId
import com.danielsnagustin.childoflight.launcher.ui.HomeStatusKey
import com.danielsnagustin.childoflight.launcher.ui.StatusLevel
import com.danielsnagustin.childoflight.launcher.ui.homeStatusChips
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de los indicadores de estado de la Home (Fase 2, Ola 1):
 * la función pura homeStatusChips deriva los 4 niveles solo del
 * estado real del disco (GameStatus) y del firmware. Nada ficticio:
 * sin juego no hay OK, sin DLCs no hay OK, un .pkg sin instalar es
 * PENDIENTE (no OK) y un PUP guardado sin instalar también.
 */
class HomeStatusChipsTest {

    private fun levelOf(chips: List<com.danielsnagustin.childoflight.launcher.ui.StatusChipData>, key: HomeStatusKey): StatusLevel =
        chips.first { it.key == key }.level

    private fun detailOf(chips: List<com.danielsnagustin.childoflight.launcher.ui.StatusChipData>, key: HomeStatusKey): String =
        chips.first { it.key == key }.detail

    @Test
    fun nadaInstalado_todoMissing() {
        val chips = homeStatusChips(GameStatus(), FirmwareStatus.Missing)
        assertEquals(4, chips.size)
        assertEquals(StatusLevel.MISSING, levelOf(chips, HomeStatusKey.GAME))
        assertEquals(StatusLevel.MISSING, levelOf(chips, HomeStatusKey.DLCS))
        assertEquals(StatusLevel.MISSING, levelOf(chips, HomeStatusKey.UPDATE))
        assertEquals(StatusLevel.MISSING, levelOf(chips, HomeStatusKey.FIRMWARE))
    }

    @Test
    fun juegoExtraido_okConTitleId() {
        val status = GameStatus(
            titleId = TitleId.EU,
            extractedTitleId = TitleId.EU,
            gameInstalled = true,
            gameExtracted = true,
            gameSizeBytes = 1_141_710_570L,
        )
        val chips = homeStatusChips(status, FirmwareStatus.Ready("vs0"))
        assertEquals(StatusLevel.OK, levelOf(chips, HomeStatusKey.GAME))
        assertEquals("PCSB00598", detailOf(chips, HomeStatusKey.GAME))
        assertEquals(StatusLevel.OK, levelOf(chips, HomeStatusKey.FIRMWARE))
        assertEquals(StatusLevel.MISSING, levelOf(chips, HomeStatusKey.DLCS))
        assertEquals(StatusLevel.MISSING, levelOf(chips, HomeStatusKey.UPDATE))
    }

    @Test
    fun soloPkg_juegoPendingNoOk() {
        val status = GameStatus(
            titleId = TitleId.EU,
            pkgTitleId = TitleId.EU,
            pkgContentId = "EP0001-PCSB00598_00-CHILDOFLIGHT000",
            pkgSizeBytes = 1_141_710_570L,
        )
        assertTrue(status.pkgInstalled)
        val chips = homeStatusChips(status, FirmwareStatus.Missing)
        assertEquals(StatusLevel.PENDING, levelOf(chips, HomeStatusKey.GAME))
        assertEquals("PCSB00598", detailOf(chips, HomeStatusKey.GAME))
    }

    @Test
    fun dlcsYUpdate_okConCantidad() {
        val status = GameStatus(
            titleId = TitleId.EU,
            extractedTitleId = TitleId.EU,
            gameInstalled = true,
            gameExtracted = true,
            dlcs = listOf(
                DlcInfo(dlcId = "PCSB00598-DLC01", titleId = "PCSB00598", sizeBytes = 12_000_000L),
                DlcInfo(dlcId = "PCSB00598-DLC02", titleId = "PCSB00598", sizeBytes = 8_000_000L, isPkg = true),
            ),
            updateInstalled = true,
        )
        val chips = homeStatusChips(status, FirmwareStatus.Missing)
        assertEquals(StatusLevel.OK, levelOf(chips, HomeStatusKey.DLCS))
        assertEquals("2", detailOf(chips, HomeStatusKey.DLCS))
        assertEquals(StatusLevel.OK, levelOf(chips, HomeStatusKey.UPDATE))
    }

    @Test
    fun firmwareGuardado_pendingNoOk() {
        val chips = homeStatusChips(GameStatus(), FirmwareStatus.Saved("PUP guardado"))
        assertEquals(StatusLevel.PENDING, levelOf(chips, HomeStatusKey.FIRMWARE))
        val ready = homeStatusChips(GameStatus(firmwareInstalled = true, firmwareName = "3.74"), FirmwareStatus.Ready("vs0"))
        assertEquals(StatusLevel.OK, levelOf(ready, HomeStatusKey.FIRMWARE))
        assertEquals("3.74", detailOf(ready, HomeStatusKey.FIRMWARE))
    }

    @Test
    fun firmwareImportadoVsInstalado_detalleDistingue() {
        // Rediseño 2026-11-08: el chip distingue importado de
        // instalado. Guardado (PUP sin instalar) → "importado";
        // instalado → la versión real. Sin firmware → sin detalle.
        val saved = homeStatusChips(GameStatus(), FirmwareStatus.Saved("PSVUPDAT.PUP guardado"))
        assertEquals(StatusLevel.PENDING, levelOf(saved, HomeStatusKey.FIRMWARE))
        assertEquals("importado", detailOf(saved, HomeStatusKey.FIRMWARE))
        val ready = homeStatusChips(GameStatus(firmwareInstalled = true, firmwareName = "3.74"), FirmwareStatus.Ready("vs0"))
        assertEquals("3.74", detailOf(ready, HomeStatusKey.FIRMWARE))
        val missing = homeStatusChips(GameStatus(), FirmwareStatus.Missing)
        assertEquals("", detailOf(missing, HomeStatusKey.FIRMWARE))
    }
}
