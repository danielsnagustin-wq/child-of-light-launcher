package com.danielsnagustin.childoflight.launcher

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.danielsnagustin.childoflight.launcher.model.ThemeMode
import com.danielsnagustin.childoflight.launcher.ui.AchievementsScreen
import com.danielsnagustin.childoflight.launcher.ui.AlertAction
import com.danielsnagustin.childoflight.launcher.ui.CleanupScreen
import com.danielsnagustin.childoflight.launcher.ui.GpuDiagnosticsScreen
import com.danielsnagustin.childoflight.launcher.ui.HomeScreen
import com.danielsnagustin.childoflight.launcher.ui.ImportScreen
import com.danielsnagustin.childoflight.launcher.ui.LauncherViewModel
import com.danielsnagustin.childoflight.launcher.ui.SavesScreen
import com.danielsnagustin.childoflight.launcher.ui.Screen
import com.danielsnagustin.childoflight.launcher.ui.SettingsScreen
import com.danielsnagustin.childoflight.launcher.ui.TouchLayoutScreen
import com.danielsnagustin.childoflight.launcher.ui.settings.SettingsAudioScreen
import com.danielsnagustin.childoflight.launcher.ui.settings.SettingsControlsScreen
import com.danielsnagustin.childoflight.launcher.ui.settings.SettingsCpuScreen
import com.danielsnagustin.childoflight.launcher.ui.settings.SettingsDiagnosticsScreen
import com.danielsnagustin.childoflight.launcher.ui.settings.SettingsFirmwareScreen
import com.danielsnagustin.childoflight.launcher.ui.settings.SettingsGeneralScreen
import com.danielsnagustin.childoflight.launcher.ui.settings.SettingsGpuScreen
import com.danielsnagustin.childoflight.launcher.ui.settings.SettingsPerformanceScreen
import com.danielsnagustin.childoflight.launcher.ui.settings.SettingsPersonalizationScreen
import com.danielsnagustin.childoflight.launcher.ui.settings.SettingsStorageScreen

/**
 * Actividad única del launcher.
 * - Navegación por estado (sin librería de navegación).
 * - Tema del launcher: claro/oscuro/sistema + color de acento +
 *   tamaño de texto (todo de UiPreferences, en DataStore).
 * - Modo rendimiento: si está activado, mantiene la pantalla encendida.
 * - Popup + sonido de logro: el sonido lo reproduce el ViewModel al
 *   desbloquear; aquí se muestra el popup con el trofeo.
 * - Mensajes del ViewModel: Snackbar.
 */
class MainActivity : ComponentActivity() {

    private val vm: LauncherViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val uiPrefs by vm.uiFlow.collectAsState(initial = null)
            val dark = when (uiPrefs?.theme) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM, null -> isSystemInDarkTheme()
            }
            val accent = try {
                Color(android.graphics.Color.parseColor(uiPrefs?.accent?.hex ?: "#F2B705"))
            } catch (_: Exception) {
                Color(0xFFF2B705)
            }
            val scale = uiPrefs?.textSize?.scale ?: 1.0f
            val typography = Typography(
                headlineMedium = TextStyle(fontSize = (28 * scale).sp),
                titleLarge = TextStyle(fontSize = (22 * scale).sp),
                titleMedium = TextStyle(fontSize = (16 * scale).sp),
                bodyLarge = TextStyle(fontSize = (16 * scale).sp),
                bodyMedium = TextStyle(fontSize = (14 * scale).sp),
                bodySmall = TextStyle(fontSize = (12 * scale).sp),
            )
            val colorScheme = if (dark) {
                darkColorScheme(primary = accent, secondary = accent)
            } else {
                lightColorScheme(primary = accent, secondary = accent)
            }
            MaterialTheme(colorScheme = colorScheme, typography = typography) {
                LauncherApp(vm)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Al volver del juego, refrescar estado y log, y cerrar la
        // medición de tiempo de la sesión activa (snapshot guardado).
        vm.refreshStatus()
        vm.refreshLog()
        vm.noteReturnedFromGame()
    }
}

@Composable
private fun LauncherApp(vm: LauncherViewModel) {
    // B2 (auditoría UI): antes la pantalla se guardaba con remember (se
    // perdía al rotar) y no había BackHandler: atrás cerraba la app desde
    // cualquier pantalla. Ahora la pantalla sobrevive a rotación y atrás
    // vuelve a Home (desde Limpiar, vuelve a Configuración).
    var screenName by rememberSaveable { mutableStateOf(Screen.HOME.name) }
    // La animación de entrada de Home solo se reproduce una vez por sesión:
    // AnimatedContent descarta y recrea la composición de Home en cada
    // navegación, así que sin esta bandera la portada (650 ms) y las
    // tarjetas (450 ms + retardos) se repetían en cada vuelta a Home.
    var homeIntroDone by rememberSaveable { mutableStateOf(false) }
    val screen = runCatching { Screen.valueOf(screenName) }.getOrDefault(Screen.HOME)
    // Atrás (Ola 2): categoría de Ajustes -> hub de Ajustes; Limpiar
    // -> hub de Ajustes; GPU y diagnóstico -> hub de Ajustes (solo se
    // llega desde Ajustes); resto -> Home.
    BackHandler(enabled = screen != Screen.HOME) {
        screenName = when (screen) {
            Screen.CLEANUP -> Screen.SETTINGS.name
            Screen.GPU -> Screen.SETTINGS.name
            Screen.SETTINGS_GENERAL,
            Screen.SETTINGS_PERSONALIZATION,
            Screen.SETTINGS_GPU,
            Screen.SETTINGS_CPU,
            Screen.SETTINGS_AUDIO,
            Screen.SETTINGS_PERFORMANCE,
            Screen.SETTINGS_CONTROLS,
            Screen.SETTINGS_FIRMWARE,
            Screen.SETTINGS_STORAGE,
            Screen.SETTINGS_DIAGNOSTICS -> Screen.SETTINGS.name
            else -> Screen.HOME.name
        }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val message by vm.message.collectAsState()
    val alert by vm.alert.collectAsState()
    val justUnlocked by vm.justUnlocked.collectAsState()
    val settings by vm.settingsFlow.collectAsState(initial = null)
    val gpu by vm.gpuFlow.collectAsState(initial = null)

    // "Reportar" del diálogo de fallo del motor reutiliza la exportación
    // de bug que ya existe (Diagnóstico → Reportar bug): mismo archivo
    // .txt con log + ajustes + dispositivo, que el usuario comparte a
    // mano. El launcher se registra siempre (no dentro del diálogo):
    // rememberLauncherForActivityResult no debe llamarse condicional.
    val exportBug = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val s = settings
        val g = gpu
        if (uri != null && s != null) vm.exportBugReport(uri, s, g)
    }

    // Modo rendimiento: pantalla encendida mientras el launcher está abierto.
    val activity = androidx.compose.ui.platform.LocalContext.current as? ComponentActivity
    LaunchedEffect(settings?.performanceMode) {
        if (settings?.performanceMode == true) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            vm.clearMessage()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            // Transición rápida entre pantallas (fundido + deslizamiento,
            // 150 ms): en una pantalla de 90 Hz, 250 ms se siente pesado
            // al entrar y al volver a Home.
            AnimatedContent(
                targetState = screen,
                transitionSpec = {
                    (fadeIn(animationSpec = tween(150)) + slideInHorizontally(
                        initialOffsetX = { it / 6 },
                        animationSpec = tween(150),
                    )) togetherWith (fadeOut(animationSpec = tween(120)) + slideOutHorizontally(
                        targetOffsetX = { -it / 6 },
                        animationSpec = tween(120),
                    ))
                },
                label = "screenTransition",
            ) { target ->
                when (target) {
                    Screen.HOME -> HomeScreen(vm, introDone = homeIntroDone, onIntroDone = { homeIntroDone = true }) { screenName = it.name }
                    Screen.IMPORT -> ImportScreen(vm) { screenName = Screen.HOME.name }
                    Screen.SETTINGS -> SettingsScreen(vm, { screenName = Screen.HOME.name }, { screenName = it.name })
                    Screen.SETTINGS_GENERAL -> SettingsGeneralScreen(vm, { screenName = Screen.SETTINGS.name }, { screenName = it.name })
                    Screen.SETTINGS_PERSONALIZATION -> SettingsPersonalizationScreen(vm, { screenName = Screen.SETTINGS.name }, { screenName = it.name })
                    Screen.SETTINGS_GPU -> SettingsGpuScreen(vm, { screenName = Screen.SETTINGS.name }, { screenName = it.name })
                    Screen.SETTINGS_CPU -> SettingsCpuScreen(vm, { screenName = Screen.SETTINGS.name }, { screenName = it.name })
                    Screen.SETTINGS_AUDIO -> SettingsAudioScreen(vm, { screenName = Screen.SETTINGS.name }, { screenName = it.name })
                    Screen.SETTINGS_PERFORMANCE -> SettingsPerformanceScreen(vm, { screenName = Screen.SETTINGS.name }, { screenName = it.name })
                    Screen.SETTINGS_CONTROLS -> SettingsControlsScreen(vm, { screenName = Screen.SETTINGS.name }, { screenName = it.name })
                    Screen.SETTINGS_FIRMWARE -> SettingsFirmwareScreen(vm, { screenName = Screen.SETTINGS.name }, { screenName = it.name })
                    Screen.SETTINGS_STORAGE -> SettingsStorageScreen(vm, { screenName = Screen.SETTINGS.name }, { screenName = it.name })
                    Screen.SETTINGS_DIAGNOSTICS -> SettingsDiagnosticsScreen(vm, { screenName = Screen.SETTINGS.name }, { screenName = it.name })
                    Screen.GPU -> GpuDiagnosticsScreen(vm) { screenName = Screen.SETTINGS.name }
                    Screen.TOUCH -> TouchLayoutScreen(vm) { screenName = Screen.HOME.name }
                    Screen.SAVES -> SavesScreen(vm) { screenName = Screen.HOME.name }
                    Screen.ACHIEVEMENTS -> AchievementsScreen(vm) { screenName = Screen.HOME.name }
                    Screen.CLEANUP -> CleanupScreen(vm) { screenName = Screen.SETTINGS.name }
                }
            }
        }
    }

    // Aviso importante (diálogo, no Snackbar): errores de importar y de
    // JUGAR, con la acción que los resuelve. El texto puede ser largo:
    // va con scroll para poder leerlo entero.
    alert?.let { info ->
        AlertDialog(
            onDismissRequest = { vm.clearAlert() },
            title = { Text("Child of Light") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(info.text)
                }
            },
            confirmButton = {
                when (info.action) {
                    AlertAction.INSTALL_VITA3K -> TextButton(onClick = { vm.clearAlert(); vm.openVita3kReleases() }) { Text("Instalar app externa") }
                    AlertAction.GO_IMPORT -> TextButton(onClick = { vm.clearAlert(); screenName = Screen.IMPORT.name }) { Text("Ir a importar") }
                    AlertAction.DELIVER_PKG_TO_VITA3K -> TextButton(onClick = { vm.clearAlert(); vm.deliverPkgToVita3k() }) { Text("Instalar en la app externa") }
                    AlertAction.CONFIRM_DOWNLOADS_COPY -> TextButton(onClick = { vm.clearAlert(); vm.confirmDeliverPkgToDownloads() }) { Text("Copiar a Descargas") }
                    AlertAction.CONFIRM_PKG_INSTALLED -> TextButton(onClick = { vm.clearAlert(); vm.confirmPkgInstalledInVita3k() }) { Text("Ya lo instalé en la app externa") }
                    AlertAction.REPLACE_DUPLICATE -> TextButton(onClick = { vm.confirmReplaceDuplicate() }) { Text("Reemplazar") }
                    AlertAction.RETRY_LAUNCH -> TextButton(onClick = { vm.clearAlert(); vm.play() }) { Text("Reintentar") }
                    null -> TextButton(onClick = { vm.clearAlert() }) { Text("Entendido") }
                }
            },
            dismissButton = when (info.action) {
                // Fallo del motor integrado: el botón secundario es
                // "Reportar" (exporta el reporte de bug), no "Cerrar".
                AlertAction.RETRY_LAUNCH -> {
                    { TextButton(onClick = { vm.clearAlert(); exportBug.launch("col-bug-report.txt") }) { Text("Reportar") } }
                }
                null -> null
                else -> {
                    { TextButton(onClick = { vm.clearAlert() }) { Text("Cerrar") } }
                }
            },
        )
    }

    // Popup de logro desbloqueado (el sonido ya sonó en el ViewModel).
    justUnlocked?.let { progress ->
        AlertDialog(
            onDismissRequest = { vm.clearJustUnlocked() },
            title = { Text("🏆 ¡Logro desbloqueado!") },
            text = {
                Text("${progress.trophy.name}\n${progress.trophy.description}\n\nLogro local del launcher (no se sincroniza con PSN ni Steam).")
            },
            confirmButton = { TextButton(onClick = { vm.clearJustUnlocked() }) { Text("¡Bien!") } },
        )
    }
}
