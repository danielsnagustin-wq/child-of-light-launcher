package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.savesCacheStamp
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Ronda 0.2.70, 7-P2-6: la huella de la caché de saves es
 * (mtime del directorio, bytes totales). Una reescritura de un
 * archivo existente con otro tamaño debe invalidar la caché aunque
 * el mtime del directorio no se mueva (en Linux, modificar el
 * contenido de un archivo no toca el mtime de su carpeta).
 */
class SavesCacheStampTest {

    private fun tempDir(): File = Files.createTempDirectory("saves-stamp").toFile()

    @Test
    fun rewriteWithDifferentSizeChangesStampEvenWithSameDirMtime() {
        val dir = tempDir()
        try {
            val save = File(dir, "save0.bin")
            save.writeText("AAAA")
            val first = savesCacheStamp(dir)
            // Reescribir con otro tamaño y devolver al directorio su
            // mtime anterior: simula el caso que la huella vieja
            // (solo mtime del directorio) no detectaba.
            val dirMtime = dir.lastModified()
            save.writeText("AAAAAAAAAA")
            dir.setLastModified(dirMtime)
            val second = savesCacheStamp(dir)
            assertEquals(first.first, second.first)
            assertNotEquals(first.second, second.second)
            assertNotEquals(first, second)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun unchangedDirKeepsStamp() {
        val dir = tempDir()
        try {
            File(dir, "save0.bin").writeText("AAAA")
            assertEquals(savesCacheStamp(dir), savesCacheStamp(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun newFileChangesStamp() {
        val dir = tempDir()
        try {
            File(dir, "save0.bin").writeText("AAAA")
            val first = savesCacheStamp(dir)
            File(dir, "save1.bin").writeText("B")
            val second = savesCacheStamp(dir)
            assertNotEquals(first, second)
        } finally {
            dir.deleteRecursively()
        }
    }
}
