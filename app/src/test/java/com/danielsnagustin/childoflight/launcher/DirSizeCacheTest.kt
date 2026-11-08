package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.DirSizeCache
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DirSizeCacheTest {

    @Test
    fun sizeIsSumOfFiles() {
        val dir = Files.createTempDirectory("cache-test").toFile()
        try {
            File(dir, "a.bin").writeBytes(ByteArray(100))
            File(dir, "sub").mkdir()
            File(dir, "sub/b.bin").writeBytes(ByteArray(50))
            assertEquals(150L, DirSizeCache().sizeOf(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun missingDirIsZero() {
        val dir = File(Files.createTempDirectory("cache-test").toFile(), "no-existe")
        assertEquals(0L, DirSizeCache().sizeOf(dir))
    }

    @Test
    fun sizeUpdatesWhenContentChanges() {
        val dir = Files.createTempDirectory("cache-test").toFile()
        val cache = DirSizeCache()
        try {
            File(dir, "a.bin").writeBytes(ByteArray(100))
            assertEquals(100L, cache.sizeOf(dir))
            // Añadir un archivo cambia el lastModified del directorio:
            // la caché debe invalidarse y devolver el tamaño nuevo.
            Thread.sleep(10)
            File(dir, "b.bin").writeBytes(ByteArray(25))
            assertEquals(125L, cache.sizeOf(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun invalidateForcesRescan() {
        val dir = Files.createTempDirectory("cache-test").toFile()
        val cache = DirSizeCache()
        try {
            val f = File(dir, "a.bin")
            f.writeBytes(ByteArray(100))
            assertEquals(100L, cache.sizeOf(dir))
            // Modificar el contenido en el sitio no cambia el mtime del
            // directorio: sin invalidate, la caché devuelve el valor viejo.
            f.writeBytes(ByteArray(200))
            assertEquals(100L, cache.sizeOf(dir))
            cache.invalidate(dir)
            assertEquals(200L, cache.sizeOf(dir))
        } finally {
            dir.deleteRecursively()
        }
    }
}
