package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.ui.Screen
import com.danielsnagustin.childoflight.launcher.ui.settings.SETTINGS_SEARCH_INDEX
import com.danielsnagustin.childoflight.launcher.ui.settings.SettingsCategory
import com.danielsnagustin.childoflight.launcher.ui.settings.normalizeSearchText
import com.danielsnagustin.childoflight.launcher.ui.settings.searchSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Buscador de ajustes (Ola 2, Etapa 1). Función pura: sin Android ni
 * Compose, corre en JVM.
 */
class SettingsSearchTest {

    @Test
    fun findsLogrosScreen() {
        // P2-12: "logros" vuelve a encontrar algo, y lleva a la pantalla
        // de Logros (no a General, que ya no tiene ese botón).
        val results = searchSettings("logros")
        assertTrue(results.any { it.title == "Logros" })
        val entry = results.first { it.title == "Logros" }
        assertEquals(Screen.ACHIEVEMENTS, entry.destination)
    }

    @Test
    fun emptyQueryReturnsNothing() {
        assertTrue(searchSettings("").isEmpty())
        assertTrue(searchSettings("   ").isEmpty())
    }

    @Test
    fun findsBySettingName() {
        val results = searchSettings("FSR")
        assertTrue(results.any { it.title == "Filtro de pantalla" })
        assertTrue(results.all { it.category == SettingsCategory.GPU })
    }

    @Test
    fun findsByKeyword() {
        val results = searchSettings("dynarmic")
        assertTrue(results.any { it.title.contains("Dynarmic") })
        assertTrue(results.any { it.category == SettingsCategory.CPU })
    }

    @Test
    fun accentInsensitive() {
        assertEquals(normalizeSearchText("Diagnóstico"), "diagnostico")
        val results = searchSettings("diagnostico")
        assertTrue(results.isNotEmpty())
        assertTrue(results.any { it.category == SettingsCategory.DIAGNOSTICS })
    }

    @Test
    fun multiWordQueryRequiresAllWords() {
        // "cache shaders" encuentra la caché de shaders, pero no la de
        // texturas sin hash (no lleva "shaders" en título ni claves).
        val results = searchSettings("cache shaders")
        assertTrue(results.any { it.title == "Caché de shaders" })
        assertTrue(results.none { it.title == "Caché de texturas sin hash" })
    }

    @Test
    fun findsAudioFixUndo() {
        val results = searchSettings("deshacer corregir audio")
        assertTrue(results.any { it.title == "Deshacer Corregir audio" })
        assertTrue(results.all { it.category == SettingsCategory.AUDIO })
    }

    @Test
    fun everyCategoryIsReachableFromSearch() {
        val covered = SETTINGS_SEARCH_INDEX.map { it.category }.toSet()
        assertEquals(SettingsCategory.entries.toSet(), covered)
    }

    @Test
    fun everySearchEntryPointsToARealScreen() {
        val screens = Screen.entries.toSet()
        SETTINGS_SEARCH_INDEX.forEach { entry ->
            assertTrue(screens.contains(entry.category.screen))
        }
    }

    @Test
    fun benchmarkLinksOnlyToDiagnostics() {
        // Dedup: la prueba de 30 s vive SOLO en Diagnóstico ("GPU y
        // diagnóstico"). Rendimiento ya no enlaza a ella: buscar
        // "prueba 30 segundos" no debe devolver entradas de
        // PERFORMANCE.
        val results = searchSettings("prueba 30 segundos")
        assertTrue(results.any { it.category == SettingsCategory.DIAGNOSTICS })
        assertTrue(results.none { it.category == SettingsCategory.PERFORMANCE })
    }

    @Test
    fun spacelessQueryFindsSpacedTitle() {
        // R2-P2-21d: "60fps" encuentra "Modo 60 FPS".
        val results = searchSettings("60fps")
        assertTrue(results.any { it.title == "Modo 60 FPS" })
        // Y "60 fps" sigue funcionando igual.
        assertTrue(searchSettings("60 fps").any { it.title == "Modo 60 FPS" })
    }

    @Test
    fun turnipEntryHiddenOnNonAdrenoButShownOtherwise() {
        // R2-P2-21a / R2-P2-28: en Mali no hay dónde instalar Turnip.
        val onMali = searchSettings("turnip", gpuVendor = com.danielsnagustin.childoflight.launcher.model.GpuVendor.MALI)
        assertTrue(onMali.none { it.title == "Instalar Turnip" })
        // En Adreno y sin GPU detectada sí se ofrece.
        assertTrue(searchSettings("turnip", gpuVendor = com.danielsnagustin.childoflight.launcher.model.GpuVendor.ADRENO).any { it.title == "Instalar Turnip" })
        assertTrue(searchSettings("turnip").any { it.title == "Instalar Turnip" })
    }

    @Test
    fun effectiveConfigEntryMatchesDiagnosticsScreenTitle() {
        // R2-P2-21b: la pantalla real dice "Configuración efectiva".
        val results = searchSettings("configuracion efectiva")
        assertTrue(results.any { it.title == "Configuración efectiva" && it.category == SettingsCategory.DIAGNOSTICS })
    }

    // P2-21: "Driver personalizado" y "Descartar cambios" existen
    // en sus pantallas y el buscador no los encontraba por su nombre.
    @Test
    fun findsDriverPersonalizado() {
        val results = searchSettings("driver personalizado")
        assertTrue(results.any { it.title == "Driver personalizado" && it.category == SettingsCategory.GPU })
    }

    @Test
    fun findsDescartarCambios() {
        val results = searchSettings("descartar cambios")
        assertTrue(results.any { it.title == "Descartar cambios" && it.category == SettingsCategory.PERSONALIZATION })
    }
}
