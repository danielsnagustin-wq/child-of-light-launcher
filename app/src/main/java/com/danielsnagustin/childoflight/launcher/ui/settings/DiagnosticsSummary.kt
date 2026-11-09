package com.danielsnagustin.childoflight.launcher.ui.settings

/**
 * Errores recientes del log (Ola 3, Etapa 10). Puro, testeable en JVM
 * (DiagnosticsSummaryTest).
 *
 * El log del launcher es texto libre (`[hora] [tag] mensaje`): no hay
 * niveles estructurados [E]/[W]. "Error reciente" = línea de nivel
 * error: FATAL (crash), ERROR/error, exception, failed, fallo/falló,
 * crash/crashed. R2-P2-20: antes se casaba también con el substring
 * "fail", así que líneas informativas de fallback (Vulkan→OpenGL, que
 * es normal) aparecían como "errores recientes". "fail" a secas ya no
 * casa: solo "failed" (fallo real).
 *
 * Se devuelven las más recientes, sin inventar nada: si no hay
 * ninguna, la lista está vacía y la UI lo dice así.
 */
private val ERROR_WORDS = listOf("fatal", "[e]", "error", "exception", "failed", "fallo", "falló", "crashed", "crash")

// Marco de stack Java/Kotlin ("at com.foo.Bar.metodo(Archivo.kt:12)"):
// un crash deja decenas seguidos y son una sola entrada de error, no N.
// \S+ y no [\w.$]+: en JDK 17+ los marcos llevan prefijo de módulo
// ("at java.base/java.lang.Thread.run(...)") y con [\w.$]+ esos no
// casaban, la cola de 8 quedaba llena de marcos y la cabecera con
// "crash" se perdía (test CrashDiagnosticsTest, run #82).
private val STACK_FRAME = Regex("\\bat \\S+\\(")

fun extractRecentErrorLines(logText: String, maxLines: Int = 8): List<String> {
    if (logText.isBlank()) return emptyList()
    val limit = maxLines.coerceAtLeast(1)
    val filtered = logText.lines()
        .map { it.trim() }
        .filter { line -> line.isNotEmpty() && ERROR_WORDS.any { word -> line.lowercase().contains(word) } }
    // Colapsa marcos de stack consecutivos a uno solo: si no, un crash
    // con 30 marcos esconde su cabecera ("Uncaught exception (crash)")
    // y "errores recientes" solo mostraría líneas "at ...".
    val collapsed = mutableListOf<String>()
    var lastWasFrame = false
    for (line in filtered) {
        val isFrame = STACK_FRAME.containsMatchIn(line)
        if (isFrame && lastWasFrame) continue
        collapsed.add(line)
        lastWasFrame = isFrame
    }
    return collapsed.takeLast(limit)
}
