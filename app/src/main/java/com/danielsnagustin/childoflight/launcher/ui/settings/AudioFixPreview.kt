package com.danielsnagustin.childoflight.launcher.ui.settings

import com.danielsnagustin.childoflight.launcher.model.AudioBackend
import com.danielsnagustin.childoflight.launcher.model.LauncherSettings

/**
 * "Corregir audio" (Fase 2, Ola 2, Etapa 4). Funciones PURAS, sin
 * Android ni Compose: el diálogo de confirmación muestra exactamente
 * lo que va a cambiar y el ViewModel aplica lo mismo con
 * audioFixTarget. La limpieza de la caché de shaders es un efecto
 * aparte del ViewModel (no forma parte de los ajustes) y se avisa en
 * el diálogo: lo limpiado no se recupera, se vuelve a llenar solo.
 */
data class AudioFixChange(val label: String, val before: String, val after: String)

/** Los ajustes tal como quedan tras "Corregir audio". Misma fuente que usa el ViewModel. */
fun audioFixTarget(current: LauncherSettings): LauncherSettings = current.copy(
    audioBackend = AudioBackend.SDL,
    audioBufferSize = 1024,
    ngsEnable = true,
    shaderCache = true,
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
            "Tamaño de buffer de audio",
            "${current.audioBufferSize}",
            "${target.audioBufferSize}",
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
    )
}
