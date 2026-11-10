package com.danielsnagustin.childoflight.launcher.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.danielsnagustin.childoflight.launcher.data.DownloadsCleanup
import com.danielsnagustin.childoflight.launcher.data.FirmwareResolver
import com.danielsnagustin.childoflight.launcher.data.FirmwareStatus
import com.danielsnagustin.childoflight.launcher.model.AppLanguage
import com.danielsnagustin.childoflight.launcher.model.GameStatus

/**
 * Pantalla de Importación: juego principal, DLCs, updates y
 * firmware, todo en un solo lugar.
 *
 * Diseño (2026-11-08, petición del usuario: "no se entiende con
 * tanto en esta parte"): cada tarjeta muestra SOLO su estado
 * (✓ Guardado / ✓ Instalado / Ausente) + un dato corto + UNA
 * acción como mucho. Nada de párrafos explicativos: lo ya subido
 * no vuelve a pedir subir, solo queda como guardado con un
 * "Reemplazar" pequeño. Los botones grandes de Importar solo
 * aparecen cuando el elemento falta.
 *
 * Estados REALES desde GameStatus (disco): nada ficticio. La
 * licencia pendiente (work.bin) y la copia a la app externa viven
 * aquí solo cuando hacen falta: la copia externa es una opción
 * avanzada y solo se muestra si el núcleo embebido NO está
 * presente (con núcleo, el .pkg se instala solo al pulsar JUGAR).
 *
 * Progreso (honesto):
 * - Copia del archivo (sin hook de progreso en copyUriAtomic):
 *   estado indeterminado ("Importando… <nombre>") + resultado.
 *   Nunca se inventa un %.
 * - Instalación en el núcleo (firmware o .pkg al jugar): % real
 *   del núcleo por installProgress (percent + mensaje del núcleo).
 */
@Composable
fun ImportScreen(vm: LauncherViewModel, onBack: () -> Unit) {
    // Ronda 0.2.55 (refuerzo, hallazgo extra no listado en la
    // auditoría): ImportScreen recogía settingsFlow arriba solo para
    // dos booleanos de la entrega a la app externa
    // (pkgDeliveredToVita3k / pkgInstalledInVita3k): cada cambio de
    // ajuste recomponía la pantalla entera. Esos booleanos los recoge
    // ExternalDeliveryBlock dentro de sí (abajo).
    val uiPrefs = LocalUiPreferences.current
    // R6-P1-10: textos según el tema (en Claro, blanco sobre tarjeta
    // casi blanca no se lee). Las pantallas de Ajustes ya lo hacen así.
    val dark = isDarkUi(uiPrefs)
    val strings = stringsFor(uiPrefs?.language ?: AppLanguage.SPANISH)
    val status by vm.status.collectAsState()
    val importing by vm.importing.collectAsState()
    // Ronda 0.2.55 P1-11: installProgress NO se recoge aquí arriba:
    // cada % recomponía TODO el LazyColumn. Lo recoge
    // ImportProgressCard dentro de sí, como hace HomeScreen con
    // LaunchProgressCard. importing sí se queda arriba: los botones
    // lo leen para deshabilitarse y cambia pocas veces.
    val pendingLicense by vm.pendingPkgLicense.collectAsState()

    val pickGame = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.importGame(uri)
    }
    val pickDlc = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.importDlc(uri)
    }
    val pickUpdate = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.importUpdate(uri)
    }
    val pickGameFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.importGameFolder(uri)
    }
    val pickDlcFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.importDlcFolder(uri)
    }
    val pickWorkBin = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.importWorkBin(uri)
    }
    // Firmware: mismo flujo del ViewModel.
    val firmwareStatus by vm.firmwareStatusFlow.collectAsState()
    val firmwareInstalling by vm.firmwareInstallingFlow.collectAsState()
    val embeddedCore by vm.embeddedCoreFlow.collectAsState()
    var showDeleteFirmwareConfirm by rememberSaveable { mutableStateOf(false) }
    val pickFirmware = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.importFirmware(uri)
    }

    // R5-A2-3: fondo según el tema (antes el overload sin argumentos
    // fijaba dark=true y la pantalla quedaba oscura con Tema Claro).
    Box(
        modifier = Modifier.fillMaxSize().background(premiumBackgroundBrush(isDarkUi(uiPrefs), uiPrefs?.homeBackground?.hex)),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp).widthIn(max = ContentMaxWidth).align(Alignment.TopCenter),
        ) {
            item {
                ScreenHeader(strings.importTitle, strings.importSubtitle)
            }
            // --- Progreso: copia indeterminada, instalación en núcleo con % real ---
            // Ronda 0.2.55 P1-11: la tarjeta vive en ImportProgressCard
            // y recoge installProgress dentro: cada % recompone solo
            // la tarjeta, no toda la pantalla de Importar.
            item {
                ImportProgressCard(vm, strings, importing)
            }
            // --- Juego principal: estado + un dato + una acción ---
            item {
                SectionTitle(strings.mainGame)
                PremiumCard {
                    if (status.gameExtracted) {
                        Text(
                            "✓ ${strings.installed} · ${status.extractedTitleId?.id ?: status.titleId?.id ?: "—"} · ${DownloadsCleanup.formatBytes(status.gameSizeBytes)}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = cardTextColor(dark),
                        )
                        if (status.pkgInstalled) {
                            // R6-P1-04: si la licencia ya está registrada
                            // en el núcleo, no pedir el work.bin: JUGAR
                            // funciona sin él.
                            if (status.licenseInstalledInCore) {
                                Text(
                                    "Licencia: instalada en el núcleo ✓",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = ColTurquoise,
                                )
                            } else {
                                Text(
                                    "Licencia (work.bin): ${if (status.pkgLicensePresent) "guardada ✓" else "falta ✗"}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (status.pkgLicensePresent) ColTurquoise else cardTextColor(dark),
                                )
                            }
                        }
                        TextButton(onClick = { pickGame.launch("*/*") }, enabled = !importing) { Text("Reemplazar") }
                    } else if (status.pkgInstalled) {
                        Text(
                            "✓ Guardado · ${status.pkgTitleId?.id ?: "—"} · ${DownloadsCleanup.formatBytes(status.pkgSizeBytes)}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = cardTextColor(dark),
                        )
                        Text(
                            "Se instala solo al pulsar JUGAR.",
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedTextColor(dark),
                        )
                        // R6-P1-04: si la licencia ya está registrada
                        // en el núcleo, no pedir el work.bin.
                        if (status.licenseInstalledInCore) {
                            Text(
                                "Licencia: instalada en el núcleo ✓",
                                style = MaterialTheme.typography.bodyMedium,
                                color = ColTurquoise,
                            )
                        } else {
                            Text(
                                "Licencia (work.bin): ${if (status.pkgLicensePresent) "guardada ✓" else "falta ✗"}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (status.pkgLicensePresent) ColTurquoise else cardTextColor(dark),
                            )
                        }
                        TextButton(onClick = { pickGame.launch("*/*") }, enabled = !importing) { Text("Reemplazar") }
                    } else {
                        Text(
                            strings.absent,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = cardTextColor(dark),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        SecondaryButton("Importar juego (.zip / .vpk / .pkg)", onClick = { pickGame.launch("*/*") }, enabled = !importing)
                        TextButton(onClick = { pickGameFolder.launch(null) }, enabled = !importing) { Text("o importar carpeta") }
                    }
                    // Copia a la app externa: solo opción avanzada y
                    // solo si el núcleo NO está presente. Con núcleo,
                    // el .pkg se instala solo al pulsar JUGAR.
                    if (status.pkgInstalled && embeddedCore != true) {
                        ExternalDeliveryBlock(vm, status, importing)
                    }
                }
            }
            // --- DLC ---
            item {
                SectionTitle(strings.dlcSection)
                PremiumCard {
                    if (status.dlcs.isEmpty()) {
                        Text(
                            strings.absent,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = cardTextColor(dark),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        SecondaryButton("Importar DLC (.zip / .pkg)", onClick = { pickDlc.launch("*/*") }, enabled = !importing)
                        TextButton(onClick = { pickDlcFolder.launch(null) }, enabled = !importing) { Text("o importar carpeta") }
                    } else {
                        status.dlcs.forEach { dlc ->
                            // Ronda 0.2.55 P2-20: key por DLC (dentro de
                            // la tarjeta no cabe item(key) de LazyColumn:
                            // key() de Compose da la misma identidad
                            // estable a cada fila).
                            key(dlc.dlcId) {
                                Text(
                                    "✓ ${dlc.dlcId} · ${DownloadsCleanup.formatBytes(dlc.sizeBytes)}" + if (dlc.isPkg) " (.pkg)" else "",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = cardTextColor(dark),
                                )
                            }
                        }
                        TextButton(onClick = { pickDlc.launch("*/*") }, enabled = !importing) { Text("Añadir otro DLC") }
                    }
                }
            }
            // --- Actualizaciones ---
            item {
                SectionTitle(strings.updatesSection)
                PremiumCard {
                    if (status.updateInstalled) {
                        Text(
                            "✓ ${strings.installed}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = cardTextColor(dark),
                        )
                        // R2-P1-05: con el update ya instalado también
                        // se puede reimportar/reemplazar (pasa por
                        // pickUpdate -> vm.importUpdate, con el diálogo
                        // de duplicado si aplica). Antes no había forma
                        // sin borrar todos los datos del juego.
                        TextButton(onClick = { pickUpdate.launch("*/*") }, enabled = !importing) { Text("Reemplazar") }
                    } else if (status.updateStored) {
                        // R6-P1-05: el .pkg de update está guardado y se
                        // instala solo al pulsar JUGAR. Antes decía
                        // "Ausente" y el usuario creía que la
                        // importación no había quedado.
                        Text(
                            "✓ Guardado · se instala al pulsar JUGAR",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = cardTextColor(dark),
                        )
                        TextButton(onClick = { pickUpdate.launch("*/*") }, enabled = !importing) { Text("Reemplazar") }
                    } else {
                        Text(
                            strings.absent,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = cardTextColor(dark),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        SecondaryButton("Importar update (.zip / .vpk / .pkg)", onClick = { pickUpdate.launch("*/*") }, enabled = !importing)
                    }
                }
            }
            // --- Firmware ---
            item {
                SectionTitle("Firmware")
                PremiumCard {
                    when (val firmware = firmwareStatus) {
                        is FirmwareStatus.Ready -> {
                            Text(
                                "✓ ${FirmwareResolver.label(firmware)}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = cardTextColor(dark),
                            )
                            // P2-29: hay un PUP guardado que no es el
                            // instalado (u otra versión, o una instalación
                            // anterior que falló): antes quedaba escondido
                            // detrás del "Instalado", sin botón. Se muestra
                            // como pendiente con su botón de instalar.
                            if (firmware.savedPupPending) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "Hay un PSVUPDAT.PUP guardado pendiente de instalar: puede ser otra versión que la instalada, o una instalación anterior que falló.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = mutedTextColor(dark),
                                )
                                if (firmwareInstalling) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                    Text(
                                        "Instalando firmware…",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = mutedTextColor(dark),
                                    )
                                } else if (embeddedCore == true) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    SecondaryButton("Instalar en el núcleo embebido", onClick = { vm.installSavedFirmwareInCore() }, enabled = !importing)
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { pickFirmware.launch("*/*") }, enabled = !importing) { Text("Reemplazar") }
                                TextButton(onClick = { showDeleteFirmwareConfirm = true }, enabled = !importing) { Text("Borrar firmware") }
                            }
                        }
                        is FirmwareStatus.Saved -> {
                            Text(
                                "✓ ${FirmwareResolver.label(firmware)}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = cardTextColor(dark),
                            )
                            if (firmwareInstalling) {
                                Spacer(modifier = Modifier.height(8.dp))
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                Text(
                                    "Instalando firmware…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = mutedTextColor(dark),
                                )
                            } else if (embeddedCore == true) {
                                Spacer(modifier = Modifier.height(8.dp))
                                SecondaryButton("Instalar en el núcleo embebido", onClick = { vm.installSavedFirmwareInCore() }, enabled = !importing)
                                TextButton(onClick = { pickFirmware.launch("*/*") }, enabled = !importing) { Text("Reemplazar") }
                            } else {
                                Text(
                                    "La app externa usa su propio firmware.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = mutedTextColor(dark),
                                )
                            }
                        }
                        FirmwareStatus.Missing -> {
                            Text(
                                strings.absent,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = cardTextColor(dark),
                            )
                            Text(
                                "Tu copia del firmware, solo en tu teléfono.",
                                style = MaterialTheme.typography.bodySmall,
                                color = mutedTextColor(dark),
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            SecondaryButton("Importar firmware (.PUP, tu copia)", onClick = { pickFirmware.launch("*/*") }, enabled = !importing)
                        }
                    }
                }
            }
            // --- Falta la licencia del .pkg ---
            item {
                pendingLicense?.let { pending ->
                    SectionTitle("Falta la licencia")
                    PremiumCard {
                        Text(
                            "Falta el work.bin del ${pending.kind.label} (${pending.titleId}).",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = cardTextColor(dark),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        SecondaryButton("Elegir work.bin", onClick = { pickWorkBin.launch("*/*") }, enabled = !importing)
                    }
                }
            }
            item {
                BackButton(onBack)
            }
        }
        if (showDeleteFirmwareConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteFirmwareConfirm = false },
                title = { Text("¿Borrar el firmware?") },
                text = { Text("Se borra el PUP guardado y el firmware instalado en el núcleo. El juego, los DLCs y los saves no se tocan. Para jugar con el núcleo embebido habrá que importar el PUP de nuevo.") },
                confirmButton = {
                    TextButton(onClick = { vm.deleteFirmware(); showDeleteFirmwareConfirm = false }) { Text("Borrar") }
                },
                dismissButton = { TextButton(onClick = { showDeleteFirmwareConfirm = false }) { Text("Cancelar") } },
            )
        }
    }
}

/**
 * Tarjeta de progreso de Importar (copia indeterminada o instalación
 * en el núcleo con % real).
 *
 * Ronda 0.2.55 P1-11: recoge installProgress DENTRO, como hace
 * HomeScreen con LaunchProgressCard: cada % recompone solo esta
 * tarjeta, no todo el LazyColumn de Importar. Si no hay nada en
 * curso, no dibuja nada (el item que la contiene queda vacío).
 * importing viene por parámetro (lo lee también la pantalla para los
 * botones) y cambia pocas veces; el % es lo que cambia a menudo.
 */
@Composable
private fun ImportProgressCard(vm: LauncherViewModel, strings: AppStrings, importing: Boolean) {
    val installProgress by vm.installProgress.collectAsState()
    if (!importing && installProgress == null) return
    // R6-P1-10: textos según el tema, como el resto de la pantalla.
    val dark = isDarkUi(LocalUiPreferences.current)
    PremiumCard {
        val progress = installProgress
        if (progress?.percent != null) {
            Text(
                "${strings.installingLabel} ${progress.percent}% — ${progress.message}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = cardTextColor(dark),
            )
            Spacer(modifier = Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { (progress.percent ?: 0) / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            PulsingText(
                progress?.message ?: strings.importingLabel,
                ColAccent,
                MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(12.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "No cierres la app mientras dura.",
            style = MaterialTheme.typography.bodySmall,
            color = mutedTextColor(dark),
        )
    }
}

/**
 * Entrega del .pkg a la app externa (Importar, solo opción avanzada).
 *
 * Ronda 0.2.55 (refuerzo, hallazgo extra no listado en la auditoría):
 * recoge settingsFlow DENTRO: los dos booleanos de entrega
 * (pkgDeliveredToVita3k / pkgInstalledInVita3k) solo los lee este
 * bloque, y antes ImportScreen los recogía arriba y cada cambio de
 * ajuste recomponía la pantalla entera.
 */
@Composable
private fun ExternalDeliveryBlock(vm: LauncherViewModel, status: GameStatus, importing: Boolean) {
    val settings by vm.settingsFlow.collectAsState(initial = null)
    val s = settings
    if (s?.pkgDeliveredToVita3k == true) {
        Text("✓ Copiado a Descargas/ChildOfLight/", style = MaterialTheme.typography.bodyMedium, color = ColTurquoise)
    }
    if (s?.pkgInstalledInVita3k == true) {
        Text("✓ Instalado en la app externa", style = MaterialTheme.typography.bodyMedium, color = ColTurquoise)
    }
    TextButton(onClick = { vm.deliverPkgToVita3k() }, enabled = !importing && status.pkgLicensePresent) { Text("Copiar a la app externa") }
    if (s?.pkgDeliveredToVita3k == true && s?.pkgInstalledInVita3k != true) {
        TextButton(onClick = { vm.confirmPkgInstalledInVita3k() }) { Text("Ya lo instalé en la app externa") }
    }
}
