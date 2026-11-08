package com.danielsnagustin.childoflight.launcher

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
}
