package com.danielsnagustin.childoflight.launcher.data

import java.io.File

/**
 * Estado del firmware de Vita, resuelto SIN pedir nada al usuario.
 *
 * El primer uso del launcher SOLO pide juego + DLCs. El firmware
 * nunca es un paso visible inicial: este resolver comprueba por dentro
 * lo que ya existe en el almacenamiento privado del launcher.
 *
 * Fuentes de firmware EN EL DISPOSITIVO (las únicas que existen):
 * 1. `vita/vs0/` extraído: es donde el núcleo embebido deja el
 *    firmware al instalar el PUP. Ruta VERIFICADA contra Vita3K
 *    upstream (master 3e43bc80): `initialize_session` fija
 *    vita_fs = storage/"vita" (android/jni/native_bootstrap.cpp) y
 *    el firmware instalado es vita_fs/"vs0" no vacío
 *    (app/src/app.cpp: `has_installed_firmware_content`).
 *    OJO: la ruta antigua `vitaRoot/vs0/` (sin `vita/`) es
 *    incorrecta para el núcleo: el núcleo nunca escribe ahí.
 * 2. `firmware/installed.json` con VERSIÓN (`{"version":"3.74",...}`):
 *    marcador que se escribe SOLO tras un `installFirmware` con
 *    éxito en el núcleo. Un marcador antiguo con solo el nombre del
 *    archivo ("PSVUPDAT.PUP") NO es firmware instalado: solo dice
 *    que el PUP se guardó (ver [FirmwareStatus.Saved]).
 * 3. `firmware/PSVUPDAT.PUP` guardado: el PUP que el usuario aportó
 *    con el selector de archivos (Ajustes → Avanzado → Firmware).
 *    Está GUARDADO, no instalado: hay que instalarlo en el núcleo
 *    con `EmbeddedPkgInstaller.installFirmware` antes de jugar
 *    embebido. Por eso [FirmwareStatus.Saved] NO es `ready`: con
 *    solo el PUP guardado, `decide()` sigue eligiendo Vita3K
 *    externo (avisa una vez y lanza igual) hasta que la instalación
 *    en el núcleo tenga éxito.
 *
 * Lo que NO existe / NO se hace (a propósito):
 * - El firmware de Vita3K EXTERNO no es accesible: cada app Android
 *   vive en su propio sandbox y no puede leer el almacenamiento
 *   privado de otra app; Vita3K no expone ningún intent, API ni
 *   ContentProvider para su firmware. Por eso, con destino externo,
 *   un firmware local ausente solo AVISA: Vita3K gestiona su propio
 *   firmware al arrancar (tiene su propio instalador).
 * - No se descarga firmware de internet: es software de Sony con
 *   copyright, no hay fuente legal para distribuirlo ni para que el
 *   launcher lo baje solo. Tampoco se le pide al usuario que lo
 *   busque por internet: si no tiene el PUP, la app lo dice en una
 *   frase ([NO_FIRMWARE_MESSAGE]) y se sigue jugando con Vita3K
 *   externo.
 * - El firmware NUNCA entra en el repo ni en el APK ni se envía a
 *   ningún sitio: solo vive en el teléfono del usuario, que aporta
 *   su propia copia con el selector de archivos del sistema.
 */
sealed class FirmwareStatus {
    /** Firmware instalado EN EL NÚCLEO (vs0 extraído o marcador con versión). `source` describe dónde se encontró. */
    data class Ready(val source: String) : FirmwareStatus()
    /** PUP guardado en el launcher, pendiente de instalar en el núcleo. NO es `ready`. */
    data class Saved(val source: String) : FirmwareStatus()
    /** No hay firmware local. No se distribuye ni se descarga. */
    data object Missing : FirmwareStatus()

    /** true solo si el núcleo embebido puede arrancar con él. Un PUP guardado no basta. */
    val ready: Boolean get() = this is Ready
}

object FirmwareResolver {

    /**
     * Una frase, dentro de la app, cuando no hay PUP en el teléfono.
     * No manda a buscar nada por internet ni a subir nada a ningún
     * sitio: el firmware solo puede venir del propio teléfono.
     */
    const val NO_FIRMWARE_MESSAGE =
        "No hay firmware en este teléfono: el núcleo embebido no puede arrancar el juego sin tu archivo PSVUPDAT.PUP. " +
            "Si lo tienes, impórtalo en Ajustes → Avanzado → Firmware; si no lo tienes, sigue jugando con la app externa, que usa su propio firmware. " +
            "No tienes que enviar el archivo por chat ni subirlo a ningún sitio: el launcher lo lee directamente de tu teléfono."

    /** Magia de un PUP de firmware de Vita ("SCEUF" al inicio). */
    private val PUP_MAGIC = byteArrayOf(0x53, 0x43, 0x45, 0x55, 0x46) // "SCEUF"

    /** Archivo del PUP guardado por el launcher. */
    fun pupFile(vitaRoot: File): File = File(vitaRoot, "firmware/PSVUPDAT.PUP")

    /** Carpeta donde el NÚCLEO deja el firmware extraído (vita_fs/vs0). */
    fun coreVs0Dir(vitaRoot: File): File = File(File(vitaRoot, "vita"), "vs0")

    /** Marcador de instalación en el núcleo (solo válido si trae versión). */
    fun installedMarkerFile(vitaRoot: File): File = File(vitaRoot, "firmware/installed.json")

    fun resolve(vitaRoot: File): FirmwareStatus {
        // 1. Firmware extraído POR EL NÚCLEO (vita/vs0, no vacío).
        //    Criterio igual que upstream (`has_installed_firmware_content`:
        //    existe, es directorio y no está vacío).
        val vs0 = coreVs0Dir(vitaRoot)
        if (vs0.isDirectory && (vs0.listFiles()?.isNotEmpty() == true)) {
            return FirmwareStatus.Ready("extraído en el núcleo (vita/vs0)")
        }
        // 2. Marcador con versión: solo lo escribe una instalación en
        //    el núcleo con éxito (ver markInstalled). Un marcador
        //    antiguo con solo el nombre del archivo no vale: cae al
        //    caso 3 (PUP guardado, pendiente de instalar).
        val version = installedVersion(vitaRoot)
        if (version != null) {
            return FirmwareStatus.Ready("instalado (versión $version)")
        }
        // 3. PUP guardado, pendiente de instalar en el núcleo.
        val pup = pupFile(vitaRoot)
        if (pup.isFile && pup.length() > 0) {
            return FirmwareStatus.Saved("PSVUPDAT.PUP guardado")
        }
        return FirmwareStatus.Missing
    }

    /**
     * Versión del firmware instalado según el marcador, o null si no
     * hay marcador con versión. Acepta el JSON de [markInstalled]
     * (`{"version":"3.74","file":"PSVUPDAT.PUP"}`) o un texto plano
     * con solo la versión ("3.74"). Un nombre de archivo
     * ("PSVUPDAT.PUP") NO es una versión y devuelve null.
     */
    fun installedVersion(vitaRoot: File): String? {
        val marker = installedMarkerFile(vitaRoot)
        val text = runCatching { marker.takeIf { it.isFile }?.readText()?.trim() }.getOrNull()
        if (text.isNullOrBlank()) return null
        val jsonVersion = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.getOrNull(1)
        if (!jsonVersion.isNullOrBlank()) return jsonVersion
        if (Regex("^\\d+\\.\\d+(\\.\\d+)?$").matches(text)) return text
        return null
    }

    /**
     * Registra que el núcleo instaló el firmware con éxito. Solo lo
     * llama el flujo de instalación tras un `installFirmware` que
     * devolvió versión (nunca al guardar el PUP: guardar no es
     * instalar). Devuelve false si no se pudo escribir el marcador.
     */
    fun markInstalled(vitaRoot: File, version: String, fileName: String = "PSVUPDAT.PUP"): Boolean {
        val cleanVersion = version.trim().replace("\"", "").replace("\n", "").replace("\r", "")
        if (cleanVersion.isBlank()) return false
        return try {
            val dir = File(vitaRoot, "firmware")
            if (!dir.exists()) dir.mkdirs()
            installedMarkerFile(vitaRoot).writeText(
                "{\"version\":\"$cleanVersion\",\"file\":\"${fileName.replace("\"", "")}\"}",
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Borra TODO el firmware local: PUP guardado, marcador y vs0
     * extraído (núcleo y ruta antigua). No toca juego, DLCs ni saves.
     * Devuelve true si se borró algo.
     */
    fun deleteFirmware(vitaRoot: File): Boolean {
        var deleted = false
        if (pupFile(vitaRoot).delete()) deleted = true
        if (installedMarkerFile(vitaRoot).delete()) deleted = true
        if (coreVs0Dir(vitaRoot).deleteRecursively()) deleted = true
        // Ruta antigua (vitaRoot/vs0): nunca la escribe el núcleo, pero
        // si alguien la creó a mano, borrarla evita un "instalado"
        // fantasma en diagnósticos viejos.
        if (File(vitaRoot, "vs0").deleteRecursively()) deleted = true
        return deleted
    }

    /** true si los primeros bytes son la magia SCEUF de un PUP. */
    fun isPupBytes(firstBytes: ByteArray): Boolean {
        if (firstBytes.size < PUP_MAGIC.size) return false
        return PUP_MAGIC.indices.all { firstBytes[it] == PUP_MAGIC[it] }
    }

    /** true si el archivo existe, no está vacío y empieza por SCEUF. */
    fun isValidPupFile(file: File): Boolean {
        if (!file.isFile || file.length() <= 0) return false
        return try {
            file.inputStream().use { input ->
                val head = ByteArray(PUP_MAGIC.size)
                val read = input.read(head)
                read == PUP_MAGIC.size && isPupBytes(head)
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Texto corto para Diagnóstico y Ajustes. */
    fun label(status: FirmwareStatus): String = when (status) {
        is FirmwareStatus.Ready -> "Instalado (${status.source})"
        is FirmwareStatus.Saved -> "Guardado, pendiente de instalar en el núcleo (${status.source})"
        FirmwareStatus.Missing -> "No guardado en el launcher (la app externa usa el suyo)"
    }
}
