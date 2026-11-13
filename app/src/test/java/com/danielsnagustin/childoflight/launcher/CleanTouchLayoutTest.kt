package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.core.ControlRect
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
import kotlin.math.sqrt

/**
 * Tests 0.2.54 (2026-11-09): layout limpio de controles (6 controles,
 * sin rombo), migración de los perfiles predefinidos guardados con el
 * layout viejo de fábrica, iconos por id de control y doble envío del
 * brillo de Igniculus. Todo puro JVM, sin Context ni Compose.
 *
 * Conversión desde la captura 1847x851 (rect del juego x 172.5-1674.5,
 * y 0-851; densidad aprox 2.07): fracJuego = (fracVentana x 1847 -
 * 172.5) / 1502 en x, igual en y. El stick queda en x 0.05 y pisa
 * la barra negra (0.2.58: el clamp solo lo limita a centro dentro
 * del rect); el salto se guarda en 1.012 y el clamp se relaja
 * también para él (R7, decisión del coordinador): se dibuja en
 * 1.012, con el borde derecho 96 px dentro de la barra negra
 * derecha en el teléfono y sin salirse de la vista.
 *
 * 0.2.57 (2026-11-10): re-medición del mockup (componentes conexos
 * por brillo): el stick es un círculo de unos 385 px, no 434 de
 * adorno a adorno; el orbe, unos 200 px, no 272. Con la densidad
 * lógica del teléfono (720x1600, 280 dpi, factor 1.75): stick
 * 186 dp, zona Igniculus 115 dp (orbe dibujado 97 dp); espada y
 * salto 82 dp, pausa 56 dp y círculo 44 dp no cambian. Los perfiles
 * guardados con los tamaños viejos (stick 210 dp, zona 156 dp)
 * migran a los nuevos: el perfil entero si sigue exacto en fábrica
 * (migrateBuiltInToCleanLayout) y, si el usuario lo editó a medias,
 * cada control que sigue en fábrica por separado
 * (migrateBuiltInFactoryValues). Ver los tests 0.2.57 abajo.
 *
 * 0.2.58 (2026-11-10, R7-P1-04/R7-P2-04/R7-P1-05): re-medición del
 * mockup por ajuste de círculo sobre el anillo brillante: espada
 * centro juego (0.915, 0.593), anillo 177 px; salto centro juego
 * (1.012, 0.767), anillo 182 px; orbe (0.821, 0.810), anillo
 * 203 px. Espada y salto suben a 92 dp y se mueven a su x del
 * mockup (0.914 y 1.012 guardado); el stick pisa la barra negra
 * izquierda y el salto la derecha (clamp relajado para los dos).
 * Los huecos verificados en el teléfono están en
 * defaultLayoutGapsOnPhone: con el salto en 1.012, la
 * espada-salto pasa de solape ~31 px a hueco +17 px y el
 * salto-orbe de solape ~11 px a hueco +82 px: ver el KDoc
 * de defaultTouchLayout, que documenta la geometría exacta.
 *
 * 0.2.63 (2026-11-11, A-1): la densidad real del teléfono es 2.80
 * (1080x2340, ~385 ppi), no 1.75: los tamaños de fábrica se
 * recalibran (stick 174 dp, zona 104 dp, orbe 87 dp; espada 86 dp
 * y salto 87 dp en default/externo, 77 dp en una mano; pausa
 * 52 dp, círculo 41 dp). Las posiciones no cambian. Los tests de
 * geometría en teléfono (defaultSizesMatchMockupProportionsOnPhone,
 * stickOverhangsLeftBlackBarOnPhone, defaultLayoutGapsOnPhone) se
 * reescribieron para 2340x1080 a 2.80 (rect del juego 1906x1080,
 * izquierda 217): los huecos quedan en +24/+127/+65 px. El test
 * sobre la captura 1847x851 (defaultLayoutRectsStayInsideTheGame-
 * RectOnTheCapture) conserva su canvas y su densidad 2.07: solo
 * cambian los valores derivados de los tamaños nuevos.
 *
 * 0.2.64 (2026-11-11, A-7): los tamaños de fábrica se guardan
 * además como fracción del alto del juego (sizeFraction): en el
 * teléfono de referencia (juego 1080 px de alto) dan los mismos px
 * que antes, pero en la captura 1847x851 (juego 851 px) el stick
 * mide 383,9 px en vez de 174 dp x 2.07 = 360,2 px: la densidad
 * 2.07 ya no manda en los controles de fábrica y los literales de
 * defaultLayoutRectsStayInsideTheGameRectOnTheCapture se
 * recalcularon (stick 55,8 px, salto 1788,4 px; el centro del
 * salto, 1692,4 px, no cambia).
 *
 * 0.2.68 (2026-11-12, neón): el default es EXACTAMENTE la imagen
 * 52710_13_ua6f.png (hecha por ChatGPT) medida por umbral de
 * brillo: 10 controles (stick 177 dp, pausa 50 dp, píldoras L/R
 * 51 dp y START 38 dp, rombo △□○✕ de 60 dp de cristal, Igniculus
 * zona 120 dp con orbe dibujado 101 dp). L (-0.012), R (1.016) y
 * ○ (1.028) viven sobre las barras negras por diseño; los botones
 * de cara NO se tocan (hueco de 14-17 px, como en la imagen). Las
 * píldoras llevan
 * widthFraction (ancho como fracción del alto del juego). El
 * default 0.2.67 (6 controles) migra entero al neón
 * (migrateBuiltInToNeonLayout); una mano NO cambia.
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
    fun defaultLayoutHasTenNeonControls() {
        // 0.2.68 (neón): el default es la imagen 52710_13_ua6f.png
        // tal cual: stick, pausa con alas, píldoras L/R/START,
        // rombo △□○✕ e Igniculus. Nada de espada/salto/círculo
        // junto a la pausa: esos ids ya no existen en el default.
        val controls = defaultTouchLayout()
        assertEquals(10, controls.size)
        assertEquals(
            listOf("stick", "btn_pause", "btn_l", "btn_r", "btn_start_pill", "btn_tri", "btn_sq", "btn_circ", "btn_cross", "igniculus"),
            controls.map { it.id },
        )
        assertTrue(controls.all { it.visible })
        assertFalse(controls.any { it.id == "btn_sword" || it.id == "btn_jump" || it.id == "btn_start" || it.id == "btn_circle" })
        assertFalse(controls.any { it.id == "btn_y" || it.id == "btn_x" || it.id == "btn_b" || it.id == "btn_a" })
        // El rombo sí lleva TRIANGLE (△ arriba); las píldoras L/R
        // son los hombros, no botones de cara.
        assertTrue(controls.any { it.vitaButton == VitaButton.TRIANGLE })
        assertEquals(VitaButton.L1, control(controls, "btn_l").vitaButton)
        assertEquals(VitaButton.R1, control(controls, "btn_r").vitaButton)
    }

    @Test
    fun defaultLayoutButtonsCarryTheRealVitaActions() {
        val controls = defaultTouchLayout()
        assertEquals(VitaButton.LEFT_STICK, control(controls, "stick").vitaButton)
        assertEquals("stick", control(controls, "stick").kind)
        assertEquals(VitaButton.START, control(controls, "btn_pause").vitaButton)
        assertEquals("Pausa", control(controls, "btn_pause").label)
        assertEquals("button", control(controls, "btn_pause").kind)
        assertEquals(VitaButton.L1, control(controls, "btn_l").vitaButton)
        assertEquals("L", control(controls, "btn_l").label)
        assertEquals(VitaButton.R1, control(controls, "btn_r").vitaButton)
        assertEquals("R", control(controls, "btn_r").label)
        assertEquals(VitaButton.START, control(controls, "btn_start_pill").vitaButton)
        assertEquals("START", control(controls, "btn_start_pill").label)
        assertEquals(VitaButton.TRIANGLE, control(controls, "btn_tri").vitaButton)
        assertEquals("Triángulo", control(controls, "btn_tri").label)
        assertEquals("button", control(controls, "btn_tri").kind)
        assertEquals(VitaButton.SQUARE, control(controls, "btn_sq").vitaButton)
        assertEquals("Cuadrado", control(controls, "btn_sq").label)
        assertEquals("button", control(controls, "btn_sq").kind)
        assertEquals(VitaButton.CIRCLE, control(controls, "btn_circ").vitaButton)
        assertEquals("Círculo", control(controls, "btn_circ").label)
        assertEquals("button", control(controls, "btn_circ").kind)
        assertEquals(VitaButton.CROSS, control(controls, "btn_cross").vitaButton)
        assertEquals("Cruz", control(controls, "btn_cross").label)
        assertEquals("button", control(controls, "btn_cross").kind)
        assertEquals(VitaButton.NONE, control(controls, "igniculus").vitaButton)
        assertEquals("igniculus", control(controls, "igniculus").kind)
    }

    @Test
    fun defaultLayoutPositionsMatchTheReferenceCapture() {
        // 0.2.68 (neón): posiciones y tamaños de la imagen
        // 52710_13_ua6f.png, fracciones del juego (ver el KDoc de
        // defaultTouchLayout). Las píldoras son más anchas que
        // altas: widthFraction como fracción del alto del juego.
        val controls = defaultTouchLayout()
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
        assertEquals(com.danielsnagustin.childoflight.launcher.data.ControlShape.PILL, control(controls, "btn_l").shape)
        assertEquals(com.danielsnagustin.childoflight.launcher.data.ControlShape.PILL, control(controls, "btn_r").shape)
        assertEquals(com.danielsnagustin.childoflight.launcher.data.ControlShape.PILL, control(controls, "btn_start_pill").shape)
        assertEquals(0.278f, control(controls, "btn_l").widthFraction!!, 0.001f)
        assertEquals(0.281f, control(controls, "btn_r").widthFraction!!, 0.001f)
        assertEquals(0.346f, control(controls, "btn_start_pill").widthFraction!!, 0.001f)
        assertNull(control(controls, "btn_tri").widthFraction)
        assertEquals(0.70f, control(controls, "stick").opacity, 0.001f)
        assertEquals(0.70f, control(controls, "btn_pause").opacity, 0.001f)
        assertEquals(0.80f, control(controls, "btn_l").opacity, 0.001f)
        assertEquals(0.80f, control(controls, "btn_r").opacity, 0.001f)
        assertEquals(0.80f, control(controls, "btn_start_pill").opacity, 0.001f)
        assertEquals(0.80f, control(controls, "btn_tri").opacity, 0.001f)
        assertEquals(0.80f, control(controls, "btn_sq").opacity, 0.001f)
        assertEquals(0.80f, control(controls, "btn_circ").opacity, 0.001f)
        assertEquals(0.80f, control(controls, "btn_cross").opacity, 0.001f)
        assertEquals(0.25f, control(controls, "igniculus").opacity, 0.001f)
    }

    @Test
    fun defaultLayoutRectsStayInsideTheGameRectOnTheCapture() {
        // Captura 1847x851, densidad aprox 2.07: el rect del juego
        // es x 172.6-1674.4, y 0-851. 0.2.68 (neón): el diseño de la
        // imagen PISA las barras negras a propósito — no es un
        // error, es la imagen: el stick y la píldora L cuelgan a la
        // izquierda (centro de L en -0.012, fuera del rect), y △, ○,
        // ✕ y la píldora R cuelgan a la derecha (centro de ○ en
        // 1.028, fuera del rect). Lo que se fija aquí: NINGÚN rect
        // se sale de la VISTA (1847x851), y pausa, □, píldora
        // START e Igniculus quedan ENTEROS dentro del rect.
        val game = gameRect(1847f, 851f)
        assertEquals(172.6f, game.left, 1f)
        assertEquals(1674.4f, game.right, 1f)
        for (c in defaultTouchLayout()) {
            val rect = controlRect(c, 1847f, 851f, 2.07f)
            assertTrue("rect de ${c.id} dentro de la vista", rect.left >= -0.01f && rect.top >= -0.01f)
            assertTrue("rect de ${c.id} dentro de la vista", rect.right <= 1847f + 0.01f && rect.bottom <= 851f + 0.01f)
            when (c.id) {
                "btn_pause", "btn_sq", "btn_start_pill", "igniculus" -> {
                    assertTrue("rect de ${c.id} entero dentro del juego", rect.left >= game.left - 0.01f && rect.top >= game.top - 0.01f)
                    assertTrue("rect de ${c.id} entero dentro del juego", rect.right <= game.right + 0.01f && rect.bottom <= game.bottom + 0.01f)
                }
                "stick" -> {
                    // Pisa la barra izquierda, con el centro dentro.
                    assertTrue("stick pisa la barra: ${c.id}", rect.left < game.left - 0.01f)
                    assertTrue("centro del stick dentro del juego", (rect.left + rect.right) / 2f >= game.left - 0.01f)
                }
                "btn_l" -> {
                    // Centro fuera del rect, sobre la barra izquierda.
                    assertTrue("L pisa la barra: ${c.id}", rect.left < game.left - 0.01f)
                    assertTrue("centro de L sobre la barra", (rect.left + rect.right) / 2f < game.left - 0.01f)
                }
                "btn_tri", "btn_cross" -> {
                    // El borde derecho cuelga sobre la barra derecha,
                    // con el centro dentro del rect.
                    assertTrue("borde de ${c.id} sobre la barra", rect.right > game.right + 0.01f)
                    assertTrue("centro de ${c.id} dentro del juego", (rect.left + rect.right) / 2f <= game.right + 0.01f)
                }
                "btn_circ", "btn_r" -> {
                    // Centro fuera del rect, sobre la barra derecha.
                    assertTrue("borde de ${c.id} sobre la barra", rect.right > game.right + 0.01f)
                    assertTrue("centro de ${c.id} sobre la barra", (rect.left + rect.right) / 2f > game.right + 0.01f)
                }
            }
        }
        // El stick ya NO se recorta al borde izquierdo del juego:
        // su izquierda es 29,9 px en la captura (172.6 + 0.035 x
        // 1501.8 - 390,5 / 2 = 29,9), 143 px dentro de la barra.
        val stickRect = controlRect(control(defaultTouchLayout(), "stick"), 1847f, 851f, 2.07f)
        assertEquals(29.9f, stickRect.left, 1f)
        assertTrue("el stick pisa la barra negra", stickRect.left < game.left - 0.01f)
        // El círculo guardado en 1.028 queda con el centro en
        // 1716,4 px y el borde derecho en 1798,1 px, 124 px dentro
        // de la barra y 49 px dentro de la vista.
        val circleRect = controlRect(control(defaultTouchLayout(), "btn_circ"), 1847f, 851f, 2.07f)
        assertEquals(1716.4f, (circleRect.left + circleRect.right) / 2f, 1f)
        assertEquals(1798.1f, circleRect.right, 1f)
        assertTrue("el círculo pisa la barra negra", circleRect.right > game.right + 0.01f)
    }

    @Test
    fun oneHandLayoutHasTenControlsWithThumbAnchors() {
        // 0.2.68 segunda pasada: una mano lleva el MISMO set de 10
        // controles que el default, con sus anclas de pulgar:
        // cuadrado y cruz se quedan EXACTAMENTE donde estaban
        // espada y salto ((0.83, 0.63) y (0.91, 0.79)); triángulo
        // y círculo se derivan simétricos alrededor de (0.91,
        // 0.63). El rombo queda más abierto que en la imagen
        // (hueco +62 px entre adyacentes): es la apertura del
        // pulgar, no un botón más grande (60 dp en los cuatro).
        val controls = oneHandLayout()
        assertEquals(10, controls.size)
        assertEquals(
            listOf("stick", "btn_start", "btn_l", "btn_r", "btn_start_pill", "btn_tri", "btn_sq", "btn_circ", "btn_cross", "igniculus"),
            controls.map { it.id },
        )
        assertTrue(controls.all { it.visible })
        assertEquals(0.05f to 0.70f, position(controls, "stick"))
        assertEquals(0.50f to 0.07f, position(controls, "btn_start"))
        assertEquals(0.83f to 0.63f, position(controls, "btn_sq"))
        assertEquals(0.91f to 0.79f, position(controls, "btn_cross"))
        assertEquals(0.91f to 0.47f, position(controls, "btn_tri"))
        assertEquals(0.99f to 0.63f, position(controls, "btn_circ"))
        assertEquals(0.78f to 0.80f, position(controls, "igniculus"))
        assertEquals(174, control(controls, "stick").sizeDp)
        assertEquals(60, control(controls, "btn_sq").sizeDp)
        assertEquals(60, control(controls, "btn_cross").sizeDp)
        assertEquals(104, control(controls, "igniculus").sizeDp)
        assertFalse(controls.any { it.id == "btn_sword" || it.id == "btn_jump" || it.id == "btn_circle" })
        // El grupo derecho baja hacia la esquina para el pulgar:
        // la cruz queda más abajo que la ✕ del default.
        assertTrue(position(controls, "btn_cross").second > position(defaultTouchLayout(), "btn_cross").second)
        assertTrue(position(controls, "btn_sq").second > position(defaultTouchLayout(), "btn_sq").second)
    }

    @Test
    fun externalLayoutHidesStickSwordJumpAndCircle() {
        // 0.2.68 (neón): el externo espeja el default neón (10
        // controles): stick, L/R, píldora START y rombo ocultos
        // (los pone el mando físico); pausa e Igniculus visibles.
        val controls = externalGamepadLayout()
        assertEquals(10, controls.size)
        assertFalse(control(controls, "stick").visible)
        assertFalse(control(controls, "btn_l").visible)
        assertFalse(control(controls, "btn_r").visible)
        assertFalse(control(controls, "btn_start_pill").visible)
        assertFalse(control(controls, "btn_tri").visible)
        assertFalse(control(controls, "btn_sq").visible)
        assertFalse(control(controls, "btn_circ").visible)
        assertFalse(control(controls, "btn_cross").visible)
        assertTrue(control(controls, "btn_pause").visible)
        assertTrue(control(controls, "igniculus").visible)
        // Mismas posiciones que el default.
        assertEquals(position(defaultTouchLayout(), "btn_pause"), position(controls, "btn_pause"))
        assertEquals(position(defaultTouchLayout(), "btn_circ"), position(controls, "btn_circ"))
        assertEquals(position(defaultTouchLayout(), "igniculus"), position(controls, "igniculus"))
        assertEquals(position(defaultTouchLayout(), "btn_sq"), position(controls, "btn_sq"))
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
        // 0.2.68 (neón): el externo ya no lleva btn_circle (0.2.67):
        // el ○ del rombo es btn_circ y también queda oculto.
        assertFalse(control(externalGamepadLayout(), "btn_circ").visible)
    }

    // --- Migración 0.2.57: tamaños viejos de fábrica -> nuevos ---

    // Layout limpio de 6 controles con los tamaños viejos de
    // 0.2.54/0.2.55/0.2.56 (stick 210 dp, zona 156 dp): la forma en
    // la que queda guardado un perfil predefinido de una instalación
    // limpia 0.2.55. Literales (no derivados del layout nuevo): el
    // test fija la entrada de forma independiente.
    private fun cleanSixControlOldSizesDefault(): List<TouchControl> = listOf(
        TouchControl("stick", "Stick", "stick", VitaButton.LEFT_STICK, 0.05f, 0.675f, 210, 0.70f),
        TouchControl("btn_sword", "Espada", "button", VitaButton.SQUARE, 0.864f, 0.576f, 82, 0.80f),
        TouchControl("btn_jump", "Salto", "button", VitaButton.CROSS, 0.93f, 0.752f, 82, 0.80f),
        TouchControl("btn_start", "Start", "button", VitaButton.START, 0.50f, 0.07f, 56, 0.70f),
        TouchControl("btn_circle", "Volver", "button", VitaButton.CIRCLE, 0.59f, 0.07f, 44, 0.70f),
        TouchControl("igniculus", "Igniculus", "igniculus", VitaButton.NONE, 0.82f, 0.808f, 156, 0.25f, colorHex = "#7FB3D5"),
    )

    private fun cleanSixControlOldSizesOneHand(): List<TouchControl> = listOf(
        TouchControl("stick", "Stick", "stick", VitaButton.LEFT_STICK, 0.05f, 0.70f, 210, 0.75f),
        TouchControl("btn_sword", "Espada", "button", VitaButton.SQUARE, 0.83f, 0.63f, 82, 0.80f),
        TouchControl("btn_jump", "Salto", "button", VitaButton.CROSS, 0.91f, 0.79f, 82, 0.80f),
        TouchControl("btn_start", "Start", "button", VitaButton.START, 0.50f, 0.07f, 56, 0.70f),
        TouchControl("btn_circle", "Volver", "button", VitaButton.CIRCLE, 0.59f, 0.07f, 44, 0.70f),
        TouchControl("igniculus", "Igniculus", "igniculus", VitaButton.NONE, 0.78f, 0.80f, 156, 0.25f, colorHex = "#7FB3D5"),
    )

    private fun cleanSixControlOldSizesExternal(): List<TouchControl> =
        cleanSixControlOldSizesDefault().map {
            if (it.id == "stick" || it.id == "btn_sword" || it.id == "btn_jump" || it.id == "btn_circle") it.copy(visible = false) else it
        }

    @Test
    fun migratesSavedOldSizeCleanDefaultToNewSizes() {
        // El caso del usuario: instalación limpia 0.2.55 con el
        // default de fábrica guardado. Cambiar defaultTouchLayout
        // solo NO le cambia el layout guardado: la migración lo
        // lleva a los valores nuevos (0.2.68, neón: el default
        // 0.2.67 entero —stick 174, zona 104, espada 86, salto 87,
        // pausa 52, círculo 41— se convierte al rombo de 10
        // controles con L/R, START, △□○✕ y orbe 101 dp), sin tocar
        // funciones (□ sigue SQUARE, ✕ CROSS, pausa START).
        val saved = ControlProfile("default-g99", "Por defecto G99", true, cleanSixControlOldSizesDefault())
        val migrated = TouchLayoutRepository.migrateBuiltInProfile(saved)
        assertEquals(defaultTouchLayout(), migrated.controls)
        assertEquals(177, control(migrated.controls, "stick").sizeDp)
        assertEquals(120, control(migrated.controls, "igniculus").sizeDp)
        assertEquals(60, control(migrated.controls, "btn_sq").sizeDp)
        assertEquals(60, control(migrated.controls, "btn_cross").sizeDp)
        assertEquals(50, control(migrated.controls, "btn_pause").sizeDp)
        assertEquals(60, control(migrated.controls, "btn_circ").sizeDp)
        assertEquals(0.890f to 0.496f, position(migrated.controls, "btn_sq"))
        assertEquals(0.959f to 0.613f, position(migrated.controls, "btn_cross"))
        assertEquals(VitaButton.SQUARE, control(migrated.controls, "btn_sq").vitaButton)
        assertEquals(VitaButton.CROSS, control(migrated.controls, "btn_cross").vitaButton)
        // Idempotente: una vez con los tamaños nuevos, no cambia.
        assertEquals(migrated, TouchLayoutRepository.migrateBuiltInProfile(migrated))
    }

    @Test
    fun migratesSavedOldSizeCleanOneHandAndExternalToNewSizes() {
        val oneHand = ControlProfile("one-hand", "Una mano", true, cleanSixControlOldSizesOneHand())
        assertEquals(oneHandLayout(), TouchLayoutRepository.migrateBuiltInProfile(oneHand).controls)
        val external = ControlProfile("external-gamepad", "Mando externo + táctil mínimo", true, cleanSixControlOldSizesExternal())
        assertEquals(externalGamepadLayout(), TouchLayoutRepository.migrateBuiltInProfile(external).controls)
        // En externo el stick sigue oculto y la zona sigue visible.
        val migratedExternal = TouchLayoutRepository.migrateBuiltInProfile(external)
        assertFalse(control(migratedExternal.controls, "stick").visible)
        assertTrue(control(migratedExternal.controls, "igniculus").visible)
    }

    @Test
    fun oldSizeCleanProfileEditedByUserKeepsItsOwnSizes() {
        // El usuario agrandó el stick a mano a 200 dp en su layout
        // limpio con tamaños viejos: el stick es suyo y NO se achica
        // (la pasada por control solo actúa sobre el valor exacto
        // de fábrica, 210 dp). La zona, que sigue en fábrica, sí se
        // achica por su lado: las pasadas son por control. 0.2.63:
        // la espada y el salto también siguen en fábrica (0.864 y
        // 0.93 con 82 dp) y la pasada por control los mueve a
        // 0.914/1.012 con 86/87 dp, como la zona.
        val resized = cleanSixControlOldSizesDefault().map {
            if (it.id == "stick") it.copy(sizeDp = 200) else it
        }
        val saved = ControlProfile("default-g99", "Por defecto G99", true, resized)
        assertEquals(saved, TouchLayoutRepository.migrateBuiltInToCleanLayout(saved))
        val migrated = TouchLayoutRepository.migrateBuiltInProfile(saved)
        assertEquals(200, control(migrated.controls, "stick").sizeDp)
        assertEquals(104, control(migrated.controls, "igniculus").sizeDp)
        assertEquals(0.914f to 0.576f, position(migrated.controls, "btn_sword"))
        assertEquals(86, control(migrated.controls, "btn_sword").sizeDp)
        assertEquals(1.012f to 0.752f, position(migrated.controls, "btn_jump"))
        assertEquals(87, control(migrated.controls, "btn_jump").sizeDp)
    }

    @Test
    fun oldSizeCleanCustomProfileIsNotResized() {
        // Un perfil personalizado con los tamaños viejos es del
        // usuario: no se toca por ninguna pasada.
        val custom = ControlProfile("custom-1", "Mío", false, cleanSixControlOldSizesDefault())
        assertEquals(custom, TouchLayoutRepository.migrateBuiltInToCleanLayout(custom))
        assertEquals(custom, TouchLayoutRepository.migrateBuiltInProfile(custom))
        assertEquals(custom, TouchLayoutRepository.migrateBuiltInFactoryValues(custom))
    }

    @Test
    fun factoryValuesPassShrinksOldSizeStickAndZoneOfEditedBuiltIn() {
        // El usuario movió la espada en su perfil predefinido con
        // tamaños viejos: el perfil entero ya no coincide con el
        // layout viejo, pero el stick y la zona siguen en fábrica y
        // la pasada por control los achica por separado.
        val edited = cleanSixControlOldSizesDefault().map {
            if (it.id == "btn_sword") it.copy(xFraction = 0.80f) else it
        }
        val saved = ControlProfile("default-g99", "Por defecto G99", true, edited)
        val migrated = TouchLayoutRepository.migrateBuiltInFactoryValues(saved)
        assertEquals(174, control(migrated.controls, "stick").sizeDp)
        assertEquals(104, control(migrated.controls, "igniculus").sizeDp)
        assertEquals(0.80f to 0.576f, position(migrated.controls, "btn_sword"))
        // Y la cadena completa hace lo mismo con el perfil entero.
        val chained = TouchLayoutRepository.migrateBuiltInProfile(saved)
        assertEquals(174, control(chained.controls, "stick").sizeDp)
        assertEquals(104, control(chained.controls, "igniculus").sizeDp)
        assertEquals(0.80f to 0.576f, position(chained.controls, "btn_sword"))
        // Si el usuario movió el stick, esa pasada no lo toca.
        val movedStick = cleanSixControlOldSizesDefault().map {
            if (it.id == "stick") it.copy(xFraction = 0.10f) else it
        }
        val savedMoved = ControlProfile("default-g99", "Por defecto G99", true, movedStick)
        val migratedMoved = TouchLayoutRepository.migrateBuiltInFactoryValues(savedMoved)
        assertEquals(210, control(migratedMoved.controls, "stick").sizeDp)
        assertEquals(104, control(migratedMoved.controls, "igniculus").sizeDp)
    }

    @Test
    fun defaultSizesMatchMockupProportionsOnPhone() {
        // Teléfono 2340x1080 a densidad 2.80 (1080x2340, ~385 ppi):
        // el rect del juego es 1906x1080 centrado (x 217-2123). Los
        // tamaños en dp tienen que rendir como los controles de la
        // imagen 52710_13_ua6f.png (0.2.68, neón): stick 177 dp
        // (0.212W), orbe dibujado 101 dp (zona 0.145W), botones de cara
        // 60 dp de cristal (huella 0.089W), pausa 50 dp. Ver el KDoc de
        // defaultTouchLayout, que documenta la geometría exacta.
        val controls = defaultTouchLayout()
        val stickRect = controlRect(control(controls, "stick"), 2340f, 1080f, 2.80f)
        val stickHeightPx = stickRect.bottom - stickRect.top
        assertEquals(495.6f, stickHeightPx, 1f)
        assertEquals(0.459f, stickHeightPx / 1080f, 0.01f)
        val zone = control(controls, "igniculus")
        val zoneRect = controlRect(zone, 2340f, 1080f, 2.80f)
        assertEquals(336.0f, zoneRect.bottom - zoneRect.top, 1f)
        // Orbe dibujado: 0.42 x ancho a cada lado = 0.84 x zona.
        // 0.2.68 segunda pasada: la zona es 120 dp y el orbe
        // dibujado es 101 dp (0.84 x 120); el bbox de la gota
        // (268 px = 121 dp) es la zona con su marco, no el orbe.
        val orbDrawnPx = 0.84f * zone.sizeDp * 2.80f
        assertEquals(282.2f, orbDrawnPx, 1f)
        assertEquals(0.261f, orbDrawnPx / 1080f, 0.01f)
        val pauseRect = controlRect(control(controls, "btn_pause"), 2340f, 1080f, 2.80f)
        val pauseHeightPx = pauseRect.bottom - pauseRect.top
        assertEquals(140.0f, pauseHeightPx, 1f)
        assertEquals(0.130f, pauseHeightPx / 1080f, 0.01f)
        // Proporciones entre controles, como en la imagen (cara
        // 0.34, orbe 0.68, pausa 0.28 del stick).
        val stick = control(controls, "stick").sizeDp.toFloat()
        assertEquals(0.34f, control(controls, "btn_sq").sizeDp / stick, 0.01f)
        assertEquals(0.34f, control(controls, "btn_cross").sizeDp / stick, 0.01f)
        assertEquals(0.68f, 0.84f * zone.sizeDp / stick, 0.01f)
        assertEquals(0.28f, control(controls, "btn_pause").sizeDp / stick, 0.01f)
        // Botones de cara dibujados: 60 dp x 2.80 = 168 px de
        // diámetro (15,6% de la altura, como en la imagen).
        val sqRect = controlRect(control(controls, "btn_sq"), 2340f, 1080f, 2.80f)
        assertEquals(168.0f, sqRect.bottom - sqRect.top, 1f)
        val crossRect = controlRect(control(controls, "btn_cross"), 2340f, 1080f, 2.80f)
        assertEquals(168.0f, crossRect.bottom - crossRect.top, 1f)
        // Píldoras: más anchas que altas (ancho = widthFraction x
        // alto del juego). L: 0.278 x 1080 = 300 px de ancho por
        // 51 dp x 2.80 = 143 px de alto; START: 0.346 x 1080 =
        // 374 px por 38 dp x 2.80 = 106 px.
        val lRect = controlRect(control(controls, "btn_l"), 2340f, 1080f, 2.80f)
        assertEquals(300.2f, lRect.right - lRect.left, 1f)
        assertEquals(142.8f, lRect.bottom - lRect.top, 1f)
        val startRect = controlRect(control(controls, "btn_start_pill"), 2340f, 1080f, 2.80f)
        assertEquals(373.7f, startRect.right - startRect.left, 1f)
        assertEquals(106.4f, startRect.bottom - startRect.top, 1f)
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
    fun editedFiveControlLayoutReturnedFieldByFieldIdentical() {
        // Fija campo por campo que un limpio de 5 controles
        // editado por el usuario vuelve intacto por la cadena
        // completa: la pasada 0.2.57 por control solo achica el
        // stick (210 -> 174) y la zona (156 -> 104) si el perfil
        // ya trae btn_circle, y este no lo trae (pre-círculo de
        // 0.2.54). Regresión del run #231.
        val edited = cleanFiveControlDefault().map {
            if (it.id == "btn_sword") it.copy(xFraction = 0.80f) else it
        }
        val saved = ControlProfile("default-g99", "Por defecto G99", true, edited)
        val migrated = TouchLayoutRepository.migrateBuiltInProfile(saved)
        assertEquals(saved, migrated)
        assertEquals(210, control(migrated.controls, "stick").sizeDp)
        assertEquals(156, control(migrated.controls, "igniculus").sizeDp)
        assertEquals(0.80f to 0.576f, position(migrated.controls, "btn_sword"))
        assertEquals(0.05f to 0.675f, position(migrated.controls, "stick"))
        assertEquals(0.82f to 0.808f, position(migrated.controls, "igniculus"))
        assertFalse(migrated.controls.any { it.id == "btn_circle" })
        assertEquals(5, migrated.controls.size)
    }

    @Test
    fun circleButtonUsesGlyphAndRipplePath() {
        // 0.2.68 (neón): btn_circ es el ○ del rombo, un botón
        // normal (kind "button"): en el juego pasa por ButtonView,
        // que dispara la gota en ACTION_DOWN y dibuja el glifo ○
        // (faceGlyphFor CIRCLE -> GLYPH_CIRCLE), sin icono propio
        // por id (no es espada ni flecha).
        val controls = defaultTouchLayout()
        assertEquals("button", control(controls, "btn_circ").kind)
        assertNull(TouchOverlayMapping.iconOverrideFor("btn_circ"))
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
        // 0.2.68 (neón): el default lleva 10 controles; la zona
        // sigue detrás y el resto mantiene el orden de la lista.
        val default = TouchOverlayMapping.overlayChildOrder(defaultTouchLayout())
        assertEquals("igniculus", default.first().id)
        assertEquals(listOf("stick", "btn_pause", "btn_l", "btn_r", "btn_start_pill", "btn_tri", "btn_sq", "btn_circ", "btn_cross"), default.drop(1).map { it.id })
        // Los ocultos no aparecen.
        val hidden = TouchOverlayMapping.overlayChildOrder(externalGamepadLayout())
        assertEquals(listOf("igniculus", "btn_pause"), hidden.map { it.id })
    }

    // --- Migración 0.2.58 (R7-P1-04/R7-P2-04): posiciones y tamaños nuevos ---

    // Layout limpio de 6 controles con los valores de 0.2.57
    // (espada y salto en 82 dp en (0.864, 0.576) y (0.93, 0.752)):
    // la forma en la que queda guardado un perfil predefinido de
    // una instalación 0.2.57. Literales (no derivados del layout
    // nuevo): el test fija la entrada de forma independiente.
    private fun cleanSixControl057Default(): List<TouchControl> = listOf(
        TouchControl("stick", "Stick", "stick", VitaButton.LEFT_STICK, 0.05f, 0.675f, 186, 0.70f),
        TouchControl("btn_sword", "Espada", "button", VitaButton.SQUARE, 0.864f, 0.576f, 82, 0.80f),
        TouchControl("btn_jump", "Salto", "button", VitaButton.CROSS, 0.93f, 0.752f, 82, 0.80f),
        TouchControl("btn_start", "Start", "button", VitaButton.START, 0.50f, 0.07f, 56, 0.70f),
        TouchControl("btn_circle", "Volver", "button", VitaButton.CIRCLE, 0.59f, 0.07f, 44, 0.70f),
        TouchControl("igniculus", "Igniculus", "igniculus", VitaButton.NONE, 0.82f, 0.808f, 115, 0.25f, colorHex = "#7FB3D5"),
    )

    private fun cleanSixControl057External(): List<TouchControl> =
        cleanSixControl057Default().map {
            if (it.id == "stick" || it.id == "btn_sword" || it.id == "btn_jump" || it.id == "btn_circle") it.copy(visible = false) else it
        }

    @Test
    fun migratesSaved057DefaultToNewPositionsAndSizes() {
        // El caso del usuario: instalación 0.2.57 con el default
        // de fábrica guardado. La migración lo lleva al default
        // 0.2.68 entero (neón: rombo de 10 controles; □ 60 dp en
        // (0.890, 0.496), ✕ 60 dp en (0.959, 0.613), stick 177,
        // zona 120, pausa 50).
        val saved = ControlProfile("default-g99", "Por defecto G99", true, cleanSixControl057Default())
        val migrated = TouchLayoutRepository.migrateBuiltInProfile(saved)
        assertEquals(defaultTouchLayout(), migrated.controls)
        assertEquals(0.890f to 0.496f, position(migrated.controls, "btn_sq"))
        assertEquals(0.959f to 0.613f, position(migrated.controls, "btn_cross"))
        assertEquals(60, control(migrated.controls, "btn_sq").sizeDp)
        assertEquals(60, control(migrated.controls, "btn_cross").sizeDp)
        assertEquals(177, control(migrated.controls, "stick").sizeDp)
        assertEquals(120, control(migrated.controls, "igniculus").sizeDp)
        // Idempotente: una vez con los valores nuevos, no cambia.
        assertEquals(migrated, TouchLayoutRepository.migrateBuiltInProfile(migrated))
    }

    @Test
    fun migratesSaved057ExternalAndLeavesOneHandAlone() {
        // Externo 0.2.57: su espada/salto ocultos espejan el
        // default y también migran (0.914/1.012, 86/87 dp).
        val external = ControlProfile("external-gamepad", "Mando externo + táctil mínimo", true, cleanSixControl057External())
        assertEquals(externalGamepadLayout(), TouchLayoutRepository.migrateBuiltInProfile(external).controls)
        // 0.2.68 (neón): el externo ya no lleva btn_sword/btn_jump:
        // el □ y la ✕ del rombo quedan ocultos, como antes.
        assertFalse(control(externalGamepadLayout(), "btn_sq").visible)
        assertFalse(control(externalGamepadLayout(), "btn_cross").visible)
        // 0.2.68 segunda pasada: una mano ya neón no cambia (la
        // migración es idempotente); el □ y la ✕ quedan en 60 dp
        // EXACTAMENTE donde estaban espada y salto (77 dp en
        // 0.2.67), y un perfil guardado en 0.2.57 (82 dp) migra
        // por la pasada de lista a los nuevos (ver
        // migratesSavedOldSizeCleanOneHandAndExternalToNewSizes).
        val oneHand = ControlProfile("one-hand", "Una mano", true, oneHandLayout())
        assertEquals(oneHand, TouchLayoutRepository.migrateBuiltInProfile(oneHand))
        assertEquals(60, control(oneHandLayout(), "btn_sq").sizeDp)
        assertEquals(60, control(oneHandLayout(), "btn_cross").sizeDp)
        assertEquals(0.83f to 0.63f, position(oneHandLayout(), "btn_sq"))
        assertEquals(0.91f to 0.79f, position(oneHandLayout(), "btn_cross"))
    }

    @Test
    fun migratesSaved057CustomProfileIsNotTouched() {
        val custom = ControlProfile("custom-1", "Mío", false, cleanSixControl057Default())
        assertEquals(custom, TouchLayoutRepository.migrateBuiltInToCleanLayout(custom))
        assertEquals(custom, TouchLayoutRepository.migrateBuiltInProfile(custom))
        assertEquals(custom, TouchLayoutRepository.migrateBuiltInFactoryValues(custom))
    }

    @Test
    fun factoryValuesPassMoves057SwordAndJumpOfEditedBuiltIn() {
        // El usuario movió el stick en su perfil 0.2.57: el perfil
        // entero ya no coincide con el layout viejo, pero espada y
        // salto siguen en fábrica y la pasada por control los mueve
        // por separado. El stick movido queda donde lo dejó.
        val edited = cleanSixControl057Default().map {
            if (it.id == "stick") it.copy(xFraction = 0.10f) else it
        }
        val saved = ControlProfile("default-g99", "Por defecto G99", true, edited)
        val migrated = TouchLayoutRepository.migrateBuiltInFactoryValues(saved)
        assertEquals(0.10f to 0.675f, position(migrated.controls, "stick"))
        assertEquals(0.914f to 0.576f, position(migrated.controls, "btn_sword"))
        assertEquals(86, control(migrated.controls, "btn_sword").sizeDp)
        assertEquals(1.012f to 0.752f, position(migrated.controls, "btn_jump"))
        assertEquals(87, control(migrated.controls, "btn_jump").sizeDp)
        // Y la cadena completa hace lo mismo con el perfil entero.
        val chained = TouchLayoutRepository.migrateBuiltInProfile(saved)
        assertEquals(0.10f to 0.675f, position(chained.controls, "stick"))
        assertEquals(0.914f to 0.576f, position(chained.controls, "btn_sword"))
        assertEquals(1.012f to 0.752f, position(chained.controls, "btn_jump"))
        // Si el usuario movió la espada, esa pasada no la toca...
        val movedSword = cleanSixControl057Default().map {
            if (it.id == "btn_sword") it.copy(xFraction = 0.80f) else it
        }
        val savedMoved = ControlProfile("default-g99", "Por defecto G99", true, movedSword)
        val migratedMoved = TouchLayoutRepository.migrateBuiltInFactoryValues(savedMoved)
        assertEquals(0.80f to 0.576f, position(migratedMoved.controls, "btn_sword"))
        assertEquals(82, control(migratedMoved.controls, "btn_sword").sizeDp)
        // ...pero el salto, que sigue en fábrica, sí se mueve.
        assertEquals(1.012f to 0.752f, position(migratedMoved.controls, "btn_jump"))
        assertEquals(87, control(migratedMoved.controls, "btn_jump").sizeDp)
        // Y si el usuario movió el salto, el salto queda y la
        // espada sí se mueve.
        val movedJump = cleanSixControl057Default().map {
            if (it.id == "btn_jump") it.copy(xFraction = 0.88f) else it
        }
        val savedJump = ControlProfile("default-g99", "Por defecto G99", true, movedJump)
        val migratedJump = TouchLayoutRepository.migrateBuiltInFactoryValues(savedJump)
        assertEquals(0.88f to 0.752f, position(migratedJump.controls, "btn_jump"))
        assertEquals(82, control(migratedJump.controls, "btn_jump").sizeDp)
        assertEquals(0.914f to 0.576f, position(migratedJump.controls, "btn_sword"))
    }

    // --- Huecos verificados en el teléfono (R7-P1-04/R7-P1-05) ---

    private fun centerDistance(a: ControlRect, b: ControlRect): Float {
        val ax = (a.left + a.right) / 2f
        val ay = (a.top + a.bottom) / 2f
        val bx = (b.left + b.right) / 2f
        val by = (b.top + b.bottom) / 2f
        return sqrt((ax - bx) * (ax - bx) + (ay - by) * (ay - by))
    }

    @Test
    fun stickOverhangsLeftBlackBarOnPhone() {
        // Teléfono 2340x1080, densidad 2.80 (0.2.63, A-1: la 0.2.58
        // usaba 1600x720 a 1.75, la del Galaxy A07): el stick
        // guardado en 0.035 queda con el centro en 284 px y el
        // borde en 36 px, 181 px dentro de la barra negra; su
        // centro sigue dentro del rect del juego y no se sale por
        // la derecha ni por arriba/abajo.
        val game = gameRect(2340f, 1080f)
        assertEquals(217.1f, game.left, 1f)
        assertEquals(1905.9f, game.right - game.left, 1f)
        val stickRect = controlRect(control(defaultTouchLayout(), "stick"), 2340f, 1080f, 2.80f)
        assertEquals(36.0f, stickRect.left, 1f)
        assertEquals(283.8f, (stickRect.left + stickRect.right) / 2f, 1f)
        assertTrue("el stick pisa la barra negra", stickRect.left < game.left - 0.01f)
        assertTrue("centro del stick dentro del juego", (stickRect.left + stickRect.right) / 2f >= game.left - 0.01f)
        assertTrue("stick dentro por la derecha", stickRect.right <= game.right + 0.01f)
        assertTrue("stick dentro vertical", stickRect.top >= game.top - 0.01f && stickRect.bottom <= game.bottom + 0.01f)
        // 0.2.68 (neón): ya NO solo el stick pisa la barra: L
        // pisa la izquierda con el centro fuera (-0.012), y △, ○,
        // ✕ y R pisan la derecha (○ en 1.028, R en 1.016). Solo
        // pausa, □, píldora START e Igniculus quedan ENTEROS
        // dentro del rect; todos, eso sí, dentro de la vista.
        for (c in defaultTouchLayout()) {
            val rect = controlRect(c, 2340f, 1080f, 2.80f)
            assertTrue("rect de ${c.id} dentro de la vista", rect.left >= -0.01f && rect.top >= -0.01f)
            assertTrue("rect de ${c.id} dentro de la vista", rect.right <= 2340f + 0.01f && rect.bottom <= 1080f + 0.01f)
            if (c.id == "btn_pause" || c.id == "btn_sq" || c.id == "btn_start_pill" || c.id == "igniculus") {
                assertTrue("rect de ${c.id} dentro del juego", rect.left >= game.left - 0.01f && rect.top >= game.top - 0.01f)
                assertTrue("rect de ${c.id} dentro del juego", rect.right <= game.right + 0.01f && rect.bottom <= game.bottom + 0.01f)
            }
        }
        // L y ○ tienen el centro fuera del rect, sobre la barra.
        val lRect = controlRect(control(defaultTouchLayout(), "btn_l"), 2340f, 1080f, 2.80f)
        assertEquals(-0.012f, ((lRect.left + lRect.right) / 2f - game.left) / (game.right - game.left), 0.001f)
        val circRect = controlRect(control(defaultTouchLayout(), "btn_circ"), 2340f, 1080f, 2.80f)
        assertEquals(1.028f, ((circRect.left + circRect.right) / 2f - game.left) / (game.right - game.left), 0.001f)
        assertTrue("el círculo no se sale de la vista", circRect.right <= 2340f + 0.01f)
    }

    @Test
    fun defaultLayoutGapsOnPhone() {
        // Teléfono 2340x1080, densidad 2.80 (0.2.63, A-1: la 0.2.58
        // usaba 1600x720 a 1.75, la del Galaxy A07): rect del juego
        // 1905,9x1080, izquierda 217,1. Huecos DIBUJADOS (círculos
        // de cristal: botones de cara 60 dp = 168 px de diámetro,
        // orbe 101 dp = 282 px, 0.84 x zona de 120 dp). Positivo =
        // hueco entre cristales. 0.2.68 (neón): los botones de cara
        // NO se tocan (hueco de 14-17 px entre vecinos, centros a
        // 184 px con Ø 168 px): ES el diseño de la imagen — en la
        // imagen la diagonal entre centros es oscura. El 0.089W
        // medido por umbral es la huella con adornos, no el cristal.
        // El borde derecho del stick queda a ~2 px de la píldora
        // START (también en la imagen). Ver el KDoc de
        // defaultTouchLayout. Este test fija la geometría medida
        // para que ningún cambio futuro la mueva en silencio.
        val controls = defaultTouchLayout()
        val triRect = controlRect(control(controls, "btn_tri"), 2340f, 1080f, 2.80f)
        val sqRect = controlRect(control(controls, "btn_sq"), 2340f, 1080f, 2.80f)
        val circRect = controlRect(control(controls, "btn_circ"), 2340f, 1080f, 2.80f)
        val crossRect = controlRect(control(controls, "btn_cross"), 2340f, 1080f, 2.80f)
        val zoneRect = controlRect(control(controls, "igniculus"), 2340f, 1080f, 2.80f)
        // Centros dibujados.
        assertEquals(2044.8f, (triRect.left + triRect.right) / 2f, 1f)
        assertEquals(406.1f, (triRect.top + triRect.bottom) / 2f, 1f)
        assertEquals(1913.3f, (sqRect.left + sqRect.right) / 2f, 1f)
        assertEquals(535.7f, (sqRect.top + sqRect.bottom) / 2f, 1f)
        assertEquals(2176.3f, (circRect.left + circRect.right) / 2f, 1f)
        assertEquals(535.7f, (circRect.top + circRect.bottom) / 2f, 1f)
        assertEquals(2044.8f, (crossRect.left + crossRect.right) / 2f, 1f)
        assertEquals(662.0f, (crossRect.top + crossRect.bottom) / 2f, 1f)
        assertEquals(1705.6f, (zoneRect.left + zoneRect.right) / 2f, 1f)
        assertEquals(878.0f, (zoneRect.top + zoneRect.bottom) / 2f, 1f)
        // El círculo dibujado queda en x 1.028 del juego, fuera del
        // rect, con el borde derecho 80 px dentro de la vista
        // (2340 px) y 137 px dentro de la barra negra.
        val game = gameRect(2340f, 1080f)
        assertEquals(1.028f, ((circRect.left + circRect.right) / 2f - game.left) / (game.right - game.left), 0.001f)
        assertTrue("el círculo pisa la barra negra", circRect.right > game.right + 0.01f)
        assertTrue("el círculo no se sale de la vista", circRect.right <= 2340f + 0.01f)
        // Huecos dibujados: distancia entre centros menos radios
        // (cara 84.0 px). Los vecinos del rombo en POSITIVO: hueco
        // de 14-17 px, como en la imagen; las diagonales (△-✕, □-○)
        // quedan en positivo con más margen.
        assertEquals(16.6f, centerDistance(triRect, sqRect) - 84.0f - 84.0f, 1f)
        assertEquals(14.3f, centerDistance(sqRect, crossRect) - 84.0f - 84.0f, 1f)
        assertEquals(87.9f, centerDistance(triRect, crossRect) - 84.0f - 84.0f, 1f)
        assertEquals(95.0f, centerDistance(sqRect, circRect) - 84.0f - 84.0f, 1f)
        // Stick y píldora START: hueco de ~2 px entre bordes.
        val stickRect = controlRect(control(controls, "stick"), 2340f, 1080f, 2.80f)
        val startRect = controlRect(control(controls, "btn_start_pill"), 2340f, 1080f, 2.80f)
        assertEquals(1.8f, startRect.left - stickRect.right, 1f)
        // Cajas táctiles cruz-orbe: la cruz (✕) y la zona se
        // solapan 36 px en Y pero NO en X (hueco de 87 px), así
        // que no hay solape táctil.
        val touchOverlapX = minOf(crossRect.right, zoneRect.right) - maxOf(crossRect.left, zoneRect.left)
        val touchOverlapY = minOf(crossRect.bottom, zoneRect.bottom) - maxOf(crossRect.top, zoneRect.top)
        assertEquals(-87.2f, touchOverlapX, 1f)
        assertEquals(36.0f, touchOverlapY, 1f)
    }
}
