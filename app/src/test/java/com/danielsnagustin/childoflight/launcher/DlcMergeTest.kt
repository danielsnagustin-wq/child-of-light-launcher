package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.GameLibraryRepository
import com.danielsnagustin.childoflight.launcher.model.DlcInfo
import com.danielsnagustin.childoflight.launcher.model.GameStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fusión de DLCs (reporte 2026-11-08 21:46): un solo DLC instalado
 * (carpeta en addcont/) cuyo .pkg sigue guardado se contaba como 2.
 * mergeDlcLists lo cuenta una vez: la carpeta extraída manda y el
 * .pkg es solo su copia de respaldo. Puro JVM.
 */
class DlcMergeTest {

    @Test
    fun sameDlcExtractedAndStoredCountsOnce() {
        val extracted = listOf(DlcInfo("EP0001-PCSB00598_00-DLC000001", "PCSB00598", 123L))
        val stored = listOf(DlcInfo("EP0001-PCSB00598_00-DLC000001", "PCSB00598", 456L, isPkg = true))
        val merged = GameLibraryRepository.mergeDlcLists(extracted, stored)
        assertEquals(1, merged.size)
        assertEquals("EP0001-PCSB00598_00-DLC000001", merged[0].dlcId)
        assertEquals(false, merged[0].isPkg)
    }

    @Test
    fun differentStoredDlcIsKept() {
        val extracted = listOf(DlcInfo("DLC-A", "PCSB00598", 1L))
        val stored = listOf(
            DlcInfo("DLC-A", "PCSB00598", 2L, isPkg = true),
            DlcInfo("DLC-B", "PCSB00598", 3L, isPkg = true),
        )
        val merged = GameLibraryRepository.mergeDlcLists(extracted, stored)
        assertEquals(2, merged.size)
        assertEquals(listOf("DLC-A", "DLC-B"), merged.map { it.dlcId })
    }

    @Test
    fun emptyListsMergeToEmpty() {
        assertEquals(0, GameLibraryRepository.mergeDlcLists(emptyList(), emptyList()).size)
    }

    @Test
    fun storedPkgNamedByHashMergesWithExtractedFolderByContentKey() {
        // P1-2: el .pkg guardado se llama por hash (o por el nombre
        // truncado) y la carpeta en addcont/ por el nombre corto del
        // contenido. La fusión compara la CLAVE DE CONTENIDO (del
        // header del .pkg), no el nombre del archivo: mismo DLC, una vez.
        val extracted = listOf(DlcInfo("CHILDOFLIGHTDLC1", "PCSB00598", 123L))
        val stored = listOf(
            DlcInfo("EAMfBniHkRQw123oldLwRkHA", "PCSB00598", 456L, isPkg = true, contentKey = "CHILDOFLIGHTDLC1"),
        )
        val merged = GameLibraryRepository.mergeDlcLists(extracted, stored)
        assertEquals(1, merged.size)
        assertEquals("CHILDOFLIGHTDLC1", merged[0].dlcId)
        assertEquals(false, merged[0].isPkg)
    }

    @Test
    fun extractedFolderWithFullContentIdMergesWithStoredShortName() {
        // La carpeta en addcont/ puede llevar el Content ID completo
        // (si el DLC vino de un .zip): la clave quita el prefijo "_00-".
        val extracted = listOf(DlcInfo("EP0001-PCSB00598_00-CHILDOFLIGHTDLC1", "PCSB00598", 123L))
        val stored = listOf(
            DlcInfo("CHILDOFLIGHTDLC1", "PCSB00598", 456L, isPkg = true, contentKey = "CHILDOFLIGHTDLC1"),
        )
        val merged = GameLibraryRepository.mergeDlcLists(extracted, stored)
        assertEquals(1, merged.size)
        assertEquals("EP0001-PCSB00598_00-CHILDOFLIGHTDLC1", merged[0].dlcId)
    }

    @Test
    fun mergeIgnoresCaseDifferences() {
        val extracted = listOf(DlcInfo("ChildOfLightDLC1", "PCSB00598", 123L))
        val stored = listOf(
            DlcInfo("CHILDOFLIGHTDLC1", "PCSB00598", 456L, isPkg = true, contentKey = "CHILDOFLIGHTDLC1"),
        )
        assertEquals(1, GameLibraryRepository.mergeDlcLists(extracted, stored).size)
    }

    @Test
    fun differentContentKeysStaySeparate() {
        val extracted = listOf(DlcInfo("CHILDOFLIGHTDLC1", "PCSB00598", 1L))
        val stored = listOf(
            DlcInfo("CHILDOFLIGHTDLC2", "PCSB00598", 2L, isPkg = true, contentKey = "CHILDOFLIGHTDLC2"),
        )
        assertEquals(2, GameLibraryRepository.mergeDlcLists(extracted, stored).size)
    }

    @Test
    fun mergeKeepsRegionUnknownFlag() {
        // 7-P2-10: un .pkg con header ilegible llega a la fusión
        // marcado de región desconocida y la marca sobrevive.
        val stored = listOf(
            DlcInfo("DLC-HEADER-ROTO", "PCSB00598", 3L, isPkg = true, contentKey = "DLC-HEADER-ROTO", regionUnknown = true),
        )
        val merged = GameLibraryRepository.mergeDlcLists(emptyList(), stored)
        assertEquals(1, merged.size)
        assertTrue(merged[0].regionUnknown)
        assertTrue(merged[0].isPkg)
    }

    @Test
    fun installedAndStoredDlcCountsSplit() {
        // 7-P2-7: Home distingue instalados de .pkg guardados con el
        // mismo criterio que FileStates (isPkg).
        val status = GameStatus(
            dlcs = listOf(
                DlcInfo("CHILDOFLIGHTDLC1", "PCSB00598", 1L),
                DlcInfo("DLC-GUARDADO", "PCSB00598", 2L, isPkg = true, contentKey = "DLC-GUARDADO"),
            ),
        )
        assertEquals(2, status.dlcCount)
        assertEquals(1, status.installedDlcCount)
        assertEquals(1, status.storedDlcPkgCount)
    }
}
