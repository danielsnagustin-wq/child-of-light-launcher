package com.danielsnagustin.childoflight.launcher.core

import com.danielsnagustin.childoflight.launcher.data.FirmwareResolver
import com.danielsnagustin.childoflight.launcher.data.GameLibraryRepository
import com.danielsnagustin.childoflight.launcher.data.LauncherLog
import com.danielsnagustin.childoflight.launcher.data.PkgHeaderParser
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
 *   con el zRIF). Un zRIF vacío NO es una instalación válida: el
 *   descifrado F00D lo necesita y upstream devuelve false
 *   (pkg.cpp:331-351, execute < 0 -> remove + return false).
 *   Por eso installPkgFile NUNCA instala con zrif="" (P0-1): el
 *   zRIF sale del work.bin guardado o, si no lo hay, de la licencia
 *   ya instalada en el núcleo (findPkgZrif, pkg.cpp:381-405); si no
 *   hay licencia en ningún sitio, falla ANTES de instalar.
 * - convertRifToZrif(work.bin): el work.bin de 512 B -> zRIF.
 * - copyLicense(work.bin): copia la licencia .bin/.rif a su sitio
 *   (ux0/license/<title>/<contentId>.rif, license.cpp). Solo
 *   entiende .rif BINARIO: lee el Content ID del offset 16; un
 *   work.bin en texto zRIF ("KO…") NO es un .rif y no debe pasarse
 *   por copyLicense.
 * - installArchive(zip/vpk, callback, force): instala .zip/.vpk.
 * - installFirmware(pup, callback): instala el firmware PUP.
 *
 * Orden y destino, verificados en el pin (sospechas S7/S8/S9 de la
 * ronda 3, resueltas):
 * - Un .pkg corrupto NO deja un eboot a medias en el juego
 *   instalado: el núcleo desencripta en copias temporales
 *   (packages/src/pkg.cpp: install_pkg: execute(zRIF, src, _dec) y
 *   si falla borra ambas y devuelve false) y solo después copia al
 *   destino final (copy_path). La validación va antes de tocar el
 *   juego vivo.
 * - El destino lo decide el núcleo por la CATEGORY del param.sfo
 *   (interface.cpp:114-145 set_content_path), no por el Title ID
 *   que le pasemos: "ac" -> addcont/<TITLE>/<contenido corto>,
 *   "gp" -> patch/<TITLE> (y exige que el juego ya esté instalado),
 *   resto -> app/<TITLE>. Por eso un .pkg de DLC con el Title ID
 *   del juego acaba en addcont/ y no en app/.
 * - La instalación por CARPETA (install_content, interface.cpp:
 *   369-403) sí borra el destino ANTES de copiar (remove_all y
 *   luego copy): un carpeta corrupta puede dejar sin juego. Por eso
 *   el launcher instala carpetas con installDirAtomic (respaldo
 *   .bak-*, P0-2), nunca directo.
 *
 * Sin núcleo embebido, TODO devuelve failure con el mensaje
 * "Núcleo embebido no presente": el llamador debe usar entonces
 * la vía de Etapa 1 (deliverPkgFiles a Vita3K externo). Nada de
 * esto finge instalar: si el puente devuelve false, es failure.
 *
 * Las llamadas nativas bloquean (la instalación de 1 GB tarda):
 * se ejecutan en Dispatchers.IO. El progreso llega por onProgress.
 */
/**
 * Failure de [EmbeddedPkgInstaller.installPkgFile] (y de installGamePkg /
 * installDlcPkg / installUpdatePkg) con un significado DISTINTO al failure
 * genérico (P1-12): el .pkg YA quedó instalado en el núcleo, pero su
 * licencia no se pudo registrar en disco (copyLicense falló tras un
 * installPkg con éxito). El juego está dentro del núcleo; un reintento
 * de JUGAR con el mismo work.bin fallaría igual.
 *
 * CONTRATO para el llamador (Vita3KLauncher.kt, oleada 2):
 * - NO tratarlo como un failure genérico de instalación (no es
 *   "no se pudo instalar": el juego SÍ está instalado).
 * - Comprobarlo con `result.exceptionOrNull() is
 *   InstalledWithoutLicenseException` y ofrecer REEMPLAZAR el work.bin
 *   (selector de work.bin, PendingPkgLicense) en vez de un reintento
 *   que reinstalaría 1,1 GB para fallar igual.
 * - La firma sigue siendo Result<Unit> a propósito: cambiarla a un
 *   sealed de resultado rompería Vita3KLauncher.kt (que usa isFailure /
 *   onFailure y devuelve Result<Unit>), archivo de la oleada 2. El tipo
 *   distinto viaja como la excepción del failure, comprobable con `is`.
 */
class InstalledWithoutLicenseException(message: String) : IllegalStateException(message)

class EmbeddedPkgInstaller(private val library: GameLibraryRepository) {

    /** Progreso de instalación (0-100, mensaje en español). */
    fun interface ProgressListener {
        fun onProgress(percent: Int, status: String)
    }

    /**
     * 0.2.64: el callback nativo se crea POR LLAMADA, capturando el
     * [ProgressListener] de ESA instalación como parámetro local.
     * Antes había UN solo callback de instancia que leía un campo
     * compartido `progressListener` (con @Volatile): dos
     * instalaciones concurrentes (p. ej. .pkg del juego + DLC en
     * paralelo desde Vita3KLauncher) se pisaban el listener — el
     * progreso de una llegaba al listener de la otra — y el finally
     * de la primera lo ponía a null mientras la segunda seguía
     * corriendo. No había llamador concurrente hoy (latente), pero
     * el campo compartido era la única pieza que lo impedía: con el
     * callback por llamada, cada instalación lleva su listener en su
     * propio cierre y dos instalaciones concurrentes ya no se
     * interfieren. Sin campo, sin @Volatile, sin finally que limpiar.
     */
    private fun installCallback(onProgress: ProgressListener): Vita3kCoreBridge.EmbeddedInstallCallback =
        Vita3kCoreBridge.EmbeddedInstallCallback { percent, status ->
            onProgress.onProgress(percent, status)
        }

    /**
     * Sesión del núcleo lista para instalar (2026-11-07): las funciones
     * de instalación de upstream necesitan la sesión inicializada
     * (nativeLoadGame lo exige explícito: get_emuenv() null -> -2, y la
     * UI de upstream inicializa la sesión antes de instalar). Instalar
     * sin init previo es la sospecha principal si el primer .pkg o el
     * primer firmware fallan en un proceso nuevo. init es idempotente
     * en upstream (si ya está inicializado devuelve true), así que
     * llamarlo aquí no cuesta un segundo arranque: el lanzamiento
     * embebido vuelve a llamar a init y sigue igual.
     */
    private suspend fun ensureCoreInitialized(): Boolean {
        if (!Vita3kCoreBridge.available) return false
        // Ronda 0.2.70 (Área 10, P1-11): el init del instalador es el
        // tercer camino de init(): también va bajo el Mutex de sesión,
        // serializado con la destrucción de onDestroy (sin esto, un
        // instalar justo al salir del juego corría init mientras el
        // teardown destruía la sesión). La generación además lo cubre
        // como red (el init con éxito sube la generación).
        return Vita3kCoreBridge.withSessionLock {
            Vita3kCoreBridge.init(library.vitaRoot.absolutePath) == Vita3kCoreBridge.OK
        }
    }

    /**
     * Instala el .pkg del JUEGO (PCSE00480 / PCSB00598) con su work.bin.
     * Orden (P0-1, P1-12): validar el work.bin contra el Content ID del
     * .pkg -> obtener el zRIF (del work.bin o de la licencia ya instalada
     * en el núcleo) -> installPkg -> registrar el .rif con copyLicense.
     * Nunca se instala sin licencia: sin work.bin ni licencia instalada
     * en el núcleo, falla ANTES de instalar. Si copyLicense falla tras
     * instalar, el failure lleva [InstalledWithoutLicenseException]
     * (el juego YA está instalado; hay que reemplazar el work.bin, no
     * reinstalar). Devuelve success solo si installPkg devolvió true
     * y la licencia quedó registrada (o no había work.bin que registrar
     * porque la licencia ya estaba en el núcleo).
     */
    suspend fun installGamePkg(titleId: TitleId, onProgress: ProgressListener): Result<Unit> {
        // P2-28: el .pkg puede vivir en el ux0 antiguo (la migración no
        // mueve pkg/): se busca en ambos ux0 y su work.bin es el que
        // está junto al .pkg encontrado, no el del ux0 efectivo.
        val pkgFile = library.findPkgGameFile(titleId.id)
            ?: return Result.failure(IllegalStateException("No está el archivo ${titleId.id}.pkg."))
        return installPkgFile(
            pkgFile = pkgFile,
            workBinFile = library.findPkgWorkBinFile(pkgFile),
            onProgress = onProgress,
        )
    }

    /** Instala un .pkg de DLC (por DLC ID) con su work.bin. */
    suspend fun installDlcPkg(dlcId: String, onProgress: ProgressListener): Result<Unit> {
        // P2-28: igual que el juego: buscar en ambos ux0.
        val pkgFile = library.findPkgDlcFile(dlcId)
            ?: return Result.failure(IllegalStateException("No está el archivo $dlcId.pkg."))
        return installPkgFile(
            pkgFile = pkgFile,
            workBinFile = library.findPkgWorkBinFile(pkgFile),
            onProgress = onProgress,
        )
    }

    /** Instala un .pkg de UPDATE con su work.bin. */
    suspend fun installUpdatePkg(titleId: TitleId, onProgress: ProgressListener): Result<Unit> {
        // P2-28: igual que el juego: buscar en ambos ux0.
        val pkgFile = library.findPkgPatchFile(titleId.id)
            ?: return Result.failure(IllegalStateException("No está el archivo ${titleId.id}.pkg."))
        return installPkgFile(
            pkgFile = pkgFile,
            workBinFile = library.findPkgWorkBinFile(pkgFile),
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
        // R6-P2-01: sin espacio no se empieza la instalación: el .pkg
        // ya guardado más su extracción conviven en la misma partición
        // (el doble del .pkg). Es la red de seguridad de los llamadores
        // que no comprueban espacio (installStoredAddonPkgs comprueba
        // por addon antes de llamar; los del juego comprueban longitud
        // + margen). Si no se pudo medir el espacio, no se bloquea
        // (hasFreeSpace): el fallo, si lo hay, lo da installPkg.
        val neededBytes = GameLibraryRepository.pkgInstallNeededBytes(pkgFile.length())
        if (!library.hasFreeSpace(library.ux0Dir, neededBytes)) {
            val reason = GameLibraryRepository.freeSpaceMessage(neededBytes, library.freeBytes(library.ux0Dir), pkgFile.length())
            return Result.failure(IllegalStateException(reason))
        }
        if (!ensureCoreInitialized()) {
            return Result.failure(IllegalStateException("El núcleo embebido no se pudo iniciar: no se puede instalar ${pkgFile.name}."))
        }
        return withContext(Dispatchers.IO) {
            // 0.2.64: callback propio de ESTA llamada (ver installCallback):
            // el progreso va a onProgress sin pasar por ningún campo compartido.
            val callback = installCallback(onProgress)
            try {
                // (P1-12) 1. Validar el work.bin ANTES de instalar, contra
                // el Content ID de ESTE .pkg: un work.bin de otro juego
                // (o corrupto) se rechaza aquí, no tras 1,1 GB instalados.
                // Si el header del .pkg no se puede leer, se valida sin
                // expectativa de Content ID (la forma A sigue exigiendo
                // un Content ID con formato válido); installPkg de
                // upstream rechaza por su cuenta un .pkg no válido.
                val expectedContentId = readPkgContentId(pkgFile)
                if (workBinFile.isFile) {
                    val validation = runCatching {
                        workBinFile.inputStream().use {
                            PkgHeaderParser.validateWorkBin(it, expectedContentId)
                        }
                    }.getOrElse { Result.failure(it) }
                    if (validation.isFailure) {
                        return@withContext Result.failure(
                            validation.exceptionOrNull()
                                ?: IllegalStateException("El work.bin no es válido para ${pkgFile.name}."),
                        )
                    }
                }
                // (P0-1) 2. Obtener el zRIF. Nunca se instala con
                // zrif="": upstream lo necesita para descifrar (pkg.cpp:
                // execute < 0 -> install false) y un juego instalado sin
                // licencia no arranca. Dos fuentes, en este orden:
                //  a) el work.bin guardado junto al .pkg;
                //  b) la licencia YA instalada en el núcleo (findPkgZrif
                //     lee ux0/license/<title>/<contentId>.rif): es el
                //     caso legítimo sin work.bin, p. ej. la reinstalación
                //     del eboot cifrado (reinstallGamePkg) cuando el juego
                //     ya se instaló una vez con licencia.
                // Si convertRifToZrif devuelve "" con work.bin presente,
                // se falla ANTES de instalar (P1-12): instalar sin
                // licencia y descubrirlo después ya no ocurre.
                val zrif: String
                if (workBinFile.isFile) {
                    val converted = Vita3kCoreBridge.convertRifToZrif(workBinFile.absolutePath)
                    if (converted.isEmpty()) {
                        return@withContext Result.failure(
                            IllegalStateException(
                                "El work.bin de ${pkgFile.name} no se pudo convertir a licencia (zRIF): " +
                                    "el núcleo no puede instalar el .pkg sin ella. " +
                                    "Elige el work.bin binario (.rif, 512 B) que acompaña a este .pkg.",
                            ),
                        )
                    }
                    zrif = converted
                } else {
                    val installed = Vita3kCoreBridge.findPkgZrif(pkgFile.absolutePath)
                    if (installed.isEmpty()) {
                        return@withContext Result.failure(
                            IllegalStateException(
                                "Falta la licencia (work.bin) de ${pkgFile.name}: sin ella el motor integrado " +
                                    "no puede instalar el .pkg. Elige el work.bin en Importar (tarjeta «Falta la licencia»).",
                            ),
                        )
                    }
                    zrif = installed
                }
                val ok = Vita3kCoreBridge.installPkg(pkgFile.absolutePath, zrif, callback)
                if (!ok) {
                    return@withContext Result.failure(
                        IllegalStateException("El núcleo no pudo instalar ${pkgFile.name}."),
                    )
                }
                // (P1-12) 3. Registrar el .rif en el núcleo para que
                // futuras reinstalaciones lo encuentren vía findPkgZrif.
                // installPkg ya llama a create_license con el zRIF, así
                // que esto es un respaldo; si falla, el juego YA está
                // instalado: NO es un failure genérico, es
                // InstalledWithoutLicense (ver su contrato): la oleada 2
                // ofrece reemplazar el work.bin en vez de reinstalar.
                // Solo con .rif BINARIO: copyLicense lee el Content ID
                // del offset 16 (license.cpp) y un work.bin en texto
                // zRIF ("KO…") no es un .rif; su licencia ya quedó
                // registrada por create_license desde el zRIF convertido.
                if (workBinFile.isFile && !isTextZrifFile(workBinFile)) {
                    val licenseOk = Vita3kCoreBridge.copyLicense(workBinFile.absolutePath)
                    if (!licenseOk) {
                        return@withContext Result.failure(
                            InstalledWithoutLicenseException(
                                "El núcleo instaló ${pkgFile.name} pero no pudo copiar su licencia (work.bin): " +
                                    "el juego está instalado pero puede no arrancar sin ella. " +
                                    "Reemplaza el work.bin e inténtalo de nuevo.",
                            ),
                        )
                    }
                }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * Content ID del header del .pkg (p. ej.
     * "EP0001-PCSB00598_00-CHILDOFLIGHT0000"), o null si el header no
     * se puede leer. Solo se usa como expectativa al validar el
     * work.bin; null = validar sin expectativa.
     */
    private fun readPkgContentId(pkgFile: File): String? {
        return runCatching {
            pkgFile.inputStream().use { PkgHeaderParser.parse(it) }
        }.getOrNull()?.getOrNull()?.contentId
    }

    /**
     * ¿Es este work.bin un texto zRIF ("KO…" en ASCII) en vez de un
     * .rif binario? La distinción importa porque copyLicense de
     * upstream solo entiende .rif binario (license.cpp: lee el
     * Content ID del offset 16). Limitación conocida (sospecha S1 del
     * reporte): rif2zrif NO está en el pin parcial de Vita3K (solo su
     * #include y sus llamadas), así que no se puede verificar si el
     * nativo convierte un texto zRIF; si convertRifToZrif devuelve ""
     * con un work.bin en texto, installPkgFile falla antes de instalar
     * con el mensaje de conversión, sin inventar comportamiento del
     * nativo. La forma A (binaria) sí se valida con rigor: Content ID
     * con formato válido y, si se conoce, igual al del .pkg.
     */
    private fun isTextZrifFile(file: File): Boolean {
        if (!file.isFile) return false
        return runCatching {
            file.inputStream().use { input ->
                val first = ByteArray(2)
                input.read(first) == 2 &&
                    first[0] == 'K'.code.toByte() && first[1] == 'O'.code.toByte()
            }
        }.getOrDefault(false)
    }

    /** Instala un .zip/.vpk con el núcleo (misma ruta que installArchive de upstream). */
    suspend fun installArchive(archiveFile: File, forceReinstall: Boolean, onProgress: ProgressListener): Result<Unit> {
        if (!Vita3kCoreBridge.available) {
            return Result.failure(IllegalStateException("Núcleo embebido no presente (Etapa 2)."))
        }
        if (!archiveFile.isFile) {
            return Result.failure(IllegalStateException("No está el archivo ${archiveFile.name}."))
        }
        if (!ensureCoreInitialized()) {
            return Result.failure(IllegalStateException("El núcleo embebido no se pudo iniciar: no se puede instalar ${archiveFile.name}."))
        }
        return withContext(Dispatchers.IO) {
            val callback = installCallback(onProgress)
            try {
                val ok = Vita3kCoreBridge.installArchive(archiveFile.absolutePath, callback, forceReinstall)
                if (!ok) {
                    Result.failure(IllegalStateException("El núcleo no pudo instalar ${archiveFile.name}."))
                } else {
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                Result.failure(e)
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
        if (!ensureCoreInitialized()) {
            return Result.failure(IllegalStateException("El núcleo embebido no se pudo iniciar: no se puede instalar el firmware."))
        }
        return withContext(Dispatchers.IO) {
            val callback = installCallback(onProgress)
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
                    // R6-P2-06: aviso (SIN bloquear) si la versión
                    // instalada es menor que 3.15, el mínimo del juego
                    // (o desconocida): el juego puede fallar al arrancar
                    // con firmware viejo. 3.60-3.73 NO se bloquean: el
                    // juego corre en ellas. El aviso queda en el log
                    // para el diagnóstico; la instalación ya tuvo éxito.
                    if (FirmwareResolver.versionBelowMinimum(version)) {
                        LauncherLog.append(library.appContext, "firmware", "Firmware instalado $version: por debajo del mínimo recomendado 3.15 para este juego. El juego puede fallar al arrancar; si es así, instala un firmware 3.15 o superior.")
                    }
                    Result.success(version)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

}
