package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.ui.settings.extractCpuLogLines
import com.danielsnagustin.childoflight.launcher.ui.settings.extractRecentErrorLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Errores recientes del log (Ola 3, Etapa 10). Función pura: filtra
 * las líneas que mencionan error, excepción o fallo.
 */
class DiagnosticsSummaryTest {

    private val log = """
        2026-11-08 01:00:00 status: juego extraído
        2026-11-08 01:01:00 import: ERROR al leer el header del .pkg
        2026-11-08 01:02:00 status: firmware guardado
        2026-11-08 01:03:00 core: Failed to load library
        2026-11-08 01:04:00 status: prueba terminada
        2026-11-08 01:05:00 audio: Falló la inicialización de Cubeb
    """.trimIndent()

    @Test
    fun extractsOnlyErrorLinesInOrder() {
        val errors = extractRecentErrorLines(log)
        assertEquals(3, errors.size)
        assertTrue(errors[0].contains("ERROR"))
        assertTrue(errors[1].contains("Failed"))
        assertTrue(errors[2].contains("Falló"))
    }

    @Test
    fun emptyLogHasNoErrors() {
        assertTrue(extractRecentErrorLines("").isEmpty())
        assertTrue(extractRecentErrorLines("todo bien\nsin problemas").isEmpty())
    }

    @Test
    fun maxLinesKeepsTheMostRecent() {
        val manyErrors = (1..12).joinToString("\n") { "error número $it" }
        val errors = extractRecentErrorLines(manyErrors, maxLines = 5)
        assertEquals(5, errors.size)
        assertTrue(errors.last().contains("12"))
        assertTrue(errors.first().contains("8"))
    }

    @Test
    fun exceptionCountsAsError() {
        val errors = extractRecentErrorLines("java.lang.Exception: boom")
        assertEquals(1, errors.size)
    }

    @Test
    fun fallbackLineIsNotAnError() {
        // R2-P2-20: "fallback" contiene "fail" pero es informativo
        // (Vulkan→OpenGL, normal): no debe aparecer como error.
        val log = "2026-11-08 01:00:00 core: Sin Vulkan: fallback automático a OpenGL\n" +
            "2026-11-08 01:01:00 core: fallback a Double Buffer en Mali"
        assertTrue(extractRecentErrorLines(log).isEmpty())
    }

    @Test
    fun fatalCrashLineCountsAsError() {
        val errors = extractRecentErrorLines("FATAL: Uncaught exception (crash) en el hilo \"main\"")
        assertEquals(1, errors.size)
    }

    @Test
    fun bracketedErrorLevelCountsAsError() {
        val errors = extractRecentErrorLines("[2026-11-08 01:00:00] [E] no se pudo abrir el archivo")
        assertEquals(1, errors.size)
    }

    // Ronda 0.2.66 (CPU-09): líneas de CPU del log del núcleo.

    @Test
    fun extractsOnlyCpuLinesInOrder() {
        val nativeLog = "I CPU Optimisation state: Enabled\n" +
            "I renderer: Vulkan initialised\n" +
            "I JIT cache ready\n" +
            "I audio: SDL backend"
        val cpuLines = extractCpuLogLines(nativeLog)
        assertEquals(2, cpuLines.size)
        assertTrue(cpuLines[0].contains("CPU Optimisation"))
        assertTrue(cpuLines[1].contains("JIT"))
    }

    @Test
    fun emptyLogHasNoCpuLines() {
        assertTrue(extractCpuLogLines("").isEmpty())
        assertTrue(extractCpuLogLines("todo bien\nsin problemas").isEmpty())
    }

    @Test
    fun cpuWordMatchesCaseInsensitively() {
        val cpuLines = extractCpuLogLines("todo bien\nsin CPU aquí salvo esta línea")
        assertEquals(1, cpuLines.size)
    }

    @Test
    fun maxLinesKeepsTheMostRecentCpuLines() {
        val manyCpu = (1..12).joinToString("\n") { "cpu line $it" }
        val cpuLines = extractCpuLogLines(manyCpu, maxLines = 5)
        assertEquals(5, cpuLines.size)
        assertTrue(cpuLines.last().contains("12"))
        assertTrue(cpuLines.first().contains("8"))
    }
}
