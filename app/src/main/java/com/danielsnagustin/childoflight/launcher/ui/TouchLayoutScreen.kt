package com.danielsnagustin.childoflight.launcher.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.danielsnagustin.childoflight.launcher.data.ControlProfile
import com.danielsnagustin.childoflight.launcher.data.ControlShape
import com.danielsnagustin.childoflight.launcher.data.IgniculusMode
import com.danielsnagustin.childoflight.launcher.data.StickMode
import com.danielsnagustin.childoflight.launcher.data.TouchControl
import com.danielsnagustin.childoflight.launcher.data.VitaButton

private val CONTROL_COLORS = listOf("#F2B705", "#7FB3D5", "#4CAF50", "#E57373", "#BA68C8", "#FFFFFF")

private fun parseHex(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (_: Exception) {
    Color(0xFFF2B705)
}

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
    var selectedId by remember { mutableStateOf("stick") }
    var previewSize by remember { mutableStateOf(IntSize.Zero) }
    var deleteTarget by remember { mutableStateOf<TouchControl?>(null) }
    var deleteProfileTarget by remember { mutableStateOf<ControlProfile?>(null) }
    var showCreateProfile by remember { mutableStateOf(false) }
    var newProfileName by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<ControlProfile?>(null) }
    var renameText by remember { mutableStateOf("") }
    var exportTarget by remember { mutableStateOf<ControlProfile?>(null) }

    // B5 (auditoría UI): durante el arrastre los cambios van a un layout
    // local (dragLayout) y solo se guardan en DataStore al soltar
    // (onDragEnd). Antes cada evento de arrastre escribía en DataStore.
    var dragLayout by remember { mutableStateOf<List<TouchControl>?>(null) }
    val displayLayout = dragLayout ?: layout
    val selected = displayLayout.firstOrNull { it.id == selectedId } ?: displayLayout.firstOrNull()
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

    fun updateControl(newControl: TouchControl) {
        vm.saveTouchLayout(displayLayout.map { if (it.id == newControl.id) newControl else it })
    }

    Box(
        modifier = Modifier.fillMaxSize().background(premiumBackgroundBrush()),
    ) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        ScreenHeader("Controles táctiles", "Arrastra los botones en la vista previa para moverlos. Toca uno en la lista para editarlo: tamaño, opacidad, color, forma, etiqueta, remap y visibilidad.")
        NoteText(
            "En Etapa 1 este layout solo se GUARDA aquí: el overlay táctil aparecerá dentro del juego cuando llegue el núcleo embebido (Etapa 2). " +
                "Por ahora, Vita3K externo usa sus propios controles táctiles y los ajustes de aquí no se ven en el juego.",
        )

        // Vista previa con arrastre real.
        Box(
            modifier = Modifier.fillMaxWidth().height(300.dp).background(ColCard)
                .clipToBounds()
                .onSizeChanged { previewSize = it },
        ) {
            val hideAll = settings?.hideTouchWhenGamepadConnected == true && gamepads.isNotEmpty()
            if (hideAll) {
                Text(
                    "Mando conectado (${gamepads.firstOrNull() ?: ""}): overlay táctil oculto por tu ajuste.",
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                )
            } else {
                displayLayout.filter { it.visible }.forEach { control ->
                    val size = control.sizeDp.dp
                    val shape = if (control.shape == ControlShape.CIRCLE) CircleShape else RoundedCornerShape(8.dp)
                    val density = LocalDensity.current
                    val sizePx = with(density) { size.toPx() }
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
                            .background(parseHex(control.colorHex), shape)
                            .pointerInput(control.id) {
                                detectDragGestures(
                                    onDragEnd = {
                                        dragLayout?.let { vm.saveTouchLayout(it) }
                                        dragLayout = null
                                    },
                                    onDragCancel = { dragLayout = null },
                                ) { change, dragAmount ->
                                    change.consume()
                                    if (previewSize.width > 0 && previewSize.height > 0) {
                                        val base = dragLayout ?: layout
                                        val current = base.firstOrNull { it.id == control.id } ?: control
                                        val nx = (current.xFraction + dragAmount.x / previewSize.width).coerceIn(0f, 1f)
                                        val ny = (current.yFraction + dragAmount.y / previewSize.height).coerceIn(0f, 1f)
                                        selectedId = control.id
                                        dragLayout = base.map {
                                            if (it.id == control.id) it.copy(xFraction = nx, yFraction = ny) else it
                                        }
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(control.label, fontWeight = FontWeight.Bold, color = ColNight, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        NoteText("Vista previa esquemática: las fracciones X/Y se aplican a la pantalla real en el juego.")

        // --- Perfiles ---
        SectionTitle("Perfiles de control")
        profiles.forEach { profile ->
            OutlinedButton(
                onClick = { vm.setActiveControlProfile(profile.id) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            ) {
                Text("${profile.name}${if (profile.id == activeProfileId) "  ← activo" else ""}${if (profile.isBuiltIn) " (predefinido)" else ""}")
            }
            FlowRow(modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { vm.duplicateControlProfile(profile.id) }) { Text("Duplicar") }
                TextButton(onClick = { renameTarget = profile; renameText = profile.name }) { Text("Renombrar") }
                TextButton(onClick = { deleteProfileTarget = profile }) { Text("Borrar") }
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
        settings?.let { s ->
            SettingSwitch(
                "Ocultar controles táctiles si hay mando conectado",
                "Si borraste todos los botones y usas mando físico, el overlay no estorba.",
                s.hideTouchWhenGamepadConnected,
            ) { vm.saveSettings(s.copy(hideTouchWhenGamepadConnected = it)) }
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

        // --- Editar el seleccionado ---
        selected?.let { control ->
            SectionTitle("Editar: ${control.label}")
            OutlinedTextField(
                value = control.label,
                onValueChange = { updateControl(control.copy(label = it.take(12))) },
                label = { Text("Etiqueta (máx. 12)") },
                modifier = Modifier.fillMaxWidth(),
            )
            SettingSwitch("Visible en el overlay", "Oculto = no aparece ni estorba en el juego.", control.visible) {
                updateControl(control.copy(visible = it))
            }
            SettingSlider("Posición X", (control.xFraction * 100).toInt(), 0..100, { updateControl(control.copy(xFraction = it / 100f)) }) { "$it%" }
            SettingSlider("Posición Y", (control.yFraction * 100).toInt(), 0..100, { updateControl(control.copy(yFraction = it / 100f)) }) { "$it%" }
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
                Text("Remapear a botón de Vita", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                FlowRow {
                    VitaButton.entries.forEach { button ->
                        FilterChip(
                            selected = control.vitaButton == button,
                            onClick = { updateControl(control.copy(vitaButton = button)) },
                            label = { Text(button.label) },
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
            text = { Text("Desaparecerá del layout en juego y no estorbará. Puedes añadir otro botón nuevo después.") },
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
