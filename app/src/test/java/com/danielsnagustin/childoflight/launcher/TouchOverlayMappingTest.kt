package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.core.FaceGlyph
import com.danielsnagustin.childoflight.launcher.core.GameRect
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.controlRect
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.controlRectInGameRect
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.dpadForStick
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.faceGlyphFor
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.gameRect
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.stickAxes
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.toSdlButton
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayVisibility
import com.danielsnagustin.childoflight.launcher.data.TouchControl
import com.danielsnagustin.childoflight.launcher.data.VitaButton
import com.danielsnagustin.childoflight.launcher.data.defaultTouchLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Tests del overlay táctil del juego embebido (2026-11-07).
 * Todo puro JVM, sin Context ni Compose: el mapeo botón Vita ->
 * botón SDL, la matemática del stick, el rectángulo de cada control
 * (idéntico a la vista previa del editor) y la decisión de mostrar
 * u ocultar el overlay según perfil, ajustes y mando conectado.
 */
class TouchOverlayMappingTest {

    @Test
    fun faceButtonsMapToSdlPositions() {
        // SDL es posicional: SOUTH = abajo (Cruz), EAST = derecha
        // (Círculo), WEST = izquierda (Cuadrado), NORTH = arriba
        // (Triángulo). El núcleo traduce con los binds identidad.
        assertEquals(0, VitaButton.CROSS.toSdlButton())
        assertEquals(1, VitaButton.CIRCLE.toSdlButton())
        assertEquals(2, VitaButton.SQUARE.toSdlButton())
        assertEquals(3, VitaButton.TRIANGLE.toSdlButton())
    }

    @Test
    fun shoulderStartSelectAndDpadMap() {
        assertEquals(4, VitaButton.SELECT.toSdlButton())
        assertEquals(6, VitaButton.START.toSdlButton())
        assertEquals(9, VitaButton.L1.toSdlButton())
        assertEquals(10, VitaButton.R1.toSdlButton())
        assertEquals(7, VitaButton.L3.toSdlButton())
        assertEquals(8, VitaButton.R3.toSdlButton())
        assertEquals(11, VitaButton.DPAD_UP.toSdlButton())
        assertEquals(12, VitaButton.DPAD_DOWN.toSdlButton())
        assertEquals(13, VitaButton.DPAD_LEFT.toSdlButton())
        assertEquals(14, VitaButton.DPAD_RIGHT.toSdlButton())
    }

    @Test
    fun unmappedButtonsReturnNull() {
        // NONE y los sticks como *botón* no tienen botón SDL: son ejes.
        // Un botón así no se pulsa en el juego; el toque baja a SDL.
        assertNull(VitaButton.NONE.toSdlButton())
        assertNull(VitaButton.LEFT_STICK.toSdlButton())
        assertNull(VitaButton.RIGHT_STICK.toSdlButton())
    }

    @Test
    fun stickAxesCenterIsZeroAndFullDeflectionIsMax() {
        assertEquals(0.toShort() to 0.toShort(), stickAxes(0f, 0f))
        assertEquals(32767.toShort() to 0.toShort(), stickAxes(1f, 0f))
        assertEquals(0.toShort() to (-32767).toShort(), stickAxes(0f, -1f))
        assertEquals(0.toShort() to 32767.toShort(), stickAxes(0f, 1f))
    }

    @Test
    fun stickDeadZoneSwallowsSmallDrift() {
        assertEquals(0.toShort() to 0.toShort(), stickAxes(0.05f, 0f))
        assertEquals(0.toShort() to 0.toShort(), stickAxes(0f, -0.07f))
        // Justo fuera de la zona muerta ya hay salida (pequeña).
        val (x, _) = stickAxes(0.2f, 0f)
        assertTrue(x > 0)
    }

    @Test
    fun stickDiagonalDoesNotExceedOneAxis() {
        // Diagonal a tope: cada eje ~32767 / sqrt(2), nunca 32767.
        val (x, y) = stickAxes(1f, 1f)
        assertTrue(abs(x - 23169) <= 1)
        assertTrue(abs(y - 23169) <= 1)
    }

    @Test
    fun stickDpadDirections() {
        assertEquals(setOf(VitaButton.DPAD_UP), dpadForStick(0f, -0.8f))
        assertEquals(setOf(VitaButton.DPAD_DOWN, VitaButton.DPAD_RIGHT), dpadForStick(0.8f, 0.8f))
        assertEquals(emptySet<VitaButton>(), dpadForStick(0.2f, 0.2f))
    }

    @Test
    fun faceGlyphsAreDistinctAndDpadHasNone() {
        // R4-P0-2: cada botón de cara tiene su glifo propio; CROSS
        // ya no comparte icono con DPAD_UP (que no tiene glifo: se
        // dibuja con flecha). START tampoco tiene glifo (pausa).
        assertEquals(FaceGlyph.CROSS, faceGlyphFor(VitaButton.CROSS))
        assertEquals(FaceGlyph.CIRCLE, faceGlyphFor(VitaButton.CIRCLE))
        assertEquals(FaceGlyph.SQUARE, faceGlyphFor(VitaButton.SQUARE))
        assertEquals(FaceGlyph.TRIANGLE, faceGlyphFor(VitaButton.TRIANGLE))
        assertNull(faceGlyphFor(VitaButton.DPAD_UP))
        assertNull(faceGlyphFor(VitaButton.DPAD_DOWN))
        assertNull(faceGlyphFor(VitaButton.START))
        assertNull(faceGlyphFor(VitaButton.L1))
        assertNull(faceGlyphFor(VitaButton.NONE))
        val glyphs = listOf(
            faceGlyphFor(VitaButton.CROSS),
            faceGlyphFor(VitaButton.CIRCLE),
            faceGlyphFor(VitaButton.SQUARE),
            faceGlyphFor(VitaButton.TRIANGLE),
        )
        assertEquals(4, glyphs.distinct().size)
    }

    @Test
    fun gameRectMatchesMeasuredPillarbox() {
        // R4-P1-1: captura 2048x945 del teléfono: el juego ocupa
        // x 190-1857 (1667 px, aspecto 960x544 exacto), centrado.
        val rect = gameRect(2048f, 945f)
        assertEquals(190.18f, rect.left, 0.5f)
        assertEquals(0f, rect.top, 0.01f)
        assertEquals(1667.65f, rect.width(), 0.5f)
        assertEquals(945f, rect.height(), 0.01f)
        assertEquals(1857.82f, rect.right, 0.5f)
    }

    @Test
    fun gameRectLetterboxesTallView() {
        // Vista vertical 1080x2400: manda el ancho; el juego es una
        // franja 1080x612 centrada verticalmente.
        val rect = gameRect(1080f, 2400f)
        assertEquals(0f, rect.left, 0.01f)
        assertEquals(894f, rect.top, 0.01f)
        assertEquals(1080f, rect.width(), 0.01f)
        assertEquals(612f, rect.height(), 0.01f)
    }

    @Test
    fun gameRectIsWholeViewAtExactAspect() {
        // Vista exactamente 960:544 (1920x1088): sin barras, el rect
        // del juego es la vista entera y controlRect coincide con la
        // fórmula antigua de fracción de ventana.
        val rect = gameRect(1920f, 1088f)
        assertEquals(0f, rect.left, 0.01f)
        assertEquals(0f, rect.top, 0.01f)
        assertEquals(1920f, rect.width(), 0.01f)
        assertEquals(1088f, rect.height(), 0.01f)
    }

    @Test
    fun controlRectMapsFractionsInsideGameRect() {
        // R4-P1-1: CROSS en (0.80, 0.80) en la captura 2048x945 ya
        // no se centra en 0.80 x 2048 = 1638 (0.959 del juego, con
        // parte del botón sobre la barra negra), sino en 0.80 del
        // rect del juego, entero dentro de él.
        val density = 2.5f
        val control = TouchControl("a", "A", "button", VitaButton.CROSS, 0.80f, 0.80f, 64, 0.85f)
        val rect = controlRect(control, 2048f, 945f, density)
        assertEquals(1444.29f, rect.left, 1f)
        assertEquals(676f, rect.top, 1f)
        assertEquals(160f, rect.width(), 0.01f)
        assertEquals(160f, rect.height(), 0.01f)
        val game = gameRect(2048f, 945f)
        assertTrue("botón entero dentro del juego", rect.left >= game.left && rect.right <= game.right + 0.01f)
        assertTrue("centro a la izquierda del mapeo viejo de ventana", rect.left + 80f < 0.80f * 2048f)
    }

    @Test
    fun controlRectInGameRectMapsStickInsidePillarbox() {
        // El stick (0.18, 0.72) también se mide desde el rect del
        // juego: en 2048x945 su centro pasa de 0.18 x 2048 = 369
        // (0.109 del juego) a 190 + 0.18 x 1668 = 490.
        val game = GameRect(190f, 0f, 1858f, 945f)
        val stick = TouchControl("s", "Stick", "stick", VitaButton.LEFT_STICK, 0.18f, 0.72f, 140, 0.70f)
        val rect = controlRectInGameRect(stick, game, 2.5f)
        assertEquals(315.24f, rect.left, 1f)
        assertEquals(505.4f, rect.top, 1f)
        assertEquals(350f, rect.width(), 0.01f)
    }

    @Test
    fun controlRectMatchesEditorPreview() {
        // La fórmula del editor (TouchLayoutScreen.kt): offset =
        // ((fracción * tamañoVista) - tamañoPx/2).toInt(), limitado a
        // [0, tamañoVista - tamañoPx]. El overlay del juego usa la
        // misma SOLO cuando la vista es exactamente 960:544 (aquí
        // 1920x1088, donde el rect del juego ES la vista): en ese
        // caso lo que se ve en el editor es lo que aparece en el
        // juego. Con otra vista, las fracciones se miden dentro del
        // rect del juego (R4-P1-1) y la previa solo coincide si es
        // 16:9 (R4-P1-4).
        val density = 2.5f
        val viewW = 1920f
        val viewH = 1088f
        val cases = listOf(
            TouchControl("a", "A", "button", VitaButton.CROSS, 0.80f, 0.80f, 64, 0.85f),
            TouchControl("s", "Stick", "stick", VitaButton.LEFT_STICK, 0.18f, 0.72f, 140, 0.70f),
            TouchControl("edge", "Borde", "button", VitaButton.CIRCLE, 0.0f, 1.0f, 64, 0.8f),
            TouchControl("big", "Grande", "igniculus", VitaButton.NONE, 0.5f, 0.35f, 220, 0.25f),
        )
        for (control in cases) {
            val rect = controlRect(control, viewW, viewH, density)
            val sizePx = control.sizeDp * density
            val expectedLeft = ((control.xFraction * viewW) - sizePx / 2).toInt()
                .coerceIn(0, (viewW - sizePx).toInt().coerceAtLeast(0))
            val expectedTop = ((control.yFraction * viewH) - sizePx / 2).toInt()
                .coerceIn(0, (viewH - sizePx).toInt().coerceAtLeast(0))
            assertEquals("left de ${control.id}", expectedLeft, rect.left.toInt())
            assertEquals("top de ${control.id}", expectedTop, rect.top.toInt())
            assertEquals("ancho de ${control.id}", sizePx, rect.width(), 0.01f)
            assertTrue("rect dentro de la vista: ${control.id}", rect.left >= 0 && rect.top >= 0)
            assertTrue(rect.right <= viewW + 0.01f && rect.bottom <= viewH + 0.01f)
        }
    }

    @Test
    fun visibleControlsExcludesHidden() {
        val controls = defaultTouchLayout()
        val visible = TouchOverlayMapping.visibleControls(controls)
        assertEquals(controls.size, visible.size)
        val hidden = controls.map { if (it.id == "btn_a") it.copy(visible = false) else it }
        assertEquals(controls.size - 1, TouchOverlayMapping.visibleControls(hidden).size)
        assertTrue(TouchOverlayMapping.visibleControls(hidden).none { it.id == "btn_a" })
        // Perfil con todo borrado/oculto: no hay nada que mostrar.
        assertTrue(TouchOverlayMapping.visibleControls(emptyList()).isEmpty())
    }

    @Test
    fun shouldShowRespectsMasterSwitchAndProfile() {
        val controls = defaultTouchLayout()
        assertTrue(
            TouchOverlayVisibility.shouldShow(true, "default-g99", emptySet(), false, false, controls),
        )
        assertFalse(
            TouchOverlayVisibility.shouldShow(false, "default-g99", emptySet(), false, false, controls),
        )
        assertFalse(
            TouchOverlayVisibility.shouldShow(true, "default-g99", setOf("default-g99"), false, false, controls),
        )
        // Otro perfil no afectado por el oculto.
        assertTrue(
            TouchOverlayVisibility.shouldShow(true, "one-hand", setOf("default-g99"), false, false, controls),
        )
    }

    @Test
    fun shouldShowHidesWithGamepadWhenSettingIsOn() {
        val controls = defaultTouchLayout()
        assertFalse(
            TouchOverlayVisibility.shouldShow(true, "default-g99", emptySet(), true, true, controls),
        )
        assertTrue(
            TouchOverlayVisibility.shouldShow(true, "default-g99", emptySet(), false, true, controls),
        )
        assertTrue(
            TouchOverlayVisibility.shouldShow(true, "default-g99", emptySet(), true, false, controls),
        )
    }

    @Test
    fun shouldShowHidesWhenNoVisibleControls() {
        val controls = defaultTouchLayout().map { it.copy(visible = false) }
        assertFalse(
            TouchOverlayVisibility.shouldShow(true, "default-g99", emptySet(), false, false, controls),
        )
        assertFalse(
            TouchOverlayVisibility.shouldShow(true, "default-g99", emptySet(), false, false, emptyList()),
        )
    }
}
