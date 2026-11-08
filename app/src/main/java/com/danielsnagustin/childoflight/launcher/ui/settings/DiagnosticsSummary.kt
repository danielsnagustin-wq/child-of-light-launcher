package com.danielsnagustin.childoflight.launcher.ui.settings

/**
 * Errores recientes del log (Ola 3, Etapa 10). Puro, testeable en JVM
 * (DiagnosticsSummaryTest).
 *
 * El log del launcher es texto libre: no hay niveles estructurados.
 * "Error reciente" = línea que menciona error, excepción o fallo
 * (en español o en inglés, porque el núcleo escribe en inglés y el
 * launcher en español). Se devuelven las más recientes, sin inventar
 * nada: si no hay ninguna, la lista está vacía y la UI lo dice así.
 */
private val ERROR_WORDS = listOf("error", "exception", "failed", "fail", "fallo", "falló", "crashed", "crash")

fun extractRecentErrorLines(logText: String, maxLines: Int = 8): List<String> {
    if (logText.isBlank()) return emptyList()
    val limit = maxLines.coerceAtLeast(1)
    return logText.lines()
        .map { it.trim() }
        .filter { line -> line.isNotEmpty() && ERROR_WORDS.any { word -> line.lowercase().contains(word) } }
        .takeLast(limit)
}
