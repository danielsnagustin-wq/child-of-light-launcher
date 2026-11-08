package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.TouchLayoutRepository
import com.danielsnagustin.childoflight.launcher.data.builtInProfiles
import com.danielsnagustin.childoflight.launcher.data.defaultTouchLayout
import com.danielsnagustin.childoflight.launcher.model.TROPHIES_27
import com.danielsnagustin.childoflight.launcher.model.TrophyUnlockAnnouncer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AchievementsTest {

    @Test
    fun vitaHas27TrophiesNot20() {
        assertEquals(27, TROPHIES_27.size)
        assertEquals((1..27).toList(), TROPHIES_27.map { it.number })
    }

    @Test
    fun secretTrophiesAreTheFirstFour() {
        val secrets = TROPHIES_27.filter { it.isSecret }.map { it.number }
        assertEquals(listOf(1, 2, 3, 4), secrets)
        assertEquals("Atrapaestrellas", TROPHIES_27[0].name)
        assertEquals("Coronación", TROPHIES_27[3].name)
    }

    @Test
    fun onlyCoronacionHasDocumentedTierPlata() {
        assertEquals("Plata", TROPHIES_27.first { it.number == 4 }.tier)
        assertTrue(TROPHIES_27.filter { it.number != 4 }.all { it.tier == null })
    }

    @Test
    fun noPlatinumTrophy() {
        assertTrue(TROPHIES_27.none { it.name.contains("Platinum", ignoreCase = true) })
    }

    @Test
    fun aTravesDelCristalNeedsOnline() {
        val trophy = TROPHIES_27.first { it.number == 18 }
        assertEquals("A través del cristal", trophy.name)
        assertTrue(trophy.onlineRequired)
        assertTrue(TROPHIES_27.filter { it.number != 18 }.none { it.onlineRequired })
    }

    @Test
    fun spanishNamesAreOfficial() {
        // Nombres oficiales en español (listas PSN/Xbox del juego).
        val byNumber = TROPHIES_27.associateBy { it.number }
        assertEquals("Atrapaestrellas", byNumber.getValue(1).name)
        assertEquals("En la luna", byNumber.getValue(2).name)
        assertEquals("Amanecer", byNumber.getValue(3).name)
        assertEquals("Coronación", byNumber.getValue(4).name)
        assertEquals("Comedia", byNumber.getValue(5).name)
        assertEquals("La niña y la luciérnaga", byNumber.getValue(6).name)
        assertEquals("Captura de un Capilli", byNumber.getValue(7).name)
        assertEquals("Hermana querida", byNumber.getValue(8).name)
        assertEquals("Unión de un Bolmus", byNumber.getValue(9).name)
        assertEquals("La familia Kategida", byNumber.getValue(10).name)
        assertEquals("Promesa de los piscis", byNumber.getValue(11).name)
        assertEquals("Tragedia", byNumber.getValue(12).name)
        assertEquals("Décimo cumpleaños", byNumber.getValue(13).name)
        assertEquals("Perfeccionista", byNumber.getValue(14).name)
        assertEquals("Experto oculista", byNumber.getValue(15).name)
        assertEquals("Robando protagonismo", byNumber.getValue(16).name)
        assertEquals("Aguafiestas", byNumber.getValue(17).name)
        assertEquals("A través del cristal", byNumber.getValue(18).name)
        assertEquals("Furia alada", byNumber.getValue(19).name)
        assertEquals("Explorador", byNumber.getValue(20).name)
        assertEquals("Soneto perfecto", byNumber.getValue(21).name)
        assertEquals("Raíces", byNumber.getValue(22).name)
        assertEquals("General de la luz", byNumber.getValue(23).name)
        assertEquals("Guardianes de Lemuria", byNumber.getValue(24).name)
        assertEquals("Aurora borealis", byNumber.getValue(25).name)
        assertEquals("Cuando los cerdos vuelen", byNumber.getValue(26).name)
        assertEquals("La manzana dorada", byNumber.getValue(27).name)
        // Ningún nombre en inglés: la lista es española en todas las
        // superficies (lista, diálogo, popup, log).
        assertTrue(TROPHIES_27.none { it.name == "Starcatcher" || it.name == "Showstopper" || it.name == "Master Oculist" })
    }
}

/**
 * BUG 2026-11-08 (capturas 50386_0_z5ek / 50383_1_uzjw): el popup de
 * desbloqueo nombraba el trofeo equivocado. El usuario desbloqueó
 * Experto oculista (15) y después Aguafiestas (17); el popup dijo
 * "Experto oculista". Causa: el ViewModel escribía el trofeo en un
 * StateFlow plano desde corrutinas sin serializar ni identidad de
 * evento — el último escritor gana, así que un desbloqueo anterior
 * que termina tarde pisa el anuncio del último. El anunciador
 * asigna una secuencia por pulsación e ignora anuncios obsoletos.
 */
class TrophyUnlockAnnouncerTest {

    @Test
    fun unlock15ClearUnlock17Shows17() {
        // La secuencia exacta del reporte: 15, cerrar el popup, 17.
        val announcer = TrophyUnlockAnnouncer()
        val first = announcer.announce(15, atMillis = 1000L, seq = 1L)
        assertEquals(15, first?.trophy?.number)
        assertEquals("Experto oculista", announcer.current?.trophy?.name)
        announcer.clear()
        assertNull(announcer.current)
        val second = announcer.announce(17, atMillis = 2000L, seq = 2L)
        assertEquals(17, second?.trophy?.number)
        assertEquals("Aguafiestas", announcer.current?.trophy?.name)
        assertTrue(announcer.current?.unlocked == true)
        assertEquals(2000L, announcer.current?.unlockedAtMillis)
    }

    @Test
    fun lateEarlierUnlockDoesNotOverwriteLater() {
        // REPRODUCCIÓN DEL BUG: dos desbloqueos seguidos y el primero
        // termina DESPUÉS del segundo (corrutoras en vuelo). Con el
        // diseño anterior (slot plano, último escritor gana) el popup
        // acababa nombrando el 15; con la secuencia, se ignora el
        // anuncio tardío y el popup sigue siendo el 17.
        val announcer = TrophyUnlockAnnouncer()
        val later = announcer.announce(17, atMillis = 2000L, seq = 2L)
        assertEquals(17, later?.trophy?.number)
        val stale = announcer.announce(15, atMillis = 1000L, seq = 1L)
        assertNull(stale)
        assertEquals(17, announcer.current?.trophy?.number)
        assertEquals("Aguafiestas", announcer.current?.trophy?.name)
    }

    @Test
    fun announceBuildsUnlockedProgressFromNumber() {
        // El anuncio se construye desde TROPHIES_27 con el número y el
        // instante del desbloqueo: nunca se relee de un flujo, así que
        // no puede nombrar otro trofeo ni llegar sin desbloquear.
        val announcer = TrophyUnlockAnnouncer()
        val announced = announcer.announce(17, atMillis = 5000L, seq = 1L)
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
        announcer.announce(15, atMillis = 1000L, seq = 1L)
        announcer.clear()
        assertNull(announcer.current)
        assertNull(announcer.announce(15, atMillis = 1000L, seq = 1L))
        assertNull(announcer.current)
        val next = announcer.announce(17, atMillis = 2000L, seq = 2L)
        assertEquals(17, next?.trophy?.number)
    }
}

class TouchLayoutTest {

    @Test
    fun defaultLayoutHasStickButtonsAndIgniculus() {
        val layout = defaultTouchLayout()
        assertEquals(7, layout.size)
        assertTrue(layout.any { it.kind == "stick" })
        assertEquals(4, layout.count { it.kind == "button" && it.id.startsWith("btn_") && it.id != "btn_start" })
        assertTrue(layout.any { it.kind == "igniculus" })
        assertTrue(layout.all { it.visible })
    }

    @Test
    fun threeBuiltInProfilesExist() {
        val profiles = builtInProfiles()
        assertEquals(3, profiles.size)
        assertEquals(listOf("Por defecto G99", "Una mano", "Mando externo + táctil mínimo"), profiles.map { it.name })
        // Una mano oculta X/Y/Start; mando externo oculta casi todo salvo Igniculus y Start.
        val oneHand = profiles[1]
        assertFalse(oneHand.controls.first { it.id == "btn_x" }.visible)
        val external = profiles[2]
        assertFalse(external.controls.first { it.id == "stick" }.visible)
        assertTrue(external.controls.first { it.id == "igniculus" }.visible)
        assertTrue(external.controls.first { it.id == "btn_start" }.visible)
    }

    @Test
    fun controlEncodeDecodeRoundTripKeepsAllFields() {
        val original = defaultTouchLayout().first { it.id == "btn_a" }.copy(
            colorHex = "#E57373",
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
