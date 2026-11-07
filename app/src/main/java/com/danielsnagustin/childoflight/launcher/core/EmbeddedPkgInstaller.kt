package com.danielsnagustin.childoflight.launcher.core

import com.danielsnagustin.childoflight.launcher.data.FirmwareResolver
import com.danielsnagustin.childoflight.launcher.data.GameLibraryRepository
import com.danielsnagustin.childoflight.launcher.model.PkgKind
import com.danielsnagustin.childoflight.launcher.model.TitleId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Instalación de juego/DLC/update DENTRO del núcleo embebido (Etapa 2).
 *
 * Esto es lo que hace que el launcher "solo acepte el juego": con el
 * núcleo embebido, el .pkg + work.bin ya NO hay que entregarlos a
 * Vita3K externo. El propio núcleo los instala con sus funciones
 * verificadas de upstream (Vita3K master 3e43bc80,
 * vita3k/android/jni/native_install.cpp):
 * - installPkg(pkg, zrif, callback): instala el .pkg (desencripta
 *   con el zRIF). zrif="" instala sin licencia.
 * - convertRifToZrif(work.bin): el work.bin de 512 B -> zRIF.
 * - copyLicense(work.bin): copia la licencia .bin/.rif a su sitio.
 * - installArchive(zip/vpk, callback, force): instala .zip/.vpk.
 * - installFirmware(pup, callback): instala el firmware PUP.
 *
 * Sin núcleo embebido, TODO devuelve failure con el mensaje
 * "Núcleo embebido no presente": el llamador debe usar entonces
 * la vía de Etapa 1 (deliverPkgFiles a Vita3K externo). Nada de
 * esto finge instalar: si el puente devuelve false, es failure.
 *
 * Las llamadas nativas bloquean (la instalación de 1 GB tarda):
 * se ejecutan en Dispatchers.IO. El progreso llega por onProgress.
 */
class EmbeddedPkgInstaller(private val library: GameLibraryRepository) {

    /** Progreso de instalación (0-100, mensaje en español). */
    fun interface ProgressListener {
        fun onProgress(percent: Int, status: String)
    }

    private val callback = Vita3kCoreBridge.EmbeddedInstallCallback { percent, status ->
        progressListener?.onProgress(percent, status)
    }

    @Volatile
    private var progressListener: ProgressListener? = null

    /**
     * Instala el .pkg del JUEGO (PCSE00480 / PCSB00598) con su work.bin.
     * Orden upstream: zRIF del work.bin -> installPkg -> si no había
     * zRIF, copyLicense(work.bin) como respaldo. Devuelve success solo
     * si installPkg devolvió true.
     */
    suspend fun installGamePkg(titleId: TitleId, onProgress: ProgressListener): Result<Unit> {
        return installPkgFile(
            pkgFile = library.pkgGameFile(titleId.id),
            workBinFile = library.pkgWorkBinFile(titleId.id),
            onProgress = onProgress,
        )
    }

    /** Instala un .pkg de DLC (por DLC ID) con su work.bin. */
    suspend fun installDlcPkg(dlcId: String, onProgress: ProgressListener): Result<Unit> {
        return installPkgFile(
            pkgFile = library.pkgDlcFile(dlcId),
            workBinFile = library.pkgDlcWorkBinFile(dlcId),
            onProgress = onProgress,
        )
    }

    /** Instala un .pkg de UPDATE con su work.bin. */
    suspend fun installUpdatePkg(titleId: TitleId, onProgress: ProgressListener): Result<Unit> {
        return installPkgFile(
            pkgFile = library.pkgPatchFile(titleId.id),
            workBinFile = library.pkgPatchWorkBinFile(titleId.id),
            onProgress = onProgress,
        )
    }

    suspend fun installPkgFile(pkgFile: File, workBinFile: File, onProgress: ProgressListener): Result<Unit> {
        if (!Vita3kCoreBridge.available) {
            return Result.failure(IllegalStateException("Núcleo embebido no presente (Etapa 2)."))
        }
        if (!pkgFile.isFile) {
            return Result.failure(IllegalStateException("No está el archivo ${pkgFile.name}."))
        }
        return withContext(Dispatchers.IO) {
            progressListener = onProgress
            try {
                // zRIF desde el work.bin (512 B). Si no hay work.bin o no
                // se puede convertir, se instala sin licencia (zrif="").
                var zrif = ""
                if (workBinFile.isFile) {
                    zrif = Vita3kCoreBridge.convertRifToZrif(workBinFile.absolutePath)
                }
                val ok = Vita3kCoreBridge.installPkg(pkgFile.absolutePath, zrif, callback)
                if (!ok) {
                    return@withContext Result.failure(
                        IllegalStateException("El núcleo no pudo instalar ${pkgFile.name}."),
                    )
                }
                // Respaldo: si no hubo zRIF pero sí work.bin, copiar la
                // licencia a su sitio dentro del núcleo.
                if (zrif.isEmpty() && workBinFile.isFile) {
                    Vita3kCoreBridge.copyLicense(workBinFile.absolutePath)
                }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                progressListener = null
            }
        }
    }

    /** Instala un .zip/.vpk con el núcleo (misma ruta que installArchive de upstream). */
    suspend fun installArchive(archiveFile: File, forceReinstall: Boolean, onProgress: ProgressListener): Result<Unit> {
        if (!Vita3kCoreBridge.available) {
            return Result.failure(IllegalStateException("Núcleo embebido no presente (Etapa 2)."))
        }
        if (!archiveFile.isFile) {
            return Result.failure(IllegalStateException("No está el archivo ${archiveFile.name}."))
        }
        return withContext(Dispatchers.IO) {
            progressListener = onProgress
            try {
                val ok = Vita3kCoreBridge.installArchive(archiveFile.absolutePath, callback, forceReinstall)
                if (!ok) {
                    Result.failure(IllegalStateException("El núcleo no pudo instalar ${archiveFile.name}."))
                } else {
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                progressListener = null
            }
        }
    }

    /**
     * Instala el firmware PUP con el núcleo. Devuelve la versión
     * instalada (p. ej. "3.74") o failure. El PUP lo aporta el usuario
     * (opción avanzada); nunca se incluye ni se descarga en el repo.
     */
    suspend fun installFirmware(pupFile: File, onProgress: ProgressListener): Result<String> {
        if (!Vita3kCoreBridge.available) {
            return Result.failure(IllegalStateException("Núcleo embebido no presente (Etapa 2)."))
        }
        if (!pupFile.isFile) {
            return Result.failure(IllegalStateException("No está el archivo ${pupFile.name}."))
        }
        return withContext(Dispatchers.IO) {
            progressListener = onProgress
            try {
                val version = Vita3kCoreBridge.installFirmware(pupFile.absolutePath, callback)
                if (version.isEmpty()) {
                    Result.failure(IllegalStateException("El núcleo no pudo instalar el firmware."))
                } else {
                    // Registrar la versión instalada: solo así
                    // FirmwareResolver.resolve() pasa a Ready. Sin este
                    // marcador, el firmware instalado seguiría viéndose
                    // como "PUP guardado" y decide() no elegiría nunca
                    // el núcleo embebido.
                    FirmwareResolver.markInstalled(library.vitaRoot, version)
                    Result.success(version)
                }
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                progressListener = null
            }
        }
    }

    /** ¿Qué .pkg guardados tiene el launcher? (para la UI de instalación). */
    fun storedGamePkgs(): List<Pair<TitleId, PkgKind>> {
        val out = mutableListOf<Pair<TitleId, PkgKind>>()
        for (candidate in TitleId.entries) {
            if (library.pkgGameFile(candidate.id).isFile) {
                out.add(candidate to PkgKind.GAME)
            }
            if (library.pkgPatchFile(candidate.id).isFile) {
                out.add(candidate to PkgKind.UPDATE)
            }
        }
        return out
    }
}
