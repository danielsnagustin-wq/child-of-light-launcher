package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.ImportFileGuide
import com.danielsnagustin.childoflight.launcher.data.ImportTreeSearch
import com.danielsnagustin.childoflight.launcher.model.TitleId
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Búsqueda del juego dentro del árbol extraído (carga/clasificación).
 * Pura (solo java.io.File): cubre el camino "elegir archivo → reconocer
 * PCSB00598 (EU) → instalar" sin Android.
 *
 * Regresión del fallo reportado por el usuario (2026-11-07): el selector
 * sí abría, pero al cargar no funcionaba y "no lo reconocía". Causas
 * cubiertas aquí: paquete EU anidado, eboot en mayúsculas y magia ZIP.
 */
class ImportTreeSearchTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun gameTree(vararg path: String, ebootName: String = "eboot.bin"): File {
        var dir = tmp.root
        for (segment in path) {
            dir = File(dir, segment).apply { mkdirs() }
        }
        File(dir, ebootName).writeText("fake-eboot")
        return tmp.root
    }

    @Test
    fun flatEuPackageIsRecognized() {
        val root = gameTree("PCSB00598")
        val found = ImportTreeSearch.findTitleDir(root)
        assertNotNull(found)
        assertEquals(TitleId.EU, found!!.first)
        assertEquals("PCSB00598", found.second.name)
        assertNotNull(ImportTreeSearch.findEboot(found.second))
    }

    @Test
    fun nestedEuPackageIsRecognized() {
        // Paquetes EU habituales: "Child of Light/PCSB00598/eboot.bin".
        // Antes se rechazaban con "no contiene eboot.bin".
        val root = gameTree("Child of Light", "PCSB00598")
        val found = ImportTreeSearch.findTitleDir(root)
        assertNotNull(found)
        assertEquals(TitleId.EU, found!!.first)
        assertNotNull(ImportTreeSearch.findEboot(found.second))
    }

    @Test
    fun deeplyNestedEuPackageIsRecognized() {
        val root = gameTree("Juegos", "PS Vita", "PCSB00598")
        val found = ImportTreeSearch.findTitleDir(root)
        assertNotNull(found)
        assertEquals(TitleId.EU, found!!.first)
    }

    @Test
    fun flatUsPackageIsRecognized() {
        val root = gameTree("PCSE00480")
        val found = ImportTreeSearch.findTitleDir(root)
        assertNotNull(found)
        assertEquals(TitleId.US, found!!.first)
    }

    @Test
    fun unknownTitleIdIsNotRecognized() {
        val root = gameTree("PCSE00999")
        assertNull(ImportTreeSearch.findTitleDir(root))
    }

    @Test
    fun emptyTreeIsNotRecognized() {
        assertNull(ImportTreeSearch.findTitleDir(tmp.root))
    }

    @Test
    fun uppercaseEbootIsRecognized() {
        // EBOOT.BIN (mayúsculas, habitual en dumps): antes la validación
        // lo aceptaba pero el chequeo posterior lo rechazaba.
        val root = gameTree("PCSB00598", ebootName = "EBOOT.BIN")
        val found = ImportTreeSearch.findTitleDir(root)
        assertNotNull(found)
        assertNotNull(ImportTreeSearch.findEboot(found!!.second))
    }

    @Test
    fun addcontDirectlyUnderTitleIsFound() {
        val root = gameTree("PCSB00598")
        val addcont = File(root, "PCSB00598/addcont/PCSB00598DLC001").apply { mkdirs() }
        val found = ImportTreeSearch.findTitleDir(root)
        assertNotNull(found)
        val dir = ImportTreeSearch.findAddcontDir(found!!.second)
        assertNotNull(dir)
        assertEquals(addcont.parentFile, dir)
    }

    @Test
    fun addcontOneLevelDeeperIsFound() {
        val root = gameTree("PCSB00598")
        File(root, "PCSB00598/extra/addcont/PCSB00598DLC001").mkdirs()
        val found = ImportTreeSearch.findTitleDir(root)
        assertNotNull(found)
        assertNotNull(ImportTreeSearch.findAddcontDir(found!!.second))
    }

    @Test
    fun topLevelNamesListFoldersForRejectionMessage() {
        val root = gameTree("Child of Light", "PCSB00598")
        assertEquals(listOf("Child of Light"), ImportTreeSearch.topLevelDirNames(root))
    }

    @Test
    fun zipMagicIsDetected() {
        assertTrue(ImportFileGuide.looksLikeZipMagic(byteArrayOf(0x50, 0x4B, 0x03, 0x04)))
        assertTrue(ImportFileGuide.looksLikeZipMagic(byteArrayOf(0x50, 0x4B, 0x05, 0x06)))
        assertFalse(ImportFileGuide.looksLikeZipMagic(byteArrayOf(0x53, 0x43, 0x45, 0x55)))
        assertFalse(ImportFileGuide.looksLikeZipMagic(byteArrayOf(0x50, 0x4B)))
        assertFalse(ImportFileGuide.looksLikeZipMagic(byteArrayOf()))
    }

    @Test
    fun pupMagicIsDetected() {
        assertTrue(ImportFileGuide.looksLikePupMagic(byteArrayOf(0x53, 0x43, 0x45, 0x55, 0x46)))
        assertFalse(ImportFileGuide.looksLikePupMagic(byteArrayOf(0x50, 0x4B, 0x03, 0x04)))
        assertFalse(ImportFileGuide.looksLikePupMagic(byteArrayOf(0x53, 0x43)))
    }

    // --- Juego plano (eboot.bin en la raíz + sce_sys/param.sfo) ---

    private fun flatGameTree(titleId: String, ebootName: String = "eboot.bin"): File {
        File(tmp.root, ebootName).writeText("fake-eboot")
        val sceSys = File(tmp.root, "sce_sys").apply { mkdirs() }
        File(sceSys, "param.sfo").writeBytes(
            buildSfoBytes(listOf("TITLE_ID" to titleId, "TITLE" to "Child of Light")),
        )
        return tmp.root
    }

    @Test
    fun flatGameWithSfoIsRecognized() {
        val root = flatGameTree("PCSB00598")
        val found = ImportTreeSearch.findFlatGame(root)
        assertNotNull(found)
        assertEquals(TitleId.EU, found!!.first)
        assertEquals(root, found.second)
        assertNull(ImportTreeSearch.findTitleDir(root))
    }

    @Test
    fun flatGameWithUppercaseEbootIsRecognized() {
        val root = flatGameTree("PCSE00480", ebootName = "EBOOT.BIN")
        val found = ImportTreeSearch.findFlatGame(root)
        assertNotNull(found)
        assertEquals(TitleId.US, found!!.first)
    }

    @Test
    fun flatGameWithoutSfoIsNotRecognized() {
        File(tmp.root, "eboot.bin").writeText("fake-eboot")
        assertNull(ImportTreeSearch.findFlatGame(tmp.root))
    }

    @Test
    fun flatGameWithUnknownTitleIsNotRecognized() {
        val root = flatGameTree("PCSE00999")
        assertNull(ImportTreeSearch.findFlatGame(root))
    }

    @Test
    fun flatGameWithoutEbootIsNotRecognized() {
        val sceSys = File(tmp.root, "sce_sys").apply { mkdirs() }
        File(sceSys, "param.sfo").writeBytes(
            buildSfoBytes(listOf("TITLE_ID" to "PCSB00598")),
        )
        assertNull(ImportTreeSearch.findFlatGame(tmp.root))
    }
}
