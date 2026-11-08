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
    fun benchmarkLinksToPerformanceAndDiagnostics() {
        val results = searchSettings("prueba 30 segundos")
        assertTrue(results.any { it.category == SettingsCategory.PERFORMANCE })
        assertTrue(results.any { it.category == SettingsCategory.DIAGNOSTICS })
    }
}
