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
    /** true si el DLC es un .pkg guardado (ux0/pkg/dlc/), no una carpeta extraída. */
    val isPkg: Boolean = false,
    /**
     * Clave de fusión (P1-2, 2026-11-09): el nombre corto del contenido
     * ("CHILDOFLIGHTDLC1"). El núcleo nombra la carpeta en addcont/ con
     * ese nombre (pin interface.cpp: el Content ID menos sus 20 primeros
     * caracteres, o sea, lo que va tras "_00-"), y el .pkg guardado se
     * identifica por el mismo nombre, leído de SU header (no del nombre
     * del archivo, que puede ser un hash o estar truncado a 40
     * caracteres por sanitizeContentName). Por defecto es dlcId.
     * dlcId NO cambia: sigue siendo el nombre del archivo, que es lo
     * que usan installDlcPkg/deleteDlc para localizar el .pkg.
     */
    val contentKey: String = dlcId,
)

data class SaveInfo(
    val name: String,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
)

/**
 * Estado real del juego en el almacenamiento privado del launcher.
 * Todo se lee del disco en cada escaneo; nada se inventa.
 *
 * Dos formas de tener el juego:
 * - EXTRAÍDO: ux0/app/<TITLE>/ con eboot.bin (zip/.vpk o carpeta).
 *   Es lo único que el núcleo embebido (Etapa 2) puede arrancar y lo
 *   único de lo que el launcher gestiona saves/sesiones.
 * - .PKG: ux0/pkg/<TITLE>.pkg + <TITLE>.work.bin (licencia). En
 *   Etapa 1 solo Vita3K externo lo instala (el launcher lo entrega
 *   a Descargas). Con el núcleo embebido (Etapa 2), el propio núcleo
 *   lo instala (EmbeddedPkgInstaller). Por eso gameExtracted y
 *   pkgTitleId van por separado: muchas pantallas (saves, sesiones)
 *   solo tienen sentido con el juego extraído.
 */
data class GameStatus(
    /** Title ID efectivo: el extraído si lo hay, si no el del .pkg. */
    val titleId: TitleId? = null,
    /** Title ID del juego EXTRAÍDO (ux0/app/<TITLE>/eboot.bin). null si no hay. */
    val extractedTitleId: TitleId? = null,
    val gameInstalled: Boolean = false,
    /** true solo si hay juego extraído (eboot.bin en ux0/app/). */
    val gameExtracted: Boolean = false,
    val gameSizeBytes: Long = 0L,
    /** Title ID leído del header del .pkg guardado. null si no hay .pkg. */
    val pkgTitleId: TitleId? = null,
    /** Content ID del .pkg guardado, p. ej. "EP0001-PCSB00598_00-...". */
    val pkgContentId: String? = null,
    /** Tamaño del .pkg guardado en bytes. */
    val pkgSizeBytes: Long = 0L,
    /** true si el .pkg guardado tiene su work.bin (licencia) al lado. */
    val pkgLicensePresent: Boolean = false,
    /**
     * R5-A4-3: true si la licencia del juego EXTRAÍDO ya está
     * registrada en el núcleo (ux0/license/<TITLE>/<CONTENT_ID>.rif).
     * El reporte mentía "Licencia work.bin: false" cuando el juego
     * ya estaba instalado y el .pkg guardado se había borrado tras
     * JUGAR: sin .pkg no había work.bin que comprobar, pero la
     * licencia real ya estaba dentro del núcleo.
     */
    val licenseInstalledInCore: Boolean = false,
    val dlcs: List<DlcInfo> = emptyList(),
    val updateInstalled: Boolean = false,
    /**
     * R6-P1-05: true si hay un update .pkg GUARDADO (pkg/patch/<TITLE>.pkg)
     * sin instalar. Es distinto de updateInstalled (contenido real en
     * patch/): un update guardado se instala al pulsar JUGAR. La UI de
     * Importar lo muestra como "Guardado · se instala al pulsar JUGAR"
     * en vez de "Ausente".
     */
    val updateStored: Boolean = false,
    val firmwareInstalled: Boolean = false,
    val firmwareName: String? = null,
    val saves: List<SaveInfo> = emptyList(),
) {
    val dlcCount: Int get() = dlcs.size
    /** true si hay un .pkg de juego guardado (con o sin licencia). */
    val pkgInstalled: Boolean get() = pkgTitleId != null
    val installedLabel: String
        get() = when {
            !gameInstalled -> "No instalado"
            gameExtracted && titleId != null -> "Instalado (${titleId.id} · ${titleId.region})"
            pkgTitleId != null -> "Instalado como .pkg (${pkgTitleId.id} · ${pkgTitleId.region})"
            titleId == null -> "Instalado"
            else -> "Instalado (${titleId.id} · ${titleId.region})"
        }
}

/** Qué .pkg es: juego, DLC o update. Lo decide el botón que pulsó el usuario, no el nombre del archivo. */
enum class PkgKind(val label: String) {
    GAME("juego"),
    DLC("DLC"),
    UPDATE("update"),
}

sealed class ImportResult {
    data class Success(val titleId: TitleId, val detail: String) : ImportResult()
    data class DlcSuccess(val titleId: TitleId, val dlcId: String) : ImportResult()
    data class UpdateSuccess(val titleId: TitleId) : ImportResult()
    data class FirmwareSuccess(val fileName: String) : ImportResult()
    /**
     * Un .pkg guardado tras validar su header (Content ID → Title ID).
     * El nombre del archivo NO se miró: los .pkg reales se llaman por
     * hash. El juego NO está instalado como tal: al pulsar JUGAR, el
     * motor integrado instala el .pkg (UseEmbeddedPkg). Si
     * licenseFound es false, falta el work.bin y el resultado es
     * PkgNeedsLicense.
     */
    data class PkgStored(
        val titleId: TitleId,
        val storedPath: String,
        val contentId: String,
        val sizeBytes: Long,
        val licenseFound: Boolean,
        val kind: PkgKind,
    ) : ImportResult()
    /**
     * El .pkg se guardó, pero falta su licencia (work.bin de 512 B).
     * Sin ella Vita3K no puede instalarlo. El usuario debe elegir el
     * work.bin (el launcher lo busca también junto al .pkg).
     */
    data class PkgNeedsLicense(
        val titleId: TitleId,
        val storedPath: String,
        val contentId: String,
        val sizeBytes: Long,
        val kind: PkgKind,
    ) : ImportResult()
    data class Rejected(val reason: String) : ImportResult()
    data class Failed(val reason: String) : ImportResult()
}
