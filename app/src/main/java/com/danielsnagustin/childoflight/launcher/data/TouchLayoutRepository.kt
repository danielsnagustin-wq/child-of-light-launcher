package com.danielsnagustin.childoflight.launcher.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

// Crash al abrir (run #80): mismo arreglo que UiPreferencesRepository
// (ver su comentario): archivo preferences_pb corrupto -> el
// corruptionHandler lo reemplaza por vacío y el catch cubre otros
// fallos de lectura con los perfiles de fábrica.
private val Context.touchDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "col_touch_layout",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Botón de Vita al que se puede asignar un botón táctil. */
enum class VitaButton(val label: String) {
    CROSS("Cruz (X)"),
    CIRCLE("Círculo (O)"),
    SQUARE("Cuadrado (□)"),
    TRIANGLE("Triángulo (△)"),
    L1("L"),
    // R4-P2-11: L2/R2 no son botones SDL: el núcleo los lee como
    // ejes (trigger > 0x3FFF) y el overlay de upstream los manda con
    // botón negativo (input_overlay.cpp: button < 0 ->
    // SDL_SetJoystickVirtualAxis(-button, ...)). toSdlButton() los
    // codifica así (-4/-5); el editor los lista porque itera
    // VitaButton.entries. Child of Light no los usa en el título:
    // solo están disponibles para asignar, nunca en el layout por
    // defecto.
    L2("L2"),
    R1("R"),
    R2("R2"),
    START("Start"),
    SELECT("Select"),
    // R4-P2-13: botón guía (PS): SDL 5. Pulsarlo manda el botón
    // GUIDE del mando virtual y el bucle del núcleo (session.cpp:
    // SDL_EVENT_GAMEPAD_BUTTON_DOWN + GUIDE -> on_guide_button ->
    // main_android.cpp:101 openPauseMenuFromController) abre el menú
    // de pausa. También disponible solo en el editor, no por defecto.
    GUIDE("Guía (PS)"),
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
 * colorHex: color del control, p. ej. "#8CCBEB".
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
    val colorHex: String = "#8CCBEB",
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

/**
 * Rombo estándar PlayStation/Vita, centrado en ~(0.80, 0.71):
 * TRIANGLE arriba, CIRCLE derecha, CROSS abajo, SQUARE izquierda.
 * R4-P0-1: el rombo anterior estaba transpuesto (TRIANGLE a la
 * izquierda, SQUARE arriba, CIRCLE abajo, CROSS a la derecha), así
 * que el botón de abajo — el que todo el mundo pulsa para confirmar
 * en la pantalla de título — era CIRCLE (atrás), no CROSS.
 * R4-P2-1: stick de 104 dp (antes 140 dp, ocupaba el tercio
 * inferior izquierdo) e Igniculus en y 0.55 (antes 0.35, su orbe
 * flotaba sobre la "C" de "CHILD OF LIGHT" tapando el logo). Solo
 * el default de fábrica: los perfiles ya guardados no se tocan.
 */
fun defaultTouchLayout(): List<TouchControl> = listOf(
    TouchControl("stick", "Stick", "stick", VitaButton.LEFT_STICK, 0.18f, 0.72f, 104, 0.70f),
    TouchControl("btn_y", "Y", "button", VitaButton.TRIANGLE, 0.80f, 0.62f, 64, 0.80f),
    TouchControl("btn_x", "X", "button", VitaButton.SQUARE, 0.73f, 0.71f, 64, 0.80f),
    TouchControl("btn_b", "B", "button", VitaButton.CIRCLE, 0.87f, 0.71f, 64, 0.80f),
    TouchControl("btn_a", "A", "button", VitaButton.CROSS, 0.80f, 0.80f, 64, 0.85f),
    TouchControl("btn_start", "Start", "button", VitaButton.START, 0.50f, 0.06f, 56, 0.70f),
    TouchControl("igniculus", "Igniculus", "igniculus", VitaButton.NONE, 0.50f, 0.55f, 220, 0.25f, colorHex = "#7FB3D5"),
)

fun oneHandLayout(): List<TouchControl> = listOf(
    TouchControl("stick", "Stick", "stick", VitaButton.LEFT_STICK, 0.20f, 0.75f, 120, 0.75f),
    TouchControl("btn_y", "Y", "button", VitaButton.TRIANGLE, 0.76f, 0.65f, 64, 0.80f, visible = false),
    TouchControl("btn_x", "X", "button", VitaButton.SQUARE, 0.69f, 0.74f, 64, 0.80f, visible = false),
    TouchControl("btn_b", "B", "button", VitaButton.CIRCLE, 0.83f, 0.74f, 64, 0.80f),
    TouchControl("btn_a", "A", "button", VitaButton.CROSS, 0.76f, 0.83f, 72, 0.85f),
    TouchControl("btn_start", "Start", "button", VitaButton.START, 0.50f, 0.06f, 56, 0.70f, visible = false),
    // R4-P2-1: Igniculus en y 0.50 (antes 0.28, tapaba el logo
    // igual que en el perfil por defecto); solo fábrica.
    TouchControl("igniculus", "Igniculus", "igniculus", VitaButton.NONE, 0.55f, 0.50f, 160, 0.25f, colorHex = "#7FB3D5"),
)

fun externalGamepadLayout(): List<TouchControl> = listOf(
    TouchControl("stick", "Stick", "stick", VitaButton.LEFT_STICK, 0.18f, 0.72f, 140, 0.70f, visible = false),
    TouchControl("btn_y", "Y", "button", VitaButton.TRIANGLE, 0.80f, 0.62f, 64, 0.80f, visible = false),
    TouchControl("btn_x", "X", "button", VitaButton.SQUARE, 0.73f, 0.71f, 64, 0.80f, visible = false),
    TouchControl("btn_b", "B", "button", VitaButton.CIRCLE, 0.87f, 0.71f, 64, 0.80f, visible = false),
    TouchControl("btn_a", "A", "button", VitaButton.CROSS, 0.80f, 0.80f, 64, 0.85f, visible = false),
    TouchControl("btn_start", "Start", "button", VitaButton.START, 0.50f, 0.06f, 56, 0.70f),
    // R4-P2-1: Igniculus en y 0.55 (antes 0.35, tapaba el logo);
    // mismo default de fábrica que el perfil por defecto.
    TouchControl("igniculus", "Igniculus", "igniculus", VitaButton.NONE, 0.50f, 0.55f, 220, 0.25f, colorHex = "#7FB3D5"),
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

    // B6 (auditoría panel táctil): todos los guardados son
    // read-modify-write sobre DataStore (leer perfiles -> modificar
    // -> guardar). Sin mutex, dos guardados concurrentes — p. ej.
    // cada tick de un slider de tamaño/opacidad, o el soltar de un
    // arrastre que llegaba a la vez que un tick — se basaban en la
    // misma lectura y el último en terminar pisaba al otro: se
    // perdía la edición final (el slider "no persistía" o volvía
    // a un valor anterior). Todas las mutaciones públicas pasan
    // por este mutex; los helpers privados asumen que ya se tiene.
    private val mutationMutex = Mutex()

    // R4-P0-1: la migración del rombo se dispara una sola vez por
    // instancia del repositorio, al cargar los perfiles por primera
    // vez (onStart de los flujos). El guardado en sí es atómico en
    // DataStore (ver migrateSavedBuiltInProfilesOnce): dos
    // instancias que migren a la vez escriben lo mismo.
    private val diamondMigrationStarted = AtomicBoolean(false)

    val profilesFlow: Flow<List<ControlProfile>> = context.touchDataStore.data.map { prefs ->
        decodeProfiles(prefs[Keys.PROFILES] ?: "") ?: builtInProfiles()
    }.onStart {
        migrateSavedBuiltInProfilesOnce()
    }.map { profiles ->
        // En memoria el rombo ya es el estándar aunque el guardado
        // en DataStore aún no haya aterrizado: el juego y el editor
        // nunca ven el rombo transpuesto.
        migrateBuiltInProfiles(profiles)
    }.catch { e ->
        Log.w("ChildOfLight", "DataStore touch (perfiles) ilegible, usando fábrica: ${e.message}")
        emit(builtInProfiles())
    }

    val activeProfileIdFlow: Flow<String> = context.touchDataStore.data.map { prefs ->
        prefs[Keys.ACTIVE_PROFILE] ?: "default-g99"
    }.catch { e ->
        Log.w("ChildOfLight", "DataStore touch (activo) ilegible, usando default-g99: ${e.message}")
        emit("default-g99")
    }

    /** Layout del perfil activo (compatibilidad con la API anterior). */
    val layoutFlow: Flow<List<TouchControl>> = context.touchDataStore.data.map { prefs ->
        val profiles = decodeProfiles(prefs[Keys.PROFILES] ?: "") ?: builtInProfiles()
        val activeId = prefs[Keys.ACTIVE_PROFILE] ?: "default-g99"
        val active = profiles.firstOrNull { it.id == activeId }
        if (active != null) migrateBuiltInFaceDiamond(active).controls else defaultTouchLayout()
    }.onStart {
        migrateSavedBuiltInProfilesOnce()
    }.catch { e ->
        Log.w("ChildOfLight", "DataStore touch (layout) ilegible, usando default: ${e.message}")
        emit(defaultTouchLayout())
    }

    /**
     * R4-P0-1: reescribe en DataStore los perfiles predefinidos
     * guardados con el rombo transpuesto y los deja con el rombo
     * estándar. Solo corre una vez por instancia; es idempotente
     * (la segunda vez ya no hay nada que migrar y no escribe).
     * La lectura, la migración y la escritura ocurren dentro de un
     * solo edit de DataStore, así que dos migraciones concurrentes
     * no pueden pisar una edición del usuario hecha entre medias.
     * Los perfiles personalizados (isBuiltIn == false) no se tocan.
     */
    private suspend fun migrateSavedBuiltInProfilesOnce() {
        if (!diamondMigrationStarted.compareAndSet(false, true)) return
        mutationMutex.withLock {
            safeEdit("migración rombo predefinido") { prefs ->
                val raw = prefs[Keys.PROFILES] ?: return@safeEdit
                val profiles = decodeProfiles(raw) ?: return@safeEdit
                val migrated = migrateBuiltInProfiles(profiles)
                if (migrated != profiles) {
                    prefs[Keys.PROFILES] = migrated.joinToString("\n###\n") { encodeProfile(it) }
                    Log.i("ChildOfLight", "R4-P0-1: rombo predefinido migrado al estándar en DataStore")
                }
            }
        }
    }

    /**
     * P1-18: un fallo de E/S al escribir (disco lleno, archivo corrupto)
     * no puede tumbar la app: viewModelScope tiene SupervisorJob y una
     * excepción no capturada en un hijo mata el proceso. Toda escritura
     * DataStore pasa por aquí: registra el fallo y devuelve false para
     * que el llamador pueda avisar.
     */
    private suspend fun safeEdit(tag: String, transform: suspend (MutablePreferences) -> Unit): Boolean {
        return try {
            context.touchDataStore.edit(transform)
            true
        } catch (e: Exception) {
            Log.w("ChildOfLight", "DataStore $tag no se pudo escribir: ${e.message}")
            false
        }
    }

    suspend fun save(layout: List<TouchControl>): Boolean {
        return mutationMutex.withLock { saveLocked(layout) }
    }

    /**
     * Añade un control al perfil activo de forma atómica. Antes el
     * ViewModel leía el layout, añadía y guardaba en tres pasos: dos
     * toques rápidos a "+ Añadir botón" leían el mismo layout y el
     * segundo guardado pisaba al primero (un botón no aparecía).
     */
    suspend fun addControl(control: TouchControl) {
        mutationMutex.withLock { saveLocked(currentActiveControls() + control) }
    }

    /**
     * Borra un control del perfil activo de forma atómica. Devuelve
     * false si ese id no estaba (no se toca nada).
     */
    suspend fun deleteControl(controlId: String): Boolean {
        return mutationMutex.withLock {
            val current = currentActiveControls()
            val updated = current.filter { it.id != controlId }
            if (updated.size == current.size) return@withLock false
            // P1-18: si el guardado falla, el borrado no ocurrió: no
            // se puede devolver true.
            saveLocked(updated)
        }
    }

    private suspend fun saveLocked(layout: List<TouchControl>): Boolean {
        val profiles = currentProfiles()
        val activeId = currentActiveId()
        if (profiles.none { it.id == activeId }) {
            // BUG (auditoría): si el perfil activo había sido borrado (antes
            // del fix de deleteProfile), save() no guardaba nada en silencio.
            // Auto-reparación: se guarda en el primer perfil y se activa.
            if (profiles.isEmpty()) return false
            val updated = profiles.mapIndexed { index, p ->
                if (index == 0) p.copy(controls = layout) else p
            }
            if (!saveProfiles(updated)) return false
            return setActiveProfileLocked(profiles.first().id)
        }
        val updated = profiles.map { if (it.id == activeId) it.copy(controls = layout) else it }
        return saveProfiles(updated)
    }

    /** Controles del perfil activo (o del primero si el activo no existe). */
    private suspend fun currentActiveControls(): List<TouchControl> {
        val profiles = currentProfiles()
        val activeId = currentActiveId()
        return profiles.firstOrNull { it.id == activeId }?.controls
            ?: profiles.firstOrNull()?.controls ?: emptyList()
    }

    private suspend fun setActiveProfileLocked(id: String): Boolean {
        return safeEdit("perfil activo") { prefs -> prefs[Keys.ACTIVE_PROFILE] = id }
    }

    suspend fun reset() {
        mutationMutex.withLock {
            saveProfiles(builtInProfiles())
            setActiveProfileLocked("default-g99")
        }
    }

    suspend fun setActiveProfile(id: String) {
        mutationMutex.withLock { setActiveProfileLocked(id) }
    }

    suspend fun createProfile(name: String, fromControls: List<TouchControl>): ControlProfile {
        return mutationMutex.withLock {
            val cleanName = sanitizeName(name).ifBlank { "Perfil nuevo" }
            val profile = ControlProfile(
                id = newProfileId(),
                name = cleanName,
                isBuiltIn = false,
                controls = fromControls.map { it.copy() },
            )
            saveProfiles(currentProfiles() + profile)
            setActiveProfileLocked(profile.id)
            profile
        }
    }

    suspend fun duplicateProfile(id: String): ControlProfile? {
        return mutationMutex.withLock {
            val source = currentProfiles().firstOrNull { it.id == id } ?: return@withLock null
            val copy = source.copy(
                id = newProfileId(),
                name = sanitizeName("${source.name} (copia)"),
                isBuiltIn = false,
            )
            saveProfiles(currentProfiles() + copy)
            copy
        }
    }

    suspend fun renameProfile(id: String, newName: String): Boolean {
        return mutationMutex.withLock {
            val clean = sanitizeName(newName)
            if (clean.isBlank()) return@withLock false
            val profiles = currentProfiles()
            if (profiles.none { it.id == id }) return@withLock false
            saveProfiles(profiles.map { if (it.id == id) it.copy(name = clean) else it })
            true
        }
    }

    /**
     * Borra un perfil. No se puede borrar el último perfil que quede;
     * si se borra el activo, el activo pasa al primer perfil que queda.
     */
    suspend fun deleteProfile(id: String): Boolean {
        return mutationMutex.withLock {
            val profiles = currentProfiles()
            if (profiles.size <= 1) return@withLock false
            if (profiles.none { it.id == id }) return@withLock false
            val remaining = profiles.filter { it.id != id }
            saveProfiles(remaining)
            // BUG (auditoría): antes se forzaba "default-g99" como activo; si
            // ese perfil ya había sido borrado, el activo apuntaba a un perfil
            // inexistente y save() no guardaba nada en silencio.
            if (currentActiveId() == id) setActiveProfileLocked(remaining.first().id)
            true
        }
    }

    /** Exporta un perfil como texto (mismo formato de guardado, un solo perfil). */
    fun exportProfileText(profile: ControlProfile): String = encodeProfile(profile)

    /** Importa un perfil desde texto exportado. Devuelve el perfil creado o null si el texto no es válido. */
    suspend fun importProfileText(raw: String): ControlProfile? {
        return mutationMutex.withLock {
            val parsed = decodeProfile(raw.trim()) ?: return@withLock null
            val profile = parsed.copy(
                id = newProfileId(),
                isBuiltIn = false,
                name = sanitizeName(parsed.name).ifBlank { "Perfil importado" },
            )
            saveProfiles(currentProfiles() + profile)
            profile
        }
    }

    suspend fun restoreBuiltIns() {
        mutationMutex.withLock {
            val current = currentProfiles()
            val missing = builtInProfiles().filter { built -> current.none { it.id == built.id } }
            if (missing.isNotEmpty()) saveProfiles(current + missing)
        }
    }

    private suspend fun currentProfiles(): List<ControlProfile> {
        val raw = context.touchDataStore.data.first()[Keys.PROFILES] ?: ""
        val profiles = decodeProfiles(raw) ?: builtInProfiles()
        // R4-P0-1: en memoria los predefinidos ya llevan el rombo
        // estándar (el guardado lo hace migrateSavedBuiltInProfilesOnce
        // al cargar, y saveProfiles al escribir). Así duplicar un
        // predefinido aún sin migrar en disco no hereda el transpuesto.
        return migrateBuiltInProfiles(profiles)
    }

    private suspend fun currentActiveId(): String {
        return context.touchDataStore.data.first()[Keys.ACTIVE_PROFILE] ?: "default-g99"
    }

    private suspend fun saveProfiles(profiles: List<ControlProfile>): Boolean {
        // R4-P0-1: red de seguridad: cualquier guardado de perfiles
        // deja los predefinidos con el rombo estándar (la migración
        // en sí es pura e idempotente; los personalizados no se tocan).
        val normalized = migrateBuiltInProfiles(profiles)
        return safeEdit("perfiles de controles") { prefs ->
            prefs[Keys.PROFILES] = normalized.joinToString("\n###\n") { encodeProfile(it) }
        }
    }

    companion object {
        // BUG (auditoría): "custom-" + currentTimeMillis() colisiona si se
        // crean dos perfiles en el mismo milisegundo. Se añade nanoTime.
        private fun newProfileId(): String =
            "custom-${System.currentTimeMillis()}-${System.nanoTime() % 100000}"

        /**
         * R4-P0-1: un hueco del rombo de botones de cara: id del
         * control, botón de Vita que lleva de fábrica, posición
         * antigua (rombo transpuesto) y posición nueva (estándar).
         */
        private data class FaceSlot(
            val id: String,
            val button: VitaButton,
            val oldX: Float,
            val oldY: Float,
            val newX: Float,
            val newY: Float,
        )

        // Rombo transpuesto anterior (TRIANGLE izquierda, SQUARE
        // arriba, CIRCLE abajo, CROSS derecha) y rombo estándar
        // nuevo, centrados en (0.80, 0.71). Lo comparten el perfil
        // por defecto y el de mando externo (mismas posiciones).
        private val DEFAULT_FACE_SLOTS = listOf(
            FaceSlot("btn_y", VitaButton.TRIANGLE, 0.70f, 0.70f, 0.80f, 0.62f),
            FaceSlot("btn_x", VitaButton.SQUARE, 0.78f, 0.62f, 0.73f, 0.71f),
            FaceSlot("btn_b", VitaButton.CIRCLE, 0.78f, 0.80f, 0.87f, 0.71f),
            FaceSlot("btn_a", VitaButton.CROSS, 0.87f, 0.71f, 0.80f, 0.80f),
        )

        // "Una mano" tenía la misma transposición, con el rombo
        // centrado en (0.76, 0.74) y el mismo radio (0.07 en X,
        // 0.09 en Y): se conserva su carácter compacto.
        private val ONE_HAND_FACE_SLOTS = listOf(
            FaceSlot("btn_y", VitaButton.TRIANGLE, 0.70f, 0.70f, 0.76f, 0.65f),
            FaceSlot("btn_x", VitaButton.SQUARE, 0.78f, 0.62f, 0.69f, 0.74f),
            FaceSlot("btn_b", VitaButton.CIRCLE, 0.72f, 0.87f, 0.83f, 0.74f),
            FaceSlot("btn_a", VitaButton.CROSS, 0.84f, 0.78f, 0.76f, 0.83f),
        )

        // Tolerancia al comparar posiciones guardadas: el formato
        // escribe el Float con toString y lo relee exacto, pero un
        // arrastre o un redondeo no debe impedir migrar por una
        // milésima. Un botón movido de verdad se aleja mucho más.
        private const val FACE_SLOT_EPSILON = 0.001f

        /**
         * R4-P0-1: migra UN perfil predefinido del rombo transpuesto
         * al estándar. Solo actúa si el perfil es predefinido
         * (isBuiltIn) Y los 4 botones de cara siguen en su hueco de
         * fábrica (mismo id, mismo botón de Vita y misma posición
         * antigua): si el usuario movió o remapeó aunque sea uno,
         * el perfil es suyo y no se toca. Idempotente: un perfil ya
         * migrado no coincide con las posiciones antiguas y se
         * devuelve tal cual. Puro Kotlin, sin Context: testeable.
         */
        fun migrateBuiltInFaceDiamond(profile: ControlProfile): ControlProfile {
            if (!profile.isBuiltIn) return profile
            val slots = when (profile.id) {
                "one-hand" -> ONE_HAND_FACE_SLOTS
                else -> DEFAULT_FACE_SLOTS
            }
            val byId = profile.controls.associateBy { it.id }
            for (slot in slots) {
                val control = byId[slot.id] ?: return profile
                if (control.vitaButton != slot.button) return profile
                if (abs(control.xFraction - slot.oldX) > FACE_SLOT_EPSILON) return profile
                if (abs(control.yFraction - slot.oldY) > FACE_SLOT_EPSILON) return profile
            }
            val newPositions = slots.associate { it.id to (it.newX to it.newY) }
            return profile.copy(
                controls = profile.controls.map { control ->
                    val pos = newPositions[control.id]
                    if (pos != null) control.copy(xFraction = pos.first, yFraction = pos.second) else control
                },
            )
        }

        /** R4-P0-1: migra todos los perfiles predefinidos de la lista. */
        fun migrateBuiltInProfiles(profiles: List<ControlProfile>): List<ControlProfile> =
            profiles.map { migrateBuiltInFaceDiamond(it) }


        fun sanitizeName(name: String): String =
            name.replace('|', ' ').replace(';', ' ').replace('#', ' ')
                .replace('/', ' ').replace('\\', ' ')
                .replace('\n', ' ').replace('\r', ' ').trim().take(40)

        /** Saneado para campos del formato (sin quitar '#': colorHex lo usa). */
        private fun sanitizeField(value: String): String =
            value.replace('|', ' ').replace(';', ' ')
                .replace('\n', ' ').replace('\r', ' ').trim()

        fun encodeControl(c: TouchControl): String = listOf(
            // BUG (auditoría): label y colorHex no se saneaban, pero el
            // formato usa '|' y ';' como separadores; una etiqueta con '|'
            // o ';' (editable por el usuario) corrompía el perfil y el
            // control desaparecía silenciosamente al decodificar.
            c.id, sanitizeField(c.label), c.kind, c.vitaButton.name,
            c.xFraction.toString(), c.yFraction.toString(), c.sizeDp.toString(),
            c.opacity.toString(), sanitizeField(c.colorHex), c.shape.name, c.visible.toString(),
            c.stickMode.name, c.igniculusMode.name, c.sensitivity.toString(),
        ).joinToString("|")

        /**
         * Centro de un control en fracción de pantalla, con el control
         * ENTERO dentro de la pantalla: [half, 1 - half], donde half es
         * la mitad del tamaño del control en fracción. Si el control es
         * mayor que la pantalla, el centro es 0.5. Lo usa el arrastre
         * de la vista previa (B7): antes el arrastre fijaba la fracción
         * en 0..1 y el dibujado la recortaba por arriba, así que al
         * arrastrar a un borde el botón dejaba de moverse en pantalla
         * pero la fracción seguía bajando hasta 0 — y al volver, los
         * primeros píxeles del dedo no movían nada. Fracción guardada
         * y posición dibujada ahora coinciden siempre.
         */
        fun clampCenterFraction(fraction: Float, halfSizeFraction: Float): Float {
            val lo = halfSizeFraction.coerceAtMost(0.5f)
            val hi = (1f - halfSizeFraction).coerceAtLeast(0.5f)
            return fraction.coerceIn(lo, hi)
        }

        private fun finiteFloat(raw: String?, default: Float): Float =
            raw?.toFloatOrNull()?.takeIf { it.isFinite() } ?: default

        fun decodeControl(raw: String): TouchControl? {
            val p = raw.split("|")
            if (p.size < 7) return null
            val parsed = runCatching {
                if (p.size >= 14) {
                    // B8 (auditoría panel táctil): esta rama usaba
                    // toFloat()/toInt()/valueOf() directos dentro de
                    // runCatching: UN solo campo corrupto (un enum
                    // renombrado, un número roto) descartaba el
                    // control ENTERO en silencio al decodificar, y el
                    // botón desaparecía del layout. Ahora cada campo
                    // se lee con valor por defecto, igual que la rama
                    // de 8-13 campos (M28).
                    TouchControl(
                        id = p[0], label = p[1], kind = p[2],
                        vitaButton = runCatching { VitaButton.valueOf(p[3]) }.getOrDefault(VitaButton.NONE),
                        xFraction = finiteFloat(p[4], 0.5f),
                        yFraction = finiteFloat(p[5], 0.5f),
                        sizeDp = p[6].toIntOrNull() ?: 64,
                        opacity = finiteFloat(p[7], 0.8f),
                        colorHex = p[8],
                        shape = runCatching { ControlShape.valueOf(p[9]) }.getOrNull() ?: ControlShape.CIRCLE,
                        visible = p[10].toBooleanStrictOrNull() ?: true,
                        stickMode = runCatching { StickMode.valueOf(p[11]) }.getOrNull() ?: StickMode.ANALOG,
                        igniculusMode = runCatching { IgniculusMode.valueOf(p[12]) }.getOrNull() ?: IgniculusMode.DIRECT_TOUCH,
                        sensitivity = finiteFloat(p[13], 1.0f),
                    )
                } else if (p.size >= 8) {
                    // M28: formato intermedio (8-13 campos): los campos
                    // presentes se leen con los índices del formato de 14;
                    // lo que falta toma el valor por defecto. Antes caía en
                    // la rama antigua (7 campos) y p[3] (nombre de botón
                    // Vita) se parseaba como Float -> el control se
                    // descartaba en silencio.
                    TouchControl(
                        id = p[0], label = p[1], kind = p[2],
                        vitaButton = runCatching { VitaButton.valueOf(p[3]) }.getOrDefault(VitaButton.NONE),
                        xFraction = finiteFloat(p[4], 0.5f),
                        yFraction = finiteFloat(p[5], 0.5f),
                        sizeDp = p[6].toIntOrNull() ?: 64,
                        opacity = finiteFloat(p[7], 0.8f),
                        colorHex = p.getOrNull(8) ?: "#8CCBEB",
                        shape = p.getOrNull(9)?.let { runCatching { ControlShape.valueOf(it) }.getOrNull() } ?: ControlShape.CIRCLE,
                        visible = p.getOrNull(10)?.toBooleanStrictOrNull() ?: true,
                        stickMode = p.getOrNull(11)?.let { runCatching { StickMode.valueOf(it) }.getOrNull() } ?: StickMode.ANALOG,
                        igniculusMode = p.getOrNull(12)?.let { runCatching { IgniculusMode.valueOf(it) }.getOrNull() } ?: IgniculusMode.DIRECT_TOUCH,
                        sensitivity = finiteFloat(p.getOrNull(13), 1.0f),
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
            }.getOrNull() ?: return null
            // B9: normalizar al leer. Un valor corrupto fuera de rango
            // (fracción > 1, tamaño negativo, opacidad > 1, "NaN") no
            // puede sacar el botón de la pantalla ni romper el layout.
            return parsed.copy(
                xFraction = parsed.xFraction.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.5f,
                yFraction = parsed.yFraction.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.5f,
                sizeDp = parsed.sizeDp.coerceIn(16, 400),
                opacity = parsed.opacity.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.8f,
                sensitivity = parsed.sensitivity.takeIf { it.isFinite() }?.coerceIn(0.5f, 2.0f) ?: 1.0f,
            )
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
            // B10 (auditoría panel táctil): un perfil SIN controles es
            // válido (el usuario borró todos los botones para jugar
            // solo con mando físico). Antes se devolvía null y el
            // perfil entero desaparecía al leer: el layout volvía a
            // los predefinidos y los botones borrados "resucitaban".
            // Tampoco se podía importar un perfil vacío exportado.
            // Ids duplicados (p. ej. en un archivo importado a mano):
            // se queda el primero, para que borrar/editar por id no
            // afecte a dos controles a la vez.
            return ControlProfile(header[0], header[1], header[2].toBoolean(), controls.distinctBy { it.id })
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
