package com.danielsnagustin.childoflight.launcher.ui.settings

import com.danielsnagustin.childoflight.launcher.model.AudioBackend
import com.danielsnagustin.childoflight.launcher.model.LauncherSettings

/**
 * "Corregir audio" (Fase 2, Ola 2, Etapa 4). Funciones PURAS, sin
 * Android ni Compose: el diálogo de confirmación muestra exactamente
 * lo que va a cambiar y el ViewModel aplica lo mismo con
 * audioFixTarget. Ronda 0.2.64 (H-1 tiempo de carga): ya NO limpia la
 * caché de shaders (no forma parte de los ajustes ni del efecto: el
 * audio no tiene nada que ver con ella).
 */
data class AudioFixChange(val label: String, val before: String, val after: String)

/** Los ajustes tal como quedan tras "Corregir audio". Misma fuente que usa el ViewModel. */
fun audioFixTarget(current: LauncherSettings): LauncherSettings = current.copy(
    audioBackend = AudioBackend.SDL,
    ngsEnable = true,
    shaderCache = true,
    // Ronda 0.2.70 (5-P2-3): "Corregir audio" promete volver a un audio
    // conocido-bueno: el experimento también se apaga (como el modo seguro).
    expAudioSdl = false,
)

/** Las 4 filas que muestra el diálogo de confirmación (antes -> después). */
fun audioFixChanges(current: LauncherSettings): List<AudioFixChange> {
    val target = audioFixTarget(current)
    fun onOff(value: Boolean) = if (value) "ON" else "OFF"
    return listOf(
        AudioFixChange(
            "Backend de audio",
            current.audioBackend.label,
            target.audioBackend.label,
        ),
        AudioFixChange(
            "NGS (audio del sistema Vita)",
            onOff(current.ngsEnable),
            onOff(target.ngsEnable),
        ),
        AudioFixChange(
            "Caché de shaders",
            onOff(current.shaderCache),
            onOff(target.shaderCache),
        ),
        AudioFixChange(
            "Audio experimental (SDL y Cubeb)",
            onOff(current.expAudioSdl),
            onOff(target.expAudioSdl),
        ),
    )
}
