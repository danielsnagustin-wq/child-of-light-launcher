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

    /**
     * Formato del reporte de un crash no capturado (P3, crash ~15:06
     * del 2026-11-08 sin stack trace). Puro y testeable
     * (CrashDiagnosticsTest): la primera línea contiene "crash" y
     * "exception" para que extractRecentErrorLines lo recoja en el
     * reporte de bug, y el resto es el stack trace completo con sus
     * causas encadenadas.
     */
    fun formatCrashReport(thread: Thread, throwable: Throwable): String {
        return buildString {
            appendLine("FATAL: Uncaught exception (crash) en el hilo \"${thread.name}\" (id=${thread.id})")
            appendLine("FATAL: ${throwable.javaClass.name}: ${throwable.message}")
            var cause = throwable.cause
            while (cause != null) {
                appendLine("FATAL: Causado por ${cause.javaClass.name}: ${cause.message}")
                cause = cause.cause
            }
            throwable.stackTrace.forEach { frame ->
                appendLine("FATAL:     at $frame")
            }
        }
    }

    /**
     * Instala el handler de excepciones no capturadas (P3): el crash
     * del 2026-11-08 ~15:06 no dejó ningún rastro (ni stack trace ni
     * logcat accesible desde el teléfono). El handler escribe el
     * stack trace en launcher.log ANTES de delegar en el handler
     * anterior, así el próximo reporte de bug lo incluye.
     * El handler nunca se tumba a sí mismo (todo con try/catch sobre
     * Throwable) y NO oculta el crash al sistema: el handler anterior
     * sigue mostrando el diálogo y cerrando el proceso. Si no hubiera
     * handler anterior, se mata el proceso para no dejar la app en un
     * estado roto. Instalar en MainActivity.onCreate, antes de crear
     * el ViewModel.
     */
    fun installCrashHandler(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                append(appContext, "crash", formatCrashReport(thread, throwable))
            } catch (_: Throwable) {
                // El handler no debe fallar: el crash sigue su curso.
            }
            try {
                if (previous != null) {
                    previous.uncaughtException(thread, throwable)
                } else {
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
            } catch (_: Throwable) {
                // Sin handler anterior, el sistema ya cierra el proceso.
            }
        }
    }
}
