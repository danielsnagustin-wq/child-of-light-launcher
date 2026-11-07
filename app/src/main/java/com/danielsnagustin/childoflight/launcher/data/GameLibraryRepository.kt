package com.danielsnagustin.childoflight.launcher.data

import android.content.Context
import android.net.Uri
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
        for (candidate in TitleId.entries) {
            val dir = appDir(candidate.id)
            if (dir.exists() && File(dir, "eboot.bin").exists()) {
                title = candidate
                gameSize = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                break
            }
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
        val firmwareInstalled = firmwareMarker.exists() || File(firmwareDir(), "PSVUPDAT.PUP").exists()
        val firmwareName = if (firmwareInstalled) {
            firmwareMarker.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotBlank() }
                ?: "PSVUPDAT.PUP"
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
        }.sortedBy { it.name }
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
        if (ext !in setOf("zip", "vpk", "pkg")) {
            return ImportResult.Rejected("Formato no aceptado: .$ext. Usa .zip, .vpk o .pkg.")
        }
        if (ext == "pkg") {
            // Sin parser de PKG no se puede validar el contenido.
            val title = TitleId.entries.firstOrNull { fileName.uppercase().contains(it.id) }
                ?: return ImportResult.Rejected(
                    "El .pkg no trae el Title ID en el nombre del archivo " +
                        "(se esperaba PCSE00480 o PCSB00598). Sin eso no se puede saber a qué juego pertenece.",
                )
            return try {
                val pending = File(vitaRoot, "import_pending")
                if (!pending.exists()) pending.mkdirs()
                val dest = File(pending, fileName)
                copyUriToFile(uri, dest)
                LauncherLog.append(context, "import", ".pkg guardado en import_pending/${dest.name} (${dest.length()} bytes)")
                ImportResult.PkgStored(title, dest.absolutePath)
            } catch (e: Exception) {
                ImportResult.Failed("No se pudo guardar el .pkg: ${e.message}")
            }
        }
        // .zip / .vpk: inspeccionar antes de instalar nada.
        return try {
            val entries = listZipEntries(uri)
            if (entries.isEmpty()) return ImportResult.Rejected("El archivo está vacío o no es un zip válido.")
            val topFolder = entries.first().substringBefore('/').uppercase()
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
                    val dest = appDir(title.id)
                    dest.deleteRecursively()
                    dest.parentFile?.mkdirs()
                    src.copyRecursively(dest)
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
                    srcAddcont.copyRecursively(destBase, overwrite = true)
                    tmp.deleteRecursively()
                    LauncherLog.append(context, "import", "DLC $dlcId instalado para ${title.id}")
                    ImportResult.DlcSuccess(title, dlcId)
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
                    val dest = patchDir(title.id)
                    dest.deleteRecursively()
                    dest.parentFile?.mkdirs()
                    src.copyRecursively(dest)
                    tmp.deleteRecursively()
                    LauncherLog.append(context, "import", "Update instalado para ${title.id}")
                    ImportResult.UpdateSuccess(title)
                }
            }
        } catch (e: Exception) {
            ImportResult.Failed("Error al importar: ${e.message}")
        }
    }

    /** Importa el firmware .PUP (PSVUPDAT.PUP). Solo se guarda + marcador; la extracción real a vs0/ la hace Vita3K (Etapa 2). */
    fun importFirmware(uri: Uri, fileName: String): ImportResult {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext != "pup") {
            return ImportResult.Rejected("Firmware no aceptado: se esperaba un archivo .PUP (PSVUPDAT.PUP), no .$ext.")
        }
        return try {
            val dir = firmwareDir()
            if (!dir.exists()) dir.mkdirs()
            val dest = File(dir, "PSVUPDAT.PUP")
            copyUriToFile(uri, dest)
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
        return try {
            val dir = saveDir(titleId)
            if (!dir.exists()) dir.mkdirs()
            var count = 0
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val dest = File(dir, entry.name)
                            requireInside(dir, dest)
                            dest.parentFile?.mkdirs()
                            FileOutputStream(dest).use { out -> zip.copyTo(out) }
                            count++
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: return Result.failure(IllegalStateException("No se pudo abrir el archivo."))
            LauncherLog.append(context, "saves", "Saves importados: $count archivos")
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Borra juego + update + DLCs + saves del Title ID. Requiere confirmación en la UI. */
    fun deleteGameData(titleId: String): List<String> {
        val deleted = mutableListOf<String>()
        listOf(appDir(titleId), patchDir(titleId), addcontDir(titleId), saveDir(titleId)).forEach { dir ->
            if (dir.exists()) {
                dir.deleteRecursively()
                deleted.add(dir.name)
            }
        }
        LauncherLog.append(context, "delete", "Datos borrados de $titleId: ${deleted.joinToString()}")
        return deleted
    }

    /** Borra UN DLC instalado (ux0/addcont/<TITLE>/<DLC_ID>/). Con confirmación en la UI. */
    fun deleteDlc(titleId: String, dlcId: String): Boolean {
        val dir = File(addcontDir(titleId), dlcId)
        if (!dir.exists()) return false
        val size = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        dir.deleteRecursively()
        LauncherLog.append(context, "delete", "DLC $dlcId borrado de $titleId ($size bytes)")
        return true
    }

    /** Borra solo los saves (ux0/user/00/savedata/<TITLE>/), sin tocar juego/DLCs. */
    fun deleteSavesOnly(titleId: String): Boolean {
        val dir = saveDir(titleId)
        if (!dir.exists()) return false
        dir.deleteRecursively()
        LauncherLog.append(context, "delete", "Saves borrados de $titleId")
        return true
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
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val dest = File(destDir, entry.name)
                        requireInside(destDir, dest)
                        dest.parentFile?.mkdirs()
                        FileOutputStream(dest).use { out -> zip.copyTo(out) }
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
