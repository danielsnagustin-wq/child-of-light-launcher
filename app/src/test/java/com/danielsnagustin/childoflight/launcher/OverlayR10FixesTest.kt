package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.core.MultiTouchRouter
import com.danielsnagustin.childoflight.launcher.core.MultiTouchRouter.PointerAction
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.controlSizePx
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.sizeFractionForReferenceDp
import com.danielsnagustin.childoflight.launcher.data.ControlProfile
import com.danielsnagustin.childoflight.launcher.data.TouchControl
import com.danielsnagustin.childoflight.launcher.data.TouchLayoutRepository
import com.danielsnagustin.childoflight.launcher.data.VitaButton
import com.danielsnagustin.childoflight.launcher.data.defaultTouchLayout
import com.danielsnagustin.childoflight.launcher.data.oneHandLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests 0.2.65 (ronda 10, agente 4/6 — overlay y migración):
 * M-P2-1 (sizeFraction a null en migrateBuiltInFactoryValues),
 * M-P2-2 (una mano: espada/salto 82 -> 77 dp), M-P2-4 (perfil de
 * 7 campos llega al layout limpio: la comparación ignora colorHex
 * y visible) y R-P1-16 (el router coalesca MOVEs sub-píxel).
 * Todo puro JVM, sin Android ni Compose.
 */
class OverlayR10FixesTest {

    private fun defaultProfile(controls: List<TouchControl>): ControlProfile =
        ControlProfile("default-g99", "Por defecto G99", true, controls)

    private fun oneHandProfile(controls: List<TouchControl>): ControlProfile =
        ControlProfile("one-hand", "Una mano", true, controls)

    @Test
    fun factoryValuesPassClearsSizeFraction() {
        // M-P2-1: un perfil con fracción puesta (p. ej. importado
        // a mano con 15 campos) y tamaño viejo debe migrar de
        // verdad: sin sizeFraction = null, controlSizePx seguiría
        // prefiriendo la fracción y el cambio de sizeDp no se vería.
        // 0.2.68 (neón): la entrada es el layout 0.2.67 (6
        // controles) con stick 210 dp y zona 156 dp: el default
        // neón ya no lleva esos ids ni esas posiciones y construir
        // la entrada desde él dejaría el test vacío.
        val saved = listOf(
            TouchControl("stick", "Stick", "stick", VitaButton.LEFT_STICK, 0.05f, 0.675f, 210, 0.70f, sizeFraction = sizeFractionForReferenceDp(210)),
            TouchControl("btn_sword", "Espada", "button", VitaButton.SQUARE, 0.914f, 0.576f, 86, 0.80f),
            TouchControl("btn_jump", "Salto", "button", VitaButton.CROSS, 1.012f, 0.752f, 87, 0.80f),
            TouchControl("btn_start", "Start", "button", VitaButton.START, 0.50f, 0.07f, 52, 0.70f),
            TouchControl("btn_circle", "Volver", "button", VitaButton.CIRCLE, 0.59f, 0.07f, 41, 0.70f),
            TouchControl("igniculus", "Igniculus", "igniculus", VitaButton.NONE, 0.82f, 0.808f, 156, 0.25f, colorHex = "#7FB3D5", sizeFraction = sizeFractionForReferenceDp(156)),
        )
        val migrated = TouchLayoutRepository.migrateBuiltInFactoryValues(defaultProfile(saved))
        val stick = migrated.controls.first { it.id == "stick" }
        assertEquals(174, stick.sizeDp)
        assertNull(stick.sizeFraction)
        assertEquals(174 * 2.80f, controlSizePx(stick, 1080f, 2.80f), 0.01f)
        val zone = migrated.controls.first { it.id == "igniculus" }
        assertEquals(104, zone.sizeDp)
        assertNull(zone.sizeFraction)
    }

    @Test
    fun oneHandEditedProfileSwordAndJumpGoTo77() {
        // M-P2-2: una mano editada a medias (un botón movido, resto
        // en fábrica 0.2.62) dejaba espada/salto en 82 dp; la
        // fábrica es 77 dp desde la 0.2.63.
        // 0.2.68 segunda pasada: la entrada es literal (la una mano
        // de 0.2.63 con espada/salto a 82 dp y Start movido a 0.51):
        // oneHandLayout() ya es el neón de 10 controles y no lleva
        // btn_sword/btn_jump, así que derivar de él dejaría el test
        // vacío (mismo criterio que factoryValuesPassClearsSizeFraction).
        val saved = listOf(
            TouchControl("stick", "Stick", "stick", VitaButton.LEFT_STICK, 0.05f, 0.70f, 174, 0.75f, sizeFraction = sizeFractionForReferenceDp(174)),
            TouchControl("btn_sword", "Espada", "button", VitaButton.SQUARE, 0.83f, 0.63f, 82, 0.80f),
            TouchControl("btn_jump", "Salto", "button", VitaButton.CROSS, 0.91f, 0.79f, 82, 0.80f),
            TouchControl("btn_start", "Start", "button", VitaButton.START, 0.51f, 0.07f, 52, 0.70f, sizeFraction = sizeFractionForReferenceDp(52)),
            TouchControl("btn_circle", "Volver", "button", VitaButton.CIRCLE, 0.59f, 0.07f, 41, 0.70f, sizeFraction = sizeFractionForReferenceDp(41)),
            TouchControl("igniculus", "Igniculus", "igniculus", VitaButton.NONE, 0.78f, 0.80f, 104, 0.25f, colorHex = "#7FB3D5", sizeFraction = sizeFractionForReferenceDp(104)),
        )
        val migrated = TouchLayoutRepository.migrateBuiltInFactoryValues(oneHandProfile(saved))
        val sword = migrated.controls.first { it.id == "btn_sword" }
        val jump = migrated.controls.first { it.id == "btn_jump" }
        assertEquals(77, sword.sizeDp)
        assertNull(sword.sizeFraction)
        assertEquals(77, jump.sizeDp)
        assertNull(jump.sizeFraction)
        // El botón movido por el usuario no se toca.
        assertEquals(0.51f, migrated.controls.first { it.id == "btn_start" }.xFraction, 0.001f)
    }

    @Test
    fun sevenFieldProfileMigratesToCleanLayout() {
        // M-P2-4: perfil guardado en formato de 7 campos (rombo
        // transpuesto del default): al decodificar, Igniculus queda
        // en colorHex "#8CCBEB" (default) en vez de "#7FB3D5" y la
        // igualdad total de data class no coincidía con ningún
        // snapshot. Con la comparación por campos relevantes, migra.
        val sevenField = listOf(
            "stick|Stick|stick|0.18|0.72|140|0.7",
            "btn_y|Y|button|0.70|0.70|64|0.8",
            "btn_x|X|button|0.78|0.62|64|0.8",
            "btn_b|B|button|0.78|0.80|64|0.8",
            "btn_a|A|button|0.87|0.71|64|0.85",
            "btn_start|Start|button|0.50|0.06|56|0.7",
            "igniculus|Igniculus|igniculus|0.50|0.35|220|0.25",
        )
        val decoded = sevenField.map { TouchLayoutRepository.decodeControl(it)!! }
        assertEquals("#8CCBEB", decoded.first { it.id == "igniculus" }.colorHex)
        val migrated = TouchLayoutRepository.migrateBuiltInProfile(defaultProfile(decoded))
        assertEquals(defaultTouchLayout(), migrated.controls)
    }

    @Test
    fun clean062ProfileWithDefaultIgniculusColorStillMigrates() {
        // M-P2-4, segunda vía: perfil limpio de 0.2.62 con la
        // fracción ya a null pero el color de Igniculus en el
        // default "#8CCBEB": antes tampoco migraba; ahora sí.
        // 0.2.68 (neón): la entrada es el layout 0.2.67 con los
        // tamaños de 0.2.62 (stick 186, espada/salto 92, pausa 56,
        // círculo 44, zona 115), literales: el default neón ya no
        // lleva esos ids y construirla desde él dejaría el test vacío.
        val saved = listOf(
            TouchControl("stick", "Stick", "stick", VitaButton.LEFT_STICK, 0.05f, 0.675f, 186, 0.70f),
            TouchControl("btn_sword", "Espada", "button", VitaButton.SQUARE, 0.914f, 0.576f, 92, 0.80f),
            TouchControl("btn_jump", "Salto", "button", VitaButton.CROSS, 1.012f, 0.752f, 92, 0.80f),
            TouchControl("btn_start", "Start", "button", VitaButton.START, 0.50f, 0.07f, 56, 0.70f),
            TouchControl("btn_circle", "Volver", "button", VitaButton.CIRCLE, 0.59f, 0.07f, 44, 0.70f),
            TouchControl("igniculus", "Igniculus", "igniculus", VitaButton.NONE, 0.82f, 0.808f, 115, 0.25f, colorHex = "#8CCBEB"),
        )
        val migrated = TouchLayoutRepository.migrateBuiltInProfile(defaultProfile(saved))
        assertEquals(defaultTouchLayout(), migrated.controls)
    }

    @Test
    fun editedProfileStillDoesNotMigrate() {
        // Guarda preservada: un perfil que el usuario movió (aunque
        // sea un solo control) sigue siendo suyo y no migra.
        val saved = defaultTouchLayout().map { control ->
            if (control.id == "stick") control.copy(xFraction = 0.06f) else control
        }
        val migrated = TouchLayoutRepository.migrateBuiltInProfile(defaultProfile(saved))
        assertEquals(saved, migrated.controls)
    }

    @Test
    fun routerCoalescesSubPixelMovesButKeepsDrift() {
        // R-P1-16: MOVEs que no mueven el dedo ≥ 1 px desde el
        // último reenviado se descartan; la deriva lenta en pasos
        // sub-píxel se acumula y se reenvía al llegar a 1 px.
        val router = MultiTouchRouter()
        router.onPointerDown(1, 1000L, 500f, 800f, false, false)
        assertEquals(PointerAction.DROP, router.onMove(1, 500.4f, 800f))
        assertEquals(PointerAction.DROP, router.onMove(1, 500.8f, 800f))
        assertEquals(PointerAction.FORWARD_MOVE, router.onMove(1, 501.2f, 800f))
        // endAll sigue devolviendo la posición REAL, no la última
        // reenviada: el UP de cierre lleva al dedo donde está.
        val ended = router.endAll()
        assertEquals(1, ended.size)
        assertEquals(501.2f, ended.first().x, 0.001f)
        assertEquals(800f, ended.first().y, 0.001f)
    }
}
