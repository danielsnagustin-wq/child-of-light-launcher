package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.ui.formatDateTime
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Formato de fecha/hora compartido de la UI (Ronda 0.2.70, Área 6,
 * 6-P2-2): Logros y Sesiones usaban un SimpleDateFormat nuevo por
 * fila y por recomposición. El formateador compartido
 * (DateTimeFormatter inmutable) debe dar EXACTAMENTE la misma cadena
 * visible que el SimpleDateFormat antiguo, para los mismos instantes
 * y en la zona horaria del teléfono.
 */
class FormatDateTimeTest {

    private fun legacy(millis: Long): String =
        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(millis))

    @Test
    fun `mismo texto que el SimpleDateFormat antiguo`() {
        val samples = listOf(0L, 1L, 1_000L, 1_763_000_000_000L, 1_700_000_000_000L, System.currentTimeMillis())
        for (millis in samples) {
            assertEquals("millis=$millis", legacy(millis), formatDateTime(millis))
        }
    }

    @Test
    fun `formato dd/MM/yyyy HH:mm`() {
        val text = formatDateTime(1_763_000_000_000L)
        assertTrue(text, text.matches(Regex("\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2}")))
    }

    @Test
    fun `epoca cero no es guion`() {
        assertEquals(legacy(0L), formatDateTime(0L))
    }
}
