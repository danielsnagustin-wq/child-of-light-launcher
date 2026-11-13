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
 * R5-A1-1 (2026-11-09): los perfiles ya guardados con los valores
 * viejos de fábrica SÍ se normalizan al cargar
 * (migrateBuiltInFactoryValues, solo si siguen en el valor de
 * fábrica); ver TouchLayoutDiamondMigrationTest y
 * R5TouchMigrationTest. 0.2.54: el layout de fábrica ya no es el
 * rombo con stick de 104 dp, sino el layout limpio (stick de 210 dp
 * e Igniculus de 156 dp abajo a la derecha); los perfiles viejos
 * migran a él (migrateBuiltInToCleanLayout, ver
 * CleanTouchLayoutTest). 0.2.57: los tamaños de fábrica se achican
 * a la medida real del mockup (stick 186 dp, zona Igniculus
 * 115 dp, orbe dibujado 97 dp; espada/salto 82 dp, pausa 56 dp y
 * círculo 44 dp no cambian); los perfiles guardados con los
 * tamaños viejos migran a los nuevos (ver CleanTouchLayoutTest).
 * 0.2.58 (R7-P1-04/R7-P2-04): espada y salto suben a 92 dp y se
 * mueven a su x del mockup (0.914 y 1.012 guardado); ver
 * CleanTouchLayoutTest. 0.2.58 (R7-P1-05): el stick pisa la barra
 * negra (clamp relajado solo para él); ver TouchOverlayMappingTest.
 * 0.2.63 (A-1): la densidad real del teléfono es 2.80 (1080x2340),
 * no 1.75: los tamaños de fábrica se recalibran (stick 174 dp,
 * zona Igniculus 104 dp, orbe dibujado 87 dp; espada 86 dp y salto
 * 87 dp en default/externo, 77 dp en una mano; pausa 52 dp y
 * círculo 41 dp). Las posiciones no cambian.
 * 0.2.68 (neón): el default es la imagen 52710_13_ua6f.png tal
 * cual (stick 177 dp, zona Igniculus 120 dp con orbe dibujado
 * 101 dp, botones de cara 60 dp, pausa 50 dp); una mano cambia
 * a 10 controles con anclas de pulgar (stick 174 dp, zona
 * 104 dp, mismas posiciones) y el externo espeja el neón.
 * R5-A1-8: la paleta ya no lleva el morado
 * rosado #BA68C8; el test endurecido afirma que TODA la paleta
 * está en el conjunto identidad documentado abajo.
 */
class R4P2DefaultsTest {

    @Test
    fun defaultStickIsBigEnoughForTheThumb() {
        val stick = defaultTouchLayout().first { it.id == "stick" }
        // 0.2.68 (neón): 177 dp de diámetro: el círculo de cristal
        // de la imagen (0.212W x 2340 = 496 px, 45,9 % de los 1080
        // px del rectángulo de juego) a densidad real 2.80 del
        // teléfono (1080x2340): 177 x 2.80 = 496 px. La 0.2.63 lo
        // dejaba en 174 dp (487 px) y la 0.2.57 en 186 dp.
        assertEquals(177, stick.sizeDp)
        assertTrue("el stick sigue siendo usable", stick.sizeDp >= 80)
        // Pegado al borde izquierdo del juego, abajo.
        assertEquals(0.035f, stick.xFraction, 0.001f)
        assertEquals(0.700f, stick.yFraction, 0.001f)
    }

    @Test
    fun defaultIgniculusIsBottomRightWithTheActionGroup() {
        val zone = defaultTouchLayout().first { it.id == "igniculus" }
        // 0.2.68 (neón): junto al rombo, abajo a la derecha, con
        // su orbe a la medida de la imagen (zona de 120 dp, orbe
        // dibujado a 0.42 x ancho = 101 dp). La 0.2.63 lo dejaba en
        // 104 dp de zona (orbe 87 dp) y la 0.2.57 en 115 dp.
        assertEquals(0.781f, zone.xFraction, 0.001f)
        assertEquals(0.813f, zone.yFraction, 0.001f)
        assertTrue("Igniculus en la mitad inferior (y > 0.45)", zone.yFraction > 0.45f)
        assertEquals(120, zone.sizeDp)
        assertEquals(0.25f, zone.opacity, 0.001f)
    }

    @Test
    fun builtInStickSizesMatchTheirDesigns() {
        // 0.2.68 (neón): default y externo llevan el stick en
        // 177 dp (imagen); una mano es otro diseño y conserva su
        // stick en 174 dp, en su posición de fábrica.
        assertEquals(177, defaultTouchLayout().first { it.id == "stick" }.sizeDp)
        assertEquals(177, externalGamepadLayout().first { it.id == "stick" }.sizeDp)
        assertEquals(174, oneHandLayout().first { it.id == "stick" }.sizeDp)
        assertEquals(0.035f, defaultTouchLayout().first { it.id == "stick" }.xFraction, 0.001f)
        assertEquals(0.05f, oneHandLayout().first { it.id == "stick" }.xFraction, 0.001f)
        assertEquals(0.035f, externalGamepadLayout().first { it.id == "stick" }.xFraction, 0.001f)
    }

    @Test
    fun builtInIgniculusZonesAreBottomRight() {
        val oneHand = oneHandLayout().first { it.id == "igniculus" }
        // 0.2.54: una mano baja con el grupo del pulgar.
        assertEquals(0.78f, oneHand.xFraction, 0.001f)
        assertEquals(0.80f, oneHand.yFraction, 0.001f)
        assertTrue(oneHand.yFraction > 0.45f)
        val external = externalGamepadLayout().first { it.id == "igniculus" }
        // 0.2.68 (neón): el externo espeja el default neón.
        assertEquals(0.781f, external.xFraction, 0.001f)
        assertEquals(0.813f, external.yFraction, 0.001f)
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
        // R4-P2-2/R5-A1-8: la paleta entera está dentro del conjunto
        // identidad documentado (azul hielo, azul lavanda, lavanda,
        // turquesa y plata, más las variantes ya usadas azul medio
        // #7FB3D5 y blanco #FFFFFF). El morado rosado #BA68C8 quedó
        // fuera en R5; el verde #4CAF50 y el rojo #E57373 ya estaban
        // fuera desde R4. Un color nuevo fuera de este conjunto
        // rompe este test a propósito.
        val identityColors = setOf(
            "#8CCBEB", // azul hielo
            "#6578B8", // azul lavanda
            "#A99ACD", // lavanda
            "#75D6CE", // turquesa
            "#DCE8F5", // plata
            "#7FB3D5", // variante azul medio ya usada (Igniculus)
            "#FFFFFF", // blanco
        )
        val upper = CONTROL_COLORS.map { it.uppercase() }
        for (hex in upper) {
            assertTrue("color $hex fuera del conjunto identidad", hex in identityColors)
        }
        assertFalse(upper.contains("#4CAF50"))
        assertFalse(upper.contains("#E57373"))
        assertFalse("el morado rosado #BA68C8 salió de la paleta", upper.contains("#BA68C8"))
        assertTrue(upper.contains("#75D6CE"))
        assertTrue(upper.contains("#A99ACD"))
        assertTrue(upper.contains("#6578B8"))
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
