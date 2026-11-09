package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.LauncherLog
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Cola del log nativo (vita3k.log) en el reporte de bug (Fase 2,
 * 2026-11-09). Cubre lo testeable en JVM: últimas 40 líneas, tope
 * de 32 KB, primera línea parcial descartada, archivo ausente →
 * mensaje. La lectura en el teléfono (buildBugReportText) se
 * verifica en CI (compilación) y con el próximo reporte del usuario.
 */
class NativeLogTailTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `50 lineas devuelven las ultimas 40`() {
        val file = tmp.newFile("vita3k.log")
        file.writeText((1..50).joinToString("\n") { "línea $it" } + "\n")
        val tail = LauncherLog.readNativeLogTail(file)
        val lines = tail.lines()
        assertEquals(40, lines.size)
        assertEquals("línea 11", lines.first())
        assertEquals("línea 50", lines.last())
    }

    @Test
    fun `archivo pequeno conserva la primera linea`() {
        val file = tmp.newFile("vita3k.log")
        file.writeText((1..10).joinToString("\n") { "línea $it" } + "\n")
        val tail = LauncherLog.readNativeLogTail(file)
        assertTrue(tail.lines().first() == "línea 1")
        assertEquals(10, tail.lines().size)
    }

    @Test
    fun `archivo grande descarta la primera linea parcial`() {
        // Primera línea gigante (40 KB sin salto): el seek de 32 KB
        // cae en mitad de ella y debe descartarse, no aparecer cortada.
        val file = tmp.newFile("vita3k.log")
        val big = "A".repeat(40 * 1024)
        file.writeText(big + "\n" + (1..50).joinToString("\n") { "línea $it" } + "\n")
        val tail = LauncherLog.readNativeLogTail(file)
        val lines = tail.lines()
        assertEquals(40, lines.size)
        assertEquals("línea 11", lines.first())
        assertEquals("línea 50", lines.last())
        assertFalse(tail.contains("A"))
    }

    @Test
    fun `archivo ausente devuelve el mensaje`() {
        val missing = File(tmp.root, "no-existe.log")
        assertEquals("(sin registro del núcleo todavía.)", LauncherLog.readNativeLogTail(missing))
    }

    @Test
    fun `archivo vacio devuelve el mensaje`() {
        val file = tmp.newFile("vita3k.log")
        file.writeText("")
        assertEquals("(sin registro del núcleo todavía.)", LauncherLog.readNativeLogTail(file))
    }
}
