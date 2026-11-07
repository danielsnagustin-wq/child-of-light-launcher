package com.danielsnagustin.childoflight.launcher.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Log propio del launcher (no es el log de Vita3K).
 * Se guarda en <filesDir>/vita3k/launcher.log y es lo que muestra la
 * pantalla de Diagnóstico y lo que adjunta "Reportar bug".
 * Si el archivo supera 200 KB se rota (se conserva la mitad reciente).
 */
object LauncherLog {

    private fun logFile(context: Context): File {
        val dir = File(context.filesDir, "vita3k")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "launcher.log")
    }

    @Synchronized
    fun append(context: Context, tag: String, message: String) {
        try {
            val file = logFile(context)
            if (file.exists() && file.length() > 200 * 1024) {
                val lines = file.readLines()
                file.writeText(lines.takeLast(200).joinToString("\n") + "\n")
            }
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            file.appendText("[$time] [$tag] $message\n")
        } catch (_: Exception) {
            // El log nunca debe romper el launcher.
        }
    }

    fun readLast(context: Context, maxLines: Int = 60): String {
        return try {
            val file = logFile(context)
            if (!file.exists()) return "(Sin entradas de log todavía.)"
            file.readLines().takeLast(maxLines).joinToString("\n")
        } catch (e: Exception) {
            "(No se pudo leer el log: ${e.message})"
        }
    }

    fun file(context: Context): File = logFile(context)
}
