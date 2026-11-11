package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.TrophyDeletePaths
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Tests de la lógica pura nueva de la ronda 0.2.57, trabajador E
 * (borrado de trofeos por la ruta real trophy/data/<NPCOMMID>/).
 * Archivo propio para no colisionar con otros trabajadores. Puro JVM:
 * solo java.io.File, sin Android ni núcleo. El borrado en sí
 * (deleteGameData) no se prueba aquí: necesita la app corriendo y
 * los dos ux0 del repositorio; es solo-teléfono (ver informe de la ronda).
 */
class TrophyDeletePathsTest {

    @get:Rule
    val tmp = org.junit.rules.TemporaryFolder()

    private fun ux0(): File = File(tmp.root, "ux0").apply { mkdirs() }

    private fun appDir(ux0: File, titleId: String): File =
        File(ux0, "app/$titleId").apply { mkdirs() }

    private fun writeBundledTrp(appDir: File, npcommid: String) {
        val dir = File(appDir, "sce_sys/trophy").apply { mkdirs() }
        File(dir, "TROPHY.TRP").writeText(
            "<trophyconf><npcommid>$npcommid</npcommid>" +
                "<trophy id=\"000\" hidden=\"no\" ttype=\"B\" /></trophyconf>",
        )
    }

    private fun dataSet(ux0: File, npcommid: String): File =
        File(ux0, "user/00/trophy/data/$npcommid").apply { mkdirs() }

    @Test
    fun npcommidResuelto_devuelveDirRealYLegacy() {
        val ux0 = ux0()
        val app = appDir(ux0, "PCSB00598")
        writeBundledTrp(app, "NPWR00001_00")
        val real = dataSet(ux0, "NPWR00001_00")
        File(real, "TROPCONF.SFM").writeText("conf")
        File(real, "TROPUSR.DAT").writeBytes(ByteArray(8))
        val legacy = File(ux0, "user/00/trophy/PCSB00598").apply { mkdirs() }
        File(legacy, "viejo.txt").writeText("resto viejo")

        val found = TrophyDeletePaths.trophyDirsToDelete(ux0, app, "PCSB00598")

        assertTrue(found.contains(real))
        assertTrue(found.contains(legacy))
        assertEquals(2, found.size)
    }

    @Test
    fun npcommidResuelto_noTocaOtroSet() {
        // Con NPCOMMID conocido, un set de otro juego (u otra región
        // con otro NPCOMMID) no entra en la lista: solo el set propio
        // y la ruta vieja.
        val ux0 = ux0()
        val app = appDir(ux0, "PCSB00598")
        writeBundledTrp(app, "NPWR00001_00")
        dataSet(ux0, "NPWR00001_00")
        val other = dataSet(ux0, "NPWR00002_00")

        val found = TrophyDeletePaths.trophyDirsToDelete(ux0, app, "PCSB00598")

        assertFalse(found.contains(other))
        assertEquals(1, found.size)
    }

    @Test
    fun sinTrp_borraTodasLasSubcarpetasDeData() {
        // Sin TRP legible no se sabe cuál es la carpeta de este juego:
        // el launcher es de un solo juego, así que valen todas las
        // subcarpetas directas de trophy/data/.
        val ux0 = ux0()
        val app = appDir(ux0, "PCSB00598")
        val first = dataSet(ux0, "NPWR00001_00")
        val second = dataSet(ux0, "NPWR00002_00")

        val found = TrophyDeletePaths.trophyDirsToDelete(ux0, app, "PCSB00598")

        assertTrue(found.contains(first))
        assertTrue(found.contains(second))
        assertEquals(2, found.size)
    }

    @Test
    fun sinNada_noDevuelveNada() {
        val ux0 = ux0()
        val app = appDir(ux0, "PCSB00598")

        assertTrue(TrophyDeletePaths.trophyDirsToDelete(ux0, app, "PCSB00598").isEmpty())
    }

    @Test
    fun respaldoBu_delMismoSet_seBorraConEl() {
        val ux0 = ux0()
        val app = appDir(ux0, "PCSB00598")
        writeBundledTrp(app, "NPWR00001_00")
        val real = dataSet(ux0, "NPWR00001_00")
        val backup = dataSet(ux0, "_BU_NPWR00001_00")

        val found = TrophyDeletePaths.trophyDirsToDelete(ux0, app, "PCSB00598")

        assertTrue(found.contains(real))
        assertTrue(found.contains(backup))
        assertEquals(2, found.size)
    }

    @Test
    fun archivosSueltosEnData_noSeListan() {
        // Un archivo suelto directamente bajo trophy/data/ no es un
        // set de trofeos: no se borra.
        val ux0 = ux0()
        val app = appDir(ux0, "PCSB00598")
        val stray = File(ux0, "user/00/trophy/data").apply { mkdirs() }
        File(stray, "suelto.txt").writeText("x")

        val found = TrophyDeletePaths.trophyDirsToDelete(ux0, app, "PCSB00598")

        assertTrue(found.isEmpty())
    }

    @Test
    fun npcommidConSeparador_noSeUsaComoRuta() {
        // Un <npcommid> manipulado con "../" no puede escapar de
        // trophy/data/: se rechaza y se cae al fallback (todas las
        // subcarpetas de data/), nunca a una ruta fuera.
        val ux0 = ux0()
        val app = appDir(ux0, "PCSB00598")
        writeBundledTrp(app, "../fuera")
        val own = dataSet(ux0, "NPWR00001_00")

        assertEquals(null, TrophyDeletePaths.resolveNpcommid(app))

        val found = TrophyDeletePaths.trophyDirsToDelete(ux0, app, "PCSB00598")
        assertTrue(found.contains(own))
        val dataDir = File(ux0, "user/00/trophy/data").canonicalPath
        assertTrue(found.all { it.canonicalPath.startsWith("$dataDir/") })
        assertFalse(found.contains(File(ux0, "user/00/trophy/data/../fuera")))
    }

    @Test
    fun isValidNpcommid_aceptaFormatoRealYRechazaRaros() {
        assertTrue(TrophyDeletePaths.isValidNpcommid("NPWR00001_00"))
        assertTrue(TrophyDeletePaths.isValidNpcommid("NPWR12049_00"))
        assertFalse(TrophyDeletePaths.isValidNpcommid(null))
        assertFalse(TrophyDeletePaths.isValidNpcommid(""))
        assertFalse(TrophyDeletePaths.isValidNpcommid("../fuera"))
        assertFalse(TrophyDeletePaths.isValidNpcommid("NPWR 00001"))
        assertFalse(TrophyDeletePaths.isValidNpcommid("NPWR00001_00/otro"))
    }

    @Test
    fun resolveNpcommid_leeElTrpEmpaquetado() {
        val ux0 = ux0()
        val app = appDir(ux0, "PCSB00598")
        writeBundledTrp(app, "NPWR00001_00")

        assertEquals("NPWR00001_00", TrophyDeletePaths.resolveNpcommid(app))
    }

    @Test
    fun resolveNpcommid_sinTrp_devuelveNull() {
        val ux0 = ux0()
        val app = appDir(ux0, "PCSB00598")

        assertEquals(null, TrophyDeletePaths.resolveNpcommid(app))
    }

    @Test
    fun rutas_construidas_noSalenDeTrophy() {
        // Contención: ninguna ruta devuelta sale de
        // ux0/user/00/trophy (ni de ux0 entero, ni de otros títulos).
        val ux0 = ux0()
        val app = appDir(ux0, "PCSB00598")
        writeBundledTrp(app, "NPWR00001_00")
        dataSet(ux0, "NPWR00001_00")
        File(ux0, "user/00/trophy/PCSB00598").apply { mkdirs() }

        val trophyRoot = File(ux0, "user/00/trophy").canonicalPath
        val found = TrophyDeletePaths.trophyDirsToDelete(ux0, app, "PCSB00598")
        assertTrue(found.isNotEmpty())
        assertTrue(found.all { it.canonicalPath.startsWith("$trophyRoot/") })
    }
}
