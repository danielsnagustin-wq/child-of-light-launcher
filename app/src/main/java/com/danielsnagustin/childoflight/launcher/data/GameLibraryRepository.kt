package com.danielsnagustin.childoflight.launcher.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.danielsnagustin.childoflight.launcher.model.DlcInfo
import com.danielsnagustin.childoflight.launcher.model.GameStatus
import com.danielsnagustin.childoflight.launcher.model.ImportResult
import com.danielsnagustin.childoflight.launcher.model.SaveInfo
import com.danielsnagustin.childoflight.launcher.model.TitleId
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

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
 * - La primera carpeta del zip debe ser PCSE00480 o PCSB00598.
 * - Debe contener <TITLE_ID>/eboot.bin (el ejecutable del juego).
 * - Si no, se rechaza con el motivo exacto. No se instala nada a medias:
 *   la extracción va a una carpeta temporal y solo se mueve al destino
 *   si la validación pasa.
 *
 * .pkg: NO se puede inspeccionar sin un parser de paquetes de Vita.
 * Se acepta solo si el nombre del archivo contiene el Title ID y se
 * guarda en import_pending/ con el resultado PkgStored. Nunca se
 * presenta como "juego instalado".
 */
class GameLibraryRepository(private val context: Context) {

    val vitaRoot: File get() = File(context.filesDir, "vita3k")
    val configFile: File get() = File(vitaRoot, "config.yml")

    fun appDir(titleId: String): File = File(vitaRoot, "ux0/app/$titleId")
    fun addcontDir(titleId: String): File = File(vitaRoot, "ux0/addcont/$titleId")
    fun patchDir(titleId: String): File = File(vitaRoot, "ux0/patch/$titleId")
    fun saveDir(titleId: String): File = File(vitaRoot, "ux0/user/00/savedata/$titleId")
    fun trophyDir(titleId: String): File = File(vitaRoot, "ux0/user/00/trophy/$titleId")
    fun firmwareDir(): File = File(vitaRoot, "firmware")
    fun driversDir(): File = File(vitaRoot, "drivers/turnip")

    fun scanStatus(): GameStatus {
        var title: TitleId? = null
        var gameSize = 0L
        // M15: si están instaladas las dos regiones, solo se muestra US
        // (GameStatus lleva un solo titleId). Se registra para no esconderlo.
        var installedCount = 0
        for (candidate in TitleId.entries) {
            val dir = appDir(candidate.id)
            if (dir.exists() && File(dir, "eboot.bin").exists()) {
                installedCount++
                if (title == null) {
                    title = candidate
                    gameSize = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                }
            }
        }
        if (installedCount > 1) {
            LauncherLog.append(context, "status", "Dos regiones instaladas (US y EU); se muestra ${title?.id}. Los DLCs/saves de la otra región no se ven.")
        }
        val dlcs = mutableListOf<DlcInfo>()
        if (title != null) {
            val base = addcontDir(title.id)
            if (base.exists()) {
                base.listFiles()?.filter { it.isDirectory }?.forEach { dlcDir ->
                    val size = dlcDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    dlcs.add(DlcInfo(dlcDir.name, title.id, size))
                }
            }
        }
        val updateInstalled = title != null && patchDir(title.id).exists() &&
            (patchDir(title.id).listFiles()?.isNotEmpty() == true)
        val firmwareMarker = File(firmwareDir(), "installed.json")
        // BUG (auditoría): readText() puede lanzar IOException (el archivo
        // puede desaparecer entre exists() y la lectura) y scanStatus() se
        // llama sin try/catch desde el ViewModel. Además, un installed.json
        // vacío no debe contar como firmware instalado.
        val markerText = runCatching {
            firmwareMarker.takeIf { it.exists() }?.readText()?.trim()
        }.getOrNull()
        // M20: unificar el criterio con FirmwareResolver: el PUP debe tener
        // tamaño > 0 (un PUP de 0 bytes no es firmware instalado).
        val pupFile = File(firmwareDir(), "PSVUPDAT.PUP")
        val firmwareInstalled = !markerText.isNullOrBlank() ||
            (pupFile.exists() && pupFile.length() > 0)
        val firmwareName = if (firmwareInstalled) {
            markerText?.takeIf { it.isNotBlank() } ?: "PSVUPDAT.PUP"
        } else null
        val saves = if (title != null) listSaves(title.id) else emptyList()
        return GameStatus(
            titleId = title,
            gameInstalled = title != null,
            gameSizeBytes = gameSize,
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

    fun importGame(uri: Uri, fileName: String): ImportResult {
        return importArchive(uri, fileName, ImportKind.GAME)
    }

    fun importDlc(uri: Uri, fileName: String): ImportResult {
        return importArchive(uri, fileName, ImportKind.DLC)
    }

    fun importUpdate(uri: Uri, fileName: String): ImportResult {
        return importArchive(uri, fileName, ImportKind.UPDATE)
    }

    private enum class ImportKind { GAME, DLC, UPDATE }

    private fun importArchive(uri: Uri, fileName: String, kind: ImportKind): ImportResult {
        val ext = fileName.substringAfterLast('.', "").lowercase()
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
            // Sin parser de PKG no se puede validar el contenido.
            // M13: el nombre de SAF se toma como basename (nunca con rutas) y
            // si contiene los dos Title ID se rechaza (antes ganaba US por
            // orden del enum, sin avisar).
            val safeName = File(fileName).name
            val matched = TitleId.entries.filter { safeName.uppercase().contains(it.id) }
            if (matched.size > 1) {
                return ImportResult.Rejected(
                    "El .pkg contiene dos Title ID en el nombre ($safeName). Renómbralo para que traiga solo PCSE00480 o PCSB00598.",
                )
            }
            val title = matched.firstOrNull()
                ?: return ImportResult.Rejected(
                    "El .pkg no trae el Title ID en el nombre del archivo " +
                        "(se esperaba PCSE00480 o PCSB00598). Sin eso no se puede saber a qué juego pertenece.",
                )
            return try {
                val pending = File(vitaRoot, "import_pending")
                if (!pending.exists()) pending.mkdirs()
                val dest = File(pending, safeName)
                // Misma atomicidad que el firmware: temporal + rename.
                val tmp = File(pending, "$safeName.tmp")
                copyUriToFile(uri, tmp)
                if (!tmp.renameTo(dest)) {
                    tmp.delete()
                    throw IllegalStateException("No se pudo guardar el .pkg.")
                }
                LauncherLog.append(context, "import", ".pkg guardado en import_pending/${dest.name} (${dest.length()} bytes)")
                ImportResult.PkgStored(title, dest.absolutePath)
            } catch (e: Exception) {
                ImportResult.Failed("No se pudo guardar el .pkg: ${e.message}")
            }
        }
        // .zip / .vpk: inspeccionar antes de instalar nada.
        // M19: limpiar temporales de importaciones anteriores que fallaron
        // a mitad (extractZip lanzó y el tmp quedó en cacheDir).
        cleanImportTemps()
        return try {
            val entries = listZipEntries(uri)
            if (entries.isEmpty()) return ImportResult.Rejected("El archivo está vacío o no es un zip válido.")
            // BUG (auditoría) + M10: la primera entrada con '/' podía ser
            // "__MACOSX/..." o "readme.txt" y el juego se rechazaba aunque
            // fuera válido. Se busca un segmento que sea un Title ID válido
            // en TODAS las entradas, no solo la primera carpeta.
            val firstFolder = entries.firstOrNull { '/' in it }?.substringBefore('/')?.uppercase()
            val topFolder = entries.asSequence()
                .flatMap { it.split('/') }
                .firstOrNull { TitleId.fromString(it) != null }
                ?.uppercase()
                ?: return ImportResult.Rejected(
                    "Title ID no aceptado: la carpeta principal del archivo es \"${firstFolder ?: "-"}\". " +
                        "Este launcher solo acepta Child of Light: PCSE00480 (US) o PCSB00598 (EU).",
                )
            val title = TitleId.fromString(topFolder)
                ?: return ImportResult.Rejected(
                    "Title ID no aceptado: la carpeta principal del archivo es \"$topFolder\". " +
                        "Este launcher solo acepta Child of Light: PCSE00480 (US) o PCSB00598 (EU).",
                )
            when (kind) {
                ImportKind.GAME -> {
                    val hasEboot = entries.any { it.equals("${title.id}/eboot.bin", ignoreCase = true) }
                    if (!hasEboot) {
                        return ImportResult.Rejected(
                            "El archivo no contiene ${title.id}/eboot.bin. No parece el juego completo.",
                        )
                    }
                    val tmp = File(context.cacheDir, "import_game_${title.id}")
                    tmp.deleteRecursively()
                    extractZip(uri, tmp)
                    val src = File(tmp, title.id)
                    if (!src.exists()) {
                        tmp.deleteRecursively()
                        return ImportResult.Failed("Extracción incompleta: no apareció la carpeta ${title.id}.")
                    }
                    // H4: verificar eboot.bin TAMBIÉN tras extraer: un zip
                    // truncado instalaba una carpeta sin ejecutable y el
                    // juego quedaba "instalado" pero sin arrancar.
                    if (!File(src, "eboot.bin").exists()) {
                        tmp.deleteRecursively()
                        return ImportResult.Failed("Extracción incompleta: no apareció ${title.id}/eboot.bin. El archivo puede estar truncado.")
                    }
                    val dest = appDir(title.id)
                    // BUG (auditoría): antes se borraba el juego viejo ANTES
                    // de copiar el nuevo; si la copia fallaba (disco lleno),
                    // el juego se perdía y el docstring de atomicidad mentía.
                    installDirAtomic(src, dest)
                    tmp.deleteRecursively()
                    val trp = TrophyStoreReader.gameBundledTrp(dest)
                    val detail = if (trp != null) {
                        "Juego instalado. Traía su TROPHY.TRP (${trp.length()} bytes); la lista de 27 trofeos queda validada como referencia."
                    } else {
                        "Juego instalado. No traía TROPHY.TRP visible; se usa la lista bundled de 27 trofeos."
                    }
                    LauncherLog.append(context, "import", "Juego ${title.id} instalado (${dest.length()} bytes aprox.)")
                    ImportResult.Success(title, detail)
                }
                ImportKind.DLC -> {
                    val addcontEntry = entries.firstOrNull { "/addcont/" in it.lowercase() }
                        ?: return ImportResult.Rejected(
                            "El DLC no trae la ruta addcont/ dentro del zip. Se esperaba " +
                                "<TITLE_ID>/addcont/<DLC_ID>/... dentro del archivo.",
                        )
                    val parts = addcontEntry.split('/')
                    val dlcIndex = parts.indexOfFirst { it.equals("addcont", ignoreCase = true) }
                    val dlcId = parts.getOrNull(dlcIndex + 1)
                        ?: return ImportResult.Rejected("No se pudo leer el ID del DLC en la ruta addcont/.")
                    // BUG (auditoría): un dlcId "." o ".." haría que el borrado
                    // y el respaldo apuntaran fuera de addcont/. Se rechaza.
                    if (dlcId.isBlank() || dlcId == "." || dlcId == ".." ||
                        '/' in dlcId || '\\' in dlcId
                    ) {
                        return ImportResult.Rejected("ID de DLC no válido: \"$dlcId\".")
                    }
                    val tmp = File(context.cacheDir, "import_dlc_${title.id}")
                    tmp.deleteRecursively()
                    extractZip(uri, tmp)
                    val srcAddcont = File(tmp, "${title.id}/addcont")
                    if (!srcAddcont.exists()) {
                        tmp.deleteRecursively()
                        return ImportResult.Failed("Extracción incompleta: no apareció ${title.id}/addcont.")
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
                    tmp.deleteRecursively()
                    LauncherLog.append(context, "import", "DLC $dlcId instalado para ${title.id} (${installedDlcs.size} en el zip)")
                    // M11: si el zip traía varios DLCs se instalaban todos
                    // pero solo se reportaba el primero. Ahora se listan.
                    return if (installedDlcs.size > 1) {
                        ImportResult.Success(title, "DLCs instalados: ${installedDlcs.joinToString(", ")}.")
                    } else {
                        ImportResult.DlcSuccess(title, dlcId)
                    }
                }
                ImportKind.UPDATE -> {
                    val tmp = File(context.cacheDir, "import_patch_${title.id}")
                    tmp.deleteRecursively()
                    extractZip(uri, tmp)
                    val src = File(tmp, title.id)
                    if (!src.exists()) {
                        tmp.deleteRecursively()
                        return ImportResult.Rejected("El update no trae la carpeta ${title.id} como carpeta principal.")
                    }
                    // M12: un update vacío (carpeta sin archivos) no debe
                    // instalarse ni borrar el update anterior.
                    if (src.listFiles()?.isEmpty() != false) {
                        tmp.deleteRecursively()
                        return ImportResult.Rejected("El update está vacío: la carpeta ${title.id} no trae archivos.")
                    }
                    val dest = patchDir(title.id)
                    // BUG (auditoría): igual que el juego — se borraba el
                    // update viejo antes de copiar el nuevo (pérdida de datos
                    // si la copia fallaba). Instalación atómica con respaldo.
                    installDirAtomic(src, dest)
                    tmp.deleteRecursively()
                    LauncherLog.append(context, "import", "Update instalado para ${title.id}")
                    ImportResult.UpdateSuccess(title)
                }
            }
        } catch (e: Exception) {
            // M19: si extractZip lanzó, el tmp quedó en cacheDir; se limpia.
            cleanImportTemps()
            ImportResult.Failed("Error al importar: ${e.message}")
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
    fun importGameFolder(treeUri: Uri): ImportResult {
        return try {
            val root = DocumentFile.fromTreeUri(context, treeUri)
                ?: return ImportResult.Failed("No se pudo abrir la carpeta elegida.")
            val title = TitleId.fromString(root.name)
                ?: return ImportResult.Rejected(
                    "La carpeta elegida se llama \"${root.name ?: "?"}\". Debe llamarse " +
                        "PCSE00480 (US) o PCSB00598 (EU) y contener eboot.bin dentro. " +
                        "Si tu juego está en una carpeta con otro nombre, renómbrala al Title ID.",
                )
            val eboot = findChildIgnoreCase(root, "eboot.bin")
            if (eboot == null || !eboot.isFile) {
                return ImportResult.Rejected(
                    "La carpeta ${title.id} no contiene eboot.bin. Elige la carpeta del juego " +
                        "que tiene eboot.bin dentro (normalmente junto a la carpeta sce_sys).",
                )
            }
            val tmp = File(context.cacheDir, "import_folder_${title.id}")
            tmp.deleteRecursively()
            tmp.mkdirs()
            val staged = File(tmp, title.id)
            staged.mkdirs()
            copyDocumentTree(root, staged)
            if (!File(staged, "eboot.bin").exists()) {
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
    fun importDlcFolder(treeUri: Uri): ImportResult {
        return try {
            val root = DocumentFile.fromTreeUri(context, treeUri)
                ?: return ImportResult.Failed("No se pudo abrir la carpeta elegida.")
            val title = TitleId.fromString(root.name)
                ?: return ImportResult.Rejected(
                    "La carpeta elegida se llama \"${root.name ?: "?"}\". Debe llamarse " +
                        "PCSE00480 (US) o PCSB00598 (EU) y contener addcont/ dentro.",
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
                    FileOutputStream(File(destDir, name)).use { out -> input.copyTo(out) }
                } ?: throw IllegalStateException("No se pudo leer ${child.name} de la carpeta.")
            }
        }
    }
    fun importFirmware(uri: Uri, fileName: String): ImportResult {
        val ext = fileName.substringAfterLast('.', "").lowercase()
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
            if (!tmp.renameTo(dest)) {
                tmp.delete()
                throw IllegalStateException("No se pudo guardar el firmware.")
            }
            File(dir, "installed.json").writeText(fileName)
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
                        FileInputStream(file).use { it.copyTo(zip) }
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
        LauncherLog.append(context, "delete", "Datos borrados de $titleId: ${deleted.joinToString()}")
        return deleted
    }

    /** Borra UN DLC instalado (ux0/addcont/<TITLE>/<DLC_ID>/). Con confirmación en la UI. */
    fun deleteDlc(titleId: String, dlcId: String): Boolean {
        // H5: dlcId llega de nombres de carpeta en disco; un valor con "/",
        // ".." o vacío haría que deleteRecursively apuntara fuera de addcont/.
        if (dlcId.isBlank() || dlcId == "." || dlcId == ".." || '/' in dlcId || '\\' in dlcId) return false
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
            FileOutputStream(dest).use { out -> input.copyTo(out) }
        } ?: throw IllegalStateException("No se pudo abrir el archivo elegido.")
    }

    private fun listZipEntries(uri: Uri): List<String> {
        val out = mutableListOf<String>()
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) out.add(entry.name)
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        return out
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
                        val dest = File(destDir, entry.name)
                        requireInside(destDir, dest)
                        dest.parentFile?.mkdirs()
                        FileOutputStream(dest).use { out ->
                            totalBytes += zip.copyTo(out)
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
