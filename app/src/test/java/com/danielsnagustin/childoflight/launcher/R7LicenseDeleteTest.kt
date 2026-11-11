package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.dlcLicenseFilesToDelete
import com.danielsnagustin.childoflight.launcher.data.licenseDirFor
import com.danielsnagustin.childoflight.launcher.data.licenseHasRif
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R7-P1-02 y R7-P1-03 (0.2.58): la lógica pura de las licencias
 * (.rif) que borran deleteGameData y deleteDlc, sobre un ux0
 * sintético y sin Android.
 *
 * Las reglas que fijan estos tests:
 * - deleteGameData borra license/<TITLE>/ con el TITLE exacto,
 *   nunca license/ entera (ahí viven las licencias de la otra
 *   región); hasAnyGameData cuenta un título por sus .rif, no por
 *   una carpeta vacía.
 * - deleteDlc borra solo el .rif de ESE DLC (por su clave de
 *   contenido, la misma que empareja su carpeta extraída y su .pkg
 *   guardado): nunca el .rif del juego ni el de otro DLC, que
 *   viven en la misma carpeta.
 */
class R7LicenseDeleteTest {

    private val gameContentId = "EP0001-PCSB00598_00-CHILDOFLIGHT0000"
    private val dlcContentId = "EP0001-PCSB00598_00-CHILDOFLIGHTDLC1"
    private val otherDlcContentId = "EP0001-PCSB00598_00-CHILDOFLIGHTDLC2"
    private val titleId = "PCSB00598"

    private fun tempUx0(): File =
        Files.createTempDirectory("ux0-r7-license").toFile()

    private fun putRif(ux0: File, title: String, contentId: String) {
        val dir = File(ux0, "license/$title")
        dir.mkdirs()
        File(dir, "$contentId.rif").writeBytes(ByteArray(512))
    }

    @Test
    fun licenseDirFor_esLaCarpetaExactaDelTitulo() {
        val ux0 = tempUx0()
        try {
            assertEquals(
                File(ux0, "license/$titleId"),
                licenseDirFor(ux0, titleId),
            )
            assertFalse(licenseDirFor(ux0, titleId) == File(ux0, "license"))
        } finally {
            ux0.deleteRecursively()
        }
    }

    @Test
    fun licenseHasRif_cuentaElRifInstalado() {
        val ux0 = tempUx0()
        try {
            putRif(ux0, titleId, gameContentId)
            assertTrue(licenseHasRif(ux0, titleId))
        } finally {
            ux0.deleteRecursively()
        }
    }

    @Test
    fun licenseHasRif_carpetaVaciaNoEsDato() {
        val ux0 = tempUx0()
        try {
            File(ux0, "license/$titleId").mkdirs()
            assertFalse(licenseHasRif(ux0, titleId))
        } finally {
            ux0.deleteRecursively()
        }
    }

    @Test
    fun licenseHasRif_sinCarpetaNoEsDato() {
        val ux0 = tempUx0()
        try {
            assertFalse(licenseHasRif(ux0, titleId))
        } finally {
            ux0.deleteRecursively()
        }
    }

    @Test
    fun licenseHasRif_archivoSinRifNoCuenta() {
        val ux0 = tempUx0()
        try {
            val dir = File(ux0, "license/$titleId")
            dir.mkdirs()
            File(dir, "nota.txt").writeText("no es una licencia")
            assertFalse(licenseHasRif(ux0, titleId))
        } finally {
            ux0.deleteRecursively()
        }
    }

    @Test
    fun licenseHasRif_rifDeOtraRegionNoCuenta() {
        val ux0 = tempUx0()
        try {
            putRif(ux0, "PCSE00480", gameContentId)
            assertFalse(licenseHasRif(ux0, titleId))
        } finally {
            ux0.deleteRecursively()
        }
    }

    @Test
    fun licenseHasRif_tituloConSeparadoresNoVale() {
        val ux0 = tempUx0()
        try {
            assertFalse(licenseHasRif(ux0, "../PCSB00598"))
            assertFalse(licenseHasRif(ux0, ".."))
            assertFalse(licenseHasRif(ux0, ""))
        } finally {
            ux0.deleteRecursively()
        }
    }

    @Test
    fun dlcLicenseFiles_soloElRifDeEseDlc() {
        val ux0 = tempUx0()
        try {
            putRif(ux0, titleId, gameContentId)
            putRif(ux0, titleId, dlcContentId)
            putRif(ux0, titleId, otherDlcContentId)
            val found = dlcLicenseFilesToDelete(ux0, titleId, setOf("CHILDOFLIGHTDLC1"))
            assertEquals(
                listOf(File(ux0, "license/$titleId/$dlcContentId.rif")),
                found,
            )
        } finally {
            ux0.deleteRecursively()
        }
    }

    @Test
    fun dlcLicenseFiles_nombreDeRifEnMinusculasCoincide() {
        val ux0 = tempUx0()
        try {
            putRif(ux0, titleId, dlcContentId.lowercase())
            val found = dlcLicenseFilesToDelete(ux0, titleId, setOf("CHILDOFLIGHTDLC1"))
            assertEquals(1, found.size)
        } finally {
            ux0.deleteRecursively()
        }
    }

    @Test
    fun dlcLicenseFiles_sinClavesNoDevuelveNada() {
        val ux0 = tempUx0()
        try {
            putRif(ux0, titleId, dlcContentId)
            assertTrue(dlcLicenseFilesToDelete(ux0, titleId, emptySet()).isEmpty())
        } finally {
            ux0.deleteRecursively()
        }
    }

    @Test
    fun dlcLicenseFiles_sinCarpetaNoDevuelveNada() {
        val ux0 = tempUx0()
        try {
            assertTrue(
                dlcLicenseFilesToDelete(ux0, titleId, setOf("CHILDOFLIGHTDLC1")).isEmpty(),
            )
        } finally {
            ux0.deleteRecursively()
        }
    }

    @Test
    fun dlcLicenseFiles_tituloConSeparadoresNoDevuelveNada() {
        val ux0 = tempUx0()
        try {
            putRif(ux0, titleId, dlcContentId)
            assertTrue(
                dlcLicenseFilesToDelete(ux0, "../$titleId", setOf("CHILDOFLIGHTDLC1")).isEmpty(),
            )
        } finally {
            ux0.deleteRecursively()
        }
    }
}
