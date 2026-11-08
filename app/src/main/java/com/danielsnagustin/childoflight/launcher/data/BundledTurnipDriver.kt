package com.danielsnagustin.childoflight.launcher.data

import java.io.File
import java.io.FileInputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Driver Turnip incluido ("de raíz") — SOLO Adreno, SIN binario todavía.
 *
 * Qué es: Turnip es el driver Vulkan open-source de Mesa para GPUs
 * Qualcomm Adreno (proyecto Freedreno). Se distribuye como .zip
 * (formato AdrenoTools): en la raíz del zip van `meta.json`
 * (nombre, versión y `libraryName`) y la librería Vulkan, normalmente
 * `libvulkan_freedreno.so`. Una app puede cargar ese .so solo para sí
 * misma, sin root: el cargador Vulkan de la app hace dlopen de la
 * librería del paquete en vez del driver del sistema. Por eso el
 * mecanismo existe solo para Adreno: el .so emite código máquina y
 * paquetes de registros específicos del silicio Adreno.
 *
 * Fuente oficial del binario (NO descargado, NO incluido):
 * K11MCH1/AdrenoToolsDrivers en GitHub, release v26.0.0 Revisión 8
 * (publicada 2026-01-08, Mesa 26.0.0-devel, commit base e9a83d530cce,
 * Vulkan 1.4.335). Activos de esa release: Turnip_v26.0.0_R8_Gmem.zip
 * y Turnip_v26.0.0_R8_Autotuner.zip (~2 MB por zip en releases
 * anteriores: 2,14 MB la v24.3.0 R5). Licencia del código: MIT (Mesa).
 * Los drivers "Qualcomm v615/v840" de ese mismo repo NO son Mesa:
 * son binarios propietarios extraídos de otros dispositivos y NO se
 * pueden empaquetar como driver libre.
 *
 * Estado de esta integración: PREPARADA, sin binario. El archivo
 * esperado es `assets/drivers/turnip/Turnip_v26.0.0_R8_Gmem.zip`
 * (ver app/src/main/assets/drivers/turnip/README.md). Mientras no
 * exista, todo esto es no-op: el launcher sigue con el driver del
 * sistema y ningún flujo cambia. Cuando el binario se ponga en
 * assets, la instalación es automática solo en Adreno y solo si no
 * hay ya un driver instalado; cualquier fallo vuelve al driver del
 * sistema sin tocar el juego ni los saves.
 *
 * LÍMITES HONESTOS (ver docs/gpu-drivers.md):
 * - La carga real del driver la hace el núcleo al arrancar, leyendo
 *   `custom-driver-name` de config.yml y el zip de
 *   `<filesDir>/vita3k/drivers/turnip/`. El valor exacto que espera
 *   esa clave (nombre con/sin .zip) sigue TODO-VERIFY contra el
 *   árbol del núcleo fijado (ver Vita3kConfigGenerator). Este archivo
 *   NO carga ningún .so: solo selecciona, valida e instala el zip.
 * - La R8 es de enero de 2026 y ya no es la Turnip más reciente en
 *   general (otros constructores publican revisiones más nuevas).
 *   Antes de poner el binario hay que re-verificar cuál es la
 *   release vigente y su SHA-256, y rellenar EXPECTED_SHA256.
 * - Un mismo zip estándar NO cubre todos los Adreno: hay variantes
 *   por generación (ver selectVariant). Sin el binario en mano no se
 *   puede confirmar el layout exacto del zip R8 (meta.json frente a
 *   metadata.json, subcarpeta vulkan/, nombre del .so); la
 *   validación es estricta a propósito y puede necesitar relajarse
 *   cuando se tenga el zip real. Si la validación rechaza el zip
 *   incluido, la instalación manual por selector sigue funcionando
 *   igual que hoy (sin validación).
 *
 * Este objeto es Kotlin puro (sin android.*) para poder probarlo en
 * tests unitarios de la JVM.
 */
object BundledTurnipDriver {

    /** Ruta del zip dentro de assets (sin el archivo, es no-op). */
    const val ASSET_PATH = "drivers/turnip/Turnip_v26.0.0_R8_Gmem.zip"

    /** Carpeta de assets que contiene el zip incluido. */
    const val ASSET_DIR = "drivers/turnip"

    /** Nombre de archivo esperado del zip incluido. */
    const val EXPECTED_FILE_NAME = "Turnip_v26.0.0_R8_Gmem.zip"

    /**
     * SHA-256 (hex, minúsculas) del zip incluido exacto.
     * VACÍO a propósito: sin el binario en mano no hay hash que poner.
     * Mientras esté vacío, la validación es solo estructural (zip +
     * meta.json + .so) y el log avisa. Al poner el binario, rellenar
     * con el hash de la release descargada; entonces un zip distinto
     * (otra revisión, corrupto, manipulado) se rechaza.
     */
    const val EXPECTED_SHA256 = ""

    /** Tamaño máximo aceptado para el paquete (el zip real ronda 2 MB). */
    const val MAX_PACKAGE_BYTES: Long = 32L * 1024L * 1024L

    /** Variante incluida: build estándar Gmem de la R8 (6xx/7xx). */
    enum class BundledTurnipVariant { STANDARD_GMEM }

    /**
     * Elige la variante incluida para un renderer GLES, o null si
     * para esa GPU NO se debe auto-instalar el zip incluido.
     *
     * - 6xx (610-699) y 7xx altos (725/730/735/740/750): STANDARD_GMEM.
     *   Es el build estándar de K11MCH1 para esas generaciones.
     * - 710/720/722: null. El soporte en la R8 son "hacks" de
     *   descriptores y en releases anteriores se quitó y se repuso
     *   varias veces; no se auto-instala a ciegas. El usuario puede
     *   seguir instalando un zip a mano con el selector.
     * - 8xx (810/825/829/830/840): null. La R8 lo marca "very beta
     *   and hacky, use at your own risk" y lleva build aparte; el
     *   zip estándar no es para estos.
     * - A32: null. Tiene edición especial aparte (turnip_A32).
     * - Mali / Immortalis / PowerVR / Xclipse / desconocido: null.
     *   Turnip no funciona fuera de Adreno.
     */
    fun selectVariant(renderer: String): BundledTurnipVariant? {
        val r = renderer.uppercase()
        if (r.isBlank()) return null
        if (!r.contains("ADRENO")) return null
        if (r.contains("A32")) return null
        val model = Regex("ADRENO[^0-9]*(\\d{3})").find(r)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: return null
        return when {
            model in 600..699 -> BundledTurnipVariant.STANDARD_GMEM
            model in setOf(725, 730, 735, 740, 750) -> BundledTurnipVariant.STANDARD_GMEM
            else -> null
        }
    }

    /** Resultado de validar un paquete de driver. */
    data class PackageInfo(
        val fileName: String,
        /** Librería Vulkan declarada en meta.json (o el único .so). */
        val libraryName: String?,
        val sha256Hex: String,
        val sizeBytes: Long,
    )

    /**
     * Valida un zip de driver (formato AdrenoTools). Exige:
     * existe, tamaño > 0 y <= MAX_PACKAGE_BYTES, zip válido,
     * `meta.json` en la raíz (o bajo una única carpeta superior),
     * al menos un `.so`, y que la `libraryName` declarada en
     * meta.json exista dentro del zip. Si EXPECTED_SHA256 está
     * relleno, el hash debe coincidir. No extrae nada.
     */
    fun validatePackage(zipFile: File): Result<PackageInfo> {
        if (!zipFile.isFile) {
            return Result.failure(IllegalArgumentException("No existe el paquete de driver: ${zipFile.name}"))
        }
        val size = zipFile.length()
        if (size <= 0) return Result.failure(IllegalArgumentException("El paquete de driver está vacío."))
        if (size > MAX_PACKAGE_BYTES) {
            return Result.failure(IllegalArgumentException("El paquete de driver es demasiado grande ($size bytes)."))
        }
        return try {
            ZipFile(zipFile).use { zip ->
                val entries = zip.entries().toList()
                val metaEntry = entries.firstOrNull {
                    it.name == "meta.json" || (it.name.endsWith("/meta.json") && it.name.count { c -> c == '/' } == 1)
                } ?: return Result.failure(
                    IllegalArgumentException("El zip no trae meta.json (formato AdrenoTools)."),
                )
                val soEntries = entries.filter { it.name.lowercase().endsWith(".so") && !it.isDirectory }
                if (soEntries.isEmpty()) {
                    return Result.failure(IllegalArgumentException("El zip no trae ninguna librería .so."))
                }
                // Auditoría RAM ≤ 4 GB (2026-11-07): meta.json debe ser
                // un JSON pequeño; sin tope, un zip manipulado podría
                // hacer que readText() cargara hasta 32 MB (el tope del
                // zip) en RAM. El zip entero ya está topado en 32 MB.
                if (metaEntry.size > 64 * 1024) {
                    return Result.failure(
                        IllegalArgumentException("meta.json demasiado grande (${metaEntry.size} bytes)."),
                    )
                }
                val metaText = zip.getInputStream(metaEntry).bufferedReader().readText()
                val declared = Regex("\"libraryName\"\\s*:\\s*\"([^\"]+)\"").find(metaText)?.groupValues?.getOrNull(1)
                val libraryName: String? = when {
                    declared != null -> {
                        val present = soEntries.any { it.name == declared || it.name.endsWith("/$declared") }
                        if (!present) {
                            return Result.failure(
                                IllegalArgumentException("meta.json nombra $declared pero no está en el zip."),
                            )
                        }
                        declared
                    }
                    soEntries.size == 1 -> soEntries.single().name.substringAfterLast('/')
                    else -> soEntries.sortedBy { it.name }.first().name.substringAfterLast('/')
                }
                val hash = sha256Hex(zipFile)
                if (EXPECTED_SHA256.isNotBlank() && !hash.equals(EXPECTED_SHA256, ignoreCase = true)) {
                    return Result.failure(IllegalArgumentException("El SHA-256 del paquete no coincide con el esperado."))
                }
                Result.success(PackageInfo(zipFile.name, libraryName, hash, size))
            }
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(IllegalStateException("No se pudo leer el paquete de driver: ${e.message}"))
        }
    }

    /** SHA-256 en hex de unos bytes. */
    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** SHA-256 en hex de un archivo (streaming, sin cargarlo entero). */
    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            // Bucle manual, no readAllBytes(): esa API es 33+ y el
            // minSdk es 28; en dispositivos viejos tumbaría la carga.
            DigestInputStream(input, digest).use { digestInput ->
                val buffer = ByteArray(8192)
                while (digestInput.read(buffer) != -1) {
                    // Solo leer: el digest se actualiza al leer.
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
