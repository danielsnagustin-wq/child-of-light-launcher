package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.childLayout
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.controlRect
import com.danielsnagustin.childoflight.launcher.data.TouchControl
import com.danielsnagustin.childoflight.launcher.data.TouchLayoutRepository
import com.danielsnagustin.childoflight.launcher.data.VitaButton
import com.danielsnagustin.childoflight.launcher.data.defaultTouchLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests 0.2.63 (G): los márgenes de los hijos del overlay se
 * re-derivan desde las fracciones del control en CADA tamaño de
 * vista (childLayout, el cálculo que comparten rebuild() y
 * onLayout() de GamepadOverlayView). Todo puro JVM, sin Context
 * ni Compose.
 *
 * El bug (captura 52166): tras "Corregir audio" el stick aparecía
 * desplazado al centro-izquierda (~(0.28, 0.52) en vez de (0.05,
 * 0.675)). "Corregir audio" no escribe el layout, pero un rebuild
 * con un tamaño transitorio horneaba márgenes de ese tamaño y nada
 * los re-verificaba. Estos tests fijan que el layout para el tamaño
 * B es el que da controlRect(B) — no el del tamaño A — para que un
 * cambio de tamaño (o un rebuild con tamaño viejo) no deje nunca
 * márgenes rancios: onLayout() los corrige con este mismo cálculo.
 *
 * Valores verificados con el mismo Python que replicó la geometría
 * 0.2.63 (rect del juego 1906x1080 a 2340x1080, densidad 2.80):
 * stick 174 dp en 2340x1080 -> 487 px, izquierda 68, arriba 485;
 * salto 87 dp -> 243 px, izquierda 2024, arriba 690. A 1600x720
 * (densidad 1.75) el stick da 304 px, izquierda 75, arriba 333.
 *
 * 0.2.68 (neón): el default es la imagen 52710_13_ua6f.png (stick
 * 177 dp -> 496 px, rombo 60 dp -> 168 px, pausa 50 dp -> 140 px,
 * zona 120 dp -> 336 px, píldora L 300x143 px, START 374x106 px).
 * childLayout devuelve ADEMÁS el ancho (widthPx): en las píldoras
 * el ancho difiere del alto y el test lo comprueba por separado.
 */
class GamepadOverlayLayoutTest {

    private val stick063 = TouchControl("stick", "Stick", "stick", VitaButton.LEFT_STICK, 0.05f, 0.675f, 174, 0.70f)
    private val jump063 = TouchControl("btn_jump", "Saltar", "button", VitaButton.CROSS, 1.012f, 0.752f, 87, 0.80f)

    @Test
    fun childLayoutMatchesControlRectOnPhone() {
        // El tamaño B (teléfono real): el helper da exactamente lo
        // que da controlRect(B) convertido a LayoutParams.
        val rect = controlRect(stick063, 2340f, 1080f, 2.80f)
        val layout = childLayout(stick063, 2340f, 1080f, 2.80f)
        assertEquals(rect.width().toInt().coerceAtLeast(1), layout.sizePx)
        assertEquals(rect.left.toInt(), layout.leftMargin)
        assertEquals(rect.top.toInt(), layout.topMargin)
        // Valores ancla 0.2.63 (stick en 2340x1080 a 2.80).
        assertEquals(487, layout.sizePx)
        assertEquals(68, layout.leftMargin)
        assertEquals(485, layout.topMargin)
    }

    @Test
    fun childLayoutChangesWhenViewSizeChanges() {
        // El caso del bug: los márgenes horneados para el tamaño A
        // (1600x720 a 1.75) NO valen para el tamaño B (2340x1080 a
        // 2.80). Si onLayout() no los re-derivara, el stick quedaría
        // en (75, 333) con 304 px en vez de (68, 485) con 487 px.
        val layoutA = childLayout(stick063, 1600f, 720f, 1.75f)
        assertEquals(304, layoutA.sizePx)
        assertEquals(75, layoutA.leftMargin)
        assertEquals(333, layoutA.topMargin)
        val layoutB = childLayout(stick063, 2340f, 1080f, 2.80f)
        assertNotEquals(layoutA.sizePx, layoutB.sizePx)
        assertNotEquals(layoutA.leftMargin, layoutB.leftMargin)
        assertNotEquals(layoutA.topMargin, layoutB.topMargin)
        // El salto también cambia (y pisa la barra negra derecha en
        // B, como en el mockup): 243 px, izquierda 2024, arriba 690.
        val jumpB = childLayout(jump063, 2340f, 1080f, 2.80f)
        assertEquals(243, jumpB.sizePx)
        assertEquals(2024, jumpB.leftMargin)
        assertEquals(690, jumpB.topMargin)
    }

    @Test
    fun childLayoutMatchesControlRectForDefaultLayout() {
        // Todos los controles del default 0.2.68: el helper es
        // controlRect convertido a LayoutParams, en los dos tamaños.
        // 0.2.68 (neón): ancho y alto se comprueban POR SEPARADO:
        // en las píldoras el ancho (widthPx) difiere del alto
        // (sizePx) y el test viejo (solo ancho contra sizePx)
        // fallaría en L/R/START.
        for (control in defaultTouchLayout()) {
            for ((w, h, d) in listOf(Triple(1600f, 720f, 1.75f), Triple(2340f, 1080f, 2.80f))) {
                val rect = controlRect(control, w, h, d)
                val layout = childLayout(control, w, h, d)
                assertEquals("sizePx de ${control.id} a ${w}x$h", rect.height().toInt().coerceAtLeast(1), layout.sizePx)
                assertEquals("widthPx de ${control.id} a ${w}x$h", rect.width().toInt().coerceAtLeast(1), layout.widthPx)
                assertEquals("leftMargin de ${control.id} a ${w}x$h", rect.left.toInt(), layout.leftMargin)
                assertEquals("topMargin de ${control.id} a ${w}x$h", rect.top.toInt(), layout.topMargin)
            }
        }
    }

    // 0.2.64 (A-7): tamaños por fracción de la altura del rect del
    // juego. Los perfiles de fábrica llevan sizeFraction; en el
    // teléfono de referencia (2340x1080, 2.80, juego 1080 px de
    // alto) la fracción da EXACTAMENTE los tamaños verificados en
    // el teléfono (stick 487 px, salto 243 px), y en otra pantalla
    // el tamaño escala con el juego en vez de quedarse en dp fijos.
    @Test
    fun fractionReproducesVerifiedSizesOnReferencePhone() {
        val stick = defaultTouchLayout().first { it.id == "stick" }
        val cross = defaultTouchLayout().first { it.id == "btn_cross" }
        val sq = defaultTouchLayout().first { it.id == "btn_sq" }
        val pause = defaultTouchLayout().first { it.id == "btn_pause" }
        val circle = defaultTouchLayout().first { it.id == "btn_circ" }
        val zone = defaultTouchLayout().first { it.id == "igniculus" }
        val pillL = defaultTouchLayout().first { it.id == "btn_l" }
        val pillStart = defaultTouchLayout().first { it.id == "btn_start_pill" }
        // Anclas 0.2.68 (neón): los mismos px que con dp (177/74/
        // 50/120) y el ancho de las píldoras (L 300 px, START 374 px).
        assertEquals(495, childLayout(stick, 2340f, 1080f, 2.80f).sizePx)
        assertEquals(168, childLayout(cross, 2340f, 1080f, 2.80f).sizePx)
        assertEquals(168, childLayout(sq, 2340f, 1080f, 2.80f).sizePx)
        assertEquals(140, childLayout(pause, 2340f, 1080f, 2.80f).sizePx)
        assertEquals(168, childLayout(circle, 2340f, 1080f, 2.80f).sizePx)
        assertEquals(336, childLayout(zone, 2340f, 1080f, 2.80f).sizePx)
        assertEquals(142, childLayout(pillL, 2340f, 1080f, 2.80f).sizePx)
        assertEquals(300, childLayout(pillL, 2340f, 1080f, 2.80f).widthPx)
        assertEquals(106, childLayout(pillStart, 2340f, 1080f, 2.80f).sizePx)
        assertEquals(373, childLayout(pillStart, 2340f, 1080f, 2.80f).widthPx)
    }

    @Test
    fun fractionScalesWithGameHeightOnOtherScreens() {
        // A 1600x720 el rect del juego mide 720 px de alto: el stick
        // en fracción es 0,4589 x 720 = 330 px, NO los 304 px del
        // modo dp (177 x 1,75). El tamaño sigue al juego, no a la
        // densidad: en una pantalla más baja el stick es más
        // pequeño en px, pero ocupa la misma proporción del juego.
        val stick = defaultTouchLayout().first { it.id == "stick" }
        assertEquals(330, childLayout(stick, 1600f, 720f, 1.75f).sizePx)
        // Sin fracción (perfil guardado, control del usuario): el
        // modo dp no cambia (304 px a 1,75).
        assertEquals(304, childLayout(stick063, 1600f, 720f, 1.75f).sizePx)
        assertEquals(487, childLayout(stick063, 2340f, 1080f, 2.80f).sizePx)
    }

    @Test
    fun sizeFractionSurvivesEncodingAndLegacyDecodesToNull() {
        val stick = defaultTouchLayout().first { it.id == "stick" }
        val decoded = TouchLayoutRepository.decodeControl(TouchLayoutRepository.encodeControl(stick))
        assertNotNull(decoded)
        assertEquals(stick.sizeFraction, decoded!!.sizeFraction)
        assertEquals(stick.sizeDp, decoded.sizeDp)
        // Perfil viejo de 14 campos (sin el 15º): fracción null =
        // modo dp, sin tocar el tamaño guardado.
        val legacy14 = "stick|Stick|stick|LEFT_STICK|0.05|0.675|174|0.7|#8CCBEB|CIRCLE|true|ANALOG|DIRECT_TOUCH|1.0"
        val legacy = TouchLayoutRepository.decodeControl(legacy14)
        assertNotNull(legacy)
        assertNull(legacy!!.sizeFraction)
        assertEquals(174, legacy.sizeDp)
    }
}
