package com.danielsnagustin.childoflight.launcher.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * Driver GPU personalizado Turnip (Mesa, open-source, solo Adreno).
 *
 * Turnip es el driver Mesa para GPUs Qualcomm Adreno. Se distribuye como
 * zip y Vita3K-Android lo carga solo para la app cuando se elige un
 * driver personalizado. En Mali NO aplica: Turnip no funciona en Mali.
 *
 * Este repositorio solo gestiona el ARCHIVO: copiar el zip elegido a
 * <vita_fs>/drivers/turnip/ y recordar su nombre/versión (del nombre
 * del archivo). La carga real del driver la hace Vita3K al arrancar;
 * el launcher no puede verificar que el driver cargó sin el núcleo
 * (Etapa 2). Por eso la UI dice "instalado en el launcher", no
 * "activo en Vita3K", hasta que el núcleo lo confirme.
 *
 * Al cambiar de driver, Vita3K limpia la caché de shaders al arrancar.
 * El launcher también ofrece limpiar la caché manualmente: borra los
 * directorios de caché de shaders conocidos dentro del vita_fs.
 * Solo se borra caché, nunca el juego ni los saves.
 */
class TurnipDriverRepository(private val context: Context) {

    /** Instala un zip de Turnip. Devuelve el nombre guardado. */
    fun installDriverZip(uri: Uri, fileName: String): Result<String> {
        // El nombre viene de DISPLAY_NAME del selector: se toma solo el
        // basename. Un nombre con "/" o ".." escaparía de la carpeta de
        // drivers y escribiría fuera de ella.
        val safeName = File(fileName).name
        if (!safeName.lowercase().endsWith(".zip")) {
            return Result.failure(IllegalArgumentException("El driver Turnip debe ser un archivo .zip."))
        }
        return try {
            val dir = File(context.filesDir, "vita3k/drivers/turnip")
            if (!dir.exists()) dir.mkdirs()
            // BUG (auditoría): antes se borraban TODOS los drivers y luego
            // se copiaba el nuevo; si openInputStream devolvía null o la
            // copia fallaba, el driver viejo (que funcionaba) ya estaba
            // perdido. Ahora se copia a temporal y solo se sustituye al final.
            val tmp = File(dir, "$safeName.tmp")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tmp).use { out -> input.copyTo(out, LARGE_FILE_COPY_BUFFER_BYTES) }
            } ?: return Result.failure(IllegalStateException("No se pudo abrir el zip."))
            val committed = commitTempFile(dir, tmp, safeName)
            val savedName = committed.getOrElse { return Result.failure(it) }
            // Solo se guarda el último driver instalado.
            dir.listFiles()?.forEach { if (it.name != savedName) it.delete() }
            LauncherLog.append(context, "driver", "Turnip instalado: $savedName (${File(dir, savedName).length()} bytes)")
            Result.success(savedName)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * true si hay un zip de driver incluido en assets (ver
     * BundledTurnipDriver.ASSET_PATH). No abre el zip, solo lista la
     * carpeta de assets. Sin binario en assets devuelve false y el
     * launcher sigue con el driver del sistema.
     */
    fun bundledAssetPresent(): Boolean {
        return try {
            context.assets.list(BundledTurnipDriver.ASSET_DIR)
                ?.contains(BundledTurnipDriver.EXPECTED_FILE_NAME) == true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Instala el driver Turnip incluido en assets ("de raíz").
     *
     * Copia el zip de assets a temporal, lo VALIDA (formato
     * AdrenoTools: meta.json + .so, ver BundledTurnipDriver) y solo
     * entonces lo confirma como driver activo. Si assets no trae el
     * zip, si la validación falla o si la copia falla, NO se toca el
     * driver que hubiera instalado y se devuelve el fallo: el
     * llamador debe seguir con el driver del sistema.
     * No descarga nada: el binario solo llega si se puso en assets
     * al compilar la APK.
     */
    fun installBundledAsset(
        assetPath: String = BundledTurnipDriver.ASSET_PATH,
    ): Result<BundledInstall> {
        val safeName = File(assetPath).name
        if (!safeName.lowercase().endsWith(".zip")) {
            return Result.failure(IllegalArgumentException("El driver incluido debe ser un archivo .zip."))
        }
        return try {
            val dir = File(context.filesDir, "vita3k/drivers/turnip")
            if (!dir.exists()) dir.mkdirs()
            val tmp = File(dir, "$safeName.tmp")
            try {
                context.assets.open(assetPath).use { input ->
                    FileOutputStream(tmp).use { out -> input.copyTo(out, LARGE_FILE_COPY_BUFFER_BYTES) }
                }
            } catch (e: Exception) {
                tmp.delete()
                return Result.failure(IllegalStateException("No hay driver incluido en assets ($assetPath)."))
            }
            val info = BundledTurnipDriver.validatePackage(tmp).getOrElse { e ->
                tmp.delete()
                return Result.failure(e)
            }
            val committed = commitTempFile(dir, tmp, safeName)
            val savedName = committed.getOrElse { return Result.failure(it) }
            // Solo se guarda el último driver instalado.
            dir.listFiles()?.forEach { if (it.name != savedName) it.delete() }
            if (BundledTurnipDriver.EXPECTED_SHA256.isBlank()) {
                LauncherLog.append(context, "driver", "AVISO: driver incluido instalado sin comprobar SHA-256 (hash aún sin rellenar en BundledTurnipDriver).")
            }
            LauncherLog.append(context, "driver", "Driver incluido instalado: $savedName (${info.sizeBytes} bytes, ${info.libraryName ?: "sin libraryName"}, sha256 ${info.sha256Hex.take(12)}…)")
            Result.success(BundledInstall(savedName, info))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Confirma un temporal como driver activo (rename atómico con
     * reintento si ya había un destino). Si no se puede confirmar,
     * borra el temporal y devuelve el fallo; el driver anterior
     * sigue intacto. Extraído de installDriverZip para compartirlo
     * con installBundledAsset sin duplicar la lógica.
     */
    private fun commitTempFile(dir: File, tmp: File, safeName: String): Result<String> {
        val dest = File(dir, safeName)
        var renamed = tmp.renameTo(dest)
        if (!renamed && dest.exists()) {
            dest.delete()
            renamed = tmp.renameTo(dest)
        }
        if (!renamed) {
            tmp.delete()
            return Result.failure(IllegalStateException("No se pudo guardar el driver."))
        }
        return Result.success(safeName)
    }

    fun installedDriverFile(): File? {
        val dir = File(context.filesDir, "vita3k/drivers/turnip")
        return dir.listFiles()?.firstOrNull { it.name.lowercase().endsWith(".zip") }
    }

    /** Borra el driver Turnip instalado (el zip). No borra el juego ni los saves. */
    fun deleteDriver(): Boolean {
        val dir = File(context.filesDir, "vita3k/drivers/turnip")
        if (!dir.exists()) return false
        val hadFile = dir.listFiles()?.isNotEmpty() == true
        dir.deleteRecursively()
        LauncherLog.append(context, "driver", "Driver Turnip borrado (había archivo: $hadFile)")
        return hadFile
    }

    /**
     * Limpia la caché de shaders. Borra solo directorios de caché
     * conocidos dentro del vita_fs. Devuelve cuántos archivos borró.
     * Vita3K regenera la caché al jugar (el stutter de la primera
     * partida vuelve temporalmente hasta que se llena de nuevo).
     */
    fun clearShaderCache(): ClearCacheResult {
        var deletedFiles = 0
        var deletedDirs = 0
        val vitaRoot = File(context.filesDir, "vita3k")
        val candidates = listOf(
            File(vitaRoot, "shader_cache"),
            File(vitaRoot, "cache/shaders"),
            // M27: antes se borraba también vita3k/cache/ entero, que es
            // caché genérica (no solo shaders) pese a lo que decía el
            // comentario. Ya no se toca.
        )
        for (dir in candidates) {
            if (dir.exists()) {
                deletedFiles += dir.walkTopDown().count { it.isFile }
                dir.deleteRecursively()
                deletedDirs++
            }
        }
        LauncherLog.append(context, "driver", "Caché de shaders limpiada: $deletedFiles archivos en $deletedDirs carpetas")
        return ClearCacheResult(deletedFiles, deletedDirs)
    }
}

data class ClearCacheResult(val deletedFiles: Int, val deletedDirs: Int)

/** Driver incluido instalado desde assets: nombre guardado + validación. */
data class BundledInstall(val savedName: String, val info: BundledTurnipDriver.PackageInfo)
