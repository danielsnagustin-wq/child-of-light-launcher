package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.TouchLayoutRepository
import com.danielsnagustin.childoflight.launcher.data.builtInProfiles
import com.danielsnagustin.childoflight.launcher.data.defaultTouchLayout
import com.danielsnagustin.childoflight.launcher.model.TROPHIES_27
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
        assertEquals("Starcatcher", TROPHIES_27[0].name)
        assertEquals("Coronation", TROPHIES_27[3].name)
    }

    @Test
    fun onlyCoronationHasDocumentedTierSilver() {
        assertEquals("Silver", TROPHIES_27.first { it.number == 4 }.tier)
        assertTrue(TROPHIES_27.filter { it.number != 4 }.all { it.tier == null })
    }

    @Test
    fun noPlatinumTrophy() {
        assertTrue(TROPHIES_27.none { it.name.contains("Platinum", ignoreCase = true) })
    }

    @Test
    fun throughTheLookingGlassNeedsOnline() {
        val trophy = TROPHIES_27.first { it.number == 18 }
        assertEquals("Through the Looking Glass", trophy.name)
        assertTrue(trophy.onlineRequired)
        assertTrue(TROPHIES_27.filter { it.number != 18 }.none { it.onlineRequired })
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
