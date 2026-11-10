package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.core.FaceGlyph
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.controlRect
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.gameRect
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.toSdlButton
import com.danielsnagustin.childoflight.launcher.data.ControlProfile
import com.danielsnagustin.childoflight.launcher.data.TouchControl
import com.danielsnagustin.childoflight.launcher.data.TouchLayoutRepository
import com.danielsnagustin.childoflight.launcher.data.VitaButton
import com.danielsnagustin.childoflight.launcher.data.defaultTouchLayout
import com.danielsnagustin.childoflight.launcher.data.externalGamepadLayout
import com.danielsnagustin.childoflight.launcher.data.oneHandLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests 0.2.54 (2026-11-09): layout limpio de controles (6 controles,
 * sin rombo), migración de los perfiles predefinidos guardados con el
 * layout viejo de fábrica, iconos por id de control y doble envío del
 * brillo de Igniculus. Todo puro JVM, sin Context ni Compose.
 *
 * Conversión desde la captura 1847x851 (rect del juego x 172.5-1674.5,
 * y 0-851; densidad aprox 2.07): fracJuego = (fracVentana x 1847 -
 * 172.5) / 1502 en x, igual en y. El stick queda en x 0.05 y el
 * recorte a medio botón dentro del juego lo hace controlRect; el
 * salto en ventana x 0.920 da juego 1.02 y se guarda en 0.93
 * (1 - medio botón con 82 dp).
 */
class CleanTouchLayoutTest {

    private fun control(controls: List<TouchControl>, id: String): TouchControl =
        controls.first { it.id == id }

    private fun position(controls: List<TouchControl>, id: String): Pair<Float, Float> {
        val c = control(controls, id)
        return c.xFraction to c.yFraction
    }

    // Layout por defecto tal como se guardó en 0.2.49 (rombo
    // transpuesto, stick 140 dp, Igniculus en y 0.35).
    private fun oldTransposedDefault(): List<TouchControl> = listOf(
        TouchControl("stick", "Stick", "stick", VitaButton.LEFT_STICK, 0.18f, 0.72f, 140, 0.70f),
        TouchControl("btn_y", "Y", "button", VitaButton.TRIANGLE, 0.70f, 0.70f, 64, 0.80f),
        TouchControl("btn_x", "X", "button", VitaButton.SQUARE, 0.78f, 0.62f, 64, 0.80f),
        TouchControl("btn_b", "B", "button", VitaButton.CIRCLE, 0.78f, 0.80f, 64, 0.80f),
        TouchControl("btn_a", "A", "button", VitaButton.CROSS, 0.87f, 0.71f, 64, 0.85f),
        TouchControl("btn_start", "Start", "button", VitaButton.START, 0.50f, 0.06f, 56, 0.70f),
        TouchControl("igniculus", "Igniculus", "igniculus", VitaButton.NONE, 0.50f, 0.35f, 220, 0.25f, colorHex = "#7FB3D5"),
    )

    // Layout por defecto con el rombo ya estándar (post R4-P0-1):
    // stick 104 dp, Igniculus en y 0.55.
    private fun oldStandardDefault(): List<TouchControl> = listOf(
        TouchControl("stick", "Stick", "stick", VitaButton.LEFT_STICK, 0.18f, 0.72f, 104, 0.70f),
        TouchControl("btn_y", "Y", "button", VitaButton.TRIANGLE, 0.80f, 0.62f, 64, 0.80f),
        TouchControl("btn_x", "X", "button", VitaButton.SQUARE, 0.73f, 0.71f, 64, 0.80f),
        TouchControl("btn_b", "B", "button", VitaButton.CIRCLE, 0.87f, 0.71f, 64, 0.80f),
        TouchControl("btn_a", "A", "button", VitaButton.CROSS, 0.80f, 0.80f, 64, 0.85f),
        TouchControl("btn_start", "Start", "button", VitaButton.START, 0.50f, 0.06f, 56, 0.70f),
        TouchControl("igniculus", "Igniculus", "igniculus", VitaButton.NONE, 0.50f, 0.55f, 220, 0.25f, colorHex = "#7FB3D5"),
    )

    // "Una mano" tal como se guardó en 0.2.49 (transpuesto).
    private fun oldTransposedOneHand(): List<TouchControl> = listOf(
        TouchControl("stick", "Stick", "stick", VitaButton.LEFT_STICK, 0.20f, 0.75f, 120, 0.75f),
        TouchControl("btn_y", "Y", "button", VitaButton.TRIANGLE, 0.70f, 0.70f, 64, 0.80f, visible = false),
        TouchControl("btn_x", "X", "button", VitaButton.SQUARE, 0.78f, 0.62f, 64, 0.80f, visible = false),
        TouchControl("btn_b", "B", "button", VitaButton.CIRCLE, 0.72f, 0.87f, 64, 0.80f),
        TouchControl("btn_a", "A", "button", VitaButton.CROSS, 0.84f, 0.78f, 72, 0.85f),
        TouchControl("btn_start", "Start", "button", VitaButton.START, 0.50f, 0.06f, 56, 0.70f, visible = false),
        TouchControl("igniculus", "Igniculus", "igniculus", VitaButton.NONE, 0.55f, 0.28f, 160, 0.25f, colorHex = "#7FB3D5"),
    )

    // "Mando externo" tal como se guardó en 0.2.49 (transpuesto,
    // stick y botones de cara ocultos, Start visible).
    private fun oldTransposedExternal(): List<TouchControl> =
        oldTransposedDefault().map {
            if (it.id == "stick") it.copy(visible = false)
            else if (it.kind == "button" && it.id != "btn_start") it.copy(visible = false)
            else it
        }

    // --- El layout limpio por defecto ---

    @Test
    fun defaultLayoutHasSixCleanControls() {
        val controls = defaultTouchLayout()
        assertEquals(6, controls.size)
        assertEquals(
            listOf("stick", "btn_sword", "btn_jump", "btn_start", "btn_circle", "igniculus"),
            controls.map { it.id },
        )
        assertTrue(controls.all { it.visible })
        // Sin rombo en el default, a propósito; el Circle del
        // default es btn_circle junto a la pausa, no el btn_b.
        assertFalse(controls.any { it.id == "btn_y" || it.id == "btn_x" || it.id == "btn_b" || it.id == "btn_a" })
        assertFalse(controls.any { it.vitaButton == VitaButton.TRIANGLE })
    }

    @Test
    fun defaultLayoutButtonsCarryTheRealVitaActions() {
        val controls = defaultTouchLayout()
        assertEquals(VitaButton.LEFT_STICK, control(controls, "stick").vitaButton)
        assertEquals("stick", control(controls, "stick").kind)
        assertEquals(VitaButton.SQUARE, control(controls, "btn_sword").vitaButton)
        assertEquals("Espada", control(controls, "btn_sword").label)
        assertEquals("button", control(controls, "btn_sword").kind)
        assertEquals(VitaButton.CROSS, control(controls, "btn_jump").vitaButton)
        assertEquals("Salto", control(controls, "btn_jump").label)
        assertEquals("button", control(controls, "btn_jump").kind)
        assertEquals(VitaButton.START, control(controls, "btn_start").vitaButton)
        assertEquals(VitaButton.CIRCLE, control(controls, "btn_circle").vitaButton)
        assertEquals("Volver", control(controls, "btn_circle").label)
        assertEquals("button", control(controls, "btn_circle").kind)
        assertEquals(VitaButton.NONE, control(controls, "igniculus").vitaButton)
        assertEquals("igniculus", control(controls, "igniculus").kind)
    }

    @Test
    fun defaultLayoutPositionsMatchTheReferenceCapture() {
        val controls = defaultTouchLayout()
        assertEquals(0.50f to 0.07f, position(controls, "btn_start"))
        assertEquals(0.59f to 0.07f, position(controls, "btn_circle"))
        assertEquals(0.05f to 0.675f, position(controls, "stick"))
        assertEquals(0.864f to 0.576f, position(controls, "btn_sword"))
        assertEquals(0.93f to 0.752f, position(controls, "btn_jump"))
        assertEquals(0.82f to 0.808f, position(controls, "igniculus"))
        assertEquals(210, control(controls, "stick").sizeDp)
        assertEquals(82, control(controls, "btn_sword").sizeDp)
        assertEquals(82, control(controls, "btn_jump").sizeDp)
        assertEquals(56, control(controls, "btn_start").sizeDp)
        assertEquals(44, control(controls, "btn_circle").sizeDp)
        assertEquals(156, control(controls, "igniculus").sizeDp)
        assertEquals(0.70f, control(controls, "stick").opacity, 0.001f)
        assertEquals(0.80f, control(controls, "btn_sword").opacity, 0.001f)
        assertEquals(0.80f, control(controls, "btn_jump").opacity, 0.001f)
        assertEquals(0.70f, control(controls, "btn_start").opacity, 0.001f)
        assertEquals(0.70f, control(controls, "btn_circle").opacity, 0.001f)
        assertEquals(0.25f, control(controls, "igniculus").opacity, 0.001f)
    }

    @Test
    fun defaultLayoutRectsStayInsideTheGameRectOnTheCapture() {
        // Captura 1847x851, densidad aprox 2.07: el rect del juego
        // es x 172.5-1674.5, y 0-851. Los 6 controles quedan
        // ENTEROS dentro, aunque el stick se guarde en x 0.05 (en
        // la captura pisa la barra negra) y el salto en 0.93.
        // El círculo queda a la derecha de la pausa con 15 dp de
        // hueco entre bordes, sin solaparse con ella.
        val game = gameRect(1847f, 851f)
        assertEquals(172.5f, game.left, 1f)
        assertEquals(1674.5f, game.right, 1f)
        for (c in defaultTouchLayout()) {
            val rect = controlRect(c, 1847f, 851f, 2.07f)
            assertTrue("rect de ${c.id} dentro del juego", rect.left >= game.left - 0.01f && rect.top >= game.top - 0.01f)
            assertTrue("rect de ${c.id} dentro del juego", rect.right <= game.right + 0.01f && rect.bottom <= game.bottom + 0.01f)
        }
        // El stick se recorta al borde izquierdo del juego: su
        // izquierda ES la del rect del juego, no la de la ventana.
        val stickRect = controlRect(control(defaultTouchLayout(), "stick"), 1847f, 851f, 2.07f)
        assertEquals(game.left, stickRect.left, 1f)
        assertTrue("el stick no pisa la barra negra", stickRect.left >= game.left - 0.01f)
    }

    @Test
    fun oneHandLayoutKeepsSixControlsWithThumbGroupShifted() {
        val controls = oneHandLayout()
        assertEquals(6, controls.size)
        assertEquals(
            listOf("stick", "btn_sword", "btn_jump", "btn_start", "btn_circle", "igniculus"),
            controls.map { it.id },
        )
        assertTrue(controls.all { it.visible })
        assertEquals(0.05f to 0.70f, position(controls, "stick"))
        assertEquals(0.83f to 0.63f, position(controls, "btn_sword"))
        assertEquals(0.91f to 0.79f, position(controls, "btn_jump"))
        assertEquals(0.50f to 0.07f, position(controls, "btn_start"))
        assertEquals(0.59f to 0.07f, position(controls, "btn_circle"))
        assertEquals(0.78f to 0.80f, position(controls, "igniculus"))
        assertEquals(210, control(controls, "stick").sizeDp)
        assertEquals(44, control(controls, "btn_circle").sizeDp)
        assertEquals(156, control(controls, "igniculus").sizeDp)
        // El grupo derecho baja hacia la esquina para el pulgar.
        assertTrue(position(controls, "btn_jump").second > position(defaultTouchLayout(), "btn_jump").second)
        assertTrue(position(controls, "btn_sword").second > position(defaultTouchLayout(), "btn_sword").second)
    }

    @Test
    fun externalLayoutHidesStickSwordJumpAndCircle() {
        val controls = externalGamepadLayout()
        assertEquals(6, controls.size)
        assertFalse(control(controls, "stick").visible)
        assertFalse(control(controls, "btn_sword").visible)
        assertFalse(control(controls, "btn_jump").visible)
        assertFalse(control(controls, "btn_circle").visible)
        assertTrue(control(controls, "btn_start").visible)
        assertTrue(control(controls, "igniculus").visible)
        // Mismas posiciones que el default.
        assertEquals(position(defaultTouchLayout(), "btn_start"), position(controls, "btn_start"))
        assertEquals(position(defaultTouchLayout(), "btn_circle"), position(controls, "btn_circle"))
        assertEquals(position(defaultTouchLayout(), "igniculus"), position(controls, "igniculus"))
        assertEquals(position(defaultTouchLayout(), "btn_sword"), position(controls, "btn_sword"))
    }

    // --- Migración al layout limpio ---

    @Test
    fun migratesSavedTransposedDefaultToCleanLayout() {
        val saved = ControlProfile("default-g99", "Por defecto G99", true, oldTransposedDefault())
        assertEquals(defaultTouchLayout(), TouchLayoutRepository.migrateBuiltInToCleanLayout(saved).controls)
        assertEquals(defaultTouchLayout(), TouchLayoutRepository.migrateBuiltInProfile(saved).controls)
    }

    @Test
    fun migratesSavedStandardDiamondDefaultToCleanLayout() {
        // Un perfil ya normalizado al rombo estándar también es
        // layout viejo de fábrica: también migra al limpio.
        val saved = ControlProfile("default-g99", "Por defecto G99", true, oldStandardDefault())
        assertEquals(defaultTouchLayout(), TouchLayoutRepository.migrateBuiltInToCleanLayout(saved).controls)
        assertEquals(defaultTouchLayout(), TouchLayoutRepository.migrateBuiltInProfile(saved).controls)
    }

    @Test
    fun migratesSavedOneHandAndExternalToCleanLayout() {
        val oneHand = ControlProfile("one-hand", "Una mano", true, oldTransposedOneHand())
        assertEquals(oneHandLayout(), TouchLayoutRepository.migrateBuiltInProfile(oneHand).controls)
        val external = ControlProfile("external-gamepad", "Mando externo + táctil mínimo", true, oldTransposedExternal())
        assertEquals(externalGamepadLayout(), TouchLayoutRepository.migrateBuiltInProfile(external).controls)
    }

    @Test
    fun cleanMigrationLeavesEditedBuiltInAlone() {
        // El usuario movió un botón de cara: el perfil es suyo.
        val edited = oldTransposedDefault().map {
            if (it.id == "btn_a") it.copy(xFraction = 0.85f) else it
        }
        val saved = ControlProfile("default-g99", "Por defecto G99", true, edited)
        assertEquals(saved, TouchLayoutRepository.migrateBuiltInToCleanLayout(saved))
        // La cadena completa tampoco lo convierte al limpio: sigue
        // con sus 7 controles viejos, sin espada ni salto, y el
        // botón editado queda donde lo dejó el usuario. El stick y
        // la Igniculus sí se normalizan por la pasada de fábrica.
        val migrated = TouchLayoutRepository.migrateBuiltInProfile(saved)
        assertEquals(7, migrated.controls.size)
        assertFalse(migrated.controls.any { it.id == "btn_sword" || it.id == "btn_jump" })
        assertEquals(0.85f to 0.71f, position(migrated.controls, "btn_a"))
        assertEquals(104, control(migrated.controls, "stick").sizeDp)
        assertEquals(0.50f to 0.55f, position(migrated.controls, "igniculus"))
    }

    @Test
    fun cleanMigrationLeavesCustomProfilesAlone() {
        val custom = ControlProfile("custom-1", "Mío", false, oldTransposedDefault())
        assertEquals(custom, TouchLayoutRepository.migrateBuiltInToCleanLayout(custom))
        assertEquals(custom, TouchLayoutRepository.migrateBuiltInProfile(custom))
    }

    @Test
    fun cleanMigrationIsIdempotentAndFreshIsUntouched() {
        val saved = ControlProfile("default-g99", "Por defecto G99", true, oldTransposedDefault())
        val once = TouchLayoutRepository.migrateBuiltInProfile(saved)
        assertEquals(once, TouchLayoutRepository.migrateBuiltInProfile(once))
        assertEquals(defaultTouchLayout(), once.controls)
        val fresh = ControlProfile("default-g99", "Por defecto G99", true, defaultTouchLayout())
        assertEquals(fresh, TouchLayoutRepository.migrateBuiltInProfile(fresh))
        val freshOneHand = ControlProfile("one-hand", "Una mano", true, oneHandLayout())
        assertEquals(freshOneHand, TouchLayoutRepository.migrateBuiltInProfile(freshOneHand))
    }

    // Layout limpio de 5 controles (sin btn_circle), forma en la
    // que un perfil pudo quedar guardado en 0.2.54 antes del
    // círculo. Literales (no derivados del layout nuevo): el test
    // fija la entrada de forma independiente.
    private fun cleanFiveControlDefault(): List<TouchControl> = listOf(
        TouchControl("stick", "Stick", "stick", VitaButton.LEFT_STICK, 0.05f, 0.675f, 210, 0.70f),
        TouchControl("btn_sword", "Espada", "button", VitaButton.SQUARE, 0.864f, 0.576f, 82, 0.80f),
        TouchControl("btn_jump", "Salto", "button", VitaButton.CROSS, 0.93f, 0.752f, 82, 0.80f),
        TouchControl("btn_start", "Start", "button", VitaButton.START, 0.50f, 0.07f, 56, 0.70f),
        TouchControl("igniculus", "Igniculus", "igniculus", VitaButton.NONE, 0.82f, 0.808f, 156, 0.25f, colorHex = "#7FB3D5"),
    )

    private fun cleanFiveControlOneHand(): List<TouchControl> = listOf(
        TouchControl("stick", "Stick", "stick", VitaButton.LEFT_STICK, 0.05f, 0.70f, 210, 0.75f),
        TouchControl("btn_sword", "Espada", "button", VitaButton.SQUARE, 0.83f, 0.63f, 82, 0.80f),
        TouchControl("btn_jump", "Salto", "button", VitaButton.CROSS, 0.91f, 0.79f, 82, 0.80f),
        TouchControl("btn_start", "Start", "button", VitaButton.START, 0.50f, 0.07f, 56, 0.70f),
        TouchControl("igniculus", "Igniculus", "igniculus", VitaButton.NONE, 0.78f, 0.80f, 156, 0.25f, colorHex = "#7FB3D5"),
    )

    private fun cleanFiveControlExternal(): List<TouchControl> =
        cleanFiveControlDefault().map {
            if (it.id == "stick" || it.id == "btn_sword" || it.id == "btn_jump") it.copy(visible = false) else it
        }

    @Test
    fun cleanMigrationAddsCircleToFiveControlDefault() {
        // Un perfil que ya migró al limpio de 5 en 0.2.54 recibe
        // el círculo junto a la pausa, sin tocar nada más.
        val saved = ControlProfile("default-g99", "Por defecto G99", true, cleanFiveControlDefault())
        assertEquals(defaultTouchLayout(), TouchLayoutRepository.migrateBuiltInToCleanLayout(saved).controls)
        assertEquals(defaultTouchLayout(), TouchLayoutRepository.migrateBuiltInProfile(saved).controls)
        // La pasada es idempotente: una vez con círculo, no cambia.
        val once = TouchLayoutRepository.migrateBuiltInProfile(saved)
        assertEquals(once, TouchLayoutRepository.migrateBuiltInProfile(once))
    }

    @Test
    fun cleanMigrationAddsCircleToFiveControlOneHandAndExternal() {
        val oneHand = ControlProfile("one-hand", "Una mano", true, cleanFiveControlOneHand())
        assertEquals(oneHandLayout(), TouchLayoutRepository.migrateBuiltInProfile(oneHand).controls)
        val external = ControlProfile("external-gamepad", "Mando externo + táctil mínimo", true, cleanFiveControlExternal())
        assertEquals(externalGamepadLayout(), TouchLayoutRepository.migrateBuiltInProfile(external).controls)
        // En externo el círculo queda oculto, como la espada y el salto.
        assertFalse(control(externalGamepadLayout(), "btn_circle").visible)
    }

    @Test
    fun cleanMigrationDoesNotAddCircleToEditedFiveControlLayout() {
        // El usuario movió la espada en su layout limpio de 5: el
        // perfil es suyo y NO recibe el círculo.
        val edited = cleanFiveControlDefault().map {
            if (it.id == "btn_sword") it.copy(xFraction = 0.80f) else it
        }
        val saved = ControlProfile("default-g99", "Por defecto G99", true, edited)
        assertEquals(saved, TouchLayoutRepository.migrateBuiltInToCleanLayout(saved))
        assertEquals(saved, TouchLayoutRepository.migrateBuiltInProfile(saved))
        assertFalse(saved.controls.any { it.id == "btn_circle" })
    }

    @Test
    fun circleButtonUsesGlyphAndRipplePath() {
        // btn_circle es un botón normal (kind "button"): en el juego
        // pasa por ButtonView, que dispara la gota en ACTION_DOWN
        // y dibuja el glifo ○ (faceGlyphFor CIRCLE -> GLYPH_CIRCLE),
        // sin icono propio por id (no es espada ni flecha).
        val controls = defaultTouchLayout()
        assertEquals("button", control(controls, "btn_circle").kind)
        assertNull(TouchOverlayMapping.iconOverrideFor("btn_circle"))
        assertEquals(FaceGlyph.CIRCLE, TouchOverlayMapping.faceGlyphFor(VitaButton.CIRCLE))
        // El círculo manda el botón SDL 1 (EAST) al núcleo.
        assertEquals(1, VitaButton.CIRCLE.toSdlButton())
    }

    @Test
    fun cleanMigrationMigratesAllThreeBuiltIns() {
        val saved = listOf(
            ControlProfile("default-g99", "Por defecto G99", true, oldTransposedDefault()),
            ControlProfile("one-hand", "Una mano", true, oldTransposedOneHand()),
            ControlProfile("external-gamepad", "Mando externo + táctil mínimo", true, oldTransposedExternal()),
            ControlProfile("custom-1", "Mío", false, oldTransposedDefault()),
        )
        val migrated = TouchLayoutRepository.migrateBuiltInProfiles(saved)
        assertEquals(defaultTouchLayout(), migrated[0].controls)
        assertEquals(oneHandLayout(), migrated[1].controls)
        assertEquals(externalGamepadLayout(), migrated[2].controls)
        assertEquals(saved[3], migrated[3])
    }

    // --- Iconos por id de control ---

    @Test
    fun iconOverrideIsByControlIdNotByVitaButton() {
        assertEquals(
            TouchOverlayMapping.ControlIconOverride.SWORD,
            TouchOverlayMapping.iconOverrideFor("btn_sword"),
        )
        assertEquals(
            TouchOverlayMapping.ControlIconOverride.ARROW_UP,
            TouchOverlayMapping.iconOverrideFor("btn_jump"),
        )
        // Cualquier otro id (incluidos los botones de cara del
        // rombo) no tiene icono propio: iconFor cae al glifo.
        assertNull(TouchOverlayMapping.iconOverrideFor("btn_a"))
        assertNull(TouchOverlayMapping.iconOverrideFor("btn_b"))
        assertNull(TouchOverlayMapping.iconOverrideFor("btn_x"))
        assertNull(TouchOverlayMapping.iconOverrideFor("btn_y"))
        assertNull(TouchOverlayMapping.iconOverrideFor("btn_start"))
        assertNull(TouchOverlayMapping.iconOverrideFor("stick"))
        assertNull(TouchOverlayMapping.iconOverrideFor("igniculus"))
        assertNull(TouchOverlayMapping.iconOverrideFor("otro"))
    }

    @Test
    fun userFaceButtonsKeepTheirGlyphs() {
        // Un CROSS añadido por el usuario en el editor (id btn_a,
        // no btn_jump) sigue con su glifo X, y un SQUARE con el
        // suyo: el icono propio es solo para btn_sword/btn_jump.
        assertEquals(FaceGlyph.CROSS, TouchOverlayMapping.faceGlyphFor(VitaButton.CROSS))
        assertEquals(FaceGlyph.SQUARE, TouchOverlayMapping.faceGlyphFor(VitaButton.SQUARE))
        assertEquals(FaceGlyph.CIRCLE, TouchOverlayMapping.faceGlyphFor(VitaButton.CIRCLE))
        assertEquals(FaceGlyph.TRIANGLE, TouchOverlayMapping.faceGlyphFor(VitaButton.TRIANGLE))
        assertNull(TouchOverlayMapping.iconOverrideFor("btn_a"))
        assertNull(TouchOverlayMapping.iconOverrideFor("btn_x"))
    }

    // --- Doble envío del brillo ---

    @Test
    fun glowSendsBothShoulderButtonAndTriggerAxis() {
        // Hombro L (SDL 9) + eje L2 (-4): en modo normal el 9 basta
        // y en modo ext solo el eje llega al bit L; enviar ambos NO
        // es inocuo: en lecturas v2 el 9 pone ademas el bit L1
        // (0x400) como colateral (ver KDoc de glowSdlButtons).
        assertEquals(listOf(9, -4), TouchOverlayMapping.glowSdlButtons())
        assertEquals(9, TouchOverlayMapping.SDL_BUTTON_LEFT_SHOULDER)
        assertEquals(-4, -TouchOverlayMapping.SDL_AXIS_LEFT_TRIGGER)
    }

    // --- Orden de hijos: la zona detrás, los botones encima ---

    @Test
    fun overlayChildOrderPutsIgniculusBehindButtons() {
        val controls = listOf(
            TouchControl("btn_jump", "Salto", "button", VitaButton.CROSS, 0.93f, 0.752f, 82, 0.80f),
            TouchControl("igniculus", "Igniculus", "igniculus", VitaButton.NONE, 0.82f, 0.808f, 156, 0.25f),
            TouchControl("stick", "Stick", "stick", VitaButton.LEFT_STICK, 0.05f, 0.675f, 210, 0.70f),
        )
        val ordered = TouchOverlayMapping.overlayChildOrder(controls)
        assertEquals(listOf("igniculus", "btn_jump", "stick"), ordered.map { it.id })
        // Los botones mantienen su orden relativo entre ellos.
        val default = TouchOverlayMapping.overlayChildOrder(defaultTouchLayout())
        assertEquals("igniculus", default.first().id)
        assertEquals(listOf("stick", "btn_sword", "btn_jump", "btn_start", "btn_circle"), default.drop(1).map { it.id })
        // Los ocultos no aparecen.
        val hidden = TouchOverlayMapping.overlayChildOrder(externalGamepadLayout())
        assertEquals(listOf("igniculus", "btn_start"), hidden.map { it.id })
    }
}
