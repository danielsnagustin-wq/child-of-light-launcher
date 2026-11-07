package com.danielsnagustin.childoflight.launcher

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
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
import com.danielsnagustin.childoflight.launcher.ui.LauncherViewModel
import com.danielsnagustin.childoflight.launcher.ui.SavesScreen
import com.danielsnagustin.childoflight.launcher.ui.Screen
import com.danielsnagustin.childoflight.launcher.ui.SettingsScreen
import com.danielsnagustin.childoflight.launcher.ui.TouchLayoutScreen

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
    val screen = runCatching { Screen.valueOf(screenName) }.getOrDefault(Screen.HOME)
    BackHandler(enabled = screen != Screen.HOME) {
        screenName = if (screen == Screen.CLEANUP) Screen.SETTINGS.name else Screen.HOME.name
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val message by vm.message.collectAsState()
    val alert by vm.alert.collectAsState()
    val justUnlocked by vm.justUnlocked.collectAsState()
    val settings by vm.settingsFlow.collectAsState(initial = null)

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
            when (screen) {
                Screen.HOME -> HomeScreen(vm) { screenName = it.name }
                Screen.SETTINGS -> SettingsScreen(vm, { screenName = Screen.HOME.name }, { screenName = it.name })
                Screen.GPU -> GpuDiagnosticsScreen(vm) { screenName = Screen.HOME.name }
                Screen.TOUCH -> TouchLayoutScreen(vm) { screenName = Screen.HOME.name }
                Screen.SAVES -> SavesScreen(vm) { screenName = Screen.HOME.name }
                Screen.ACHIEVEMENTS -> AchievementsScreen(vm) { screenName = Screen.HOME.name }
                Screen.CLEANUP -> CleanupScreen(vm) { screenName = Screen.SETTINGS.name }
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
                    AlertAction.INSTALL_VITA3K -> TextButton(onClick = { vm.clearAlert(); vm.openVita3kReleases() }) { Text("Instalar Vita3K") }
                    AlertAction.GO_IMPORT -> TextButton(onClick = { vm.clearAlert(); screenName = Screen.SETTINGS.name }) { Text("Ir a importar") }
                    null -> TextButton(onClick = { vm.clearAlert() }) { Text("Entendido") }
                }
            },
            dismissButton = if (info.action != null) {
                { TextButton(onClick = { vm.clearAlert() }) { Text("Cerrar") } }
            } else {
                null
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
