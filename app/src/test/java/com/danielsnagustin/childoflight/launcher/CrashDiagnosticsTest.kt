package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.LauncherLog
import com.danielsnagustin.childoflight.launcher.data.formatLaunchAttempt
import com.danielsnagustin.childoflight.launcher.data.formatLoadGameResult
import com.danielsnagustin.childoflight.launcher.ui.settings.extractRecentErrorLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Diagnóstico del crash ~15:06 (2026-11-08, sin stack trace).
 * Cubre lo testeable en JVM: el formato del reporte de crash y de
 * las líneas de arranque. El handler en sí (MainActivity) y los
 * try/catch C++ del puente JNI no se pueden probar aquí: se verifican
 * en CI (compilación) y en el teléfono (el próximo reporte debe
 * incluir el stack trace).
 */
class CrashDiagnosticsTest {

    @Test
    fun crashReportIsPickedUpByRecentErrors() {
        val throwable = RuntimeException("boom de prueba")
        val report = LauncherLog.formatCrashReport(Thread.currentThread(), throwable)
        val recent = extractRecentErrorLines(report)
        assertTrue("El reporte de crash debe aparecer en errores recientes", recent.isNotEmpty())
        assertTrue(recent.any { it.contains("crash", ignoreCase = true) })
    }

    @Test
    fun crashReportContainsThreadExceptionAndStackTrace() {
        val throwable = RuntimeException("boom de prueba")
        val report = LauncherLog.formatCrashReport(Thread.currentThread(), throwable)
        assertTrue(report.contains("Uncaught exception"))
        assertTrue(report.contains("java.lang.RuntimeException"))
        assertTrue(report.contains("boom de prueba"))
        assertTrue(report.contains(Thread.currentThread().name))
        // Al menos un marco del stack trace (la propia prueba).
        assertTrue(report.lines().any { it.contains("at ") })
    }

    @Test
    fun crashReportIncludesChainedCauses() {
        val root = IllegalStateException("causa raíz")
        val throwable = RuntimeException("fallo exterior", root)
        val report = LauncherLog.formatCrashReport(Thread.currentThread(), throwable)
        assertTrue(report.contains("Causado por java.lang.IllegalStateException: causa raíz"))
    }

    @Test
    fun launchAttemptLineContainsDecisionState() {
        val line = formatLaunchAttempt("PCSB00598", corePresent = true, firmwareInstalled = true)
        assertTrue(line.contains("PCSB00598"))
        assertTrue(line.contains("núcleo=presente"))
        assertTrue(line.contains("firmware=instalado"))

        val missing = formatLaunchAttempt(null, corePresent = false, firmwareInstalled = false)
        assertTrue(missing.contains("sin determinar"))
        assertTrue(missing.contains("núcleo=ausente"))
        assertTrue(missing.contains("firmware=no instalado"))
    }

    @Test
    fun loadGameResultLineNamesCode() {
        assertEquals("loadGame(PCSB00598) = 0 (OK)", formatLoadGameResult("PCSB00598", 0))
        assertEquals(
            "loadGame(PCSB00598) = -2 (sin sesión)",
            formatLoadGameResult("PCSB00598", -2),
        )
        assertEquals(
            "loadGame(PCSB00598) = -3 (juego no instalado en el núcleo)",
            formatLoadGameResult("PCSB00598", -3),
        )
        assertTrue(formatLoadGameResult("PCSB00598", -99).contains("-99"))
    }
}
