package com.danielsnagustin.childoflight.launcher

import androidx.compose.ui.unit.dp
import com.danielsnagustin.childoflight.launcher.data.DownloadsCleanup
import com.danielsnagustin.childoflight.launcher.data.DownloadsCopy
import com.danielsnagustin.childoflight.launcher.ui.ContentMaxWidth
import com.danielsnagustin.childoflight.launcher.ui.downloadsCopyConfirmText
import com.danielsnagustin.childoflight.launcher.ui.previewHeightForScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de los añadidos 2026-11-07 13:33 (sin carpeta en Descargas +
 * revisión de layouts). Todo puro, sin Android: formatos, textos y
 * tamaños de layout. La verificación en el teléfono y en CI sigue
 * pendiente: aquí solo se cubre la lógica que sí se puede probar en JVM.
 */
class DownloadsAndLayoutTest {

    // --- DownloadsCleanup.formatBytes ---

    @Test
    fun formatBytes_pkgDe114GB() {
        assertEquals("1.14 GB", DownloadsCleanup.formatBytes(1_141_710_570L))
    }

    @Test
    fun formatBytes_workBin512B() {
        assertEquals("512 bytes", DownloadsCleanup.formatBytes(512L))
    }

    @Test
    fun formatBytes_megasYKb() {
        assertEquals("1.5 MB", DownloadsCleanup.formatBytes(1_500_000L))
        assertEquals("2 KB", DownloadsCleanup.formatBytes(2_500L))
    }

    // --- DownloadsCleanup.summarize ---

    @Test
    fun summarize_listaArchivosYTotal() {
        val copies = listOf(
            DownloadsCopy("PCSB00598.pkg", 1_141_710_570L),
            DownloadsCopy("PCSB00598.work.bin", 512L),
        )
        assertEquals(1_141_711_082L, DownloadsCleanup.totalBytes(copies))
        val text = DownloadsCleanup.summarize(copies)
        assertTrue(text.contains("PCSB00598.pkg (1.14 GB)"))
        assertTrue(text.contains("PCSB00598.work.bin (512 bytes)"))
        assertTrue(text.contains("Total: 1.14 GB"))
        assertFalse(text.contains("Vita3K", ignoreCase = true))
    }

    // --- downloadsCopyConfirmText (diálogo antes de copiar a Descargas) ---

    @Test
    fun confirmText_explicaLaCopiaYSinNombreExterno() {
        val text = downloadsCopyConfirmText(
            listOf("PCSB00598.pkg", "PCSB00598.work.bin"),
            1_141_711_082L,
        )
        assertTrue(text.contains("Descargas/ChildOfLight/"))
        assertTrue(text.contains("almacenamiento interno"))
        assertTrue(text.contains("PCSB00598.pkg"))
        assertTrue(text.contains("1.14 GB"))
        assertTrue(text.contains("Ajustes → Almacenamiento"))
        assertFalse(text.contains("Vita3K", ignoreCase = true))
        assertFalse(text.contains("vita3k", ignoreCase = true))
    }

    @Test
    fun confirmText_listaVacia_noRompe() {
        val text = downloadsCopyConfirmText(emptyList(), 0L)
        assertTrue(text.contains("Descargas/ChildOfLight/"))
        assertTrue(text.contains("0 bytes"))
    }

    // --- Layout: tamaños de pantalla ---

    @Test
    fun contentMaxWidth_720dp() {
        assertEquals(720.dp, ContentMaxWidth)
    }

    @Test
    fun previewHeight_horizontalSeAchica() {
        // Teléfono en horizontal (~360-400 dp de alto): 200 dp.
        assertEquals(200.dp, previewHeightForScreen(360.dp))
        assertEquals(200.dp, previewHeightForScreen(400.dp))
        assertEquals(200.dp, previewHeightForScreen(499.dp))
    }

    @Test
    fun previewHeight_verticalSeMantiene() {
        // En vertical (y en el límite de 500 dp): 300 dp.
        assertEquals(300.dp, previewHeightForScreen(500.dp))
        assertEquals(300.dp, previewHeightForScreen(800.dp))
    }
}
