package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.ControlProfile
import com.danielsnagustin.childoflight.launcher.data.TouchLayoutRepository
import com.danielsnagustin.childoflight.launcher.data.VitaButton
import com.danielsnagustin.childoflight.launcher.data.defaultTouchLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de la auditoría del panel táctil (2026-11-07): perfil vacío,
 * decodificación tolerante de 14 campos, normalización al leer,
 * dedupe de ids, clamp del centro al arrastrar y saneado de nombres.
 * Todo puro JVM, sin Context ni Compose.
 */
class TouchLayoutAuditFixesTest {

    @Test
    fun emptyProfileRoundTripKeepsProfile() {
        // B10: borrar todos los botones es válido (jugar solo con
        // mando físico). Antes decodeProfile devolvía null y el
        // perfil desaparecía, resucitando los predefinidos.
        val profile = ControlProfile("custom-1", "Solo mando", false, emptyList())
        val decoded = TouchLayoutRepository.decodeProfile(TouchLayoutRepository.encodeProfile(profile))
        assertNotNull(decoded)
        assertEquals(profile, decoded)
        val profiles = TouchLayoutRepository.decodeProfiles(TouchLayoutRepository.encodeProfile(profile))
        assertNotNull(profiles)
        assertEquals(1, profiles!!.size)
        assertTrue(profiles[0].controls.isEmpty())
    }

    @Test
    fun emptyProfileTextDecodes() {
        val decoded = TouchLayoutRepository.decodeProfile("custom-1|Solo mando|false\n")
        assertNotNull(decoded)
        assertTrue(decoded!!.controls.isEmpty())
        // Texto sin cabecera válida sigue siendo inválido.
        assertNull(TouchLayoutRepository.decodeProfile("basura sin separadores"))
        assertNull(TouchLayoutRepository.decodeProfile("a|b"))
    }

    @Test
    fun corrupt14FieldControlKeepsControlWithDefaults() {
        // B8: un solo campo corrupto en el formato de 14 campos ya no
        // descarta el control entero.
        val badEnum = "btn_a|A|button|NO_EXISTE|0.87|0.71|64|0.85|#8CCBEB|CIRCLE|true|ANALOG|DIRECT_TOUCH|1.0"
        val decodedEnum = TouchLayoutRepository.decodeControl(badEnum)
        assertNotNull(decodedEnum)
        assertEquals(VitaButton.NONE, decodedEnum!!.vitaButton)
        assertEquals(0.87f, decodedEnum.xFraction, 0.001f)

        val badNumber = "btn_a|A|button|CROSS|xx|0.71|zz|0.85|#8CCBEB|CIRCLE|true|ANALOG|DIRECT_TOUCH|1.0"
        val decodedNumber = TouchLayoutRepository.decodeControl(badNumber)
        assertNotNull(decodedNumber)
        assertEquals(0.5f, decodedNumber!!.xFraction, 0.001f)
        assertEquals(64, decodedNumber.sizeDp)
    }

    @Test
    fun decodeNormalizesOutOfRangeValues() {
        // B9: fracción > 1, tamaño negativo, opacidad > 1 y
        // sensibilidad fuera de rango se fijan al leer, no sacan el
        // botón de la pantalla ni rompen el layout.
        val raw = "btn_a|A|button|CROSS|1.5|-0.2|-10|2.0|#8CCBEB|CIRCLE|true|ANALOG|DIRECT_TOUCH|9.9"
        val decoded = TouchLayoutRepository.decodeControl(raw)
        assertNotNull(decoded)
        // 0.2.68 (neón): la x corrupta (1.5) cae al tope de barra
        // 1.1, no a 1.0: L (-0.012), ○ (1.028) y R (1.016) viven
        // sobre las barras y el tope es simétrico [-0.1, 1.1].
        assertEquals(1.1f, decoded!!.xFraction, 0.001f)
        assertEquals(0.0f, decoded.yFraction, 0.001f)
        assertEquals(16, decoded.sizeDp)
        assertEquals(1.0f, decoded.opacity, 0.001f)
        assertEquals(2.0f, decoded.sensitivity, 0.001f)
    }

    @Test
    fun decodeTreatsNaNAsMissing() {
        val raw = "btn_a|A|button|CROSS|NaN|0.71|64|0.85|#8CCBEB|CIRCLE|true|ANALOG|DIRECT_TOUCH|1.0"
        val decoded = TouchLayoutRepository.decodeControl(raw)
        assertNotNull(decoded)
        assertEquals(0.5f, decoded!!.xFraction, 0.001f)
    }

    @Test
    fun decodeProfileDedupesDuplicateControlIds() {
        // Dos controles con el mismo id: borrar/editar uno afectaba
        // a los dos. Al leer se queda el primero.
        // 0.2.68 (neón): el botón de prueba es la ✕ del rombo.
        val first = defaultTouchLayout().first { it.id == "btn_cross" }
        val second = first.copy(label = "Duplicado")
        val profile = ControlProfile("p1", "Test", false, listOf(first, second))
        val decoded = TouchLayoutRepository.decodeProfile(TouchLayoutRepository.encodeProfile(profile))
        assertNotNull(decoded)
        assertEquals(1, decoded!!.controls.size)
        assertEquals("Cruz", decoded.controls[0].label)
    }

    @Test
    fun clampCenterFractionKeepsWholeControlInside() {
        // B7: el centro queda en [half, 1-half]; si el control es
        // mayor que la pantalla, el centro es 0.5.
        assertEquals(0.1f, TouchLayoutRepository.clampCenterFraction(0f, 0.1f), 0.001f)
        assertEquals(0.9f, TouchLayoutRepository.clampCenterFraction(1f, 0.1f), 0.001f)
        assertEquals(0.3f, TouchLayoutRepository.clampCenterFraction(0.3f, 0.1f), 0.001f)
        assertEquals(0.2f, TouchLayoutRepository.clampCenterFraction(-1f, 0.2f), 0.001f)
        assertEquals(0.5f, TouchLayoutRepository.clampCenterFraction(0f, 0.6f), 0.001f)
        assertEquals(0.5f, TouchLayoutRepository.clampCenterFraction(1f, 0.6f), 0.001f)
    }

    @Test
    fun clampCenterFractionCrossKeepsNeonPosition() {
        // 0.2.68 (neón): la cruz (✕) admite x hasta 1.1 en el
        // editor (arrastre y slider X): su centro de fábrica es
        // 0.959 (imagen, sobre la barra negra derecha en el
        // rombo). Sin maxFraction el editor la devolvería a
        // 1 - half y perdería el diseño.
        val half = 0.0441f // 60 dp en el teléfono (84.0 px / 1905.9 px)
        assertEquals(0.959f, TouchLayoutRepository.clampCenterFraction(0.959f, half, TouchLayoutRepository.JUMP_MAX_X_FRACTION), 0.001f)
        assertEquals(1.1f, TouchLayoutRepository.clampCenterFraction(1.5f, half, TouchLayoutRepository.JUMP_MAX_X_FRACTION), 0.001f)
        assertEquals(0.9559f, TouchLayoutRepository.clampCenterFraction(0.959f, half), 0.001f)
        // El round-trip de fábrica sobrevive a encode/decode: las
        // posiciones sobre las barras no se deshacen al releer el
        // perfil guardado. 0.2.68: el clamp de decodeControl era
        // [0, 1] salvo btn_jump y movía L a 0.0 y ○/R a 1.0 en cada
        // carga; ahora el tope es simétrico [-0.1, 1.1].
        for ((id, x) in listOf("btn_cross" to 0.959f, "btn_circ" to 1.028f, "btn_l" to -0.012f, "btn_r" to 1.016f)) {
            val control = defaultTouchLayout().first { it.id == id }
            val decoded = TouchLayoutRepository.decodeControl(TouchLayoutRepository.encodeControl(control))
            assertNotNull(decoded)
            assertEquals(x, decoded!!.xFraction, 0.001f)
        }
    }

    @Test
    fun sanitizeNameStripsSlashes() {
        // Un '/' en el nombre del perfil rompía el nombre de archivo
        // de exportación ("perfil-a/b.txt").
        assertEquals("a b c d", TouchLayoutRepository.sanitizeName("a/b\\c|d"))
        assertEquals("Mi perfil", TouchLayoutRepository.sanitizeName("Mi perfil"))
    }

    @Test
    fun defaultLayoutStillRoundTripsExactly() {
        // La normalización no debe alterar los valores válidos.
        val profile = ControlProfile("default-g99", "Por defecto G99", true, defaultTouchLayout())
        val decoded = TouchLayoutRepository.decodeProfile(TouchLayoutRepository.encodeProfile(profile))
        assertNotNull(decoded)
        assertEquals(profile, decoded)
    }
}
