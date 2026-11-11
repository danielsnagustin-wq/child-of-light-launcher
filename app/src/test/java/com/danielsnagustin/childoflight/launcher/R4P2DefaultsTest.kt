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
 * R5-A1-8: la paleta ya no lleva el morado
 * rosado #BA68C8; el test endurecido afirma que TODA la paleta
 * está en el conjunto identidad documentado abajo.
 */
class R4P2DefaultsTest {

    @Test
    fun defaultStickIsBigEnoughForTheThumb() {
        val stick = defaultTouchLayout().first { it.id == "stick" }
        // 0.2.63 (A-1): 174 dp de diámetro: el círculo de cristal
        // del mockup (385 px, 45 % de la altura del mockup) a
        // densidad real 2.80 del teléfono (1080x2340): 174 x 2.80 =
        // 487 px = 45 % de los 1080 px del rectángulo de juego. La
        // 0.2.57 usaba densidad 1.75 (la del Galaxy A07, no la de
        // este teléfono) y salía a 186 dp, un 7 % demasiado grande.
        assertEquals(174, stick.sizeDp)
        assertTrue("el stick sigue siendo usable", stick.sizeDp >= 80)
        // Pegado al borde izquierdo del juego, abajo.
        assertEquals(0.05f, stick.xFraction, 0.001f)
        assertEquals(0.675f, stick.yFraction, 0.001f)
    }

    @Test
    fun defaultIgniculusIsBottomRightWithTheActionGroup() {
        val zone = defaultTouchLayout().first { it.id == "igniculus" }
        // 0.2.63 (A-1): junto a la espada y el salto, abajo a la
        // derecha, con su orbe a la medida del mockup (zona de
        // 104 dp, orbe dibujado a 0.42 x ancho = 87 dp). La 0.2.57
        // lo dejaba en 115 dp (densidad 1.75, la del Galaxy A07) y
        // la 0.2.54 en 156 dp de zona (orbe 131 dp), gigantes en el
        // teléfono.
        assertEquals(0.82f, zone.xFraction, 0.001f)
        assertEquals(0.808f, zone.yFraction, 0.001f)
        assertTrue("Igniculus en la mitad inferior (y > 0.45)", zone.yFraction > 0.45f)
        assertEquals(104, zone.sizeDp)
        assertEquals(0.25f, zone.opacity, 0.001f)
    }

    @Test
    fun builtInSticksAreUnifiedAt174Dp() {
        // 0.2.63 (A-1): los tres perfiles llevan el stick en 174 dp,
        // en su posición de fábrica.
        for (profile in builtInProfiles()) {
            val stick = profile.controls.first { it.id == "stick" }
            assertEquals("stick del perfil ${profile.id}", 174, stick.sizeDp)
        }
        assertEquals(0.05f, defaultTouchLayout().first { it.id == "stick" }.xFraction, 0.001f)
        assertEquals(0.05f, oneHandLayout().first { it.id == "stick" }.xFraction, 0.001f)
        assertEquals(0.05f, externalGamepadLayout().first { it.id == "stick" }.xFraction, 0.001f)
    }

    @Test
    fun builtInIgniculusZonesAreBottomRight() {
        val oneHand = oneHandLayout().first { it.id == "igniculus" }
        // 0.2.54: una mano baja con el grupo del pulgar.
        assertEquals(0.78f, oneHand.xFraction, 0.001f)
        assertEquals(0.80f, oneHand.yFraction, 0.001f)
        assertTrue(oneHand.yFraction > 0.45f)
        val external = externalGamepadLayout().first { it.id == "igniculus" }
        assertEquals(0.82f, external.xFraction, 0.001f)
        assertEquals(0.808f, external.yFraction, 0.001f)
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
