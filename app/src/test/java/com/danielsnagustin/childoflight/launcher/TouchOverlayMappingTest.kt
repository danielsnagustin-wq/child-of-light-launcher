package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.core.FaceGlyph
import com.danielsnagustin.childoflight.launcher.core.GameRect
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.controlRect
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.controlRectInGameRect
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.dpadForStick
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.faceGlyphFor
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.gameRect
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.isTriggerSdlButton
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.stickAxes
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.toSdlButton
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayVisibility
import com.danielsnagustin.childoflight.launcher.data.TouchControl
import com.danielsnagustin.childoflight.launcher.data.VitaButton
import com.danielsnagustin.childoflight.launcher.data.builtInProfiles
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
    fun triggersMapToNegativeAxisCodes() {
        // R4-P2-11: L2/R2 no son botones SDL: upstream los manda con
        // botón negativo (input_overlay.cpp:104-108, button < 0 ->
        // SDL_SetJoystickVirtualAxis(-button, ...)). -4 = eje
        // LEFT_TRIGGER (L2), -5 = eje RIGHT_TRIGGER (R2); los binds
        // de ejes por defecto son la identidad (app.cpp:105-112) y
        // el núcleo los lee como trigger > 0x3FFF (ctrl.cpp:
        // apply_controller, vía ext).
        assertEquals(-TouchOverlayMapping.SDL_AXIS_LEFT_TRIGGER, VitaButton.L2.toSdlButton())
        assertEquals(-TouchOverlayMapping.SDL_AXIS_RIGHT_TRIGGER, VitaButton.R2.toSdlButton())
        assertEquals(-4, VitaButton.L2.toSdlButton())
        assertEquals(-5, VitaButton.R2.toSdlButton())
        assertTrue(isTriggerSdlButton(VitaButton.L2.toSdlButton()!!))
        assertTrue(isTriggerSdlButton(VitaButton.R2.toSdlButton()!!))
        // Los botones reales (0..14) no son gatillos codificados.
        assertFalse(isTriggerSdlButton(VitaButton.CROSS.toSdlButton()!!))
        assertFalse(isTriggerSdlButton(VitaButton.GUIDE.toSdlButton()!!))
        assertFalse(isTriggerSdlButton(0))
    }

    @Test
    fun guideMapsToSdlGuideButton() {
        // R4-P2-13: GUIDE = SDL_BUTTON_GUIDE (5). El bucle del núcleo
        // (session.cpp:401-403) lo traduce a on_guide_button ->
        // openPauseMenuFromController (main_android.cpp:101).
        assertEquals(5, VitaButton.GUIDE.toSdlButton())
        assertEquals(TouchOverlayMapping.SDL_BUTTON_GUIDE, VitaButton.GUIDE.toSdlButton())
    }

    @Test
    fun newButtonsAreAssignableButNotInDefaultLayouts() {
        // R4-P2-11/12/13: L2, R2 y GUIDE existen en el enum, así que
        // el editor los lista (TouchLayoutScreen itera
        // VitaButton.entries) y se pueden asignar a un botón táctil.
        // Pero NINGÚN perfil predefinido los lleva: la pantalla sigue
        // limpia y todo es opcional y OFF por defecto (Child of Light
        // no usa L2/R2/L3/R3 en el título).
        assertTrue(VitaButton.entries.contains(VitaButton.L2))
        assertTrue(VitaButton.entries.contains(VitaButton.R2))
        assertTrue(VitaButton.entries.contains(VitaButton.GUIDE))
        val newButtons = setOf(VitaButton.L2, VitaButton.R2, VitaButton.GUIDE, VitaButton.L3, VitaButton.R3)
        for (profile in builtInProfiles()) {
            assertTrue(
                "perfil ${profile.id} no debe llevar L2/R2/GUIDE/L3/R3 por defecto",
                profile.controls.none { it.vitaButton in newButtons },
            )
        }
        // L2/R2 no tienen glifo: se dibujan con su etiqueta.
        // GUIDE tampoco tiene glifo de cara (faceGlyphFor es null),
        // pero el overlay le dibuja el texto "PS" como icono
        // (R5-A1-16: iconFor -> TEXT_PS).
        assertNull(faceGlyphFor(VitaButton.L2))
        assertNull(faceGlyphFor(VitaButton.R2))
        assertNull(faceGlyphFor(VitaButton.GUIDE))
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
    fun stickMayOverhangLeftBlackBarButCenterStaysInside() {
        // 0.2.58 (R7-P1-05): SOLO el stick pisa la barra negra
        // lateral como en el mockup. Teléfono 1600x720, densidad
        // 1.75: el stick guardado en 0.05 queda con el centro en
        // 228 px (como el mockup, 227 px) y el borde en 65 px, 99 px
        // dentro de la barra; su centro sigue dentro del rect del
        // juego y nunca se sale de la vista por la izquierda.
        val game = gameRect(1600f, 720f)
        val stick = TouchControl("s", "Stick", "stick", VitaButton.LEFT_STICK, 0.05f, 0.675f, 186, 0.70f)
        val rect = controlRectInGameRect(stick, game, 1.75f)
        assertEquals(65.5f, rect.left, 1f)
        assertEquals(228.2f, (rect.left + rect.right) / 2f, 1f)
        assertTrue("el stick pisa la barra negra", rect.left < game.left - 0.01f)
        assertTrue("centro del stick dentro del juego", (rect.left + rect.right) / 2f >= game.left - 0.01f)
        assertTrue("el stick no se sale de la vista", rect.left >= 0f)
        assertTrue("el stick no se sale por la derecha", rect.right <= game.right + 0.01f)
        // Un stick arrastrado a x 0 no se sale de la vista: su
        // borde queda en gameLeft - half (2 px) con el centro en
        // el borde del rect del juego.
        val dragged = TouchControl("s", "Stick", "stick", VitaButton.LEFT_STICK, 0.0f, 0.675f, 186, 0.70f)
        val draggedRect = controlRectInGameRect(dragged, game, 1.75f)
        assertEquals(2.0f, draggedRect.left, 1f)
        assertEquals(game.left, (draggedRect.left + draggedRect.right) / 2f, 1f)
    }

    @Test
    fun jumpMayOverhangRightBlackBarButStaysInsideView() {
        // 0.2.58 (R7, decisión del coordinador): el salto TAMBIÉN
        // pisa la barra negra derecha, simétrico al stick: su
        // centro queda en 1.012 del juego (fuera del rect, como en
        // el mockup) y su borde derecho supera gameRight, pero
        // nunca se sale de la vista (tope en el borde derecho).
        // La relajación es SOLO por id btn_jump, NO por kind
        // "button": la espada comparte kind y sigue entera dentro.
        val game = gameRect(1600f, 720f)
        val jump = TouchControl("btn_jump", "Salto", "button", VitaButton.CROSS, 1.012f, 0.752f, 92, 0.80f)
        val rect = controlRectInGameRect(jump, game, 1.75f)
        assertEquals(1450.6f, (rect.left + rect.right) / 2f, 1f)
        assertEquals(1.012f, ((rect.left + rect.right) / 2f - game.left) / (game.right - game.left), 0.001f)
        assertTrue("el salto pisa la barra negra", rect.right > game.right + 0.01f)
        assertTrue("el salto no se sale de la vista", rect.right <= 1600f + 0.01f)
        assertTrue("salto entero vertical", rect.top >= game.top - 0.01f && rect.bottom <= game.bottom + 0.01f)
        // Un salto arrastrado a x 1.5 no se sale de la vista: su
        // borde derecho queda en el borde de la vista (1600) con
        // el centro en 1519,5 px (1,066 del juego).
        val dragged = TouchControl("btn_jump", "Salto", "button", VitaButton.CROSS, 1.5f, 0.752f, 92, 0.80f)
        val draggedRect = controlRectInGameRect(dragged, game, 1.75f)
        assertEquals(1600f, draggedRect.right, 0.01f)
        assertEquals(1519.5f, (draggedRect.left + draggedRect.right) / 2f, 1f)
        // La espada en 0.914 queda entera dentro sin recorte.
        val sword = TouchControl("w", "Espada", "button", VitaButton.SQUARE, 0.914f, 0.576f, 92, 0.80f)
        val swordRect = controlRectInGameRect(sword, game, 1.75f)
        assertEquals(1326.0f, (swordRect.left + swordRect.right) / 2f, 1f)
        assertTrue("espada entera dentro del juego", swordRect.left >= game.left - 0.01f)
        assertTrue("espada entera dentro del juego", swordRect.right <= game.right + 0.01f)
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
        val hidden = controls.map { if (it.id == "btn_jump") it.copy(visible = false) else it }
        assertEquals(controls.size - 1, TouchOverlayMapping.visibleControls(hidden).size)
        assertTrue(TouchOverlayMapping.visibleControls(hidden).none { it.id == "btn_jump" })
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
