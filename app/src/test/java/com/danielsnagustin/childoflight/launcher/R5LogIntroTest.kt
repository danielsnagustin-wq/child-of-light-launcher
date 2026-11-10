package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.LauncherLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Ronda 5 (R5-A1-9 / R5-A1-10): rotación del log con reloj monótono
 * y escritura atómica vía .tmp + rename.
 *
 * Antes, shouldAttemptRotation se alimentaba con el reloj de pared
 * (un atraso de hora aplazaba la rotación) y el instante del intento
 * se marcaba ANTES de rotar (un fallo consumía el minuto); y
 * rotateLogFile hacía readLines + writeText directo sobre el mismo
 * archivo (matar el proceso a mitad dejaba el log truncado).
 *
 * La marca de intro (R5-A1-4, UiPreferencesRepository
 * .markHomeIntroSeen) no tiene test JVM: DataStore exige Context
 * Android y el método es una sola llamada safeEdit con el mismo
 * patrón atómico que hideGpuPresets; su contrato (nombre y firma)
 * se verifica por inspección.
 */
class R5LogIntroTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `rotateLogFile no deja tmp tras rotar`() {
        val file = tmp.newFile("launcher.log")
        // 250 líneas de ~1 KB: más de 200 KB en total.
        file.writeText((1..250).joinToString("\n") { "línea $it ${"x".repeat(1000)}" } + "\n")
        assertTrue(file.length() > LauncherLog.MAX_LOG_BYTES)
        assertTrue(LauncherLog.rotateLogFile(file))
        // La rotación atómica no deja el .tmp atrás.
        assertFalse(java.io.File(tmp.root, "launcher.log.tmp").exists())
        val lines = file.readLines()
        assertEquals(LauncherLog.KEEP_LINES_AFTER_ROTATION, lines.size)
        assertTrue(lines.first().startsWith("línea 51 "))
        assertTrue(lines.last().startsWith("línea 250 "))
    }

    @Test
    fun `rotateLogFile sobrescribe un tmp huerfano sin contaminar el log`() {
        val file = tmp.newFile("launcher.log")
        file.writeText((1..250).joinToString("\n") { "línea $it ${"x".repeat(1000)}" } + "\n")
        // Resto de una rotación interrumpida: se sobrescribe con el
        // contenido nuevo, no se mezcla con el log.
        val staleTmp = java.io.File(tmp.root, "launcher.log.tmp")
        staleTmp.writeText("basura vieja\n")
        assertTrue(LauncherLog.rotateLogFile(file))
        assertFalse(staleTmp.exists())
        val lines = file.readLines()
        assertEquals(LauncherLog.KEEP_LINES_AFTER_ROTATION, lines.size)
        assertFalse(lines.any { it.contains("basura vieja") })
    }

    @Test
    fun `rotateLogFile no toca un directorio llamado launcher log`() {
        val dir = tmp.newFolder("launcher.log")
        assertFalse(LauncherLog.rotateLogFile(dir))
        assertTrue(dir.isDirectory)
        assertFalse(java.io.File(tmp.root, "launcher.log.tmp").exists())
    }

    @Test
    fun `shouldAttemptRotation con el reloj hacia atras no intenta rotar`() {
        // Reloj atrasado (o instante anterior al último intento): la
        // diferencia es negativa y no se intenta rotar. Con el reloj
        // de pared este caso aplazaba la rotación hasta que la hora
        // alcanzara el valor viejo; con elapsedRealtime() (monótono)
        // el instante nunca retrocede.
        assertFalse(LauncherLog.shouldAttemptRotation(nowMillis = 1000, lastAttemptMillis = 5000))
    }
}
