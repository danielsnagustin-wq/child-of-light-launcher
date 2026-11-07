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
                FileOutputStream(tmp).use { out -> input.copyTo(out) }
            } ?: return Result.failure(IllegalStateException("No se pudo abrir el zip."))
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
            // Solo se guarda el último driver instalado.
            dir.listFiles()?.forEach { if (it != dest) it.delete() }
            LauncherLog.append(context, "driver", "Turnip instalado: $safeName (${dest.length()} bytes)")
            Result.success(safeName)
        } catch (e: Exception) {
            Result.failure(e)
        }
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
