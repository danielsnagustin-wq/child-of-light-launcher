package com.danielsnagustin.childoflight.launcher.data

import com.danielsnagustin.childoflight.launcher.model.AccentColor
import com.danielsnagustin.childoflight.launcher.model.AppLanguage
import com.danielsnagustin.childoflight.launcher.model.AudioBackend
import com.danielsnagustin.childoflight.launcher.model.CardSize
import com.danielsnagustin.childoflight.launcher.model.CardTransparency
import com.danielsnagustin.childoflight.launcher.model.GpuBackend
import com.danielsnagustin.childoflight.launcher.model.GpuDriver
import com.danielsnagustin.childoflight.launcher.model.GpuProfileId
import com.danielsnagustin.childoflight.launcher.model.HomeBackground
import com.danielsnagustin.childoflight.launcher.model.IconSize
import com.danielsnagustin.childoflight.launcher.model.LauncherSettings
import com.danielsnagustin.childoflight.launcher.model.MemoryMapping
import com.danielsnagustin.childoflight.launcher.model.PerformanceOverlayDetail
import com.danielsnagustin.childoflight.launcher.model.PerformanceOverlayPosition
import com.danielsnagustin.childoflight.launcher.model.ScreenFilter
import com.danielsnagustin.childoflight.launcher.model.TextSize
import com.danielsnagustin.childoflight.launcher.model.ThemeMode
import com.danielsnagustin.childoflight.launcher.model.UiPreferences

/**
 * Copia de seguridad de ajustes (Ola 3, Etapa 9a). Puro, testeable en
 * JVM (SettingsBackupTest): sin Compose, sin Android, sin librería
 * JSON externa (org.json no se puede probar en unit tests JVM: sus
 * métodos son stubs). Por eso el JSON es un objeto plano con un
 * parser mínimo propio (strings, números, booleanos, null).
 *
 * Formato: {"format":"col-settings-backup","version":1, ...campos...}.
 * Al importar se aplican los MISMOS clamps que ya existen en
 * SettingsRepository (resolución 1-4, aniso válido, opacidad 0.1-1,
 * volumen 0-100, retraso 0-30 ms): un
 * archivo editado a mano no puede pasar valores fuera de rango al
 * config.yml. Un archivo inválido devuelve failure con un mensaje
 * claro y NO se aplica nada parcial: la decodificación construye los
 * dos objetos completos antes de que el ViewModel guarde nada.
 *
 * NO se respaldan estados que describen archivos o el sistema, no
 * preferencias: pkgDeliveredToVita3k / pkgInstalledInVita3k (confirman
 * una instalación real en la app externa), customDriverName /
 * turnipVersion / turnipInstalled (driver instalado en disco).
 * Al restaurar, esos campos se conservan como estén en el teléfono.
 *
 * R5-A2-8: el modo seguro NUNCA se exporta: al exportar se fuerza
 * safeMode=false en el JSON (ver exportSettingsBackup en el
 * ViewModel). Un backup exportado con el modo seguro ON guardaba los
 * valores forzados 1x/SDL como ajustes normales y ningún snapshot:
 * al importar, safeMode=true quedaba persistido sin copia que
 * restaurar y al desactivarlo los valores previos se perdían. Al
 * importar, si el JSON trae safeMode=true (copias viejas), se trata
 * como false y el mensaje de importación lo dice (ver
 * backupJsonHasSafeModeEnabled). Los snapshots internos (modo seguro,
 * "Corregir audio") NO pasan por aquí: readSettingsSnapshot usa
 * decodeSettingsBackup directamente y conserva safeMode tal cual.
 */
const val SETTINGS_BACKUP_FORMAT = "col-settings-backup"
const val SETTINGS_BACKUP_VERSION = 1

fun encodeSettingsBackup(settings: LauncherSettings, ui: UiPreferences): String {
    val fields = mutableListOf<String>()
    fun str(key: String, value: String) { fields += "\"$key\":${jsonString(value)}" }
    fun bool(key: String, value: Boolean) { fields += "\"$key\":$value" }
    fun num(key: String, value: Number) { fields += "\"$key\":$value" }
    str("format", SETTINGS_BACKUP_FORMAT)
    num("version", SETTINGS_BACKUP_VERSION)
    str("backend", settings.backend.name)
    num("resolutionMultiplier", settings.resolutionMultiplier)
    str("screenFilter", settings.screenFilter.name)
    num("anisotropicFiltering", settings.anisotropicFiltering)
    bool("vSync", settings.vSync)
    bool("asyncPipelineCompilation", settings.asyncPipelineCompilation)
    bool("highAccuracy", settings.highAccuracy)
    bool("surfaceSyncEnabled", settings.surfaceSyncEnabled)
    str("memoryMapping", settings.memoryMapping.name)
    bool("textureCache", settings.textureCache)
    bool("shaderCache", settings.shaderCache)
    bool("cpuOpt", settings.cpuOpt)
    bool("fpsHack", settings.fpsHack)
    num("fileLoadingDelay", settings.fileLoadingDelay)
    num("audioVolume", settings.audioVolume)
    str("audioBackend", settings.audioBackend.name)
    bool("ngsEnable", settings.ngsEnable)
    bool("showFpsOverlay", settings.showFpsOverlay)
    str("performanceOverlayDetail", settings.performanceOverlayDetail.name)
    str("performanceOverlayPosition", settings.performanceOverlayPosition.name)
    bool("hashlessTextureCache", settings.hashlessTextureCache)
    bool("showCompileShaders", settings.showCompileShaders)
    str("gpuDriver", settings.gpuDriver.name)
    str("activeProfile", settings.activeProfile.name)
    bool("autoRevertTo1xIfBelow28Fps", settings.autoRevertTo1xIfBelow28Fps)
    bool("performanceMode", settings.performanceMode)
    bool("safeMode", settings.safeMode)
    bool("closeBackgroundApps", settings.closeBackgroundApps)
    bool("allowExternalVita3k", settings.allowExternalVita3k)
    bool("psnSignedIn", settings.psnSignedIn)
    bool("hideTouchWhenGamepadConnected", settings.hideTouchWhenGamepadConnected)
    bool("showTouchOverlayInGame", settings.showTouchOverlayInGame)
    num("touchOverlayOpacity", settings.touchOverlayOpacity)
    bool("touchOverlayNeonGlow", settings.touchOverlayNeonGlow)
    str("touchOverlayHiddenProfileIds", settings.touchOverlayHiddenProfileIds.joinToString(","))
    str("theme", ui.theme.name)
    str("accent", ui.accent.name)
    str("homeBackground", ui.homeBackground.name)
    str("textSize", ui.textSize.name)
    str("language", ui.language.name)
    str("sectionOrder", ui.sectionOrder.joinToString(",") { it.name })
    str("hiddenPresets", ui.hiddenPresets.joinToString(",") { it.name })
    str("cardSize", ui.cardSize.name)
    str("cardTransparency", ui.cardTransparency.name)
    str("iconSize", ui.iconSize.name)
    return "{${fields.joinToString(",")}}"
}

fun decodeSettingsBackup(
    json: String,
    current: LauncherSettings,
    currentUi: UiPreferences,
): Result<Pair<LauncherSettings, UiPreferences>> {
    val map = parseFlatJsonObject(json)
        .getOrElse { return Result.failure(IllegalArgumentException("Archivo de copia de seguridad inválido: no es un JSON válido (${it.message}). No se aplicó nada.")) }
    if (map["format"] != SETTINGS_BACKUP_FORMAT) {
        return Result.failure(IllegalArgumentException("Archivo de copia de seguridad inválido: no es una copia de ajustes de este launcher. No se aplicó nada."))
    }
    if (map["version"]?.toIntOrNull() != SETTINGS_BACKUP_VERSION) {
        return Result.failure(IllegalArgumentException("Archivo de copia de seguridad inválido: versión no soportada (${map["version"] ?: "sin versión"}). No se aplicó nada."))
    }
    // Enum desconocido -> default del modelo (igual que al leer DataStore).
    fun <T : Enum<T>> enumValue(raw: String?, values: Array<T>, default: T): T =
        raw?.let { name -> values.firstOrNull { it.name == name } } ?: default
    fun boolValue(raw: String?, default: Boolean): Boolean =
        when (raw?.lowercase()) { "true" -> true; "false" -> false; else -> default }
    fun intValue(raw: String?, default: Int): Int = raw?.toIntOrNull() ?: default
    fun floatValue(raw: String?, default: Float): Float {
        val parsed = raw?.toFloatOrNull() ?: return default
        return if (parsed.isFinite()) parsed else default
    }
    val settings = current.copy(
        backend = enumValue(map["backend"], GpuBackend.entries.toTypedArray(), current.backend),
        resolutionMultiplier = SettingsRepository.clampResolution(floatValue(map["resolutionMultiplier"], current.resolutionMultiplier)),
        screenFilter = enumValue(map["screenFilter"], ScreenFilter.entries.toTypedArray(), current.screenFilter),
        anisotropicFiltering = SettingsRepository.clampAniso(intValue(map["anisotropicFiltering"], current.anisotropicFiltering)),
        vSync = boolValue(map["vSync"], current.vSync),
        asyncPipelineCompilation = boolValue(map["asyncPipelineCompilation"], current.asyncPipelineCompilation),
        highAccuracy = boolValue(map["highAccuracy"], current.highAccuracy),
        surfaceSyncEnabled = boolValue(map["surfaceSyncEnabled"], current.surfaceSyncEnabled),
        memoryMapping = enumValue(map["memoryMapping"], MemoryMapping.entries.toTypedArray(), current.memoryMapping),
        textureCache = boolValue(map["textureCache"], current.textureCache),
        shaderCache = boolValue(map["shaderCache"], current.shaderCache),
        cpuOpt = boolValue(map["cpuOpt"], current.cpuOpt),
        fpsHack = boolValue(map["fpsHack"], current.fpsHack),
        fileLoadingDelay = SettingsRepository.clampFileLoadingDelay(intValue(map["fileLoadingDelay"], current.fileLoadingDelay)),
        audioVolume = intValue(map["audioVolume"], current.audioVolume).coerceIn(0, 100),
        audioBackend = enumValue(map["audioBackend"], AudioBackend.entries.toTypedArray(), current.audioBackend),
        ngsEnable = boolValue(map["ngsEnable"], current.ngsEnable),
        showFpsOverlay = boolValue(map["showFpsOverlay"], current.showFpsOverlay),
        performanceOverlayDetail = enumValue(map["performanceOverlayDetail"], PerformanceOverlayDetail.entries.toTypedArray(), current.performanceOverlayDetail),
        performanceOverlayPosition = enumValue(map["performanceOverlayPosition"], PerformanceOverlayPosition.entries.toTypedArray(), current.performanceOverlayPosition),
        hashlessTextureCache = boolValue(map["hashlessTextureCache"], current.hashlessTextureCache),
        showCompileShaders = boolValue(map["showCompileShaders"], current.showCompileShaders),
        gpuDriver = enumValue(map["gpuDriver"], GpuDriver.entries.toTypedArray(), current.gpuDriver),
        activeProfile = enumValue(map["activeProfile"], GpuProfileId.entries.toTypedArray(), current.activeProfile),
        autoRevertTo1xIfBelow28Fps = boolValue(map["autoRevertTo1xIfBelow28Fps"], current.autoRevertTo1xIfBelow28Fps),
        performanceMode = boolValue(map["performanceMode"], current.performanceMode),
        safeMode = boolValue(map["safeMode"], current.safeMode),
        closeBackgroundApps = boolValue(map["closeBackgroundApps"], current.closeBackgroundApps),
        allowExternalVita3k = boolValue(map["allowExternalVita3k"], current.allowExternalVita3k),
        psnSignedIn = boolValue(map["psnSignedIn"], current.psnSignedIn),
        hideTouchWhenGamepadConnected = boolValue(map["hideTouchWhenGamepadConnected"], current.hideTouchWhenGamepadConnected),
        showTouchOverlayInGame = boolValue(map["showTouchOverlayInGame"], current.showTouchOverlayInGame),
        touchOverlayOpacity = SettingsRepository.clampOverlayOpacity(floatValue(map["touchOverlayOpacity"], current.touchOverlayOpacity)),
        touchOverlayNeonGlow = boolValue(map["touchOverlayNeonGlow"], current.touchOverlayNeonGlow),
        touchOverlayHiddenProfileIds = map["touchOverlayHiddenProfileIds"]?.let {
            SettingsRepository.decodeHiddenProfiles(it)
        } ?: current.touchOverlayHiddenProfileIds,
    )
    val ui = currentUi.copy(
        theme = enumValue(map["theme"], ThemeMode.entries.toTypedArray(), currentUi.theme),
        accent = enumValue(map["accent"], AccentColor.entries.toTypedArray(), currentUi.accent),
        homeBackground = enumValue(map["homeBackground"], HomeBackground.entries.toTypedArray(), currentUi.homeBackground),
        textSize = enumValue(map["textSize"], TextSize.entries.toTypedArray(), currentUi.textSize),
        language = enumValue(map["language"], AppLanguage.entries.toTypedArray(), currentUi.language),
        // P2-36: si la clave NO está en el backup, se conserva el valor
        // actual en vez de resetear el campo (una clave ausente antes
        // dejaba sectionOrder en el orden por defecto y hiddenPresets
        // vacío, borrando la personalización del usuario al importar).
        sectionOrder = map["sectionOrder"]?.let { UiPreferencesRepository.decodeSectionOrder(it) } ?: currentUi.sectionOrder,
        hiddenPresets = map["hiddenPresets"]?.let { UiPreferencesRepository.decodeHiddenPresets(it) } ?: currentUi.hiddenPresets,
        cardSize = enumValue(map["cardSize"], CardSize.entries.toTypedArray(), currentUi.cardSize),
        cardTransparency = enumValue(map["cardTransparency"], CardTransparency.entries.toTypedArray(), currentUi.cardTransparency),
        iconSize = enumValue(map["iconSize"], IconSize.entries.toTypedArray(), currentUi.iconSize),
    )
    return Result.success(settings to ui)
}

/**
 * R5-A2-8: true si el JSON de una copia de seguridad trae
 * safeMode=true (copias viejas, exportadas con el modo seguro ON).
 * Lee el valor con el mismo parser que decodeSettingsBackup, así
 * que admite espacios alrededor de ':' y no depende del formato
 * exacto del encoder. Un JSON inválido devuelve false: la
 * decodificación ya rechaza el archivo por su cuenta. Pura (JVM).
 */
fun backupJsonHasSafeModeEnabled(json: String): Boolean =
    parseFlatJsonObject(json).getOrNull()?.get("safeMode") == "true"

/** Escapa un string para JSON (comillas, barra, controles y unicode no ASCII). */
internal fun jsonString(value: String): String {
    val out = StringBuilder(value.length + 2)
    out.append('"')
    for (c in value) {
        when (c) {
            '"' -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            '\b' -> out.append("\\b")
            '\u000C' -> out.append("\\f")
            else -> if (c.code < 0x20) out.append("\\u%04x".format(c.code)) else out.append(c)
        }
    }
    out.append('"')
    return out.toString()
}

/**
 * Parser mínimo de un objeto JSON plano: claves string y valores
 * string / número / booleano / null. Las claves duplicadas se quedan
 * con el último valor. Cualquier otra forma (arrays, objetos
 * anidados, texto sin cerrar) es un error.
 */
internal fun parseFlatJsonObject(json: String): Result<Map<String, String>> {
    return runCatching {
        val parser = FlatJsonParser(json)
        parser.parseObject()
    }
}

private class FlatJsonParser(private val text: String) {
    private var pos = 0

    fun parseObject(): Map<String, String> {
        skipWs()
        requireChar('{')
        val result = linkedMapOf<String, String>()
        skipWs()
        if (peek() == '}') {
            pos++
            return result
        }
        while (true) {
            skipWs()
            val key = parseString()
            skipWs()
            requireChar(':')
            skipWs()
            val value = parseValue()
            result[key] = value
            skipWs()
            when (peek()) {
                ',' -> { pos++; continue }
                '}' -> { pos++; break }
                else -> error("se esperaba ',' o '}'")
            }
        }
        skipWs()
        if (pos != text.length) error("texto tras el objeto JSON")
        return result
    }

    private fun parseValue(): String {
        return when (peek()) {
            '"' -> parseString()
            't' -> { expectLiteral("true"); "true" }
            'f' -> { expectLiteral("false"); "false" }
            'n' -> { expectLiteral("null"); "" }
            else -> parseNumber()
        }
    }

    private fun parseNumber(): String {
        val start = pos
        while (pos < text.length && (text[pos].isDigit() || text[pos] in "-+.eE")) pos++
        if (pos == start) error("valor no válido")
        return text.substring(start, pos)
    }

    private fun parseString(): String {
        requireChar('"')
        val out = StringBuilder()
        while (true) {
            if (pos >= text.length) error("string sin cerrar")
            val c = text[pos++]
            when (c) {
                '"' -> return out.toString()
                '\\' -> {
                    if (pos >= text.length) error("escape sin cerrar")
                    when (val esc = text[pos++]) {
                        '"' -> out.append('"')
                        '\\' -> out.append('\\')
                        '/' -> out.append('/')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (pos + 4 > text.length) error("escape unicode sin cerrar")
                            val hex = text.substring(pos, pos + 4)
                            val code = hex.toIntOrNull(16) ?: error("escape unicode no válido")
                            out.append(code.toChar())
                            pos += 4
                        }
                        else -> error("escape no válido")
                    }
                }
                else -> out.append(c)
            }
        }
    }

    private fun expectLiteral(literal: String) {
        if (!text.startsWith(literal, pos)) error("literal no válido")
        pos += literal.length
    }

    private fun requireChar(expected: Char) {
        if (peek() != expected) error("se esperaba '$expected'")
        pos++
    }

    private fun peek(): Char? = text.getOrNull(pos)

    private fun skipWs() {
        while (pos < text.length && text[pos].isWhitespace()) pos++
    }
}
