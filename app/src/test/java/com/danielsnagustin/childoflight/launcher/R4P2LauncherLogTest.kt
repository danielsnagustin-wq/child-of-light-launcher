package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.LauncherLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * R4-P2-5: rotación del log y dedup de scanStatus.
 *
 * Antes, cada append con el archivo por encima de 200 KB pagaba
 * readLines() + writeText() del archivo entero DENTRO del candado, y
 * cada scanStatus() añadía una línea idéntica: las ráfagas de onResume
 * llenaban el log y aceleraban la rotación. Ahora la rotación solo se
 * intenta como mucho una vez por minuto y la línea de scanStatus solo
 * se registra cuando su resumen cambia.
 */
class R4P2LauncherLogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `shouldRotate solo por encima de 200 KB`() {
        assertFalse(LauncherLog.shouldRotate(0))
        assertFalse(LauncherLog.shouldRotate(LauncherLog.MAX_LOG_BYTES))
        assertTrue(LauncherLog.shouldRotate(LauncherLog.MAX_LOG_BYTES + 1))
    }

    @Test
    fun `shouldAttemptRotation respeta el intervalo de un minuto`() {
        val interval = LauncherLog.ROTATION_MIN_INTERVAL_MILLIS
        assertFalse(LauncherLog.shouldAttemptRotation(nowMillis = 1000, lastAttemptMillis = 1000))
        assertFalse(LauncherLog.shouldAttemptRotation(nowMillis = 1000 + interval - 1, lastAttemptMillis = 1000))
        assertTrue(LauncherLog.shouldAttemptRotation(nowMillis = 1000 + interval, lastAttemptMillis = 1000))
        assertTrue(LauncherLog.shouldAttemptRotation(nowMillis = 1000 + interval + 1, lastAttemptMillis = 1000))
    }

    @Test
    fun `rotateLogFile conserva solo las 200 lineas recientes`() {
        val file = tmp.newFile("launcher.log")
        // 250 líneas de ~1 KB: más de 200 KB en total.
        file.writeText((1..250).joinToString("\n") { "línea $it ${"x".repeat(1000)}" } + "\n")
        assertTrue(file.length() > LauncherLog.MAX_LOG_BYTES)
        assertTrue(LauncherLog.rotateLogFile(file))
        val lines = file.readLines()
        assertEquals(LauncherLog.KEEP_LINES_AFTER_ROTATION, lines.size)
        assertTrue(lines.first().startsWith("línea 51 "))
        assertTrue(lines.last().startsWith("línea 250 "))
    }

    @Test
    fun `rotateLogFile no toca un archivo pequeno`() {
        val file = tmp.newFile("launcher.log")
        val content = (1..10).joinToString("\n") { "línea $it" } + "\n"
        file.writeText(content)
        assertFalse(LauncherLog.rotateLogFile(file))
        assertEquals(content, file.readText())
    }

    @Test
    fun `rotateLogFile no toca un archivo ausente`() {
        val missing = java.io.File(tmp.root, "no-existe.log")
        assertFalse(LauncherLog.rotateLogFile(missing))
    }

    @Test
    fun `shouldLogScanStatus solo registra cambios`() {
        assertTrue(LauncherLog.shouldLogScanStatus(null, "scanStatus ux0=/x instalado=true"))
        assertFalse(
            LauncherLog.shouldLogScanStatus(
                "scanStatus ux0=/x instalado=true",
                "scanStatus ux0=/x instalado=true",
            ),
        )
        assertTrue(
            LauncherLog.shouldLogScanStatus(
                "scanStatus ux0=/x instalado=true",
                "scanStatus ux0=/x instalado=false",
            ),
        )
    }
}
