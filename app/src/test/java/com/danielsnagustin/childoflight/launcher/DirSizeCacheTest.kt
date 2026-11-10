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
            File(dir, "sub").mkdir()
            val f = File(dir, "sub/a.bin")
            f.writeBytes(ByteArray(100))
            assertEquals(100L, cache.sizeOf(dir))
            // Modificar el contenido en el sitio de un archivo ANIDADO
            // no cambia ni el mtime del directorio ni el agregado de
            // primer nivel: sin invalidate, la caché devuelve el valor
            // viejo. (Ronda 0.2.55 P2-9: un archivo de primer nivel
            // que crece SÍ se ve por el agregado; ver el test de abajo.)
            f.writeBytes(ByteArray(200))
            assertEquals(100L, cache.sizeOf(dir))
            cache.invalidate(dir)
            assertEquals(200L, cache.sizeOf(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun sizeUpdatesWhenTopLevelFileGrowsInPlace() {
        // Ronda 0.2.55 P2-9: crecer un archivo de primer nivel sin
        // crear/borrar nada no cambia el mtime del directorio, pero
        // sí el agregado de primer nivel: la caché debe invalidarse
        // sola y devolver el tamaño nuevo.
        val dir = Files.createTempDirectory("cache-test").toFile()
        val cache = DirSizeCache()
        try {
            val f = File(dir, "a.bin")
            f.writeBytes(ByteArray(100))
            assertEquals(100L, cache.sizeOf(dir))
            f.writeBytes(ByteArray(200))
            assertEquals(200L, cache.sizeOf(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun invalidateAfterNestedChangeForcesRescan() {
        // R4-P2-7: el peligro real es el cambio anidado: añadir un
        // archivo dentro de una subcarpeta cambia el mtime de la
        // subcarpeta, no el del directorio cacheado, así que sin
        // invalidate la caché devuelve el tamaño viejo.
        val dir = Files.createTempDirectory("cache-test").toFile()
        val cache = DirSizeCache()
        try {
            File(dir, "sub").mkdir()
            File(dir, "sub/a.bin").writeBytes(ByteArray(100))
            assertEquals(100L, cache.sizeOf(dir))
            File(dir, "sub/b.bin").writeBytes(ByteArray(50))
            assertEquals(100L, cache.sizeOf(dir))
            cache.invalidate(dir)
            assertEquals(150L, cache.sizeOf(dir))
        } finally {
            dir.deleteRecursively()
        }
    }
}
