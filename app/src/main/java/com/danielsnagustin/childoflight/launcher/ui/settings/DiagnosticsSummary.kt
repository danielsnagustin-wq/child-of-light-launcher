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

fun extractRecentErrorLines(logText: String, maxLines: Int = 8): List<String> {
    if (logText.isBlank()) return emptyList()
    val limit = maxLines.coerceAtLeast(1)
    return logText.lines()
        .map { it.trim() }
        .filter { line -> line.isNotEmpty() && ERROR_WORDS.any { word -> line.lowercase().contains(word) } }
        .takeLast(limit)
}
