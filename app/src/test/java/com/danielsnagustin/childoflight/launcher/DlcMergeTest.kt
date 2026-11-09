package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.GameLibraryRepository
import com.danielsnagustin.childoflight.launcher.model.DlcInfo
import org.junit.Assert.assertEquals
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
}
