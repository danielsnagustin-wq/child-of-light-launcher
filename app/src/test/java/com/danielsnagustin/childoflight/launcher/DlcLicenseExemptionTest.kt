package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.isDlcLicenseInstalledInCore
import com.danielsnagustin.childoflight.launcher.data.isGameLicenseInstalledInCore
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R7-P1-04 resto (0.2.57): la exención de "Falta la licencia" para
 * un .pkg de DLC sin work.bin solo vale si su PROPIO .rif está
 * instalado en el núcleo (ux0/license/<TITLE>/<DLC_CONTENT_ID>.rif).
 * Lógica pura sobre un ux0 sintético, sin Android.
 *
 * El caso que fija la regla: el .rif del JUEGO vive en la misma
 * carpeta license/<TITLE>/ que el del DLC, así que un chequeo por
 * "cualquier .rif en la carpeta" (el fallback de
 * isLicenseInstalledInCore) eximiría todos los DLCs. Aquí solo vale
 * el archivo con el Content ID propio del DLC.
 */
class DlcLicenseExemptionTest {

    private val gameContentId = "EP0001-PCSB00598_00-CHILDOFLIGHT0000"
    private val dlcContentId = "EP0001-PCSB00598_00-CHILDOFLIGHTDLC1"
    private val titleId = "PCSB00598"

    private fun tempUx0(): File =
        Files.createTempDirectory("ux0-dlc-license").toFile()

    private fun putRif(ux0: File, title: String, contentId: String) {
        val dir = File(ux0, "license/$title")
        dir.mkdirs()
        File(dir, "$contentId.rif").writeBytes(ByteArray(512))
    }

    @Test
    fun rifPropioDelDlc_exime() {
        val ux0 = tempUx0()
        try {
            putRif(ux0, titleId, dlcContentId)
            assertTrue(isDlcLicenseInstalledInCore(ux0, titleId, dlcContentId))
        } finally {
            ux0.deleteRecursively()
        }
    }

    @Test
    fun soloRifDelJuego_noEximeAlDlc() {
        val ux0 = tempUx0()
        try {
            putRif(ux0, titleId, gameContentId)
            assertFalse(isDlcLicenseInstalledInCore(ux0, titleId, dlcContentId))
        } finally {
            ux0.deleteRecursively()
        }
    }

    @Test
    fun sinNingunRif_noExime() {
        val ux0 = tempUx0()
        try {
            assertFalse(isDlcLicenseInstalledInCore(ux0, titleId, dlcContentId))
        } finally {
            ux0.deleteRecursively()
        }
    }

    @Test
    fun rifDelDlcEnOtroTitulo_noExime() {
        val ux0 = tempUx0()
        try {
            putRif(ux0, "PCSE00480", dlcContentId)
            assertFalse(isDlcLicenseInstalledInCore(ux0, titleId, dlcContentId))
        } finally {
            ux0.deleteRecursively()
        }
    }

    @Test
    fun rifDelJuegoYDelDlc_eximeAlDlc() {
        val ux0 = tempUx0()
        try {
            putRif(ux0, titleId, gameContentId)
            putRif(ux0, titleId, dlcContentId)
            assertTrue(isDlcLicenseInstalledInCore(ux0, titleId, dlcContentId))
        } finally {
            ux0.deleteRecursively()
        }
    }

    // --- 7-P2-9: el chequeo del JUEGO ya no acepta cualquier .rif ---

    @Test
    fun sinContentId_soloRifDelDlc_noEximeAlJuego() {
        // SFO sin CONTENT_ID + solo el .rif de un DLC en la carpeta:
        // antes el fallback "cualquier .rif" lo eximía; ahora es false.
        val ux0 = tempUx0()
        try {
            putRif(ux0, titleId, dlcContentId)
            assertFalse(isGameLicenseInstalledInCore(ux0, titleId, null))
        } finally {
            ux0.deleteRecursively()
        }
    }

    @Test
    fun sinContentId_sinRif_noEximeAlJuego() {
        val ux0 = tempUx0()
        try {
            assertFalse(isGameLicenseInstalledInCore(ux0, titleId, null))
            assertFalse(isGameLicenseInstalledInCore(ux0, titleId, ""))
            assertFalse(isGameLicenseInstalledInCore(ux0, titleId, "   "))
        } finally {
            ux0.deleteRecursively()
        }
    }

    @Test
    fun conContentId_rifPropio_eximeAlJuego() {
        val ux0 = tempUx0()
        try {
            putRif(ux0, titleId, gameContentId)
            assertTrue(isGameLicenseInstalledInCore(ux0, titleId, gameContentId))
        } finally {
            ux0.deleteRecursively()
        }
    }

    @Test
    fun conContentId_soloRifDelDlc_noEximeAlJuego() {
        val ux0 = tempUx0()
        try {
            putRif(ux0, titleId, dlcContentId)
            assertFalse(isGameLicenseInstalledInCore(ux0, titleId, gameContentId))
        } finally {
            ux0.deleteRecursively()
        }
    }
}
