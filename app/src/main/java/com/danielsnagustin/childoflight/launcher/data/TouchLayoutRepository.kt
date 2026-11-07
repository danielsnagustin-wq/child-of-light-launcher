package com.danielsnagustin.childoflight.launcher.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.touchDataStore: DataStore<Preferences> by preferencesDataStore(name = "col_touch_layout")

/** Botón de Vita al que se puede asignar un botón táctil. */
enum class VitaButton(val label: String) {
    CROSS("Cruz (X)"),
    CIRCLE("Círculo (O)"),
    SQUARE("Cuadrado (□)"),
    TRIANGLE("Triángulo (△)"),
    L1("L"),
    R1("R"),
    START("Start"),
    SELECT("Select"),
    DPAD_UP("D-Pad arriba"),
    DPAD_DOWN("D-Pad abajo"),
    DPAD_LEFT("D-Pad izquierda"),
    DPAD_RIGHT("D-Pad derecha"),
    LEFT_STICK("Stick izquierdo"),
    RIGHT_STICK("Stick derecho"),
    L3("L3"),
    R3("R3"),
    NONE("Sin asignar"),
}

enum class ControlShape { CIRCLE, SQUARE }
enum class StickMode(val label: String) { ANALOG("Stick analógico"), DPAD("D-Pad"), BOTH("Ambos") }
enum class IgniculusMode(val label: String) { DIRECT_TOUCH("Toque directo"), JOYSTICK("Joystick") }

/**
 * Un control táctil del layout de Child of Light.
 * x/y son fracciones de la pantalla (0..1), size en dp, opacity 0..1.
 * - stick: stick izquierdo para Aurora (stickMode: analógico / D-Pad / ambos).
 * - button: botón asignable a cualquier botón de Vita (vitaButton).
 * - igniculus: zona táctil para la luciérnaga (modo toque directo o joystick, sensibilidad).
 * visible=false = oculto (no aparece en el overlay ni estorba).
 * colorHex: color del control, p. ej. "#F2B705".
 */
data class TouchControl(
    val id: String,
    val label: String,
    val kind: String, // "stick" | "button" | "igniculus"
    val vitaButton: VitaButton = VitaButton.NONE,
    val xFraction: Float = 0.5f,
    val yFraction: Float = 0.5f,
    val sizeDp: Int = 64,
    val opacity: Float = 0.8f,
    val colorHex: String = "#F2B705",
    val shape: ControlShape = ControlShape.CIRCLE,
    val visible: Boolean = true,
    val stickMode: StickMode = StickMode.ANALOG,
    val igniculusMode: IgniculusMode = IgniculusMode.DIRECT_TOUCH,
    /** Sensibilidad de la zona Igniculus: 0.5 (lenta) .. 2.0 (rápida). */
    val sensitivity: Float = 1.0f,
)

data class ControlProfile(
    val id: String,
    val name: String,
    val isBuiltIn: Boolean,
    val controls: List<TouchControl>,
)

fun defaultTouchLayout(): List<TouchControl> = listOf(
    TouchControl("stick", "Stick", "stick", VitaButton.LEFT_STICK, 0.18f, 0.72f, 140, 0.70f),
    TouchControl("btn_y", "Y", "button", VitaButton.TRIANGLE, 0.70f, 0.70f, 64, 0.80f),
    TouchControl("btn_x", "X", "button", VitaButton.SQUARE, 0.78f, 0.62f, 64, 0.80f),
    TouchControl("btn_b", "B", "button", VitaButton.CIRCLE, 0.78f, 0.80f, 64, 0.80f),
    TouchControl("btn_a", "A", "button", VitaButton.CROSS, 0.87f, 0.71f, 64, 0.85f),
    TouchControl("btn_start", "Start", "button", VitaButton.START, 0.50f, 0.06f, 56, 0.70f),
    TouchControl("igniculus", "Igniculus", "igniculus", VitaButton.NONE, 0.50f, 0.35f, 220, 0.25f, colorHex = "#7FB3D5"),
)

fun oneHandLayout(): List<TouchControl> = listOf(
    TouchControl("stick", "Stick", "stick", VitaButton.LEFT_STICK, 0.20f, 0.75f, 120, 0.75f),
    TouchControl("btn_y", "Y", "button", VitaButton.TRIANGLE, 0.70f, 0.70f, 64, 0.80f, visible = false),
    TouchControl("btn_x", "X", "button", VitaButton.SQUARE, 0.78f, 0.62f, 64, 0.80f, visible = false),
    TouchControl("btn_b", "B", "button", VitaButton.CIRCLE, 0.72f, 0.87f, 64, 0.80f),
    TouchControl("btn_a", "A", "button", VitaButton.CROSS, 0.84f, 0.78f, 72, 0.85f),
    TouchControl("btn_start", "Start", "button", VitaButton.START, 0.50f, 0.06f, 56, 0.70f, visible = false),
    TouchControl("igniculus", "Igniculus", "igniculus", VitaButton.NONE, 0.55f, 0.28f, 160, 0.25f, colorHex = "#7FB3D5"),
)

fun externalGamepadLayout(): List<TouchControl> = listOf(
    TouchControl("stick", "Stick", "stick", VitaButton.LEFT_STICK, 0.18f, 0.72f, 140, 0.70f, visible = false),
    TouchControl("btn_y", "Y", "button", VitaButton.TRIANGLE, 0.70f, 0.70f, 64, 0.80f, visible = false),
    TouchControl("btn_x", "X", "button", VitaButton.SQUARE, 0.78f, 0.62f, 64, 0.80f, visible = false),
    TouchControl("btn_b", "B", "button", VitaButton.CIRCLE, 0.78f, 0.80f, 64, 0.80f, visible = false),
    TouchControl("btn_a", "A", "button", VitaButton.CROSS, 0.87f, 0.71f, 64, 0.85f, visible = false),
    TouchControl("btn_start", "Start", "button", VitaButton.START, 0.50f, 0.06f, 56, 0.70f),
    TouchControl("igniculus", "Igniculus", "igniculus", VitaButton.NONE, 0.50f, 0.35f, 220, 0.25f, colorHex = "#7FB3D5"),
)

fun builtInProfiles(): List<ControlProfile> = listOf(
    ControlProfile("default-g99", "Por defecto G99", true, defaultTouchLayout()),
    ControlProfile("one-hand", "Una mano", true, oneHandLayout()),
    ControlProfile("external-gamepad", "Mando externo + táctil mínimo", true, externalGamepadLayout()),
)

/**
 * Perfiles de control guardados en DataStore.
 * Formato: perfiles separados por "\n###\n"; cada perfil es
 * "id|nombre|isBuiltIn\n<controles>"; controles separados por ";"
 * y con 14 campos separados por "|". Los nombres se sanejan
 * (sin |, ;, # ni saltos de línea) al guardar.
 * Compatible hacia atrás: el formato antiguo de 7 campos por control
 * se lee con valores por defecto para los campos nuevos.
 */
class TouchLayoutRepository(private val context: Context) {

    private object Keys {
        val PROFILES = stringPreferencesKey("control_profiles")
        val ACTIVE_PROFILE = stringPreferencesKey("active_control_profile")
    }

    val profilesFlow: Flow<List<ControlProfile>> = context.touchDataStore.data.map { prefs ->
        decodeProfiles(prefs[Keys.PROFILES] ?: "") ?: builtInProfiles()
    }

    val activeProfileIdFlow: Flow<String> = context.touchDataStore.data.map { prefs ->
        prefs[Keys.ACTIVE_PROFILE] ?: "default-g99"
    }

    /** Layout del perfil activo (compatibilidad con la API anterior). */
    val layoutFlow: Flow<List<TouchControl>> = context.touchDataStore.data.map { prefs ->
        val profiles = decodeProfiles(prefs[Keys.PROFILES] ?: "") ?: builtInProfiles()
        val activeId = prefs[Keys.ACTIVE_PROFILE] ?: "default-g99"
        profiles.firstOrNull { it.id == activeId }?.controls ?: defaultTouchLayout()
    }

    suspend fun save(layout: List<TouchControl>) {
        val profiles = currentProfiles()
        val activeId = currentActiveId()
        val updated = profiles.map { if (it.id == activeId) it.copy(controls = layout) else it }
        saveProfiles(updated)
    }

    suspend fun reset() {
        saveProfiles(builtInProfiles())
        setActiveProfile("default-g99")
    }

    suspend fun setActiveProfile(id: String) {
        context.touchDataStore.edit { prefs -> prefs[Keys.ACTIVE_PROFILE] = id }
    }

    suspend fun createProfile(name: String, fromControls: List<TouchControl>): ControlProfile {
        val cleanName = sanitizeName(name).ifBlank { "Perfil nuevo" }
        val profile = ControlProfile(
            id = "custom-${System.currentTimeMillis()}",
            name = cleanName,
            isBuiltIn = false,
            controls = fromControls.map { it.copy() },
        )
        saveProfiles(currentProfiles() + profile)
        setActiveProfile(profile.id)
        return profile
    }

    suspend fun duplicateProfile(id: String): ControlProfile? {
        val source = currentProfiles().firstOrNull { it.id == id } ?: return null
        val copy = source.copy(
            id = "custom-${System.currentTimeMillis()}",
            name = sanitizeName("${source.name} (copia)"),
            isBuiltIn = false,
        )
        saveProfiles(currentProfiles() + copy)
        return copy
    }

    suspend fun renameProfile(id: String, newName: String): Boolean {
        val clean = sanitizeName(newName)
        if (clean.isBlank()) return false
        val profiles = currentProfiles()
        if (profiles.none { it.id == id }) return false
        saveProfiles(profiles.map { if (it.id == id) it.copy(name = clean) else it })
        return true
    }

    /**
     * Borra un perfil. No se puede borrar el último perfil que quede;
     * si se borra el activo, el activo pasa a "Por defecto G99".
     */
    suspend fun deleteProfile(id: String): Boolean {
        val profiles = currentProfiles()
        if (profiles.size <= 1) return false
        if (profiles.none { it.id == id }) return false
        saveProfiles(profiles.filter { it.id != id })
        if (currentActiveId() == id) setActiveProfile("default-g99")
        return true
    }

    /** Exporta un perfil como texto (mismo formato de guardado, un solo perfil). */
    fun exportProfileText(profile: ControlProfile): String = encodeProfile(profile)

    /** Importa un perfil desde texto exportado. Devuelve el perfil creado o null si el texto no es válido. */
    suspend fun importProfileText(raw: String): ControlProfile? {
        val parsed = decodeProfile(raw.trim()) ?: return null
        val profile = parsed.copy(
            id = "custom-${System.currentTimeMillis()}",
            isBuiltIn = false,
            name = sanitizeName(parsed.name).ifBlank { "Perfil importado" },
        )
        saveProfiles(currentProfiles() + profile)
        return profile
    }

    suspend fun restoreBuiltIns() {
        val current = currentProfiles()
        val missing = builtInProfiles().filter { built -> current.none { it.id == built.id } }
        if (missing.isNotEmpty()) saveProfiles(current + missing)
    }

    private suspend fun currentProfiles(): List<ControlProfile> {
        val raw = context.touchDataStore.data.first()[Keys.PROFILES] ?: ""
        return decodeProfiles(raw) ?: builtInProfiles()
    }

    private suspend fun currentActiveId(): String {
        return context.touchDataStore.data.first()[Keys.ACTIVE_PROFILE] ?: "default-g99"
    }

    private suspend fun saveProfiles(profiles: List<ControlProfile>) {
        context.touchDataStore.edit { prefs ->
            prefs[Keys.PROFILES] = profiles.joinToString("\n###\n") { encodeProfile(it) }
        }
    }

    companion object {
        fun sanitizeName(name: String): String =
            name.replace('|', ' ').replace(';', ' ').replace('#', ' ')
                .replace('\n', ' ').replace('\r', ' ').trim().take(40)

        fun encodeControl(c: TouchControl): String = listOf(
            c.id, c.label, c.kind, c.vitaButton.name,
            c.xFraction.toString(), c.yFraction.toString(), c.sizeDp.toString(),
            c.opacity.toString(), c.colorHex, c.shape.name, c.visible.toString(),
            c.stickMode.name, c.igniculusMode.name, c.sensitivity.toString(),
        ).joinToString("|")

        fun decodeControl(raw: String): TouchControl? {
            val p = raw.split("|")
            if (p.size < 7) return null
            return runCatching {
                if (p.size >= 14) {
                    TouchControl(
                        id = p[0], label = p[1], kind = p[2],
                        vitaButton = VitaButton.valueOf(p[3]),
                        xFraction = p[4].toFloat(), yFraction = p[5].toFloat(),
                        sizeDp = p[6].toInt(), opacity = p[7].toFloat(),
                        colorHex = p[8], shape = ControlShape.valueOf(p[9]),
                        visible = p[10].toBoolean(),
                        stickMode = StickMode.valueOf(p[11]),
                        igniculusMode = IgniculusMode.valueOf(p[12]),
                        sensitivity = p[13].toFloat(),
                    )
                } else {
                    // Formato antiguo (7 campos): valores por defecto para lo nuevo.
                    TouchControl(
                        id = p[0], label = p[1], kind = p[2],
                        vitaButton = defaultButtonFor(p[0], p[2]),
                        xFraction = p[3].toFloat(), yFraction = p[4].toFloat(),
                        sizeDp = p[5].toInt(), opacity = p[6].toFloat(),
                    )
                }
            }.getOrNull()
        }

        private fun defaultButtonFor(id: String, kind: String): VitaButton = when (id) {
            "btn_a" -> VitaButton.CROSS
            "btn_b" -> VitaButton.CIRCLE
            "btn_x" -> VitaButton.SQUARE
            "btn_y" -> VitaButton.TRIANGLE
            "btn_start" -> VitaButton.START
            "stick" -> VitaButton.LEFT_STICK
            else -> if (kind == "igniculus") VitaButton.NONE else VitaButton.NONE
        }

        fun encodeProfile(profile: ControlProfile): String {
            val header = listOf(profile.id, sanitizeName(profile.name), profile.isBuiltIn.toString()).joinToString("|")
            val controls = profile.controls.joinToString(";") { encodeControl(it) }
            return "$header\n$controls"
        }

        fun decodeProfile(raw: String): ControlProfile? {
            val lines = raw.split("\n", limit = 2)
            if (lines.isEmpty()) return null
            val header = lines[0].split("|")
            if (header.size < 3) return null
            val controlsRaw = if (lines.size > 1) lines[1] else ""
            val controls = if (controlsRaw.isBlank()) emptyList() else {
                controlsRaw.split(";").mapNotNull { decodeControl(it) }
            }
            if (controls.isEmpty()) return null
            return ControlProfile(header[0], header[1], header[2].toBoolean(), controls)
        }

        fun decodeProfiles(raw: String): List<ControlProfile>? {
            if (raw.isBlank()) return null
            val profiles = raw.split("\n###\n").mapNotNull { decodeProfile(it) }
            return profiles.ifEmpty { null }
        }

        // Compatibilidad: funciones del formato antiguo (un solo layout).
        fun encode(layout: List<TouchControl>): String =
            layout.joinToString(";") { encodeControl(it) }

        fun decode(raw: String): List<TouchControl>? {
            if (raw.isBlank()) return null
            return runCatching {
                raw.split(";").mapNotNull { decodeControl(it) }
            }.getOrNull()?.ifEmpty { null }
        }
    }
}
