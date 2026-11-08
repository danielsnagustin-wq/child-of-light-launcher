# Etapa 2 — Plan para embeber el núcleo Vita3K

**Estado:** investigación + esqueleto (2026-11-07). El núcleo **NO** está
embebido todavía y nada de lo aquí descrito se presenta como funcional.
**Fuentes verificadas** leyendo el código público el 2026-11-07
(rama `master` de Vita3K/Vita3K y `main` de sashkinbro/EmuCoreV).
Lo no verificado se marca `TODO-VERIFY`.

---

## 1. Lo que se verificó en Vita3K upstream

### 1.1 Estructura del repo (`Vita3K/Vita3K`)

- `vita3k/CMakeLists.txt` (rama `ANDROID`): compila
  `add_library(vita3k SHARED …)` con los JNI de `vita3k/android/jni/`:
  `main_android.cpp`, `native_bootstrap.cpp`, `native_config.cpp`,
  `native_drivers.cpp`, `native_install.cpp`, `native_session.cpp`,
  `native_apps.cpp`, `native_trophies.cpp`, `native_users.cpp`,
  `android_state.cpp`, `filesystem_android.cpp`, `input_overlay.cpp`,
  `ime.cpp`, `interface.cpp`, `performance.cpp`. Enlaza `SDL3`,
  `pugixml`, `android`. La rama no-Android compila un ejecutable y
  añade `gui-qt`; **Android NO incluye gui-qt**.
- `vita3k/interface.h`: `load_app(int32_t&, EmuEnvState&)` y sobrecarga
  con `AppLaunchRequest`; `run_app(EmuEnvState&, int32_t)` y sobrecarga;
  `toggle_texture_replacement`, `take_screenshot`.
- `android/app/build.gradle`: namespace y applicationId
  `org.vita3k.emulator`, compileSdk 35, **ndkVersion "29.0.14206865"**,
  minSdk 28, targetSdk 35, versionCode 21, versionName "0.2.1",
  `abiFilters "arm64-v8a", "x86_64"`, buildTypes debug / **reldebug**
  (RelWithDebInfo, debuggable, jniDebuggable) / release,
  `sourceSets.main.jniLibs.srcDirs = ['../prebuilt']`, java srcDirs +=
  `../../external/sdl/android-project/app/src/main/java` (SDL Android),
  `externalNativeBuild.cmake.path = '../../CMakeLists.txt'`, cmake
  3.22.1+, `packaging.jniLibs.useLegacyPackaging = true`,
  Relinker `com.getkeepsafe.relinker:relinker:1.4.5`.
- `building.md`: CMake, C++20, presets
  `<target_os>-<project_generator>-<compiler>`; 32-bit/x86/i386 **NO**
  soportado. Android: SDK + NDK, `ANDROID_NDK_HOME` (y
  `ANDROID_SDK_HOME` si no se usa Android Studio); Android depende de
  vcpkg: `vcpkg install --triplet arm64-android` y `x64-android`
  desde la raíz, `VCPKG_ROOT`. Build en Android Studio (carpeta
  Vita3K Android) o `./gradlew --stacktrace assembleReldebug`.
- `.github/workflows/c-cpp.yml`, job `android-build` (ubuntu-latest):
  checkout con `submodules: recursive`, caché binaria de vcpkg (clave
  con hash de `vcpkg.json`, `CMakeLists.txt`, `cmake/**`, `.ci/**`,
  `android/app/build.gradle`, el workflow), Java 17 (temurin),
  `gradle/actions/setup-gradle`, `android-actions/setup-android`,
  `./.ci/setup-android.sh` con `VCPKG_ROOT=/usr/local/share/vcpkg`,
  ccache, keystore opcional, y `bash ./.ci/build-android.sh
  <ARTIFACT_DIR>` con `ANDROID_NDK_HOME`.
- `.ci/build-android.sh`: copia `data` → `android/app/assets/data`,
  `vita3k/shaders-builtin` → `android/app/assets/shaders-builtin`,
  descarga strings de Android, `./gradlew --stacktrace
  ":app:assemble${BUILD_TYPE}"` (Release con keystore, si no Reldebug)
  y copia el APK al directorio de salida.

### 1.2 Punto de entrada real: NO hay `loadGame(titleId)` público

- `vita3k/android/jni/main_android.cpp`: entrada SDL3
  `SDL_main(int argc, char *argv[])`. Parsea argv `{"-r", "<TITLE_ID>"}`.
  (El comentario dice que argv viene de `Emulator.getArguments()`,
  p. ej. `{"-r", "PCSE00000"}`.) Sin Title ID: error "No title ID
  provided", return -1. Luego
  `sdl_frontend::run(*emuenv, *session_controller,
  AppLaunchRequest{.app_path = title_id}, hooks)`. Los hooks llaman a
  Kotlin: `on_launch` → `setCurrentGameId(String)`,
  `on_guide_button` → `openPauseMenuFromController()`, además de
  `on_init` (rotación), `on_window` (ventana SDL para IME),
  `on_ime_changed`, `on_stop` (detach del overlay).
- `vita3k/android/jni/native_bootstrap.cpp`, JNI
  `org.vita3k.emulator.NativeLib`:
  - `prepareFrontend()` → `SDL_SetMainReady()`,
    `SDL_SetAppMetadata(…, "org.vita3k.emulator")`, init cámara SDL.
  - `init(storage_path)` → `initialize_session()`: vita_fs =
    `<storage>/vita`; log/config/shared = `<storage>`; cache =
    `<storage>/cache`; patch = `<storage>/patch`; cheat =
    `<storage>/cheats`; crea carpetas (incl. shaderlog/texturelog);
    `logging::init`; `config::init_config(cfg, 1, argv={"vita3k"},
    root_paths, false)`; `app::init`; enumera dispositivos Vulkan;
    `init_libraries`; `app::init_apps_list`; `app::load_users`;
    `app::ensure_current_user`; `compat::load_from_disk`. Crea el
    `AppSessionController`.
  - También: `isInitialized()`, `isOfficialBuild()`,
    `refreshAppsList()` (`app::scan_apps`), `getFirmwareInstallStateMask()`
    (bits: preinstalado=1<<0, principal=1<<1, fuentes=1<<2),
    `getAppVersion()`, `getCompatibilityDatabaseVersion()`,
    `installCompatibilityDatabase(zip_bytes, version)`.
- `vita3k/android/jni/native_session.cpp`, JNI
  `org.vita3k.emulator.NativeLib` + `Emulator.setNativeDisplayRotation`:
  `setInputIntercepted`, `setPauseReasonEnabled`, `isAppRunning`,
  `requestAppQuit` (evento SDL_EVENT_QUIT),
  `requestAppRelaunch(title_id, self_path, args[], load_exec_reason)`
  → `AppLaunchRequest` + `app::request_in_process_launch` + SDL quit,
  `isAppPaused`, `getRunningAppTitle`, `isImeActive`, `submitIme`,
  `dismissIme`.
- **Consecuencia:** las funciones JNI de upstream están ligadas a la
  clase `org.vita3k.emulator.NativeLib`. Nuestro paquete
  (`com.danielsnagustin.childoflight.launcher`) **no puede llamarlas
  tal cual**. Hay que escribir un puente propio (ver §3).

### 1.3 Rutas y datos (ux0, saves, trofeos, firmware)

- Upstream espera `<storage>/vita/ux0/...` y `config.yml` en
  `<storage>/config.yml` (ver §1.2: vita_fs = `<storage>/vita`).
- **Desajuste detectado con Etapa 1:** el launcher guarda
  `<filesDir>/vita3k/ux0/...` y `config.yml` en `<filesDir>/vita3k`
  con `pref-path` = ese mismo directorio. Al embeber hay que elegir
  (ver §3.3): (a) alinear el layout con upstream
  (`<filesDir>/vita3k/vita/ux0/...`, con migración de instalaciones
  Etapa 1) o (b) parchear `initialize_session` en el árbol vendorizado
  para que vita_fs = storage_path. Recomendado (a): menos diff de fork.
- Saves: `ux0/user/00/savedata/<TITLE_ID>/`. Trofeos:
  `ux0/user/00/trophy/` (soporte parcial en Vita3K, formato TROPHY.TRP
  sin decodificar en Etapa 1). DLC: `ux0/addcont/<TITLE_ID>/<DLC_ID>/`.
  Update: `ux0/patch/<TITLE_ID>/`. Firmware: PUP → extracción a `vs0/`
  (la hace Vita3K; el launcher solo guarda el PUP).
- Assets obligatorios en el APK: `data/` y `vita3k/shaders-builtin`
  copiados a `assets/` (lo hace `.ci/build-android.sh` en upstream).

### 1.4 Licencia

- Vita3K es **GPLv2** (`COPYING.txt`, dictada en gran parte por
  Unicorn). Al embeber el núcleo, el trabajo combinado debe cumplir
  la GPLv2: publicar el código del núcleo y sus modificaciones (el
  repo del launcher ya es público; habrá que incluir `COPYING.txt`,
  atribución y el código del árbol vendorizado / submódulo).

---

## 2. Cómo lo hacen los forks con núcleo embebido (verificado)

### 2.1 EmuCoreV (`sashkinbro/EmuCoreV`, `main`, 2026-11-07) — modelo a seguir

- App Android (Kotlin + Compose) con núcleo Vita3K integrado, en
  Google Play como `com.sbro.emucorev`, minSdk 28, targetSdk 37,
  v0.2.4 build 84, **solo `arm64-v8a`**. README: "Vita3K-based native
  core integrated into a Kotlin + Jetpack Compose Android app",
  "bundled Vita3K source tree", "JNI bridge to native C++",
  "Android NDK + CMake". Licencia **GPL v2+**.
- Estructura `app/src/main/cpp/`:
  - `CMakeLists.txt` (25 líneas): `add_subdirectory(vita3k)` y
    opciones de enlace para páginas de 16 KiB
    (`-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384`).
  - `vita3k/` = árbol upstream vendorizado (solo lectura).
  - `emucorev/` = adaptador propio: `emucorev/README.md` dice que el
    lado nativo tiene 3 capas: (1) `../vita3k` vendorizado, (2)
    `emucorev` traduce diferencias de API/build y filtra los targets
    de UI stock de Vita3K del grafo de la librería Android, (3) puente
    Android/JNI con los entry points Kotlin de EmuCoreV. "Gradle still
    enters the vanilla Vita3K CMake project directly:
    app/src/main/cpp/vita3k/CMakeLists.txt".
  - `emucorev/CMakeLists.txt`: exige el target `vita3k`, añade include
    dirs (puente, núcleo, `android/jni`), añade al target `vita3k`
    los fuentes `layer_contract.cpp`, `vita_install_bridge.cpp`,
    `runtime_bridge.cpp`, `input_overlay_bridge.cpp`, `input_state.cpp`,
    `keyboard_mouse_bridge.cpp`; define `EMUCOREV_ANDROID_BRIDGE=1`.
- Requisitos de build (README): Android Studio, SDK,
  NDK **29.0.14206865**, CMake 3.22.1+, **JDK 21**, Python 3,
  salidas OpenSSL para Android en `tools/openssl-test/out`,
  checkout `third_party/vcpkg`; Gradle pasa vcpkg y NDK a CMake.
- **Verificado: usa el .so compilado desde el árbol vendorizado
  (fork/adaptador), NO un intent a Vita3K externo.**

### 2.2 Alternativa: núcleo libretro de Vita3K (no elegida)

- Docs libretro: port de Vita3K a libretro (upstream + port de
  danprice142), para Linux x86_64/arm64, Windows x64, Android
  arm64-v8a/x86_64; requiere CPU 64-bit + Vulkan 1.1; renderiza en el
  dispositivo Vulkan de RetroArch; firmware en `system/vita3k/`,
  carpetas `data` + `shaders-builtin`; juego como `.vpk`/`.zip` o
  carpeta con `eboot.bin`; saves en `system/vita3k/ux0/…` (mismo
  layout ux0). API estable `retro_*` (más simple de enlazar que el
  SDL_main de upstream), pero obligaría a escribir un frontend
  libretro (vídeo/audio/input) y a verificar la disponibilidad de un
  `.so` Android precompilado o su receta de build. `TODO-VERIFY` si se
  reconsidera; la vía recomendada sigue siendo §3 (EmuCoreV).

### 2.3 Lo que NO funciona (descartado)

- **Cargar el `libVita3K.so` del APK oficial tal cual:** sus símbolos
  JNI están ligados a `org.vita3k.emulator.NativeLib` y a la
  `SDLActivity` de `external/sdl`; en nuestro paquete fallarían con
  `UnsatisfiedLinkError` / clases ausentes. Además el `.so` no se
  distribuye por separado y extraerlo del APK es frágil.
- **Intent con extras para arrancar un Title ID en Vita3K externo:**
  no hay deep-link documentado verificado. Se lanza el Intent MAIN
  normal (Etapa 1). `TODO-VERIFY` en Etapa 2.

---

## 3. Arquitectura propuesta (modelo EmuCoreV)

```
child-of-light-launcher/
  app/src/main/cpp/
    CMakeLists.txt            <- entrada: add_subdirectory(vita3k) + adaptador
    vita3k/                   <- árbol Vita3K (lo clona CI, commit fijado; NO es submódulo ni se commitea)
    launcher/                 <- capa adaptadora propia (bridge JNI + filtros)
      CMakeLists.txt
      include/launcher_bridge.h
      src/runtime_bridge.cpp  <- init/loadGame/getFps/shutdown reales
      src/input_bridge.cpp    <- overlay táctil -> ctrl (futuro)
      src/install_bridge.cpp  <- firmware PUP -> vs0, .pkg (futuro)
  app/src/main/java/.../core/
    Vita3kCoreBridge.kt       <- YA EXISTE (esqueleto): init/loadGame/getFps/shutdown
  app/src/main/cpp/
    Vita3kBridge.cpp          <- YA EXISTE (stub NOT_IMPLEMENTED, se reemplaza)
```

### 3.1 Qué hace la capa adaptadora (`launcher/`)

1. **Registrar JNI en nuestro paquete:** funciones
   `Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_native*`
   que llamen a los mismos C++ que usa upstream:
   `initialize_session()` (bootstrap), `sdl_frontend::run()` con
   `AppLaunchRequest{.app_path = titleId}` (arranque `-r`), y
   `requestAppQuit` / `isAppRunning` (sesión). El stub actual ya fija
   estos nombres y códigos de retorno.
2. **Actividad SDL:** el juego corre en el hilo nativo de SDL. Se
   necesita una Activity que extienda la `SDLActivity` de
   `external/sdl` (submódulo de Vita3K) y arranque el hilo nativo con
   argv `{"-r", titleId}` — igual que la `Emulator` de upstream. Es el
   trabajo más grande de Etapa 2; `TODO-VERIFY` el detalle exacto de
   `Emulator.getArguments()` contra el Kotlin de upstream (no se pudo
   releer en esta sesión por un 403 de la API de GitHub al listar esa
   carpeta; el contrato C++ sí está verificado).
3. **Filtrar la UI stock:** como EmuCoreV, excluir del grafo Android
   los targets de UI de Vita3K que no se quieren (el launcher ya tiene
   su propia UI Compose); el juego es de un solo Title ID.
4. **Rutas:** decidir §1.3 (a) o (b). Recomendado (a) + migración
   única de instalaciones Etapa 1 (`ux0/` → `vita/ux0/`).
5. **Assets:** copiar `data/` y `vita3k/shaders-builtin` a
   `app/src/main/assets/` en el workflow (como `.ci/build-android.sh`).

### 3.2 Sesiones y firmware: no tocan el núcleo

- **Sesiones:** la activación es 100% del lado del launcher
  (`SessionManager`): antes de lanzar, la savedata viva ya es la de
  la sesión activa. El núcleo solo lee `ux0/user/00/savedata/<TITLE>`.
  No hace falta ningún cambio JNI para sesiones.
- **Firmware:** el núcleo embebido solo puede usar firmware local
  (`PSVUPDAT.PUP` guardado o `vs0/` extraído). `FirmwareResolver`
  ya lo detecta sin pedir nada al usuario; el primer uso solo pide
  juego + DLCs. Si falta firmware local y el núcleo embebido está
  presente, `Vita3KLauncher` bloquea con una frase
  (`FIRMWARE_BLOCKED_EMBEDDED`). Con Vita3K externo, solo avisa con
  una frase y lanza igual (Vita3K gestiona su propio firmware).
- **FPS de la prueba de 30 s:** hoy es manual (overlay). Cuando el
  puente real exista, `Vita3kCoreBridge.getFps()` alimentará la
  medición automática; el stub devuelve -1.0f hasta entonces.

### 3.3 Archivos a crear / modificar en Etapa 2 (build del núcleo)

| Archivo | Acción |
|---|---|
| `app/src/main/cpp/vita3k/` | NO se commitea: lo clona CI en cada build (commit fijado `3e43bc80` + submódulos recursivos). El push por API no maneja submódulos git (ver `ETAPA2-ESTADO.md` §2). `.gitignore` lo excluye. |
| `app/src/main/cpp/CMakeLists.txt` | Reemplazar el stub: `add_subdirectory(vita3k)` + `add_subdirectory(launcher)` + flags 16 KiB. |
| `app/src/main/cpp/launcher/` | Nuevo: capa adaptadora (runtime/input/install bridge). |
| `app/src/main/cpp/Vita3kBridge.cpp` | Reemplazar el stub por el puente real (o moverlo a `launcher/src/`). |
| `app/build.gradle.kts` | Cableado nativo CONDICIONAL (hecho en la fundación): `ndkVersion "29.0.14206865"`, `externalNativeBuild { cmake { path = "src/main/cpp/CMakeLists.txt"; version = "3.22.1+" } }`, `abiFilters "arm64-v8a"` (solo arm64: el procesador objetivo es arm64 y ahorra tamaño), `packaging.jniLibs.useLegacyPackaging = true` — todo solo si existe `src/main/cpp/vita3k/CMakeLists.txt`. `VCPKG_ROOT` lo aporta el runner de CI (vcpkg preinstalado) y el workflow hace `vcpkg install --triplet arm64-android` (ver borrador). |
| `.github/workflows/android.yml` | Job `build-native` (borrador en `docs/etapa2/workflow-android-native.yml`, por navegador): clon de Vita3K con commit fijado + `submodule update --init --recursive`, caché vcpkg, JDK 17, NDK 29, copiar `data/` + `shaders-builtin` a `assets/`, `assembleDebug`/`assembleRelease`, subir APK + medir tamaño del `.so`. |
| `app/src/main/java/.../core/Vita3kCoreBridge.kt` | Ampliado en la fundación: `prepareFrontend`, instalación (7 fns) y sesión (8 fns) + `EmbeddedInstallCallback` y `PAUSE_REASON_*`. Sin `.so`, todo devuelve el valor de "no implementado". |
| `app/src/main/java/.../launcher/Vita3KLauncher.kt` | Cableado en la fundación: `decide()` -> embebido (migra layout + instala .pkg con el núcleo + abre `EmbeddedGameActivity`) o fallback a externo. Sin firmware local ya NO bloquea: usa la vía externa. |
| `GameLibraryRepository.kt` | Migración de layout hecha en la fundación (`core/EmbeddedCoreLayout.kt`, opción (a) del §3.1 punto 4: `ux0` -> `vita/ux0`, idempotente). |
| `COPYING.txt` + README | Incluir GPLv2 + atribución a Vita3K (obligatorio al embeber). |

### 3.4 Dependencias NDK / CMake / vcpkg

- NDK **29.0.14206865**, CMake **3.22.1+**, JDK 21 (EmuCoreV) o 17
  (CI Vita3K) — decidir en la primera build de Actions.
- vcpkg: triplets `arm64-android` (y `x64-android` solo si se
  mantiene x86_64; recomendado NO: solo arm64-v8a). `VCPKG_ROOT` y
  caché binaria en Actions (ver job `android-build` de upstream).
- Python 3 disponible para CMake; OpenSSL para Android (EmuCoreV lo
  prepara en `tools/openssl-test/out` — `TODO-VERIFY` si Vita3K
  upstream lo necesita igual en su `vcpkg.json`).
- Gradle 8.7 (actual del workflow) + AGP del proyecto. `TODO-VERIFY`
  compatibilidad AGP/NDK 29 en la primera build.

---

## 4. Riesgos

1. **Tamaño del `.so` / APK:** el núcleo Vita3K es grande (decenas
   de MB; cifra exacta `TODO-VERIFY`: medir en la primera build de
   Actions). Mitigación: solo `arm64-v8a`, build RelWithDebInfo o
   Release, `--exclude-libs` de símbolos (upstream ya lo hace en
   Release). Si el APK se dispara, valorar AAB / descarga del núcleo
   aparte (no en Etapa 2).
2. **Mali-G57 vs Adreno Turnip:** Turnip es solo Adreno; en Mali se
   usa el driver Vulkan del sistema + Double Buffer. Child of Light
   es 2D UbiArt (ligero) y el usuario confirma que va muy bien en el
   G99. No se prometen 60 fps en Mali. Los presets actuales ya
   reflejan esto; el núcleo no cambia los presets.
3. **GPLv2:** embeber obliga a publicar el código del núcleo y sus
   modificaciones + atribución. El repo ya es público; hay que añadir
   `COPYING.txt` y no distribuir nunca el APK con el núcleo sin su
   código.
4. **Firmware PUP:** el usuario aporta el suyo (opción avanzada en
   Ajustes → Diagnóstico). Nunca se incluye ni se descarga firmware,
   juego ni claves en el repo. El launcher no pide buscar firmware
   por internet.
5. **Páginas de 16 KiB:** Google Play exige soporte 16 KiB en apps
   nuevas. El stub ya incluye los flags (`max-page-size=16384`); la
   build real debe mantenerlos (EmuCoreV lo hace).
6. **Tiempo de build:** el núcleo completo en Actions puede tardar
   mucho la primera vez (vcpkg + compilación C++ grande). Mitigación:
   caché binaria de vcpkg + ccache (como upstream). `TODO-VERIFY` el
   tiempo real en la primera build.
7. **Actividad SDL / IME / overlay táctil:** el overlay táctil del
   launcher (Compose) y la vista SDL son dos mundos: el input táctil
   debe llegar al núcleo vía `input_overlay` / `ctrl` de Vita3K o vía
   la capa adaptadora. Es el mayor riesgo de integración después del
   propio build. `TODO-VERIFY` con la primera build que arranque el
   juego.
8. **Tamaño del repo:** el árbol Vita3K + submódulos es grande
   (cientos de MB con historial). NO se commitea: lo clona CI en
   cada build (commit fijado) y `.gitignore` lo excluye; el repo
   solo guarda la capa adaptadora propia.

---

## 5. Qué NO se puede hacer en esta VM

- No hay Android SDK/NDK ni Gradle runnable: no se puede compilar
  el `.so` ni el APK aquí. La primera compilación real es en GitHub
  Actions (que sí tiene SDK/NDK).
- No se puede clonar el árbol Vita3K completo aquí (submódulos
  grandes: SDL, vcpkg, etc.). Se verificó con un clon superficial
  en /tmp (solo para leer el código); el repo NO lo contiene: lo
  clona CI en cada build (commit fijado).
- No se puede probar en dispositivo real (sin GPU Mali, sin teléfono):
  el arranque `-r PCSE00480`, el rendimiento en G99 y el overlay
  táctil solo se verifican instalando el APK en un dispositivo con Helio G99.
- No se puede verificar el detalle Kotlin de upstream
  (`Emulator.getArguments()`, `NativeLib.kt`) en esta sesión: la API
  de GitHub devolvió 403 al listar esa carpeta. El contrato C++ sí
  está verificado; el detalle Kotlin es `TODO-VERIFY` antes de
  escribir la Activity SDL.

---

## 6. Estado actual (fundación, 2026-11-07)

- `app/src/main/java/.../core/Vita3kCoreBridge.kt`: interfaz Kotlin
  con `available` (carga `vita3k` y luego `vita3k-bridge`), `init`,
  `loadGame`, `getFps`, `shutdown` + `prepareFrontend`,
  instalación (`installPkg`/`installArchive`/`installFirmware`/
  `copyLicense`/`createLicense`/`findPkgZrif`/`convertRifToZrif`)
  y sesión (`isAppRunning`/`requestAppQuit`/`isAppPaused`/
  `getRunningAppTitle`/máscara de firmware/`refreshAppsList`/
  `setPauseReasonEnabled`/`setInputIntercepted`). Sin `.so` devuelve
  `NOT_IMPLEMENTED` (-1) / false / "" / -1.0f. No finge que el
  núcleo funciona.
- `app/src/main/java/org/libsdl/app/`: pegamento Java de SDL3
  vendorizado (commit fa2c02b = SDL 3.4.16, el fijado por Vita3K).
- `core/EmbeddedGameActivity.kt`: SDLActivity del juego
  (`{"-r", titleId}`, `getLibraries() -> {"vita3k"}`,
  `setCurrentGameId` / `openPauseMenuFromController` (pausa) /
  `getNativeDisplayRotation` con las firmas exactas que exige
  `main_android.cpp`).
- `core/EmbeddedCoreLayout.kt`: migración `ux0 -> vita/ux0`
  (idempotente, con `Conflict` si hay dos instalaciones).
- `core/EmbeddedGameLauncher.kt`: `decide()` pura (UseEmbedded /
  UseEmbeddedPkg / UseExternal / Blocked). Sin firmware local NO se
  bloquea: se usa Vita3K externo (cambio de política vs §3.2, ver
  ETAPA2-ESTADO.md §4).
- `core/EmbeddedPkgInstaller.kt`: instala .pkg/.zip/PUP con el
  núcleo (work.bin -> zRIF -> installPkg).
- `app/src/main/cpp/CMakeLists.txt` + `launcher/`: CMake
  condicional (con árbol -> núcleo real + adaptador; sin árbol ->
  solo stub) y adaptador JNI que reenvía a las funciones de
  upstream (`runtime_bridge.cpp`, `install_bridge.cpp`,
  `session_bridge.cpp`).
- `app/src/main/cpp/Vita3kBridge.cpp`: stub JNI con las 21
  funciones del contrato (todas NOT_IMPLEMENTED sin núcleo).
- `app/build.gradle.kts`: cableado nativo CONDICIONAL (NDK 29 +
  arm64-v8a + externalNativeBuild solo si el árbol existe).
- `Vita3KLauncher`: usa el puente si `available` (con migración de
  layout + instalación .pkg embebida) y si no, fallback a Vita3K
  externo. La rama `EmbeddedCorePresent` se eliminó (callejón sin
  salida); el tipo sigue existiendo por compatibilidad.
- Sesiones (`SessionManager`/`SessionRepository`) y firmware
  (`FirmwareResolver`) ya están implementados del lado del launcher y
  no necesitan cambios JNI.
- Estado completo, verificación contra upstream, decisión del
  submódulo (clon en CI, no submódulo) y pasos que faltan:
  [`docs/etapa2/ETAPA2-ESTADO.md`](ETAPA2-ESTADO.md).

## 7. Siguiente paso concreto (primera build del `.so` en Actions)

1. Activar el workflow borrador
   (`docs/etapa2/workflow-android-native.yml`, POR NAVEGADOR: el
   push por API no puede modificar `.github/workflows/`) y hacer
   push de los archivos de la fundación.
2. Leer el log de Actions: si compila, medir el tamaño de
   `libVita3K.so` y del APK. Si falla, el error dirá qué falta
   (normalmente un submódulo del clon, vcpkg o la toolchain).
3. Solo cuando el `.so` compile, probar en un dispositivo con
   Helio G99 el arranque `-r PCSB00598` (instalar el .pkg con el
   núcleo, jugar, pausa, salir) y luego integrar el overlay táctil.
