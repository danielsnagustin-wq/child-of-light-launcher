package com.danielsnagustin.childoflight.launcher.data

import java.io.File

/**
 * Estado del firmware de Vita, resuelto SIN pedir nada al usuario.
 *
 * El primer uso del launcher SOLO pide juego + DLCs. El firmware
 * nunca es un paso visible inicial: este resolver comprueba por dentro
 * lo que ya existe en el almacenamiento privado del launcher:
 * 1. `vs0/` extraído (Vita3K extrae el PUP ahí al instalarlo).
 * 2. `firmware/PSVUPDAT.PUP` guardado (importado antes, vía la opción
 *    avanzada de Ajustes → Diagnóstico).
 * 3. `firmware/installed.json` (marcador de una importación anterior).
 *
 * Lo que NO hace (a propósito):
 * - No descarga firmware de internet: es software de Sony con
 *   copyright, no hay fuente legal para distribuirlo ni para que el
 *   launcher lo baje solo. Tampoco se le pide al usuario que lo
 *   busque por internet.
 * - No puede ver el firmware de Vita3K EXTERNO: cada app Android
 *   vive en su propio sandbox y no puede leer el almacenamiento
 *   privado de otra app. Por eso, cuando el destino es Vita3K
 *   externo, un firmware local ausente solo AVISA con una frase:
 *   Vita3K gestiona su propio firmware al arrancar (tiene su propio
 *   instalador). Solo el núcleo EMBEBIDO (Etapa 2) depende del
 *   firmware local, y ahí sí bloquea el lanzamiento con una frase.
 */
sealed class FirmwareStatus {
    /** Firmware local utilizable. `source` describe dónde se encontró. */
    data class Ready(val source: String) : FirmwareStatus()
    /** No hay firmware local. No se distribuye ni se descarga. */
    data object Missing : FirmwareStatus()

    val ready: Boolean get() = this is Ready
}

object FirmwareResolver {

    fun resolve(vitaRoot: File): FirmwareStatus {
        val vs0 = File(vitaRoot, "vs0")
        if (vs0.isDirectory && (vs0.listFiles()?.isNotEmpty() == true)) {
            return FirmwareStatus.Ready("extraído (vs0)")
        }
        val pup = File(vitaRoot, "firmware/PSVUPDAT.PUP")
        if (pup.isFile && pup.length() > 0) {
            return FirmwareStatus.Ready("PSVUPDAT.PUP guardado")
        }
        val marker = File(vitaRoot, "firmware/installed.json")
        if (marker.isFile) {
            return FirmwareStatus.Ready("marcador de instalación")
        }
        return FirmwareStatus.Missing
    }

    /** Texto corto para Diagnóstico. */
    fun label(status: FirmwareStatus): String = when (status) {
        is FirmwareStatus.Ready -> "Instalado (${status.source})"
        FirmwareStatus.Missing -> "No guardado en el launcher (Vita3K externo usa el suyo)"
    }
}
