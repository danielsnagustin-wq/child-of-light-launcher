package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.GameLibraryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * R4-P2-7: al completar una instalación o importación, los tamaños
 * cacheados del juego, el update y los DLCs deben invalidarse.
 * La lista de directorios a invalidar es pura (sin Context) y se
 * prueba aquí; el cableado (quién la llama al terminar de instalar)
 * necesita Context y solo se verifica por lectura de código: no hay
 * Robolectric ni mocks en los tests unitarios.
 */
class DirSizeInvalidationTest {

    @Test
    fun installedSizeDirsCoversAppPatchAddcont() {
        val ux0 = Files.createTempDirectory("ux0-test").toFile()
        try {
            val dirs = GameLibraryRepository.installedSizeDirs("PCSB00598", listOf(ux0))
            assertEquals(
                listOf(
                    File(ux0, "app/PCSB00598"),
                    File(ux0, "patch/PCSB00598"),
                    File(ux0, "addcont/PCSB00598"),
                ),
                dirs,
            )
        } finally {
            ux0.deleteRecursively()
        }
    }

    @Test
    fun installedSizeDirsCoversBothUx0Dirs() {
        val effective = Files.createTempDirectory("ux0-effective").toFile()
        val legacy = Files.createTempDirectory("ux0-legacy").toFile()
        try {
            val dirs = GameLibraryRepository.installedSizeDirs("PCSE00480", listOf(effective, legacy))
            assertEquals(6, dirs.size)
            assertTrue(File(effective, "app/PCSE00480") in dirs)
            assertTrue(File(effective, "patch/PCSE00480") in dirs)
            assertTrue(File(effective, "addcont/PCSE00480") in dirs)
            assertTrue(File(legacy, "app/PCSE00480") in dirs)
            assertTrue(File(legacy, "patch/PCSE00480") in dirs)
            assertTrue(File(legacy, "addcont/PCSE00480") in dirs)
        } finally {
            effective.deleteRecursively()
            legacy.deleteRecursively()
        }
    }

    @Test
    fun installedSizeDirsIsEmptyWithoutUx0() {
        assertEquals(
            emptyList<File>(),
            GameLibraryRepository.installedSizeDirs("PCSB00598", emptyList()),
        )
    }
}
