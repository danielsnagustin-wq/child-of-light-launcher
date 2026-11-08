package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.Vita3kConfigGenerator
import com.danielsnagustin.childoflight.launcher.model.GpuDriver
import com.danielsnagustin.childoflight.launcher.model.LauncherSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-B8 (2026-11-08): customDriverName se interpolaba sin escapar en
 * el YAML entrecomillado. El nombre viene del stem de un zip elegido
 * por el usuario, así que una comilla o un salto de línea rompía todo
 * el config.yml y el núcleo arrancaba con defaults en silencio
 * (parse() devuelve FileNotFound ante YAML::Exception, pin 3e43bc80,
 * config.cpp:301-328). El generador escapa ahora todos los valores
 * interpolados entre comillas con escapeYamlDoubleQuoted.
 */
class Vita3kConfigGeneratorEscapeTest {

    @Test
    fun escapeLeavesNormalDriverNameUnchanged() {
        assertEquals(
            "Turnip_v26.0.0_R8_Gmem",
            Vita3kConfigGenerator.escapeYamlDoubleQuoted("Turnip_v26.0.0_R8_Gmem"),
        )
        assertEquals("", Vita3kConfigGenerator.escapeYamlDoubleQuoted(""))
        assertEquals("/data/vita3k/vita", Vita3kConfigGenerator.escapeYamlDoubleQuoted("/data/vita3k/vita"))
    }

    @Test
    fun escapeEscapesQuoteAndBackslash() {
        assertEquals("a\\\"b", Vita3kConfigGenerator.escapeYamlDoubleQuoted("a\"b"))
        assertEquals("a\\\\b", Vita3kConfigGenerator.escapeYamlDoubleQuoted("a\\b"))
        // La barra va primero: una comilla precedida de barra no debe
        // dejar una barra suelta sin escapar.
        assertEquals("a\\\\\"b", Vita3kConfigGenerator.escapeYamlDoubleQuoted("a\\\"b"))
    }

    @Test
    fun escapeEscapesNewlineCarriageReturnAndTab() {
        assertEquals("a\\nb", Vita3kConfigGenerator.escapeYamlDoubleQuoted("a\nb"))
        assertEquals("a\\rb", Vita3kConfigGenerator.escapeYamlDoubleQuoted("a\rb"))
        assertEquals("a\\tb", Vita3kConfigGenerator.escapeYamlDoubleQuoted("a\tb"))
    }

    @Test
    fun escapeEscapesOtherControlCharactersAsUnicode() {
        assertEquals("a\\u0001b", Vita3kConfigGenerator.escapeYamlDoubleQuoted("a\u0001b"))
        assertEquals("a\\u007Fb", Vita3kConfigGenerator.escapeYamlDoubleQuoted("a\u007Fb"))
    }

    @Test
    fun hostileDriverNameCannotBreakYaml() {
        // Nombre hostil: comilla + salto de línea + intento de inyectar
        // otra clave. El YAML generado debe mantenerlo en UNA sola línea
        // y la clave inyectada no debe existir como línea propia.
        val yaml = Vita3kConfigGenerator.generate(
            LauncherSettings(
                gpuDriver = GpuDriver.TURNIP,
                turnipInstalled = true,
                customDriverName = "Bad\"Name\nEvil: true",
            ),
            "/data/vita3k",
        )
        assertTrue(yaml.lines().any { it == "custom-driver-name: \"Bad\\\"Name\\nEvil: true\"" })
        assertFalse(yaml.lines().any { it.startsWith("Evil:") })
        assertFalse(yaml.contains("custom-driver-name: \"Bad\"Name"))
    }

    @Test
    fun hostileVitaFsPathIsEscaped() {
        val yaml = Vita3kConfigGenerator.generate(
            LauncherSettings(),
            "/data/vi\"ta\nx",
        )
        assertTrue(yaml.lines().any { it == "pref-path: \"/data/vi\\\"ta\\nx\"" })
    }
}
