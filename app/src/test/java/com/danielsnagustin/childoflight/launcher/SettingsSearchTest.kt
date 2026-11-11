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
    fun jitFindsNothing() {
        // R7-P2-08: no existe ajuste JIT (la pantalla de CPU lo dice:
        // no hay interruptor JIT porque no haría nada), así que buscar
        // "jit" no debe llevar a la optimización CPU (Dynarmic).
        assertTrue(searchSettings("jit").isEmpty())
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

    // P2-21 + R6-P2-11: "Driver personalizado" y "Driver GPU" eran dos
    // entradas para el mismo control: fusionadas en una sola ("Driver
    // GPU", con "personalizado" en las claves).
    @Test
    fun findsDriverPersonalizado() {
        val results = searchSettings("driver personalizado")
        assertTrue(results.any { it.title == "Driver GPU" && it.category == SettingsCategory.GPU })
        assertEquals(1, results.count { it.title == "Driver GPU" })
        assertTrue(results.none { it.title == "Driver personalizado" })
    }

    @Test
    fun findsImportarFirmware() {
        // R6-P2-11: "importar firmware" / "firmware" / "pup" llevan a
        // la pantalla Importar (destino propio, no una categoría).
        val byFull = searchSettings("importar firmware")
        assertTrue(byFull.any { it.title == "Importar firmware" })
        val entry = byFull.first { it.title == "Importar firmware" }
        assertEquals(Screen.IMPORT, entry.destination)
        assertTrue(searchSettings("firmware").any { it.title == "Importar firmware" })
        assertTrue(searchSettings("pup").any { it.title == "Importar firmware" })
    }

    @Test
    fun pupHasNoFalsePositives() {
        // R6-P2-11: "pup" es subcadena de "gpu"+"preset" ("Presets por
        // GPU") con el matching viejo. Ahora solo coincide la entrada
        // que lleva "pup" como palabra entera.
        val results = searchSettings("pup")
        assertTrue(results.all { it.title == "Importar firmware" })
        assertTrue(results.none { it.title == "Presets por GPU" })
        assertTrue(results.none { it.title == "Perfil activo" })
    }

    @Test
    fun psnHasNoFalsePositives() {
        // R6-P2-11: "psn" es subcadena de "fps"+"nucleo" ("Resumen del
        // dispositivo") con el matching viejo.
        val results = searchSettings("psn")
        assertTrue(results.any { it.title == "Sesión PSN (simulada)" })
        assertTrue(results.none { it.title == "Resumen del dispositivo" })
    }

    @Test
    fun appExternaHasNoFalsePositives() {
        // R6-P2-11: "app" es subcadena de "mapping" y "externa" de
        // "external" ("Memory Mapping") con el matching viejo.
        val results = searchSettings("app externa")
        assertTrue(results.any { it.title == "Permitir app externa" })
        assertTrue(results.none { it.title == "Memory Mapping" })
    }

    @Test
    fun findsPersonalizarBotones() {
        // R6 (evidencia del teléfono, 0.2.55): el editor táctil tiene
        // entrada propia en Controles y HUD y el buscador lleva ahí.
        val results = searchSettings("personalizar botones")
        assertTrue(results.any { it.title == "Personalizar botones" && it.category == SettingsCategory.CONTROLS })
    }

    @Test
    fun findsDescartarCambios() {
        val results = searchSettings("descartar cambios")
        assertTrue(results.any { it.title == "Descartar cambios" && it.category == SettingsCategory.PERSONALIZATION })
    }
}
