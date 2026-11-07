package com.danielsnagustin.childoflight.launcher.model

/**
 * Title IDs oficiales de Child of Light en PS Vita.
 *
 * Verificado:
 * - PCSE00480 = versión US (NTSC). Aparece en listados NoNpDrm / Vitamin.
 * - PCSB00598 = versión EU (PAL), Complete Edition. Es el ID del cartucho
 *   PAL / AU (PCSB 00598) y de la base de datos de cheats de Vita.
 *
 * Cualquier otro Title ID se rechaza en la importación: este launcher es
 * de UN SOLO juego, no una biblioteca de Vita.
 */
enum class TitleId(val id: String, val region: String) {
    US("PCSE00480", "US"),
    EU("PCSB00598", "EU");

    companion object {
        val SUPPORTED: Set<String> = entries.map { it.id }.toSet()

        fun fromString(raw: String?): TitleId? {
            if (raw == null) return null
            val clean = raw.trim().uppercase()
            return entries.firstOrNull { it.id == clean }
        }

        fun isSupported(raw: String?): Boolean = fromString(raw) != null
    }
}

data class DlcInfo(
    val dlcId: String,
    val titleId: String,
    /** Tamaño total en bytes de ux0/addcont/<title>/<dlc>/, 0 si no se pudo medir. */
    val sizeBytes: Long = 0L,
)

data class SaveInfo(
    val name: String,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
)

/**
 * Estado real del juego en el almacenamiento privado del launcher.
 * Todo se lee del disco en cada escaneo; nada se inventa.
 */
data class GameStatus(
    val titleId: TitleId? = null,
    val gameInstalled: Boolean = false,
    val gameSizeBytes: Long = 0L,
    val dlcs: List<DlcInfo> = emptyList(),
    val updateInstalled: Boolean = false,
    val firmwareInstalled: Boolean = false,
    val firmwareName: String? = null,
    val saves: List<SaveInfo> = emptyList(),
) {
    val dlcCount: Int get() = dlcs.size
    val installedLabel: String
        get() = when {
            !gameInstalled -> "No instalado"
            titleId == null -> "Instalado"
            else -> "Instalado (${titleId.id} · ${titleId.region})"
        }
}

sealed class ImportResult {
    data class Success(val titleId: TitleId, val detail: String) : ImportResult()
    data class DlcSuccess(val titleId: TitleId, val dlcId: String) : ImportResult()
    data class UpdateSuccess(val titleId: TitleId) : ImportResult()
    data class FirmwareSuccess(val fileName: String) : ImportResult()
    /**
     * Un .pkg no se puede inspeccionar sin un parser de paquetes de Vita.
     * Se guarda tal cual y su instalación real la hace Vita3K (Etapa 2).
     * NUNCA se presenta como "juego instalado".
     */
    data class PkgStored(val titleId: TitleId, val storedPath: String) : ImportResult()
    data class Rejected(val reason: String) : ImportResult()
    data class Failed(val reason: String) : ImportResult()
}
