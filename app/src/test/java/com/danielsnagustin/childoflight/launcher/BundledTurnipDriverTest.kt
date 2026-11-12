package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.BundledTurnipDriver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Tests del driver Turnip incluido (BundledTurnipDriver).
 * Kotlin puro (sin android.*): corre en la JVM, sin teléfono.
 * No toca TurnipDriverRepository (necesita Context) ni el ViewModel.
 */
class BundledTurnipDriverTest {

    // --- Selección por modelo de Adreno ---

    @Test
    fun adreno740UsesStandardGmem() {
        assertEquals(
            BundledTurnipDriver.BundledTurnipVariant.STANDARD_GMEM,
            BundledTurnipDriver.selectVariant("Adreno (TM) 740"),
        )
    }

    @Test
    fun adreno650And619UseStandardGmem() {
        assertEquals(
            BundledTurnipDriver.BundledTurnipVariant.STANDARD_GMEM,
            BundledTurnipDriver.selectVariant("Adreno (TM) 650"),
        )
        assertEquals(
            BundledTurnipDriver.BundledTurnipVariant.STANDARD_GMEM,
            BundledTurnipDriver.selectVariant("Adreno (TM) 619"),
        )
    }

    @Test
    fun adreno8xxHasNoBundledVariant() {
        assertNull(BundledTurnipDriver.selectVariant("Adreno (TM) 830"))
        assertNull(BundledTurnipDriver.selectVariant("Adreno (TM) 840"))
    }

    @Test
    fun adreno710And720HaveNoBundledVariant() {
        assertNull(BundledTurnipDriver.selectVariant("Adreno (TM) 710"))
        assertNull(BundledTurnipDriver.selectVariant("Adreno (TM) 720"))
    }

    @Test
    fun maliAndUnknownHaveNoBundledVariant() {
        assertNull(BundledTurnipDriver.selectVariant("Mali-G57 MC2"))
        assertNull(BundledTurnipDriver.selectVariant("Adreno A32"))
        assertNull(BundledTurnipDriver.selectVariant(""))
        assertNull(BundledTurnipDriver.selectVariant("Adreno (TM)"))
    }

    // --- SHA-256 ---

    @Test
    fun sha256KnownVector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            BundledTurnipDriver.sha256Hex("abc".toByteArray()),
        )
    }

    // --- Validación del paquete ---

    private fun buildZip(entries: Map<String, ByteArray>): File {
        val file = File.createTempFile("turnip-test-", ".zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    private val metaJson = """
        {"name":"Turnip","driverVersion":"v26.0.0","libraryName":"libvulkan_freedreno.so"}
    """.trimIndent().toByteArray()

    @Test
    fun validPackagePasses() {
        val zip = buildZip(
            mapOf(
                "meta.json" to metaJson,
                "libvulkan_freedreno.so" to byteArrayOf(0x7F, 0x45, 0x4C, 0x46),
            ),
        )
        try {
            // Ronda 0.2.64: el zip es falso (su hash nunca es el R8
            // real), así que se valida solo la estructura (""): con
            // el hash por defecto se rechaza (ver wrongShaFails).
            val result = BundledTurnipDriver.validatePackage(zip, expectedSha256 = "")
            assertTrue(result.isSuccess)
            val info = result.getOrThrow()
            assertEquals("libvulkan_freedreno.so", info.libraryName)
            assertEquals(zip.length(), info.sizeBytes)
            assertEquals(64, info.sha256Hex.length)
        } finally {
            zip.delete()
        }
    }

    @Test
    fun wrongShaFails() {
        // Ronda 0.2.64: EXPECTED_SHA256 ya está relleno (R8 real,
        // verificado 2026-11-11): un zip estructuralmente válido
        // pero con otro hash se rechaza con el hash por defecto.
        val zip = buildZip(
            mapOf(
                "meta.json" to metaJson,
                "libvulkan_freedreno.so" to byteArrayOf(0x7F, 0x45, 0x4C, 0x46),
            ),
        )
        try {
            assertTrue(BundledTurnipDriver.validatePackage(zip).isFailure)
        } finally {
            zip.delete()
        }
    }

    @Test
    fun packageWithoutMetaJsonFails() {
        val zip = buildZip(mapOf("libvulkan_freedreno.so" to byteArrayOf(1, 2, 3)))
        try {
            assertTrue(BundledTurnipDriver.validatePackage(zip).isFailure)
        } finally {
            zip.delete()
        }
    }

    @Test
    fun packageWithoutSoFails() {
        val zip = buildZip(mapOf("meta.json" to metaJson))
        try {
            assertTrue(BundledTurnipDriver.validatePackage(zip).isFailure)
        } finally {
            zip.delete()
        }
    }

    @Test
    fun packageWithMetaNamingMissingSoFails() {
        val zip = buildZip(
            mapOf(
                "meta.json" to metaJson,
                "libvulkan_otro.so" to byteArrayOf(1, 2, 3),
            ),
        )
        try {
            assertTrue(BundledTurnipDriver.validatePackage(zip).isFailure)
        } finally {
            zip.delete()
        }
    }

    @Test
    fun nonZipFileFails() {
        val file = File.createTempFile("turnip-test-", ".zip")
        try {
            file.writeText("esto no es un zip")
            assertTrue(BundledTurnipDriver.validatePackage(file).isFailure)
        } finally {
            file.delete()
        }
    }

    @Test
    fun missingFileFails() {
        val result = BundledTurnipDriver.validatePackage(File("/tmp/no-existe-turnip-12345.zip"))
        assertTrue(result.isFailure)
    }
}
