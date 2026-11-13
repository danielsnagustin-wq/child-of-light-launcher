/**
 * Pruebas unitarias para la caché RAWG en disco.
 *
 * Estas pruebas no utilizan red ni dependencias de Android; la clase `RawgCache`
 * es Kotlin puro y se prueba directamente con archivos temporales.
 */
package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.RawgCache
import com.danielsnagustin.childoflight.launcher.online.RawgGameDetails
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RawgCacheTest {

    @Test
    fun roundTripKeepsDetails() {
        val filesDir = Files.createTempDirectory("rawg-files").toFile()
        val cacheDir = Files.createTempDirectory("rawg-cache").toFile()
        try {
            val cache = RawgCache(filesDir, cacheDir)

            val details = RawgGameDetails(
                id = 1675,
                slug = "child-of-light",
                name = "Child of Light",
                description = "Aurora \"la luz\"\n y más — ñ",
                genres = listOf("Indie", "RPG"),
                released = "2014-04-29",
                backgroundImageUrl = "https://example.com/child-of-light.jpg"
            )

            assertTrue(cache.saveDetails("Child of Light", details))
            val loaded = cache.loadDetails("Child of Light")
            assertEquals(details, loaded)
        } finally {
            filesDir.deleteRecursively()
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun roundTripKeepsNulls() {
        val filesDir = Files.createTempDirectory("rawg-files").toFile()
        val cacheDir = Files.createTempDirectory("rawg-cache").toFile()
        try {
            val cache = RawgCache(filesDir, cacheDir)

            val details = RawgGameDetails(
                id = 9999,
                slug = "null-test",
                name = "Null Test",
                description = null,
                genres = emptyList(),
                released = null,
                backgroundImageUrl = null
            )

            assertTrue(cache.saveDetails("Null Test", details))
            val loaded = cache.loadDetails("Null Test")
            assertEquals(details, loaded)
        } finally {
            filesDir.deleteRecursively()
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun roundTripKeepsCover() {
        val filesDir = Files.createTempDirectory("rawg-files").toFile()
        val cacheDir = Files.createTempDirectory("rawg-cache").toFile()
        try {
            val cache = RawgCache(filesDir, cacheDir)

            val coverBytes = byteArrayOf(1, 2, 3, 4)
            assertTrue(cache.saveCover("Child of Light", coverBytes))
            val loadedBytes = cache.loadCover("Child of Light")
            assertArrayEquals(coverBytes, loadedBytes)
        } finally {
            filesDir.deleteRecursively()
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun missingCacheReturnsNull() {
        val filesDir = Files.createTempDirectory("rawg-files").toFile()
        val cacheDir = Files.createTempDirectory("rawg-cache").toFile()
        try {
            val cache = RawgCache(filesDir, cacheDir)

            assertNull(cache.loadDetails("Child of Light"))
            assertNull(cache.loadCover("Child of Light"))
        } finally {
            filesDir.deleteRecursively()
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun corruptCacheReturnsNull() {
        val filesDir = Files.createTempDirectory("rawg-files").toFile()
        val cacheDir = Files.createTempDirectory("rawg-cache").toFile()
        try {
            // Simular archivo corrupto
            val corruptFile = File(filesDir, "rawg_details_child_of_light.json")
            corruptFile.writeText("{esto no es json")
            val cache = RawgCache(filesDir, cacheDir)

            assertNull(cache.loadDetails("Child of Light"))
        } finally {
            filesDir.deleteRecursively()
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun differentTitlesDoNotCollide() {
        val filesDir = Files.createTempDirectory("rawg-files").toFile()
        val cacheDir = Files.createTempDirectory("rawg-cache").toFile()
        try {
            val cache = RawgCache(filesDir, cacheDir)

            val details1 = RawgGameDetails(
                id = 1675,
                slug = "child-of-light",
                name = "Child of Light",
                description = "Desc 1",
                genres = listOf("Indie"),
                released = "2014-04-29",
                backgroundImageUrl = "https://example.com/child-of-light.jpg"
            )
            val details2 = RawgGameDetails(
                id = 1234,
                slug = "fatal-frame-ii-crimson-butterfly",
                name = "Fatal Frame II: Crimson Butterfly",
                description = "Desc 2",
                genres = listOf("Horror"),
                released = "2003-12-18",
                backgroundImageUrl = "https://example.com/fatal-frame-ii.jpg"
            )

            assertTrue(cache.saveDetails("Child of Light", details1))
            assertTrue(cache.saveDetails("Fatal Frame II: Crimson Butterfly", details2))

            assertEquals(details1, cache.loadDetails("Child of Light"))
            assertEquals(details2, cache.loadDetails("Fatal Frame II: Crimson Butterfly"))
        } finally {
            filesDir.deleteRecursively()
            cacheDir.deleteRecursively()
        }
    }
}
