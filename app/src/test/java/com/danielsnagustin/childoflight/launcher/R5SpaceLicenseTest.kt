package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.FirmwareResolver
import com.danielsnagustin.childoflight.launcher.data.GameLibraryRepository
import com.danielsnagustin.childoflight.launcher.data.ParamSfo
import com.danielsnagustin.childoflight.launcher.model.DlcInfo
import com.danielsnagustin.childoflight.launcher.model.GameStatus
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tests de la ronda 5 (W4: espacio, licencia, addons).
 *
 * Cubre solo lógica pura (JVM, sin Android ni Context):
 * - R5-A4-1: cuánto espacio hace falta para importar un .pkg o un
 *   .zip, y el mensaje de "Falta espacio". El StatFs real
 *   (hasFreeSpace/freeBytes) no se puede probar en unit tests JVM
 *   (stub de Android): lo que se fija aquí es la matemática que lo
 *   alimenta y el texto que ve el usuario.
 * - R5-A4-3: el Content ID se lee del param.sfo (CONTENT_ID) y el
 *   campo nuevo de GameStatus nace en false.
 * - R5-A4-6: borrar el PUP guardado exige marcador CON versión y
 *   vs0 no vacío, no solo que exista el marcador.
 * - R5-A4-9: la fusión de DLCs deduplica por clave de contenido
 *   (la que usa el listado de ambos ux0 en scanStatus).
 */
class R5SpaceLicenseTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // --- R5-A4-1: espacio ---

    @Test
    fun `pkgImportNeededBytes suma el declarado mas el margen de extraccion`() {
        assertEquals(
            1_141_710_576L + GameLibraryRepository.PKG_EXTRACTION_MARGIN_BYTES,
            GameLibraryRepository.pkgImportNeededBytes(1_141_710_576L),
        )
    }

    @Test
    fun `pkgImportNeededBytes sin declarado usa solo el margen`() {
        assertEquals(
            GameLibraryRepository.PKG_EXTRACTION_MARGIN_BYTES,
            GameLibraryRepository.pkgImportNeededBytes(0L),
        )
        assertEquals(
            GameLibraryRepository.PKG_EXTRACTION_MARGIN_BYTES,
            GameLibraryRepository.pkgImportNeededBytes(-5L),
        )
    }

    @Test
    fun `zipImportNeededBytes es el doble del zip`() {
        assertEquals(2_000_000_000L, GameLibraryRepository.zipImportNeededBytes(1_000_000_000L))
        assertEquals(0L, GameLibraryRepository.zipImportNeededBytes(0L))
    }

    @Test
    fun `freeSpaceMessage dice cuanto falta y cuanto hay`() {
        assertEquals(
            "Falta espacio: hacen falta 2.28 GB, hay 1.61 GB libres",
            GameLibraryRepository.freeSpaceMessage(2_283_421_152L, 1_610_612_736L),
        )
    }

    // --- P0 espacio libre (0.2.55): la sonda y el mensaje no-medible ---

    @Test
    fun `statFsProbeDir con pkg inexistente devuelve el ux0 existente`() {
        // El caso real del bug: en instalación limpia ux0/pkg/ aún
        // no existe; la sonda es ux0/, que sí existe.
        val root = tmp.newFolder("vitaRoot")
        val ux0 = File(root, "ux0").apply { mkdirs() }
        assertEquals(ux0, GameLibraryRepository.statFsProbeDir(File(ux0, "pkg"), root))
    }

    @Test
    fun `statFsProbeDir con tres niveles inexistentes sube al ancestro existente`() {
        val root = tmp.newFolder("vitaRoot")
        val nested = File(File(File(root, "ux0"), "pkg"), "sub")
        assertEquals(root, GameLibraryRepository.statFsProbeDir(nested, root))
    }

    @Test
    fun `statFsProbeDir devuelve el mismo directorio si ya existe`() {
        val root = tmp.newFolder("vitaRoot")
        assertEquals(root, GameLibraryRepository.statFsProbeDir(root, tmp.root))
    }

    @Test
    fun `statFsProbeDir sin padre existente devuelve el fallback`() {
        val fallback = tmp.newFolder("fallback")
        assertEquals(
            fallback,
            GameLibraryRepository.statFsProbeDir(File("solo-nombre-inexistente"), fallback),
        )
    }

    @Test
    fun `freeSpaceMessage con espacio no medible no dice 0 bytes libres`() {
        assertEquals(
            "No se pudo medir el espacio libre: hacen falta 2.28 GB",
            GameLibraryRepository.freeSpaceMessage(2_283_421_152L, -1L),
        )
    }

    @Test
    fun `freeSpaceMessage con 0 bytes libres reales si dice 0 bytes libres`() {
        // 0 medido de verdad es disco lleno, distinto de no-medible.
        assertEquals(
            "Falta espacio: hacen falta 2.28 GB, hay 0 bytes libres",
            GameLibraryRepository.freeSpaceMessage(2_283_421_152L, 0L),
        )
    }

    @Test
    fun `freeSpaceMessage con declarado explica el pico del pkg`() {
        assertEquals(
            "Falta espacio: hacen falta 2.28 GB, hay 1.61 GB libres. " +
                "El pico incluye el .pkg guardado (1.14 GB) y su extracción (1.14 GB); " +
                "tras instalarlo, el .pkg se borra solo",
            GameLibraryRepository.freeSpaceMessage(2_283_421_152L, 1_610_612_736L, 1_141_710_576L),
        )
    }

    @Test
    fun `freeSpaceMessage sin declarado no explica el pico`() {
        assertEquals(
            "Falta espacio: hacen falta 2.28 GB, hay 1.61 GB libres",
            GameLibraryRepository.freeSpaceMessage(2_283_421_152L, 1_610_612_736L, 0L),
        )
    }

    // --- R5-A4-3: licencia en el núcleo ---

    @Test
    fun `param sfo trae el Content ID`() {
        val info = ParamSfo.parse(
            buildSfoBytes(
                listOf(
                    "TITLE_ID" to "PCSB00598",
                    "TITLE" to "Child of Light",
                    "CONTENT_ID" to "EP0001-PCSB00598_00-CHILDOFLIGHT0000",
                ),
            ),
        ).getOrThrow()
        assertEquals("EP0001-PCSB00598_00-CHILDOFLIGHT0000", info.contentId)
        assertEquals("PCSB00598", info.titleId)
    }

    @Test
    fun `param sfo sin Content ID devuelve null en contentId`() {
        val info = ParamSfo.parse(
            buildSfoBytes(listOf("TITLE_ID" to "PCSB00598", "TITLE" to "Child of Light")),
        ).getOrThrow()
        assertEquals(null, info.contentId)
    }

    @Test
    fun `GameStatus nace sin licencia instalada en el nucleo`() {
        assertFalse(GameStatus().licenseInstalledInCore)
    }

    // --- R5-A4-6: borrado del PUP ---

    private fun writePup(root: File) {
        val dir = File(root, "firmware").apply { mkdirs() }
        File(dir, "PSVUPDAT.PUP").writeBytes(
            byteArrayOf(0x53, 0x43, 0x45, 0x55, 0x46, 0x00, 0x01, 0x02),
        )
    }

    private fun writeMarker(root: File, text: String) {
        val dir = File(root, "firmware").apply { mkdirs() }
        File(dir, "installed.json").writeText(text)
    }

    private fun pupFile(root: File): File = File(File(root, "firmware"), "PSVUPDAT.PUP")

    private fun makeVs0NonEmpty(root: File) {
        File(File(File(root, "vita"), "vs0"), "sys").mkdirs()
    }

    private fun makeVs0Empty(root: File) {
        File(File(root, "vita"), "vs0").mkdirs()
    }

    @Test
    fun `PUP se borra con marcador con version y vs0 no vacio`() {
        val root = tmp.newFolder("vitaRoot")
        writePup(root)
        assertTrue(FirmwareResolver.markInstalled(root, "3.74"))
        makeVs0NonEmpty(root)
        assertTrue(FirmwareResolver.deleteSavedPupIfInstalled(root))
        assertFalse(pupFile(root).exists())
        assertTrue(File(File(root, "firmware"), "installed.json").exists())
    }

    @Test
    fun `PUP NO se borra con marcador sin version aunque vs0 exista`() {
        // R5-A4-6: un marcador huérfano (sin "version") no prueba que
        // el firmware esté instalado: borrar el PUP dejaría sin vía
        // de reinstalación.
        val root = tmp.newFolder("vitaRoot")
        writePup(root)
        writeMarker(root, "{\"file\":\"PSVUPDAT.PUP\"}")
        makeVs0NonEmpty(root)
        assertFalse(FirmwareResolver.deleteSavedPupIfInstalled(root))
        assertTrue(pupFile(root).exists())
    }

    @Test
    fun `PUP NO se borra con version pero vs0 vacio`() {
        // R5-A4-6: el marcador dice instalado pero el vs0 se perdió
        // (borrado a mano, migración fallida): el PUP es la única
        // fuente para reinstalar el firmware.
        val root = tmp.newFolder("vitaRoot")
        writePup(root)
        assertTrue(FirmwareResolver.markInstalled(root, "3.74"))
        makeVs0Empty(root)
        assertFalse(FirmwareResolver.deleteSavedPupIfInstalled(root))
        assertTrue(pupFile(root).exists())
    }

    @Test
    fun `PUP NO se borra con version pero sin vs0`() {
        val root = tmp.newFolder("vitaRoot")
        writePup(root)
        assertTrue(FirmwareResolver.markInstalled(root, "3.74"))
        assertFalse(FirmwareResolver.deleteSavedPupIfInstalled(root))
        assertTrue(pupFile(root).exists())
    }

    // --- R5-A4-9: fusión de DLCs por clave de contenido ---

    @Test
    fun `dlcMergeKey quita el Content ID completo y mayusculas`() {
        assertEquals(
            "CHILDOFLIGHTDLC1",
            GameLibraryRepository.dlcMergeKey("EP0001-PCSB00598_00-CHILDOFLIGHTDLC1"),
        )
        assertEquals(
            "CHILDOFLIGHTDLC1",
            GameLibraryRepository.dlcMergeKey("childoflightdlc1"),
        )
    }

    @Test
    fun `mergeDlcLists cuenta una vez el DLC extraido y su pkg guardado`() {
        val extracted = listOf(DlcInfo("CHILDOFLIGHTDLC1", "PCSB00598", 100L))
        val stored = listOf(
            DlcInfo(
                "CHILDOFLIGHTDLC1",
                "PCSB00598",
                200L,
                isPkg = true,
                contentKey = "EP0001-PCSB00598_00-CHILDOFLIGHTDLC1",
            ),
            DlcInfo("CHILDOFLIGHTDLC2", "PCSB00598", 300L, isPkg = true),
        )
        val merged = GameLibraryRepository.mergeDlcLists(extracted, stored)
        assertEquals(2, merged.size)
        assertEquals(listOf("CHILDOFLIGHTDLC1", "CHILDOFLIGHTDLC2"), merged.map { it.dlcId })
    }
}
