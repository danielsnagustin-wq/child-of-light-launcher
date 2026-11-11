package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.Vita3kConfigGenerator
import com.danielsnagustin.childoflight.launcher.model.AudioBackend
import com.danielsnagustin.childoflight.launcher.model.GameLanguage
import com.danielsnagustin.childoflight.launcher.model.GpuBackend
import com.danielsnagustin.childoflight.launcher.model.GpuDriver
import com.danielsnagustin.childoflight.launcher.model.GpuInfo
import com.danielsnagustin.childoflight.launcher.model.GpuProfileId
import com.danielsnagustin.childoflight.launcher.model.GpuVendor
import com.danielsnagustin.childoflight.launcher.model.LauncherSettings
import com.danielsnagustin.childoflight.launcher.model.MemoryMapping
import com.danielsnagustin.childoflight.launcher.model.PerformanceOverlayDetail
import com.danielsnagustin.childoflight.launcher.model.PerformanceOverlayPosition
import com.danielsnagustin.childoflight.launcher.model.ScreenFilter
import com.danielsnagustin.childoflight.launcher.model.presetSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * El generador solo emite claves verificadas en config.h de Vita3K.
 * Los ajustes sin clave verificada no se emiten en absoluto: no
 * existen en el modelo de ajustes (se eliminaron por no tener
 * efecto real, auditoría de funcionalidad 2026-11-08).
 */
class Vita3kConfigGeneratorTest {

    @Test
    fun g99RecommendedEmitsVerifiedKeys() {
        val yaml = Vita3kConfigGenerator.generate(presetSettings(GpuProfileId.G99_RECOMMENDED), "/data/vita3k")
        assertTrue(yaml.contains("backend-renderer: \"Vulkan\""))
        assertTrue(yaml.contains("resolution-multiplier: 1.0"))
        assertTrue(yaml.contains("screen-filter: \"Bilinear\""))
        assertTrue(yaml.contains("v-sync: false"))
        assertTrue(yaml.contains("anisotropic-filtering: 1"))
        assertTrue(yaml.contains("async-pipeline-compilation: true"))
        assertTrue(yaml.contains("high-accuracy: false"))
        // Surface Sync Disabled -> disable-surface-sync: true (inverso verificado).
        assertTrue(yaml.contains("disable-surface-sync: true"))
        assertTrue(yaml.contains("memory-mapping: \"double-buffer\""))
        assertTrue(yaml.contains("audio-backend: \"SDL\""))
        assertTrue(yaml.contains("audio-volume: 100"))
        assertTrue(yaml.contains("ngs-enable: true"))
        assertTrue(yaml.contains("cpu-opt: true"))
        assertTrue(yaml.contains("fps-hack: false"))
        assertTrue(yaml.contains("performance-overlay: false"))
    }

    @Test
    fun fpsHackForces60FpsPackage() {
        // Base adversa: 2x, FSR, sin VSync, page-table, cachés OFF (como el
        // perfil de gama baja): el modo 60 debe imponer su paquete completo.
        val yaml = Vita3kConfigGenerator.generate(
            LauncherSettings(
                resolutionMultiplier = 2f,
                screenFilter = ScreenFilter.FSR,
                vSync = false,
                memoryMapping = MemoryMapping.PAGE_TABLE,
                textureCache = false,
                shaderCache = false,
                fpsHack = true,
            ), "/data/vita3k",
        )
        assertTrue(yaml.contains("fps-hack: true"))
        assertTrue(yaml.contains("v-sync: true"))
        assertTrue(yaml.contains("resolution-multiplier: 1.0"))
        assertTrue(yaml.contains("screen-filter: \"Bilinear\""))
        assertTrue(yaml.contains("memory-mapping: \"double-buffer\""))
        assertTrue(yaml.contains("texture-cache: true"))
        assertTrue(yaml.contains("shader-cache: true"))
        assertTrue(yaml.contains("async-pipeline-compilation: true"))
        assertTrue(yaml.contains("high-accuracy: false"))
        assertTrue(yaml.contains("disable-surface-sync: true"))
        assertTrue(yaml.contains("cpu-opt: true"))
        assertTrue(yaml.contains("ngs-enable: true"))
    }

    @Test
    fun hashlessTextureCacheDefaultsOffAndEmitsWhenEnabled() {
        // Default (preset G99): OFF, como en upstream (default false,
        // config.h:308). El consumo está verificado en el renderer
        // (texture/cache.cpp): true = use_protect, las texturas grandes
        // se vigilan por protección de páginas en vez de re-hashear.
        val yamlDefault = Vita3kConfigGenerator.generate(presetSettings(GpuProfileId.G99_RECOMMENDED), "/data/vita3k")
        assertTrue(yamlDefault.contains("hashless-texture-cache: false"))
        val yamlOn = Vita3kConfigGenerator.generate(
            LauncherSettings(hashlessTextureCache = true), "/data/vita3k",
        )
        assertTrue(yamlOn.contains("hashless-texture-cache: true"))
    }

    @Test
    fun performanceOverlayDetailAndPositionEmitted() {
        // Defaults de upstream (config.h:362,364): MINIMUM = 0,
        // TOP_LEFT = 0.
        val yamlDefault = Vita3kConfigGenerator.generate(presetSettings(GpuProfileId.G99_RECOMMENDED), "/data/vita3k")
        assertTrue(yamlDefault.contains("performance-overlay-detail: 0"))
        assertTrue(yamlDefault.contains("performance-overlay-position: 0"))
        // Base adversa: máximo detalle, esquina opuesta.
        val yamlMax = Vita3kConfigGenerator.generate(
            LauncherSettings(
                showFpsOverlay = true,
                performanceOverlayDetail = PerformanceOverlayDetail.MAXIMUM,
                performanceOverlayPosition = PerformanceOverlayPosition.BOTTOM_RIGHT,
            ), "/data/vita3k",
        )
        assertTrue(yamlMax.contains("performance-overlay: true"))
        assertTrue(yamlMax.contains("performance-overlay-detail: 3"))
        assertTrue(yamlMax.contains("performance-overlay-position: 5"))
    }

    @Test
    fun compileShadersHiddenByDefault() {
        // El launcher oculta los mensajes por defecto (pantalla limpia),
        // aunque upstream los muestre por defecto (config.h:306, true).
        val yamlDefault = Vita3kConfigGenerator.generate(presetSettings(GpuProfileId.G99_RECOMMENDED), "/data/vita3k")
        assertTrue(yamlDefault.contains("show-compile-shaders: false"))
        val yamlShown = Vita3kConfigGenerator.generate(
            LauncherSettings(showCompileShaders = true), "/data/vita3k",
        )
        assertTrue(yamlShown.contains("show-compile-shaders: true"))
    }

    @Test
    fun surfaceSyncEnabledInvertsDisableFlag() {
        val yaml = Vita3kConfigGenerator.generate(
            LauncherSettings(surfaceSyncEnabled = true), "/data/vita3k",
        )
        assertTrue(yaml.contains("disable-surface-sync: false"))
    }

    @Test
    fun removedUnverifiedSettingsAreNeverEmitted() {
        val yaml = Vita3kConfigGenerator.generate(presetSettings(GpuProfileId.G99_RECOMMENDED), "/data/vita3k")
        // Los ajustes sin clave verificada se eliminaron del modelo:
        // no debe existir ninguna línea "clave: valor" ni mención
        // para ellos en el YAML generado.
        assertFalse(yaml.lines().any { it.startsWith("frame-limiter:") })
        assertFalse(yaml.lines().any { it.startsWith("frame-skipping:") })
        assertFalse(yaml.lines().any { it.startsWith("accurate-thread-scheduling:") })
        assertFalse(yaml.lines().any { it.startsWith("audio-buffer-size:") })
        assertFalse(yaml.contains("TODO-VERIFY"))
        assertFalse(yaml.contains("frame-limiter"))
    }

    @Test
    fun quality2xEmitsResolution20() {
        val yaml = Vita3kConfigGenerator.generate(presetSettings(GpuProfileId.G99_QUALITY), "/data/vita3k")
        assertTrue(yaml.contains("resolution-multiplier: 2.0"))
        assertTrue(yaml.contains("screen-filter: \"FSR\""))
    }

    @Test
    fun safeModeForcesSafeValues() {
        val yaml = Vita3kConfigGenerator.generate(
            LauncherSettings(safeMode = true, resolutionMultiplier = 4f), "/data/vita3k",
        )
        assertTrue(yaml.contains("resolution-multiplier: 1.0"))
        assertTrue(yaml.contains("audio-backend: \"SDL\""))
        assertTrue(yaml.contains("MODO SEGURO"))
    }

    // --- Compatibilidad por GPU (auditoría 2026-11-07) ---

    @Test
    fun turnipIsOnlyEmittedWhenDriverIsTurnip() {
        // Bug real: antes bastaba turnipInstalled + nombre guardado para
        // emitir custom-driver-name, así que cambiar a "Sistema" no
        // desactivaba Turnip. Ahora manda gpuDriver.
        val installed = LauncherSettings(
            gpuDriver = GpuDriver.SYSTEM,
            turnipInstalled = true,
            customDriverName = "turnip-24.1.0",
        )
        val yamlSystem = Vita3kConfigGenerator.generate(installed, "/data/vita3k")
        assertTrue(yamlSystem.contains("custom-driver-name: \"\""))
        assertFalse(yamlSystem.contains("turnip-24.1.0"))

        val active = installed.copy(gpuDriver = GpuDriver.TURNIP)
        val yamlTurnip = Vita3kConfigGenerator.generate(active, "/data/vita3k")
        assertTrue(yamlTurnip.contains("custom-driver-name: \"turnip-24.1.0\""))
    }

    @Test
    fun memoryMappingStringsAreVerifiedUpstream() {
        // Verificado contra el núcleo (pin 3e43bc80, renderer.cpp
        // VKState::late_init): los 4 strings se comparan exactos;
        // "off" cae en Disabled por fall-through. Ninguno debe salir
        // como TODO-VERIFY.
        val pageTable = Vita3kConfigGenerator.generate(
            LauncherSettings(memoryMapping = MemoryMapping.PAGE_TABLE), "/data/vita3k",
        )
        assertTrue(pageTable.contains("memory-mapping: \"page-table\""))
        val nativeBuffer = Vita3kConfigGenerator.generate(
            LauncherSettings(memoryMapping = MemoryMapping.NATIVE_BUFFER), "/data/vita3k",
        )
        assertTrue(nativeBuffer.contains("memory-mapping: \"native-buffer\""))
        val externalHost = Vita3kConfigGenerator.generate(
            LauncherSettings(memoryMapping = MemoryMapping.EXTERNAL_HOST), "/data/vita3k",
        )
        assertTrue(externalHost.contains("memory-mapping: \"external-host\""))
        val off = Vita3kConfigGenerator.generate(
            LauncherSettings(memoryMapping = MemoryMapping.OFF), "/data/vita3k",
        )
        assertTrue(off.contains("memory-mapping: \"off\""))
        assertFalse(pageTable.contains("TODO-VERIFY: string exacto de memory-mapping"))
    }

    @Test
    fun cubebAudioBackendIsVerifiedUpstream() {
        // Verificado contra el núcleo (pin 3e43bc80, audio.cpp
        // AudioState::set_backend): acepta exactamente "SDL" y "Cubeb".
        val yaml = Vita3kConfigGenerator.generate(
            LauncherSettings(audioBackend = AudioBackend.CUBEB), "/data/vita3k",
        )
        assertTrue(yaml.contains("audio-backend: \"Cubeb\""))
        assertFalse(yaml.contains("TODO-VERIFY: string exacto de Cubeb"))
    }

    @Test
    fun vulkanFallsBackToOpenGlWhenPhoneHasNoVulkan() {
        val noVulkan = GpuInfo(vendor = GpuVendor.MALI, renderer = "Mali-G57 MC2", vulkanSupported = false)
        val yaml = Vita3kConfigGenerator.generate(
            LauncherSettings(backend = GpuBackend.VULKAN), "/data/vita3k", noVulkan,
        )
        assertTrue(yaml.contains("backend-renderer: \"OpenGL\""))
        assertFalse(yaml.contains("backend-renderer: \"Vulkan\""))

        val withVulkan = noVulkan.copy(vulkanSupported = true, vulkanVersionLabel = "Vulkan 1.1+")
        val yamlVulkan = Vita3kConfigGenerator.generate(
            LauncherSettings(backend = GpuBackend.VULKAN), "/data/vita3k", withVulkan,
        )
        assertTrue(yamlVulkan.contains("backend-renderer: \"Vulkan\""))
    }

    @Test
    fun showWelcomeFalseAlwaysEmitted() {
        // Verificado en el pin (config.h:380): show-welcome existe,
        // bool, default true. El launcher siempre emite false: es un
        // port, no muestra la bienvenida del emulador. Su único
        // consumo verificado es la GUI Qt (main_window.cpp:1031-1037);
        // en Android/SDL ni siquiera se ejecuta ese diálogo.
        val yaml = Vita3kConfigGenerator.generate(
            LauncherSettings(), "/data/vita3k",
        )
        assertTrue(yaml.contains("show-welcome: false"))
        assertFalse(yaml.contains("show-welcome: true"))
    }

    @Test
    fun displayInfoMessageNeverEmittedAsRealKey() {
        // display-info-message NO existe en config.h (lectura completa
        // 0-465, pin 3e43bc80): nunca debe salir, ni como clave real
        // ni como comentario.
        val yaml = Vita3kConfigGenerator.generate(
            LauncherSettings(), "/data/vita3k",
        )
        assertFalse(yaml.contains("display-info-message: "))
        assertFalse(yaml.contains("display-info-message"))
    }

    @Test
    fun psnSignedInFollowsSetting() {
        // Verificado en el pin (config.h:398): psn-signed-in existe,
        // es INT, default false. Lo consumen sceNpCheckCallback y
        // sceNpGetServiceState (SceNpManager.cpp:131 y :149): con 0
        // el núcleo responde siempre SIGNED_OUT y los juegos que
        // preguntan el estado de sesión antes de activar sus DLC
        // nunca los aplican. El launcher emite 1 con el ajuste ON
        // (por defecto) y 0 con el ajuste OFF (Ajustes → General →
        // Motor → "Sesión PSN (simulada)"): inicio de sesión
        // EMULADO, sin cuenta real ni red. Es int, no bool: se
        // emite "1" o "0", nunca "true".
        val on = Vita3kConfigGenerator.generate(
            LauncherSettings(), "/data/vita3k",
        )
        assertTrue(on.contains("psn-signed-in: 1"))
        assertFalse(on.contains("psn-signed-in: 0"))
        assertFalse(on.contains("psn-signed-in: true"))
        val off = Vita3kConfigGenerator.generate(
            LauncherSettings(psnSignedIn = false), "/data/vita3k",
        )
        assertTrue(off.contains("psn-signed-in: 0"))
        assertFalse(off.contains("psn-signed-in: 1"))
    }

    @Test
    fun psnSignedInComesFromEffectiveSettings() {
        // R4-P2-10: psn-signed-in se emite desde el effective (s), no
        // desde settings directo. Hoy ninguna transformación lo toca
        // (ni modo seguro ni 60 FPS), así que el valor pasa igual, pero
        // el generador debe seguir emitiéndolo bajo ambas
        // transformaciones: es la guarda de regresión de la
        // unificación en el effective.
        val safeOn = Vita3kConfigGenerator.generate(
            LauncherSettings(safeMode = true, psnSignedIn = true), "/data/vita3k",
        )
        assertTrue(safeOn.contains("psn-signed-in: 1"))
        val fpsOff = Vita3kConfigGenerator.generate(
            LauncherSettings(fpsHack = true, psnSignedIn = false), "/data/vita3k",
        )
        assertTrue(fpsOff.contains("psn-signed-in: 0"))
        assertFalse(fpsOff.contains("psn-signed-in: 1"))
    }

    @Test
    fun gameLanguageDefaultsToSpanish() {
        // 0.2.63 (E): sys-lang VERIFICADO en el pin (config.h:172):
        // int, default ENGLISH_US (1). Valores del enum
        // SceSystemParamLang (util/system.h): SPANISH 3, ENGLISH_US 1,
        // ENGLISH_GB 18, FRENCH 2, GERMAN 4, ITALIAN 5. El default
        // del launcher es ESPAÑOL (3): el juego EU PCSB00598 trae
        // español y con 3 pide sus textos y subtítulos en español
        // (.es.srt) en vez de los .en.srt que pedía en inglés.
        val yaml = Vita3kConfigGenerator.generate(
            LauncherSettings(), "/data/vita3k",
        )
        assertTrue(yaml.contains("sys-lang: 3"))
        assertFalse(yaml.contains("sys-lang: 1"))
    }

    @Test
    fun gameLanguageFollowsSetting() {
        // 0.2.63 (E): el ajuste (Ajustes → General → Motor →
        // "Idioma del juego") decide el valor emitido: inglés
        // americano 1, inglés británico 18, francés 2. Es int, no
        // string: se emite el número, nunca el nombre.
        val us = Vita3kConfigGenerator.generate(
            LauncherSettings(gameLanguage = GameLanguage.ENGLISH_US), "/data/vita3k",
        )
        assertTrue(us.contains("sys-lang: 1"))
        assertFalse(us.contains("sys-lang: 3"))
        val gb = Vita3kConfigGenerator.generate(
            LauncherSettings(gameLanguage = GameLanguage.ENGLISH_GB), "/data/vita3k",
        )
        assertTrue(gb.contains("sys-lang: 18"))
        val fr = Vita3kConfigGenerator.generate(
            LauncherSettings(gameLanguage = GameLanguage.FRENCH), "/data/vita3k",
        )
        assertTrue(fr.contains("sys-lang: 2"))
    }

    @Test
    fun prefPathIsVitaFsNotStorageRoot() {
        // Bug -3 (2026-11-08): pref-path ES el vita_fs en upstream
        // (config.h:182 del pin 3e43bc80: pref-path es
        // cfg.vita_fs_path; app_init.cpp:434-440 lo usa como
        // state.vita_fs_path). El vita_fs del núcleo es
        // <vitaRoot>/vita (initialize_session). Emitir la raíz hacía
        // que en un proceso fresco el núcleo escaneara el ux0
        // antiguo (vacío) y loadGame devolviera -3 aunque el juego
        // estuviera instalado en vita/ux0.
        val root = java.io.File("/data/vita3k")
        val vitaFs = Vita3kConfigGenerator.vitaFsPathFor(root)
        assertTrue(vitaFs.absolutePath.endsWith("/vita"))
        val yaml = Vita3kConfigGenerator.generate(
            LauncherSettings(), vitaFs.absolutePath,
        )
        assertTrue(yaml.contains("pref-path: \"${vitaFs.absolutePath}\""))
        // La ruta SIN /vita no debe aparecer como valor completo
        // (con comillas de cierre): es prefijo de la buena, así que
        // se comprueba con el cierre de comillas.
        assertFalse(yaml.contains("pref-path: \"${root.absolutePath}\"\n"))
    }

    // R4-P0-7 + R4-P2-4: writeConfig() idempotente. JUGAR escribía el
    // mismo config.yml 3 veces por segundo; la red de seguridad es no
    // reescribir si el contenido es idéntico.

    private fun tempConfigFile(): java.io.File {
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "col-r4p07-${System.nanoTime()}")
        dir.mkdirs()
        return java.io.File(dir, "config.yml")
    }

    @Test
    fun writeConfigIfChangedWritesWhenFileIsMissing() {
        val file = tempConfigFile()
        assertFalse(file.exists())
        assertTrue(Vita3kConfigGenerator.writeConfigIfChanged(file, "v-sync: false\n"))
        assertEquals("v-sync: false\n", file.readText())
    }

    @Test
    fun writeConfigIfChangedSkipsIdenticalContent() {
        val file = tempConfigFile()
        assertTrue(Vita3kConfigGenerator.writeConfigIfChanged(file, "v-sync: false\n"))
        val firstModified = file.lastModified()
        Thread.sleep(5)
        assertFalse(Vita3kConfigGenerator.writeConfigIfChanged(file, "v-sync: false\n"))
        assertEquals("v-sync: false\n", file.readText())
        assertEquals(firstModified, file.lastModified())
    }

    @Test
    fun writeConfigIfChangedRewritesWhenContentDiffers() {
        val file = tempConfigFile()
        assertTrue(Vita3kConfigGenerator.writeConfigIfChanged(file, "v-sync: false\n"))
        assertTrue(Vita3kConfigGenerator.writeConfigIfChanged(file, "v-sync: true\n"))
        assertEquals("v-sync: true\n", file.readText())
    }

    @Test
    fun writeConfigIfChangedCreatesMissingParentDirs() {
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "col-r4p07-nested-${System.nanoTime()}/a/b")
        val file = java.io.File(dir, "config.yml")
        assertFalse(dir.exists())
        assertTrue(Vita3kConfigGenerator.writeConfigIfChanged(file, "v-sync: false\n"))
        assertEquals("v-sync: false\n", file.readText())
    }

    // R5-A3-9: writeConfigIfChanged con fichero ilegible y con
    // config.yml como directorio.

    @Test
    fun writeConfigIfChangedWritesWhenExistingFileIsUnreadable() {
        // Si readText() falla (permiso), existing queda null y se
        // intenta escribir: el fallo de lectura no bloquea la
        // escritura (el fichero sigue siendo escribible). En sistemas
        // que no honran el permiso (root), readText lee y el
        // contenido difiere, así que el resultado es el mismo:
        // escribe y devuelve true.
        val file = tempConfigFile()
        assertTrue(Vita3kConfigGenerator.writeConfigIfChanged(file, "v-sync: false\n"))
        file.setReadable(false, false)
        try {
            assertTrue(Vita3kConfigGenerator.writeConfigIfChanged(file, "v-sync: true\n"))
        } finally {
            file.setReadable(true, false)
        }
        assertEquals("v-sync: true\n", file.readText())
    }

    @Test
    fun writeConfigIfChangedThrowsWhenConfigYmlIsADirectory() {
        // R6-P2-29: config.yml como directorio es un error EXPLÍCITO
        // y documentado (IllegalStateException con la ruta), no el
        // FileNotFoundException incidental de writeText de antes. Los
        // llamadores lo aguantan con runCatching/catch (Throwable) y
        // muestran bloqueo, no crash.
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "col-r5a39-dir-${System.nanoTime()}/config.yml")
        assertTrue(dir.mkdirs())
        try {
            assertThrows(IllegalStateException::class.java) {
                Vita3kConfigGenerator.writeConfigIfChanged(dir, "v-sync: false\n")
            }
        } finally {
            dir.delete()
        }
    }

    // R5-A3-10: un solo tipo incorrecto en config.yml descarta TODOS
    // los ajustes en silencio (el parse del núcleo aborta entero).

    @Test
    fun everyEmittedLineHasTheYamlTypeTheCoreExpects() {
        // Regresión: cada línea emitida tiene el tipo YAML que
        // config.h declara para esa clave. En especial psn-signed-in
        // es INT (1/0), nunca true/false. No hay validación de
        // re-lectura YAML en writeConfig (no hay librería YAML en la
        // app): solo este test.
        val expectedTypes = mapOf(
            "backend-renderer" to "string",
            "resolution-multiplier" to "float",
            "screen-filter" to "string",
            "v-sync" to "bool",
            "anisotropic-filtering" to "int",
            "async-pipeline-compilation" to "bool",
            "high-accuracy" to "bool",
            "disable-surface-sync" to "bool",
            "memory-mapping" to "string",
            "texture-cache" to "bool",
            "shader-cache" to "bool",
            "custom-driver-name" to "string",
            "cpu-opt" to "bool",
            "fps-hack" to "bool",
            "file-loading-delay" to "int",
            "audio-backend" to "string",
            "audio-volume" to "int",
            "ngs-enable" to "bool",
            "performance-overlay" to "bool",
            "performance-overlay-detail" to "int",
            "performance-overlay-position" to "int",
            "hashless-texture-cache" to "bool",
            "show-compile-shaders" to "bool",
            "show-welcome" to "bool",
            "psn-signed-in" to "int",
            "pref-path" to "string",
        )
        val yaml = Vita3kConfigGenerator.generate(LauncherSettings(psnSignedIn = true), "/data/vita3k")
        for (line in yaml.lines()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val key = line.substringBefore(":")
            val value = line.substringAfter(":").trim()
            val expected = expectedTypes[key] ?: fail("config.yml emite una clave sin tipo clasificado en este test: $key")
            when (expected) {
                "string" -> assertTrue("'$key' debe ser string entrecomillado, fue: $value", value.startsWith("\"") && value.endsWith("\""))
                "bool" -> assertTrue("'$key' debe ser bool, fue: $value", value == "true" || value == "false")
                "int" -> assertTrue("'$key' debe ser int, fue: $value", value.matches(Regex("-?\\d+")))
                "float" -> assertTrue("'$key' debe ser float, fue: $value", value.matches(Regex("-?\\d+\\.\\d+")))
                else -> fail("tipo desconocido en este test: $expected")
            }
        }
        assertTrue(yaml.contains("psn-signed-in: 1"))
        assertFalse(yaml.contains("psn-signed-in: true"))
        // Con el ajuste OFF, 0 (nunca false).
        val off = Vita3kConfigGenerator.generate(LauncherSettings(psnSignedIn = false), "/data/vita3k")
        assertTrue(off.contains("psn-signed-in: 0"))
        assertFalse(off.contains("psn-signed-in: false"))
        // Todas las claves clasificadas se emiten (el test no deja
        // pasar una clave que desaparezca sin avisar).
        for (key in expectedTypes.keys) {
            assertTrue("config.yml ya no emite '$key'", yaml.lines().any { it.startsWith("$key:") })
        }
    }
}
