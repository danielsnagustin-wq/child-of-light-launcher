package com.danielsnagustin.childoflight.launcher.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.danielsnagustin.childoflight.launcher.data.ControlProfile
import com.danielsnagustin.childoflight.launcher.data.ControlShape
import com.danielsnagustin.childoflight.launcher.data.IgniculusMode
import com.danielsnagustin.childoflight.launcher.data.StickMode
import com.danielsnagustin.childoflight.launcher.data.TouchControl
import com.danielsnagustin.childoflight.launcher.data.TouchLayoutRepository
import com.danielsnagustin.childoflight.launcher.data.VitaButton

// R4-P2-2: paleta solo con la identidad azul/lavanda/turquesa/
// plata (antes incluía un verde y un rojo, fuera de la identidad).
// R5-A1-8: el morado rosado #BA68C8 sale de la paleta y entra el
// azul lavanda #6578B8 de la identidad. internal para que el test
// R4P2 la pueda verificar; ningún default de control usa verde ni
// rojo (verificado por grep en main: solo esta paleta los tenía).
internal val CONTROL_COLORS = listOf("#8CCBEB", "#7FB3D5", "#75D6CE", "#A99ACD", "#6578B8", "#FFFFFF")

private fun parseHex(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (_: Exception) {
    Color(0xFF8CCBEB)
}

/**
 * R4-P1-4: cristal del juego para la vista previa del editor, sin
 * tocar el núcleo. Degradado radial plata en el centro, color del
 * botón en el medio y azul profundo en el borde: el mismo aspecto
 * que el overlay dibuja en el juego (GamepadOverlayView), en vez de
 * un círculo plano de color. 0.2.56: la zona Igniculus lo usa solo
 * en su orbe interior (84%): fuera del orbe la zona queda
 * transparente, como en el juego.
 */
private fun touchPreviewGlassBrush(base: Color): Brush = Brush.radialGradient(
    listOf(Color(0xFFDCE8F5), base.copy(alpha = 0.85f), Color(0xFF182644)),
)

/** R4-P1-4: texto del resultado de "probar mando": etiqueta del botón y a qué botón de Vita está asignado. Puro y testeable en JVM. */
fun touchControlTestLabel(control: TouchControl): String =
    "${control.label} → ${control.vitaButton.label}"

/**
 * Editor COMPLETO de controles táctiles de Child of Light.
 *
 * Por botón: mover ARRASTRANDO en la vista previa, tamaño, opacidad,
 * color, forma (círculo/cuadrado), mostrar/ocultar, cambiar etiqueta
 * y REMAPEO a cualquier botón de Vita. El stick cambia entre
 * analógico / D-Pad / ambos. La zona Igniculus tiene tamaño, posición,
 * sensibilidad, activar/desactivar y modo toque directo vs joystick.
 * Cada botón tiene papelera con confirmación.
 *
 * Perfiles: crear, duplicar, renombrar, borrar, importar/exportar.
 * Predefinidos: "Por defecto G99", "Una mano", "Mando externo + táctil mínimo".
 *
 * Si borras todos los botones y usas mando físico, el interruptor
 * "Ocultar controles táctiles si hay mando conectado" quita el overlay.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TouchLayoutScreen(vm: LauncherViewModel, onBack: () -> Unit) {
    val layout by vm.touchLayoutFlow.collectAsState(initial = emptyList())
    val profiles by vm.profilesFlow.collectAsState(initial = emptyList())
    val activeProfileId by vm.activeProfileIdFlow.collectAsState(initial = "default-g99")
    val settings by vm.settingsFlow.collectAsState(initial = null)
    // R5-A2-3: fondo según el tema, como el scaffold de Ajustes
    // (SettingsCommon.kt:72). Antes premiumBackgroundBrush() sin
    // argumentos fijaba dark=true: con Tema Claro esta pantalla
    // quedaba con el fondo AMOLED oscuro mientras las categorías
    // de Ajustes se aclaraban.
    val uiPrefs = LocalUiPreferences.current
    // B11: selectedId y los textos de los diálogos se pierden al
    // rotar/recrear (eran remember): el usuario editaba un botón y
    // al girar el teléfono volvía al stick y perdía lo escrito.
    var selectedId by rememberSaveable { mutableStateOf("stick") }
    // P-C1 (0.2.61): espejo del tamaño medido por TouchPreview (lo
    // comunica con onPreviewSizeChanged): los sliders de posición de
    // abajo lo usan para activarse y para el clamp. El tamaño que
    // usa el arrastre vive dentro de TouchPreview.
    var previewSize by remember { mutableStateOf(IntSize.Zero) }
    // R4-P1-4: modo "probar mando" del editor: tocar un botón en la
    // vista previa lo resalta y muestra su etiqueta y su asignación,
    // sin moverlo ni guardar nada. Sin juego en curso no hay SDL que
    // leer, así que la prueba es del editor (botón + asignación), no
    // una lectura del estado SDL en vivo; el texto lo dice.
    var testMode by rememberSaveable { mutableStateOf(false) }
    var lastTestedId by remember { mutableStateOf<String?>(null) }

    var deleteTarget by remember { mutableStateOf<TouchControl?>(null) }
    var deleteProfileTarget by remember { mutableStateOf<ControlProfile?>(null) }
    var showCreateProfile by rememberSaveable { mutableStateOf(false) }
    var newProfileName by rememberSaveable { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<ControlProfile?>(null) }
    var renameText by rememberSaveable { mutableStateOf("") }
    var exportTarget by remember { mutableStateOf<ControlProfile?>(null) }

    // P-C1 (0.2.61): el layout en arrastre (dragLayout) vive dentro de
    // TouchPreview; aquí solo llega el layout confirmado del flujo.
    // Durante un arrastre el editor muestra las posiciones confirmadas
    // (no las del arrastre en curso) y al soltar el flujo trae el final.
    val selected = layout.firstOrNull { it.id == selectedId } ?: layout.firstOrNull()
    // B3: los mandos se leen como flujo y se refrescan al entrar en la
    // pantalla (antes remember una sola vez: obsoleto si se conectaba
    // un mando después).
    val gamepads by vm.gamepadsFlow.collectAsState()
    LaunchedEffect(Unit) { vm.refreshGamepads() }

    val exportProfile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val target = exportTarget
        if (uri != null && target != null) vm.exportControlProfile(uri, target)
        exportTarget = null
    }
    val importProfile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.importControlProfile(uri)
    }

    // B12 (auditoría panel táctil): mientras se arrastra un botón
    // se desactiva el scroll vertical de la columna. Antes el
    // arrastre vertical competía con el scroll de la página: mover
    // un botón hacia arriba/abajo desplazaba la pantalla en vez del
    // botón (o lo movía menos que el dedo).
    var isDragging by remember { mutableStateOf(false) }

    fun updateControl(newControl: TouchControl) {
        vm.saveTouchLayout(layout.map { if (it.id == newControl.id) newControl else it })
    }

    // P-C4 (0.2.61): el brush del fondo se memoriza por sus entradas
    // (hex del fondo + tema oscuro): antes se reconstruía en cada
    // recomposición. isDarkUi es @Composable: se resuelve fuera del
    // remember y dentro solo entran valores planos.
    val backgroundHex = uiPrefs?.homeBackground?.hex
    val backgroundDark = isDarkUi(uiPrefs)
    val backgroundBrush = remember(backgroundHex, backgroundDark) { premiumBackgroundBrush(backgroundDark, backgroundHex) }

    Box(
        modifier = Modifier.fillMaxSize().background(backgroundBrush),
    ) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState(), enabled = !isDragging).padding(horizontal = 20.dp, vertical = 16.dp).widthIn(max = ContentMaxWidth).align(Alignment.TopCenter),
    ) {
        ScreenHeader("Controles táctiles", "Arrastra los botones en la vista previa para moverlos. Toca uno en la lista para editarlo: tamaño, opacidad, color, forma, etiqueta, reasignación y visibilidad.")
        NoteText(
            "Estos botones son los que aparecen sobre el juego embebido. Arrastra para moverlos; los cambios se aplican al reiniciar el juego.",
        )

        // Vista previa con arrastre real.
        // B13: la vista previa se dibuja SIEMPRE, aunque haya un
        // mando conectado y el ajuste de ocultar esté activo: antes
        // se sustituía por un texto y no se podía arrastrar ni ver
        // ningún botón del panel con el mando conectado. El ajuste
        // solo afecta al juego, no al editor.
        val hideAll = settings?.hideTouchWhenGamepadConnected == true && gamepads.isNotEmpty()
        // P-C1 (0.2.61): la vista previa vive en TouchPreview (abajo),
        // dueño de dragLayout, previewSize y el pointerInput.
        TouchPreview(
            layout = layout,
            testMode = testMode,
            lastTestedId = lastTestedId,
            onSelect = { selectedId = it },
            onTested = { lastTestedId = it; selectedId = it },
            onDraggingChanged = { isDragging = it },
            onPreviewSizeChanged = { previewSize = it },
            onDragEnd = { vm.saveTouchLayout(it) },
        )
        if (hideAll) {
            NoteText("Mando conectado (${gamepads.firstOrNull() ?: ""}): en el juego el overlay táctil quedará oculto por tu ajuste. Aquí se sigue viendo para poder editarlo.")
        }
        NoteText("Vista previa 16:9 (960x544, como el juego) con el estilo de cristal del overlay: lo que ves es lo que aparece sobre el juego.")

        // --- Probar mando (R4-P1-4) ---
        SectionTitle("Probar mando")
        SettingSwitch(
            "Probar botones en la vista previa",
            "Activa el modo prueba: toca un botón en la vista previa y se resalta con su etiqueta y su asignación. No mueve el botón ni guarda nada.",
            testMode,
        ) {
            testMode = it
            if (!it) lastTestedId = null
        }
        if (testMode) {
            val testedControl = layout.firstOrNull { it.id == lastTestedId }
            if (testedControl != null) {
                Text(
                    "Probado: ${touchControlTestLabel(testedControl)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    // R9-P2-02: ColTurquoise fijo sobre #E9EFF6 = 1.48:1
                    // en tema claro; en claro azul lavanda #6578B8.
                    color = if (isDarkUi(uiPrefs)) ColTurquoise else ColLavenderBlue,
                )
            } else {
                NoteText("Toca un botón en la vista previa para comprobar su etiqueta y su asignación.")
            }
            NoteText("La prueba comprueba el botón en el editor (etiqueta y asignación). Que llegue al juego se comprueba jugando, con el mando virtual activo: sin juego en curso no hay estado SDL que leer.")
        }

        // --- Perfiles ---
        SectionTitle("Perfiles de control")
        profiles.forEach { profile ->
            OutlinedButton(
                onClick = { vm.setActiveControlProfile(profile.id) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            ) {
                Text("${profile.name}${if (profile.isBuiltIn) " (predefinido)" else ""}${if (profile.id == activeProfileId) "  ← activo" else ""}")
            }
            FlowRow(modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { vm.duplicateControlProfile(profile.id) }) { Text("Duplicar") }
                TextButton(onClick = { renameTarget = profile; renameText = profile.name }) { Text("Renombrar") }
                // El último perfil no se puede borrar (el repositorio lo
                // rechaza): no se ofrece un diálogo que promete borrar y
                // luego falla. "Restaurar predefinidos que falten" lo recupera.
                TextButton(onClick = { deleteProfileTarget = profile }, enabled = profiles.size > 1) { Text("Borrar") }
                TextButton(onClick = { exportTarget = profile; exportProfile.launch("perfil-${profile.name}.txt") }) { Text("Exportar") }
            }
        }
        OutlinedButton(onClick = { showCreateProfile = true }, modifier = Modifier.fillMaxWidth()) { Text("Crear perfil nuevo (copia del activo)") }
        OutlinedButton(onClick = { importProfile.launch("*/*") }, modifier = Modifier.fillMaxWidth()) { Text("Importar perfil") }
        OutlinedButton(onClick = { vm.resetTouchLayout() }, modifier = Modifier.fillMaxWidth()) { Text("Restablecer los 3 perfiles predefinidos") }
        // M29: restoreBuiltIns() no tenía ninguna llamada; este botón repone
        // solo los predefinidos que falten, sin borrar los personalizados
        // (el de arriba los restablece todos y borra los personalizados).
        OutlinedButton(onClick = { vm.restoreBuiltInProfiles() }, modifier = Modifier.fillMaxWidth()) { Text("Restaurar predefinidos que falten") }

        // --- Elegir botón ---
        SectionTitle("Elegir botón")
        if (gamepads.isNotEmpty()) {
            Text("Mando conectado: ${gamepads.joinToString()}", fontWeight = FontWeight.SemiBold)
        }
        layout.forEach { control ->
            OutlinedButton(
                onClick = { selectedId = control.id },
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            ) {
                Text("${control.label} (${control.kind} → ${control.vitaButton.label})${if (!control.visible) " [oculto]" else ""}${if (control.id == selectedId) "  ←" else ""}")
            }
        }
        OutlinedButton(onClick = { vm.addTouchControl() }, modifier = Modifier.fillMaxWidth()) { Text("+ Añadir botón nuevo") }
        // P1-13: el stick y la zona Igniculus se pueden borrar, pero
        // antes no se podían volver a crear (solo se creaban botones).
        // Se ofrecen aquí solo cuando faltan en el perfil activo.
        if (layout.none { it.kind == "stick" }) {
            OutlinedButton(onClick = { vm.restoreMissingTouchControl("stick") }, modifier = Modifier.fillMaxWidth()) { Text("+ Añadir stick") }
        }
        if (layout.none { it.kind == "igniculus" }) {
            OutlinedButton(onClick = { vm.restoreMissingTouchControl("igniculus") }, modifier = Modifier.fillMaxWidth()) { Text("+ Añadir zona Igniculus") }
        }

        // --- Editar el seleccionado ---
        selected?.let { control ->
            SectionTitle("Editar: ${control.label}")
            // P-C7 (0.2.61): la etiqueta vive en un borrador local y
            // solo se guarda al perder el foco, pulsar Done, cambiar
            // de seleccionado o salir de la pantalla (ver saveLabel).
            // Antes cada tecla llamaba a updateControl -> saveTouchLayout
            // -> reescritura de todos los perfiles en DataStore.
            // Patron B5 (SettingSlider en ColTheme.kt): estado local
            // mientras se edita, guardar solo al terminar.
            var labelDraft by rememberSaveable(control.id, control.label) { mutableStateOf(control.label) }
            val focusManager = LocalFocusManager.current
            fun saveLabel() {
                val trimmed = labelDraft.take(12)
                if (trimmed != control.label) updateControl(control.copy(label = trimmed))
            }
            // R9-P2-06: la clave incluye control.label: si cambiar de
            // perfil (o borrar el activo) trae el mismo id con otra
            // etiqueta, el efecto viejo dispone y saveLabel() guarda
            // el borrador; tras un guardado normal es no-op
            // (trimmed == control.label).
            DisposableEffect(control.id, control.label) {
                onDispose { saveLabel() }
            }
            OutlinedTextField(
                value = labelDraft,
                onValueChange = { labelDraft = it.take(12) },
                label = { Text("Etiqueta (máx. 12)") },
                modifier = Modifier.fillMaxWidth()
                    .onFocusChanged { if (!it.isFocused) saveLabel() },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { saveLabel(); focusManager.clearFocus() }),
            )
            SettingSwitch("Visible en el overlay", "Oculto = no aparece ni estorba en el juego.", control.visible) {
                updateControl(control.copy(visible = it))
            }
            // P2-22: los sliders X/Y escribían la fracción directa
            // (0..100): un botón grande podía quedar medio fuera de
            // pantalla. El mismo clamp que el arrastre: el centro
            // ENTERO dentro de la vista previa.
            // R4-P1-4: los sliders quedan DESHABILITADOS hasta que la
            // vista previa se mide (previewSize distinto de cero). Si
            // aún no se midió, half = 0 (clamp 0..1, como antes) y el
            // slider y lo dibujado discreparían al guardar.
            val previewMeasured = previewSize.width > 0 && previewSize.height > 0
            if (!previewMeasured) {
                NoteText("Midiendo la vista previa: los sliders de posición se activan al medirla.")
            }
            val density = LocalDensity.current
            val halfXFrac = if (previewSize.width > 0) (with(density) { control.sizeDp.dp.toPx() } / 2f) / previewSize.width else 0f
            val halfYFrac = if (previewSize.height > 0) (with(density) { control.sizeDp.dp.toPx() } / 2f) / previewSize.height else 0f
            // 0.2.58 (R7): el slider X del salto llega a 110 % (su
            // centro de fábrica es 1.012); con el rango 0..100 el
            // slider mostraría 101 fuera de rango y al moverlo
            // perdería el valor del mockup. Los demás controles
            // siguen en 0..100.
            val xRange = if (control.id == "btn_jump") 0..110 else 0..100
            SettingSlider("Posición X", (control.xFraction * 100).toInt(), xRange, { updateControl(control.copy(xFraction = TouchLayoutRepository.clampCenterFraction(it / 100f, halfXFrac, if (control.id == "btn_jump") TouchLayoutRepository.JUMP_MAX_X_FRACTION else null))) }, enabled = previewMeasured) { "$it%" }
            SettingSlider("Posición Y", (control.yFraction * 100).toInt(), 0..100, { updateControl(control.copy(yFraction = TouchLayoutRepository.clampCenterFraction(it / 100f, halfYFrac))) }, enabled = previewMeasured) { "$it%" }
            SettingSlider("Tamaño", control.sizeDp, 40..260, { updateControl(control.copy(sizeDp = it)) }) { "${it}dp" }
            SettingSlider("Opacidad", (control.opacity * 100).toInt(), 10..100, { updateControl(control.copy(opacity = it / 100f)) }) { "$it%" }

            Text("Forma", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            FlowRow {
                ControlShape.entries.forEach { shape ->
                    FilterChip(
                        selected = control.shape == shape,
                        onClick = { updateControl(control.copy(shape = shape)) },
                        label = { Text(if (shape == ControlShape.CIRCLE) "Círculo" else "Cuadrado") },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
            }
            Text("Color", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            FlowRow {
                CONTROL_COLORS.forEach { hex ->
                    FilterChip(
                        selected = control.colorHex.equals(hex, ignoreCase = true),
                        onClick = { updateControl(control.copy(colorHex = hex)) },
                        label = { Text(hex) },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
            }
            if (control.kind == "button") {
                Text("Reasignar a botón del juego", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                FlowRow {
                    VitaButton.entries.forEach { button ->
                        FilterChip(
                            selected = control.vitaButton == button,
                            onClick = { updateControl(control.copy(vitaButton = button)) },
                            // R6-P2-24: LEFT_STICK, RIGHT_STICK y NONE
                            // no llegan al juego (son ejes o sin
                            // asignar: toSdlButton() devuelve null y el
                            // botón queda fantasma). No se excluyen del
                            // remap: el usuario puede quererlas como
                            // placeholder documentado, pero la opción
                            // avisa de que no tiene efecto en el juego.
                            label = {
                                Text(
                                    if (button == VitaButton.NONE || button == VitaButton.LEFT_STICK || button == VitaButton.RIGHT_STICK) {
                                        button.label + " · Sin efecto en el juego"
                                    } else {
                                        button.label
                                    },
                                )
                            },
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                }
            }
            if (control.kind == "stick") {
                Text("Modo del stick", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                FlowRow {
                    StickMode.entries.forEach { mode ->
                        FilterChip(
                            selected = control.stickMode == mode,
                            onClick = { updateControl(control.copy(stickMode = mode)) },
                            label = { Text(mode.label) },
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                }
            }
            if (control.kind == "igniculus") {
                Text("Modo Igniculus", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                FlowRow {
                    IgniculusMode.entries.forEach { mode ->
                        FilterChip(
                            selected = control.igniculusMode == mode,
                            onClick = { updateControl(control.copy(igniculusMode = mode)) },
                            label = { Text(mode.label) },
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                }
                SettingSlider("Sensibilidad Igniculus", (control.sensitivity * 100).toInt(), 50..200, { updateControl(control.copy(sensitivity = it / 100f)) }) { "${it / 100f}" }
                NoteText("Toque directo: la luciérnaga va donde tocas. Joystick: arrastras desde la zona como un stick.")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = { deleteTarget = control },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("🗑 Borrar este botón") }
        }

        BackButton(onBack)
    }
    }

    deleteTarget?.let { control ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("¿Borrar el botón ${control.label}?") },
            text = {
                Text(
                    if (control.kind == "stick" || control.kind == "igniculus") {
                        "Desaparecerá del layout en juego y no estorbará. Puedes volver a añadirlo después con el botón \"+ Añadir stick\" o \"+ Añadir zona Igniculus\" de esta pantalla."
                    } else {
                        "Desaparecerá del layout en juego y no estorbará. Puedes añadir otro botón nuevo después."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.deleteTouchControl(control.id); deleteTarget = null }) { Text("Borrar") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancelar") } },
        )
    }
    deleteProfileTarget?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleteProfileTarget = null },
            title = { Text("¿Borrar el perfil ${profile.name}?") },
            text = { Text("Se borra el perfil de control. Si era el activo, se activa el primer perfil que queda.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteControlProfile(profile.id); deleteProfileTarget = null }) { Text("Borrar") }
            },
            dismissButton = { TextButton(onClick = { deleteProfileTarget = null }) { Text("Cancelar") } },
        )
    }
    if (showCreateProfile) {
        AlertDialog(
            onDismissRequest = { showCreateProfile = false },
            title = { Text("Crear perfil nuevo") },
            text = {
                OutlinedTextField(
                    value = newProfileName,
                    onValueChange = { newProfileName = it },
                    label = { Text("Nombre del perfil") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.createControlProfile(newProfileName.ifBlank { "Perfil nuevo" }); newProfileName = ""; showCreateProfile = false }) { Text("Crear") }
            },
            dismissButton = { TextButton(onClick = { showCreateProfile = false }) { Text("Cancelar") } },
        )
    }
    renameTarget?.let { profile ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Renombrar perfil") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Nombre nuevo") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.renameControlProfile(profile.id, renameText); renameTarget = null }) { Text("Renombrar") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancelar") } },
        )
    }
}

/**
 * P-C1 (0.2.61): vista previa del editor, extraida de TouchLayoutScreen.
 * Dueña de dragLayout, previewSize, previewControls y el pointerInput:
 * cada pixel arrastrado solo recompone esta vista, no la pantalla entera
 * (perfiles, chooser y editor). La raiz pasa el layout confirmado y
 * recibe el final con onDragEnd; el arrastre, la selección y el tamaño
 * medido se le comunican con onDraggingChanged / onSelect / onTested /
 * onPreviewSizeChanged. Semantica preservada: onDragStart desactiva el
 * scroll de la raiz (via onDraggingChanged) y onDragCancel resetea el
 * arrastre y el layout local, igual que antes.
 */
@Composable
private fun TouchPreview(
    layout: List<TouchControl>,
    testMode: Boolean,
    lastTestedId: String?,
    onSelect: (String) -> Unit,
    onTested: (String) -> Unit,
    onDraggingChanged: (Boolean) -> Unit,
    onPreviewSizeChanged: (IntSize) -> Unit,
    onDragEnd: (List<TouchControl>) -> Unit,
) {
    // B5 (auditoría UI): durante el arrastre los cambios van a un layout
    // local (dragLayout) y solo se guardan en DataStore al soltar
    // (onDragEnd). Antes cada evento de arrastre escribía en DataStore.
    var dragLayout by remember { mutableStateOf<List<TouchControl>?>(null) }
    val displayLayout = dragLayout ?: layout
    var previewSize by remember { mutableStateOf(IntSize.Zero) }
    // B14: la zona Igniculus (115 dp, opacidad 0.25) se dibuja
    // PRIMERO, detrás de los botones. Antes iba la última y
    // tapaba por completo el botón Start (y parte de Y/X): en
    // la vista previa Start no se podía ni tocar ni arrastrar,
    // porque el toque lo recibía la zona Igniculus.
    val previewControls = displayLayout.filter { it.visible }
        .sortedBy { if (it.kind == "igniculus") 0 else 1 }
    // La altura se adapta a la pantalla (BoxWithConstraints): en
    // horizontal la vista previa fija de 300 dp se comía casi toda
    // la altura y no cabían ni la cabecera ni los botones.
    // R4-P1-4: previa 16:9 real (previewSizeForScreen, 960x544
    // como el juego): cabe en ancho y alto, sin altura fija.
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val previewSizeDp = previewSizeForScreen(maxWidth, maxHeight)
        Box(
            modifier = Modifier.width(previewSizeDp.width).height(previewSizeDp.height).align(Alignment.Center).background(ColCard)
                .clipToBounds()
                .onSizeChanged { previewSize = it; onPreviewSizeChanged(it) },
        ) {
            previewControls.forEach { control ->
                    val size = control.sizeDp.dp
                    val shape = if (control.shape == ControlShape.CIRCLE) CircleShape else RoundedCornerShape(8.dp)
                    val density = LocalDensity.current
                    val sizePx = with(density) { size.toPx() }
                    val isTested = testMode && lastTestedId == control.id
                    Box(
                        modifier = Modifier
                            .offset {
                                // B4 (auditoría UI): antes solo había tope
                                // izquierdo/superior y el botón se salía por
                                // derecha/abajo (hasta sizePx/2 fuera).
                                IntOffset(
                                    x = ((control.xFraction * previewSize.width) - sizePx / 2).toInt()
                                        .coerceIn(0, (previewSize.width - sizePx).toInt().coerceAtLeast(0)),
                                    y = ((control.yFraction * previewSize.height) - sizePx / 2).toInt()
                                        .coerceIn(0, (previewSize.height - sizePx).toInt().coerceAtLeast(0)),
                                )
                            }
                            .size(size)
                            .alpha(control.opacity)
                            .background(
                                // R4-P1-4: ambas ramas devuelven Brush.
                                // Antes la rama igniculus devolvía Color
                                // y la otra Brush: el if quedaba como Any
                                // y ninguna sobrecarga de background
                                // aplicaba (run #159). 0.2.56: la zona
                                // Igniculus es transparente fuera del
                                // orbe (como en el juego): el cristal va
                                // en el orbe interior (84%), no en toda
                                // la zona; el borde queda como anillo.
                                if (control.kind == "igniculus") SolidColor(Color.Transparent)
                                else touchPreviewGlassBrush(parseHex(control.colorHex)),
                                shape,
                            )
                            .border(
                                width = if (isTested) 2.dp else 1.dp,
                                color = if (isTested) ColTurquoise else ColIceBlue.copy(alpha = 0.7f),
                                shape = shape,
                            )
                            .pointerInput(control.id, testMode) {
                                if (testMode) {
                                    // R4-P1-4: en modo prueba el toque
                                    // no arrastra: resalta el botón y
                                    // lo selecciona para editarlo.
                                    detectTapGestures(onTap = {
                                        onTested(control.id)
                                    })
                                } else {
                                    detectDragGestures(
                                    onDragStart = { onDraggingChanged(true) },
                                    onDragEnd = {
                                        onDraggingChanged(false)
                                        dragLayout?.let { onDragEnd(it) }
                                        dragLayout = null
                                    },
                                    onDragCancel = { onDraggingChanged(false); dragLayout = null },
                                ) { change, dragAmount ->
                                    change.consume()
                                    if (previewSize.width > 0 && previewSize.height > 0) {
                                        val base = dragLayout ?: layout
                                        val current = base.firstOrNull { it.id == control.id } ?: control
                                        // B7: la fracción se fija con el
                                        // centro del botón entero dentro
                                        // de la vista previa (ver
                                        // clampCenterFraction). Antes se
                                        // fijaba en 0..1 y el dibujado
                                        // recortaba: al llegar al borde
                                        // el botón dejaba de moverse
                                        // pero la fracción seguía
                                        // cambiando, y al volver los
                                        // primeros píxeles no movían nada.
                                        // 0.2.58 (R7): el salto (btn_jump) admite
                                        // x hasta 1.1 (JUMP_MAX_X_FRACTION): su
                                        // centro de fábrica es 1.012 (mockup,
                                        // sobre la barra negra derecha); con el
                                        // tope normal el arrastre lo devolvería
                                        // a 0.937 y perdería el arreglo. La Y
                                        // sigue con el tope normal.
                                        val nx = TouchLayoutRepository.clampCenterFraction(
                                            current.xFraction + dragAmount.x / previewSize.width,
                                            (sizePx / 2f) / previewSize.width,
                                            if (control.id == "btn_jump") TouchLayoutRepository.JUMP_MAX_X_FRACTION else null,
                                        )
                                        val ny = TouchLayoutRepository.clampCenterFraction(
                                            current.yFraction + dragAmount.y / previewSize.height,
                                            (sizePx / 2f) / previewSize.height,
                                        )
                                        onSelect(control.id)
                                        dragLayout = base.map {
                                            if (it.id == control.id) it.copy(xFraction = nx, yFraction = ny) else it
                                        }
                                    }
                                }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        // 0.2.56: orbe interior como en el juego:
                        // pomo del stick (42% del tamano) y orbe de
                        // Igniculus (84% de la zona), con el mismo
                        // cristal. La etiqueta va encima de todo.
                        if (control.kind == "stick") {
                            Box(
                                modifier = Modifier.size(size * 0.42f)
                                    .background(touchPreviewGlassBrush(parseHex(control.colorHex)), CircleShape)
                                    .border(1.dp, ColIceBlue.copy(alpha = 0.7f), CircleShape),
                            )
                        } else if (control.kind == "igniculus") {
                            Box(
                                modifier = Modifier.size(size * 0.84f)
                                    .background(touchPreviewGlassBrush(parseHex(control.colorHex)), CircleShape)
                                    .border(1.dp, ColIceBlue.copy(alpha = 0.7f), CircleShape),
                            )
                        }
                        Text(control.label, fontWeight = FontWeight.Bold, color = ColNight, style = MaterialTheme.typography.bodySmall)
                    }
                }
        }
    }
}
