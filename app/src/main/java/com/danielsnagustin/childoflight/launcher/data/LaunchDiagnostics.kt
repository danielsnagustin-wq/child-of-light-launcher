package com.danielsnagustin.childoflight.launcher.data

/**
 * Formato de las líneas de diagnóstico del arranque (P3, crash
 * ~15:06 del 2026-11-08 sin stack trace).
 *
 * Funciones puras y testeables (CrashDiagnosticsTest): el log del
 * launcher es texto libre, y estas líneas son las que permiten leer
 * en el próximo reporte de bug hasta dónde llegó cada intento de
 * JUGAR. No escriben nada por sí mismas: las llaman
 * LauncherViewModel.play() y Vita3KLauncher.tryLaunchEmbedded().
 */
fun formatLaunchAttempt(titleId: String?, corePresent: Boolean, firmwareInstalled: Boolean): String {
    val title = titleId ?: "(sin determinar)"
    val core = if (corePresent) "presente" else "ausente"
    val firmware = if (firmwareInstalled) "instalado" else "no instalado"
    return "JUGAR pulsado: title=$title núcleo=$core firmware=$firmware"
}

fun formatLoadGameResult(titleId: String, code: Int): String {
    val detail = when (code) {
        0 -> "OK"
        -2 -> "sin sesión"
        -3 -> "juego no instalado en el núcleo"
        else -> "código desconocido"
    }
    return "loadGame($titleId) = $code ($detail)"
}
