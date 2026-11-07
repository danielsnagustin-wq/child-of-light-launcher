package com.danielsnagustin.childoflight.launcher.model

/**
 * Una sesión / partida de Child of Light.
 *
 * En Vita el guardado es UNA sola carpeta
 * ux0/user/00/savedata/<TITLE_ID>/, así que el launcher gestiona
 * varias sesiones por separado: cada sesión guarda su propia copia
 * de savedata + su progreso de logros locales + su perfil de control,
 * en sessions/<id>/ dentro del vita_fs. La carpeta "viva"
 * (ux0/user/00/savedata/<TITLE_ID>/) siempre contiene la sesión
 * ACTIVA; al cambiar de sesión se hace snapshot de la actual y se
 * restaura la otra. Nunca se sobrescribe sin avisar.
 *
 * - chapter: texto MANUAL del usuario (p. ej. "Capítulo 3 — Lemuria").
 *   Los saves de Vita son binarios y en Etapa 1 no se decodifica el
 *   capítulo desde ellos; no se inventa un capítulo leído del save.
 * - playTimeMillis: tiempo jugado acumulado, medido por el launcher
 *   (desde JUGAR hasta volver a la app). Best-effort, no del juego.
 */
data class GameSession(
    val id: String,
    val name: String,
    val titleId: String,
    val createdAtMillis: Long,
    val lastPlayedAtMillis: Long,
    val playTimeMillis: Long,
    val chapter: String = "",
    val savedataFiles: Int = 0,
    val savedataBytes: Long = 0L,
    val unlockedTrophies: Int = 0,
) {
    /** Sesión vacía: nunca se ha jugado / no tiene savedata guardado. */
    val isEmpty: Boolean get() = savedataFiles == 0

    fun playTimeLabel(): String {
        val minutes = playTimeMillis / 60_000L
        if (minutes < 1) return "0 min"
        val hours = minutes / 60
        val rest = minutes % 60
        return if (hours == 0L) "$minutes min" else "$hours h $rest min"
    }
}
