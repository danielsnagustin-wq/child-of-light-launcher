package com.danielsnagustin.childoflight.launcher.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
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
        "update" -> "Elegiste un archivo .bin suelto. Ese archivo solo no instala el update: " +
            "elige el .zip o .vpk del update, o usa \"Importar carpeta del juego\" " +
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
 * - Trofeos: ux0/user/00/trophy/<TITLE_ID>/ (ver TrophyStoreReader)
 * - Firmware: <vita_fs>/firmware/PSVUPDAT.PUP (+ marcador installed.json)
 * - Config:  <vita_fs>/config.yml (ver Vita3kConfigGenerator)
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
 * entrega a la app externa ("Instalar en la app externa", en la
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
class GameLibraryRepository(private val context: Context) {

    // Caché de tamaños de directorio: scanStatus() se llama en init, en
    // cada onResume y al volver del juego, y antes recorría TODO el juego
    // (~1,14 GB) y cada DLC en cada llamada. La caché solo guarda el
    // tamaño; el contenido (qué hay instalado) se sigue leyendo siempre.
    private val dirSizeCache = DirSizeCache()

    companion object {
        /**
         * Prefijo de ImportResult.Rejected cuando el juego/DLC/update ya
         * está subido: el ViewModel lo convierte en un diálogo
         * "Reemplazar / Cancelar" en vez de un error. El texto tras el
         * prefijo es el mensaje para el usuario (sin el prefijo).
         */
        const val DUPLICATE_MARKER = "DUPLICATE::"
    }

    /** ¿El juego ya está subido (extraído en ux0/app/ o como .pkg)? */
    private fun isGameInstalled(titleId: String): Boolean =
        (appDir(titleId).exists() && ImportTreeSearch.findEboot(appDir(titleId)) != null) ||
            pkgGameFile(titleId).isFile

    /** ¿El DLC ya está subido (extraído en addcont/ o como .pkg)? */
    private fun isDlcInstalled(titleId: String, dlcId: String): Boolean =
        File(addcontDir(titleId), dlcId).exists() || pkgDlcFile(dlcId).isFile

    /** ¿El update ya está subido (extraído en patch/ o como .pkg)? */
    private fun isUpdateInstalled(titleId: String): Boolean =
        (patchDir(titleId).exists() && patchDir(titleId).listFiles()?.isNotEmpty() == true) ||
            pkgPatchFile(titleId).isFile

    private fun formatSizeMb(bytes: Long): String =
        if (bytes >= 1_000_000_000L) "%.2f GB".format(bytes / 1_000_000_000.0)
        else "${bytes / 1_000_000} MB"

    /**
     * Mensaje de duplicado para el JUEGO, o null si no está subido.
     * Distingue la misma forma (reemplazar no ocupa más) de otra forma
     * (extraído + .pkg convivirían y ocuparían más).
     */
    private fun gameDuplicateMessage(titleId: String): String? {
        val pkg = pkgGameFile(titleId)
        if (pkg.isFile) {
            return "El juego $titleId ya está subido como .pkg (${formatSizeMb(pkg.length())}). " +
                "Si lo subes de nuevo, se reemplaza el archivo guardado y no ocupa más espacio. ¿Quieres reemplazarlo?"
        }
        if (appDir(titleId).exists() && ImportTreeSearch.findEboot(appDir(titleId)) != null) {
            return "El juego $titleId ya está instalado (extraído). Si lo subes como .pkg tendrás " +
                "las dos copias y ocupará más espacio. ¿Seguro que quieres subirlo de todos modos?"
        }
        return null
    }

    private fun updateDuplicateMessage(titleId: String): String? {
        if (!isUpdateInstalled(titleId)) return null
        return "El update de $titleId ya está subido. Si lo subes de nuevo, se reemplaza " +
            "la copia guardada y no ocupa más espacio. ¿Quieres reemplazarlo?"
    }

    private fun duplicateResult(message: String): ImportResult =
        ImportResult.Rejected("$DUPLICATE_MARKER$message")

    /**
     * Borra restos de importaciones que se cortaron a medias (app cerrada
     * o sin espacio durante la copia): temporales "*.tmp" y respaldos
     * "*.bak-<timestamp>" de .pkg, juego, DLCs y update. Sin esto, cada
     * importación interrumpida dejaba hasta 1 GB huérfano y el launcher
     * "engordaba" sin que el usuario supiera por qué. Solo se borran
     * restos de importación; nunca el juego, los DLCs ni los saves.
     */
    private fun cleanStaleImportLeftovers() {
        runCatching {
            listOf(pkgDir(), pkgDlcDir(), pkgPatchDir()).forEach { dir ->
                dir.listFiles()?.forEach { f ->
                    if (f.name.endsWith(".tmp") && f.isFile) f.delete()
                    else if (".bak-" in f.name) f.deleteRecursively()
                }
            }
            listOf(File(ux0Dir, "app"), File(ux0Dir, "patch")).forEach { base ->
                base.listFiles()?.forEach { f ->
                    if (".bak-" in f.name) f.deleteRecursively()
                }
            }
            File(ux0Dir, "addcont").listFiles()?.forEach { titleDir ->
                titleDir.listFiles()?.forEach { f ->
                    if (".bak-" in f.name) f.deleteRecursively()
                }
            }
        }
    }

    val vitaRoot: File get() = File(context.filesDir, "vita3k")
    /** ux0 efectivo: vita/ux0 si ya se migró al layout del núcleo, si no el ux0 antiguo. */
    val ux0Dir: File get() = EmbeddedCoreLayout.resolveUx0Dir(vitaRoot)
    val configFile: File get() = File(vitaRoot, "config.yml")

    fun appDir(titleId: String): File = File(ux0Dir, "app/$titleId")
    fun addcontDir(titleId: String): File = File(ux0Dir, "addcont/$titleId")
    fun patchDir(titleId: String): File = File(ux0Dir, "patch/$titleId")
    fun saveDir(titleId: String): File = File(ux0Dir, "user/00/savedata/$titleId")
    fun trophyDir(titleId: String): File = File(ux0Dir, "user/00/trophy/$titleId")
    fun firmwareDir(): File = File(vitaRoot, "firmware")
    fun driversDir(): File = File(vitaRoot, "drivers/turnip")

    // --- Almacenamiento de .pkg (ver PkgHeaderParser) ---
    /** Carpeta de .pkg guardados: ux0/pkg/. El nombre del .pkg original no se conserva: se guarda por Title ID. */
    fun pkgDir(): File = File(ux0Dir, "pkg")
    fun pkgGameFile(titleId: String): File = File(pkgDir(), "$titleId.pkg")
    fun pkgWorkBinFile(titleId: String): File = File(pkgDir(), "$titleId.work.bin")
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

    /** .pkg guardados sin su work.bin (licencia). Sin ella Vita3K no puede instalarlos. */
    fun storedPkgsMissingLicense(): List<MissingPkgLicense> {
        val missing = mutableListOf<MissingPkgLicense>()
        for (candidate in TitleId.entries) {
            if (pkgGameFile(candidate.id).isFile && !pkgWorkBinFile(candidate.id).isFile) {
                missing.add(MissingPkgLicense(candidate, com.danielsnagustin.childoflight.launcher.model.PkgKind.GAME, null))
            }
            if (pkgPatchFile(candidate.id).isFile && !pkgPatchWorkBinFile(candidate.id).isFile) {
                missing.add(MissingPkgLicense(candidate, com.danielsnagustin.childoflight.launcher.model.PkgKind.UPDATE, null))
            }
        }
        pkgDlcDir().listFiles()
            ?.filter { it.isFile && it.name.lowercase().endsWith(".pkg") }
            ?.forEach { pkg ->
                val dlcId = pkg.nameWithoutExtension
                if (!pkgDlcWorkBinFile(dlcId).isFile) {
                    val title = runCatching {
                        pkg.inputStream().use { PkgHeaderParser.parse(it) }
                    }.getOrNull()?.getOrNull()?.titleId ?: TitleId.US
                    missing.add(MissingPkgLicense(title, com.danielsnagustin.childoflight.launcher.model.PkgKind.DLC, dlcId))
                }
            }
        return missing
    }

    /** Archivos .pkg + work.bin guardados, para entregarlos a Vita3K. */
    fun storedPkgFilesForDelivery(): List<File> {
        val out = mutableListOf<File>()
        pkgDir().listFiles()?.forEach { file ->
            if (file.isFile && (file.name.lowercase().endsWith(".pkg") || file.name.lowercase().endsWith(".work.bin"))) {
                out.add(file)
            }
        }
        listOf(pkgDlcDir(), pkgPatchDir()).forEach { dir ->
            dir.listFiles()?.forEach { file ->
                if (file.isFile && (file.name.lowercase().endsWith(".pkg") || file.name.lowercase().endsWith(".work.bin"))) {
                    out.add(file)
                }
            }
        }
        return out.sortedBy { it.name }
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
    private fun titleIdFromFolderSfo(root: DocumentFile): TitleId? {
        return try {
            val sceSys = root.listFiles()
                .firstOrNull { it.isDirectory && it.name.equals("sce_sys", ignoreCase = true) } ?: return null
            val sfo = sceSys.listFiles()
                .firstOrNull { it.isFile && it.name.equals("param.sfo", ignoreCase = true) } ?: return null
            if (sfo.length() > ParamSfo.MAX_SFO_BYTES) return null
            val bytes = context.contentResolver.openInputStream(sfo.uri)?.use { it.readBytes() } ?: return null
            TitleId.fromString(ParamSfo.parse(bytes).getOrNull()?.titleId)
        } catch (_: Exception) {
            null
        }
    }

    fun scanStatus(): GameStatus {
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
        var pkgTitle: TitleId? = null
        var pkgContentId: String? = null
        var pkgSize = 0L
        var pkgLicense = false
        for (candidate in TitleId.entries) {
            val pkg = pkgGameFile(candidate.id)
            if (!pkg.isFile) continue
            val header = runCatching {
                pkg.inputStream().use { PkgHeaderParser.parse(it) }
            }.getOrNull()?.getOrNull()
            // Si el header no se lee (pkg corrupto o a medias), se sigue
            // mostrando como .pkg: el usuario puede borrarlo e importar
            // de nuevo. No se inventa el Title ID.
            pkgTitle = header?.titleId ?: candidate
            pkgContentId = header?.contentId
            pkgSize = pkg.length()
            pkgLicense = pkgWorkBinFile(candidate.id).isFile
            break
        }
        val title = extractedTitle ?: pkgTitle
        val dlcs = mutableListOf<DlcInfo>()
        if (title != null) {
            val base = addcontDir(title.id)
            if (base.exists()) {
                base.listFiles()?.filter { it.isDirectory }?.forEach { dlcDir ->
                    val size = dirSizeCache.sizeOf(dlcDir)
                    dlcs.add(DlcInfo(dlcDir.name, title.id, size))
                }
            }
            // DLCs en .pkg (sin extraer): ux0/pkg/dlc/<DLC>.pkg
            val dlcDir = pkgDlcDir()
            if (dlcDir.exists()) {
                dlcDir.listFiles()
                    ?.filter { it.isFile && it.name.lowercase().endsWith(".pkg") }
                    ?.forEach { pkgFile ->
                        dlcs.add(DlcInfo(pkgFile.nameWithoutExtension, title.id, pkgFile.length(), isPkg = true))
                    }
            }
        }
        val updateInstalled = (title != null && patchDir(title.id).exists() &&
            (patchDir(title.id).listFiles()?.isNotEmpty() == true)) ||
            (title != null && pkgPatchFile(title.id).isFile)
        // Firmware: criterio ÚNICO con FirmwareResolver. "Instalado"
        // significa instalado EN EL NÚCLEO (vs0 extraído o marcador
        // con versión); un PUP solo guardado NO es instalado (es
        // "guardado, no instalado"). Antes, cualquier marcador no
        // vacío o cualquier PUP con tamaño > 0 se daba por instalado.
        val fwStatus = FirmwareResolver.resolve(vitaRoot)
        val firmwareInstalled = fwStatus.ready
        val firmwareName = when (val s = fwStatus) {
            is FirmwareStatus.Ready -> FirmwareResolver.installedVersion(vitaRoot) ?: s.source
            is FirmwareStatus.Saved -> "PSVUPDAT.PUP (guardado, no instalado)"
            FirmwareStatus.Missing -> null
        }
        // Los saves solo existen con el juego extraído: con un .pkg los
        // saves viven dentro de Vita3K, no en el launcher.
        val saves = if (extractedTitle != null) listSaves(extractedTitle.id) else emptyList()
        // Diagnóstico (2026-11-08): la ruta ux0 resuelta puede voltear
        // entre legacy y embebido; si JUGAR dice que el juego no está
        // instalado, esta línea dice dónde se buscó y qué se encontró.
        LauncherLog.append(
            context, "status",
            "scanStatus ux0=${ux0Dir.absolutePath} extraído=${extractedTitle?.id} pkg=${pkgTitle?.id} instalado=${extractedTitle != null || pkgTitle != null}",
        )
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
            dlcs = dlcs.sortedBy { it.dlcId },
            updateInstalled = updateInstalled,
            firmwareInstalled = firmwareInstalled,
            firmwareName = firmwareName,
            saves = saves,
        )
    }

    fun listSaves(titleId: String): List<SaveInfo> {
        val dir = saveDir(titleId)
        if (!dir.exists()) return emptyList()
        return dir.walkTopDown().filter { it.isFile }.map {
            SaveInfo(
                name = it.relativeTo(dir).path,
                sizeBytes = it.length(),
                lastModifiedMillis = it.lastModified(),
            )
        }.sortedBy { it.name }.toList()
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

    private fun importArchive(uri: Uri, fileName: String, kind: ImportKind, force: Boolean = false): ImportResult {
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
            // Duplicado: si ya está subido, NO se vuelve a copiar en
            // silencio (eso era lo que hacía "engordar" al launcher).
            // Se avisa y el usuario elige Reemplazar o Cancelar.
            if (!force) {
                val duplicateMessage = when (pkgKind) {
                    com.danielsnagustin.childoflight.launcher.model.PkgKind.GAME ->
                        gameDuplicateMessage(title.id)
                    com.danielsnagustin.childoflight.launcher.model.PkgKind.DLC -> {
                        val dlcId = sanitizeContentName(parsed.contentName).ifBlank { "dlc-${title.id}" }
                        if (isDlcInstalled(title.id, dlcId)) {
                            "El DLC $dlcId ya está subido. Si lo subes de nuevo, se reemplaza " +
                                "la copia guardada y no ocupa más espacio. ¿Quieres reemplazarlo?"
                        } else null
                    }
                    com.danielsnagustin.childoflight.launcher.model.PkgKind.UPDATE ->
                        updateDuplicateMessage(title.id)
                }
                if (duplicateMessage != null) return duplicateResult(duplicateMessage)
            }
            return try {
                cleanLegacyPending()
                val (dest, licenseDest) = pkgStorageFor(title, parsed, pkgKind)
                // Copia atómica: temporal + rename. Un .pkg son ~1.1 GB:
                // si la copia falla a medias, el .pkg viejo sigue intacto.
                copyUriAtomic(uri, dest)
                val sizeBytes = dest.length()
                // Licencia: buscar work.bin junto al .pkg (misma carpeta).
                // Se valida el contenido (512 B: licencia .rif binaria con
                // el Content ID de ESTE .pkg, o texto zRIF «KO»): un .bin
                // que no sea licencia no se acepta como tal.
                var licenseFound = false
                val sibling = findWorkBinSibling(uri)
                if (sibling != null) {
                    val valid = runCatching {
                        context.contentResolver.openInputStream(sibling)?.use { PkgHeaderParser.validateWorkBin(it, parsed.contentId) }
                    }.getOrNull()?.isSuccess == true
                    if (valid) {
                        copyUriAtomic(sibling, licenseDest)
                        licenseFound = true
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
        return try {
            tmp.deleteRecursively()
            extractZip(uri, tmp)
            // Carpeta del Title ID (hasta 3 niveles) o juego PLANO
            // (eboot.bin en la raíz con sce_sys/param.sfo que diga el
            // Title ID por contenido). El nombre del archivo/carpeta
            // nunca decide: solo el contenido.
            val found = ImportTreeSearch.findTitleDir(tmp)
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
            when (kind) {
                ImportKind.GAME -> {
                    // H4: verificar eboot.bin TAMBIÉN tras extraer: un zip
                    // truncado instalaba una carpeta sin ejecutable y el
                    // juego quedaba "instalado" pero sin arrancar.
                    // Sin distinguir mayúsculas: EBOOT.BIN también vale.
                    if (ImportTreeSearch.findEboot(titleDir) == null) {
                        ImportResult.Failed("Extracción incompleta: no apareció ${title.id}/eboot.bin. El archivo puede estar truncado.")
                    } else {
                    if (!force) {
                        gameDuplicateMessage(title.id)?.let { return duplicateResult(it) }
                    }
                    val dest = appDir(title.id)
                    // BUG (auditoría): antes se borraba el juego viejo ANTES
                    // de copiar el nuevo; si la copia fallaba (disco lleno),
                    // el juego se perdía y el docstring de atomicidad mentía.
                    installDirAtomic(titleDir, dest)
                    val trp = TrophyStoreReader.gameBundledTrp(dest)
                    val detail = if (trp != null) {
                        "Juego instalado. Traía su TROPHY.TRP (${trp.length()} bytes); la lista de 27 trofeos queda validada como referencia."
                    } else {
                        "Juego instalado. No traía TROPHY.TRP visible; se usa la lista bundled de 27 trofeos."
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
                            return duplicateResult(
                                "Ya tienes subido(s) este DLC: ${already.joinToString(", ")}. " +
                                    "Si lo subes de nuevo, se reemplaza la copia guardada y no ocupa más espacio. ¿Quieres reemplazarlo?",
                            )
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
                        ImportResult.Rejected("El update está vacío: la carpeta ${title.id} no trae archivos.")
                    } else {
                    if (!force) {
                        updateDuplicateMessage(title.id)?.let { return duplicateResult(it) }
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
            val title = TitleId.fromString(root.name) ?: titleIdFromFolderSfo(root)
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
                gameDuplicateMessage(title.id)?.let { return duplicateResult(it) }
            }
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
            if (ImportTreeSearch.findEboot(staged) == null) {
                tmp.deleteRecursively()
                return ImportResult.Failed("Copia incompleta: no apareció eboot.bin tras copiar la carpeta.")
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
            val title = TitleId.fromString(root.name) ?: titleIdFromFolderSfo(root)
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
            if (!force) {
                val already = stagedNames.filter { isDlcInstalled(title.id, it) }
                if (already.isNotEmpty()) {
                    tmp.deleteRecursively()
                    return duplicateResult(
                        "Ya tienes subido(s) este DLC: ${already.joinToString(", ")}. " +
                            "Si lo subes de nuevo, se reemplaza la copia guardada y no ocupa más espacio. ¿Quieres reemplazarlo?",
                    )
                }
            }
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
        // M14: antes se sobrescribía la viva archivo a archivo desde el zip;
        // un zip corrupto a mitad dejaba saves mezclados. Ahora se extrae
        // primero a un temporal (si el zip está corrupto, la viva no se toca)
        // y solo después se fusiona. El ViewModel además hace snapshot de la
        // sesión activa antes de llamar aquí.
        val tmp = File(context.cacheDir, "import_saves_$titleId")
        return try {
            tmp.deleteRecursively()
            extractZip(uri, tmp)
            val dir = saveDir(titleId)
            if (!dir.exists()) dir.mkdirs()
            var count = 0
            tmp.walkTopDown().filter { it.isFile }.forEach { file ->
                val rel = file.relativeTo(tmp).path
                val dest = File(dir, rel)
                requireInside(dir, dest)
                dest.parentFile?.mkdirs()
                file.copyTo(dest, overwrite = true)
                count++
            }
            LauncherLog.append(context, "saves", "Saves importados: $count archivos")
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** Borra juego + update + DLCs + saves del Title ID. Requiere confirmación en la UI. */
    fun deleteGameData(titleId: String): List<String> {
        val deleted = mutableListOf<String>()
        // M17: antes se devolvía dir.name 4 veces (el Title ID repetido:
        // "PCSE00480, PCSE00480, ..."). Ahora etiquetas legibles.
        listOf(
            appDir(titleId) to "juego",
            patchDir(titleId) to "update",
            addcontDir(titleId) to "DLCs",
            saveDir(titleId) to "saves",
        ).forEach { (dir, label) ->
            if (dir.exists()) {
                dir.deleteRecursively()
                deleted.add(label)
            }
        }
        // .pkg guardados (juego + update + licencia) y DLCs en .pkg.
        var pkgDeleted = false
        listOf(
            pkgGameFile(titleId), pkgWorkBinFile(titleId),
            pkgPatchFile(titleId), pkgPatchWorkBinFile(titleId),
        ).forEach { file ->
            if (file.exists()) {
                file.delete()
                pkgDeleted = true
            }
        }
        if (pkgDlcDir().exists()) {
            val hadDlcPkgs = pkgDlcDir().listFiles()?.isNotEmpty() == true
            pkgDlcDir().deleteRecursively()
            if (hadDlcPkgs) {
                pkgDeleted = true
                deleted.add("DLCs (.pkg)")
            }
        }
        if (pkgDeleted && "paquete .pkg" !in deleted) deleted.add("paquete .pkg")
        LauncherLog.append(context, "delete", "Datos borrados de $titleId: ${deleted.joinToString()}")
        return deleted
    }

    /** Borra UN DLC instalado (ux0/addcont/<TITLE>/<DLC_ID>/ o ux0/pkg/dlc/<DLC_ID>.pkg). Con confirmación en la UI. */
    fun deleteDlc(titleId: String, dlcId: String): Boolean {
        // H5: dlcId llega de nombres de carpeta en disco; un valor con "/",
        // ".." o vacío haría que deleteRecursively apuntara fuera de addcont/.
        if (dlcId.isBlank() || dlcId == "." || dlcId == ".." || '/' in dlcId || '\\' in dlcId) return false
        // DLC en .pkg: se borra el .pkg y su work.bin.
        val pkgDlc = pkgDlcFile(dlcId)
        if (pkgDlc.isFile) {
            val size = pkgDlc.length()
            val ok = pkgDlc.delete()
            pkgDlcWorkBinFile(dlcId).delete()
            if (ok) LauncherLog.append(context, "delete", "DLC .pkg $dlcId borrado de $titleId ($size bytes)")
            return ok
        }
        val dir = File(addcontDir(titleId), dlcId)
        if (!dir.exists()) return false
        val size = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        // M16: el resultado del borrado se devolvía siempre true.
        val ok = dir.deleteRecursively()
        if (ok) LauncherLog.append(context, "delete", "DLC $dlcId borrado de $titleId ($size bytes)")
        return ok
    }

    /** Borra solo los saves (ux0/user/00/savedata/<TITLE>/), sin tocar juego/DLCs. */
    fun deleteSavesOnly(titleId: String): Boolean {
        val dir = saveDir(titleId)
        if (!dir.exists()) return false
        // M16: igual que deleteDlc, el resultado real del borrado.
        val ok = dir.deleteRecursively()
        if (ok) LauncherLog.append(context, "delete", "Saves borrados de $titleId")
        return ok
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
