package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.builtInProfiles
import com.danielsnagustin.childoflight.launcher.data.defaultTouchLayout
import com.danielsnagustin.childoflight.launcher.data.externalGamepadLayout
import com.danielsnagustin.childoflight.launcher.data.oneHandLayout
import com.danielsnagustin.childoflight.launcher.ui.CONTROL_COLORS
import com.danielsnagustin.childoflight.launcher.ui.parseHexColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests R4-P2-1 y R4-P2-2 (2026-11-09): defaults de fábrica del
 * overlay (stick más pequeño, Igniculus bajo el logo) y paleta del
 * editor sin verde ni rojo. Todo puro JVM, sin Context ni Compose:
 * los layouts de fábrica son funciones puras y la paleta es una
 * lista de hex que se verifica con parseHexColor (Kotlin puro, no
 * Color.parseColor, que es un stub en tests JVM).
 *
 * Solo el default de fábrica cambia: los perfiles ya guardados no
 * se tocan (ver TouchLayoutDiamondMigrationTest: la migración del
 * rombo conserva el stick y la Igniculus guardados).
 */
class R4P2DefaultsTest {

    @Test
    fun defaultStickIsSmallerThanBefore() {
        val stick = defaultTouchLayout().first { it.id == "stick" }
        // Antes 140 dp: ocupaba el tercio inferior izquierdo.
        assertEquals(104, stick.sizeDp)
        assertTrue("el stick sigue siendo usable", stick.sizeDp >= 80)
        // Misma posición, solo más pequeño.
        assertEquals(0.18f, stick.xFraction, 0.001f)
        assertEquals(0.72f, stick.yFraction, 0.001f)
    }

    @Test
    fun defaultIgniculusIsBelowTheTitleLogo() {
        val zone = defaultTouchLayout().first { it.id == "igniculus" }
        // Antes y 0.35: el orbe flotaba sobre la "C" de
        // "CHILD OF LIGHT" tapando el logo. El centro queda ahora
        // en la mitad inferior, entre el stick y el rombo de cara.
        assertEquals(0.50f, zone.xFraction, 0.001f)
        assertEquals(0.55f, zone.yFraction, 0.001f)
        assertTrue("Igniculus bajo la banda del logo (y > 0.45)", zone.yFraction > 0.45f)
        // Mismo tamaño y opacidad: solo baja, no se redibuja.
        assertEquals(220, zone.sizeDp)
        assertEquals(0.25f, zone.opacity, 0.001f)
    }

    @Test
    fun builtInIgniculusZonesAreBelowTheTitleLogo() {
        val oneHand = oneHandLayout().first { it.id == "igniculus" }
        assertEquals(0.55f, oneHand.xFraction, 0.001f)
        assertEquals(0.50f, oneHand.yFraction, 0.001f)
        assertTrue(oneHand.yFraction > 0.45f)
        val external = externalGamepadLayout().first { it.id == "igniculus" }
        assertEquals(0.50f, external.xFraction, 0.001f)
        assertEquals(0.55f, external.yFraction, 0.001f)
        assertTrue(external.yFraction > 0.45f)
    }

    @Test
    fun builtInControlsUseNoGreenOrRed() {
        // R4-P2-2: ningún default de control usa el verde ni el rojo
        // que se quitaron de la paleta (verificado también por grep
        // en main: solo la paleta los tenía).
        val forbidden = setOf("#4CAF50", "#E57373")
        for (profile in builtInProfiles()) {
            for (control in profile.controls) {
                assertFalse(
                    "control ${control.id} del perfil ${profile.id} usa color fuera de identidad: ${control.colorHex}",
                    control.colorHex.uppercase() in forbidden,
                )
            }
        }
    }

    @Test
    fun editorPaletteHasNoGreenOrRed() {
        // R4-P2-2: fuera de la paleta el verde y el rojo; dentro,
        // turquesa #75D6CE y lavanda #A99ACD de la identidad.
        val upper = CONTROL_COLORS.map { it.uppercase() }
        assertFalse(upper.contains("#4CAF50"))
        assertFalse(upper.contains("#E57373"))
        assertTrue(upper.contains("#75D6CE"))
        assertTrue(upper.contains("#A99ACD"))
        assertEquals("la paleta sigue teniendo 6 colores", 6, CONTROL_COLORS.size)
        assertEquals("sin duplicados en la paleta", CONTROL_COLORS.size, upper.distinct().size)
    }

    @Test
    fun editorPaletteParsesWithPureKotlinParser() {
        // Cada color de la paleta se puede parsear sin
        // android.graphics.Color (stub en JVM).
        for (hex in CONTROL_COLORS) {
            assertNotNull("paleta no parseable: $hex", parseHexColor(hex))
        }
    }
}
