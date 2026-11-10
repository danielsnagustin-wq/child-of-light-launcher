package com.danielsnagustin.childoflight.launcher.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Log propio del launcher (no es el log de Vita3K).
 * Se guarda en <filesDir>/vita3k/launcher.log y es lo que muestra la
 * pantalla de Diagnóstico y lo que adjunta "Reportar bug".
 * Si el archivo supera 200 KB se rota (se conserva la mitad reciente).
 */
object LauncherLog {

    /** Tamaño a partir del cual el log se rota (se conservan las 200 líneas recientes). */
    const val MAX_LOG_BYTES: Long = 200 * 1024

    /** Líneas que conserva una rotación. */
    const val KEEP_LINES_AFTER_ROTATION: Int = 200

    /**
     * R4-P2-5: intervalo mínimo entre intentos de rotación. La rotación
     * (readLines + writeText del archivo entero) corría DENTRO del
     * candado de cada append y en CADA append mientras el archivo
     * superara 200 KB: cada onResume (scanStatus) pagaba la rotación
     * entera en el hilo que llamaba. Ahora el intento se hace como
     * mucho una vez por minuto: el coste por append vuelve a ser solo
     * el appendText, y la rotación sigue ocurriendo (tarde o temprano,
     * el siguiente append tras el minuto rota). El candado se mantiene
     * para no perder líneas entre lectores/escritores concurrentes.
     */
    const val ROTATION_MIN_INTERVAL_MILLIS: Long = 60_000

    private val lastRotationAttemptMillis = AtomicLong(0L)

    /**
     * R4-P2-5: true si el archivo es lo bastante grande como para
     * rotar. Puro (solo el tamaño), testeable en JVM.
     */
    fun shouldRotate(fileLengthBytes: Long): Boolean = fileLengthBytes > MAX_LOG_BYTES

    /**
     * R4-P2-5: true si ya pasó el intervalo mínimo desde el último
     * intento de rotación. Puro, testeable en JVM.
     */
    fun shouldAttemptRotation(nowMillis: Long, lastAttemptMillis: Long): Boolean =
        nowMillis - lastAttemptMillis >= ROTATION_MIN_INTERVAL_MILLIS

    /**
     * R4-P2-5: rota [file] si supera MAX_LOG_BYTES: conserva solo las
     * KEEP_LINES_AFTER_ROTATION líneas recientes. Devuelve true si
     * rotó y false si no hacía falta o no se pudo leer/escribir.
     * Pura salvo File, para poder probarla en la JVM sin Android
     * (el mismo patrón que writeConfigIfChanged).
     */
    fun rotateLogFile(file: File): Boolean {
        return try {
            if (!file.isFile || file.length() <= MAX_LOG_BYTES) return false
            val lines = file.readLines()
            file.writeText(lines.takeLast(KEEP_LINES_AFTER_ROTATION).joinToString("\n") + "\n")
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * R4-P2-5: true si el resumen de scanStatus debe registrarse: solo
     * cuando CAMBIÓ respecto al último registrado (o no hay anterior).
     * scanStatus() se llama en init, en cada onResume y al volver del
     * juego, y antes añadía una línea idéntica por cada llamada: las
     * ráfagas llenaban el log y aceleraban la rotación. Puro y
     * testeable en JVM; el último resumen vive en
     * GameLibraryRepository (un solo llamador de esta línea).
     */
    fun shouldLogScanStatus(lastLoggedSummary: String?, currentSummary: String): Boolean =
        lastLoggedSummary != currentSummary

    private fun logFile(context: Context): File {
        val dir = File(context.filesDir, "vita3k")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "launcher.log")
    }

    @Synchronized
    fun append(context: Context, tag: String, message: String) {
        try {
            val file = logFile(context)
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            file.appendText("[$time] [$tag] $message\n")
            // R4-P2-5: la rotación ya no corre en cada append: solo se
            // intenta si el archivo supera el tope Y pasó el intervalo
            // mínimo desde el último intento (como mucho una vez por
            // minuto). El append de arriba ya quedó escrito; la
            // rotación conserva las 200 líneas recientes, incluida esta.
            if (shouldRotate(file.length()) &&
                shouldAttemptRotation(System.currentTimeMillis(), lastRotationAttemptMillis.get())
            ) {
                lastRotationAttemptMillis.set(System.currentTimeMillis())
                rotateLogFile(file)
            }
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

    /**
     * Cola del log NATIVO del núcleo (vita3k.log, Fase 2, 2026-11-09):
     * el log del launcher no distingue si el juego sigue cargando
     * (H1) o si el núcleo no presenta nada (H3); el log nativo sí
     * ("Game started", errores del renderer, avisos de
     * superficie/swapchain). Lee por BYTES (el log nativo puede pesar
     * MB): RandomAccessFile, seek a max(0, len - 32 KB), descarta la
     * primera línea parcial, takeLast(40). Puro sobre un File
     * (testeable en JVM, patrón CrashDiagnosticsTest). Nunca lanza:
     * si no existe o no se puede leer, devuelve el mensaje entre
     * paréntesis. El reporte nunca debe fallar por esto.
     */
    fun readNativeLogTail(file: File, maxLines: Int = 40): String {
        return try {
            if (!file.isFile) return "(sin registro del núcleo todavía.)"
            val bytes = java.io.RandomAccessFile(file, "r").use { raf ->
                val len = raf.length()
                val start = maxOf(0L, len - 32 * 1024)
                raf.seek(start)
                val buf = ByteArray((len - start).toInt())
                raf.readFully(buf)
                buf
            }
            val text = String(bytes, Charsets.UTF_8)
            val lines = text.lines()
            // La primera línea puede estar cortada por el seek: se
            // descarta solo si empezamos a mitad del archivo.
            val usable = if (bytes.size >= 32 * 1024 && lines.size > 1) lines.drop(1) else lines
            val tail = usable.filter { it.isNotBlank() }.takeLast(maxLines)
            if (tail.isEmpty()) "(sin registro del núcleo todavía.)" else tail.joinToString("\n")
        } catch (_: Exception) {
            "(sin registro del núcleo todavía.)"
        }
    }

    fun file(context: Context): File = logFile(context)

    /**
     * Formato del reporte de un crash no capturado (P3, crash ~15:06
     * del 2026-11-08 sin stack trace). Puro y testeable
     * (CrashDiagnosticsTest): la primera línea contiene "crash" y
     * "exception" para que extractRecentErrorLines lo recoja en el
     * reporte de bug, y el resto es el stack trace completo con sus
     * causas encadenadas. P2-38: cada causa trae también SU stack
     * trace (antes solo su clase y mensaje) y las excepciones
     * suprimidas (suppressed) se listan con el suyo: un crash con
     * causa encadenada ya no pierde la mitad del informe.
     */
    fun formatCrashReport(thread: Thread, throwable: Throwable): String {
        return buildString {
            appendLine("FATAL: Uncaught exception (crash) en el hilo \"${thread.name}\" (id=${thread.id})")
            appendLine("FATAL: ${throwable.javaClass.name}: ${throwable.message}")
            var cause = throwable.cause
            while (cause != null) {
                appendLine("FATAL: Causado por ${cause.javaClass.name}: ${cause.message}")
                cause.stackTrace.forEach { frame ->
                    appendLine("FATAL:     at $frame")
                }
                cause.suppressed.forEach { suppressed ->
                    appendLine("FATAL: Suprimida en la causa: ${suppressed.javaClass.name}: ${suppressed.message}")
                    suppressed.stackTrace.forEach { frame ->
                        appendLine("FATAL:     at $frame")
                    }
                }
                cause = cause.cause
            }
            throwable.stackTrace.forEach { frame ->
                appendLine("FATAL:     at $frame")
            }
            throwable.suppressed.forEach { suppressed ->
                appendLine("FATAL: Suprimida: ${suppressed.javaClass.name}: ${suppressed.message}")
                suppressed.stackTrace.forEach { frame ->
                    appendLine("FATAL:     at $frame")
                }
            }
        }
    }

    // P2-35: el handler se instala UNA sola vez por proceso. Cada
    // recreación de la actividad (p. ej. rotar) volvía a envolver el
    // handler anterior y en el siguiente crash el informe se escribía
    // N veces en launcher.log. AtomicBoolean: la instalación es
    // atómica aunque dos hilos la pidan a la vez.
    private val crashHandlerInstalled = AtomicBoolean(false)

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
        if (!crashHandlerInstalled.compareAndSet(false, true)) return
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
