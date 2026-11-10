package com.danielsnagustin.childoflight.launcher.data

import com.danielsnagustin.childoflight.launcher.core.EmbeddedCoreLayout
import java.io.File

/**
 * Lectura de referencia del directorio de trofeos de Vita3K.
 *
 * Ubicación real: ux0/user/00/trophy/data/<NPCOMMID>/ dentro del
 * vita_fs del launcher (R6-P1-18). Vita3K tiene soporte parcial de
 * trofeos y su propio manager; lo instalado es TROPCONF.SFM,
 * TROPUSR.DAT y TROPxxx.PNG bajo trophy/data/<NPCOMMID>/ (psdevwiki
 * "Trophy"; log real de Vita3K, issue Vita3K-Android #649:
 * trophy/data/NPWR12049_00/TROPUSR.DAT). El TROPHY.TRP es solo el
 * instalador: no existe como archivo instalado, así que la ruta vieja
 * trophy/<TITLE_ID>/TROPHY.TRP no se busca. El pin no trae el módulo
 * np/trophy/: la ruta queda pendiente de confirmar en el teléfono.
 *
 * Por eso este lector es honesto y limitado: informa SI existe el
 * directorio y si vino un TROPCONF.SFM instalado. NO afirma
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

    /**
     * R6-P1-18: escanea la ubicación REAL de los trofeos instalados:
     * ux0/user/00/trophy/data/<NPCOMMID>/, con TROPCONF.SFM como
     * marcador de datos instalados (y TROPUSR.DAT como progreso del
     * núcleo, si está). El NPCOMMID se resuelve del <npcommid> del
     * TROPHY.TRP empaquetado del juego; si no se puede resolver, se
     * miran todos los directorios bajo trophy/data/ (el launcher es
     * de un solo juego). La ruta vieja trophy/<TITLE>/TROPHY.TRP no
     * existe como instalado: el TRP es solo el instalador.
     * [TrophyStoreScan.trpPresent] es ahora "TROPCONF.SFM instalado"
     * (no "TROPHY.TRP presente") y [TrophyStoreScan.trpSizeBytes] su
     * tamaño; la forma del resultado no cambia para la UI.
     */
    fun scan(vitaRoot: File, titleId: String): TrophyStoreScan {
        val ux0 = EmbeddedCoreLayout.resolveUx0Dir(vitaRoot)
        val dataDir = File(ux0, "user/00/trophy/data")
        if (!dataDir.isDirectory) {
            return TrophyStoreScan(
                trophyDirExists = false,
                trpPresent = false,
                trpSizeBytes = 0L,
                note = "El núcleo aún no creó la carpeta de trofeos (trophy/data/). " +
                    "Se crea al jugar. El progreso que se muestra es el local del launcher.",
            )
        }
        val npcommid = runCatching {
            val bundled = gameBundledTrp(File(ux0, "app/$titleId"))
                ?.takeIf { it.isFile && it.length() <= TrophyTrpReader.MAX_TRP_BYTES }
                ?: return@runCatching null
            TrophyTrpReader.extractNpcommid(bundled.readBytes())
        }.getOrNull()
        val candidates = dataDir.listFiles()?.filter { it.isDirectory }.orEmpty()
        val matching = if (npcommid != null) {
            candidates.filter { it.name.equals(npcommid, ignoreCase = true) }
        } else {
            candidates
        }
        val found = matching.firstNotNullOfOrNull { dir ->
            File(dir, "TROPCONF.SFM").takeIf { it.isFile }?.let { dir to it }
        }
        if (found == null) {
            return TrophyStoreScan(
                trophyDirExists = true,
                trpPresent = false,
                trpSizeBytes = 0L,
                note = if (npcommid != null) {
                    "La carpeta de trofeos existe pero aún no hay datos instalados para $npcommid. " +
                        "Se crean al jugar. El progreso mostrado es el local del launcher."
                } else {
                    "La carpeta de trofeos existe pero aún no hay datos instalados (TROPCONF.SFM). " +
                        "Se crean al jugar. El progreso mostrado es el local del launcher."
                },
            )
        }
        val (dir, conf) = found
        val usr = File(dir, "TROPUSR.DAT")
        return TrophyStoreScan(
            trophyDirExists = true,
            trpPresent = true,
            trpSizeBytes = conf.length(),
            note = "Datos de trofeos instalados en trophy/data/${dir.name}/ (TROPCONF.SFM, ${conf.length()} bytes" +
                (if (usr.isFile) "; TROPUSR.DAT, ${usr.length()} bytes" else "; sin TROPUSR.DAT todavía") +
                "). El progreso mostrado es el local del launcher.",
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
