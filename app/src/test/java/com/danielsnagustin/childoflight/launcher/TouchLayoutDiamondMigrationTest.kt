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
 * R5-A1-1 (2026-11-09): la migración completa (migrateBuiltInProfile)
 * añade una segunda pasada que normaliza el stick y la Igniculus a
 * los valores de fábrica actuales, solo si siguen en el valor viejo
 * de fábrica. Los tests de migración completa afirman los valores
 * NUEVOS; los de migrateBuiltInFaceDiamond sola siguen afirmando
 * solo el rombo (esa pasada no toca stick ni Igniculus).
 *
 * 0.2.54 (2026-11-09): el layout de fábrica ya NO es el rombo: es
 * el layout limpio (stick, espada, salto, Start, Igniculus). Los
 * tests de layout de fábrica de este archivo se reescribieron para
 * el limpio; los de migrateBuiltInFaceDiamond sola siguen
 * probando esa pasada (un perfil viejo con el rombo transpuesto
 * migra al estándar), y los de migración completa ahora afirman
 * que un perfil viejo entero migra al layout limpio de fábrica.
 *
 * 0.2.57 (2026-11-10): los tamaños de fábrica se achican (stick
 * 186 dp, zona Igniculus 115 dp); los tests de este archivo afirman
 * los valores nuevos.
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
    fun defaultLayoutIsCleanLayout() {
        // 0.2.54: el default ya no es el rombo: stick grande a la
        // izquierda, espada (SQUARE) y salto (CROSS) a la derecha,
        // Start arriba e Igniculus abajo a la derecha, y el círculo
        // (CIRCLE) junto a la pausa para volver/cancelar.
        val controls = defaultTouchLayout()
        assertEquals(6, controls.size)
        assertEquals(0.05f to 0.675f, facePosition(controls, "stick"))
        assertEquals(186, controls.first { it.id == "stick" }.sizeDp)
        assertEquals(0.864f to 0.576f, facePosition(controls, "btn_sword"))
        assertEquals(0.93f to 0.752f, facePosition(controls, "btn_jump"))
        assertEquals(0.50f to 0.07f, facePosition(controls, "btn_start"))
        assertEquals(0.59f to 0.07f, facePosition(controls, "btn_circle"))
        assertEquals(44, controls.first { it.id == "btn_circle" }.sizeDp)
        assertEquals(0.82f to 0.808f, facePosition(controls, "igniculus"))
        assertEquals(115, controls.first { it.id == "igniculus" }.sizeDp)
    }

    @Test
    fun oneHandLayoutIsCleanLayoutWithThumbGroup() {
        // 0.2.54: mismos 6 controles, grupo derecho desplazado
        // para el pulgar, todos visibles.
        val controls = oneHandLayout()
        assertEquals(6, controls.size)
        assertEquals(0.05f to 0.70f, facePosition(controls, "stick"))
        assertEquals(186, controls.first { it.id == "stick" }.sizeDp)
        assertEquals(0.83f to 0.63f, facePosition(controls, "btn_sword"))
        assertEquals(0.91f to 0.79f, facePosition(controls, "btn_jump"))
        assertEquals(0.50f to 0.07f, facePosition(controls, "btn_start"))
        assertEquals(0.59f to 0.07f, facePosition(controls, "btn_circle"))
        assertEquals(0.78f to 0.80f, facePosition(controls, "igniculus"))
        assertTrue(controls.all { it.visible })
    }

    @Test
    fun externalGamepadLayoutIsCleanLayoutWithActionHidden() {
        // 0.2.54: mismo layout limpio; stick, espada, salto y
        // círculo ocultos, Start e Igniculus visibles.
        val controls = externalGamepadLayout()
        assertEquals(6, controls.size)
        assertEquals(0.864f to 0.576f, facePosition(controls, "btn_sword"))
        assertEquals(0.93f to 0.752f, facePosition(controls, "btn_jump"))
        assertTrue(controls.filter { it.id == "stick" || it.id == "btn_sword" || it.id == "btn_jump" || it.id == "btn_circle" }.all { !it.visible })
        assertEquals(true, controls.first { it.id == "btn_start" }.visible)
        assertEquals(186, controls.first { it.id == "stick" }.sizeDp)
        assertEquals(0.82f to 0.808f, facePosition(controls, "igniculus"))
    }

    @Test
    fun migratesSavedBuiltInDefaultProfile() {
        val saved = ControlProfile("default-g99", "Por defecto G99", true, oldDefaultLayout())
        val migrated = TouchLayoutRepository.migrateBuiltInProfile(saved)
        // 0.2.54: un perfil viejo entero (rombo transpuesto, stick
        // 140 dp, Igniculus en y 0.35) migra al layout limpio de
        // fábrica, no al rombo estándar.
        assertEquals(defaultTouchLayout(), migrated.controls)
        assertEquals(0.864f to 0.576f, facePosition(migrated.controls, "btn_sword"))
        assertEquals(0.93f to 0.752f, facePosition(migrated.controls, "btn_jump"))
        assertEquals(186, migrated.controls.first { it.id == "stick" }.sizeDp)
        assertEquals(0.82f to 0.808f, facePosition(migrated.controls, "igniculus"))
        assertEquals("default-g99", migrated.id)
        assertTrue(migrated.isBuiltIn)
    }

    @Test
    fun migratesSavedBuiltInOneHandProfile() {
        val saved = ControlProfile("one-hand", "Una mano", true, oldOneHandLayout())
        val migrated = TouchLayoutRepository.migrateBuiltInProfile(saved)
        // 0.2.54: migra al layout limpio de una mano entero.
        assertEquals(oneHandLayout(), migrated.controls)
        assertEquals(0.83f to 0.63f, facePosition(migrated.controls, "btn_sword"))
        assertEquals(0.91f to 0.79f, facePosition(migrated.controls, "btn_jump"))
        assertEquals(186, migrated.controls.first { it.id == "stick" }.sizeDp)
        assertEquals(0.78f to 0.80f, facePosition(migrated.controls, "igniculus"))
    }

    @Test
    fun migratesSavedBuiltInExternalProfile() {
        val saved = ControlProfile("external-gamepad", "Mando externo + táctil mínimo", true, oldExternalLayout())
        val migrated = TouchLayoutRepository.migrateBuiltInProfile(saved)
        // 0.2.54: migra al layout limpio de mando externo entero.
        assertEquals(externalGamepadLayout(), migrated.controls)
        assertEquals(0.864f to 0.576f, facePosition(migrated.controls, "btn_sword"))
        assertEquals(0.93f to 0.752f, facePosition(migrated.controls, "btn_jump"))
        assertEquals(186, migrated.controls.first { it.id == "stick" }.sizeDp)
        assertEquals(0.82f to 0.808f, facePosition(migrated.controls, "igniculus"))
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
        val migrated = TouchLayoutRepository.migrateBuiltInProfile(decoded!!)
        // 0.2.54: tras el round-trip, la migración completa lleva
        // el perfil viejo al layout limpio de fábrica.
        assertEquals(0.864f to 0.576f, facePosition(migrated.controls, "btn_sword"))
        assertEquals(0.93f to 0.752f, facePosition(migrated.controls, "btn_jump"))
        assertEquals(186, migrated.controls.first { it.id == "stick" }.sizeDp)
        assertEquals(0.82f to 0.808f, facePosition(migrated.controls, "igniculus"))
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
        // 0.2.54: los tres migran al layout limpio de fábrica; el
        // personalizado no se toca.
        assertEquals(0.864f to 0.576f, facePosition(migrated[0].controls, "btn_sword"))
        assertEquals(0.93f to 0.752f, facePosition(migrated[0].controls, "btn_jump"))
        assertEquals(186, migrated[0].controls.first { it.id == "stick" }.sizeDp)
        assertEquals(0.82f to 0.808f, facePosition(migrated[0].controls, "igniculus"))
        assertEquals(0.83f to 0.63f, facePosition(migrated[1].controls, "btn_sword"))
        assertEquals(0.91f to 0.79f, facePosition(migrated[1].controls, "btn_jump"))
        assertEquals(186, migrated[1].controls.first { it.id == "stick" }.sizeDp)
        assertEquals(0.78f to 0.80f, facePosition(migrated[1].controls, "igniculus"))
        assertEquals(0.864f to 0.576f, facePosition(migrated[2].controls, "btn_sword"))
        assertEquals(0.93f to 0.752f, facePosition(migrated[2].controls, "btn_jump"))
        assertEquals(186, migrated[2].controls.first { it.id == "stick" }.sizeDp)
        assertEquals(0.82f to 0.808f, facePosition(migrated[2].controls, "igniculus"))
        assertEquals(saved[3], migrated[3])
        assertEquals(builtInProfiles().map { it.id }, migrated.take(3).map { it.id })
    }
}
