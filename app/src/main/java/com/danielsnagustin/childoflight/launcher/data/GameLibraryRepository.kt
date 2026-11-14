package com.danielsnagustin.childoflight.launcher.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.danielsnagustin.childoflight.launcher.core.EmbeddedCoreLayout
import com.danielsnagustin.childoflight.launcher.model.DlcInfo
import com.danielsnagustin.childoflight.launcher.model.GameStatus
import com.danielsnagustin.childoflight.launcher.model.ImportResult
import com.danielsnagustin.childoflight.launcher.model.SaveInfo
import com.danielsnagustin.childoflight.launcher.model.TitleId
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Buffer de copia para archivos grandes (auditoría RAM ≤ 4 GB, 2026-11-07).
 * InputStream.copyTo() sin tamaño usa 8 KiB por defecto: para un .pkg de
 * 1,14 GB son ~140.000 lecturas/escrituras. Con 256 KiB son ~4.500 y la
 * RAM extra es solo ese buffer transitorio (0,25 MB, se libera al cerrar
 * el stream). La copia sigue siendo por streaming: NUNCA se carga el
 * archivo entero en RAM (no hay readBytes/readText sobre archivos grandes
 * en este repositorio; el único readBytes es param.sfo, con tope de 256 KB).
 */
internal const val LARGE_FILE_COPY_BUFFER_BYTES = 256 * 1024

/**
 * Guía pura (sin Android) de qué archivo elegir al importar.
 * Sin Context ni I/O: testeable en ImportClassifierTest.
 *
 * El usuario veía "eboot.bin" en los mensajes y creía que el launcher
 * le pedía un .bin: en realidad el .bin va DENTRO del .zip/.vpk o de
 * la carpeta del juego. Un eboot.bin suelto no basta para instalar nada.
 */
object ImportFileGuide {
    fun extensionOf(fileName: String): String =
        fileName.substringAfterLast('.', "").lowercase()

    fun isBareBin(fileName: String): Boolean = extensionOf(fileName) == "bin"

    fun isPackage(fileName: String): Boolean =
        extensionOf(fileName) in setOf("zip", "vpk", "pkg")

    /**
     * Magia ZIP ("PK" + 0x03 0x04, o zip vacío 0x05 0x06): un .vpk ES
     * un zip, así que ambos comparten la misma magia. Sirve para
     * reconocer el paquete aunque el selector devuelva el archivo sin
     * extensión en el nombre (algunos gestores devuelven un ID de
     * documento en vez del nombre real).
     */
    fun looksLikeZipMagic(firstBytes: ByteArray): Boolean {
        if (firstBytes.size < 4) return false
        return firstBytes[0] == 0x50.toByte() && firstBytes[1] == 0x4B.toByte() &&
            ((firstBytes[2] == 0x03.toByte() && firstBytes[3] == 0x04.toByte()) ||
                (firstBytes[2] == 0x05.toByte() && firstBytes[3] == 0x06.toByte()))
    }

    /** Magia de un PUP de firmware de Vita ("SCEUF" al inicio). */
    fun looksLikePupMagic(firstBytes: ByteArray): Boolean {
        val magic = byteArrayOf(0x53, 0x43, 0x45, 0x55, 0x46) // "SCEUF"
        if (firstBytes.size < magic.size) return false
        return magic.indices.all { firstBytes[it] == magic[it] }
    }

    /**
     * Magia de un .pkg de Vita ("\x7FPKG" al inicio). Sirve para
     * reconocer el paquete aunque el archivo se llame por hash y no
     * traiga extensión en el nombre (algunos gestores devuelven un ID
     * de documento en vez del nombre real). El nombre nunca decide
     * nada: solo la magia y, después, el Content ID del header.
     */
    fun looksLikePkgMagic(firstBytes: ByteArray): Boolean {
        if (firstBytes.size < 4) return false
        return firstBytes[0] == 0x7F.toByte() && firstBytes[1] == 0x50.toByte() &&
            firstBytes[2] == 0x4B.toByte() && firstBytes[3] == 0x47.toByte()
    }

    /** Mensaje claro en español cuando el usuario elige un .bin suelto. */
    fun bareBinHelp(what: String): String = when (what) {
        "DLC" -> "Elegiste un archivo .bin suelto. Ese archivo solo no instala el DLC: " +
            "elige el .zip del DLC (con la ruta addcont/ dentro), o usa " +
            "\"Importar carpeta de DLC\" y elige la carpeta PCSE00480 (US) o PCSB00598 (EU) " +
            "que contiene addcont/ dentro."
        "update" -> "Elegiste un archivo .bin suelto. Ese archivo solo no instala la actualización: " +
            "elige el .zip o .vpk de la actualización, o usa \"Importar carpeta del juego\" " +
            "y elige la carpeta PCSE00480 (US) o PCSB00598 (EU) que contiene eboot.bin dentro."
        else -> "Elegiste un archivo .bin suelto (eboot.bin). Ese archivo solo no basta para instalar el juego: " +
            "el juego necesita su carpeta completa. Elige el .zip o .vpk COMPLETO del juego " +
            "(dentro lleva eboot.bin), o usa \"Importar carpeta del juego\" y elige la carpeta " +
            "PCSE00480 (US) o PCSB00598 (EU) que contiene eboot.bin dentro."
    }
}

/** Una copia del .pkg o su licencia que quedó en Descargas/ChildOfLight/. */
data class DownloadsCopy(val name: String, val sizeBytes: Long)

/**
 * Lógica pura de la limpieza de Descargas/ChildOfLight/ (sin Android):
 * tamaños y texto del diálogo de confirmación. Testeable en JVM
 * (DownloadsAndLayoutTest).
 */
object DownloadsCleanup {
    fun totalBytes(copies: List<DownloadsCopy>): Long = copies.sumOf { it.sizeBytes }

    /** Mismo formato que el resto del launcher, con locale fijo (sin comas decimales). */
    fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> java.lang.String.format(Locale.US, "%.2f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000L -> java.lang.String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000L -> "${bytes / 1_000} KB"
        else -> "$bytes bytes"
    }

    /** Texto del diálogo de confirmación: qué se borraría y cuánto ocupa. */
    fun summarize(copies: List<DownloadsCopy>): String {
        val lines = copies.joinToString("\n") { "• ${it.name} (${formatBytes(it.sizeBytes)})" }
        return "$lines\n\nTotal: ${formatBytes(totalBytes(copies))}"
    }
}

/**
 * Biblioteca del juego: importación y estructura de archivos.
 *
 * Estructura Vita3K real (rutas estándar de Vita, las mismas que usa
 * Vita3K en PC/Android):
 * - Juego:   ux0/app/<TITLE_ID>/            (la carpeta DEBE llamarse como el Title ID)
 * - DLC:     ux0/addcont/<TITLE_ID>/<DLC_ID>/
 * - Update:  ux0/patch/<TITLE_ID>/
 * - Saves:   ux0/user/00/savedata/<TITLE_ID>/
 * - Trofeos: ux0/user/00/trophy/data/<NPCOMMID>/ (TROPCONF.SFM,
 *   TROPUSR.DAT, TROPxxx.PNG; ver TrophyDeletePaths y
 *   TrophyStoreReader). La ruta vieja trophy/<TITLE_ID>/ nunca la
 *   usa el núcleo: solo queda como limpieza extra en el borrado.
 * - Firmware: <vita_fs>/firmware/PSVUPDAT.PUP (+ marcador installed.json)
 * - Config:  <vitaRoot>/config.yml (config_path = storage_path; pref-path apunta a <vitaRoot>/vita)
 *
 * Todo vive en el almacenamiento PRIVADO de la app (<filesDir>/vita3k),
 * así que no necesita permisos de almacenamiento.
 *
 * Validación real de .zip/.vpk (un .vpk ES un zip):
 * - Dentro del archivo debe haber una carpeta PCSE00480 o PCSB00598
 *   (puede ir anidada: "Child of Light/PCSB00598/...", habitual en
 *   paquetes EU) y esa carpeta debe contener eboot.bin (sin distinguir
 *   mayúsculas: EBOOT.BIN vale). La extensión se resuelve por nombre,
 *   tipo MIME o magia del archivo, no solo por el nombre.
 * - Si no, se rechaza con el motivo exacto. No se instala nada a medias:
 *   la extracción va a una carpeta temporal y solo se mueve al destino
 *   si la validación pasa.
 *
 * .pkg: se valida por CONTENIDO, nunca por el nombre del archivo.
 * El parser (PkgHeaderParser) lee el header: magic "\x7FPKG" y el
 * Content ID en 0x30, de donde sale el Title ID (PCSE00480/PCSB00598)
 * y la región. Los .pkg reales se llaman por hash y no traen el Title
 * ID en el nombre: exigirlo era el error que rechazaba la copia del
 * usuario. El .pkg se guarda en ux0/pkg/ con su work.bin (licencia
 * zRIF de 512 B, buscada junto al .pkg o pedida al usuario). El
 * launcher NO puede desencriptar ni extraer un .pkg por sí mismo: lo
 * instala el motor integrado al pulsar JUGAR (UseEmbeddedPkg). La
 * entrega a la app externa ("Copiar a la app externa", en la
 * pantalla Importar) sigue existiendo como alternativa: pide
 * confirmación y solo entonces copia ambos a Descargas/ChildOfLight/
 * y abre la app externa con la guía. El .pkg y su licencia viven
 * siempre en el almacenamiento interno del launcher; la copia a
 * Descargas es solo la entrega explícita al externo y se puede borrar
 * desde Ajustes → Almacenamiento.
 *
 * .zip/.vpk: dentro debe haber una carpeta PCSE00480 o PCSB00598
 * (puede ir anidada hasta 3 niveles) con eboot.bin, O el juego plano
 * (eboot.bin en la raíz con sce_sys/param.sfo que diga el Title ID).
 * La extensión se resuelve por nombre, tipo MIME o magia del archivo.
 */
/**
 * Rutas de borrado de los datos de trofeos (ronda 0.2.57, trabajador E).
 * Lógica pura (solo java.io.File): testeable en JVM sin Android
 * (TrophyDeletePathsTest).
 *
 * Ruta REAL (R6-P1-18): el núcleo instala los trofeos en
 * ux0/user/00/trophy/data/<NPCOMMID>/, con TROPCONF.SFM, TROPUSR.DAT
 * y TROPxxx.PNG. La misma disposición la documenta psdevwiki
 * ("Trophy": home/userid/trophy/NPCOMMID/ con TROPCONF.SFM y
 * TROPUSR.DAT; el NPCOMMID va en formato NPWRxxyyy_zz) y la confirma
 * el log real de Vita3K del issue Vita3K-Android #649
 * (trophy/data/NPWR12049_00/TROPUSR.DAT), citados en
 * TrophyStoreReader. El pin local NO trae el módulo np/trophy/ del
 * núcleo, así que la ruta no se puede verificar ahí; queda pendiente
 * confirmarla en el teléfono (igual que en R6-P1-18).
 *
 * El NPCOMMID se resuelve del <npcommid> del TROPHY.TRP empaquetado
 * del juego (igual que TrophyStoreReader.scan): no se inventa ni se
 * fija ningún NPCOMMID en el código, porque depende de la copia del
 * juego instalada. Si el TRP no se puede leer (contenedor cifrado o
 * comprimido, o juego sin TRP visible), el borrado no sabe cuál es
 * la carpeta de este juego: en ese caso valen TODAS las subcarpetas
 * directas de trophy/data/. Es seguro porque el launcher es de un
 * solo juego (trophy/data/ solo guarda sets de este juego) y porque
 * deleteGameData borra las dos regiones juntas (P2-C9). Nunca se
 * sube de trophy/data/: solo se listan sus hijas directas, y nunca
 * se toca ux0 entero ni otros títulos.
 *
 * Ruta VIEJA (trophy/<TITLE_ID>/): el núcleo nunca la usa; se
 * mantiene como limpieza extra por si quedó algo de versiones viejas.
 */
object TrophyDeletePaths {

    /** Ruta vieja de trofeos (limpieza extra, nunca la usa el núcleo). */
    fun legacyTrophyDir(ux0: File, titleId: String): File =
        File(ux0, "user/00/trophy/$titleId")

    /** Carpeta real de datos de trofeos del núcleo. */
    fun trophyDataDir(ux0: File): File = File(ux0, "user/00/trophy/data")

    /** Carpeta real de un set de trofeos: trophy/data/<NPCOMMID>/. */
    fun trophyDataDirFor(ux0: File, npcommid: String): File =
        File(trophyDataDir(ux0), npcommid)

    private val NPCOMMID_REGEX = Regex("^[A-Za-z0-9_]{1,32}$")

    /**
     * ¿Es un NPCOMMID usable como nombre de carpeta? Solo letras,
     * dígitos y guion bajo (el formato real es NPWRxxyyy_zz, p. ej.
     * NPWR12049_00). Un valor con separadores o puntos (p. ej. un
     * <npcommid> manipulado con "../") NO vale: construir una ruta
     * con él escaparía de trophy/data/.
     */
    fun isValidNpcommid(value: String?): Boolean =
        value != null && NPCOMMID_REGEX.matches(value)

    /**
     * Resuelve el NPCOMMID del TROPHY.TRP empaquetado en [appDir]
     * (ux0/app/<TITLE>/sce_sys/trophy/TROPHY.TRP). Null si no hay TRP,
     * si es demasiado grande, si no se puede leer o si su
     * <npcommid> no es un nombre de carpeta válido. Nunca lanza.
     */
    fun resolveNpcommid(appDir: File): String? {
        val bytes = runCatching {
            TrophyStoreReader.gameBundledTrp(appDir)
                ?.takeIf { it.isFile && it.length() <= TrophyTrpReader.MAX_TRP_BYTES }
                ?.readBytes()
        }.getOrNull() ?: return null
        return TrophyTrpReader.extractNpcommid(bytes)?.takeIf { isValidNpcommid(it) }
    }

    /**
     * Carpetas de trofeos a borrar en [ux0] para este juego. Solo
     * devuelve carpetas que EXISTEN (el llamador las borra y etiqueta
     * "trofeos" si borró al menos una):
     * - La real: trophy/data/<NPCOMMID>/ (más su respaldo _BU_ si
     *   existe: psdevwiki documenta que al reinstalar un set, el
     *   anterior se renombra a _BU_<NPCOMMID>).
     * - La vieja: trophy/<TITLE_ID>/ (limpieza extra).
     * Si el NPCOMMID no se pudo resolver, todas las subcarpetas
     * directas de trophy/data/ (ver el comentario del objeto). Los
     * archivos sueltos directamente bajo trophy/data/ no son datos
     * de un set y no se listan.
     */
    fun trophyDirsToDelete(ux0: File, appDir: File, titleId: String): List<File> {
        val found = mutableListOf<File>()
        val legacy = legacyTrophyDir(ux0, titleId)
        if (legacy.isDirectory) found.add(legacy)
        val dataDir = trophyDataDir(ux0)
        val npcommid = resolveNpcommid(appDir)
        if (npcommid != null) {
            val real = trophyDataDirFor(ux0, npcommid)
            if (real.isDirectory) found.add(real)
            val backup = trophyDataDirFor(ux0, "_BU_$npcommid")
            if (backup.isDirectory) found.add(backup)
        } else {
            dataDir.listFiles()?.filter { it.isDirectory }?.let { found.addAll(it) }
        }
        return found.distinct()
    }
}

class GameLibraryRepository(private val context: Context) {

    /** Context de la app, para el log de diagnóstico desde el instalador (EmbeddedPkgInstaller). */
    val appContext: Context get() = context

    // Caché de tamaños de directorio: scanStatus() se llama en init, en
    // cada onResume y al volver del juego, y antes recorría TODO el juego
    // (~1,14 GB) y cada DLC en cada llamada. La caché solo guarda el
    // tamaño; el contenido (qué hay instalado) se sigue leyendo siempre.
    private val dirSizeCache = DirSizeCache()

    // Ronda 0.2.55 P1-5: caché de listSaves() (misma idea que
    // DirSizeCache): clave título, marca mtime del directorio de saves.
    private val savesCache = mutableMapOf<String, Pair<Pair<Long, Long>, List<SaveInfo>>>()

    /**
     * R4-P2-5: último resumen de scanStatus registrado en el log.
     * El compare-and-set de abajo va bajo un synchronized pequeño
     * (R5-A1-12): scanStatus() NO corre siempre bajo el mutex de
     * refreshStatus() del ViewModel, como afirmaba el comentario
     * anterior. Hay llamadores fuera de ese mutex (checkPrerequisites
     * en Vita3KLauncher y deleteInstalledGamePkgs en el ViewModel),
     * así que dos escaneos concurrentes podían ver el mismo cambio y
     * registrar la línea duplicada. Benigno (una línea duplicada en
     * el log, nunca un dato incorrecto: los append son
     * sincronizados), pero el comentario mentía y el duplicado era
     * real. El @Volatile se conserva: la lectura fuera del
     * synchronized sigue viendo el último valor publicado.
     */
    @Volatile
    private var lastScanStatusSummary: String? = null

    /** Candado del compare-and-set del resumen de scanStatus (R5-A1-12). */
    private val scanStatusLogLock = Any()

    companion object {
        /**
         * Prefijo de ImportResult.Rejected cuando el juego/DLC/update ya
         * está subido: el ViewModel lo convierte en un diálogo
         * "Reemplazar / Cancelar" en vez de un error. El texto tras el
         * prefijo es el mensaje para el usuario (sin el prefijo).
         */
        const val DUPLICATE_MARKER = "DUPLICATE::"

        /**
         * Nombre de contenido del .pkg DEL JUEGO (tras "_00-" del
         * Content ID, p. ej. "EP0001-PCSB00598_00-CHILDOFLIGHT0000").
         * Los .pkg de DLC/update traen otros nombres: es lo que
         * distingue el .pkg del juego sin fiarse del botón (P1-9).
         */
        const val GAME_CONTENT_NAME = "CHILDOFLIGHT0000"

        /**
         * ¿El .pkg cuadra con el botón pulsado? Pura (testeable sin
         * Context). El tipo del .pkg se lee de su propia tabla INFO
         * (pin packages/src/pkg.cpp: content_type 0x15 = juego,
         * 0x16 = DLC, 0x1F = tema; el núcleo rechaza cualquier otro).
         * El DLC ya se distingue con certeza. Límite conocido: juego y
         * update comparten content_type 0x15 y el update comparte el
         * Content ID del juego (por eso su licencia es la del juego):
         * el núcleo los separa por la CATEGORY del param.sfo, cifrada
         * dentro del .pkg, así que entre esos dos botones no se
         * bloquea por tipo: GAME se acepta solo con el nombre del
         * juego y UPDATE se acepta siempre (el núcleo decide por el
         * SFO al instalar). Si la tabla INFO no se puede leer
         * (pkgContentType null), se usa la regla vieja (solo el
         * nombre) para no romper importaciones que funcionaban.
         * Null si cuadra; si no, el mensaje de rechazo.
         */
        fun pkgKindMismatch(
            header: PkgHeader,
            requested: com.danielsnagustin.childoflight.launcher.model.PkgKind,
        ): String? {
            val contentType = header.pkgContentType
            val isGameContent = header.contentName == GAME_CONTENT_NAME
            if (contentType == PkgHeaderParser.CONTENT_TYPE_THEME) {
                return "Este .pkg es un tema de PS Vita, no contenido de Child of Light. " +
                    "No se puede importar como ${requested.label}."
            }
            if (contentType != null &&
                contentType != PkgHeaderParser.CONTENT_TYPE_APP &&
                contentType != PkgHeaderParser.CONTENT_TYPE_DLC
            ) {
                return "Este .pkg trae un tipo de contenido no compatible " +
                    "(0x${contentType.toString(16)}): el núcleo solo instala juego y DLC. " +
                    "No se puede importar como ${requested.label}."
            }
            if (contentType == PkgHeaderParser.CONTENT_TYPE_DLC) {
                return when (requested) {
                    com.danielsnagustin.childoflight.launcher.model.PkgKind.DLC -> null
                    com.danielsnagustin.childoflight.launcher.model.PkgKind.GAME ->
                        "Este .pkg es un DLC, no el juego: usa Importar DLC."
                    com.danielsnagustin.childoflight.launcher.model.PkgKind.UPDATE ->
                        "Este .pkg es un DLC, no una actualización: usa Importar DLC."
                }
            }
            if (contentType == PkgHeaderParser.CONTENT_TYPE_APP) {
                if (requested == com.danielsnagustin.childoflight.launcher.model.PkgKind.DLC) {
                    return "Este .pkg no es un DLC (es el juego o una actualización): " +
                        "usa Importar juego o Importar actualización."
                }
                if (requested == com.danielsnagustin.childoflight.launcher.model.PkgKind.GAME && !isGameContent) {
                    return "Este .pkg no es el juego (trae ${header.contentName}): " +
                        "no se puede importar como juego. Usa Importar DLC o Importar actualización según lo que sea."
                }
                return null
            }
            // Sin tabla INFO legible: regla vieja (solo el nombre).
            return when {
                requested == com.danielsnagustin.childoflight.launcher.model.PkgKind.GAME && !isGameContent ->
                    "Este .pkg no es el juego (trae ${header.contentName}): " +
                        "no se puede importar como juego. Usa Importar DLC o Importar actualización según lo que sea."
                requested != com.danielsnagustin.childoflight.launcher.model.PkgKind.GAME && isGameContent ->
                    "Este .pkg es del juego, no un ${requested.label}: usa Importar juego. " +
                        "Si se guardara como ${requested.label}, sobrescribiría el juego."
                else -> null
            }
        }

        /**
         * Tamaño mínimo de un PSVUPDAT.PUP real: el PUP de Vita 3.74
         * ronda los 134 MB (ver FirmwareResolver.APPROX_PUP_SIZE_TEXT).
         * Por debajo de 100 MB no es un PUP completo: se rechaza en
         * vez de guardarlo y reintentar instalarlo en cada JUGAR (P1-13).
         */
        const val MIN_PUP_SIZE_BYTES = 100_000_000L

        /**
         * R5-A4-1: margen de extracción al importar un .pkg: el núcleo
         * extrae ~1,1 GB en ux0/app/ además del .pkg guardado, así
         * que el pico es el tamaño declarado del .pkg más este margen
         * (el .pkg del juego pesa 1.141.710.576 B; la extracción ronda
         * lo mismo). Solo se usa para el chequeo previo de espacio,
         * nunca como tamaño real.
         */
        const val PKG_EXTRACTION_MARGIN_BYTES = 1_141_710_576L

        /**
         * R5-A4-1: cuánto espacio libre hace falta para importar un
         * .pkg: su tamaño declarado en el header más el margen de
         * extracción del núcleo. Si el header no declara tamaño
         * (0 o negativo), solo el margen: algo de protección es mejor
         * que ninguna. Puro (testeable en JVM).
         */
        fun pkgImportNeededBytes(declaredTotalSizeBytes: Long): Long {
            return if (declaredTotalSizeBytes > 0) {
                declaredTotalSizeBytes + PKG_EXTRACTION_MARGIN_BYTES
            } else {
                PKG_EXTRACTION_MARGIN_BYTES
            }
        }

        /**
         * R5-A4-1: cuánto espacio libre hace falta para importar un
         * .zip/.vpk: el doble de su tamaño (la extracción a un
         * temporal más la copia a ux0/app/ conviven en la misma
         * partición). Puro (testeable en JVM).
         */
        fun zipImportNeededBytes(zipSizeBytes: Long): Long {
            return zipSizeBytes * 2
        }

        /**
         * R6-P2-01: bytes libres que hacen falta para INSTALAR un .pkg
         * ya guardado: el .pkg ya existe en disco y su extracción
         * convive con él en la misma partición, así que el pico es el
         * doble del .pkg. Para el juego equivale a longitud +
         * PKG_EXTRACTION_MARGIN_BYTES (el margen es el tamaño del
         * propio juego). Puro (testeable en JVM).
         */
        fun pkgInstallNeededBytes(pkgLengthBytes: Long): Long {
            return pkgLengthBytes * 2
        }

        /**
         * R6-P2-08(c): ¿la copia de un .pkg quedó incompleta? Si el
         * header declara un tamaño total, manda él; si declara 0
         * (header sin ese campo), manda el tamaño del archivo de
         * origen si se conoce. Sin tamaño de origen conocido no se
         * puede saber: se devuelve false y el fallo aparecerá al
         * instalar (límite documentado en importArchive). Puro.
         */
        fun pkgCopyIncomplete(destLengthBytes: Long, declaredTotalSizeBytes: Long, sourceSizeBytes: Long): Boolean {
            if (declaredTotalSizeBytes > 0) return destLengthBytes < declaredTotalSizeBytes
            if (sourceSizeBytes > 0) return destLengthBytes < sourceSizeBytes
            return false
        }

        /** R6-P1-02(a): un .pkg de juego por debajo de 200 MB es sospechoso (el juego real es 1,14 GB). */
        const val SUSPICIOUS_SMALL_GAME_PKG_BYTES = 200_000_000L

        /**
         * R6-P2-02: Title IDs de las carpetas de juego que trae un zip,
         * leídos SOLO de los nombres de sus entradas (sin extraer). Se
         * miran los componentes de ruta hasta el nivel 3, igual que
         * ImportTreeSearch.findTitleDirs sobre el árbol extraído. Un
         * juego PLANO (eboot.bin en la raíz, Title ID en el SFO) no se
         * puede determinar por nombres: devuelve vacío y el llamador
         * deja el comportamiento actual. Puro (testeable en JVM).
         */
        fun zipTitleDirNames(entryNames: List<String>): List<TitleId> {
            val found = mutableListOf<TitleId>()
            for (name in entryNames) {
                val parts = name.split('/').take(4)
                for (part in parts) {
                    TitleId.fromString(part)?.let { if (it !in found) found.add(it) }
                }
            }
            return found
        }

        /**
         * R6-P2-02: DLCs que trae un zip, leídos de los nombres de sus
         * entradas: cualquier ruta con un componente "addcont" (sin
         * distinguir mayúsculas) seguido de una carpeta es un DLC con
         * ese nombre. Puro (testeable en JVM).
         */
        fun zipAddcontDlcIds(entryNames: List<String>): List<String> {
            val found = mutableListOf<String>()
            for (name in entryNames) {
                val parts = name.split('/')
                for (i in parts.indices) {
                    if (parts[i].equals("addcont", ignoreCase = true) && i + 1 < parts.size) {
                        val dlcId = parts[i + 1]
                        if (dlcId.isNotBlank() && dlcId !in found) found.add(dlcId)
                    }
                }
            }
            return found
        }

        /**
         * R6-P2-02: ¿el zip trae un eboot.bin? (por nombre, sin
         * extraer). Solo sirve como pista: el título de un juego
         * plano vive en el SFO, no en los nombres. Puro (testeable
         * en JVM).
         */
        fun zipHasEboot(entryNames: List<String>): Boolean {
            return entryNames.any { it.substringAfterLast('/').equals("eboot.bin", ignoreCase = true) }
        }

        /**
         * R6-P2-02: busca el End Of Central Directory (EOCD) en la cola
         * del archivo (los últimos bytes leídos). Devuelve (nº de
         * entradas, tamaño del directorio central, offset del
         * directorio central) o null si no hay EOCD (no es un zip,
         * está truncado) o es ZIP64 (contadores a 0xFFFF/0xFFFFFFFF:
         * el índice no cabe en el EOCD y la pasada barata no se puede
         * hacer). Solo se acepta un EOCD cuyo final coincida con el
         * final del archivo (el comentario del zip va tras él): así no
         * vale una firma que aparezca dentro del comentario. Puro.
         */
        fun findZipEocd(tail: ByteArray): Triple<Int, Long, Long>? {
            val sig = byteArrayOf(0x50, 0x4b, 0x05, 0x06)
            var pos = tail.size - 22
            while (pos >= 0) {
                if (tail[pos] == sig[0] && tail[pos + 1] == sig[1] && tail[pos + 2] == sig[2] && tail[pos + 3] == sig[3]) {
                    val commentLen = u16Le(tail, pos + 20)
                    if (pos + 22 + commentLen == tail.size) {
                        val totalEntries = u16Le(tail, pos + 10)
                        val cdSize = u32Le(tail, pos + 12).toLong() and 0xFFFFFFFFL
                        val cdOffset = u32Le(tail, pos + 16).toLong() and 0xFFFFFFFFL
                        if (totalEntries == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) return null
                        return Triple(totalEntries, cdSize, cdOffset)
                    }
                }
                pos--
            }
            return null
        }

        /**
         * R6-P2-02: nombres de las entradas del directorio central de
         * un zip. [bytes] es el bloque del directorio central leído de
         * disco; [expectedCount] es el nº de entradas que dice el
         * EOCD. null si el bloque no cuadra (firma, truncado, nº
         * distinto): el llamador usa el comportamiento actual. Puro.
         */
        fun parseZipCentralDirectoryNames(bytes: ByteArray, expectedCount: Int): List<String>? {
            val names = mutableListOf<String>()
            var pos = 0
            while (names.size < expectedCount) {
                if (pos + 46 > bytes.size) return null
                if (bytes[pos] != 0x50.toByte() || bytes[pos + 1] != 0x4b.toByte() || bytes[pos + 2] != 0x01.toByte() || bytes[pos + 3] != 0x02.toByte()) return null
                val nameLen = u16Le(bytes, pos + 28)
                val extraLen = u16Le(bytes, pos + 30)
                val commentLen = u16Le(bytes, pos + 32)
                val entryEnd = pos + 46 + nameLen + extraLen + commentLen
                if (entryEnd > bytes.size) return null
                val name = runCatching { String(bytes, pos + 46, nameLen, Charsets.UTF_8) }.getOrNull() ?: return null
                names.add(name)
                pos = entryEnd
            }
            return names
        }

        /**
         * R5-A4-1: mensaje cuando no cabe una importación o una
         * instalación: cuánto hace falta y cuánto hay libre, en
         * formato legible. Puro (testeable en JVM).
         *
         * P0 espacio libre (0.2.55): dos casos que el texto viejo
         * confundía. Si [freeBytes] es negativo, el espacio NO se
         * pudo medir (StatFs falló incluso sobre un directorio que
         * existe): se dice así, nunca "hay 0 bytes libres", porque
         * 0 es una medida real de disco lleno y un negativo es
         * "no lo sé". Si se conoce el tamaño declarado del .pkg
         * ([declaredPkgBytes] mayor que 0), se explica el pico en
         * una línea con cifras reales: el .pkg guardado más su
         * extracción, y que tras instalarlo el .pkg se borra solo.
         */
        fun freeSpaceMessage(
            neededBytes: Long,
            freeBytes: Long,
            declaredPkgBytes: Long? = null,
        ): String {
            val peak = if (declaredPkgBytes != null && declaredPkgBytes > 0 && declaredPkgBytes < neededBytes) {
                ". El pico incluye el .pkg guardado (${DownloadsCleanup.formatBytes(declaredPkgBytes)}) " +
                    "y su extracción (${DownloadsCleanup.formatBytes(neededBytes - declaredPkgBytes)}); " +
                    "tras instalarlo, el .pkg se borra solo"
            } else {
                ""
            }
            return if (freeBytes < 0) {
                "No se pudo medir el espacio libre: hacen falta ${DownloadsCleanup.formatBytes(neededBytes)}$peak"
            } else {
                "Falta espacio: hacen falta ${DownloadsCleanup.formatBytes(neededBytes)}, " +
                    "hay ${DownloadsCleanup.formatBytes(freeBytes)} libres$peak"
            }
        }

        /**
         * P0 espacio libre (0.2.55): sonda para StatFs. StatFs lanza
         * IllegalArgumentException si la ruta NO existe, y en una
         * instalación limpia ux0/ y ux0/pkg/ aún no existen (se crean
         * al copiar), así que medir sobre la ruta destino devolvía 0
         * y TODA importación se rechazaba. La medición es por
         * partición, así que el ancestro existente más cercano da el
         * mismo valor: se sube con parentFile hasta encontrarlo. Si
         * no existe ni la raíz, se usa [fallbackDir] (filesDir, que
         * siempre existe). Puro (testeable en JVM).
         */
        fun statFsProbeDir(dir: File, fallbackDir: File): File {
            var probe: File? = dir
            while (probe != null && !probe.exists()) {
                probe = probe.parentFile
            }
            return probe ?: fallbackDir
        }

        /**
         * Tamaño de la cabecera SCE de un SELF (R4-P0-5/R4-P0-6,
         * 2026-11-09): 128 bytes. La cabecera trae magic u32 @0,
         * version u32 @4, sdk_type u16 @8, header_type u16 @10,
         * metadata_offset u32 @12, header_len u64 @16, elf_filesize
         * u64 @24, self_filesize u64 @32, elf_offset u64 @64 y
         * phdr_offset u64 @72 (struct SCE_header, vita-toolchain
         * src/self.h; tabla SELF de psdevwiki). Hay que leer 128
         * bytes para validar un SELF: con 64 no se ven elf_offset
         * ni phdr_offset ni self_filesize (R4-P1-6).
         *
         * No hay piso de tamaño para el eboot: el mínimo de 1 MB
         * anterior era inventado y rechazaba eboots válidos pequeños,
         * y no protegía contra truncados de más de 1 MB porque
         * self_filesize nunca se comprobaba. La protección real es
         * estructural (ver isSelfFile): self_filesize debe ser el
         * tamaño del archivo y los offsets deben caer dentro de él.
         */
        private const val SELF_HEADER_SIZE_BYTES = 128

        /**
         * Lee los primeros [size] bytes de un archivo (bucle: un solo
         * read puede devolver menos). null si el archivo es más corto
         * o no se puede leer. Puro (solo File).
         */
        private fun readHeaderBytes(file: File, size: Int): ByteArray? {
            if (!file.isFile) return null
            return try {
                file.inputStream().use { input ->
                    val buf = ByteArray(size)
                    var read = 0
                    while (read < size) {
                        val n = input.read(buf, read, size - read)
                        if (n <= 0) return null
                        read += n
                    }
                    buf
                }
            } catch (_: Exception) {
                null
            }
        }

        /** Entero de 32 bits little-endian en [bytes] desde [offset]. */
        private fun u32Le(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)

        /** Entero de 16 bits little-endian en [bytes] desde [offset]. */
        private fun u16Le(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8)

        /** Entero de 64 bits little-endian en [bytes] desde [offset]. */
        private fun u64Le(bytes: ByteArray, offset: Int): Long {
            var value = 0L
            for (i in 0 until 8) {
                value = value or ((bytes[offset + i].toLong() and 0xFFL) shl (i * 8))
            }
            return value
        }

        /**
         * Lee [size] bytes de [file] desde [offset] (bucle: un solo
         * read puede devolver menos). null si no se puede leer el
         * rango completo. Puro (solo File).
         */
        private fun readBytesAt(file: File, offset: Long, size: Int): ByteArray? {
            if (!file.isFile || offset < 0) return null
            return try {
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(offset)
                    val buf = ByteArray(size)
                    var read = 0
                    while (read < size) {
                        val n = raf.read(buf, read, size - read)
                        if (n <= 0) return null
                        read += n
                    }
                    buf
                }
            } catch (_: Exception) {
                null
            }
        }

        /** Magia SELF: 'S' 'E' 'C' 0x00. [header] debe tener al menos 4 bytes. */
        private fun isSecMagic(header: ByteArray): Boolean =
            header[0] == 'S'.code.toByte() && header[1] == 'E'.code.toByte() &&
                header[2] == 'C'.code.toByte() && header[3] == 0.toByte()

        /** Magia ELF: 0x7F 'E' 'L' 'F'. [header] debe tener al menos 4 bytes. */
        private fun isElfMagic(header: ByteArray): Boolean =
            header[0] == 0x7F.toByte() && header[1] == 'E'.code.toByte() &&
                header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte()

        /**
         * Motivo por el que un ELF no es válido (o OK). Un eboot.bin
         * de Vita descifrado empieza por 0x7F 'E' 'L' 'F'. Se exige
         * la magia, EI_CLASS 1 (ELF32: la Vita es ARM de 32 bits y
         * el núcleo usa Elf32_Ehdr) y la tabla de segmentos dentro
         * del archivo (e_phoff + e_phnum * e_phentsize). Sin piso
         * de tamaño: un archivo más corto que la cabecera es
         * inválido por estructura (readHeaderBytes devuelve null).
         * Puro (solo File) para poder probarlo en la JVM sin Context.
         */
        fun elfValidationReason(file: File): EbootInvalidReason {
            if (!file.isFile) return EbootInvalidReason.FALTA
            val header = readHeaderBytes(file, 64) ?: return EbootInvalidReason.TAMANO
            if (!isElfMagic(header)) return EbootInvalidReason.MAGIA
            if (header[4].toInt() != 1) return EbootInvalidReason.MAGIA
            val phoff = u32Le(header, 28).toLong() and 0xFFFFFFFFL
            val phentsize = u16Le(header, 42)
            val phnum = u16Le(header, 44)
            if (phnum <= 0 || phentsize < 32) return EbootInvalidReason.PHDR_FUERA
            if (phoff + phnum.toLong() * phentsize > file.length()) return EbootInvalidReason.PHDR_FUERA
            return EbootInvalidReason.OK
        }

        /**
         * ¿El archivo es un ELF válido? Ver elfValidationReason.
         * Puro (solo File).
         */
        fun isElfFile(file: File): Boolean = elfValidationReason(file) == EbootInvalidReason.OK

        /**
         * Motivo por el que un SELF no es válido (o OK). Un eboot.bin
         * instalado NO es un ELF: sigue siendo un SELF, que empieza
         * por 'S' 'E' 'C' 0x00 (pin 3e43bc80, kernel/src/load_self.cpp:
         * 508-509: SCE_MAGIC = 0x00454353). Se comprueban los mismos
         * campos que comprueba el núcleo al cargarlo (load_self.cpp:
         * 512-520: version == 3 y header_type == 1), con header_type
         * en su offset real, u16 @10 (R4-P0-5: el offset 8 es
         * sdk_type, no header_type; un juego con sysver de 3.40 o
         * más trae sdk_type distinto de 1 y antes se rechazaba
         * aunque el SELF fuera perfecto). Además, chequeo
         * estructural real en vez de piso de tamaño (R4-P0-6):
         * self_filesize u64 @32 solo se exige NO MAYOR que el
         * archivo (truncado si es mayor). R6-P0: la igualdad
         * estricta (self_filesize == length) NO la respalda el
         * pin: load_self.cpp nunca compara self_filesize con el
         * tamaño del archivo (solo lo usa para el dump, :699, y
         * lo loguea, :586); un SELF real puede traer bytes
         * finales y el núcleo lo carga igual. La igualdad
         * estricta era el falso negativo que reinstalaba el
         * .pkg en cada JUGAR con el eboot real instalado
         * (5.340.000 B, sysver 3.15). elf_offset u64 @64 debe caer
         * dentro del archivo y traer la magia ELF (0x7F 'E' 'L' 'F'
         * + EI_CLASS 1) en ese offset, y phdr_offset u64 @72 debe
         * caer dentro del archivo. El núcleo lo descifra EN MEMORIA
         * al cargarlo (load_self), nunca en disco. Puro (solo File).
         */
        fun selfValidationReason(file: File): EbootInvalidReason {
            if (!file.isFile) return EbootInvalidReason.FALTA
            val header = readHeaderBytes(file, SELF_HEADER_SIZE_BYTES) ?: return EbootInvalidReason.TAMANO
            if (!isSecMagic(header)) return EbootInvalidReason.MAGIA
            if (u32Le(header, 4) != 3) return EbootInvalidReason.VERSION
            if (u16Le(header, 10) != 1) return EbootInvalidReason.HEADER_TYPE
            val length = file.length()
            if (u64Le(header, 32) > length) return EbootInvalidReason.TAMANO
            val elfOffset = u64Le(header, 64)
            if (elfOffset < 0 || elfOffset > length - 5) return EbootInvalidReason.TAMANO
            val elfHead = readBytesAt(file, elfOffset, 5) ?: return EbootInvalidReason.TAMANO
            if (!isElfMagic(elfHead) || elfHead[4].toInt() != 1) return EbootInvalidReason.MAGIA
            val phdrOffset = u64Le(header, 72)
            if (phdrOffset < 0 || phdrOffset >= length) return EbootInvalidReason.PHDR_FUERA
            return EbootInvalidReason.OK
        }

        /**
         * ¿El archivo es un SELF válido de Vita? Ver
         * selfValidationReason. Puro (solo File).
         */
        fun isSelfFile(file: File): Boolean = selfValidationReason(file) == EbootInvalidReason.OK

        /**
         * Motivo por el que un eboot.bin no es cargable por el núcleo
         * (o OK). La magia decide qué validación manda: 'S' 'E' 'C'
         * 0x00 va por la vía SELF y 0x7F 'E' 'L' 'F' por la vía ELF;
         * sin ninguna de las dos magias el motivo es MAGIA, y sin
         * archivo o con menos de 4 bytes, FALTA o TAMANO. Puro
         * (solo File).
         */
        fun ebootValidationReason(file: File): EbootInvalidReason {
            if (!file.isFile) return EbootInvalidReason.FALTA
            val head = readHeaderBytes(file, 4) ?: return EbootInvalidReason.TAMANO
            if (isSecMagic(head)) return selfValidationReason(file)
            if (isElfMagic(head)) return elfValidationReason(file)
            return EbootInvalidReason.MAGIA
        }

        /**
         * ¿El eboot.bin es cargable por el núcleo? ELF (descifrado) o
         * SELF (cifrado, el estado normal en disco). Puro (solo File).
         */
        fun isValidEbootFile(file: File): Boolean = isElfFile(file) || isSelfFile(file)

        /**
         * Fusiona DLCs extraídos y DLCs en .pkg guardado sin duplicar
         * el mismo DLC en sus dos formas: un DLC instalado (carpeta
         * en addcont/) cuyo .pkg sigue guardado se cuenta UNA vez
         * (la carpeta extraída manda; el .pkg es solo su copia de
         * respaldo). Sin esto, un solo DLC se veía como 2 (reporte
         * 2026-11-08 21:46: "DLCs: 2" con un solo DLC puesto).
         * P1-2 (2026-11-09): la fusión compara la CLAVE DE CONTENIDO
         * (contentKey), no el dlcId: el nombre del archivo del .pkg
         * puede ser un hash o estar truncado, mientras la carpeta en
         * addcont/ lleva el nombre corto del contenido. Puro (solo
         * listas) para poder probarlo en la JVM.
         */
        fun mergeDlcLists(extracted: List<DlcInfo>, storedPkgs: List<DlcInfo>): List<DlcInfo> {
            val extractedKeys = extracted.map { dlcMergeKey(it.contentKey) }.toSet()
            // R6-P2-08(b): los almacenados también se deduplican ENTRE
            // SÍ por clave de fusión (mismo DLC en .pkg en los dos ux0,
            // o con el nombre en distinta capitalización): se queda el
            // primero. Sin esto, un solo DLC guardado en ambos ux0 se
            // contaba dos veces.
            val seenStoredKeys = mutableSetOf<String>()
            val dedupedStored = storedPkgs.filter { dlcMergeKey(it.contentKey) !in extractedKeys && seenStoredKeys.add(dlcMergeKey(it.contentKey)) }
            return extracted + dedupedStored
        }

        /**
         * Clave de fusión de DLCs (P1-2): el nombre corto del contenido
         * en mayúsculas. Si la clave trae el Content ID completo
         * ("EP0001-PCSB00598_00-CHILDOFLIGHTDLC1", como puede llamarse
         * la carpeta si el DLC vino de un .zip), se queda solo con lo
         * que va tras "_00-". Así coinciden: carpeta extraída, .pkg
         * guardado (contentKey del header) y nombres con distinta
         * capitalización.
         */
        fun dlcMergeKey(raw: String): String {
            val short = raw.substringAfter("_00-", raw)
            return short.uppercase()
        }

        /**
         * R4-P2-7: directorios cuyos tamaños cacheados quedan obsoletos
         * al instalar o importar contenido del [titleId]: el juego
         * (app/), el update (patch/) y los DLCs (addcont/), en cada
         * ux0 dado. scanStatus() solo lee tamaños de la caché para
         * app/<id> y para cada carpeta de DLC bajo addcont/<id>
         * (DirSizeCache.sizeOf), así que invalidar addcont/<id> solo
         * no bastaría: invalidateDirSizes() invalida además sus hijos
         * directos (las carpetas de DLC). Puro (solo File) para poder
         * probarlo en la JVM sin Context.
         */
        fun installedSizeDirs(titleId: String, ux0Dirs: List<File>): List<File> =
            ux0Dirs.flatMap { ux0 ->
                listOf(
                    File(ux0, "app/$titleId"),
                    File(ux0, "patch/$titleId"),
                    File(ux0, "addcont/$titleId"),
                )
            }
    }


    /** ¿El DLC ya está subido (extraído en addcont/ o como .pkg)? Dual: en el ux0 efectivo o en el antiguo. Un nombre con ".bak-" es un respaldo, no un DLC (P2-C1). */
    private fun isDlcInstalled(titleId: String, dlcId: String): Boolean =
        (".bak-" !in dlcId && File(addcontDir(titleId), dlcId).exists()) || findPkgDlcFile(dlcId) != null

    /** ¿El update ya está INSTALADO (contenido real en patch/)? Dual: en el ux0 efectivo o en el antiguo. Un .pkg solo guardado NO es instalado (P1-1): el .pkg se instala en el núcleo al pulsar JUGAR, y contarlo como instalado mentía en el informe. R5-A4-9: antes solo se miraba el patch/ efectivo y el update del ux0 antiguo era invisible en estado de conflicto. R6-P2-05: internal (no private) para que installStoredAddonPkgs (Vita3KLauncher) reutilice el mismo criterio en vez de reimplementarlo. */
    internal fun isUpdateInstalled(titleId: String): Boolean {
        return bothUx0Dirs().any { ux0 ->
            val patch = File(ux0, "patch/$titleId")
            patch.exists() && patch.listFiles()?.isNotEmpty() == true
        }
    }


    /**
     * R5-A4-1: bytes libres en la partición de [dir] (StatFs).
     *
     * P0 espacio libre (0.2.55): -1 si no se puede medir (StatFs
     * falló incluso sobre la sonda); 0 solo si la partición está
     * llena de verdad. Antes devolvía 0 en ambos casos, y como en
     * instalación limpia el destino (ux0/pkg/) aún no existe, StatFs
     * lanzaba y toda importación se rechazaba con "0 bytes libres".
     * La sonda es el ancestro existente más cercano (o filesDir):
     * la medición es por partición, así que da el mismo valor.
     * No testeable en unit tests JVM (StatFs de Android).
     */
    fun freeBytes(dir: File): Long {
        val probe = statFsProbeDir(dir, context.filesDir)
        return runCatching { StatFs(probe.path).availableBytes }.getOrDefault(-1L)
    }

    /**
     * R5-A4-1: ¿cabe [neededBytes] en la partición de [dir]? La
     * única comprobación de espacio libre del launcher: antes no
     * había ninguna y una copia de 1,14 GB fallaba a mitad con el
     * disco casi lleno. Si no se sabe cuánto hace falta (0 o menos),
     * no hay nada que comprobar.
     *
     * P0 espacio libre (0.2.55): si el espacio no se pudo medir
     * (freeBytes menor que 0), NO se bloquea: bloquear por "no lo
     * sé" es lo que rechazaba todas las importaciones en
     * instalación limpia. Tampoco se miente diciendo que hay
     * espacio: el mensaje de rechazo no se llega a mostrar y, si el
     * disco está lleno de verdad, la copia o la instalación fallan
     * con su error real.
     */
    fun hasFreeSpace(dir: File, neededBytes: Long): Boolean {
        if (neededBytes <= 0L) return true
        val free = freeBytes(dir)
        if (free < 0L) return true
        return free >= neededBytes
    }

    /**
     * Mensaje de duplicado para el JUEGO, o null si no está subido.
     * Distingue la misma forma (reemplazar no ocupa más) de otra forma
     * (extraído + .pkg convivirían y ocuparían más). [incomingIsPkg]
     * dice qué se está importando ahora: .pkg (true) o .zip/carpeta
     * extraída (false). P1-10: antes el mensaje era siempre el del
     * .pkg, así que un .zip sobre un .pkg guardado mentía ("no ocupa
     * más") cuando en realidad las dos copias convivían y sumaban.
     */
    private fun gameDuplicateMessage(titleId: String, incomingIsPkg: Boolean): String? {
        val pkg = findPkgGameFile(titleId)
        val extracted = appDir(titleId).exists() && ImportTreeSearch.findEboot(appDir(titleId)) != null
        if (pkg != null && incomingIsPkg) {
            return "El juego $titleId ya está subido como .pkg (${DownloadsCleanup.formatBytes(pkg.length())}). " +
                "Si lo subes de nuevo, se reemplaza el archivo guardado y no ocupa más espacio. ¿Quieres reemplazarlo?"
        }
        if (pkg != null && !incomingIsPkg) {
            return "El juego $titleId ya está subido como .pkg (${DownloadsCleanup.formatBytes(pkg.length())}). " +
                "Si lo subes extraído tendrás las dos copias y ocupará más espacio. ¿Seguro que quieres subirlo de todos modos?"
        }
        if (extracted && !incomingIsPkg) {
            return "El juego $titleId ya está instalado (extraído). Si lo subes de nuevo, " +
                "se reemplaza la copia instalada y no ocupa más espacio. ¿Quieres reemplazarlo?"
        }
        if (extracted && incomingIsPkg) {
            return "El juego $titleId ya está instalado (extraído). Si lo subes como .pkg tendrás " +
                "las dos copias y ocupará más espacio. ¿Seguro que quieres subirlo de todos modos?"
        }
        return null
    }

    private fun updateDuplicateMessage(titleId: String, incomingIsPkg: Boolean): String? {
        val pkg = findPkgPatchFile(titleId)
        val extracted = patchDir(titleId).exists() && patchDir(titleId).listFiles()?.isNotEmpty() == true
        if (pkg != null && incomingIsPkg) {
            return "La actualización de $titleId ya está subida como .pkg. Si la subes de nuevo, se reemplaza " +
                "la copia guardada y no ocupa más espacio. ¿Quieres reemplazarla?"
        }
        if (pkg != null && !incomingIsPkg) {
            return "La actualización de $titleId ya está subida como .pkg. Si la subes extraída tendrás " +
                "las dos copias y ocupará más espacio. ¿Seguro que quieres subirla de todos modos?"
        }
        if (extracted && !incomingIsPkg) {
            return "La actualización de $titleId ya está subida (extraído). Si la subes de nuevo, se reemplaza " +
                "la copia instalada y no ocupa más espacio. ¿Quieres reemplazarla?"
        }
        if (extracted && incomingIsPkg) {
            return "La actualización de $titleId ya está subida (extraído). Si la subes como .pkg tendrás " +
                "las dos copias y ocupará más espacio. ¿Seguro que quieres subirla de todos modos?"
        }
        return null
    }

    /** Mensaje de duplicado para un DLC, distinguiendo misma forma de forma distinta (P1-10). */
    private fun dlcDuplicateMessage(titleId: String, dlcId: String, incomingIsPkg: Boolean): String? {
        val pkg = findPkgDlcFile(dlcId)
        val extracted = File(addcontDir(titleId), dlcId).exists()
        if (pkg != null && incomingIsPkg) {
            return "El DLC $dlcId ya está subido como .pkg. Si lo subes de nuevo, se reemplaza " +
                "la copia guardada y no ocupa más espacio. ¿Quieres reemplazarlo?"
        }
        if (pkg != null && !incomingIsPkg) {
            return "El DLC $dlcId ya está subido como .pkg. Si lo subes extraído tendrás " +
                "las dos copias y ocupará más espacio. ¿Seguro que quieres subirlo de todos modos?"
        }
        if (extracted && !incomingIsPkg) {
            return "El DLC $dlcId ya está subido (extraído). Si lo subes de nuevo, se reemplaza " +
                "la copia instalada y no ocupa más espacio. ¿Quieres reemplazarlo?"
        }
        if (extracted && incomingIsPkg) {
            return "El DLC $dlcId ya está subido (extraído). Si lo subes como .pkg tendrás " +
                "las dos copias y ocupará más espacio. ¿Seguro que quieres subirlo de todos modos?"
        }
        return null
    }

    private fun duplicateResult(message: String): ImportResult =
        ImportResult.Rejected("$DUPLICATE_MARKER$message")

    /**
     * Borra restos de importaciones que se cortaron a medias (app cerrada
     * o sin espacio durante la copia): temporales "*.tmp" de .pkg, juego,
     * DLCs, update y firmware. Sin esto, cada importación interrumpida
     * dejaba hasta 1 GB huérfano y el launcher "engordaba" sin que el
     * usuario supiera por qué.
     *
     * P0-2: los respaldos "*.bak-<timestamp>" YA NO se borran aquí.
     * Son el ÚNICO respaldo del juego/DLC anterior si la app murió a
     * mitad de instalación: borrarlos antes de que la nueva importación
     * termine podía dejar al usuario sin juego viejo ni nuevo. Los
     * restaura [restoreStaleBackups] (al inicio de cada importación y
     * en [scanStatus]); un .bak-* cuyo destino ya existe se deja en
     * disco: ocupa espacio, pero borrarlo sería borrar un respaldo
     * cuya copia nueva no se ha verificado como completa.
     * P2-31: el borrado de esos .bak-* huérfanos (destino existe) es
     * una acción EXPLÍCITA del usuario en "Limpiar no usados"
     * ([deleteOrphanBackups], con su tamaño visible antes de
     * confirmar); aquí, en la limpieza silenciosa, siguen sin tocarse.
     */
    // R5-A4-1: internal (no private) para que refreshStatusOnce() del
    // ViewModel la llame al inicio de cada refresco: un .tmp huérfano
    // de una importación fallida no debe esperar a la siguiente
    // importación para limpiarse.
    internal fun cleanStaleImportLeftovers() {
        runCatching {
            listOf(pkgDir(), pkgDlcDir(), pkgPatchDir()).forEach { dir ->
                dir.listFiles()?.forEach { f ->
                    if (f.name.endsWith(".tmp") && f.isFile) f.delete()
                }
            }
            // P2-C10: el temporal del firmware (PSVUPDAT.PUP.tmp, hasta
            // ~134 MB) también quedaba huérfano si la copia fallaba.
            firmwareDir().listFiles()?.forEach { f ->
                if (f.name.endsWith(".tmp") && f.isFile) f.delete()
            }
        }
    }

    /**
     * Restaura los respaldos "*.bak-<timestamp>" cuyo destino original
     * ya no existe (P0-2): si la app murió entre el rename del juego
     * viejo a .bak-* y la instalación del nuevo, el juego viejo SOLO
     * vive como .bak-*. Renombrarlo de vuelta lo recupera. Solo se
     * restaura cuando el destino no existe: si el destino existe, el
     * .bak-* es un respaldo viejo y se deja (ver [cleanStaleImportLeftovers]).
     * Se llama al inicio de cada importación y en [scanStatus].
     */
    private fun restoreStaleBackups() {
        runCatching {
            val ux0s = bothUx0Dirs()
            for (ux0 in ux0s) {
                restoreBackupsIn(File(ux0, "pkg"))
                restoreBackupsIn(File(ux0, "pkg/dlc"))
                restoreBackupsIn(File(ux0, "pkg/patch"))
                restoreBackupsIn(File(ux0, "app"))
                restoreBackupsIn(File(ux0, "patch"))
                File(ux0, "addcont").listFiles()
                    ?.filter { it.isDirectory }
                    ?.forEach { restoreBackupsIn(it) }
            }
        }
    }

    /**
     * P2-31: .bak-* HUÉRFANOS: su destino ORIGINAL existe (la
     * instalación completó y el respaldo quedó atrás porque el borrado
     * del respaldo falló, o la app murió entre la copia y el borrado).
     * [restoreStaleBackups] nunca los toca (solo restaura cuando el
     * destino FALTA), así que solo ocupan espacio — pueden ser GBs.
     * NO se borran solos al arrancar: el borrado es una acción
     * explícita del usuario en "Limpiar no usados", con el tamaño
     * visible antes de confirmar. Un .bak-* cuyo destino falta NO es
     * huérfano (es el único respaldo del juego anterior) y no se toca.
     * Devuelve (cantidad, bytes totales).
     */
    fun findOrphanBackups(): Pair<Int, Long> {
        val orphans = scanOrphanBackups()
        return orphans.size to orphans.sumOf { dirSizeBytes(it) }
    }

    /** Borra los .bak-* huérfanos ([findOrphanBackups]) y devuelve (cantidad borrada, bytes liberados). */
    fun deleteOrphanBackups(): Pair<Int, Long> {
        var count = 0
        var freed = 0L
        for (bak in scanOrphanBackups()) {
            val bytes = dirSizeBytes(bak)
            bak.deleteRecursively()
            if (!bak.exists()) {
                count++
                freed += bytes
            }
        }
        if (count > 0) {
            LauncherLog.append(context, "delete", "Respaldos .bak huérfanos borrados: $count (${DownloadsCleanup.formatBytes(freed)} liberados).")
        }
        return count to freed
    }

    /** Todos los .bak-* huérfanos en ambos ux0 (padres + addcont/<título>/). */
    private fun scanOrphanBackups(): List<File> {
        val found = mutableListOf<File>()
        for (ux0 in bothUx0Dirs()) {
            val parents = mutableListOf(
                File(ux0, "pkg"),
                File(ux0, "pkg/dlc"),
                File(ux0, "pkg/patch"),
                File(ux0, "app"),
                File(ux0, "patch"),
            )
            File(ux0, "addcont").listFiles()
                ?.filter { it.isDirectory }
                ?.forEach { parents.add(it) }
            parents.forEach { found.addAll(orphanBackupsIn(it)) }
        }
        return found
    }

    /** .bak-* de un directorio cuyo destino original EXISTE (huérfanos). */
    private fun orphanBackupsIn(dir: File): List<File> {
        return dir.listFiles()?.filter { f ->
            ".bak-" in f.name && run {
                val originalName = f.name.substringBefore(".bak-")
                originalName.isNotBlank() && originalName != f.name && File(dir, originalName).exists()
            }
        } ?: emptyList()
    }

    /** Tamaño en bytes de un archivo o directorio (recursivo). */
    private fun dirSizeBytes(file: File): Long {
        if (file.isFile) return file.length()
        return file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun restoreBackupsIn(dir: File) {
        dir.listFiles()?.forEach { f ->
            if (".bak-" !in f.name) return@forEach
            val originalName = f.name.substringBefore(".bak-")
            if (originalName.isBlank() || originalName == f.name) return@forEach
            val dest = File(dir, originalName)
            if (dest.exists()) return@forEach
            if (f.renameTo(dest)) {
                // R5-A1-13: el directorio restaurado cambió de sitio:
                // su tamaño cacheado (y el de sus hijos) ya no vale.
                invalidateDirSizes(dest)
                LauncherLog.append(context, "import", "Respaldo restaurado: ${f.name} -> $originalName (la app murió a mitad de una importación anterior).")
            }
        }
    }

    /**
     * Los dos ux0 que pueden tener contenido: el efectivo y el antiguo
     * (distintos cuando el efectivo es vita/ux0). Las búsquedas y los
     * borrados duales recorren ambos para no dejar .pkg, DLCs ni saves
     * huérfanos en el antiguo (P1-5).
     */
    private fun bothUx0Dirs(): List<File> =
        listOf(ux0Dir, EmbeddedCoreLayout.legacyUx0Dir(vitaRoot)).distinct()

    val vitaRoot: File get() = File(context.filesDir, "vita3k")
    /** ux0 efectivo: vita/ux0 si ya se migró al layout del núcleo, si no el ux0 antiguo. */
    val ux0Dir: File get() = EmbeddedCoreLayout.resolveUx0Dir(vitaRoot)

    /**
     * Los dos ux0 donde puede vivir contenido: el efectivo y el
     * antiguo (P1-5). Tras la migración el juego vive en el efectivo,
     * pero un DLC, un .pkg o unos saves pueden haber quedado en el
     * antiguo: borrar solo en el efectivo dejaba copias fantasma.
     */
    private fun ux0Dirs(): List<File> {
        val legacy = EmbeddedCoreLayout.legacyUx0Dir(vitaRoot)
        return if (legacy == ux0Dir) listOf(ux0Dir) else listOf(ux0Dir, legacy)
    }
    val configFile: File get() = File(vitaRoot, "config.yml")

    fun appDir(titleId: String): File = File(ux0Dir, "app/$titleId")
    fun addcontDir(titleId: String): File = File(ux0Dir, "addcont/$titleId")
    fun patchDir(titleId: String): File = File(ux0Dir, "patch/$titleId")
    fun saveDir(titleId: String): File = File(ux0Dir, "user/00/savedata/$titleId")
    fun firmwareDir(): File = File(vitaRoot, "firmware")

    // --- Almacenamiento de .pkg (ver PkgHeaderParser) ---
    /** Carpeta de .pkg guardados: ux0/pkg/. El nombre del .pkg original no se conserva: se guarda por Title ID. */
    fun pkgDir(): File = File(ux0Dir, "pkg")
    fun pkgGameFile(titleId: String): File = File(pkgDir(), "$titleId.pkg")
    fun pkgWorkBinFile(titleId: String): File = File(pkgDir(), "$titleId.work.bin")

    /**
     * Motivo de una validación de eboot (R4-P1-5, 2026-11-09): la
     * validación ya no devuelve solo un Boolean opaco, sino el
     * motivo exacto, para que el log diga por qué un eboot no
     * vale sin tener que adivinar entre tamaño y offset.
     * FALTA: no hay eboot.bin. TAMANO: el archivo es más corto
     * que su cabecera o que lo que su propia cabecera declara
     * (truncado). MAGIA: ni magia SELF ni magia ELF, o el ELF
     * interior del SELF no trae su magia. VERSION: SELF con
     * version distinta de 3. HEADER_TYPE: SELF con header_type
     * distinto de 1. PHDR_FUERA: la tabla de segmentos apunta
     * fuera del archivo. OK: cargable por el núcleo.
     */
    enum class EbootInvalidReason(val logName: String) {
        FALTA("falta"),
        TAMANO("tamaño"),
        MAGIA("magia"),
        VERSION("version"),
        HEADER_TYPE("header_type"),
        PHDR_FUERA("phdr_fuera"),
        OK("ok"),
    }

    /**
     * Resultado de validar el eboot.bin instalado (R4-P1-5,
     * 2026-11-09): además del motivo, los campos que el log
     * necesita para discriminar entre R4-P0-5 (sdk_type leído
     * como header_type) y R4-P0-6 (tamaño). version, sdkType y
     * headerType son "?" cuando el archivo no es un SELF legible.
     * R6-P0: selfFilesize es el self_filesize declarado por la
     * cabecera SELF (u64 @32), "-1" cuando no es un SELF legible:
     * el próximo informe del teléfono lo necesita para decir qué
     * campo falla en el eboot real, sin adivinarlo.
     */
    data class EbootValidation(
        val path: String,
        val sizeBytes: Long,
        val magic: String,
        val version: String,
        val sdkType: String,
        val headerType: String,
        val reason: EbootInvalidReason,
        val selfFilesize: String = "-1",
    ) {
        /** Una sola línea de log con todo el diagnóstico. */
        fun logLine(): String =
            "eboot=$path tamaño=$sizeBytes bytes magia=$magic version=$version " +
                "sdk_type=$sdkType header_type=$headerType self_filesize=$selfFilesize motivo=${reason.logName}"
    }

    /**
     * Valida el eboot.bin instalado y devuelve el motivo, no solo
     * un Boolean (R4-P1-5). No cambia ninguna decisión: el motivo
     * OK es exactamente isValidEbootFile. FALTA si no hay eboot.bin.
     */
    fun ebootValidation(titleId: String): EbootValidation {
        val expectedPath = File(appDir(titleId), "eboot.bin").absolutePath
        val eboot = runCatching { ImportTreeSearch.findEboot(appDir(titleId)) }.getOrNull()
            ?: return EbootValidation(expectedPath, 0, "?", "?", "?", "?", EbootInvalidReason.FALTA)
        val reason = ebootValidationReason(eboot)
        val header = readHeaderBytes(eboot, SELF_HEADER_SIZE_BYTES)
        val magic = when {
            header != null && isSecMagic(header) -> "SEC"
            header != null && isElfMagic(header) -> "ELF"
            else -> "?"
        }
        val version = if (header != null && magic == "SEC") u32Le(header, 4).toString() else "?"
        val sdkType = if (header != null && magic == "SEC") u16Le(header, 8).toString() else "?"
        val headerType = if (header != null && magic == "SEC") u16Le(header, 10).toString() else "?"
        val selfFilesize = if (header != null && magic == "SEC") u64Le(header, 32).toString() else "-1"
        return EbootValidation(eboot.absolutePath, eboot.length(), magic, version, sdkType, headerType, reason, selfFilesize)
    }

    /**
     * .pkg del juego esté donde esté: en el ux0 efectivo o en el
     * antiguo. Tras instalarse el juego, el ux0 efectivo pasa a ser
     * vita/ux0 pero el .pkg sigue en el ux0 antiguo (la migración
     * AlreadyMigrated no mueve nada): pkgGameFile() ya no lo ve.
     * null si no hay .pkg en ninguno de los dos.
     */
    fun findPkgGameFile(titleId: String): File? {
        val resolved = pkgGameFile(titleId)
        if (resolved.isFile) return resolved
        val legacy = File(EmbeddedCoreLayout.legacyUx0Dir(vitaRoot), "pkg/$titleId.pkg")
        if (legacy.isFile) return legacy
        return null
    }

    /** work.bin junto a un .pkg encontrado por findPkgGameFile. */
    fun findPkgWorkBinFile(pkgFile: File): File =
        File(pkgFile.parentFile, "${pkgFile.nameWithoutExtension}.work.bin")

    /**
     * .pkg de DLC esté donde esté: en el ux0 efectivo o en el antiguo.
     * Mismo patrón que [findPkgGameFile] (P1-5a). Null si no hay .pkg
     * de ese DLC en ninguno de los dos. La licencia va junto al .pkg
     * encontrado: [findPkgWorkBinFile].
     */
    fun findPkgDlcFile(dlcId: String): File? {
        val resolved = pkgDlcFile(dlcId)
        if (resolved.isFile) return resolved
        val legacy = File(EmbeddedCoreLayout.legacyUx0Dir(vitaRoot), "pkg/dlc/$dlcId.pkg")
        if (legacy.isFile) return legacy
        return null
    }

    /**
     * .pkg de update esté donde esté: en el ux0 efectivo o en el
     * antiguo. Mismo patrón que [findPkgGameFile] (P1-5a). Null si no
     * hay .pkg de update de ese Title ID en ninguno de los dos. La
     * licencia va junto al .pkg encontrado: [findPkgWorkBinFile].
     */
    fun findPkgPatchFile(titleId: String): File? {
        val resolved = pkgPatchFile(titleId)
        if (resolved.isFile) return resolved
        val legacy = File(EmbeddedCoreLayout.legacyUx0Dir(vitaRoot), "pkg/patch/$titleId.pkg")
        if (legacy.isFile) return legacy
        return null
    }
    fun pkgDlcDir(): File = File(pkgDir(), "dlc")
    fun pkgDlcFile(dlcId: String): File = File(pkgDlcDir(), "$dlcId.pkg")
    fun pkgDlcWorkBinFile(dlcId: String): File = File(pkgDlcDir(), "$dlcId.work.bin")
    fun pkgPatchDir(): File = File(pkgDir(), "patch")
    fun pkgPatchFile(titleId: String): File = File(pkgPatchDir(), "$titleId.pkg")
    fun pkgPatchWorkBinFile(titleId: String): File = File(pkgPatchDir(), "$titleId.work.bin")

    /** .pkg guardado al que le falta su work.bin (licencia). */
    data class MissingPkgLicense(
        val titleId: TitleId,
        val kind: com.danielsnagustin.childoflight.launcher.model.PkgKind,
        val dlcId: String?,
    )

    /** .pkg guardados sin su work.bin (licencia). Sin ella Vita3K no puede instalarlos. Dual: busca en el ux0 efectivo y en el antiguo (P1-5a). */
    /**
     * Ronda 0.2.70, 7-P2-8 (decisión DOCUMENTADA, sin extensión del
     * chequeo a DLCs extraídos): los DLC extraídos (zip/addcont/) NO
     * se comprueban por licencia porque el núcleo no les pide ninguna
     * en tiempo de ejecución. Verificado en el pin 3e43bc80:
     * `get_license` (packages/src/license.cpp) solo carga el .rif
     * DEL JUEGO (title_id + content_id del juego, llamado una vez en
     * setup_game_launch, app/src/app.cpp:257) y si falta solo registra
     * un aviso y sigue con sku_flag por defecto — no bloquea la carga
     * ni itera los DLCs de addcont/. La licencia de un DLC solo se
     * usa al INSTALAR su .pkg (create_license/copy_license + el
     * zRIF/work.bin); un DLC extraído por zip ya trae sus archivos
     * descifrados y el juego los lee de addcont/ por filesystem, sin
     * puerta de licencia. Por eso avisar "falta el .rif" de un DLC
     * extraído sería un falso positivo: no hay ningún .rif que el
     * núcleo fuera a consultar por él. Los .pkg guardados SÍ se
     * comprueban abajo (aún no instalados: su licencia decide si se
     * pueden instalar).
     */
    fun storedPkgsMissingLicense(): List<MissingPkgLicense> {
        val missing = mutableListOf<MissingPkgLicense>()
        for (candidate in TitleId.entries) {
            val gamePkg = findPkgGameFile(candidate.id)
            // R6-P1-04: un .pkg de juego sin work.bin NO es "falta de
            // licencia" si la licencia ya está instalada en el núcleo
            // (ux0/license/<TITLE>/<CONTENT_ID>.rif): el juego se
            // instaló una vez con licencia y el .pkg se puede
            // reinstalar sin work.bin (findPkgZrif). Sin esto, Importar
            // pedía el work.bin en cada refresco aunque el juego
            // corriera. R7-P1-04 parcial: lo mismo vale para el
            // update (rama de abajo): comparte el Content ID del
            // juego y por tanto su licencia.
            if (gamePkg != null && !findPkgWorkBinFile(gamePkg).isFile && !isLicenseInstalledInCore(candidate.id)) {
                missing.add(MissingPkgLicense(candidate, com.danielsnagustin.childoflight.launcher.model.PkgKind.GAME, null))
            }
            val patchPkg = findPkgPatchFile(candidate.id)
            // R7-P1-04 parcial (0.2.57): igual que el juego: el update
            // comparte el Content ID del juego (pin pkg.cpp: el patch
            // se distingue por la CATEGORY "gp" del param.sfo, no por
            // Content ID), así que su licencia ES la del juego y
            // findPkgZrif la resuelve sin work.bin.
            if (patchPkg != null && !findPkgWorkBinFile(patchPkg).isFile && !isLicenseInstalledInCore(candidate.id)) {
                missing.add(MissingPkgLicense(candidate, com.danielsnagustin.childoflight.launcher.model.PkgKind.UPDATE, null))
            }
        }
        // DLCs en .pkg: en ambos ux0, sin duplicar el mismo DLC.
        val dlcPkgs = mutableMapOf<String, File>()
        for (ux0 in bothUx0Dirs()) {
            File(File(ux0, "pkg"), "dlc").listFiles()
                ?.filter { it.isFile && it.name.lowercase().endsWith(".pkg") }
                ?.forEach { pkg -> dlcPkgs.putIfAbsent(pkg.nameWithoutExtension, pkg) }
        }
        dlcPkgs.forEach { (dlcId, pkg) ->
            if (!findPkgWorkBinFile(pkg).isFile) {
                val header = runCatching {
                    pkg.inputStream().use { PkgHeaderParser.parse(it) }
                }.getOrNull()?.getOrNull()
                val title = header?.titleId ?: TitleId.US
                // R7-P1-04 resto (0.2.57): igual que el juego y el
                // update, un .pkg de DLC sin work.bin NO es "falta de
                // licencia" si su PROPIO .rif ya está instalado en el
                // núcleo: installPkgFile lo reinstala sin work.bin vía
                // findPkgZrif (pin pkg.cpp: el zRIF se busca por el
                // Content ID del propio .pkg). OJO: NO vale
                // isLicenseInstalledInCore(title): comprueba el .rif
                // DEL JUEGO (su Content ID), que eximiría todos los
                // DLCs en cuanto existiera el .rif del juego. Aquí
                // solo vale el .rif con el Content ID propio de ESTE
                // .pkg de DLC (ver isDlcLicenseInstalledInCore). Sin
                // header legible no hay Content ID que comprobar: se
                // sigue marcando falta (lo honesto).
                val dlcContentId = header?.contentId?.trim()?.takeIf { it.isNotBlank() }
                val licensedInCore = dlcContentId != null &&
                    isDlcLicenseInstalledInCore(ux0Dir, title.id, dlcContentId)
                if (!licensedInCore) {
                    missing.add(MissingPkgLicense(title, com.danielsnagustin.childoflight.launcher.model.PkgKind.DLC, dlcId))
                }
            }
        }
        return missing
    }

    /** Archivos .pkg + work.bin guardados, para entregarlos a Vita3K. Dual: en el ux0 efectivo y en el antiguo (P1-5a). */
    fun storedPkgFilesForDelivery(): List<File> {
        val out = mutableListOf<File>()
        for (ux0 in bothUx0Dirs()) {
            File(ux0, "pkg").listFiles()?.forEach { file ->
                if (file.isFile && (file.name.lowercase().endsWith(".pkg") || file.name.lowercase().endsWith(".work.bin"))) {
                    out.add(file)
                }
            }
            listOf(File(File(ux0, "pkg"), "dlc"), File(File(ux0, "pkg"), "patch")).forEach { dir ->
                dir.listFiles()?.forEach { file ->
                    if (file.isFile && (file.name.lowercase().endsWith(".pkg") || file.name.lowercase().endsWith(".work.bin"))) {
                        out.add(file)
                    }
                }
            }
        }
        return out.distinctBy { it.absolutePath }.sortedBy { it.name }
    }

    // --- Descargas/ChildOfLight/ (copias para la app externa) ---
    //
    // El juego, los DLCs, los updates y la licencia viven SIEMPRE en el
    // almacenamiento PRIVADO del launcher (filesDir/vita3k): la única
    // copia a Descargas es la entrega explícita a la app externa
    // (Vita3KLauncher.deliverPkgFiles), que el usuario confirma antes
    // en un diálogo y que dejará de existir cuando el núcleo embebido
    // esté listo. Estas funciones listan y borran esas copias: la app
    // ofrece la limpieza en Ajustes → Almacenamiento y NUNCA borra
    // nada sin que el usuario lo confirme en el diálogo.

    /** Lista lo que hay en Descargas/ChildOfLight/ (copias entregadas a la app externa). */
    fun listDownloadsCopies(): List<DownloadsCopy> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // MediaStore (API 29+): sin permisos, son archivos propios de la app.
            val out = mutableListOf<DownloadsCopy>()
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.SIZE),
                "${MediaStore.Downloads.RELATIVE_PATH} = ?",
                arrayOf("Download/ChildOfLight/"),
                null,
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                while (cursor.moveToNext()) {
                    out.add(DownloadsCopy(cursor.getString(nameIdx), cursor.getLong(sizeIdx)))
                }
            }
            out.sortedBy { it.name }
        } else {
            // API 28: carpeta pública legacy.
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ChildOfLight")
            dir.listFiles()
                ?.filter { it.isFile }
                ?.map { DownloadsCopy(it.name, it.length()) }
                ?.sortedBy { it.name }
                ?: emptyList()
        }
    }

    /**
     * Borra todo lo de Descargas/ChildOfLight/. Devuelve cuántos archivos
     * borró. Solo se llama desde la confirmación del usuario en Ajustes →
     * Almacenamiento. El juego/DLCs/licencia del almacenamiento interno
     * NO se tocan.
     */
    fun deleteDownloadsCopies(): Int {
        var deleted = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.RELATIVE_PATH} = ?",
                arrayOf("Download/ChildOfLight/"),
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    deleted += resolver.delete(
                        ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id),
                        null,
                        null,
                    )
                }
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ChildOfLight")
            dir.listFiles()?.forEach { file ->
                if (file.isFile && file.delete()) deleted++
            }
            // Si quedó vacía, se borra también la carpeta.
            runCatching { dir.delete() }
        }
        return deleted
    }


    /**
     * Dónde se guarda un .pkg según su tipo. El DLC se guarda por el
     * nombre del contenido del Content ID (saneado), no por el nombre
     * del archivo: "CHILDOFLIGHTDLC1.pkg", etc.
     */
    fun pkgStorageFor(titleId: TitleId, header: PkgHeader, kind: com.danielsnagustin.childoflight.launcher.model.PkgKind): Pair<File, File> {
        return when (kind) {
            com.danielsnagustin.childoflight.launcher.model.PkgKind.GAME ->
                pkgGameFile(titleId.id) to pkgWorkBinFile(titleId.id)
            com.danielsnagustin.childoflight.launcher.model.PkgKind.UPDATE ->
                pkgPatchFile(titleId.id) to pkgPatchWorkBinFile(titleId.id)
            com.danielsnagustin.childoflight.launcher.model.PkgKind.DLC -> {
                val dlcId = sanitizeContentName(header.contentName).ifBlank { "dlc-${titleId.id}" }
                pkgDlcFile(dlcId) to pkgDlcWorkBinFile(dlcId)
            }
        }
    }

    /** Nombre de archivo seguro a partir del nombre del contenido del .pkg. */
    fun sanitizeContentName(raw: String): String {
        return raw.map { c ->
            if (c.isLetterOrDigit() || c == '-' || c == '_' || c == '.') c else '_'
        }.joinToString("").trim('.', '_').take(40)
    }

    /** Copia atómica de un URI a un archivo: temporal + rename. */
    private fun copyUriAtomic(uri: Uri, dest: File) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        copyUriToFile(uri, tmp)
        if (!tmp.renameTo(dest)) {
            tmp.delete()
            throw IllegalStateException("No se pudo guardar ${dest.name}.")
        }
    }

    /**
     * Busca el work.bin hermano del .pkg elegido (misma carpeta SAF).
     * Orden: "work.bin" exacto, "work (N).bin" (copias de Chrome),
     * cualquier .bin de 512 B. El contenido se valida después con
     * PkgHeaderParser.validateWorkBin: un .bin que no sea licencia
     * (.rif binario con Content ID, o texto zRIF «KO») no se acepta. Best-effort: algunos proveedores no dejan
     * listar la carpeta; en ese caso se devuelve null y se pide el
     * work.bin al usuario.
     */
    fun findWorkBinSibling(pkgUri: Uri): Uri? {
        return try {
            val pkgDoc = DocumentFile.fromSingleUri(context, pkgUri) ?: return null
            val parent = pkgDoc.parentFile ?: return null
            val siblings = parent.listFiles().filter { it.isFile }
            siblings.firstOrNull { it.name.equals("work.bin", ignoreCase = true) }?.uri
                ?: siblings.firstOrNull {
                    val n = it.name?.lowercase() ?: ""
                    n.startsWith("work") && n.endsWith(".bin")
                }?.uri
                ?: siblings.firstOrNull {
                    it.name?.lowercase()?.endsWith(".bin") == true && it.length() == 512L
                }?.uri
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Guarda el work.bin elegido por el usuario para un .pkg ya
     * guardado. Valida el contenido (512 B: licencia .rif binaria con
     * Content ID, o texto zRIF «KO») antes de copiar. Si se pasa
     * expectedContentId, el Content ID del .bin debe coincidir: el .bin
     * tiene que ser la licencia DE ESTE .pkg, no de otro juego.
     */
    fun importWorkBin(uri: Uri, licenseDest: File, expectedContentId: String? = null): Result<Unit> {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                PkgHeaderParser.validateWorkBin(input, expectedContentId).getOrThrow()
            } ?: return Result.failure(IllegalStateException("No se pudo abrir el archivo elegido."))
            copyUriAtomic(uri, licenseDest)
            LauncherLog.append(context, "import", "work.bin guardado en ${licenseDest.absolutePath}")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Borra la carpeta legacy import_pending/ (flujo antiguo sin parser). */
    private fun cleanLegacyPending() {
        runCatching {
            val pending = File(vitaRoot, "import_pending")
            if (pending.exists()) {
                pending.deleteRecursively()
                LauncherLog.append(context, "import", "Carpeta legacy import_pending/ eliminada (flujo .pkg antiguo).")
            }
        }
    }

    /** Title ID leído de sce_sys/param.sfo dentro de una carpeta SAF (por contenido, no por nombre). */
    private fun titleIdFromFolderSfo(root: DocumentFile): TitleId? =
        TitleId.fromString(sfoTitleIdRaw(root))

    /** TITLE_ID en crudo de sce_sys/param.sfo dentro de una carpeta SAF (null si no hay SFO o no trae TITLE_ID). */
    private fun sfoTitleIdRaw(root: DocumentFile): String? {
        return try {
            val sceSys = root.listFiles()
                .firstOrNull { it.isDirectory && it.name.equals("sce_sys", ignoreCase = true) } ?: return null
            val sfo = sceSys.listFiles()
                .firstOrNull { it.isFile && it.name.equals("param.sfo", ignoreCase = true) } ?: return null
            if (sfo.length() > ParamSfo.MAX_SFO_BYTES) return null
            val bytes = context.contentResolver.openInputStream(sfo.uri)?.use { it.readBytes() } ?: return null
            ParamSfo.parse(bytes).getOrNull()?.titleId?.trim()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * R5-A4-3: ¿la licencia del juego extraído ya está registrada en
     * el núcleo? El núcleo guarda el .rif en
     * ux0/license/<TITLE>/<CONTENT_ID>.rif (pin license.cpp:99). El
     * Content ID sale del param.sfo instalado
     * (app/<TITLE>/sce_sys/param.sfo). Solo mira el ux0
     * efectivo: el juego extraído vive ahí y su licencia también.
     *
     * Ronda 0.2.70, 7-P2-9: si el SFO no trae CONTENT_ID, la
     * respuesta es false ("desconocido", no "instalado"): el
     * fallback anterior ("cualquier .rif en la carpeta") aceptaba
     * el .rif de un DLC como licencia del juego, exactamente la
     * trampa que el chequeo de DLCs ya evitaba.
     */
    /**
     * Rebote de JUGAR (2026-11-13): Content ID del SFO instalado
     * (null si no se puede leer). Se usa para distinguir en el
     * reporte "licencia no registrada" (SFO legible, sin .rif) de
     * "sin dato" (SFO ilegible).
     */
    private fun licenseContentId(titleId: String): String? {
        val sfo = File(appDir(titleId), "sce_sys/param.sfo")
        return runCatching { ParamSfo.parse(sfo) }.getOrNull()?.getOrNull()?.contentId?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun isLicenseInstalledInCore(titleId: String): Boolean {
        return isGameLicenseInstalledInCore(ux0Dir, titleId, licenseContentId(titleId))
    }

    // R4-P1-12: si el llamador ya resolvió el firmware en este mismo
    // refresco, se reutiliza en vez de resolverlo otra vez dentro del
    // escaneo (antes FirmwareResolver.resolve() corría 2 veces por
    // refresco: una aquí y otra en el ViewModel). Sin argumento se
    // resuelve aquí como siempre: los demás llamadores no cambian.
    fun scanStatus(firmware: FirmwareStatus? = null): GameStatus {
        // P0-2: antes de leer nada, restaurar los respaldos .bak-* cuyo
        // destino ya no existe (la app murió a mitad de una importación
        // anterior y el juego/DLC viejo solo vive como .bak-*).
        restoreStaleBackups()
        var extractedTitle: TitleId? = null
        var gameSize = 0L
        // M15: si están instaladas las dos regiones, solo se muestra US
        // (GameStatus lleva un solo titleId). Se registra para no esconderlo.
        var installedCount = 0
        for (candidate in TitleId.entries) {
            val dir = appDir(candidate.id)
            // "No lo reconoce": el eboot puede venir como EBOOT.BIN
            // (mayúsculas, habitual en dumps). La comprobación era
            // sensible al caso y el juego instalado no se reconocía.
            if (dir.exists() && ImportTreeSearch.findEboot(dir) != null) {
                installedCount++
                if (extractedTitle == null) {
                    extractedTitle = candidate
                    gameSize = dirSizeCache.sizeOf(dir)
                }
            }
        }
        if (installedCount > 1) {
            LauncherLog.append(context, "status", "Dos regiones instaladas (US y EU); se muestra ${extractedTitle?.id}. Los DLCs/saves de la otra región no se ven.")
        }
        // .pkg de juego guardado: su Title ID se lee del header (Content
        // ID), nunca del nombre del archivo guardado (que es el Title ID,
        // pero el header manda si el archivo está corrupto o a medias).
        // Dual: el .pkg puede vivir en el ux0 antiguo (P1-5a). P2-C7:
        // antes había un break tras el primer Title ID y con dos
        // regiones instaladas solo se veía una; ahora se registran
        // ambas (el estado lleva una sola, la primera, y se deja en
        // el log que hay otra).
        var pkgTitle: TitleId? = null
        var pkgContentId: String? = null
        var pkgSize = 0L
        var pkgLicense = false
        val pkgTitlesFound = mutableListOf<TitleId>()
        for (candidate in TitleId.entries) {
            val pkg = findPkgGameFile(candidate.id) ?: continue
            val header = runCatching {
                pkg.inputStream().use { PkgHeaderParser.parse(it) }
            }.getOrNull()?.getOrNull()
            // Si el header no se lee (pkg corrupto o a medias), se sigue
            // mostrando como .pkg: el usuario puede borrarlo e importar
            // de nuevo. No se inventa el Title ID.
            val foundTitle = header?.titleId ?: candidate
            pkgTitlesFound.add(foundTitle)
            if (pkgTitle == null) {
                pkgTitle = foundTitle
                pkgContentId = header?.contentId
                pkgSize = pkg.length()
                // P1-3: "existe el archivo" no es licencia: un work.bin
                // de otro juego (o un archivo cualquiera de 512 B) se
                // aceptaba como licencia válida y el juego fallaba al
                // arrancar sin explicación. Se valida el CONTENIDO
                // contra el Content ID del .pkg, igual que al importar.
                // Si no hay work.bin o no es válido, la licencia es
                // "falta": es lo honesto, y el informe ya no miente.
                pkgLicense = runCatching {
                    findPkgWorkBinFile(pkg).inputStream().use {
                        PkgHeaderParser.validateWorkBin(it, header?.contentId)
                    }
                }.getOrNull()?.isSuccess == true
            } else {
                // R6-P2-08(a): la licencia de TODOS los .pkg de juego,
                // no solo del primero: con dos regiones, el segundo
                // .pkg sin licencia válida quedaba invisible. pkgLicense
                // sigue siendo la del primero (la que se muestra).
                val otherLicenseOk = runCatching {
                    findPkgWorkBinFile(pkg).inputStream().use {
                        PkgHeaderParser.validateWorkBin(it, header?.contentId)
                    }
                }.getOrNull()?.isSuccess == true
                if (!otherLicenseOk) {
                    LauncherLog.append(context, "status", ".pkg de juego ${foundTitle.id} sin licencia válida (work.bin ausente o con Content ID distinto); se muestra el primero.")
                }
            }
        }
        if (pkgTitlesFound.size > 1) {
            LauncherLog.append(context, "status", "Dos .pkg de juego guardados (${pkgTitlesFound.joinToString { it.id }}); se muestra ${pkgTitle?.id}.")
        }
        val title = extractedTitle ?: pkgTitle
        // R5-A4-3: con el juego extraído, la licencia que vale es la
        // registrada en el núcleo (ux0/license/<TITLE>/<CONTENT_ID>.rif),
        // no el work.bin junto al .pkg guardado: tras un JUGAR
        // con éxito el .pkg se borra y el chequeo del work.bin
        // quedaba en false aunque el juego corriera. Si hay .pkg
        // guardado, pkgLicense sigue calculándose como antes (arriba).
        val licenseInstalledInCore = extractedTitle?.let { isLicenseInstalledInCore(it.id) } ?: false
        // Rebote de JUGAR (2026-11-13): si el juego está extraído pero
        // su eboot no es un SELF válido, el núcleo no lo arranca y cada
        // JUGAR rebota en silencio. La validación es barata (lee la
        // cabecera, ≤133 bytes) y se guarda en el estado para que
        // JUGAR se bloquee con la explicación y Home lo marque.
        // El motivo exacto queda en el log vía ebootValidation().
        val ebootBroken = extractedTitle?.let {
            ebootValidation(it.id).reason != EbootInvalidReason.OK
        } ?: false
        // Rebote de JUGAR (2026-11-13): si el SFO instalado se lee,
        // la licencia "sin dato" del reporte pasa a ser "no registrada
        // en el núcleo" cuando no hay .rif (dato distinto y honesto).
        val licenseContentIdReadable = extractedTitle?.let { licenseContentId(it.id) != null } ?: false
        val extractedDlcs = mutableListOf<DlcInfo>()
        if (title != null) {
            // R5-A4-9: los DLCs extraídos se leen de AMBOS ux0 (el
            // efectivo y el antiguo), igual que ya se hacía con los
            // .pkg de DLC: en estado de conflicto, un DLC del ux0
            // antiguo era invisible. Sin duplicar el mismo DLC en
            // sus dos formas (la clave de fusión manda, como en
            // mergeDlcLists); el efectivo manda si está en los dos.
            val seenExtractedKeys = mutableSetOf<String>()
            for (ux0 in bothUx0Dirs()) {
                val base = File(ux0, "addcont/${title.id}")
                if (!base.exists()) continue
                // P2-C1: las carpetas de respaldo "<dlc>.bak-<ts>" no
                // son DLCs: se filtran (y se restauran en
                // restoreStaleBackups, no se listan como fantasmas).
                base.listFiles()?.filter { it.isDirectory && ".bak-" !in it.name }?.forEach { dlcDir ->
                    if (!seenExtractedKeys.add(dlcMergeKey(dlcDir.name))) return@forEach
                    val size = dirSizeCache.sizeOf(dlcDir)
                    extractedDlcs.add(DlcInfo(dlcDir.name, title.id, size))
                }
            }
        }
        // DLCs en .pkg (sin extraer): ux0/pkg/dlc/<DLC>.pkg, en ambos
        // ux0. P2-C7: el título de cada DLC se lee de SU header, no se
        // atribuye todo al título efectivo.
        val storedDlcPkgs = mutableListOf<DlcInfo>()
        val seenDlcPkgs = mutableSetOf<String>()
        for (ux0 in bothUx0Dirs()) {
            val dlcDir = File(File(ux0, "pkg"), "dlc")
            if (!dlcDir.exists()) continue
            dlcDir.listFiles()
                ?.filter { it.isFile && it.name.lowercase().endsWith(".pkg") }
                ?.forEach { pkgFile ->
                    if (!seenDlcPkgs.add(pkgFile.nameWithoutExtension)) return@forEach
                    // P1-2: la clave de fusión es el nombre corto del
                    // contenido, leído del header del .pkg (no del
                    // nombre del archivo, que puede ser un hash o estar
                    // truncado). Si el header no se lee, la clave es el
                    // nombre del archivo (mejor esfuerzo).
                    val header = runCatching {
                        pkgFile.inputStream().use { PkgHeaderParser.parse(it) }
                    }.getOrNull()?.getOrNull()
                    val dlcTitle = header?.titleId ?: title
                    // R6-P2-04: un DLC guardado de OTRA región no se
                    // mezcla con el título efectivo: con el juego EU
                    // extraído y un .pkg de DLC US guardado, el DLC US
                    // se listaba como del juego EU. Sin título
                    // efectivo (no hay juego), no se puede filtrar y
                    // se listan todos.
                    if (title != null && dlcTitle != null && dlcTitle != title) return@forEach
                    // Ronda 0.2.70, 7-P2-10: si el header es ilegible
                    // (header == null), NO se puede filtrar por región
                    // y el DLC se listaba como del título efectivo sin
                    // decirlo. Se marca de región desconocida (la UI
                    // lo muestra) en vez de atribuirlo en silencio.
                    val contentKey = header?.contentName?.takeIf { it.isNotBlank() }
                        ?: pkgFile.nameWithoutExtension
                    storedDlcPkgs.add(DlcInfo(pkgFile.nameWithoutExtension, dlcTitle?.id ?: title?.id ?: "", pkgFile.length(), isPkg = true, contentKey = contentKey, regionUnknown = header == null))
                }
        }
        // Un DLC instalado (carpeta) cuyo .pkg sigue guardado se cuenta
        // UNA vez, no dos (mergeDlcLists).
        val dlcs = mergeDlcLists(extractedDlcs, storedDlcPkgs)
        // P1-1 (2026-11-09): "Update instalado" exige contenido REAL
        // en ux0/patch/. Antes bastaba con que existiera el .pkg
        // guardado (findPkgPatchFile != null) y el informe mentía:
        // un update solo guardado, sin instalar, salía como instalado.
        // R5-A4-9: isUpdateInstalled mira el patch/ de AMBOS ux0, así
        // que en estado de conflicto el update del ux0 antiguo ya no
        // es invisible; el .pkg guardado sigue sin contar como
        // instalado (ver isUpdateInstalled).
        // R6-P2-04: por el título EFECTIVO, no por cualquier región:
        // un update US instalado no es "update instalado" del juego EU.
        val updateInstalled = title?.let { isUpdateInstalled(it.id) } ?: false
        // Firmware: criterio ÚNICO con FirmwareResolver. "Instalado"
        // significa instalado EN EL NÚCLEO (vs0 extraído o marcador
        // con versión); un PUP solo guardado NO es instalado (es
        // "guardado, no instalado"). Antes, cualquier marcador no
        // vacío o cualquier PUP con tamaño > 0 se daba por instalado.
        val fwStatus = firmware ?: FirmwareResolver.resolve(vitaRoot)
        val firmwareInstalled = fwStatus.ready
        val firmwareName = when (fwStatus) {
            // P2-C11: null (no "") cuando Ready es solo por vs0 sin
            // marcador: no se conoce la versión y "" mentía como nombre.
            is FirmwareStatus.Ready -> FirmwareResolver.installedVersion(vitaRoot)
            is FirmwareStatus.Saved -> "PSVUPDAT.PUP (guardado, no instalado)"
            FirmwareStatus.Missing -> null
        }
        // Los saves solo existen con el juego extraído: con un .pkg los
        // saves viven dentro de Vita3K, no en el launcher.
        val saves = if (extractedTitle != null) listSaves(extractedTitle.id) else emptyList()
        // Diagnóstico (2026-11-08): la ruta ux0 resuelta puede voltear
        // entre legacy y embebido; si JUGAR dice que el juego no está
        // instalado, esta línea dice dónde se buscó y qué se encontró.
        // R4-P2-5: solo se registra cuando el resumen CAMBIA: antes
        // cada scanStatus (init, cada onResume, al volver del juego)
        // añadía la misma línea y el log rotaba en ráfagas.
        val scanSummary =
            "scanStatus ux0=${ux0Dir.absolutePath} extraído=${extractedTitle?.id} pkg=${pkgTitle?.id} instalado=${extractedTitle != null || pkgTitle != null}"
        // R5-A1-12: el compare-and-set va bajo el candado: hay
        // llamadores de scanStatus fuera del mutex de refreshStatus
        // del ViewModel, así que sin esto dos escaneos concurrentes
        // podían registrar la misma línea dos veces.
        synchronized(scanStatusLogLock) {
            if (LauncherLog.shouldLogScanStatus(lastScanStatusSummary, scanSummary)) {
                lastScanStatusSummary = scanSummary
                LauncherLog.append(context, "status", scanSummary)
            }
        }
        return GameStatus(
            titleId = title,
            extractedTitleId = extractedTitle,
            gameInstalled = extractedTitle != null || pkgTitle != null,
            gameExtracted = extractedTitle != null,
            gameSizeBytes = gameSize,
            pkgTitleId = pkgTitle,
            pkgContentId = pkgContentId,
            pkgSizeBytes = pkgSize,
            pkgLicensePresent = pkgLicense,
            licenseInstalledInCore = licenseInstalledInCore,
            ebootBroken = ebootBroken,
            licenseContentIdReadable = licenseContentIdReadable,
            dlcs = dlcs.sortedBy { it.dlcId },
            updateInstalled = updateInstalled,
            // R6-P1-05: un update .pkg guardado sin instalar (para la
            // UI de Importar: avisa de que hay un update pendiente).
            updateStored = title?.let { findPkgPatchFile(it.id) != null } ?: false,
            firmwareInstalled = firmwareInstalled,
            firmwareName = firmwareName,
            saves = saves,
        )
    }

    fun listSaves(titleId: String): List<SaveInfo> {
        // Ronda 0.2.55 P1-5: la savedata se re-listaba entera
        // (walkTopDown) en cada escaneo. Caché por (título, huella):
        // los saves solo cambian jugando, y al volver del juego el
        // ViewModel invalida (noteReturnedFromGame) y al borrarlos se
        // invalida en deleteSavesOnly.
        // Ronda 0.2.70, 7-P2-6: la huella ya no es solo el mtime del
        // directorio: un cambio de contenido sin tocar el directorio
        // (el núcleo reescribiendo un archivo existente con otro
        // tamaño) no lo mueve y dejaba la caché obsoleta. Ahora es
        // (mtime del directorio, bytes totales): la reescritura con
        // distinto tamaño invalida. El paseo para la huella es solo
        // metadatos (length/lastModified, sin leer contenido) y la
        // caché sigue ahorrando construir + ordenar la lista en cada
        // escaneo. Un cambio que conserve tamaño Y mtime del
        // directorio sigue indetectable por esta vía, pero ese caso
        // ya lo cubre la invalidación al volver del juego.
        val dir = saveDir(titleId)
        if (!dir.exists()) {
            savesCache.remove(titleId)
            return emptyList()
        }
        // Ronda 0.2.70, 7-P2-5 (definición explícita, SIN cambio de
        // conteo): listSaves cuenta TODOS los archivos bajo
        // savedata/<TITLE>/, no "partidas": un archivo auxiliar del
        // núcleo inflaría el número si lo hubiera. La etiqueta de la
        // UI ya dice "archivos" ("Archivos de guardado (N)",
        // "Partidas guardadas: N archivos"), así que no miente; no
        // se filtra por nombre/extensión porque no está verificado
        // en el teléfono qué archivos escribe realmente el juego.
        val stamp = savesCacheStamp(dir)
        val cached = savesCache[titleId]
        if (cached != null && cached.first == stamp) return cached.second
        val result = dir.walkTopDown().filter { it.isFile }.map {
            SaveInfo(
                name = it.relativeTo(dir).path,
                sizeBytes = it.length(),
                lastModifiedMillis = it.lastModified(),
            )
        }.sortedBy { it.name }.toList()
        savesCache[titleId] = stamp to result
        return result
    }

    /** Olvida los saves cacheados de un título (al volver del juego o tras borrarlos). */
    fun invalidateSaves(titleId: String) {
        savesCache.remove(titleId)
    }

    // ------------------------------------------------------------------
    // Importación
    // ------------------------------------------------------------------

    fun importGame(uri: Uri, fileName: String, force: Boolean = false): ImportResult {
        return importArchive(uri, fileName, ImportKind.GAME, force)
    }

    fun importDlc(uri: Uri, fileName: String, force: Boolean = false): ImportResult {
        return importArchive(uri, fileName, ImportKind.DLC, force)
    }

    fun importUpdate(uri: Uri, fileName: String, force: Boolean = false): ImportResult {
        return importArchive(uri, fileName, ImportKind.UPDATE, force)
    }

    /**
     * Extensión del archivo elegido, sin fiarse solo del nombre:
     * 1) extensión del nombre (lo habitual),
     * 2) tipo MIME que devuelve el proveedor SAF,
     * 3) magia de los primeros bytes (PK = zip/.vpk).
     * Sin esto, un archivo válido sin extensión en el nombre se
     * rechazaba con "Formato no aceptado" y el usuario no sabía por qué.
     */
    private fun detectArchiveExtension(uri: Uri, fileName: String): String {
        val byName = ImportFileGuide.extensionOf(fileName)
        if (byName in setOf("zip", "vpk", "pkg", "bin", "pup")) return byName
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        if (mime == "application/zip" || mime == "application/x-zip-compressed") return "zip"
        // Si el nombre no trae extensión conocida, se husmea la magia del
        // archivo (8 bytes): PK = zip/.vpk, SCEUF = .PUP, \x7FPKG = .pkg.
        // Sin esto, el proveedor que devuelve otro MIME dejaba el archivo
        // sin reconocer. El nombre nunca decide: un .pkg por hash sin
        // extensión se reconoce por su magia igual que uno con .pkg.
        val head = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buf = ByteArray(8)
                val read = input.read(buf)
                if (read >= 4) buf else null
            }
        }.getOrNull()
        if (head != null && ImportFileGuide.looksLikeZipMagic(head)) return "zip"
        if (head != null && ImportFileGuide.looksLikePupMagic(head)) return "pup"
        if (head != null && ImportFileGuide.looksLikePkgMagic(head)) return "pkg"
        return byName
    }

    private enum class ImportKind { GAME, DLC, UPDATE }

    /**
     * R6-P2-02: nombres de las entradas de un .zip/.vpk SIN extraerlo:
     * se lee el End Of Central Directory de la cola y el directorio
     * central (dos lecturas pequeñas por FileChannel). Barato frente
     * a extraer ~2 GB. null si no se puede leer el índice (ZIP64,
     * directorio central mayor de 4 MB, EOCD ausente o inconsistente):
     * el llamador deja el comportamiento actual. No bloquea nunca.
     */
    private fun listZipEntryNames(uri: Uri): List<String>? {
        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                    // Se lee con run: el escáner confunde la llamada
                    // FileChannel size con un modificador de Compose.
                    val fileSize = channel.run { size() }
                    if (fileSize < 22) return@runCatching null
                    val tailSize = minOf(fileSize, 65_557L).toInt()
                    val tail = ByteArray(tailSize)
                    val tailBuf = ByteBuffer.wrap(tail)
                    var read = 0
                    while (read < tailSize) {
                        val n = channel.read(tailBuf, fileSize - tailSize + read)
                        if (n < 0) break
                        read += n
                    }
                    if (read < tailSize) return@runCatching null
                    val eocd = findZipEocd(tail) ?: return@runCatching null
                    val (count, cdSize, cdOffset) = eocd
                    if (count <= 0 || cdSize <= 0 || cdSize > 4L * 1024L * 1024L || cdOffset < 0 || cdOffset + cdSize > fileSize) return@runCatching null
                    val cdBytes = ByteArray(cdSize.toInt())
                    val cdBuf = ByteBuffer.wrap(cdBytes)
                    var cdRead = 0
                    while (cdRead < cdBytes.size) {
                        val n = channel.read(cdBuf, cdOffset + cdRead)
                        if (n < 0) break
                        cdRead += n
                    }
                    if (cdRead < cdBytes.size) return@runCatching null
                    parseZipCentralDirectoryNames(cdBytes, count)
                }
            }
        }.getOrNull()
    }

    private fun importArchive(uri: Uri, fileName: String, kind: ImportKind, force: Boolean = false): ImportResult {
        // P0-2: restaurar respaldos .bak-* huérfanos antes de limpiar
        // nada (un .bak-* puede SER el juego si la app murió antes).
        restoreStaleBackups()
        // Restos de importaciones cortadas a medias (hasta 1 GB huérfanos).
        cleanStaleImportLeftovers()
        // "Al cargar no funciona": la extensión se sacaba SOLO del nombre
        // que devuelve el selector. Algunos gestores devuelven un ID de
        // documento sin extensión (o un nombre sin punto) y el .zip/.vpk
        // válido se rechazaba con "Formato no aceptado". Se resuelve por
        // nombre, tipo MIME y, en último caso, magia del archivo (PK = zip).
        val ext = detectArchiveExtension(uri, fileName)
        // El .bin suelto (eboot.bin) NO se rechaza en genérico: se explica
        // en español qué falta y qué archivo hay que elegir.
        if (ext == "bin") {
            val what = when (kind) {
                ImportKind.GAME -> "juego"
                ImportKind.DLC -> "DLC"
                ImportKind.UPDATE -> "update"
            }
            return ImportResult.Rejected(ImportFileGuide.bareBinHelp(what))
        }
        if (ext !in setOf("zip", "vpk", "pkg")) {
            return ImportResult.Rejected(
                "Formato no aceptado: .$ext. Usa .zip, .vpk o .pkg. " +
                    "Un .bin suelto (eboot.bin) no basta: elige el paquete completo o la carpeta del juego.",
            )
        }
        if (ext == "pkg") {
            // EL NOMBRE DEL ARCHIVO NO IMPORTA: los .pkg reales se llaman
            // por hash ("EAMfBniHkRQw...oldLwRkHA.pkg") y nunca traen el
            // Title ID en el nombre. Solo manda el header: se lee el
            // Content ID ANTES de copiar nada, así un .pkg de otro juego
            // o corrupto no deja 1 GB guardado a medias.
            val header = try {
                context.contentResolver.openInputStream(uri)?.use { PkgHeaderParser.parse(it) }
                    ?: return ImportResult.Failed("No se pudo abrir el archivo elegido.")
            } catch (e: Exception) {
                return ImportResult.Failed("No se pudo leer el header del .pkg: ${e.message}")
            }
            val parsed = header.getOrElse { e ->
                return ImportResult.Rejected(e.message ?: "El .pkg no es válido.")
            }
            val title = parsed.titleId
            val pkgKind = when (kind) {
                ImportKind.GAME -> com.danielsnagustin.childoflight.launcher.model.PkgKind.GAME
                ImportKind.DLC -> com.danielsnagustin.childoflight.launcher.model.PkgKind.DLC
                ImportKind.UPDATE -> com.danielsnagustin.childoflight.launcher.model.PkgKind.UPDATE
            }
            // P1-9/P1-4: el tipo de .pkg lo decide su Content ID y su
            // tabla INFO (content_type), no solo el botón pulsado. Un
            // .pkg de juego importado como DLC sobrescribía el juego
            // (y viceversa) sin avisar; un DLC importado como update
            // se guardaba en la ranura equivocada.
            pkgKindMismatch(parsed, pkgKind)?.let { return ImportResult.Rejected(it) }
            // R6-P1-02(a): un .pkg de JUEGO sospechosamente pequeño
            // (<200 MB; el juego real es 1,14 GB) parece un UPDATE,
            // no el juego. Si se importa como juego, el núcleo lo
            // instala por su SFO en patch/ y cada JUGAR lo reinstala
            // sin que el juego arranque (bucle loadGame -3). Se avisa
            // con el mismo flujo de confirmación que los duplicados
            // (duplicateResult: el usuario confirma y se re-ejecuta
            // con force = true), saltado con force. Límite: el SFO
            // del .pkg va cifrado y no se puede distinguir un update
            // de un juego por su contenido: el tamaño es la única
            // pista. El tamaño declarado manda; si el header declara
            // 0, se mide el archivo de origen.
            if (kind == ImportKind.GAME && !force) {
                val declaredSize = parsed.declaredTotalSizeBytes
                val sourceSize = if (declaredSize > 0) {
                    declaredSize
                } else {
                    runCatching {
                        context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
                    }.getOrDefault(-1L)
                }
                if (sourceSize in 1 until SUSPICIOUS_SMALL_GAME_PKG_BYTES) {
                    return duplicateResult(
                        "Este .pkg pesa solo ${DownloadsCleanup.formatBytes(sourceSize)} y el juego completo pesa ~1,14 GB: " +
                            "parece una ACTUALIZACIÓN, no el juego. Si lo importas como juego, el motor lo instalará " +
                            "en otro sitio y cada JUGAR lo reinstalará sin que el juego arranque. " +
                            "¿Quieres importarlo de todos modos como juego? Para importarlo como actualización, " +
                            "cancela y usa Importar → Actualización. (El SFO del .pkg va cifrado: el tamaño es la única pista.)",
                    )
                }
            }
            // Duplicado: si ya está subido, NO se vuelve a copiar en
            // silencio (eso era lo que hacía "engordar" al launcher).
            // Se avisa y el usuario elige Reemplazar o Cancelar.
            if (!force) {
                val duplicateMessage = when (pkgKind) {
                    com.danielsnagustin.childoflight.launcher.model.PkgKind.GAME ->
                        gameDuplicateMessage(title.id, incomingIsPkg = true)
                    com.danielsnagustin.childoflight.launcher.model.PkgKind.DLC -> {
                        val dlcId = sanitizeContentName(parsed.contentName).ifBlank { "dlc-${title.id}" }
                        dlcDuplicateMessage(title.id, dlcId, incomingIsPkg = true)
                    }
                    com.danielsnagustin.childoflight.launcher.model.PkgKind.UPDATE ->
                        updateDuplicateMessage(title.id, incomingIsPkg = true)
                }
                if (duplicateMessage != null) return duplicateResult(duplicateMessage)
            }
            return try {
                cleanLegacyPending()
                val (dest, licenseDest) = pkgStorageFor(title, parsed, pkgKind)
                // R5-A4-1: sin espacio no se empieza la copia: el .pkg
                // guardado más la extracción del núcleo son ~2,28 GB
                // de pico en la misma partición. Si no cabe, se
                // rechaza antes de copiar nada (nunca a mitad).
                val neededBytes = pkgImportNeededBytes(parsed.declaredTotalSizeBytes)
                val destDir = dest.parentFile ?: pkgDir()
                if (!hasFreeSpace(destDir, neededBytes)) {
                    // P0 espacio libre (0.2.55): se pasa el tamaño
                    // declarado para que el mensaje explique el pico
                    // (pkg guardado + extracción) con cifras reales.
                    return ImportResult.Rejected(
                        freeSpaceMessage(neededBytes, freeBytes(destDir), parsed.declaredTotalSizeBytes),
                    )
                }
                // Copia atómica: temporal + rename. Un .pkg son ~1.1 GB:
                // si la copia falla a medias, el .pkg viejo sigue intacto.
                copyUriAtomic(uri, dest)
                // P1-8: un .pkg truncado o a medias no se detectaba: el
                // header se validaba pero jamás se comparaba el tamaño
                // real con el declarado. Upstream lo comprueba al
                // instalar (pkg.cpp: pkg_size < total_size -> false).
                // Si falta tamaño, se borra el destino y se rechaza
                // aquí, no minutos después en JUGAR.
                // R6-P2-08(c): con declared == 0 (header sin ese campo)
                // se compara con el tamaño del archivo de origen, si se
                // conoce; sin él, el truncado sigue sin detectarse al
                // importar y el fallo aparecerá en JUGAR (límite).
                val declared = parsed.declaredTotalSizeBytes
                val sourceSizeForTruncation = if (declared == 0L) {
                    runCatching {
                        context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
                    }.getOrDefault(-1L)
                } else {
                    -1L
                }
                if (pkgCopyIncomplete(dest.length(), declared, sourceSizeForTruncation)) {
                    val actual = dest.length()
                    dest.delete()
                    val expectedText = if (declared > 0) {
                        "su header declara ${DownloadsCleanup.formatBytes(declared)}"
                    } else {
                        "el archivo elegido pesa ${DownloadsCleanup.formatBytes(sourceSizeForTruncation)}"
                    }
                    return ImportResult.Rejected(
                        "El .pkg está incompleto: pesa ${DownloadsCleanup.formatBytes(actual)} pero $expectedText. " +
                            "Vuelve a descargarlo o copiarlo completo.",
                    )
                }
                val sizeBytes = dest.length()
                // Licencia: buscar work.bin junto al .pkg (misma carpeta).
                // Se valida el contenido (512 B: licencia .rif binaria con
                // el Content ID de ESTE .pkg, o texto zRIF «KO»): un .bin
                // que no sea licencia no se acepta como tal.
                // P2-C8: la copia de la licencia va en su propio try: si
                // el .pkg ya se guardó y solo falla la licencia, el
                // resultado es PkgNeedsLicense (no Failed).
                var licenseFound = false
                val sibling = findWorkBinSibling(uri)
                if (sibling != null) {
                    val valid = runCatching {
                        context.contentResolver.openInputStream(sibling)?.use { PkgHeaderParser.validateWorkBin(it, parsed.contentId) }
                    }.getOrNull()?.isSuccess == true
                    if (valid) {
                        licenseFound = runCatching { copyUriAtomic(sibling, licenseDest) }
                            .onFailure { e ->
                                LauncherLog.append(context, "import", "El .pkg se guardó pero no se pudo copiar su licencia (${e.message}); se pedirá el work.bin al usuario.")
                            }.isSuccess
                    } else {
                        LauncherLog.append(context, "import", "El archivo junto al .pkg no es un work.bin válido; se pedirá al usuario.")
                    }
                }
                LauncherLog.append(context, "import", ".pkg ${pkgKind.label} guardado: ${dest.name} (${sizeBytes} bytes, Content ID ${parsed.contentId}, licencia: ${if (licenseFound) "sí" else "falta"})")
                if (licenseFound) {
                    ImportResult.PkgStored(title, dest.absolutePath, parsed.contentId, sizeBytes, true, pkgKind)
                } else {
                    ImportResult.PkgNeedsLicense(title, dest.absolutePath, parsed.contentId, sizeBytes, pkgKind)
                }
            } catch (e: Exception) {
                ImportResult.Failed("No se pudo guardar el .pkg: ${e.message}")
            }
        }
        // .zip / .vpk: extraer UNA sola vez y validar sobre el árbol
        // extraído. Antes se leía el zip DOS veces (listado + extracción)
        // sin mostrar nada: con ~2 GB el usuario veía minutos de silencio
        // y creía que "al cargar no funciona". La carpeta del Title ID se
        // busca en el árbol (ImportTreeSearch): los paquetes EU anidados
        // ("Child of Light/PCSB00598/...") ya no se rechazan con
        // "no contiene eboot.bin".
        // M19: limpiar temporales de importaciones anteriores que fallaron
        // a mitad (extractZip lanzó y el tmp quedó en cacheDir).
        cleanImportTemps()
        val tmp = File(context.cacheDir, "import_${kind.name.lowercase()}_${System.currentTimeMillis()}")
        // R5-A4-1: sin espacio no se empieza la extracción: el temporal
        // en caché más la copia a ux0/app/ conviven en la misma
        // partición interna (el doble del tamaño del zip). Si el
        // tamaño del zip no se puede saber, no se bloquea: se intenta
        // y el fallo, si lo hay, lo da la extracción.
        val zipSizeBytes = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        }.getOrDefault(-1L)
        if (zipSizeBytes > 0) {
            val neededBytes = zipImportNeededBytes(zipSizeBytes)
            if (!hasFreeSpace(ux0Dir, neededBytes)) {
                return ImportResult.Rejected(freeSpaceMessage(neededBytes, freeBytes(ux0Dir)))
            }
        }
        // R6-P2-02: primera pasada barata: listar las entradas del zip
        // por su directorio central (sin extraer) y comprobar duplicado
        // y multi-región ANTES de extraer ~2 GB. Si el índice no se
        // puede leer (ZIP64, directorio central enorme, fallo de
        // lectura) o el título no se puede determinar barato (juego
        // plano: el Title ID vive en el SFO, no en los nombres), se
        // sigue con el comportamiento actual (los chequeos de
        // duplicado post-extracción quedan como red de seguridad).
        if (!force) {
            val zipEntryNames = listZipEntryNames(uri)
            if (zipEntryNames != null) {
                val zipTitles = zipTitleDirNames(zipEntryNames).distinct()
                if (zipTitles.size > 1) {
                    return ImportResult.Rejected(
                        "El archivo trae dos regiones (${zipTitles.joinToString(" y ") { it.id }}). " +
                            "Importa un paquete de una sola región: PCSE00480 (US) o PCSB00598 (EU).",
                    )
                }
                val zipTitle = zipTitles.firstOrNull()
                if (zipTitle != null) {
                    when (kind) {
                        ImportKind.GAME -> {
                            gameDuplicateMessage(zipTitle.id, incomingIsPkg = false)?.let { return duplicateResult(it) }
                        }
                        ImportKind.DLC -> {
                            val zipDlcIds = zipAddcontDlcIds(zipEntryNames)
                            val already = zipDlcIds.filter { isDlcInstalled(zipTitle.id, it) }
                            if (already.isNotEmpty()) {
                                val messages = already.mapNotNull { dlcDuplicateMessage(zipTitle.id, it, incomingIsPkg = false) }
                                return duplicateResult(messages.joinToString("\n"))
                            }
                        }
                        ImportKind.UPDATE -> {
                            updateDuplicateMessage(zipTitle.id, incomingIsPkg = false)?.let { return duplicateResult(it) }
                        }
                    }
                }
            }
        }
        return try {
            tmp.deleteRecursively()
            extractZip(uri, tmp)
            // Carpeta del Title ID (hasta 3 niveles) o juego PLANO
            // (eboot.bin en la raíz con sce_sys/param.sfo que diga el
            // Title ID por contenido). El nombre del archivo/carpeta
            // nunca decide: solo el contenido.
            // P2-C3: si el árbol trae las DOS regiones, no se instala
            // solo la primera en silencio: se rechaza y se pide un
            // paquete de una sola región.
            val titleDirs = ImportTreeSearch.findTitleDirs(tmp)
            val distinctTitles = titleDirs.map { it.first }.distinct()
            if (distinctTitles.size > 1) {
                return ImportResult.Rejected(
                    "El archivo trae dos regiones (${distinctTitles.joinToString(" y ") { it.id }}). " +
                        "Importa un paquete de una sola región: PCSE00480 (US) o PCSB00598 (EU).",
                )
            }
            val found = titleDirs.firstOrNull()
                ?: ImportTreeSearch.findFlatGame(tmp)
            if (found == null) {
                val topNames = ImportTreeSearch.topLevelDirNames(tmp)
                ImportResult.Rejected(
                    "Title ID no aceptado: dentro del archivo no hay una carpeta PCSE00480 (US) ni PCSB00598 (EU), " +
                        "ni un juego plano (eboot.bin en la raíz con sce_sys/param.sfo que diga el Title ID). " +
                        if (topNames.isEmpty()) "El archivo parece vacío."
                        else "Las carpetas principales son: ${topNames.joinToString(", ")}. " +
                            "Este launcher solo acepta Child of Light: PCSE00480 (US) o PCSB00598 (EU).",
                )
            } else {
                val (title, titleDir) = found
                // P2-C2: si la carpeta del Title ID trae
                // sce_sys/param.sfo, su TITLE_ID manda sobre el nombre
                // de la carpeta (una carpeta PCSB00598 con el eboot de
                // otro juego no se instala como Child of Light). El
                // juego plano (titleDir == tmp) ya viene del SFO.
                if (titleDir != tmp) {
                    val sfoCheck = ImportTreeSearch.validateTitleDirSfo(titleDir)
                    if (sfoCheck.isFailure) {
                        return ImportResult.Rejected(sfoCheck.exceptionOrNull()?.message ?: "El contenido del paquete no cuadra con su carpeta.")
                    }
                }
            when (kind) {
                ImportKind.GAME -> {
                    // H4: verificar eboot.bin TAMBIÉN tras extraer: un zip
                    // truncado instalaba una carpeta sin ejecutable y el
                    // juego quedaba "instalado" pero sin arrancar.
                    // Sin distinguir mayúsculas: EBOOT.BIN también vale.
                    // P1-7: validar el CONTENIDO, no solo la existencia:
                    // un archivo llamado eboot.bin con 4 bytes de basura
                    // pasaba y el juego quedaba "instalado" sin arrancar
                    // nunca (mismo agujero que el P0-1, en la entrada).
                    val eboot = ImportTreeSearch.findEboot(titleDir)
                    if (eboot == null) {
                        ImportResult.Failed("Extracción incompleta: no apareció ${title.id}/eboot.bin. El archivo puede estar truncado.")
                    } else if (!isValidEbootFile(eboot)) {
                        ImportResult.Failed("El eboot.bin del archivo no es válido (ni ELF ni SELF, o está truncado). El archivo puede estar corrupto o no ser el juego: revisa que sea el .zip del juego completo.")
                    } else {
                    if (!force) {
                        gameDuplicateMessage(title.id, incomingIsPkg = false)?.let { return duplicateResult(it) }
                    }
                    val dest = appDir(title.id)
                    // BUG (auditoría): antes se borraba el juego viejo ANTES
                    // de copiar el nuevo; si la copia fallaba (disco lleno),
                    // el juego se perdía y el docstring de atomicidad mentía.
                    installDirAtomic(titleDir, dest)
                    val trp = TrophyStoreReader.gameBundledTrp(dest)
                    val detail = if (trp != null) {
                        "Juego instalado. Traía su TROPHY.TRP (${trp.length()} bytes); su contenido se verifica contra la lista en Logros (diagnóstico TRP)."
                    } else {
                        "Juego instalado. No traía TROPHY.TRP visible; se usa la lista bundled de 20 trofeos."
                    }
                    LauncherLog.append(context, "import", "Juego ${title.id} instalado (${dest.length()} bytes aprox.)")
                    ImportResult.Success(title, detail)
                    }
                }
                ImportKind.DLC -> {
                    val srcAddcont = ImportTreeSearch.findAddcontDir(titleDir)
                        ?: return ImportResult.Rejected(
                            "El DLC no trae la ruta addcont/ dentro del archivo. Se esperaba " +
                                "${title.id}/addcont/<DLC_ID>/... dentro del paquete.",
                        )
                    // BUG (auditoría): un dlcId "." o ".." haría que el borrado
                    // y el respaldo apuntaran fuera de addcont/. Se rechaza.
                    val dlcIds = srcAddcont.listFiles()
                        ?.filter { it.isDirectory }
                        ?.map { it.name }
                        .orEmpty()
                    val badId = dlcIds.firstOrNull { it.isBlank() || it == "." || it == ".." || '/' in it || '\\' in it }
                    if (badId != null) {
                        ImportResult.Rejected("ID de DLC no válido: \"$badId\".")
                    } else if (dlcIds.isEmpty()) {
                        ImportResult.Rejected("La carpeta addcont/ está vacía: no había ningún DLC dentro.")
                    } else {
                    if (!force) {
                        val already = dlcIds.filter { isDlcInstalled(title.id, it) }
                        if (already.isNotEmpty()) {
                            // P1-10: el mensaje distingue misma forma
                            // (reemplaza) de forma distinta (conviven).
                            val messages = already.mapNotNull { dlcDuplicateMessage(title.id, it, incomingIsPkg = false) }
                            return duplicateResult(messages.joinToString("\n"))
                        }
                    }
                    val destBase = addcontDir(title.id)
                    destBase.mkdirs()
                    // Se instala cada DLC de la carpeta addcont/ con respaldo
                    // (ver installDlcDir): si la copia falla, el DLC viejo
                    // se restaura en vez de quedar corrupto.
                    val installedDlcs = srcAddcont.listFiles()
                        ?.filter { it.isDirectory }
                        ?.onEach { dlcDir -> installDlcDir(dlcDir, destBase, dlcDir.name) }
                        ?.map { it.name }
                        .orEmpty()
                    LauncherLog.append(context, "import", "DLC ${dlcIds.first()} instalado para ${title.id} (${installedDlcs.size} en el archivo)")
                    // M11: si el archivo traía varios DLCs se instalaban todos
                    // pero solo se reportaba el primero. Ahora se listan.
                    if (installedDlcs.size > 1) {
                        ImportResult.Success(title, "DLCs instalados: ${installedDlcs.joinToString(", ")}.")
                    } else {
                        ImportResult.DlcSuccess(title, dlcIds.first())
                    }
                    }
                }
                ImportKind.UPDATE -> {
                    // M12: un update vacío (carpeta sin archivos) no debe
                    // instalarse ni borrar el update anterior.
                    if (titleDir.listFiles()?.isEmpty() != false) {
                        ImportResult.Rejected("La actualización está vacía: la carpeta ${title.id} no trae archivos.")
                    } else {
                        // P2-27: validar el contenido del update igual que
                        // el del juego (antes solo se comprobaba que la
                        // carpeta no estuviera vacía): el eboot, si lo
                        // trae, debe ser válido (un update sin eboot puede
                        // ser un parche solo de datos y se admite), y el
                        // SFO debe decir CATEGORY "gp" (un juego trae
                        // "gd": instalarlo como update lo pondría en
                        // patch/ y el núcleo lo ignoraría).
                        val updateEboot = ImportTreeSearch.findEboot(titleDir)
                        if (updateEboot != null && !isValidEbootFile(updateEboot)) {
                            ImportResult.Failed("El eboot.bin de la actualización no es válido (ni ELF ni SELF, o está truncado). El archivo puede estar corrupto o no ser una actualización de Child of Light.")
                        } else {
                            val category = ImportTreeSearch.sfoCategory(titleDir)
                            if (category != null && category != "gp") {
                                ImportResult.Rejected("El paquete no es una actualización: su param.sfo dice CATEGORY \"$category\" (una actualización trae \"gp\"). Revisa que sea el .zip de la actualización, no el del juego.")
                            } else {
                                if (!force) {
                                    updateDuplicateMessage(title.id, incomingIsPkg = false)?.let { return duplicateResult(it) }
                                }
                                val dest = patchDir(title.id)
                                // BUG (auditoría): igual que el juego — se borraba el
                                // update viejo antes de copiar el nuevo (pérdida de datos
                                // si la copia fallaba). Instalación atómica con respaldo.
                                installDirAtomic(titleDir, dest)
                                LauncherLog.append(context, "import", "Update instalado para ${title.id}")
                                ImportResult.UpdateSuccess(title)
                            }
                        }
                    }
                }
            }
            }
        } catch (e: Exception) {
            // M19: si extractZip lanzó, el tmp quedó en cacheDir; se limpia.
            cleanImportTemps()
            ImportResult.Failed("Error al importar: ${e.message}")
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** Borra los temporales import_game_/import_dlc_/import_patch_ de cacheDir. */
    private fun cleanImportTemps() {
        runCatching {
            context.cacheDir.listFiles()
                ?.filter { it.name.startsWith("import_") }
                ?.forEach { it.deleteRecursively() }
        }
    }

    /**
     * Instala un directorio de forma atómica: el destino anterior se mueve
     * a un respaldo (rename, barato) y solo se borra cuando la copia nueva
     * termina bien. Si la copia falla (disco lleno, zip corrupto), se borra
     * el destino parcial y se restaura el respaldo: no se pierde lo que ya
     * estaba instalado. Si el respaldo no se puede crear, se aborta sin
     * tocar nada.
     */
    private fun installDirAtomic(src: File, dest: File) {
        val backup = File(dest.parentFile, dest.name + ".bak-" + System.currentTimeMillis())
        val hadDest = dest.exists()
        if (hadDest && !dest.renameTo(backup)) {
            throw IllegalStateException("No se pudo respaldar ${dest.name} antes de instalar.")
        }
        try {
            dest.parentFile?.mkdirs()
            src.copyRecursively(dest)
            if (hadDest) backup.deleteRecursively()
            // R4-P2-7: el tamaño cacheado de este directorio ya no
            // vale tras instalar (la copia puede dejar el lastModified
            // sin cambiar si solo se tocan archivos anidados).
            invalidateDirSizes(dest)
        } catch (e: Exception) {
            dest.deleteRecursively()
            if (hadDest) backup.renameTo(dest)
            throw e
        }
    }

    /**
     * Instala UN DLC con respaldo: el DLC viejo se mueve a un lado y solo
     * se borra cuando la copia nueva termina bien. Si la copia falla, se
     * restaura el DLC viejo. Lo usan el .zip de DLC y la carpeta de DLC.
     */
    private fun installDlcDir(srcDlcDir: File, destBase: File, dlcId: String) {
        val existingDlc = File(destBase, dlcId)
        val dlcBackup = File(destBase, "$dlcId.bak-" + System.currentTimeMillis())
        val hadDlc = existingDlc.exists()
        if (hadDlc && !existingDlc.renameTo(dlcBackup)) {
            throw IllegalStateException("No se pudo respaldar el DLC $dlcId antes de instalar.")
        }
        try {
            srcDlcDir.copyRecursively(existingDlc, overwrite = true)
            if (hadDlc) dlcBackup.deleteRecursively()
            // R4-P2-7: invalida addcont/<title> y sus hijos directos
            // (las carpetas de DLC, que son las entradas cacheadas
            // que lee scanStatus).
            invalidateDirSizes(destBase)
        } catch (e: Exception) {
            existingDlc.deleteRecursively()
            if (hadDlc) dlcBackup.renameTo(existingDlc)
            throw e
        }
    }

    // ------------------------------------------------------------------
    // Importación por carpeta (selector de carpetas, OpenDocumentTree)
    // ------------------------------------------------------------------

    /**
     * Importa el juego desde una CARPETA: debe llamarse PCSE00480 o
     * PCSB00598 y contener eboot.bin. Es la vía para quien tiene el
     * juego descomprimido (no un .zip/.vpk). Copia todo el árbol a
     * ux0/app/<TITLE>/ de forma atómica (respaldo + restauración).
     */
    fun importGameFolder(treeUri: Uri, force: Boolean = false): ImportResult {
        return try {
            val root = DocumentFile.fromTreeUri(context, treeUri)
                ?: return ImportResult.Failed("No se pudo abrir la carpeta elegida.")
            // El nombre de la carpeta NO es obligatorio: si no es el
            // Title ID, se lee de sce_sys/param.sfo (por contenido).
            // P2-C2: si la carpeta SÍ se llama como un Title ID pero su
            // param.sfo dice otro juego u otra región, el contenido
            // manda y se rechaza (no se instala un eboot ajeno como
            // Child of Light).
            val folderTitle = TitleId.fromString(root.name)
            val sfoRawTitle = sfoTitleIdRaw(root)
            if (folderTitle != null && !sfoRawTitle.isNullOrEmpty() &&
                TitleId.fromString(sfoRawTitle) != folderTitle
            ) {
                return ImportResult.Rejected(
                    "La carpeta se llama ${folderTitle.id} pero su param.sfo dice $sfoRawTitle. " +
                        "El contenido manda sobre el nombre de la carpeta.",
                )
            }
            val title = folderTitle ?: titleIdFromFolderSfo(root)
                ?: return ImportResult.Rejected(
                    "La carpeta elegida se llama \"${root.name ?: "?"}\". Debe llamarse " +
                        "PCSE00480 (US) o PCSB00598 (EU) y contener eboot.bin dentro, o traer " +
                        "eboot.bin + sce_sys/param.sfo con el Title ID dentro. " +
                        "Si tu juego está en una carpeta con otro nombre, renómbrala al Title ID.",
                )
            val eboot = findChildIgnoreCase(root, "eboot.bin")
            if (eboot == null || !eboot.isFile) {
                return ImportResult.Rejected(
                    "La carpeta ${title.id} no contiene eboot.bin (o no se pudo leer su contenido). Elige la carpeta del juego " +
                        "que tiene eboot.bin dentro (normalmente junto a la carpeta sce_sys). " +
                        "Si la carpeta está en la tarjeta SD o en Drive y no se lee, muévela al almacenamiento interno o a Descargas e inténtalo de nuevo.",
                )
            }
            // Duplicado: no volver a copiar en silencio.
            if (!force) {
                gameDuplicateMessage(title.id, incomingIsPkg = false)?.let { return duplicateResult(it) }
            }
            // R6-P2-01: sin espacio no se empieza la copia: el staging
            // en caché más la instalación en ux0/app/ conviven en la
            // misma partición (el doble del tamaño del árbol). Si el
            // tamaño no se puede medir, se avisa en el log y NO se
            // bloquea: el fallo, si lo hay, lo da la copia.
            val treeSize = documentTreeSizeBytes(root)
            if (treeSize < 0) {
                LauncherLog.append(context, "import", "No se pudo medir el tamaño de la carpeta ${title.id}; se importa sin chequeo de espacio.")
            } else if (!hasFreeSpace(context.cacheDir, treeSize * 2)) {
                return ImportResult.Rejected(freeSpaceMessage(treeSize * 2, freeBytes(context.cacheDir)))
            }
            restoreStaleBackups()
            cleanStaleImportLeftovers()
            val tmp = File(context.cacheDir, "import_folder_${title.id}")
            tmp.deleteRecursively()
            tmp.mkdirs()
            val staged = File(tmp, title.id)
            staged.mkdirs()
            copyDocumentTree(root, staged)
            // Sin distinguir mayúsculas: el pre-chequeo (findChildIgnoreCase)
            // acepta EBOOT.BIN, pero este post-chequeo era sensible al caso
            // y una carpeta válida se rechazaba con "Copia incompleta".
            val stagedEboot = ImportTreeSearch.findEboot(staged)
            if (stagedEboot == null) {
                tmp.deleteRecursively()
                return ImportResult.Failed("Copia incompleta: no apareció eboot.bin tras copiar la carpeta.")
            }
            // P1-7: validar el CONTENIDO, no solo la existencia: un
            // archivo llamado eboot.bin con 4 bytes de basura pasaba el
            // pre-chequeo y el juego quedaba "instalado" sin arrancar
            // nunca. Se valida la copia (File) que se va a instalar.
            if (!isValidEbootFile(stagedEboot)) {
                tmp.deleteRecursively()
                return ImportResult.Failed("El eboot.bin de la carpeta no es válido (ni ELF ni SELF, o está truncado). La carpeta puede estar corrupta o no ser el juego.")
            }
            installDirAtomic(staged, appDir(title.id))
            tmp.deleteRecursively()
            val detail = "Juego instalado desde la carpeta ${title.id}."
            LauncherLog.append(context, "import", "Juego ${title.id} instalado desde carpeta")
            ImportResult.Success(title, detail)
        } catch (e: Exception) {
            ImportResult.Failed("Error al importar la carpeta: ${e.message}")
        }
    }

    /**
     * Importa DLCs desde una CARPETA: debe llamarse PCSE00480 o PCSB00598
     * y contener addcont/ dentro (estructura <TITLE>/addcont/<DLC>/...).
     * Instala cada DLC con respaldo, como el .zip de DLC.
     */
    fun importDlcFolder(treeUri: Uri, force: Boolean = false): ImportResult {
        return try {
            val root = DocumentFile.fromTreeUri(context, treeUri)
                ?: return ImportResult.Failed("No se pudo abrir la carpeta elegida.")
            // El nombre de la carpeta NO es obligatorio: si no es el
            // Title ID, se lee de sce_sys/param.sfo (igual que
            // importGameFolder). La carpeta del juego trae sce_sys/.
            // P1-6: si la carpeta SÍ se llama como un Title ID pero su
            // param.sfo dice otro juego u otra región, el contenido
            // manda y se rechaza (antes se confiaba en el nombre y una
            // carpeta mal nombrada instalaba los DLCs bajo el Title ID
            // equivocado). Mismo contraste que importGameFolder.
            val folderTitle = TitleId.fromString(root.name)
            val sfoRawTitle = sfoTitleIdRaw(root)
            if (folderTitle != null && !sfoRawTitle.isNullOrEmpty() &&
                TitleId.fromString(sfoRawTitle) != folderTitle
            ) {
                return ImportResult.Rejected(
                    "La carpeta se llama ${folderTitle.id} pero su param.sfo dice $sfoRawTitle. " +
                        "El contenido manda sobre el nombre de la carpeta.",
                )
            }
            val title = folderTitle ?: titleIdFromFolderSfo(root)
                ?: return ImportResult.Rejected(
                    "La carpeta elegida se llama \"${root.name ?: "?"}\". Debe llamarse " +
                        "PCSE00480 (US) o PCSB00598 (EU) y contener addcont/ dentro, o traer " +
                        "eboot.bin + sce_sys/param.sfo con el Title ID dentro.",
                )
            val addcont = findChildIgnoreCase(root, "addcont")
            if (addcont == null || !addcont.isDirectory) {
                return ImportResult.Rejected(
                    "La carpeta ${title.id} no contiene addcont/. Elige la carpeta del juego " +
                        "con la estructura ${title.id}/addcont/<DLC>/ dentro, o importa el .zip del DLC.",
                )
            }
            // R6-P2-01: igual que el juego: el staging en caché más la
            // instalación en ux0/addcont/ conviven en la misma
            // partición (el doble del tamaño; el árbol entero es una
            // cota superior, ya que solo se copia addcont/). Si el
            // tamaño no se puede medir, se avisa en el log y NO se
            // bloquea.
            val treeSize = documentTreeSizeBytes(root)
            if (treeSize < 0) {
                LauncherLog.append(context, "import", "No se pudo medir el tamaño de la carpeta de DLCs ${title.id}; se importa sin chequeo de espacio.")
            } else if (!hasFreeSpace(context.cacheDir, treeSize * 2)) {
                return ImportResult.Rejected(freeSpaceMessage(treeSize * 2, freeBytes(context.cacheDir)))
            }
            val tmp = File(context.cacheDir, "import_dlc_folder_${title.id}")
            tmp.deleteRecursively()
            tmp.mkdirs()
            val stagedAddcont = File(tmp, "addcont")
            stagedAddcont.mkdirs()
            copyDocumentTree(addcont, stagedAddcont)
            val destBase = addcontDir(title.id)
            destBase.mkdirs()
            val stagedNames = stagedAddcont.listFiles()
                ?.filter { it.isDirectory }
                ?.map { it.name }
                .orEmpty()
            // Duplicado: lo mismo que en el .zip de DLC — si ya está
            // subido, se avisa en vez de volver a copiar en silencio.
            // P1-10: el mensaje distingue misma forma de forma distinta.
            if (!force) {
                val already = stagedNames.filter { isDlcInstalled(title.id, it) }
                if (already.isNotEmpty()) {
                    tmp.deleteRecursively()
                    val messages = already.mapNotNull { dlcDuplicateMessage(title.id, it, incomingIsPkg = false) }
                    return duplicateResult(messages.joinToString("\n"))
                }
            }
            restoreStaleBackups()
            cleanStaleImportLeftovers()
            val installed = stagedAddcont.listFiles()
                ?.filter { it.isDirectory }
                ?.map { dlcDir ->
                    installDlcDir(dlcDir, destBase, dlcDir.name)
                    dlcDir.name
                } ?: emptyList()
            tmp.deleteRecursively()
            if (installed.isEmpty()) {
                return ImportResult.Rejected("La carpeta addcont/ está vacía: no había ningún DLC dentro.")
            }
            LauncherLog.append(context, "import", "DLCs instalados desde carpeta para ${title.id}: ${installed.joinToString()}")
            ImportResult.Success(title, "DLCs instalados: ${installed.joinToString(", ")}.")
        } catch (e: Exception) {
            ImportResult.Failed("Error al importar la carpeta de DLCs: ${e.message}")
        }
    }

    /** Busca un hijo por nombre sin distinguir mayúsculas (findFile es sensible al caso en algunos providers). */
    private fun findChildIgnoreCase(dir: DocumentFile, name: String): DocumentFile? {
        return dir.listFiles().firstOrNull { it.name?.equals(name, ignoreCase = true) == true }
    }

    /** Copia un árbol DocumentFile (carpeta SAF) a un directorio local. */
    private fun copyDocumentTree(doc: DocumentFile, destDir: File) {
        doc.listFiles().forEach { child ->
            val name = child.name ?: return@forEach
            if (name == "." || name == "..") return@forEach
            if (child.isDirectory) {
                val sub = File(destDir, name)
                sub.mkdirs()
                copyDocumentTree(child, sub)
            } else if (child.isFile) {
                context.contentResolver.openInputStream(child.uri)?.use { input ->
                    FileOutputStream(File(destDir, name)).use { out -> input.copyTo(out, LARGE_FILE_COPY_BUFFER_BYTES) }
                } ?: throw IllegalStateException("No se pudo leer ${child.name} de la carpeta.")
            }
        }
    }

    /**
     * R6-P2-01: tamaño estimado de un árbol SAF (suma de
     * DocumentFile.length(), recursivo). -1 si algún tamaño es
     * desconocido: el llamador avisa en el log y NO bloquea (mejor
     * intentar que rechazar por no saber medir).
     */
    private fun documentTreeSizeBytes(doc: DocumentFile): Long {
        if (doc.isFile) return doc.length()
        var total = 0L
        for (child in doc.listFiles()) {
            val childSize = documentTreeSizeBytes(child)
            if (childSize < 0) return -1L
            total += childSize
        }
        return total
    }
    fun importFirmware(uri: Uri, fileName: String): ImportResult {
        // Igual que los paquetes: no fiarse solo del nombre del selector.
        val ext = detectArchiveExtension(uri, fileName)
        if (ext != "pup") {
            return ImportResult.Rejected("Firmware no aceptado: se esperaba un archivo .PUP (PSVUPDAT.PUP), no .$ext.")
        }
        return try {
            val dir = firmwareDir()
            if (!dir.exists()) dir.mkdirs()
            val dest = File(dir, "PSVUPDAT.PUP")
            // BUG (auditoría): copiar directo sobre el PUP viejo lo corrompía
            // si la copia fallaba a medias. Primero a temporal, luego rename.
            val tmp = File(dir, "PSVUPDAT.PUP.tmp")
            copyUriToFile(uri, tmp)
            // M18: un PUP de 0 bytes no es firmware válido; antes se guardaba
            // y el marcador installed.json lo daba por instalado.
            if (tmp.length() <= 0) {
                tmp.delete()
                return ImportResult.Rejected("El archivo de firmware está vacío (0 bytes). Elige el PSVUPDAT.PUP completo.")
            }
            // P1-13: un PUP truncado (p. ej. 5 MB con cabecera SCEUF)
            // se aceptaba y cada JUGAR reintentaba instalarlo entero.
            // Un PUP real de Vita 3.74 ronda los 134 MB (ver
            // FirmwareResolver.APPROX_PUP_SIZE_TEXT).
            if (tmp.length() < MIN_PUP_SIZE_BYTES) {
                val pupSize = tmp.length()
                tmp.delete()
                return ImportResult.Rejected(
                    "El archivo de firmware parece incompleto (pesa ${DownloadsCleanup.formatBytes(pupSize)}; " +
                        "un PSVUPDAT.PUP real ronda los ${FirmwareResolver.APPROX_PUP_SIZE_TEXT}). Elige el PUP completo.",
                )
            }
            // El PUP se reconoce por su magia SCEUF, no solo por el
            // nombre: un archivo llamado .PUP con otro contenido no es
            // firmware y no debe guardarse como tal.
            val head = ByteArray(5)
            FileInputStream(tmp).use { it.read(head) }
            if (!ImportFileGuide.looksLikePupMagic(head)) {
                tmp.delete()
                return ImportResult.Rejected("Ese archivo no es un PSVUPDAT.PUP válido (no empieza por SCEUF). Elige el PUP de firmware que tengas guardado en el teléfono.")
            }
            if (!tmp.renameTo(dest)) {
                tmp.delete()
                throw IllegalStateException("No se pudo guardar el firmware.")
            }
            // El marcador installed.json SOLO lo escribe markInstalled tras
            // instalar en el núcleo con éxito: un PUP nuevo invalida el
            // "instalado" anterior hasta que el núcleo lo instale de nuevo.
            runCatching { File(dir, "installed.json").delete() }
            LauncherLog.append(context, "import", "Firmware $fileName guardado (${dest.length()} bytes)")
            ImportResult.FirmwareSuccess(fileName)
        } catch (e: Exception) {
            // P2-C10: si copyUriToFile lanzó, el PSVUPDAT.PUP.tmp
            // (hasta ~134 MB) quedaba huérfano en firmware/.
            runCatching { File(firmwareDir(), "PSVUPDAT.PUP.tmp").delete() }
            ImportResult.Failed("No se pudo guardar el firmware: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // Saves
    // ------------------------------------------------------------------

    fun exportSaves(titleId: String, outUri: Uri): Result<Int> {
        return try {
            val dir = saveDir(titleId)
            if (!dir.exists()) return Result.failure(IllegalStateException("No hay carpeta de saves todavía."))
            var count = 0
            context.contentResolver.openOutputStream(outUri)?.use { out ->
                ZipOutputStream(out).use { zip ->
                    dir.walkTopDown().filter { it.isFile }.forEach { file ->
                        val rel = file.relativeTo(dir).path
                        zip.putNextEntry(ZipEntry(rel))
                        FileInputStream(file).use { it.copyTo(zip, LARGE_FILE_COPY_BUFFER_BYTES) }
                        zip.closeEntry()
                        count++
                    }
                }
            } ?: return Result.failure(IllegalStateException("No se pudo abrir el destino."))
            LauncherLog.append(context, "saves", "Saves exportados: $count archivos")
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun importSaves(titleId: String, uri: Uri): Result<Int> {
        // M14 + R2-P2-31: REEMPLAZAR, no fusionar. Antes se copiaba el
        // zip encima de la viva y los archivos viejos que el zip no
        // traía sobrevivían (p. ej. importar solo la ranura 1 dejaba
        // las ranuras 2-3 viejas: el juego veía una mezcla, no el set
        // importado). Ahora, tras extraer a un temporal (si el zip está
        // corrupto la viva no se toca), se borra el contenido de la
        // viva y se copia el set importado. Es seguro porque el
        // llamador (LauncherViewModel.importSaves) hace antes
        // backupActiveSession() y aborta si el respaldo falla: los
        // saves actuales quedan guardados en la sesión activa.
        val tmp = File(context.cacheDir, "import_saves_$titleId")
        return try {
            tmp.deleteRecursively()
            extractZip(uri, tmp)
            val dir = saveDir(titleId)
            dir.deleteRecursively()
            dir.mkdirs()
            var count = 0
            tmp.walkTopDown().filter { it.isFile }.forEach { file ->
                val rel = file.relativeTo(tmp).path
                val dest = File(dir, rel)
                requireInside(dir, dest)
                dest.parentFile?.mkdirs()
                file.copyTo(dest, overwrite = true)
                count++
            }
            LauncherLog.append(context, "saves", "Saves importados: $count archivos (reemplazaron los saves actuales)")
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            tmp.deleteRecursively()
        }
    }

    /**
     * P2-34: olvida los tamaños cacheados de [dir] y de sus hijos
     * directos (tras un borrado, el tamaño guardado quedaría viejo:
     * scanStatus leería el tamaño de antes de borrar). Se llama en
     * los borrados de juego y DLC, que son los tamaños que scanStatus
     * lee de la caché, y en las instalaciones (R4-P2-7).
     */
    private fun invalidateDirSizes(dir: File) {
        dirSizeCache.invalidate(dir)
        dir.listFiles()?.filter { it.isDirectory }?.forEach { dirSizeCache.invalidate(it) }
    }

    /**
     * R4-P2-7 (2026-11-09): olvida los tamaños cacheados del juego, el
     * update y los DLCs de [titleId] en los dos ux0, al COMPLETAR una
     * instalación o importación. La instalación la hace a veces el
     * núcleo (vía .pkg), así que este es el punto que conocen los
     * llamadores externos (Vita3KLauncher, LauncherViewModel) cuando
     * el núcleo confirma el éxito; las instalaciones que copia el
     * propio repo invalidan además dentro de installDirAtomic /
     * installDlcDir. Sin esto, el primer scanStatus tras instalar
     * leía el tamaño viejo de la caché (la clave es el lastModified
     * del dir y solo cambian archivos anidados). La clave del caché
     * NO cambia: esto es solo invalidación explícita.
     */
    fun invalidateInstalledSizes(titleId: String) {
        installedSizeDirs(titleId, bothUx0Dirs()).forEach { invalidateDirSizes(it) }
    }

    /** Borra juego + update + DLCs + saves del Title ID. Requiere confirmación en la UI. */
    fun deleteGameData(titleId: String): List<String> {
        val deleted = mutableListOf<String>()
        // P2-C9: borrar los datos de AMBAS regiones cuando existan, no
        // solo del título efectivo: con dos regiones instaladas, borrar
        // una dejaba la otra (juego + DLC + saves). P1-5(b): borrar en
        // AMBOS ux0 (efectivo y antiguo): el .pkg legacy quedaba
        // huérfano y el juego "resucitaba" al volver resolveUx0Dir al
        // antiguo. La etiqueta lleva la región cuando se borra más de
        // una, para que el diálogo no mienta sobre qué se borró.
        val targets = TitleId.entries.filter { candidate ->
            candidate.id == titleId || hasAnyGameData(candidate.id)
        }
        val showRegion = targets.size > 1
        for (target in targets) {
            val suffix = if (showRegion) " (${target.id})" else ""
            for (ux0 in bothUx0Dirs()) {
                listOf(
                    File(ux0, "app/${target.id}") to "juego$suffix",
                    File(ux0, "patch/${target.id}") to "update$suffix",
                    File(ux0, "addcont/${target.id}") to "DLCs$suffix",
                    File(ux0, "user/00/savedata/${target.id}") to "saves$suffix",
                ).forEach { (dir, label) ->
                    if (dir.exists()) {
                        dir.deleteRecursively()
                        if (label !in deleted) deleted.add(label)
                    }
                }
                // Ronda 0.2.57, trabajador E: los trofeos REALES viven
                // en trophy/data/<NPCOMMID>/ (ver TrophyDeletePaths):
                // la ruta vieja trophy/<TITLE>/ nunca la usa el núcleo
                // y borrar solo ella dejaba los datos reales (el
                // TROPCONF.SFM y el progreso en TROPUSR.DAT). La ruta
                // vieja se sigue borrando como limpieza extra.
                TrophyDeletePaths.trophyDirsToDelete(ux0, File(ux0, "app/${target.id}"), target.id)
                    .forEach { dir ->
                        dir.deleteRecursively()
                        if ("trofeos$suffix" !in deleted) deleted.add("trofeos$suffix")
                    }
                // R7-P1-02: las licencias instaladas (.rif en
                // license/<TITLE>/) también se borran: si no, un .pkg
                // reimportado se instalaba sin work.bin (findPkgZrif
                // exime al título con licencia en el núcleo) y el
                // "borrado total" no revocaba nada. Solo la carpeta
                // EXACTA de este título, nunca license/ entera: ahí
                // viven también las licencias de la otra región.
                val licenseDir = licenseDirFor(ux0, target.id)
                if (licenseDir.exists()) {
                    val hadRif = licenseHasRif(ux0, target.id)
                    licenseDir.deleteRecursively()
                    if (hadRif && "licencias$suffix" !in deleted) deleted.add("licencias$suffix")
                }
                // .pkg guardados (juego + update + licencia) en este ux0.
                var pkgDeleted = false
                listOf(
                    File(File(ux0, "pkg"), "${target.id}.pkg"),
                    File(File(ux0, "pkg"), "${target.id}.work.bin"),
                    File(File(File(ux0, "pkg"), "patch"), "${target.id}.pkg"),
                    File(File(File(ux0, "pkg"), "patch"), "${target.id}.work.bin"),
                ).forEach { file ->
                    if (file.exists()) {
                        file.delete()
                        pkgDeleted = true
                    }
                }
                if (pkgDeleted && "paquete .pkg$suffix" !in deleted) deleted.add("paquete .pkg$suffix")
                // P2-30: los .bak-* hermanos de este título: si quedan,
                // el próximo escaneo los restaura (su destino ya no
                // existe) y el juego "resucita". Se borran aquí porque
                // este es el borrado total explícito del usuario, no una
                // limpieza silenciosa (P0-2).
                if (deleteTitleBackups(ux0, target.id) && "respaldos .bak$suffix" !in deleted) {
                    deleted.add("respaldos .bak$suffix")
                }
                // P2-34: los tamaños cacheados de estos directorios ya
                // no valen: si no se invalidan, el próximo scanStatus
                // mostraría el tamaño de antes de borrar.
                invalidateDirSizes(File(ux0, "app/${target.id}"))
                invalidateDirSizes(File(ux0, "patch/${target.id}"))
                invalidateDirSizes(File(ux0, "addcont/${target.id}"))
            }
        }
        // DLCs en .pkg: se borra la carpeta entera en ambos ux0.
        var dlcPkgDeleted = false
        for (ux0 in bothUx0Dirs()) {
            val dlcDir = File(File(ux0, "pkg"), "dlc")
            if (dlcDir.exists()) {
                val hadDlcPkgs = dlcDir.listFiles()?.isNotEmpty() == true
                dlcDir.deleteRecursively()
                if (hadDlcPkgs) dlcPkgDeleted = true
            }
        }
        if (dlcPkgDeleted && "DLCs (.pkg)" !in deleted) deleted.add("DLCs (.pkg)")
        LauncherLog.append(context, "delete", "Datos borrados de $titleId: ${deleted.joinToString()}")
        return deleted
    }

    /** ¿Hay algún dato de este Title ID en alguno de los dos ux0? */
    private fun hasAnyGameData(titleId: String): Boolean =
        bothUx0Dirs().any { ux0 ->
            File(ux0, "app/$titleId").exists() ||
                File(ux0, "patch/$titleId").exists() ||
                File(ux0, "addcont/$titleId").exists() ||
                File(ux0, "user/00/savedata/$titleId").exists() ||
                // P2-30: los trofeos y los .bak-* hermanos también son
                // datos del título: si solo quedaran ellos, el título
                // seguiría contando como objetivo del borrado total.
                File(ux0, "user/00/trophy/$titleId").exists() ||
                // Ronda 0.2.57, trabajador E: los datos REALES de
                // trofeos (trophy/data/<NPCOMMID>/) también son datos
                // del título. No se resuelve el NPCOMMID aquí: el
                // launcher es de un solo juego y cualquier subcarpeta
                // bajo trophy/data/ es de este juego (las dos regiones
                // se borran juntas, P2-C9).
                File(ux0, "user/00/trophy/data").listFiles()?.any { it.isDirectory } == true ||
                // R7-P1-02: las licencias instaladas (.rif en
                // license/<TITLE>/) también son datos del título: si
                // solo quedaran ellas, el título seguiría contando
                // como objetivo del borrado total.
                licenseHasRif(ux0, titleId) ||
                File(File(ux0, "pkg"), "$titleId.pkg").isFile ||
                hasTitleBackups(ux0, titleId)
        }

    /**
     * Borra los respaldos .bak-* hermanos de los datos de un título
     * (app/<T>.bak-*, patch/<T>.bak-*, pkg/<T>.pkg.bak-*,
     * pkg/<T>.work.bin.bak-*, pkg/patch/<T>.pkg.bak-*,
     * pkg/patch/<T>.work.bin.bak-*). Los .bak-* DENTRO de addcont/<T>/
     * y de pkg/dlc/ no se listan: mueren con el borrado recursivo de
     * esos directorios. Devuelve true si borró al menos uno.
     */
    private fun deleteTitleBackups(ux0: File, titleId: String): Boolean {
        var deletedAny = false
        val parents = listOf(
            File(ux0, "app") to listOf("$titleId.bak-"),
            File(ux0, "patch") to listOf("$titleId.bak-"),
            File(ux0, "pkg") to listOf("$titleId.pkg.bak-", "$titleId.work.bin.bak-"),
            File(ux0, "pkg/patch") to listOf("$titleId.pkg.bak-", "$titleId.work.bin.bak-"),
        )
        for ((parent, prefixes) in parents) {
            parent.listFiles()?.forEach { f ->
                if (prefixes.any { f.name.startsWith(it) }) {
                    f.deleteRecursively()
                    deletedAny = true
                }
            }
        }
        return deletedAny
    }

    /** ¿Queda algún .bak-* hermano de los datos de este título? */
    private fun hasTitleBackups(ux0: File, titleId: String): Boolean {
        val parents = listOf(
            File(ux0, "app") to listOf("$titleId.bak-"),
            File(ux0, "patch") to listOf("$titleId.bak-"),
            File(ux0, "pkg") to listOf("$titleId.pkg.bak-", "$titleId.work.bin.bak-"),
            File(ux0, "pkg/patch") to listOf("$titleId.pkg.bak-", "$titleId.work.bin.bak-"),
        )
        return parents.any { (parent, prefixes) ->
            parent.listFiles()?.any { f -> prefixes.any { f.name.startsWith(it) } } == true
        }
    }

    /**
     * Borra el .pkg de juego GUARDADO (ux0/pkg/<TITLE>.pkg) cuando el
     * juego ya está extraído y su eboot es VÁLIDO (ELF o SELF): el .pkg
     * es una copia redundante (1,14 GB) y el juego que corre es el
     * extraído, así que la app ocupaba el doble (2,54 GB de datos con
     * un juego de 1,14 GB). NUNCA se borra si el eboot no es válido
     * (sería la única vía de reinstalación) ni si el juego no está
     * extraído (sería la única copia: P0-2). El .pkg puede vivir en
     * el ux0 antiguo (la migración no mueve pkg/), así que se busca
     * en los dos. El work.bin (licencia, 512 B) se conserva: no pesa
     * nada y es la prueba de la licencia instalada. Lo usa la limpieza
     * manual (Ajustes → Limpiar no usados) y el borrado automático
     * tras instalar (el usuario no quiere borrarlo a mano, 2026-11-08).
     */
    /**
     * Borra el .pkg guardado del juego cuando el juego ya está
     * extraído y el eboot es válido (P0-1, 2026-11-09: la validación
     * es estructural — magia + campos SELF + offsets dentro del
     * archivo — y el borrado solo se llama después de que loadGame
     * valide el juego). Cada decisión queda registrada en el log: en la 0.2.10 el .pkg no se borró
     * y no había ni una línea de log que dijera por qué, así que el
     * fallo era invisible. El work.bin se conserva siempre.
     * R6-P0: [requireValidEboot] en false SOLO lo usa el borrado
     * tras loadGame == 0 (tryLaunchEmbedded): el núcleo acaba de
     * cargar el juego, así que el eboot es válido por definición y
     * la puerta de ebootValidation (que daba falso negativo con el
     * SELF real) no debe bloquear el borrado. La limpieza manual
     * sigue con la puerta puesta (true): sin loadGame que lo
     * valide, un eboot no válido significa que el .pkg es la única
     * vía de reinstalación. El .pkg se busca en los dos ux0
     * (findPkgGameFile dual): puede vivir en el antiguo.
     */
    fun deleteStoredGamePkg(titleId: String, requireValidEboot: Boolean = true): Boolean {
        if (titleId.isBlank() || titleId == "." || titleId == ".." || '/' in titleId || '\\' in titleId) {
            LauncherLog.append(context, "delete", ".pkg no borrado: titleId no válido")
            return false
        }
        if (requireValidEboot) {
            val ebootCheck = ebootValidation(titleId)
            if (ebootCheck.reason != EbootInvalidReason.OK) {
                LauncherLog.append(context, "delete", ".pkg no borrado: ${ebootCheck.logLine()}")
                return false
            }
        }
        val pkg = findPkgGameFile(titleId)
        if (pkg == null) {
            LauncherLog.append(context, "delete", ".pkg no borrado: no hay .pkg guardado de $titleId")
            return false
        }
        val size = pkg.length()
        val ok = pkg.delete()
        if (ok) {
            val why = if (requireValidEboot) {
                "el juego ya está extraído"
            } else {
                "loadGame validó el juego (sin puerta de eboot)"
            }
            LauncherLog.append(context, "delete", ".pkg guardado $titleId borrado ($size bytes): $why")
        } else {
            LauncherLog.append(context, "delete", ".pkg NO se pudo borrar: ${pkg.name} ($size bytes)")
        }
        return ok
    }

    /**
     * R6-P1-06: borra el .pkg guardado de un DLC o update DESPUÉS de
     * instalarse con éxito, CONSERVANDO su work.bin (igual que el
     * juego). Guardas: solo se borra si el contenido quedó realmente
     * instalado (UPDATE: contenido real en patch/; DLC: carpeta en
     * addcont/ con la misma clave de contenido que el header del
     * .pkg). Si el header no se lee, la clave es el nombre del
     * archivo (mejor esfuerzo). El juego NO pasa por aquí: su .pkg se
     * borra solo tras loadGame == 0 (deleteStoredGamePkg). Lo llaman
     * installStoredAddonPkgs, installPkgWithEmbeddedCore (Vita3KLauncher)
     * e installImportedPkg (LauncherViewModel) tras installPkgFile con
     * éxito.
     */
    fun deleteStoredAddonPkg(pkgFile: File, kind: com.danielsnagustin.childoflight.launcher.model.PkgKind, titleId: String): Boolean {
        if (kind == com.danielsnagustin.childoflight.launcher.model.PkgKind.GAME) return false
        if (!pkgFile.isFile) return false
        val installed = when (kind) {
            com.danielsnagustin.childoflight.launcher.model.PkgKind.UPDATE -> isUpdateInstalled(titleId)
            com.danielsnagustin.childoflight.launcher.model.PkgKind.DLC -> {
                val contentKey = runCatching {
                    pkgFile.inputStream().use { PkgHeaderParser.parse(it) }
                }.getOrNull()?.getOrNull()?.contentName?.takeIf { it.isNotBlank() }
                    ?.let { dlcMergeKey(it) }
                    ?: dlcMergeKey(pkgFile.nameWithoutExtension)
                bothUx0Dirs().any { ux0 ->
                    File(ux0, "addcont/$titleId").listFiles()
                        ?.any { it.isDirectory && dlcMergeKey(it.name) == contentKey } == true
                }
            }
            com.danielsnagustin.childoflight.launcher.model.PkgKind.GAME -> false
        }
        if (!installed) {
            LauncherLog.append(context, "delete", ".pkg de ${kind.label} no borrado: el contenido no quedó instalado todavía (${pkgFile.name})")
            return false
        }
        val size = pkgFile.length()
        val ok = pkgFile.delete()
        if (ok) {
            LauncherLog.append(context, "delete", ".pkg de ${kind.label} borrado tras instalar ($size bytes): ${pkgFile.name}")
        } else {
            LauncherLog.append(context, "delete", ".pkg de ${kind.label} NO se pudo borrar: ${pkgFile.name} ($size bytes)")
        }
        return ok
    }

    /**
     * R6-P1-02(b): borra el .pkg guardado del juego EN CUALQUIER ESTADO
     * (sin exigir juego extraído ni eboot válido): es el gancho para el
     * borrado de un toque del .pkg "update disfrazado de juego" que deja
     * el lanzamiento en bucle loadGame -3. El work.bin se conserva
     * siempre. Solo lo llama la UI con confirmación del usuario: sin
     * juego extraído, este .pkg es la única copia del juego.
     */
    fun deleteStoredGamePkgAnyState(titleId: String): Boolean {
        if (titleId.isBlank() || titleId == "." || titleId == ".." || '/' in titleId || '\\' in titleId) {
            LauncherLog.append(context, "delete", ".pkg no borrado: titleId no válido")
            return false
        }
        val pkg = findPkgGameFile(titleId)
        if (pkg == null) {
            LauncherLog.append(context, "delete", ".pkg no borrado: no hay .pkg guardado de $titleId")
            return false
        }
        val size = pkg.length()
        val ok = pkg.delete()
        if (ok) {
            LauncherLog.append(context, "delete", ".pkg guardado $titleId borrado por el usuario ($size bytes): el juego no estaba extraído")
        } else {
            LauncherLog.append(context, "delete", ".pkg NO se pudo borrar: ${pkg.name} ($size bytes)")
        }
        return ok
    }

    /** Borra UN DLC instalado (ux0/addcont/<TITLE>/<DLC_ID>/ o ux0/pkg/dlc/<DLC_ID>.pkg). Con confirmación en la UI. */
    /** Resultado real de borrar un DLC: qué formas se borraron y si algo falló. */
    data class DlcDeleteResult(
        val deletedStoredPkg: Boolean,
        val deletedExtracted: Boolean,
        val failed: Boolean,
    ) {
        /** true solo si se borró al menos una forma y nada falló. */
        val ok: Boolean get() = (deletedStoredPkg || deletedExtracted) && !failed
    }

    /** Resultado real de borrar los saves: si había saves y si el borrado falló. */
    data class SavesDeleteResult(
        val deleted: Boolean,
        val failed: Boolean,
    )

    /** contentKey del header de un .pkg de DLC guardado (null si no se lee). */
    private fun pkgDlcContentKey(pkg: File): String? {
        return runCatching {
            pkg.inputStream().use { PkgHeaderParser.parse(it) }
        }.getOrNull()?.getOrNull()?.contentName?.let { dlcMergeKey(it) }
    }

    fun deleteDlc(titleId: String, dlcId: String): DlcDeleteResult {
        // H5: dlcId llega de nombres de carpeta en disco; un valor con "/",
        // ".." o vacío haría que deleteRecursively apuntara fuera de addcont/.
        if (dlcId.isBlank() || dlcId == "." || dlcId == ".." || '/' in dlcId || '\\' in dlcId) {
            return DlcDeleteResult(deletedStoredPkg = false, deletedExtracted = false, failed = true)
        }
        // P1-5: antes solo se borraba UNA forma (el .pkg si existía, y
        // se volvía sin tocar la carpeta extraída) y solo en el ux0
        // efectivo, pero el mensaje decía "DLC borrado". Ahora se
        // borran AMBAS formas en AMBOS ux0 y se devuelve qué se borró
        // de verdad. La coincidencia es por contentKey (P1-2): la
        // carpeta extraída y el .pkg guardado pueden llamarse distinto
        // (el .pkg real de NPS se llama por hash; la carpeta, por el
        // nombre corto del contenido). Si el dlcId ES el nombre del
        // .pkg (hash), su contentKey se añade a las claves buscadas.
        val keys = mutableSetOf(dlcMergeKey(dlcId))
        for (ux0 in ux0Dirs()) {
            File(ux0, "pkg/dlc").listFiles()
                ?.filter { it.isFile && it.nameWithoutExtension == dlcId }
                ?.forEach { pkg -> pkgDlcContentKey(pkg)?.let { keys.add(it) } }
        }
        var deletedStoredPkg = false
        var deletedExtracted = false
        var failed = false
        for (ux0 in ux0Dirs()) {
            // Carpeta extraída: cualquier carpeta cuyo contentKey esté
            // entre las claves buscadas.
            File(ux0, "addcont/$titleId").listFiles()
                ?.filter { it.isDirectory && dlcMergeKey(it.name) in keys }
                ?.forEach { dir ->
                    val size = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    if (dir.deleteRecursively()) {
                        deletedExtracted = true
                        // P2-34: el tamaño cacheado de este DLC ya no vale.
                        dirSizeCache.invalidate(dir)
                        LauncherLog.append(context, "delete", "DLC $dlcId borrado de $titleId ($size bytes, ${dir.path})")
                    } else {
                        failed = true
                        LauncherLog.append(context, "delete", "DLC $dlcId NO se pudo borrar de $titleId (${dir.path})")
                    }
                }
            // .pkg guardado: nombre exacto o cualquier .pkg cuyo header
            // traiga un contentKey buscado.
            File(ux0, "pkg/dlc").listFiles()
                ?.filter { it.isFile && it.extension.equals("pkg", ignoreCase = true) }
                ?.filter { pkg ->
                    pkg.nameWithoutExtension == dlcId || pkgDlcContentKey(pkg)?.let { it in keys } == true
                }
                ?.forEach { pkg ->
                    val size = pkg.length()
                    val ok = pkg.delete()
                    // Ronda 0.2.65 (A-P2-5): la licencia (.work.bin)
                    // solo se borra si el .pkg se borró de verdad.
                    // Antes se borraba siempre: si el .pkg fallaba,
                    // el DLC quedaba instalado SIN licencia (roto)
                    // y ya no había forma de recuperarla.
                    if (ok) {
                        File(pkg.parentFile, "${pkg.nameWithoutExtension}.work.bin").delete()
                        deletedStoredPkg = true
                        LauncherLog.append(context, "delete", "DLC .pkg $dlcId borrado de $titleId ($size bytes, ${pkg.path})")
                    } else {
                        failed = true
                        LauncherLog.append(context, "delete", "DLC .pkg $dlcId NO se pudo borrar de $titleId (${pkg.path})")
                    }
                }
            // R7-P1-03: el .rif instalado de ESTE DLC
            // (license/<TITLE>/<CONTENT_ID>.rif): si sobrevive, el
            // DLC reimportado se instala sin work.bin (la exención de
            // storedPkgsMissingLicense lo exime). Solo el archivo
            // exacto de este DLC (por su clave de contenido, la misma
            // que empareja su carpeta extraída y su .pkg guardado):
            // nunca el .rif del juego ni el de otro DLC, que viven en
            // la misma carpeta.
            dlcLicenseFilesToDelete(ux0, titleId, keys).forEach { rif ->
                if (rif.delete()) {
                    LauncherLog.append(context, "delete", "Licencia .rif del DLC $dlcId borrada de $titleId (${rif.path})")
                } else {
                    failed = true
                    LauncherLog.append(context, "delete", "Licencia .rif del DLC $dlcId NO se pudo borrar de $titleId (${rif.path})")
                }
            }
        }
        return DlcDeleteResult(deletedStoredPkg, deletedExtracted, failed)
    }

    /** Borra solo los saves (ux0/user/00/savedata/<TITLE>/), sin tocar juego/DLCs. */
    fun deleteSavesOnly(titleId: String): SavesDeleteResult {
        // P1-5: igual que deleteDlc, los saves podían quedar en el ux0
        // antiguo tras la migración. Se borran en AMBOS ux0; el
        // resultado distingue "no había saves" de "no se pudo borrar".
        var deleted = false
        var failed = false
        for (ux0 in ux0Dirs()) {
            val dir = File(ux0, "user/00/savedata/$titleId")
            if (dir.exists()) {
                if (dir.deleteRecursively()) {
                    deleted = true
                    LauncherLog.append(context, "delete", "Saves borrados de $titleId (${dir.path})")
                } else {
                    failed = true
                    LauncherLog.append(context, "delete", "Saves NO se pudieron borrar de $titleId (${dir.path})")
                }
            }
        }
        // Ronda 0.2.55 P1-5: los saves borrados ya no están en disco:
        // la caché de listSaves() debe olvidarlos o el próximo
        // escaneo seguiría mostrándolos.
        if (deleted) savesCache.remove(titleId)
        return SavesDeleteResult(deleted, failed)
    }

    // ------------------------------------------------------------------
    // Utilidades ZIP / SAF
    // ------------------------------------------------------------------

    private fun copyUriToFile(uri: Uri, dest: File) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(dest).use { out -> input.copyTo(out, LARGE_FILE_COPY_BUFFER_BYTES) }
        } ?: throw IllegalStateException("No se pudo abrir el archivo elegido.")
    }

    private fun extractZip(uri: Uri, destDir: File) {
        if (!destDir.exists()) destDir.mkdirs()
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                var entries = 0
                var totalBytes = 0L
                while (entry != null) {
                    // M7: tope anti zip-bomb / llenar disco. El juego completo
                    // ronda 2 GB descomprimido; 6 GB y 100.000 entradas sobran.
                    entries++
                    if (entries > 100_000) throw IllegalArgumentException("El archivo trae demasiadas entradas (> 100.000).")
                    if (!entry.isDirectory) {
                        // Zips creados en Windows a veces usan '\' como
                        // separador: en Android '\' es un carácter de nombre
                        // válido, no un separador, y la carpeta del juego
                        // no aparecía tras extraer ("Extracción incompleta").
                        val entryName = entry.name.replace('\\', '/')
                        val dest = File(destDir, entryName)
                        requireInside(destDir, dest)
                        dest.parentFile?.mkdirs()
                        FileOutputStream(dest).use { out ->
                            totalBytes += zip.copyTo(out, LARGE_FILE_COPY_BUFFER_BYTES)
                            if (totalBytes > 6_000_000_000L) throw IllegalArgumentException("El archivo es demasiado grande (> 6 GB).")
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } ?: throw IllegalStateException("No se pudo abrir el archivo elegido.")
    }

    /** Protección contra zip-slip: el destino debe quedar dentro del directorio base. */
    private fun requireInside(base: File, dest: File) {
        val basePath = base.canonicalFile
        val destPath = dest.canonicalFile
        require(destPath.path.startsWith(basePath.path + File.separator)) {
            "Entrada de zip fuera de la carpeta destino: ${dest.name}"
        }
    }
}

/**
 * Ronda 0.2.70, 7-P2-6: huella de la caché de saves: (mtime del
 * directorio, bytes totales de sus archivos). El mtime del
 * directorio solo cambia al crear/borrar/renombrar archivos; una
 * reescritura de un archivo existente con otro tamaño no lo mueve,
 * así que el tamaño total es el que invalida ese caso. Puro (solo
 * File) para poder probarlo en la JVM.
 */
internal fun savesCacheStamp(dir: File): Pair<Long, Long> {
    val files = dir.walkTopDown().filter { it.isFile }.toList()
    return dir.lastModified() to files.sumOf { it.length() }
}

/**
 * Ronda 0.2.70, 7-P2-9: ¿la licencia del JUEGO ya está instalada en
 * el núcleo? Comprobación EXACTA por Content ID: solo vale el
 * `<CONTENT_ID>.rif` propio del juego. Sin Content ID conocido
 * (SFO ilegible o sin CONTENT_ID) devuelve false: aceptar
 * cualquier .rif en license/<TITLE>/ eximiría el juego con el .rif
 * de un DLC, que vive en esa misma carpeta. Top-level internal para
 * testear la lógica pura sin instanciar el repositorio (que pide
 * Context); la envoltura privada isLicenseInstalledInCore solo
 * resuelve el Content ID desde el SFO instalado.
 */
internal fun isGameLicenseInstalledInCore(ux0Dir: File, titleId: String, contentId: String?): Boolean {
    if (contentId.isNullOrBlank()) return false
    return File(ux0Dir, "license/$titleId/$contentId.rif").isFile
}

/**
 * R7-P1-04 resto (0.2.57): ¿el .rif PROPIO de un DLC ya está
 * instalado en el núcleo? El núcleo guarda cada licencia en
 * ux0/license/<TITLE>/<CONTENT_ID>.rif (pin license.cpp:
 * copy_license deriva título y nombre del Content ID del .rif) y
 * find_pkg_zrif la busca por el Content ID del propio .pkg (pin
 * pkg.cpp). El Content ID del DLC es distinto al del juego, así que
 * la comprobación es EXACTA por ese Content ID: un chequeo por
 * "cualquier .rif en license/<TITLE>/" NO vale aquí (7-P2-9: ese
 * fallback ya ni existe para el juego), porque el .rif del juego
 * vive en esa misma carpeta y eximiría todos los DLCs. Solo mira el
 * ux0 efectivo, igual que isLicenseInstalledInCore. Top-level
 * internal para testear la lógica pura sin instanciar el
 * repositorio (que pide Context).
 */
internal fun isDlcLicenseInstalledInCore(ux0Dir: File, titleId: String, contentId: String): Boolean =
    File(ux0Dir, "license/$titleId/$contentId.rif").isFile

/**
 * R7-P1-02 (0.2.58): carpeta de licencias instaladas de un título
 * (ux0/license/<TITLE>/). Solo esa carpeta exacta, nunca license/
 * entera: ahí viven también las licencias de la otra región.
 * Top-level internal para testear la lógica pura sin instanciar el
 * repositorio (que pide Context).
 */
internal fun licenseDirFor(ux0Dir: File, titleId: String): File =
    File(ux0Dir, "license/$titleId")

/**
 * R7-P1-02: ¿hay al menos un .rif instalado para este título? Una
 * carpeta license/<TITLE>/ vacía (sin .rif) no es dato del título:
 * no hace que hasAnyGameData lo cuente. Un título con separadores
 * o ".." nunca vale: no se construye una ruta con él.
 */
internal fun licenseHasRif(ux0Dir: File, titleId: String): Boolean {
    if (titleId.isBlank() || titleId == "." || titleId == ".." || '/' in titleId || '\\' in titleId) return false
    return licenseDirFor(ux0Dir, titleId).listFiles()
        ?.any { it.isFile && it.name.lowercase().endsWith(".rif") } == true
}

/**
 * R7-P1-03: los .rif instalados de UN DLC concreto, en
 * ux0/license/<TITLE>/. El .rif se llama por el Content ID
 * completo (<CONTENT_ID>.rif) y la clave de fusión del DLC es su
 * nombre corto (lo que va tras "_00-"): un .rif es de este DLC
 * cuando su clave de fusión está entre [contentKeys], la misma
 * clave que ya empareja su carpeta extraída y su .pkg guardado.
 * Solo esos archivos, nunca el .rif del juego ni el de otro DLC
 * (que viven en la misma carpeta). Lista vacía si el título no
 * vale o no hay .rif de ese DLC.
 */
internal fun dlcLicenseFilesToDelete(ux0Dir: File, titleId: String, contentKeys: Set<String>): List<File> {
    if (titleId.isBlank() || titleId == "." || titleId == ".." || '/' in titleId || '\\' in titleId) return emptyList()
    if (contentKeys.isEmpty()) return emptyList()
    return licenseDirFor(ux0Dir, titleId).listFiles()
        ?.filter { it.isFile && it.name.lowercase().endsWith(".rif") && GameLibraryRepository.dlcMergeKey(it.nameWithoutExtension) in contentKeys }
        ?: emptyList()
}
