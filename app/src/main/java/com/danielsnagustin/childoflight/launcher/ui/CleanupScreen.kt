package com.danielsnagustin.childoflight.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.danielsnagustin.childoflight.launcher.data.DownloadsCleanup
import com.danielsnagustin.childoflight.launcher.model.GpuProfileId

/**
 * "Limpiar no usados": lista lo que no se usa y permite borrarlo.
 * - Botones táctiles ocultos (se borran definitivamente del layout).
 * - Perfiles de control que no son el activo.
 * - Presets de GPU que no son el activo (se OCULTAN de la lista, no se
 *   borra código: los presets son predefinidos; "Mostrar todos" los recupera).
 * - Driver Turnip instalado pero sin usar (driver actual = Sistema).
 * - .pkg de juego guardado cuyo juego ya está extraído (copia
 *   redundante: el juego ocupa el doble, .pkg + extraído).
 * - Respaldos .bak huérfanos (destino existe: la instalación
 *   completó y el respaldo quedó atrás; se muestran con su tamaño).
 * - DLCs instalados (borra de ux0/addcont/<TITLE>/<DLC>/).
 * El borrado en lote pide confirmación y dice exactamente qué borra.
 * Los saves NO se borran desde aquí (R6-P2-16): la limpieza nunca
 * borra partidas guardadas (regla del repo). Las partidas se borran
 * desde Guardados → "Borrar datos del juego".
 */
@Composable
fun CleanupScreen(vm: LauncherViewModel, onBack: () -> Unit) {
    val layout by vm.touchLayoutFlow.collectAsState(initial = emptyList())
    val profiles by vm.profilesFlow.collectAsState(initial = emptyList())
    val activeProfileId by vm.activeProfileIdFlow.collectAsState(initial = "default-g99")
    val settings by vm.settingsFlow.collectAsState(initial = null)
    val uiPrefs = LocalUiPreferences.current
    val status by vm.status.collectAsState()
    var showBatchConfirm by rememberSaveable { mutableStateOf(false) }
    var deleteDlcTarget by rememberSaveable { mutableStateOf<String?>(null) }
    // R2-P1-06: los borrados de botón y de perfil pedían confirmación
    // igual que el de DLC (mismo patrón: id en estado + AlertDialog).
    // Antes ejecutaban vm.deleteTouchControl / deleteControlProfile
    // directamente en el onClick: un toque accidental borraba para
    // siempre un botón editado a mano o un perfil entero.
    var deleteControlTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteProfileTarget by rememberSaveable { mutableStateOf<String?>(null) }
    // El borrado del driver Turnip también pide confirmación: un toque
    // accidental borraba el .zip instalado sin aviso (el borrado en lote
    // ya tenía su propio diálogo).
    var showDeleteTurnipConfirm by rememberSaveable { mutableStateOf(false) }
    var showDeleteInstalledPkgConfirm by rememberSaveable { mutableStateOf(false) }
    // R6-P1-02(b): confirmación del borrado del .pkg guardado SIN
    // juego extraído (update importado como juego por error): mismo
    // patrón que showDeleteInstalledPkgConfirm (booleano + AlertDialog).
    var showDeleteUnextractedPkgConfirm by rememberSaveable { mutableStateOf(false) }

    val hiddenButtons = layout.filter { !it.visible }
    val unusedProfiles = profiles.filter { it.id != activeProfileId }
    val unusedPresets = GpuProfileId.entries.filter {
        it != settings?.activeProfile && it !in (uiPrefs?.hiddenPresets ?: emptySet())
    }
    val turnipUnused = settings?.turnipInstalled == true &&
        settings?.gpuDriver?.name == "SYSTEM"
    // P2-31: respaldos .bak huérfanos (destino existe). Se revisan al
    // entrar en la pantalla; null = aún revisando. Delegated property:
    // los valores derivados se calculan con ?.let (no smart-cast).
    val orphanBak by vm.orphanBackupsFlow.collectAsState(initial = null)
    val orphanCount = orphanBak?.first ?: 0
    val orphanBytes = orphanBak?.second ?: 0L
    var showDeleteOrphanBakConfirm by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.refreshOrphanBackups() }

    // R5-A2-3: fondo según el tema (antes el overload sin argumentos
    // fijaba dark=true y la pantalla quedaba oscura con Tema Claro).
    Box(
        modifier = Modifier.fillMaxSize().background(premiumBackgroundBrush(isDarkUi(uiPrefs), uiPrefs?.homeBackground?.hex)),
    ) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp).widthIn(max = ContentMaxWidth).align(Alignment.TopCenter),
    ) {
        ScreenHeader("Limpiar no usados", "Todo lo que se borra aquí se puede volver a crear o importar, salvo los saves.")

        SectionTitle("Botones táctiles ocultos (${hiddenButtons.size})")
        if (hiddenButtons.isEmpty()) Text("Ninguno: todos los botones están visibles.")
        hiddenButtons.forEach { control ->
            OutlinedButton(
                onClick = { deleteControlTarget = control.id },
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            ) { Text("🗑 Borrar definitivamente: ${control.label}") }
        }

        SectionTitle("Perfiles de control sin usar (${unusedProfiles.size})")
        if (unusedProfiles.isEmpty()) Text("Ninguno: solo queda el perfil activo.")
        unusedProfiles.forEach { profile ->
            OutlinedButton(
                onClick = { deleteProfileTarget = profile.id },
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            ) { Text("🗑 Borrar perfil: ${profile.name}") }
        }

        SectionTitle("Presets de GPU sin usar (${unusedPresets.size})")
        NoteText("Ocultarlos de la lista de presets. No se borra código; \"Mostrar todos\" los recupera.")
        unusedPresets.forEach { preset ->
            OutlinedButton(
                onClick = { vm.hideGpuPreset(preset) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            ) { Text("Ocultar preset: ${preset.label}") }
        }
        // R2-P2-05: deshabilitado cuando ya se muestran todos (no hay
        // presets ocultos): antes siempre estaba activo y no hacía nada
        // visible al pulsarlo en ese caso.
        OutlinedButton(
            onClick = { vm.showAllGpuPresets() },
            modifier = Modifier.fillMaxWidth(),
            enabled = (uiPrefs?.hiddenPresets?.isNotEmpty() == true),
        ) { Text("Mostrar todos los presets") }

        SectionTitle("Driver Turnip")
        if (turnipUnused) {
            Text("Turnip instalado (${settings?.turnipVersion}) pero no se usa: el driver actual es Sistema.", fontWeight = FontWeight.SemiBold)
            OutlinedButton(onClick = { showDeleteTurnipConfirm = true }, modifier = Modifier.fillMaxWidth()) { Text("🗑 Borrar driver Turnip") }
        } else if (settings?.turnipInstalled == true) {
            Text("Turnip en uso (${settings?.turnipVersion}). No se marca como no usado.")
        } else {
            Text("No hay driver Turnip instalado.")
        }

        SectionTitle(".pkg guardado ya instalado")
        val installedPkgRedundant = status.gameExtracted && status.pkgTitleId != null && status.pkgTitleId == status.extractedTitleId
        if (!installedPkgRedundant) Text("Ninguno: no hay .pkg guardado del juego extraído.")
        else {
            Text("El juego ya está extraído y es el que corre; el .pkg guardado es una copia que ocupa lo mismo otra vez.", fontWeight = FontWeight.SemiBold)
            OutlinedButton(onClick = { showDeleteInstalledPkgConfirm = true }, modifier = Modifier.fillMaxWidth()) { Text("🗑 Borrar .pkg guardado (${DownloadsCleanup.formatBytes(status.pkgSizeBytes)})") }
        }
        NoteText("Solo se borra el .pkg. La licencia (work.bin) no se borra y el juego extraído no se toca.")

        // R6-P1-02(b): .pkg de juego guardado SIN juego extraído. El
        // botón de arriba solo aparece con el juego extraído, así que
        // un update importado como juego (JUGAR entra en bucle) no se
        // podía borrar desde ningún sitio. Este botón usa el gancho
        // sin puertas de estado (deleteStoredGamePkgAnyState). Ojo: es
        // también el estado de un .pkg de juego legítimo recién
        // importado, aún sin instalar: el diálogo avisa de que es la
        // única copia. Juego y update comparten content_type 0x15 y el
        // mismo Content ID, así que la app no puede distinguirlos por
        // el header: la decisión es del usuario, con el aviso claro.
        SectionTitle(".pkg guardado sin instalar")
        if (!status.storedGamePkgWithoutExtracted) Text("Ninguno: no hay .pkg de juego guardado sin instalar.")
        else {
            Text("Hay un .pkg de juego guardado (${DownloadsCleanup.formatBytes(status.pkgSizeBytes)}) y el juego no está extraído. Si es una actualización que se importó como juego por error, JUGAR entra en bucle: bórralo aquí e importa de nuevo el juego y la actualización por separado.", fontWeight = FontWeight.SemiBold)
            OutlinedButton(onClick = { showDeleteUnextractedPkgConfirm = true }, modifier = Modifier.fillMaxWidth()) { Text("🗑 Borrar .pkg guardado sin instalar (${DownloadsCleanup.formatBytes(status.pkgSizeBytes)})") }
        }
        NoteText("Solo se borra el .pkg. La licencia (work.bin) no se borra. Como el juego no está extraído, este .pkg es la única copia: si es el juego de verdad, tendrás que importarlo de nuevo.")

        // P2-31: respaldos .bak huérfanos: la instalación completó y
        // el respaldo viejo quedó atrás ocupando espacio (pueden ser
        // GBs). Solo se borran con confirmación; un .bak cuyo destino
        // falta no es huérfano y nunca aparece aquí.
        SectionTitle("Respaldos .bak huérfanos")
        if (orphanBak == null) Text("Revisando…")
        else if (orphanCount == 0) Text("Ninguno: no hay respaldos .bak de instalaciones que ya completaron.")
        else {
            Text(
                "Hay $orphanCount respaldo(s) .bak de instalaciones que ya completaron (${DownloadsCleanup.formatBytes(orphanBytes)}): el juego o DLC nuevo ya está en su sitio y el respaldo viejo solo ocupa espacio.",
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedButton(onClick = { showDeleteOrphanBakConfirm = true }, modifier = Modifier.fillMaxWidth()) {
                Text("🗑 Borrar respaldos .bak (${DownloadsCleanup.formatBytes(orphanBytes)})")
            }
        }
        NoteText("Solo se borran respaldos cuyo juego o DLC ya está instalado en su sitio. Un .bak cuyo destino falta NO se toca: es el único respaldo del juego anterior y se restaura solo si hace falta.")

        SectionTitle("DLCs instalados (${status.dlcCount})")
        if (status.dlcs.isEmpty()) Text("Ninguno.")
        status.dlcs.forEach { dlc ->
            OutlinedButton(
                onClick = { deleteDlcTarget = dlc.dlcId },
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            ) { Text("🗑 Borrar DLC: ${dlc.dlcId} (${DownloadsCleanup.formatBytes(dlc.sizeBytes)})") }
        }

        SectionTitle("Borrar en lote")
        OutlinedButton(onClick = { showBatchConfirm = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Borrar todo lo no usado de esta pantalla")
        }
        NoteText("En lote: borra botones ocultos, perfiles sin usar, oculta presets sin usar y borra el Turnip sin usar. NO borra DLCs ni saves: esos siempre se borran uno a uno con confirmación.")

        BackButton(onBack)
    }
    }

    if (showBatchConfirm) {
        AlertDialog(
            onDismissRequest = { showBatchConfirm = false },
            title = { Text("¿Borrar todo lo no usado?") },
            text = {
                Text(
                    "Se borrarán: ${hiddenButtons.size} botones ocultos, ${unusedProfiles.size} perfiles sin usar, " +
                        "${unusedPresets.size} presets se ocultarán, y el Turnip sin usar si lo hay. " +
                        "DLCs y saves NO se tocan en lote.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    hiddenButtons.forEach { vm.deleteTouchControl(it.id) }
                    unusedProfiles.forEach { vm.deleteControlProfile(it.id) }
                    vm.hideGpuPresets(unusedPresets.toSet())
                    if (turnipUnused) vm.deleteTurnipDriver()
                    showBatchConfirm = false
                }) { Text("Borrar todo") }
            },
            dismissButton = { TextButton(onClick = { showBatchConfirm = false }) { Text("Cancelar") } },
        )
    }
    if (showDeleteOrphanBakConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteOrphanBakConfirm = false },
            title = { Text("¿Borrar los respaldos .bak huérfanos?") },
            text = {
                Text(
                    "Se borrarán $orphanCount respaldo(s) (${DownloadsCleanup.formatBytes(orphanBytes)}). " +
                        "El juego y los DLCs instalados no se tocan.",
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.deleteOrphanBackups(); showDeleteOrphanBakConfirm = false }) { Text("Borrar") }
            },
            dismissButton = { TextButton(onClick = { showDeleteOrphanBakConfirm = false }) { Text("Cancelar") } },
        )
    }
    deleteControlTarget?.let { controlId ->
        val control = layout.firstOrNull { it.id == controlId }
        AlertDialog(
            onDismissRequest = { deleteControlTarget = null },
            title = { Text("¿Borrar definitivamente el botón ${control?.label ?: ""}?") },
            text = { Text("Se borra del layout para siempre. Puedes añadir otro botón nuevo después desde Controles táctiles.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteTouchControl(controlId); deleteControlTarget = null }) { Text("Borrar") }
            },
            dismissButton = { TextButton(onClick = { deleteControlTarget = null }) { Text("Cancelar") } },
        )
    }
    deleteProfileTarget?.let { profileId ->
        val profile = profiles.firstOrNull { it.id == profileId }
        AlertDialog(
            onDismissRequest = { deleteProfileTarget = null },
            title = { Text("¿Borrar el perfil ${profile?.name ?: ""}?") },
            text = { Text("Se borra el perfil de control entero. Si era el activo, se activa el primer perfil que queda.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteControlProfile(profileId); deleteProfileTarget = null }) { Text("Borrar") }
            },
            dismissButton = { TextButton(onClick = { deleteProfileTarget = null }) { Text("Cancelar") } },
        )
    }
    if (showDeleteTurnipConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteTurnipConfirm = false },
            title = { Text("¿Borrar el driver Turnip?") },
            text = { Text("Se borra el driver Turnip instalado (${settings?.turnipVersion ?: ""}). El juego seguirá usando el driver del sistema; puedes reinstalar Turnip después desde Ajustes → GPU.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteTurnipDriver(); showDeleteTurnipConfirm = false }) { Text("Borrar") }
            },
            dismissButton = { TextButton(onClick = { showDeleteTurnipConfirm = false }) { Text("Cancelar") } },
        )
    }
    deleteDlcTarget?.let { dlcId ->
        val isPkgDlc = status.dlcs.firstOrNull { it.dlcId == dlcId }?.isPkg == true
        AlertDialog(
            onDismissRequest = { deleteDlcTarget = null },
            title = { Text("¿Borrar el DLC $dlcId?") },
            text = {
                Text(
                    if (isPkgDlc) {
                        "Se borra el .pkg guardado y su licencia. Solo vuelve si importas de nuevo tu copia del DLC."
                    } else {
                        "Se borra el DLC instalado. Solo vuelve si importas de nuevo tu copia del DLC."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.deleteDlc(dlcId); deleteDlcTarget = null }) { Text("Borrar DLC") }
            },
            dismissButton = { TextButton(onClick = { deleteDlcTarget = null }) { Text("Cancelar") } },
        )
    }
    if (showDeleteUnextractedPkgConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteUnextractedPkgConfirm = false },
            title = { Text("¿Borrar el .pkg guardado sin instalar?") },
            text = { Text("Se borra el .pkg guardado de ${status.pkgTitleId?.id} (${DownloadsCleanup.formatBytes(status.pkgSizeBytes)}). El juego NO está extraído, así que este .pkg es su única copia: bórralo solo si es una actualización que se importó como juego por error y JUGAR entra en bucle. No se borra ningún juego instalado ni partidas (no hay juego extraído) y la licencia no se toca. Solo vuelve si importas de nuevo tu copia del .pkg.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteStoredGamePkgAnyState(); showDeleteUnextractedPkgConfirm = false }) { Text("Borrar .pkg") }
            },
            dismissButton = { TextButton(onClick = { showDeleteUnextractedPkgConfirm = false }) { Text("Cancelar") } },
        )
    }
    if (showDeleteInstalledPkgConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteInstalledPkgConfirm = false },
            title = { Text("¿Borrar el .pkg guardado?") },
            text = { Text("Se borra el .pkg guardado de ${status.pkgTitleId?.id} (${DownloadsCleanup.formatBytes(status.pkgSizeBytes)}). El juego extraído sigue instalado y la licencia no se toca. Solo vuelve si importas de nuevo tu copia del .pkg.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteInstalledGamePkgs(); showDeleteInstalledPkgConfirm = false }) { Text("Borrar .pkg") }
            },
            dismissButton = { TextButton(onClick = { showDeleteInstalledPkgConfirm = false }) { Text("Cancelar") } },
        )
    }
}
