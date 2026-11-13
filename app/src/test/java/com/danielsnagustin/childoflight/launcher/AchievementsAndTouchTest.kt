package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.AchievementRepository
import com.danielsnagustin.childoflight.launcher.data.TouchLayoutRepository
import com.danielsnagustin.childoflight.launcher.data.builtInProfiles
import com.danielsnagustin.childoflight.launcher.data.defaultTouchLayout
import com.danielsnagustin.childoflight.launcher.model.TROPHIES_20
import com.danielsnagustin.childoflight.launcher.model.TrophyUnlockAnnouncer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AchievementsTest {

    @Test
    fun vitaHas20TrophiesNot27() {
        assertEquals(20, TROPHIES_20.size)
        assertEquals((1..20).toList(), TROPHIES_20.map { it.number })
    }

    @Test
    fun secretTrophiesAreTheFirstFour() {
        val secrets = TROPHIES_20.filter { it.isSecret }.map { it.number }
        assertEquals(listOf(1, 2, 3, 4), secrets)
        assertEquals("Atrapaestrellas", TROPHIES_20[0].name)
        assertEquals("Coronación", TROPHIES_20[3].name)
    }

    @Test
    fun onlyCoronacionHasTierPlataRestAreBronce() {
        assertEquals("Plata", TROPHIES_20.first { it.number == 4 }.tier)
        assertTrue(TROPHIES_20.filter { it.number != 4 }.all { it.tier == "Bronce" })
    }

    @Test
    fun noPlatinumTrophy() {
        assertTrue(TROPHIES_20.none { it.name.contains("Platinum", ignoreCase = true) })
        assertTrue(TROPHIES_20.none { it.tier == "Platinum" })
    }

    @Test
    fun noTrophyNeedsOnline() {
        // La lista Vita de 20 no tiene trofeos online: "A través del
        // cristal" (enviar Oculi a un amigo) solo existe en Xbox One /
        // PC y no está en esta lista.
        assertTrue(TROPHIES_20.none { it.onlineRequired })
        assertTrue(TROPHIES_20.none { it.name == "A través del cristal" })
    }

    @Test
    fun removedTrophiesAreNotInTheList() {
        // Los 7 eliminados de la lista vieja de 27 no están en Vita.
        val names = TROPHIES_20.map { it.name }.toSet()
        assertFalse("La niña y la luciérnaga" in names)
        assertFalse("General de la luz" in names)
        assertFalse("Guardianes de Lemuria" in names)
        assertFalse("Aurora borealis" in names)
        assertFalse("Cuando los cerdos vuelen" in names)
        assertFalse("La manzana dorada" in names)
    }

    @Test
    fun spanishNamesAreOfficial() {
        // Nombres oficiales en español (EliteGuías + AllGamersIn).
        val byNumber = TROPHIES_20.associateBy { it.number }
        assertEquals("Atrapaestrellas", byNumber.getValue(1).name)
        assertEquals("En la luna", byNumber.getValue(2).name)
        assertEquals("Amanecer", byNumber.getValue(3).name)
        assertEquals("Coronación", byNumber.getValue(4).name)
        assertEquals("Comedia", byNumber.getValue(5).name)
        assertEquals("Captura de un Capilli", byNumber.getValue(6).name)
        assertEquals("Hermana querida", byNumber.getValue(7).name)
        assertEquals("Unión de un Bolmus", byNumber.getValue(8).name)
        assertEquals("La familia Kategida", byNumber.getValue(9).name)
        assertEquals("Promesa de los piscis", byNumber.getValue(10).name)
        assertEquals("Tragedia", byNumber.getValue(11).name)
        assertEquals("Décimo cumpleaños", byNumber.getValue(12).name)
        assertEquals("Perfeccionista", byNumber.getValue(13).name)
        assertEquals("Experto oculista", byNumber.getValue(14).name)
        assertEquals("Robando protagonismo", byNumber.getValue(15).name)
        assertEquals("Aguafiestas", byNumber.getValue(16).name)
        assertEquals("Furia alada", byNumber.getValue(17).name)
        assertEquals("Explorador", byNumber.getValue(18).name)
        assertEquals("Soneto perfecto", byNumber.getValue(19).name)
        assertEquals("Raíces", byNumber.getValue(20).name)
        // Ningún nombre en inglés: la lista es española en todas las
        // superficies (lista, diálogo, popup, log).
        assertTrue(TROPHIES_20.none { it.name == "Starcatcher" || it.name == "Showstopper" || it.name == "Master Oculist" })
    }

    @Test
    fun englishNamesAreOnlyForTrpMapping() {
        // El nombre en inglés vive en englishName (puente JNI, para
        // mapear por nombre contra el TROPHY.TRP) y nunca es el
        // nombre visible.
        val byNumber = TROPHIES_20.associateBy { it.number }
        assertEquals("Starcatcher", byNumber.getValue(1).englishName)
        assertEquals("Coronation", byNumber.getValue(4).englishName)
        assertEquals("Master Oculist", byNumber.getValue(14).englishName)
        assertEquals("Showstopper", byNumber.getValue(16).englishName)
        assertEquals("Winged Fury", byNumber.getValue(17).englishName)
        assertEquals("Grassroots", byNumber.getValue(20).englishName)
        assertTrue(TROPHIES_20.all { it.englishName.isNotBlank() })
        assertTrue(TROPHIES_20.none { it.name == it.englishName })
    }
}

/**
 * Migración 27 -> 20 del progreso guardado (0.2.57): la decisión y
 * su cálculo son funciones puras del companion de
 * AchievementRepository (sin Android: no hay Robolectric en los
 * tests unitarios). El edit en DataStore solo se verifica en el
 * teléfono.
 */
class AchievementRepositoryTest {

    @Test
    fun encodeDecodeRoundTripKeepsAllEntries() {
        val original = mapOf(1 to 1000L, 4 to 2000L, 20 to 3000L)
        val encoded = AchievementRepository.encode(original)
        assertEquals("1|1000;4|2000;20|3000", encoded)
        assertEquals(original, AchievementRepository.decode(encoded))
        assertEquals(emptyMap<Int, Long>(), AchievementRepository.decode(""))
        // Entradas inválidas se descartan; números fuera de 1..20
        // los filtra el repositorio, no decode.
        assertEquals(mapOf(6 to 500L, 21 to 900L), AchievementRepository.decode("6|500;xx|1;7|;21|900"))
    }

    @Test
    fun oldVersionMigratesAndCurrentVersionDoesNot() {
        assertTrue(AchievementRepository.shouldMigrateTrophyList(0))
        assertTrue(AchievementRepository.shouldMigrateTrophyList(1))
        assertFalse(AchievementRepository.shouldMigrateTrophyList(2))
        assertFalse(AchievementRepository.shouldMigrateTrophyList(3))
        assertEquals(2, AchievementRepository.TROPHY_LIST_VERSION)
    }

    @Test
    fun migrationClearsOldNumberedProgressOnce() {
        // Claves viejas de la lista de 27 (6-20 cambiaron de trofeo
        // con la renumeración; 21 ya no existe): se borran, no se
        // reinterpretan.
        val (version, text) = AchievementRepository.applyTrophyListMigration(0, "6|1000;19|2000;21|3000")
        assertEquals(2, version)
        assertEquals("", text)
        val (version1, text1) = AchievementRepository.applyTrophyListMigration(1, "4|1000")
        assertEquals(2, version1)
        assertEquals("", text1)
    }

    @Test
    fun migrationLeavesCurrentProgressIntact() {
        val (version, text) = AchievementRepository.applyTrophyListMigration(2, "4|1000;17|2000")
        assertEquals(2, version)
        assertEquals("4|1000;17|2000", text)
    }

    @Test
    fun oldFormatSessionTextIsDetectedByAnyKeyOverTwenty() {
        // Una sola clave mayor que 20 delata la lista vieja (27):
        // el texto entero se descarta, no solo esa clave.
        assertTrue(AchievementRepository.isOldFormatTrophyText("6|1000;21|2000"))
        assertTrue(AchievementRepository.isOldFormatTrophyText("21|2000"))
        assertTrue(AchievementRepository.isOldFormatTrophyText("27|2000"))
        // Texto nuevo (claves 1..20) o vacío: no es formato viejo.
        assertFalse(AchievementRepository.isOldFormatTrophyText("4|1000;17|2000"))
        assertFalse(AchievementRepository.isOldFormatTrophyText("1|1000;20|2000"))
        assertFalse(AchievementRepository.isOldFormatTrophyText(""))
    }
}

/**
 * BUG 2026-11-08 (capturas 50386_0_z5ek / 50383_1_uzjw): el popup de
 * desbloqueo nombraba el trofeo equivocado. El usuario desbloqueó
 * Experto oculista (14) y después Aguafiestas (16); el popup dijo
 * "Experto oculista". Causa: el ViewModel escribía el trofeo en un
 * StateFlow plano desde corrutinas sin serializar ni identidad de
 * evento — el último escritor gana, así que un desbloqueo anterior
 * que termina tarde pisa el anuncio del último. El anunciador
 * asigna una secuencia por pulsación e ignora anuncios obsoletos.
 */
class TrophyUnlockAnnouncerTest {

    @Test
    fun unlock14ClearUnlock16Shows16() {
        // La secuencia exacta del reporte: 14, cerrar el popup, 16.
        val announcer = TrophyUnlockAnnouncer()
        val first = announcer.announce(14, atMillis = 1000L, seq = 1L)
        assertEquals(14, first?.trophy?.number)
        assertEquals("Experto oculista", announcer.current?.trophy?.name)
        announcer.clear()
        assertNull(announcer.current)
        val second = announcer.announce(16, atMillis = 2000L, seq = 2L)
        assertEquals(16, second?.trophy?.number)
        assertEquals("Aguafiestas", announcer.current?.trophy?.name)
        assertTrue(announcer.current?.unlocked == true)
        assertEquals(2000L, announcer.current?.unlockedAtMillis)
    }

    @Test
    fun lateEarlierUnlockDoesNotOverwriteLater() {
        // REPRODUCCIÓN DEL BUG: dos desbloqueos seguidos y el primero
        // termina DESPUÉS del segundo (corrutoras en vuelo). Con el
        // diseño anterior (slot plano, último escritor gana) el popup
        // acababa nombrando el 14; con la secuencia, se ignora el
        // anuncio tardío y el popup sigue siendo el 16.
        val announcer = TrophyUnlockAnnouncer()
        val later = announcer.announce(16, atMillis = 2000L, seq = 2L)
        assertEquals(16, later?.trophy?.number)
        val stale = announcer.announce(14, atMillis = 1000L, seq = 1L)
        assertNull(stale)
        assertEquals(16, announcer.current?.trophy?.number)
        assertEquals("Aguafiestas", announcer.current?.trophy?.name)
    }

    @Test
    fun announceBuildsUnlockedProgressFromNumber() {
        // El anuncio se construye desde TROPHIES_20 con el número y el
        // instante del desbloqueo: nunca se relee de un flujo, así que
        // no puede nombrar otro trofeo ni llegar sin desbloquear.
        val announcer = TrophyUnlockAnnouncer()
        val announced = announcer.announce(16, atMillis = 5000L, seq = 1L)
        assertNotNull(announced)
        assertEquals("Aguafiestas", announced!!.trophy.name)
        assertEquals("Interrumpe a 3 enemigos al mismo tiempo.", announced.trophy.description)
        assertTrue(announced.unlocked)
        assertEquals(5000L, announced.unlockedAtMillis)
    }

    @Test
    fun unknownNumberReturnsNull() {
        val announcer = TrophyUnlockAnnouncer()
        assertNull(announcer.announce(99, atMillis = 1000L, seq = 1L))
        assertNull(announcer.current)
    }

    @Test
    fun clearKeepsHighWaterMark() {
        // Cerrar el popup no resetea la secuencia: un anuncio tardío
        // del desbloqueo anterior sigue ignorándose después de cerrar.
        val announcer = TrophyUnlockAnnouncer()
        announcer.announce(14, atMillis = 1000L, seq = 1L)
        announcer.clear()
        assertNull(announcer.current)
        assertNull(announcer.announce(14, atMillis = 1000L, seq = 1L))
        assertNull(announcer.current)
        val next = announcer.announce(16, atMillis = 2000L, seq = 2L)
        assertEquals(16, next?.trophy?.number)
    }
}

class TouchLayoutTest {

    @Test
    fun defaultLayoutHasStickButtonsAndIgniculus() {
        val layout = defaultTouchLayout()
        // 0.2.68 (neón): 10 controles (stick, pausa, L/R, START,
        // rombo △□○✕ e Igniculus): 8 botones.
        assertEquals(10, layout.size)
        assertTrue(layout.any { it.kind == "stick" })
        assertEquals(8, layout.count { it.kind == "button" })
        assertTrue(layout.any { it.kind == "igniculus" })
        assertTrue(layout.all { it.visible })
    }

    @Test
    fun threeBuiltInProfilesExist() {
        val profiles = builtInProfiles()
        assertEquals(3, profiles.size)
        assertEquals(listOf("Por defecto G99", "Una mano", "Mando externo + táctil mínimo"), profiles.map { it.name })
        // Una mano lleva los mismos 6 controles, todos visibles;
        // mando externo oculta stick, L/R, píldora START y rombo,
        // y deja pausa e Igniculus.
        val oneHand = profiles[1]
        assertTrue(oneHand.controls.all { it.visible })
        val external = profiles[2]
        assertFalse(external.controls.first { it.id == "stick" }.visible)
        assertFalse(external.controls.first { it.id == "btn_l" }.visible)
        assertFalse(external.controls.first { it.id == "btn_r" }.visible)
        assertFalse(external.controls.first { it.id == "btn_start_pill" }.visible)
        assertFalse(external.controls.first { it.id == "btn_tri" }.visible)
        assertFalse(external.controls.first { it.id == "btn_sq" }.visible)
        assertFalse(external.controls.first { it.id == "btn_circ" }.visible)
        assertFalse(external.controls.first { it.id == "btn_cross" }.visible)
        assertTrue(external.controls.first { it.id == "igniculus" }.visible)
        assertTrue(external.controls.first { it.id == "btn_pause" }.visible)
    }

    @Test
    fun controlEncodeDecodeRoundTripKeepsAllFields() {
        // R5-A1-7: decodeControl normaliza los colores legacy
        // (#E57373 -> #A99ACD) al leer, así que el round-trip
        // identidad usa un color de la identidad actual
        // (#6578B8, azul lavanda) y el legacy se cubre aparte
        // en decodeNormalizesLegacyRedToLavender.
        // 0.2.68 (neón): el botón de prueba es la ✕ del rombo
        // (btn_cross); el round-trip cubre también su
        // widthFraction (null en los botones cuadrados).
        val original = defaultTouchLayout().first { it.id == "btn_cross" }.copy(
            colorHex = "#6578B8",
            shape = com.danielsnagustin.childoflight.launcher.data.ControlShape.SQUARE,
            vitaButton = com.danielsnagustin.childoflight.launcher.data.VitaButton.R1,
            visible = false,
            sensitivity = 1.5f,
        )
        val encoded = TouchLayoutRepository.encodeControl(original)
        val decoded = TouchLayoutRepository.decodeControl(encoded)
        assertNotNull(decoded)
        assertEquals(original, decoded)
    }

    @Test
    fun decodeNormalizesLegacyRedToLavender() {
        // R5-A1-7: el rojo viejo (#E57373) ya no es de la
        // identidad: al decodificar se normaliza a lavanda
        // (#A99ACD). El resto de campos sobrevive intacto.
        // (También cubierto en R5TouchMigrationTest.)
        val raw = "btn_a|A|button|R1|0.8|0.8|64|0.85|#E57373|SQUARE|false|ANALOG|DIRECT_TOUCH|1.5"
        val decoded = TouchLayoutRepository.decodeControl(raw)
        assertNotNull(decoded)
        assertEquals("#A99ACD", decoded!!.colorHex)
        assertEquals(com.danielsnagustin.childoflight.launcher.data.VitaButton.R1, decoded.vitaButton)
        assertEquals(com.danielsnagustin.childoflight.launcher.data.ControlShape.SQUARE, decoded.shape)
        assertFalse(decoded.visible)
        assertEquals(1.5f, decoded.sensitivity)
    }

    @Test
    fun profileEncodeDecodeRoundTrip() {
        val profile = builtInProfiles()[0]
        val encoded = TouchLayoutRepository.encodeProfile(profile)
        val decoded = TouchLayoutRepository.decodeProfile(encoded)
        assertNotNull(decoded)
        assertEquals(profile, decoded)
    }

    @Test
    fun legacy7FieldControlDecodesWithDefaults() {
        val legacy = "btn_a|A|button|0.87|0.71|64|0.85"
        val decoded = TouchLayoutRepository.decodeControl(legacy)
        assertNotNull(decoded)
        assertEquals(com.danielsnagustin.childoflight.launcher.data.VitaButton.CROSS, decoded!!.vitaButton)
        assertTrue(decoded.visible)
        assertNull(TouchLayoutRepository.decodeControl("solo|dos"))
    }
}
