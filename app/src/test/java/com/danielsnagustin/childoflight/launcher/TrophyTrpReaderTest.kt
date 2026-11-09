package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.TrpReadResult
import com.danielsnagustin.childoflight.launcher.data.TrophyTrpReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Nombres en inglés de los 27 trofeos del launcher, en su orden 1..27.
// Copia literal de los comentarios EN de model/Achievements.kt (no se
// importa TROPHIES_27 para que este test sea autocontenido y para no
// mezclar la lista bajo investigación con el lector que la verifica).
private val LAUNCHER_27_EN = mapOf(
    1 to "Starcatcher",
    2 to "Over the Moon",
    3 to "Sunrise",
    4 to "Coronation",
    5 to "Comedy",
    6 to "The Girl and the Firefly",
    7 to "Capilli Catch",
    8 to "Sweet Sister",
    9 to "Bolmus Bond",
    10 to "Kategida Kindred",
    11 to "Piscean Promise",
    12 to "Tragedy",
    13 to "Tenth Birthday",
    14 to "Perfectionist",
    15 to "Master Oculist",
    16 to "Scene Stealer",
    17 to "Showstopper",
    18 to "Through the Looking Glass",
    19 to "Winged Fury",
    20 to "Explorer",
    21 to "Perfect Sonnet",
    22 to "Grassroots",
    23 to "General of Light",
    24 to "Guardians of Lemuria",
    25 to "Aurora Borealis",
    26 to "When Pigs Fly",
    27 to "Golden Apple",
)

// Los 20 nombres del set de PlayStation según las fuentes PS (CheatCC
// Vita/PS4, GameRevolution PS4, guía PS3/PS4: 19 bronce + 1 plata,
// 4 secretos), en el orden del XML sintético de estos tests (id 000..019).
private val PS_20_EN = listOf(
    "Starcatcher",
    "Over the Moon",
    "Sunrise",
    "Coronation",
    "Comedy",
    "Capilli Catch",
    "Sweet Sister",
    "Bolmus Bond",
    "Kategida Kindred",
    "Piscean Promise",
    "Tragedy",
    "Tenth Birthday",
    "Perfectionist",
    "Master Oculist",
    "Scene Stealer",
    "Showstopper",
    "Winged Fury",
    "Explorer",
    "Perfect Sonnet",
    "Grassroots",
)

private fun id3(id: Int): String = "%03d".format(id)

private fun buildReducedTrp20(): ByteArray {
    val xml = buildString {
        append("<trophyconf version=\"1.1\">")
        append("<npcommid>NPWR00001_00</npcommid>")
        append("<trophyset-version>01.00</trophyset-version>")
        append("<parental-level license-area=\"default\">0</parental-level>")
        for (id in 0 until 20) {
            val hidden = if (id < 4) "yes" else "no"
            val tier = if (id == 3) "S" else "B"
            append("<trophy id=\"${id3(id)}\" hidden=\"$hidden\" ttype=\"$tier\" pid=\"000\"/>")
        }
        append("</trophyconf>")
    }
    return wrapInFakeContainer(xml)
}

private fun buildFullConf3(): ByteArray {
    val xml = buildString {
        append("<!--Sce-Np-Trophy-Signature: 4c3deadbeef-->")
        append("<trophyconf version=\"1.0\">")
        append("<npcommid>NPWR00001_00</npcommid>")
        append("<trophyset-version>01.00</trophyset-version>")
        append("<trophy id=\"000\" hidden=\"yes\" ttype=\"B\" pid=\"-1\">")
        append("<name>Starcatcher</name><detail>Recover the stars.</detail></trophy>")
        append("<trophy id=\"001\" hidden=\"no\" ttype=\"B\" pid=\"000\">")
        append("<name>Kategida Kindred</name>")
        append("<detail>Óengus joins Aurora's party &amp; fights.</detail></trophy>")
        append("<trophy id=\"002\" hidden=\"no\" ttype=\"S\" pid=\"000\">")
        append("<name>Coronation</name><detail>Dethrone the Queen of the Night.</detail></trophy>")
        append("</trophyconf>")
    }
    return wrapInFakeContainer(xml)
}

private fun buildReducedTrp3(): ByteArray {
    val xml = buildString {
        append("<trophyconf version=\"1.1\">")
        append("<npcommid>NPWR00001_00</npcommid>")
        append("<trophyset-version>01.00</trophyset-version>")
        for (id in 0 until 3) {
            append("<trophy id=\"${id3(id)}\" hidden=\"no\" ttype=\"B\" pid=\"000\"/>")
        }
        append("</trophyconf>")
    }
    return wrapInFakeContainer(xml)
}

// Simula un contenedor TRP opaco: bytes binarios (cabecera falsa + un
// falso PNG) antes y después del XML. El lector no parsea el contenedor,
// solo busca el XML en texto plano dentro de los bytes.
private fun wrapInFakeContainer(xml: String): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(byteArrayOf(0x54, 0x52, 0x50, 0x00, 0x01, 0x00, 0x00, 0x00))
    out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
    out.write(ByteArray(256) { (it % 251).toByte() })
    out.write(xml.toByteArray(Charsets.UTF_8))
    out.write(ByteArray(128) { 0 })
    return out.toByteArray()
}

private fun success(result: TrpReadResult) =
    (result as TrpReadResult.Success).value

private fun unreadable(result: TrpReadResult) =
    (result as TrpReadResult.Unreadable)

class TrophyTrpReaderTest {

    @Test
    fun reduced20_countsTwentyWithSecretsAndSilver() {
        val value = success(TrophyTrpReader.read(buildReducedTrp20()))
        assertEquals(20, value.trophyCount)
        assertEquals((0 until 20).toList(), value.trophies.map { it.id })
        assertEquals("NPWR00001_00", value.npcommid)
        assertEquals("01.00", value.trophysetVersion)
        assertFalse(value.hasNames)
        assertEquals(listOf(true, true, true, true), value.trophies.take(4).map { it.hidden })
        assertFalse(value.trophies.drop(4).any { it.hidden })
        assertEquals("S", value.trophies[3].tier)
        assertTrue(value.trophies.filterIndexed { i, _ -> i != 3 }.all { it.tier == "B" })
        assertTrue(value.warnings.isEmpty())
    }

    @Test
    fun fullForm_parsesNamesDetailsEntitiesAndUtf8() {
        val value = success(TrophyTrpReader.read(buildFullConf3()))
        assertEquals(3, value.trophyCount)
        assertTrue(value.hasNames)
        assertTrue(value.signatureMarkerPresent)
        assertEquals("Starcatcher", value.trophies[0].name)
        assertEquals("Recover the stars.", value.trophies[0].detail)
        assertEquals("Kategida Kindred", value.trophies[1].name)
        assertEquals(
            "Óengus joins Aurora's party & fights.",
            value.trophies[1].detail,
        )
        assertEquals("S", value.trophies[2].tier)
    }

    @Test
    fun twoSpans_sameCount_prefersTheOneWithNames() {
        val combined = buildReducedTrp3() + buildFullConf3()
        val value = success(TrophyTrpReader.read(combined))
        assertEquals(3, value.trophyCount)
        assertTrue(value.hasNames)
        assertEquals(2, value.spansFound)
        assertTrue(value.warnings.any { it.contains("2 bloques") })
    }

    @Test
    fun twoSpans_moreTrophiesWinsEvenWithoutNames() {
        val combined = buildReducedTrp20() + buildFullConf3()
        val value = success(TrophyTrpReader.read(combined))
        assertEquals(20, value.trophyCount)
        assertFalse(value.hasNames)
        assertEquals(2, value.spansFound)
    }

    @Test
    fun truncatedXml_isUnreadableNotCrash() {
        val full = String(buildReducedTrp20(), Charsets.UTF_8)
        val cut = full.substring(0, full.indexOf("</trophyconf>") - 50)
        val result = unreadable(TrophyTrpReader.read(cut.toByteArray(Charsets.UTF_8)))
        assertTrue(result.reason.contains("truncado"))
    }

    @Test
    fun randomBytes_isUnreadable() {
        val bytes = ByteArray(1024) { (it * 7 % 256).toByte() }
        val result = unreadable(TrophyTrpReader.read(bytes))
        assertTrue(result.reason.contains("trophyconf"))
    }

    @Test
    fun emptyBytes_isUnreadable() {
        val result = unreadable(TrophyTrpReader.read(ByteArray(0)))
        assertTrue(result.reason.contains("vacío"))
    }

    @Test
    fun duplicateIds_areSkippedWithWarning() {
        // P2-24: un id duplicado no invalida todo el diagnóstico: se
        // ignora la repetición (con aviso) y la entrada válida se lee.
        // El test viejo esperaba Unreadable, pero ese contrato cambió
        // en P2-24 (ver malformedEntriesAreSkippedWithWarning).
        val xml = "<trophyconf version=\"1.1\">" +
            "<trophy id=\"000\" hidden=\"no\" ttype=\"B\" pid=\"000\"/>" +
            "<trophy id=\"000\" hidden=\"no\" ttype=\"B\" pid=\"000\"/>" +
            "</trophyconf>"
        val value = success(TrophyTrpReader.read(wrapInFakeContainer(xml)))
        assertEquals(1, value.trophyCount)
        assertEquals(listOf(0), value.trophies.map { it.id })
        assertTrue(value.warnings.any { it.contains("duplicado") })
    }

    @Test
    fun trophyWithoutId_isUnreadable() {
        val xml = "<trophyconf version=\"1.1\">" +
            "<trophy hidden=\"no\" ttype=\"B\" pid=\"000\"/>" +
            "</trophyconf>"
        val result = unreadable(TrophyTrpReader.read(wrapInFakeContainer(xml)))
        assertTrue(result.reason.contains("sin atributo id"))
    }

    @Test
    fun malformedEntriesAreSkippedWithWarning() {
        // P2-24: una entrada mala no invalida todo el diagnóstico:
        // se ignora solo ella y las válidas se leen igual.
        val xml = "<trophyconf version=\"1.1\">" +
            "<trophy id=\"000\" hidden=\"no\" ttype=\"B\" pid=\"000\"/>" +
            "<trophy hidden=\"no\" ttype=\"B\" pid=\"000\"/>" +
            "<trophy id=\"abc\" hidden=\"no\" ttype=\"B\" pid=\"000\"/>" +
            "<trophy id=\"001\" hidden=\"no\" ttype=\"B\" pid=\"000\"/>" +
            "<trophy id=\"001\" hidden=\"no\" ttype=\"B\" pid=\"000\"/>" +
            "<trophy id=\"1000\" hidden=\"no\" ttype=\"B\" pid=\"000\"/>" +
            "</trophyconf>"
        val value = success(TrophyTrpReader.read(wrapInFakeContainer(xml)))
        assertEquals(2, value.trophyCount)
        assertEquals(listOf(0, 1), value.trophies.map { it.id })
        assertEquals(4, value.warnings.size)
        assertTrue(value.warnings.any { it.contains("sin atributo id") })
        assertTrue(value.warnings.any { it.contains("no numérico") })
        assertTrue(value.warnings.any { it.contains("duplicado") })
        assertTrue(value.warnings.any { it.contains("fuera de rango") })
    }

    @Test
    fun nonSequentialIds_produceWarningButStillParse() {
        val xml = "<trophyconf version=\"1.1\">" +
            "<trophy id=\"000\" hidden=\"no\" ttype=\"B\" pid=\"000\"/>" +
            "<trophy id=\"002\" hidden=\"no\" ttype=\"B\" pid=\"000\"/>" +
            "</trophyconf>"
        val value = success(TrophyTrpReader.read(wrapInFakeContainer(xml)))
        assertEquals(2, value.trophyCount)
        assertTrue(value.warnings.any { it.contains("no son 000") })
    }

    @Test
    fun readFile_missingFile_isUnreadable() {
        val result = unreadable(
            TrophyTrpReader.readFile(File("/tmp/no-existe-TROPHY.TRP")),
        )
        assertTrue(result.reason.contains("no existe"))
    }

    @Test
    fun readFile_validTempFile_parses() {
        val dir = Files.createTempDirectory("trp-test-").toFile()
        try {
            val file = File(dir, "TROPHY.TRP")
            file.writeBytes(buildReducedTrp20())
            val value = success(TrophyTrpReader.readFile(file))
            assertEquals(20, value.trophyCount)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun readBundledTrp_withoutTrp_isUnreadable() {
        val dir = Files.createTempDirectory("trp-test-").toFile()
        try {
            val result = unreadable(TrophyTrpReader.readBundledTrp(dir))
            assertTrue(result.reason.contains("no trae TROPHY.TRP"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun readBundledTrp_findsTrpUnderSceSys() {
        val dir = Files.createTempDirectory("trp-test-").toFile()
        try {
            val trophyDir = File(dir, "sce_sys/trophy").apply { mkdirs() }
            File(trophyDir, "TROPHY.TRP").writeBytes(buildReducedTrp20())
            val value = success(TrophyTrpReader.readBundledTrp(dir))
            assertEquals(20, value.trophyCount)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun compareByName_twentyPsNamesAgainstTwentySeven() {
        val xml = buildString {
            append("<trophyconf version=\"1.0\">")
            append("<npcommid>NPWR00001_00</npcommid>")
            append("<trophyset-version>01.00</trophyset-version>")
            for ((index, name) in PS_20_EN.withIndex()) {
                val hidden = if (index < 4) "yes" else "no"
                val tier = if (index == 3) "S" else "B"
                append("<trophy id=\"${id3(index)}\" hidden=\"$hidden\" ttype=\"$tier\" pid=\"000\">")
                append("<name>$name</name><detail>Detail $index.</detail></trophy>")
            }
            append("</trophyconf>")
        }
        val value = success(TrophyTrpReader.read(wrapInFakeContainer(xml)))
        val comparison = TrophyTrpReader.compareByName(value, LAUNCHER_27_EN)
        assertEquals(20, comparison.trpCount)
        assertEquals(27, comparison.launcherCount)
        assertEquals(
            listOf(1, 2, 3, 4, 5, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 19, 20, 21, 22),
            comparison.matchedLauncherNumbers,
        )
        assertEquals(listOf(6, 18, 23, 24, 25, 26, 27), comparison.launcherOnlyNumbers)
        assertTrue(comparison.trpOnlyNames.isEmpty())
    }

    @Test
    fun compareByName_trpOnlyNameIsReported() {
        val xml = "<trophyconf version=\"1.0\">" +
            "<trophy id=\"000\" hidden=\"no\" ttype=\"B\" pid=\"-1\">" +
            "<name>Starcatcher</name><detail>Recover the stars.</detail></trophy>" +
            "<trophy id=\"001\" hidden=\"no\" ttype=\"B\" pid=\"000\">" +
            "<name>Something Else Entirely</name><detail>Unknown.</detail></trophy>" +
            "</trophyconf>"
        val value = success(TrophyTrpReader.read(wrapInFakeContainer(xml)))
        val comparison = TrophyTrpReader.compareByName(value, LAUNCHER_27_EN)
        assertEquals(listOf("Something Else Entirely"), comparison.trpOnlyNames)
        assertEquals(listOf(1), comparison.matchedLauncherNumbers)
    }

    @Test
    fun compareByName_matchingIgnoresCaseAndExtraSpaces() {
        val xml = "<trophyconf version=\"1.0\">" +
            "<trophy id=\"000\" hidden=\"no\" ttype=\"B\" pid=\"-1\">" +
            "<name>  starcatcher </name><detail>x</detail></trophy>" +
            "</trophyconf>"
        val value = success(TrophyTrpReader.read(wrapInFakeContainer(xml)))
        val comparison = TrophyTrpReader.compareByName(value, LAUNCHER_27_EN)
        assertEquals(listOf(1), comparison.matchedLauncherNumbers)
    }

    // R4-P1-8: la caché del TRP por (ruta, tamaño, última
    // modificación). Cada test usa su propio directorio temporal: la
    // caché es de una entrada en el objeto y dos rutas distintas no
    // deben compartir resultado.

    @Test
    fun readFileCached_secondReadWithoutChange_returnsSameResult() {
        val dir = Files.createTempDirectory("trp-cache-").toFile()
        try {
            val file = File(dir, "TROPHY.TRP")
            file.writeBytes(buildReducedTrp20())
            val first = success(TrophyTrpReader.readFileCached(file))
            val second = success(TrophyTrpReader.readFileCached(file))
            assertEquals(20, first.trophyCount)
            assertEquals(first, second)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun readFileCached_sameStatAfterRewrite_returnsCachedNotNewContent() {
        // Prueba de que la segunda lectura NO toca el disco: se
        // reescribe el archivo con otro npcommid del MISMO tamaño y
        // se restaura la fecha original, así que la clave de caché no
        // cambia y debe devolverse el contenido viejo.
        val dir = Files.createTempDirectory("trp-cache-").toFile()
        try {
            val file = File(dir, "TROPHY.TRP")
            file.writeBytes(buildReducedTrp20())
            val originalModified = file.lastModified()
            val first = success(TrophyTrpReader.readFileCached(file))
            assertEquals("NPWR00001_00", first.npcommid)
            // Mismo tamaño, otro npcommid: reemplazo a nivel de bytes
            // (el contenedor lleva bytes binarios y un round-trip por
            // String UTF-8 no conservaría el tamaño exacto).
            val rewritten = buildReducedTrp20()
            val needle = "NPWR00001_00".toByteArray(Charsets.UTF_8)
            val replacement = "NPWR00002_00".toByteArray(Charsets.UTF_8)
            var index = -1
            for (i in 0..rewritten.size - needle.size) {
                if (rewritten.copyOfRange(i, i + needle.size).contentEquals(needle)) {
                    index = i
                    break
                }
            }
            assertTrue(index >= 0)
            replacement.copyInto(rewritten, index)
            assertEquals(buildReducedTrp20().size, rewritten.size)
            file.writeBytes(rewritten)
            assertTrue(file.setLastModified(originalModified))
            val second = success(TrophyTrpReader.readFileCached(file))
            assertEquals("NPWR00001_00", second.npcommid)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun readFileCached_rewrittenFile_invalidatesCache() {
        val dir = Files.createTempDirectory("trp-cache-").toFile()
        try {
            val file = File(dir, "TROPHY.TRP")
            file.writeBytes(buildReducedTrp20())
            assertEquals(20, success(TrophyTrpReader.readFileCached(file)).trophyCount)
            // Otro contenido y otro tamaño: la clave cambia y la
            // caché se invalida (es lo que pasa al reinstalar el juego).
            file.writeBytes(buildReducedTrp3())
            assertEquals(3, success(TrophyTrpReader.readFileCached(file)).trophyCount)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun readFileCached_differentPaths_doNotShareResults() {
        val dirA = Files.createTempDirectory("trp-cache-a-").toFile()
        val dirB = Files.createTempDirectory("trp-cache-b-").toFile()
        try {
            val fileA = File(dirA, "TROPHY.TRP")
            val fileB = File(dirB, "TROPHY.TRP")
            fileA.writeBytes(buildReducedTrp20())
            fileB.writeBytes(buildReducedTrp3())
            assertEquals(20, success(TrophyTrpReader.readFileCached(fileA)).trophyCount)
            assertEquals(3, success(TrophyTrpReader.readFileCached(fileB)).trophyCount)
            // Releer el primero no devuelve el del segundo.
            assertEquals(20, success(TrophyTrpReader.readFileCached(fileA)).trophyCount)
        } finally {
            dirA.deleteRecursively()
            dirB.deleteRecursively()
        }
    }

    @Test
    fun readBundledTrpCached_secondRead_returnsCachedAndInvalidatesOnRewrite() {
        val dir = Files.createTempDirectory("trp-cache-").toFile()
        try {
            val trophyDir = File(dir, "sce_sys/trophy").apply { mkdirs() }
            File(trophyDir, "TROPHY.TRP").writeBytes(buildReducedTrp20())
            assertEquals(20, success(TrophyTrpReader.readBundledTrp(dir)).trophyCount)
            assertEquals(20, success(TrophyTrpReader.readBundledTrp(dir)).trophyCount)
            File(trophyDir, "TROPHY.TRP").writeBytes(buildReducedTrp3())
            assertEquals(3, success(TrophyTrpReader.readBundledTrp(dir)).trophyCount)
        } finally {
            dir.deleteRecursively()
        }
    }
}
