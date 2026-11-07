package com.danielsnagustin.childoflight.launcher.data

import java.io.File

/**
 * Lectura de referencia del directorio de trofeos de Vita3K.
 *
 * Ubicación real: ux0/user/00/trophy/<TITLE_ID>/ dentro del vita_fs
 * del launcher. Vita3K tiene soporte parcial de trofeos y su propio
 * manager; el archivo TROPHY.TRP es un contenedor cuyo formato NO se
 * decodifica en Etapa 1 (requeriría el parser del formato TRP de Sony).
 *
 * Por eso este lector es honesto y limitado: informa SI existe el
 * directorio, QUÉ archivos hay y si vino un TROPHY.TRP con el juego
 * importado. NO afirma cuántos trofeos desbloqueó Vita3K ni inventa
 * un parseo. La lista bundled de 27 trofeos se usa como referencia
 * y el progreso que se MUESTRA es el local del launcher (DataStore).
 */
data class TrophyStoreScan(
    val trophyDirExists: Boolean,
    val files: List<String>,
    val trpPresent: Boolean,
    val trpSizeBytes: Long,
    val note: String,
)

object TrophyStoreReader {

    fun scan(vitaRoot: File, titleId: String): TrophyStoreScan {
        val dir = File(vitaRoot, "ux0/user/00/trophy/$titleId")
        if (!dir.exists() || !dir.isDirectory) {
            return TrophyStoreScan(
                trophyDirExists = false,
                files = emptyList(),
                trpPresent = false,
                trpSizeBytes = 0L,
                note = "Vita3K aún no creó la carpeta de trofeos para $titleId. " +
                    "Se crea al jugar con Vita3K. El progreso que se muestra es el local del launcher.",
            )
        }
        val files = dir.listFiles()?.map { it.name }?.sorted() ?: emptyList()
        val trp = File(dir, "TROPHY.TRP")
        val trpPresent = trp.exists()
        return TrophyStoreScan(
            trophyDirExists = true,
            files = files,
            trpPresent = trpPresent,
            trpSizeBytes = if (trpPresent) trp.length() else 0L,
            note = if (trpPresent) {
                "TROPHY.TRP encontrado (${trp.length()} bytes). El formato TRP no se " +
                    "decodifica en Etapa 1: la lista de 27 trofeos bundled es la referencia " +
                    "y el progreso mostrado es el local del launcher."
            } else {
                "Carpeta de trofeos existe pero sin TROPHY.TRP. El progreso mostrado es el local del launcher."
            },
        )
    }

    /** ¿El juego importado traía su TROPHY.TRP dentro de ux0/app/<TITLE>/ ? */
    fun gameBundledTrp(appDir: File): File? {
        // TROPHY.TRP vive en sce_sys/trophy/ dentro del juego en Vita.
        val candidates = listOf(
            File(appDir, "sce_sys/trophy/TROPHY.TRP"),
            File(appDir, "TROPHY.TRP"),
        )
        return candidates.firstOrNull { it.exists() }
    }
}
