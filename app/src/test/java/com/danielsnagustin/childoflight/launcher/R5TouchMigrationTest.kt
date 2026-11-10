package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.ControlProfile
import com.danielsnagustin.childoflight.launcher.data.TouchControl
import com.danielsnagustin.childoflight.launcher.data.TouchLayoutRepository
import com.danielsnagustin.childoflight.launcher.data.VitaButton
import com.danielsnagustin.childoflight.launcher.data.defaultTouchLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests R5-A1-1, R5-A1-2 y R5-A1-7 (2026-11-09): la segunda pasada
 * de la migración de perfiles predefinidos guardados (stick e
 * Igniculus a los valores de fábrica actuales, solo si siguen en el
 * valor viejo de fábrica) y la normalización de colores guardados
 * fuera de paleta al decodificar. Todo puro JVM, sin Context ni
 * Compose: las funciones viven en el companion de
 * TouchLayoutRepository y no tocan DataStore.
 *
 * Guarda "solo si es valor de fábrica": si el usuario movió o
 * redimensionó el stick o la Igniculus, son suyos y no se tocan.
 * La pasada de fábrica y la del rombo son independientes: cada una
 * juzga sus controles por separado.
 */
class R5TouchMigrationTest {

    private fun position(controls: List<TouchControl>, id: String): Pair<Float, Float> {
        val control = controls.first { it.id == id }
        return control.xFraction to control.yFraction
    }

    private fun stickSize(controls: List<TouchControl>): Int =
        controls.first { it.id == "stick" }.sizeDp

    // Layout por defecto tal como se guardó en 0.2.49: rombo
    // transpuesto, stick 140 dp, Igniculus en y 0.35.
    private fun oldDefaultLayout(): List<TouchControl> = listOf(
        TouchControl("stick", "Stick", "stick", VitaButton.LEFT_STICK, 0.18f, 0.72f, 140, 0.70f),
        TouchControl("btn_y", "Y", "button", VitaButton.TRIANGLE, 0.70f, 0.70f, 64, 0.80f),
        TouchControl("btn_x", "X", "button", VitaButton.SQUARE, 0.78f, 0.62f, 64, 0.80f),
        TouchControl("btn_b", "B", "button", VitaButton.CIRCLE, 0.78f, 0.80f, 64, 0.80f),
        TouchControl("btn_a", "A", "button", VitaButton.CROSS, 0.87f, 0.71f, 64, 0.85f),
        TouchControl("btn_start", "Start", "button", VitaButton.START, 0.50f, 0.06f, 56, 0.70f),
        TouchControl("igniculus", "Igniculus", "igniculus", VitaButton.NONE, 0.50f, 0.35f, 220, 0.25f, colorHex = "#7FB3D5"),
    )

    // "Una mano" tal como se guardó en 0.2.49: stick 120 dp,
    // Igniculus en (0.55, 0.28).
    private fun oldOneHandLayout(): List<TouchControl> = listOf(
        TouchControl("stick", "Stick", "stick", VitaButton.LEFT_STICK, 0.20f, 0.75f, 120, 0.75f),
        TouchControl("btn_y", "Y", "button", VitaButton.TRIANGLE, 0.70f, 0.70f, 64, 0.80f, visible = false),
        TouchControl("btn_x", "X", "button", VitaButton.SQUARE, 0.78f, 0.62f, 64, 0.80f, visible = false),
        TouchControl("btn_b", "B", "button", VitaButton.CIRCLE, 0.72f, 0.87f, 64, 0.80f),
        TouchControl("btn_a", "A", "button", VitaButton.CROSS, 0.84f, 0.78f, 72, 0.85f),
        TouchControl("btn_start", "Start", "button", VitaButton.START, 0.50f, 0.06f, 56, 0.70f, visible = false),
        TouchControl("igniculus", "Igniculus", "igniculus", VitaButton.NONE, 0.55f, 0.28f, 160, 0.25f, colorHex = "#7FB3D5"),
    )

    // --- R5-A1-1: la pasada de fábrica normaliza los valores viejos ---

    @Test
    fun factoryValuesMigrateDefaultStickAndIgniculus() {
        val saved = ControlProfile("default-g99", "Por defecto G99", true, oldDefaultLayout())
        val migrated = TouchLayoutRepository.migrateBuiltInFactoryValues(saved)
        assertEquals(104, stickSize(migrated.controls))
        assertEquals(0.18f to 0.72f, position(migrated.controls, "stick"))
        assertEquals(0.50f to 0.55f, position(migrated.controls, "igniculus"))
    }

    @Test
    fun factoryValuesMigrateOneHandStickAndIgniculus() {
        val saved = ControlProfile("one-hand", "Una mano", true, oldOneHandLayout())
        val migrated = TouchLayoutRepository.migrateBuiltInFactoryValues(saved)
        assertEquals(104, stickSize(migrated.controls))
        assertEquals(0.20f to 0.75f, position(migrated.controls, "stick"))
        assertEquals(0.50f to 0.50f, position(migrated.controls, "igniculus"))
    }

    @Test
    fun factoryValuesMigrateExternalStickAndIgniculus() {
        val saved = ControlProfile(
            "external-gamepad", "Mando externo + táctil mínimo", true,
            oldDefaultLayout().map {
                if (it.id == "stick") it.copy(visible = false)
                else if (it.kind == "button" && it.id != "btn_start") it.copy(visible = false)
                else it
            },
        )
        val migrated = TouchLayoutRepository.migrateBuiltInFactoryValues(saved)
        assertEquals(104, stickSize(migrated.controls))
        assertEquals(0.50f to 0.55f, position(migrated.controls, "igniculus"))
    }

    // --- R5-A1-1: guarda "solo si es valor de fábrica" ---

    @Test
    fun factoryValuesLeaveUserResizedStickAlone() {
        // El usuario puso el stick en 130 dp: no es el valor de
        // fábrica (140/120), así que no se toca.
        val edited = oldDefaultLayout().map {
            if (it.id == "stick") it.copy(sizeDp = 130) else it
        }
        val saved = ControlProfile("default-g99", "Por defecto G99", true, edited)
        val migrated = TouchLayoutRepository.migrateBuiltInProfile(saved)
        assertEquals(130, stickSize(migrated.controls))
        // La Igniculus sí seguía en fábrica y sí migra.
        assertEquals(0.50f to 0.55f, position(migrated.controls, "igniculus"))
    }

    @Test
    fun factoryValuesLeaveUserMovedStickAlone() {
        // El usuario movió el stick (mismo tamaño 140, otra
        // posición): es suyo y no se toca.
        val edited = oldDefaultLayout().map {
            if (it.id == "stick") it.copy(xFraction = 0.25f) else it
        }
        val saved = ControlProfile("default-g99", "Por defecto G99", true, edited)
        val migrated = TouchLayoutRepository.migrateBuiltInProfile(saved)
        assertEquals(140, stickSize(migrated.controls))
        assertEquals(0.25f to 0.72f, position(migrated.controls, "stick"))
    }

    @Test
    fun factoryValuesLeaveUserMovedIgniculusAlone() {
        // El usuario subió la Igniculus a y 0.45: no es el valor de
        // fábrica (0.35), así que no se toca.
        val edited = oldDefaultLayout().map {
            if (it.id == "igniculus") it.copy(yFraction = 0.45f) else it
        }
        val saved = ControlProfile("default-g99", "Por defecto G99", true, edited)
        val migrated = TouchLayoutRepository.migrateBuiltInProfile(saved)
        assertEquals(0.50f to 0.45f, position(migrated.controls, "igniculus"))
        // El stick sí seguía en fábrica y sí migra.
        assertEquals(104, stickSize(migrated.controls))
    }

    @Test
    fun factoryValuesLeaveOneHandUserMovedStickAlone() {
        // Una mano: stick movido con su tamaño viejo (120) tampoco
        // se toca.
        val edited = oldOneHandLayout().map {
            if (it.id == "stick") it.copy(xFraction = 0.25f) else it
        }
        val saved = ControlProfile("one-hand", "Una mano", true, edited)
        val migrated = TouchLayoutRepository.migrateBuiltInProfile(saved)
        assertEquals(120, stickSize(migrated.controls))
        assertEquals(0.25f to 0.75f, position(migrated.controls, "stick"))
    }

    @Test
    fun factoryValuesLeaveCustomProfilesAlone() {
        val custom = ControlProfile("custom-1", "Mío", false, oldDefaultLayout())
        assertEquals(custom, TouchLayoutRepository.migrateBuiltInFactoryValues(custom))
        assertEquals(custom, TouchLayoutRepository.migrateBuiltInProfile(custom))
    }

    // --- Las dos pasadas son independientes ---

    @Test
    fun diamondPassAloneDoesNotTouchStickOrIgniculus() {
        val saved = ControlProfile("default-g99", "Por defecto G99", true, oldDefaultLayout())
        val migrated = TouchLayoutRepository.migrateBuiltInFaceDiamond(saved)
        assertEquals(140, stickSize(migrated.controls))
        assertEquals(0.50f to 0.35f, position(migrated.controls, "igniculus"))
    }

    @Test
    fun factoryPassAloneDoesNotMoveTheDiamond() {
        val saved = ControlProfile("default-g99", "Por defecto G99", true, oldDefaultLayout())
        val migrated = TouchLayoutRepository.migrateBuiltInFactoryValues(saved)
        // Rombo transpuesto intacto: TRIANGLE seguía a la izquierda.
        assertEquals(0.70f to 0.70f, position(migrated.controls, "btn_y"))
        assertEquals(0.87f to 0.71f, position(migrated.controls, "btn_a"))
    }

    @Test
    fun editedDiamondDoesNotBlockFactoryPass() {
        // El usuario movió un botón de cara: el rombo no migra,
        // pero el stick y la Igniculus (en fábrica) sí.
        val edited = oldDefaultLayout().map {
            if (it.id == "btn_a") it.copy(xFraction = 0.85f) else it
        }
        val saved = ControlProfile("default-g99", "Por defecto G99", true, edited)
        val migrated = TouchLayoutRepository.migrateBuiltInProfile(saved)
        assertEquals(0.85f to 0.80f, position(migrated.controls, "btn_a"))
        assertEquals(0.70f to 0.70f, position(migrated.controls, "btn_y"))
        assertEquals(104, stickSize(migrated.controls))
        assertEquals(0.50f to 0.55f, position(migrated.controls, "igniculus"))
    }

    @Test
    fun fullMigrationIsIdempotentAndFreshIsUntouched() {
        val saved = ControlProfile("default-g99", "Por defecto G99", true, oldDefaultLayout())
        val once = TouchLayoutRepository.migrateBuiltInProfile(saved)
        assertEquals(once, TouchLayoutRepository.migrateBuiltInProfile(once))
        assertEquals(defaultTouchLayout(), once.controls)
        val fresh = ControlProfile("default-g99", "Por defecto G99", true, defaultTouchLayout())
        assertEquals(fresh, TouchLayoutRepository.migrateBuiltInProfile(fresh))
    }

    // --- R5-A1-7: colores guardados fuera de paleta se normalizan al leer ---

    @Test
    fun decodeNormalizesOldGreenToTurquoise() {
        val raw = "btn_a|A|button|CROSS|0.8|0.8|64|0.85|#4CAF50|CIRCLE|true|ANALOG|DIRECT_TOUCH|1.0"
        val decoded = TouchLayoutRepository.decodeControl(raw)
        assertNotNull(decoded)
        assertEquals("#75D6CE", decoded!!.colorHex)
    }

    @Test
    fun decodeNormalizesOldRedToLavenderCaseInsensitive() {
        val raw = "btn_a|A|button|CROSS|0.8|0.8|64|0.85|#e57373|CIRCLE|true|ANALOG|DIRECT_TOUCH|1.0"
        val decoded = TouchLayoutRepository.decodeControl(raw)
        assertNotNull(decoded)
        assertEquals("#A99ACD", decoded!!.colorHex)
        // La variante en minúscula del verde también se normaliza.
        val greenLower = "btn_a|A|button|CROSS|0.8|0.8|64|0.85|#4caf50|CIRCLE|true|ANALOG|DIRECT_TOUCH|1.0"
        assertEquals("#75D6CE", TouchLayoutRepository.decodeControl(greenLower)!!.colorHex)
    }

    @Test
    fun decodeLeavesIdentityColorsAlone() {
        for (hex in listOf("#8CCBEB", "#6578B8", "#A99ACD", "#75D6CE", "#DCE8F5", "#7FB3D5", "#FFFFFF")) {
            val raw = "btn_a|A|button|CROSS|0.8|0.8|64|0.85|$hex|CIRCLE|true|ANALOG|DIRECT_TOUCH|1.0"
            assertEquals(hex, TouchLayoutRepository.decodeControl(raw)!!.colorHex)
        }
        // normalizePaletteColor directo: cualquier otro hex pasa tal cual,
        // y los dos viejos se mapean (case-insensitive).
        assertEquals("#123456", TouchLayoutRepository.normalizePaletteColor("#123456"))
        assertEquals("#75D6CE", TouchLayoutRepository.normalizePaletteColor("#4CAF50"))
        assertEquals("#A99ACD", TouchLayoutRepository.normalizePaletteColor("#e57373"))
    }

    @Test
    fun decodeProfileNormalizesOldColorsOnRoundTrip() {
        // Un perfil guardado con verde/rojo viejos se lee ya con los
        // colores de la identidad, sin tocar nada más.
        val saved = ControlProfile(
            "custom-1", "Mío", false, listOf(
                TouchControl("btn_a", "A", "button", VitaButton.CROSS, 0.80f, 0.80f, 64, 0.85f, colorHex = "#4CAF50"),
                TouchControl("btn_b", "B", "button", VitaButton.CIRCLE, 0.87f, 0.71f, 64, 0.80f, colorHex = "#E57373"),
                TouchControl("btn_x", "X", "button", VitaButton.SQUARE, 0.73f, 0.71f, 64, 0.80f, colorHex = "#8CCBEB"),
            ),
        )
        val decoded = TouchLayoutRepository.decodeProfile(TouchLayoutRepository.encodeProfile(saved))
        assertNotNull(decoded)
        assertEquals("#75D6CE", decoded!!.controls.first { it.id == "btn_a" }.colorHex)
        assertEquals("#A99ACD", decoded.controls.first { it.id == "btn_b" }.colorHex)
        assertEquals("#8CCBEB", decoded.controls.first { it.id == "btn_x" }.colorHex)
        assertTrue(decoded.controls.first { it.id == "btn_a" }.xFraction == 0.80f)
    }
}
