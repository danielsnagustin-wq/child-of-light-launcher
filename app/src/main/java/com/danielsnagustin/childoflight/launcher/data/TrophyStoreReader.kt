package com.danielsnagustin.childoflight.launcher.data

import com.danielsnagustin.childoflight.launcher.core.EmbeddedCoreLayout
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
 * directorio y si vino un TROPHY.TRP con el juego importado. NO afirma
 * cuántos trofeos desbloqueó Vita3K ni inventa un parseo. La lista
 * bundled de 27 trofeos se usa como referencia y el progreso que se
 * MUESTRA es el local del launcher (DataStore).
 *
 * R2-P2-37: el campo `files` de TrophyStoreScan se eliminó: se
 * calculaba pero nada lo mostraba (la UI solo usa `note`).
 */
data class TrophyStoreScan(
    val trophyDirExists: Boolean,
    val trpPresent: Boolean,
    val trpSizeBytes: Long,
    val note: String,
)

object TrophyStoreReader {

    fun scan(vitaRoot: File, titleId: String): TrophyStoreScan {
        val dir = File(EmbeddedCoreLayout.resolveUx0Dir(vitaRoot), "user/00/trophy/$titleId")
        if (!dir.exists() || !dir.isDirectory) {
            return TrophyStoreScan(
                trophyDirExists = false,
                trpPresent = false,
                trpSizeBytes = 0L,
                note = "El núcleo aún no creó la carpeta de trofeos para $titleId. " +
                    "Se crea al jugar. El progreso que se muestra es el local del launcher.",
            )
        }
        val trp = File(dir, "TROPHY.TRP")
        val trpPresent = trp.exists()
        return TrophyStoreScan(
            trophyDirExists = true,
            trpPresent = trpPresent,
            trpSizeBytes = if (trpPresent) trp.length() else 0L,
            note = if (trpPresent) {
                "TROPHY.TRP encontrado (${trp.length()} bytes). Su contenido " +
                    "se verifica en el diagnóstico TRP de esta pantalla; el " +
                    "progreso mostrado es el local del launcher."
            } else {
                "Carpeta de trofeos existe pero sin TROPHY.TRP. El progreso mostrado es el local del launcher."
            },
        )
    }

    /** ¿El juego importado traía su TROPHY.TRP dentro de ux0/app/<TITLE>/ ? */
    fun gameBundledTrp(appDir: File): File? {
        // TROPHY.TRP vive en sce_sys/trophy/ dentro del juego en Vita.
        // Búsqueda sin distinguir mayúsculas en cada nivel (P2-C12):
        // un juego con SCE_SYS/TROPHY/ no reportaba su TRP.
        val sceSys = ImportTreeSearch.findChildIgnoreCase(appDir, "sce_sys")
            ?.takeIf { it.isDirectory }
        val trophyDir = sceSys?.let {
            ImportTreeSearch.findChildIgnoreCase(it, "trophy")?.takeIf { d -> d.isDirectory }
        }
        val nested = trophyDir?.let {
            ImportTreeSearch.findChildIgnoreCase(it, "TROPHY.TRP")?.takeIf { f -> f.isFile }
        }
        if (nested != null) return nested
        return ImportTreeSearch.findChildIgnoreCase(appDir, "TROPHY.TRP")?.takeIf { it.isFile }
    }
}
