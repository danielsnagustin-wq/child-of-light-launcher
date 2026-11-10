package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.model.LauncherSettings
import com.danielsnagustin.childoflight.launcher.model.effectiveSettings
import com.danielsnagustin.childoflight.launcher.ui.lightBackgroundTint
import com.danielsnagustin.childoflight.launcher.ui.settings.SETTINGS_SEARCH_INDEX
import com.danielsnagustin.childoflight.launcher.ui.settings.SettingsCategory
import com.danielsnagustin.childoflight.launcher.ui.settings.searchSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ronda 5, 0.2.53 (worker W3): tema claro, buscador y ajustes
 * efectivos. Solo lógica pura, testeable en JVM: los Brush de fondo
 * no exponen sus colores en unit tests, así que el tinte claro se
 * prueba por su primitiva (lightBackgroundTint, que es lo que la rama
 * clara de homeBackgroundBrush y de premiumBackgroundBrush usan como
 * primer color del gradiente).
 */
class R5ThemeTest {

    // --- R5-A2-4: el fondo elegido sí tiñe la Home en Tema Claro ---

    @Test
    fun lightBackgroundTint_conservaElMatizDelFondoElegido() {
        // El azul lavanda #6578B8 aclara a un tinte azulado (azul >
        // rojo), distinto del tinte del AMOLED #10131D: el hex elegido
        // no se ignora en claro. Antes la rama clara de
        // homeBackgroundBrush usaba un gradiente fijo y daba igual
        // qué fondo se hubiera elegido.
        val tint = lightBackgroundTint("#6578B8")
        assertTrue(tint.blue > tint.red)
        assertNotEquals(lightBackgroundTint("#10131D"), tint)
    }

    @Test
    fun lightBackgroundTint_turquesaConservaSuVerde() {
        val tint = lightBackgroundTint("#75D6CE")
        assertTrue(tint.green > tint.red)
        assertTrue(tint.blue > tint.red)
        assertNotEquals(lightBackgroundTint("#10131D"), tint)
    }

    // --- R5-A2-11 / R5-A1-14: ajustes efectivos con 60 FPS ON ---

    @Test
    fun effectiveSettings_60fpsFuerzaCachesOn() {
        // Con 60 FPS ON, apply60FpsMode fuerza textureCache y
        // shaderCache ON aunque la guardada sea OFF (p. ej. la
        // variante conservadora de ≤ 4 GB): la línea "Configuración
        // efectiva" de Diagnóstico muestra las efectivas.
        val saved = LauncherSettings(fpsHack = true, textureCache = false, shaderCache = false)
        val effective = effectiveSettings(saved)
        assertTrue(effective.textureCache)
        assertTrue(effective.shaderCache)
    }

    @Test
    fun effectiveSettings_60fpsFuerzaVsyncOn() {
        // Lo mismo para VSync: la pantalla GPU y diagnóstico muestra
        // el efectivo, no el guardado.
        val saved = LauncherSettings(fpsHack = true, vSync = false)
        assertTrue(effectiveSettings(saved).vSync)
    }

    @Test
    fun effectiveSettings_sin60fpsRespetaLasCachesGuardadas() {
        // Sin 60 FPS ni modo seguro, lo efectivo es lo guardado: una
        // caché OFF se muestra OFF, no ON.
        val saved = LauncherSettings(fpsHack = false, textureCache = false, shaderCache = false)
        val effective = effectiveSettings(saved)
        assertFalse(effective.textureCache)
        assertFalse(effective.shaderCache)
    }

    // --- R5-A2-9: "Restablecer esta categoría" fuera del índice para GENERAL ---

    @Test
    fun searchIndex_sinEntradaRestablecerCategoriaParaGeneral() {
        val resets = SETTINGS_SEARCH_INDEX.filter { it.title == "Restablecer esta categoría" }
        assertEquals(7, resets.size)
        assertTrue(resets.none { it.category == SettingsCategory.GENERAL })
        assertTrue(resets.none { it.category == SettingsCategory.STORAGE })
    }

    @Test
    fun searchRestablecerCategoria_noLlevaAGeneral() {
        val results = searchSettings("restablecer categoria")
        assertTrue(results.isNotEmpty())
        assertTrue(results.none { it.category == SettingsCategory.GENERAL })
        assertEquals(
            setOf(
                SettingsCategory.PERSONALIZATION,
                SettingsCategory.GPU,
                SettingsCategory.CPU,
                SettingsCategory.AUDIO,
                SettingsCategory.PERFORMANCE,
                SettingsCategory.CONTROLS,
                SettingsCategory.DIAGNOSTICS,
            ),
            results.map { it.category }.toSet(),
        )
    }
}
