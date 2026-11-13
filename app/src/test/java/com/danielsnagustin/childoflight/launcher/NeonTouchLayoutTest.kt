package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.barFractionForAspect
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.childLayout
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.controlRect
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.gameRect
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.pauseControlFor
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.pauseWingsLayout
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.sizeFractionForReferenceDp
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.toSdlButton
import com.danielsnagustin.childoflight.launcher.data.ControlProfile
import com.danielsnagustin.childoflight.launcher.data.ControlShape
import com.danielsnagustin.childoflight.launcher.data.TouchControl
import com.danielsnagustin.childoflight.launcher.data.TouchLayoutRepository
import com.danielsnagustin.childoflight.launcher.data.VitaButton
import com.danielsnagustin.childoflight.launcher.data.defaultTouchLayout
import com.danielsnagustin.childoflight.launcher.data.externalGamepadLayout
import com.danielsnagustin.childoflight.launcher.data.oneHandLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Tests 0.2.68 (neón, 2026-11-12): el default es EXACTAMENTE la
 * imagen 52710_13_ua6f.png (hecha por ChatGPT) medida por umbral de
 * brillo: 10 controles con las posiciones, tamaños y formas de la
 * imagen, sin rediseñar nada. Todo puro JVM, sin Context ni Compose.
 *
 * Medidas de la imagen (fracciones de ventana 1847x851 = 2340x1080
 * del teléfono; fracciones del juego con rect 1906x1080, izquierda
 * 217): L (-0.012, 0.094) 51 dp, ancho 0.278; R (1.016, 0.094) 51 dp,
 * ancho 0.281; pausa (0.498, 0.071) 50 dp; stick (0.035, 0.700)
 * 177 dp; START (0.264, 0.891) 38 dp, ancho 0.346; Igniculus
 * (0.781, 0.813) zona 120 dp, orbe dibujado 101 dp; △ (0.959,
 * 0.376), □ (0.890, 0.496), ○ (1.028, 0.496), ✕ (0.959, 0.613),
 * 60 dp de cristal cada uno. L, R y ○ viven sobre las barras
 * negras por diseño; los botones de cara NO se tocan (hueco de
 * 14-17 px entre vecinos, como en la imagen). Ver el KDoc de
 * defaultTouchLayout.
 */
class NeonTouchLayoutTest {

    private fun control(controls: List<TouchControl>, id: String): TouchControl =
        controls.first { it.id == id }

    private fun position(controls: List<TouchControl>, id: String): Pair<Float, Float> {
        val c = control(controls, id)
        return c.xFraction to c.yFraction
    }

    // El layout 0.2.67 (6 controles), guarda de la migración neón.
    // Literales (no derivados del layout nuevo): el test fija la
    // entrada de forma independiente. Con fracciones (formato de
    // 15 campos, 0.2.64+) o sin ellas (pre-0.2.64): la migración
    // acepta ambas formas.
    private fun layout067(withFractions: Boolean): List<TouchControl> {
        fun frac(dp: Int): Float? = if (withFractions) sizeFractionForReferenceDp(dp) else null
        return listOf(
            TouchControl("stick", "Stick", "stick", VitaButton.LEFT_STICK, 0.05f, 0.675f, 174, 0.70f, sizeFraction = frac(174)),
            TouchControl("btn_sword", "Espada", "button", VitaButton.SQUARE, 0.914f, 0.576f, 86, 0.80f, sizeFraction = frac(86)),
            TouchControl("btn_jump", "Salto", "button", VitaButton.CROSS, 1.012f, 0.752f, 87, 0.80f, sizeFraction = frac(87)),
            TouchControl("btn_start", "Start", "button", VitaButton.START, 0.50f, 0.07f, 52, 0.70f, sizeFraction = frac(52)),
            TouchControl("btn_circle", "Volver", "button", VitaButton.CIRCLE, 0.59f, 0.07f, 41, 0.70f, sizeFraction = frac(41)),
            TouchControl("igniculus", "Igniculus", "igniculus", VitaButton.NONE, 0.82f, 0.808f, 104, 0.25f, colorHex = "#7FB3D5", sizeFraction = frac(104)),
        )
    }

    @Test
    fun neonLayoutMatchesImageFractions() {
        val controls = defaultTouchLayout()
        assertEquals(10, controls.size)
        assertEquals(
            listOf("stick", "btn_pause", "btn_l", "btn_r", "btn_start_pill", "btn_tri", "btn_sq", "btn_circ", "btn_cross", "igniculus"),
            controls.map { it.id },
        )
        assertEquals(0.035f to 0.700f, position(controls, "stick"))
        assertEquals(0.498f to 0.071f, position(controls, "btn_pause"))
        assertEquals(-0.012f to 0.094f, position(controls, "btn_l"))
        assertEquals(1.016f to 0.094f, position(controls, "btn_r"))
        assertEquals(0.264f to 0.891f, position(controls, "btn_start_pill"))
        assertEquals(0.959f to 0.376f, position(controls, "btn_tri"))
        assertEquals(0.890f to 0.496f, position(controls, "btn_sq"))
        assertEquals(1.028f to 0.496f, position(controls, "btn_circ"))
        assertEquals(0.959f to 0.613f, position(controls, "btn_cross"))
        assertEquals(0.781f to 0.813f, position(controls, "igniculus"))
        assertEquals(177, control(controls, "stick").sizeDp)
        assertEquals(50, control(controls, "btn_pause").sizeDp)
        assertEquals(51, control(controls, "btn_l").sizeDp)
        assertEquals(51, control(controls, "btn_r").sizeDp)
        assertEquals(38, control(controls, "btn_start_pill").sizeDp)
        assertEquals(60, control(controls, "btn_tri").sizeDp)
        assertEquals(60, control(controls, "btn_sq").sizeDp)
        assertEquals(60, control(controls, "btn_circ").sizeDp)
        assertEquals(60, control(controls, "btn_cross").sizeDp)
        assertEquals(120, control(controls, "igniculus").sizeDp)
        // Solo las tres píldoras son PILL y llevan widthFraction.
        assertEquals(ControlShape.PILL, control(controls, "btn_l").shape)
        assertEquals(ControlShape.PILL, control(controls, "btn_r").shape)
        assertEquals(ControlShape.PILL, control(controls, "btn_start_pill").shape)
        assertEquals(0.278f, control(controls, "btn_l").widthFraction!!, 0.001f)
        assertEquals(0.281f, control(controls, "btn_r").widthFraction!!, 0.001f)
        assertEquals(0.346f, control(controls, "btn_start_pill").widthFraction!!, 0.001f)
        assertNull(control(controls, "stick").widthFraction)
        assertNull(control(controls, "btn_tri").widthFraction)
        assertNull(control(controls, "igniculus").widthFraction)
    }

    @Test
    fun neonButtonsMapToSdl() {
        // SDL posicional y hombros: los valores verificados en
        // TouchOverlayMapping (L1 9, R1 10, START 6).
        assertEquals(3, VitaButton.TRIANGLE.toSdlButton())
        assertEquals(2, VitaButton.SQUARE.toSdlButton())
        assertEquals(1, VitaButton.CIRCLE.toSdlButton())
        assertEquals(0, VitaButton.CROSS.toSdlButton())
        assertEquals(9, VitaButton.L1.toSdlButton())
        assertEquals(10, VitaButton.R1.toSdlButton())
        assertEquals(6, VitaButton.START.toSdlButton())
        assertNull(VitaButton.NONE.toSdlButton())
        assertNull(VitaButton.LEFT_STICK.toSdlButton())
        // Y el layout los lleva en los controles correctos.
        val controls = defaultTouchLayout()
        assertEquals(VitaButton.TRIANGLE, control(controls, "btn_tri").vitaButton)
        assertEquals(VitaButton.SQUARE, control(controls, "btn_sq").vitaButton)
        assertEquals(VitaButton.CIRCLE, control(controls, "btn_circ").vitaButton)
        assertEquals(VitaButton.CROSS, control(controls, "btn_cross").vitaButton)
        assertEquals(VitaButton.L1, control(controls, "btn_l").vitaButton)
        assertEquals(VitaButton.R1, control(controls, "btn_r").vitaButton)
    }

    @Test
    fun neonPillsAreWiderThanTallOnPhone() {
        // Teléfono 2340x1080, densidad 2.80: el ancho de una
        // píldora es widthFraction x alto del juego; el alto es
        // sizeDp x densidad. L 300x143, R 304x143, START 374x106.
        val controls = defaultTouchLayout()
        val l = controlRect(control(controls, "btn_l"), 2340f, 1080f, 2.80f)
        assertEquals(300.2f, l.right - l.left, 1f)
        assertEquals(142.8f, l.bottom - l.top, 1f)
        val r = controlRect(control(controls, "btn_r"), 2340f, 1080f, 2.80f)
        assertEquals(303.5f, r.right - r.left, 1f)
        assertEquals(142.8f, r.bottom - r.top, 1f)
        val start = controlRect(control(controls, "btn_start_pill"), 2340f, 1080f, 2.80f)
        assertEquals(373.7f, start.right - start.left, 1f)
        assertEquals(106.4f, start.bottom - start.top, 1f)
        // Los controles cuadrados siguen cuadrados.
        val tri = controlRect(control(controls, "btn_tri"), 2340f, 1080f, 2.80f)
        assertEquals(tri.right - tri.left, tri.bottom - tri.top, 0.01f)
    }

    @Test
    fun neonRectsStayInsideViewOnPhone() {
        // Teléfono 2340x1080: ningún rect se sale de la vista,
        // aunque L, R y ○ tengan el centro sobre la barra negra
        // (el clamp de controlRectInGameRect es a la vista entera).
        val game = gameRect(2340f, 1080f)
        for (c in defaultTouchLayout()) {
            val rect = controlRect(c, 2340f, 1080f, 2.80f)
            assertTrue("rect de ${c.id} dentro de la vista", rect.left >= -0.01f && rect.top >= -0.01f)
            assertTrue("rect de ${c.id} dentro de la vista", rect.right <= 2340f + 0.01f && rect.bottom <= 1080f + 0.01f)
        }
        val l = controlRect(control(defaultTouchLayout(), "btn_l"), 2340f, 1080f, 2.80f)
        assertEquals(-0.012f, ((l.left + l.right) / 2f - game.left) / (game.right - game.left), 0.001f)
        val r = controlRect(control(defaultTouchLayout(), "btn_r"), 2340f, 1080f, 2.80f)
        assertEquals(1.016f, ((r.left + r.right) / 2f - game.left) / (game.right - game.left), 0.001f)
        val circ = controlRect(control(defaultTouchLayout(), "btn_circ"), 2340f, 1080f, 2.80f)
        assertEquals(1.028f, ((circ.left + circ.right) / 2f - game.left) / (game.right - game.left), 0.001f)
        // El stick queda con el borde en 36 px, 181 px dentro de
        // la barra; la zona no se sale por abajo (1046.0 <= 1080).
        val stick = controlRect(control(defaultTouchLayout(), "stick"), 2340f, 1080f, 2.80f)
        assertEquals(36.0f, stick.left, 1f)
        val zone = controlRect(control(defaultTouchLayout(), "igniculus"), 2340f, 1080f, 2.80f)
        assertEquals(1046.0f, zone.bottom, 1f)
    }

    @Test
    fun neonOrbDrawnMatchesImage() {
        // 0.2.68 segunda pasada: la zona es 120 dp y el orbe
        // dibujado es 100,8 dp (0.84 x zona). El bbox de la gota en
        // la imagen (268x264 px = 121.5x119.7 dp) es la ZONA con
        // su marco y destellos, no el orbe: el cuerpo de la gota
        // mide 190-225 px (86-102 dp). Una zona de 144 dp
        // dibujaría un orbe de 121 dp, tan grande como el bbox
        // entero: 19% de más. Ver el KDoc de defaultTouchLayout.
        val zone = control(defaultTouchLayout(), "igniculus")
        assertEquals(120, zone.sizeDp)
        val orbDrawnPx = 0.84f * zone.sizeDp * 2.80f
        assertEquals(282.2f, orbDrawnPx, 1f)
        assertEquals(100.8f, orbDrawnPx / 2.80f, 0.1f)
    }

    @Test
    fun migrates067DefaultToNeonWithAndWithoutFractions() {
        // El default 0.2.67 guardado migra entero al neón, con
        // fracción (0.2.64+) o sin ella (pre-0.2.64): la
        // comparación ignora sizeFraction/widthFraction/colorHex.
        for (withFractions in listOf(false, true)) {
            val saved = ControlProfile("default-g99", "Por defecto G99", true, layout067(withFractions))
            val migrated = TouchLayoutRepository.migrateBuiltInProfile(saved)
            assertEquals(defaultTouchLayout(), migrated.controls)
            assertEquals(migrated, TouchLayoutRepository.migrateBuiltInProfile(migrated))
        }
        // La pasada neón sola también convierte el 0.2.67.
        val saved = ControlProfile("default-g99", "Por defecto G99", true, layout067(false))
        assertEquals(defaultTouchLayout(), TouchLayoutRepository.migrateBuiltInToNeonLayout(saved).controls)
    }

    @Test
    fun migrates067ExternalToNeonExternal() {
        // El externo 0.2.67 (acción oculta) espeja al neón externo:
        // la comparación ignora visible, así que los controles
        // ocultos no bloquean la migración.
        val external067 = layout067(false).map {
            if (it.id == "stick" || it.id == "btn_sword" || it.id == "btn_jump" || it.id == "btn_circle") it.copy(visible = false) else it
        }
        val saved = ControlProfile("external-gamepad", "Mando externo + táctil mínimo", true, external067)
        val migrated = TouchLayoutRepository.migrateBuiltInProfile(saved)
        assertEquals(externalGamepadLayout(), migrated.controls)
        assertTrue(control(migrated.controls, "btn_pause").visible)
        assertTrue(control(migrated.controls, "igniculus").visible)
        assertFalse(control(migrated.controls, "btn_sq").visible)
    }

    @Test
    fun edited067ProfileDoesNotMigrateToNeon() {
        // El usuario movió el stick en su layout 0.2.67: el perfil
        // es suyo y NO se convierte al neón (ni siquiera los
        // controles que siguen en fábrica: la pasada neón es de
        // perfil entero).
        val edited = layout067(false).map {
            if (it.id == "stick") it.copy(xFraction = 0.06f) else it
        }
        val saved = ControlProfile("default-g99", "Por defecto G99", true, edited)
        assertEquals(saved, TouchLayoutRepository.migrateBuiltInProfile(saved))
        assertEquals(saved, TouchLayoutRepository.migrateBuiltInToNeonLayout(saved))
    }

    @Test
    fun custom067ProfileDoesNotMigrateToNeon() {
        val custom = ControlProfile("custom-1", "Mío", false, layout067(false))
        assertEquals(custom, TouchLayoutRepository.migrateBuiltInProfile(custom))
        assertEquals(custom, TouchLayoutRepository.migrateBuiltInToNeonLayout(custom))
    }

    @Test
    fun neonLayoutRoundTripsThroughEncode() {
        // El perfil neón entero sobrevive a encode/decode: las
        // píldoras llevan widthFraction (campo 16) y L/R/○ viven
        // sobre las barras (x -0.012/1.016/1.028). Antes del arreglo
        // del clamp de decodeControl, L volvía a 0.0 y ○/R a 1.0.
        val profile = ControlProfile("default-g99", "Por defecto G99", true, defaultTouchLayout())
        val decoded = TouchLayoutRepository.decodeProfile(TouchLayoutRepository.encodeProfile(profile))
        assertNotNull(decoded)
        assertEquals(profile, decoded)
        val external = ControlProfile("external-gamepad", "Mando externo + táctil mínimo", true, externalGamepadLayout())
        val decodedExternal = TouchLayoutRepository.decodeProfile(TouchLayoutRepository.encodeProfile(external))
        assertNotNull(decodedExternal)
        assertEquals(external, decodedExternal)
    }

    @Test
    fun pillWidthFractionRoundTripsThroughEncode() {
        // La píldora L: los 16 campos sobreviven al round-trip.
        val pill = control(defaultTouchLayout(), "btn_l")
        val decoded = TouchLayoutRepository.decodeControl(TouchLayoutRepository.encodeControl(pill))
        assertNotNull(decoded)
        assertEquals(pill, decoded)
        assertEquals(0.278f, decoded!!.widthFraction!!, 0.001f)
        assertEquals(ControlShape.PILL, decoded.shape)
        // Formato de 15 campos (0.2.64-0.2.67, sin widthFraction):
        // la fracción de ancho es null = cuadrado.
        val legacy15 = "btn_l|L|button|L1|-0.012|0.094|51|0.8|#8CCBEB|PILL|true|ANALOG|DIRECT_TOUCH|1.0|${sizeFractionForReferenceDp(51)}"
        val legacy = TouchLayoutRepository.decodeControl(legacy15)
        assertNotNull(legacy)
        assertNull(legacy!!.widthFraction)
        assertEquals(sizeFractionForReferenceDp(51), legacy.sizeFraction)
        assertEquals(-0.012f, legacy.xFraction, 0.001f)
    }

    @Test
    fun barFractionForAspectMatchesPhone() {
        // El juego es 960x544: con 2340x1080 (2.167) la barra es
        // 0.114 del ancho del juego; con 960:544 exacto no hay
        // barra (0); con 16:9 casi nada (0.0037).
        assertEquals(0.114f, barFractionForAspect(2340f / 1080f), 0.001f)
        assertEquals(0f, barFractionForAspect(960f / 544f), 0.001f)
        assertEquals(0.0037f, barFractionForAspect(16f / 9f), 0.001f)
    }

    @Test
    fun pauseWingsAttachToFirstStart() {
        // Las alas se atan al PRIMER botón START: en el default
        // neón es btn_pause (la píldora START posterior dibuja su
        // etiqueta, no el icono II).
        assertEquals("btn_pause", pauseControlFor(defaultTouchLayout())!!.id)
        assertEquals("btn_pause", pauseControlFor(externalGamepadLayout())!!.id)
        // Sin START no hay alas; con solo la píldora, las alas
        // van a ella.
        assertNull(pauseControlFor(emptyList()))
        val onlyPill = listOf(control(defaultTouchLayout(), "btn_start_pill"))
        assertEquals("btn_start_pill", pauseControlFor(onlyPill)!!.id)
        // Las alas de la pausa en el teléfono: 4.7 x 140 = 658 px
        // de ancho por 1.07 x 140 = 149 px de alto; en la imagen
        // son 0.277W x 0.141H = 648 x 152 px (margen para los
        // destellos de las puntas).
        val pauseLayout = childLayout(control(defaultTouchLayout(), "btn_pause"), 2340f, 1080f, 2.80f)
        val wings = pauseWingsLayout(pauseLayout)
        assertEquals(658, wings.widthPx)
        assertEquals(149, wings.heightPx)
        assertEquals(648f, wings.widthPx.toFloat(), 12f)
        assertEquals(152f, wings.heightPx.toFloat(), 12f)
    }

    @Test
    fun oneHandLayoutIsThumbVariantNotNeon() {
        // Guarda: una mano es otro diseño (mismo set de 10, otras
        // posiciones): su rombo es el del pulgar, más abierto que
        // el de la imagen, y su stick/zona conservan sus valores
        // (174 dp, 104 dp). NO es el layout neón del default.
        val controls = oneHandLayout()
        assertEquals(10, controls.size)
        assertEquals(174, control(controls, "stick").sizeDp)
        assertEquals(104, control(controls, "igniculus").sizeDp)
        assertEquals(0.91f to 0.47f, position(controls, "btn_tri"))
        assertEquals(0.83f to 0.63f, position(controls, "btn_sq"))
        assertEquals(0.99f to 0.63f, position(controls, "btn_circ"))
        assertEquals(0.91f to 0.79f, position(controls, "btn_cross"))
        assertFalse(controls.any { it.id == "btn_sword" || it.id == "btn_jump" || it.id == "btn_circle" })
        assertTrue(position(controls, "btn_tri") != position(defaultTouchLayout(), "btn_tri"))
    }

    @Test
    fun oneHandStickAndStartPillDoNotVisuallyOverlap() {
        // 0.2.68 segunda pasada: en una mano el stick (0.05, 0.70)
        // está 28.6 px más a la derecha que en el default y la
        // píldora START (0.264, 0.891) es nueva; sus CAJAS se
        // solapan 22.6 px en X (y 90.5 px en Y). Lo DIBUJADO no se
        // toca: el stick dibuja un círculo y la píldora un estadio.
        // Del centro del stick a la esquina superior-izquierda del
        // rect de START hay 268.9 px, más que el radio del stick
        // (243.6 px): hueco conservador +25.3 px (con el estadio
        // real, no con su rect, el hueco es +46.4 px). Misma
        // familia que cuadrado–orbe: cajas que se solapan, dibujo
        // que no. No se mueve nada: el stick está donde estaba en
        // 0.2.67 y START va donde dice la imagen.
        val controls = oneHandLayout()
        val stick = controlRect(control(controls, "stick"), 2340f, 1080f, 2.80f)
        val start = controlRect(control(controls, "btn_start_pill"), 2340f, 1080f, 2.80f)
        assertEquals(22.6f, stick.right - start.left, 1f)
        assertEquals(90.5f, stick.bottom - start.top, 1f)
        val stickCx = (stick.left + stick.right) / 2f
        val stickCy = (stick.top + stick.bottom) / 2f
        val stickR = (stick.right - stick.left) / 2f
        val dx = start.left - stickCx
        val dy = start.top - stickCy
        val distToCorner = sqrt(dx * dx + dy * dy)
        assertEquals(268.9f, distToCorner, 1f)
        assertEquals(25.3f, distToCorner - stickR, 1f)
        assertTrue(distToCorner > stickR)
    }
}
