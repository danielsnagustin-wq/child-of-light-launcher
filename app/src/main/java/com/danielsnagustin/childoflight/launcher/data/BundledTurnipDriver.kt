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
 * (tag v26.0.0-rc08, publicada 2026-01-08T01:03:08Z, Mesa
 * 26.0.0-devel, commit base e9a83d530cce, Vulkan 1.4.335). Activos
 * de esa release: Turnip_v26.0.0_R8_Gmem.zip (3.478.156 bytes) y
 * Turnip_v26.0.0_R8_Autotuner.zip. Licencia del código: MIT (Mesa).
 * Los drivers "Qualcomm v615/v840" de ese mismo repo NO son Mesa:
 * son binarios propietarios extraídos de otros dispositivos y NO se
 * pueden empaquetar como driver libre.
 *
 * SHA-256 VERIFICADO (ronda 0.2.64, 2026-11-11, calculado sobre el
 * asset descargado de la release v26.0.0-rc08; no hay Turnip K11MCH1
 * más nuevo que la R8: las releases posteriores — v840/v837 del
 * 2026-01-14 — son drivers propietarios Qualcomm, no Turnip):
 * Turnip_v26.0.0_R8_Gmem.zip = 3.478.156 bytes, SHA-256
 * b60bffe2844a271cd293011de307fe06d28549bd5b93d03749b7c9f39af4114b.
 * Layout VERIFICADO del zip: meta.json en la raíz (305 bytes,
 * libraryName "vulkan.ad07xx.so", nombre "Mesa Turnip driver
 * v26.0.0 - R8 GMEM", driverVersion "Vulkan 1.4.335", minApi 27) +
 * vulkan.ad07xx.so (17.159.433 bytes). La validación estructural
 * (meta.json en raíz + .so con el libraryName que declara) coincide
 * con este layout real.
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
 *   `custom-driver-name` de config.yml. El valor exacto de esa clave
 *   es el STEM del zip (sin .zip, p. ej. "Turnip_v26.0.0_R8_Gmem"),
 *   verificado contra el núcleo (pin 3e43bc80, native_drivers.cpp:
 *   installCustomDriver usa archive_path.filename().stem()). El
 *   núcleo busca el driver ya EXTRAÍDO en
 *   `<filesDir>/driver/<nombre>/` (meta.json o driver_name.txt);
 *   el zip guardado en `<filesDir>/vita3k/drivers/turnip/` es solo
 *   la copia del launcher. El launcher instala el driver en el
 *   núcleo con installCustomDriver al instalar el zip y al lanzar
 *   el juego (ver Vita3kCoreBridge + Vita3KLauncher). Este archivo
 *   NO carga ningún .so: solo selecciona, valida e instala el zip.
 * - La R8 es de enero de 2026 y ya no es la Turnip más reciente en
 *   general (otros constructores publican revisiones más nuevas:
 *   MrPurple T30, 2026-08-17; whitebelyash v32, 2026-09-19). NO se
 *   cambia de constructor sin prueba en teléfono: la R8 sigue siendo
 *   la verificada (SHA arriba). Si se cambia, hay que re-verificar
 *   la release vigente y su SHA-256, y rellenar EXPECTED_SHA256.
 * - Un mismo zip estándar NO cubre todos los Adreno: hay variantes
 *   por generación (ver selectVariant). El layout del zip R8 YA está
 *   verificado (ver arriba): la validación estructural coincide con
 *   el zip real. Si la validación rechaza el zip incluido, la
 *   instalación manual por selector sigue funcionando igual que hoy
 *   (sin validación).
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
     * SHA-256 (hex, minúsculas) del zip incluido exacto. Verificado
     * 2026-11-11 contra el asset de la release v26.0.0-rc08
     * (3.478.156 bytes; ver KDoc del objeto). Mientras el binario no
     * esté en assets, la validación es solo estructural (zip +
     * meta.json + .so) y el log avisa. Con el binario puesto, un zip
     * distinto (otra revisión, corrupto, manipulado) se rechaza.
     */
    const val EXPECTED_SHA256 = "b60bffe2844a271cd293011de307fe06d28549bd5b93d03749b7c9f39af4114b"

    /** Tamaño máximo aceptado para el paquete (el zip real son ~3,5 MB). */
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
     *   and hacky, use at your own risk" y lleva build aparte
     *   (turnip_a8xx.zip, 2.477.437 bytes, en la misma release
     *   v26.0.0-rc08): NO incluido en la APK. El usuario puede seguir
     *   instalando un zip a mano con el selector, pero no se
     *   auto-instala.
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
    /**
     * Valida un zip de driver (estructura + SHA-256 si se conoce).
     * [expectedSha256] es el hash esperado; por defecto
     * EXPECTED_SHA256 (el del zip R8 real, verificado 2026-11-11):
     * un zip estructuralmente válido pero con otro hash se rechaza.
     * Los tests pasan "" para probar solo la estructura con zips
     * falsos (su hash nunca coincide con el real).
     */
    fun validatePackage(zipFile: File, expectedSha256: String = EXPECTED_SHA256): Result<PackageInfo> {
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
                if (expectedSha256.isNotBlank() && !hash.equals(expectedSha256, ignoreCase = true)) {
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
