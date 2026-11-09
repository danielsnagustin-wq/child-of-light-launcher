package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.ControlProfile
import com.danielsnagustin.childoflight.launcher.data.TouchControl
import com.danielsnagustin.childoflight.launcher.data.TouchLayoutRepository
import com.danielsnagustin.childoflight.launcher.data.VitaButton
import com.danielsnagustin.childoflight.launcher.data.builtInProfiles
import com.danielsnagustin.childoflight.launcher.data.defaultTouchLayout
import com.danielsnagustin.childoflight.launcher.data.externalGamepadLayout
import com.danielsnagustin.childoflight.launcher.data.oneHandLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests R4-P0-1 (2026-11-09): el rombo estándar de botones de cara
 * y la migración de los perfiles predefinidos ya guardados con el
 * rombo transpuesto. Todo puro JVM, sin Context ni Compose: las
 * funciones de migración viven en el companion de
 * TouchLayoutRepository y no tocan DataStore.
 *
 * Rombo estándar, centrado en (0.80, 0.71): TRIANGLE arriba,
 * CIRCLE derecha, CROSS abajo, SQUARE izquierda. Rombo antiguo
 * (transpuesto): TRIANGLE izquierda, SQUARE arriba, CIRCLE abajo,
 * CROSS derecha.
 */
class TouchLayoutDiamondMigrationTest {

    private fun facePosition(controls: List<TouchControl>, id: String): Pair<Float, Float> {
        val control = controls.first { it.id == id }
        return control.xFraction to control.yFraction
    }

    // Layout por defecto tal como se guardó en 0.2.49 (transpuesto).
    private fun oldDefaultLayout(): List<TouchControl> = listOf(
        TouchControl("stick", "Stick", "stick", VitaButton.LEFT_STICK, 0.18f, 0.72f, 140, 0.70f),
        TouchControl("btn_y", "Y", "button", VitaButton.TRIANGLE, 0.70f, 0.70f, 64, 0.80f),
        TouchControl("btn_x", "X", "button", VitaButton.SQUARE, 0.78f, 0.62f, 64, 0.80f),
        TouchControl("btn_b", "B", "button", VitaButton.CIRCLE, 0.78f, 0.80f, 64, 0.80f),
        TouchControl("btn_a", "A", "button", VitaButton.CROSS, 0.87f, 0.71f, 64, 0.85f),
        TouchControl("btn_start", "Start", "button", VitaButton.START, 0.50f, 0.06f, 56, 0.70f),
        TouchControl("igniculus", "Igniculus", "igniculus", VitaButton.NONE, 0.50f, 0.35f, 220, 0.25f, colorHex = "#7FB3D5"),
    )

    // "Una mano" tal como se guardó en 0.2.49 (misma transposición).
    private fun oldOneHandLayout(): List<TouchControl> = listOf(
        TouchControl("stick", "Stick", "stick", VitaButton.LEFT_STICK, 0.20f, 0.75f, 120, 0.75f),
        TouchControl("btn_y", "Y", "button", VitaButton.TRIANGLE, 0.70f, 0.70f, 64, 0.80f, visible = false),
        TouchControl("btn_x", "X", "button", VitaButton.SQUARE, 0.78f, 0.62f, 64, 0.80f, visible = false),
        TouchControl("btn_b", "B", "button", VitaButton.CIRCLE, 0.72f, 0.87f, 64, 0.80f),
        TouchControl("btn_a", "A", "button", VitaButton.CROSS, 0.84f, 0.78f, 72, 0.85f),
        TouchControl("btn_start", "Start", "button", VitaButton.START, 0.50f, 0.06f, 56, 0.70f, visible = false),
        TouchControl("igniculus", "Igniculus", "igniculus", VitaButton.NONE, 0.55f, 0.28f, 160, 0.25f, colorHex = "#7FB3D5"),
    )

    // "Mando externo" tal como se guardó en 0.2.49 (transpuesto, con
    // stick y botones de cara ocultos, Start visible).
    private fun oldExternalLayout(): List<TouchControl> =
        oldDefaultLayout().map {
            if (it.id == "stick") it.copy(visible = false)
            else if (it.kind == "button" && it.id != "btn_start") it.copy(visible = false)
            else it
        }

    @Test
    fun defaultLayoutIsStandardDiamond() {
        val controls = defaultTouchLayout()
        assertEquals(0.80f to 0.62f, facePosition(controls, "btn_y"))
        assertEquals(0.87f to 0.71f, facePosition(controls, "btn_b"))
        assertEquals(0.80f to 0.80f, facePosition(controls, "btn_a"))
        assertEquals(0.73f to 0.71f, facePosition(controls, "btn_x"))
        // Lo demás no se mueve: stick, Start e Igniculus quedan donde estaban.
        assertEquals(0.18f to 0.72f, facePosition(controls, "stick"))
        assertEquals(0.50f to 0.06f, facePosition(controls, "btn_start"))
        assertEquals(0.50f to 0.35f, facePosition(controls, "igniculus"))
    }

    @Test
    fun oneHandLayoutIsStandardDiamondKeepingItsCharacter() {
        // Mismo rombo estándar, centrado en (0.76, 0.74): conserva
        // su carácter compacto, sus tamaños y su visibilidad
        // (TRIANGLE y SQUARE ocultos, CIRCLE y CROSS visibles).
        val controls = oneHandLayout()
        assertEquals(0.76f to 0.65f, facePosition(controls, "btn_y"))
        assertEquals(0.83f to 0.74f, facePosition(controls, "btn_b"))
        assertEquals(0.76f to 0.83f, facePosition(controls, "btn_a"))
        assertEquals(0.69f to 0.74f, facePosition(controls, "btn_x"))
        assertEquals(false, controls.first { it.id == "btn_y" }.visible)
        assertEquals(false, controls.first { it.id == "btn_x" }.visible)
        assertEquals(true, controls.first { it.id == "btn_b" }.visible)
        assertEquals(true, controls.first { it.id == "btn_a" }.visible)
        assertEquals(72, controls.first { it.id == "btn_a" }.sizeDp)
        assertEquals(0.20f to 0.75f, facePosition(controls, "stick"))
    }

    @Test
    fun externalGamepadLayoutIsStandardDiamond() {
        val controls = externalGamepadLayout()
        assertEquals(0.80f to 0.62f, facePosition(controls, "btn_y"))
        assertEquals(0.87f to 0.71f, facePosition(controls, "btn_b"))
        assertEquals(0.80f to 0.80f, facePosition(controls, "btn_a"))
        assertEquals(0.73f to 0.71f, facePosition(controls, "btn_x"))
        assertTrue(controls.filter { it.id.startsWith("btn_") && it.id != "btn_start" }.all { !it.visible })
        assertEquals(true, controls.first { it.id == "btn_start" }.visible)
    }

    @Test
    fun migratesSavedBuiltInDefaultProfile() {
        val saved = ControlProfile("default-g99", "Por defecto G99", true, oldDefaultLayout())
        val migrated = TouchLayoutRepository.migrateBuiltInFaceDiamond(saved)
        assertEquals(defaultTouchLayout(), migrated.controls)
        assertEquals("default-g99", migrated.id)
        assertTrue(migrated.isBuiltIn)
    }

    @Test
    fun migratesSavedBuiltInOneHandProfile() {
        val saved = ControlProfile("one-hand", "Una mano", true, oldOneHandLayout())
        val migrated = TouchLayoutRepository.migrateBuiltInFaceDiamond(saved)
        assertEquals(oneHandLayout(), migrated.controls)
    }

    @Test
    fun migratesSavedBuiltInExternalProfile() {
        val saved = ControlProfile("external-gamepad", "Mando externo + táctil mínimo", true, oldExternalLayout())
        val migrated = TouchLayoutRepository.migrateBuiltInFaceDiamond(saved)
        assertEquals(externalGamepadLayout(), migrated.controls)
    }

    @Test
    fun migrationLeavesCustomProfilesAlone() {
        // Un perfil personalizado con las posiciones antiguas es del
        // usuario: no se toca, aunque coincida con el rombo viejo.
        val custom = ControlProfile("custom-1", "Mío", false, oldDefaultLayout())
        assertEquals(custom, TouchLayoutRepository.migrateBuiltInFaceDiamond(custom))
    }

    @Test
    fun migrationLeavesEditedBuiltInAlone() {
        // Si el usuario movió aunque sea un botón de cara, el perfil
        // ya es suyo: migrar solo 3 de 4 rompería su ajuste.
        val edited = oldDefaultLayout().map {
            if (it.id == "btn_a") it.copy(xFraction = 0.85f) else it
        }
        val saved = ControlProfile("default-g99", "Por defecto G99", true, edited)
        assertEquals(saved, TouchLayoutRepository.migrateBuiltInFaceDiamond(saved))
    }

    @Test
    fun migrationLeavesRemappedBuiltInAlone() {
        // Si el usuario remapeó un botón de cara, el hueco ya no es
        // el de fábrica: no se migra.
        val remapped = oldDefaultLayout().map {
            if (it.id == "btn_a") it.copy(vitaButton = VitaButton.CIRCLE) else it
        }
        val saved = ControlProfile("default-g99", "Por defecto G99", true, remapped)
        assertEquals(saved, TouchLayoutRepository.migrateBuiltInFaceDiamond(saved))
    }

    @Test
    fun migrationIsIdempotent() {
        val saved = ControlProfile("default-g99", "Por defecto G99", true, oldDefaultLayout())
        val once = TouchLayoutRepository.migrateBuiltInFaceDiamond(saved)
        assertEquals(once, TouchLayoutRepository.migrateBuiltInFaceDiamond(once))
        assertNotEquals(saved.controls, once.controls)
        // Un perfil que ya lleva el rombo nuevo no cambia.
        val fresh = ControlProfile("default-g99", "Por defecto G99", true, defaultTouchLayout())
        assertEquals(fresh, TouchLayoutRepository.migrateBuiltInFaceDiamond(fresh))
    }

    @Test
    fun migrationRoundTripsThroughEncode() {
        val saved = ControlProfile("default-g99", "Por defecto G99", true, oldDefaultLayout())
        val raw = TouchLayoutRepository.encodeProfile(saved)
        val decoded = TouchLayoutRepository.decodeProfile(raw)
        assertEquals(saved, decoded)
        val migrated = TouchLayoutRepository.migrateBuiltInFaceDiamond(decoded!!)
        assertEquals(defaultTouchLayout(), migrated.controls)
        val redecoded = TouchLayoutRepository.decodeProfile(TouchLayoutRepository.encodeProfile(migrated))
        assertEquals(migrated, redecoded)
    }

    @Test
    fun migrateBuiltInProfilesMigratesAllThree() {
        val saved = listOf(
            ControlProfile("default-g99", "Por defecto G99", true, oldDefaultLayout()),
            ControlProfile("one-hand", "Una mano", true, oldOneHandLayout()),
            ControlProfile("external-gamepad", "Mando externo + táctil mínimo", true, oldExternalLayout()),
            ControlProfile("custom-1", "Mío", false, oldDefaultLayout()),
        )
        val migrated = TouchLayoutRepository.migrateBuiltInProfiles(saved)
        assertEquals(defaultTouchLayout(), migrated[0].controls)
        assertEquals(oneHandLayout(), migrated[1].controls)
        assertEquals(externalGamepadLayout(), migrated[2].controls)
        assertEquals(saved[3], migrated[3])
        assertEquals(builtInProfiles().map { it.id }, migrated.take(3).map { it.id })
    }
}
