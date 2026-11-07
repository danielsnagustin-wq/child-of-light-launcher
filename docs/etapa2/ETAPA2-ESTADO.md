# Etapa 2 — Estado de la fundación (2026-11-07)

**Estado: FUNDACIÓN lista en local, SIN push. El núcleo NO está
embebido todavía (no hay .so): nada de lo aquí descrito se presenta
como funcional en un dispositivo.** La base remota es el commit
`d66299eb` (run #25 SUCCESS, 2026-11-07 ~12:06 CST, artefacto
16.9 MB): Etapa 1 verde, con el fix de `animateFloat`. Todo lo de
Etapa 2 vive solo en `~/workspace/child-of-light-launcher`, sin push.

**Meta del usuario (2026-11-07 11:59):** "que no tenga que hacer
tanto y solo me acepte el juego" — una sola app: poner el juego y
jugar, sin instalar ni abrir Vita3K aparte ni pasos manuales.

---

## 1. Qué se verificó (código real, no el plan)

Clon superficial de `Vita3K/Vita3K` master
(`3e43bc803a04c775737a3474e0d54f3e10ca61bf`, 2026-11-07) y de
`libsdl-org/SDL` en el commit fijado por Vita3K (`fa2c02b`, SDL 3.4.16):

- **Arranque:** `SDL_main` (`vita3k/android/jni/main_android.cpp`)
  parsea argv `{"-r", titleId}` y llama
  `sdl_frontend::run(*emuenv, *session_controller,
  AppLaunchRequest{.app_path = titleId}, hooks)`. Sin Title ID: -1.
- **Hooks nativos -> Activity:** `setCurrentGameId(String)`,
  `openPauseMenuFromController()`, `getNativeDisplayRotation()I`.
  **OJO:** `get_display_rotation()` NO comprueba null: si la
  Activity no tiene `getNativeDisplayRotation()`, SDL_main se cierra.
  `EmbeddedGameActivity` los implementa con las firmas exactas.
- **Bootstrap:** `NativeLib.init(storagePath)` =
  `initialize_session(storage_path, ...)` + `AppSessionController`.
  `vita_fs = storage_path / "vita"`, `config.yml` en `storage_path`.
  Idempotente (si ya está inicializado devuelve true).
  `prepareFrontend()` = `SDL_SetMainReady` + `SDL_SetAppMetadata` +
  cámara SDL. Upstream lo llama desde MainActivity ANTES de Emulator.
- **Hilo SDL:** `SDLActivity` arranca el hilo SDL solo en la
  transición a RESUMED (superficie lista + foco + resumed). Por eso
  `init()` después de `super.onCreate()` llega a tiempo (igual que
  `Emulator.ensureNativeSessionInitialized()` de upstream).
- **Instalación nativa (la clave de "solo acepte el juego"):**
  `installPkg(path, zrif, callback)`, `installArchive(path, callback,
  forceReinstall)`, `installFirmware(path, callback) -> versión`,
  `copyLicense(path)`, `createLicense(zrif)`, `findPkgZrif(pkgPath)`,
  `convertRifToZrif(path)`. El callback es duck-typing: busca el
  método `onProgress(int, String)` en el objeto (ScopedJniCallback).
- **Sesión:** `isAppRunning()`, `requestAppQuit()`,
  `requestAppRelaunch(titleId, selfPath, args, loadExecReason)`,
  `isAppPaused()`, `getRunningAppTitle()`,
  `setPauseReasonEnabled(mask, enabled)` (PAUSE_REASON_USER = 1),
  `setInputIntercepted(bool)`, `getFirmwareInstallStateMask()`,
  `refreshAppsList()`.
- **FPS:** `EmuEnvState::fps` (uint32_t, `vita3k/emuenv/include/
  emuenv/state.h`). No hay JNI de FPS en upstream: el adaptador lo
  lee directo del `EmuEnvState`.
- **CMake upstream:** raíz `project(Vita3K)`, C++23,
  `include("cmake/vcpkg_android.cmake")` lo primero en Android,
  `add_subdirectory(external)`, `add_subdirectory(vita3k)`;
  `vita3k/CMakeLists.txt` (rama ANDROID) compila
  `add_library(vita3k SHARED android/jni/*.cpp interface.cpp
  performance.cpp)` y enlaza `SDL3::SDL3 pugixml::pugixml android`
  + todas las submódulos estáticos (`app audio ... util`).
  `vcpkg.json`: boost-icl, boost-program-options, boost-variant,
  curl, openssl, zlib. NDK 29.0.14206865, CMake 3.22.1+.
- **Emulator (upstream):** `extends SDLActivity`,
  `getLibraries() -> {"Vita3K"}`, `getArguments()` = `{"-r",
  titleId}` del extra `title_id`, `launchMode singleTop`,
  `configChanges="layoutDirection|locale|orientation|uiMode|
  screenLayout|screenSize|smallestScreenSize|keyboard|
  keyboardHidden|navigation"`.
- **SDL Java:** `SDLActivity.java` (2240 líneas) no referencia
  recursos `R.` ni `BuildConfig`: es vendorizable tal cual.
  El hilo SDL arranca en RESUMED (ver arriba). `getLibraries()`
  por defecto devuelve `{"SDL3", "main"}`.
- **Layout:** `initialize_session` confirma el desajuste de Etapa 1:
  hay que migrar `vita3k/ux0` -> `vita3k/vita/ux0` (config.yml se
  queda en la raíz del storage). Upstream usa
  `getExternalFilesDir()` como raíz; el launcher sigue con
  `filesDir/vita3k` (mejor: no pide permiso de almacenamiento).

## 2. Decisión: cómo traer el código de Vita3K (veredicto)

**Submódulo git: NO viable con el pipeline actual.** El push del
launcher se hace por API de GitHub (snapshot push) + subidas web por
carpeta: la API no crea entradas gitlink (submódulos) y el usuario
no tiene PC para ejecutar `git submodule add`. Un árbol vendorizado
por subida web tampoco es viable (cientos de MB, miles de archivos).

**Alternativa elegida: clon en CI con commit fijado.** El workflow
(ver `workflow-android-native.yml`, borrador) clona
`Vita3K/Vita3K` en `app/src/main/cpp/vita3k` en cada build, con
`submodule update --init --recursive --depth 1`, y ABORTA si el HEAD
no coincide con el commit fijado. El repo del launcher sigue sin
submódulos y sin el árbol (`.gitignore` lo excluye). Commit fijado:
`3e43bc803a04c775737a3474e0d54f3e10ca61bf` (master, 2026-11-07).
SDL fijado por ese commit: `fa2c02bb6e21974a89ea9824bc53c9932abe5f9c`
(SDL 3.4.16); el Java de SDL vendorizado en el repo es de ese mismo
commit (copia byte a byte verificada).

## 3. Qué se construyó (fundación, solo local)

```
app/src/main/java/org/libsdl/app/      SDL3 Java vendorizado (12 .java,
  README-VENDORED.txt, SDL-LICENSE.txt) — commit fa2c02b, byte a byte.
app/src/main/java/.../core/
  EmbeddedGameActivity.kt             SDLActivity: getLibraries {"vita3k"},
    getArguments {"-r", titleId}, prepareFrontend antes de super.onCreate,
    init después; setCurrentGameId / openPauseMenuFromController (pausa) /
    getNativeDisplayRotation (firma exacta, obligatoria).
  EmbeddedCoreLayout.kt               storageRoot + migración ux0->vita/ux0
    (idempotente, con Conflict si hay dos instalaciones).
  EmbeddedGameLauncher.kt             decide() pura: UseEmbedded /
    UseEmbeddedPkg / UseExternal / Blocked.
  EmbeddedPkgInstaller.kt             installGamePkg/Dlc/Update/Archive/
    Firmware vía el núcleo (work.bin -> zRIF -> installPkg).
  Vita3kCoreBridge.kt                 + prepareFrontend, instalación
    (7 fns), sesión (8 fns), PAUSE_REASON_*, EmbeddedInstallCallback.
app/src/main/cpp/
  CMakeLists.txt                      CONDICIONAL: con árbol -> núcleo real
    + adaptador; sin árbol -> solo stub (Etapa 1 intacta).
  Vita3kBridge.cpp                    stub ampliado con las 17 fns JNI
    nuevas (todas NOT_IMPLEMENTED / false / "").
  launcher/                           capa adaptadora (modelo EmuCoreV):
    CMakeLists.txt                    añade las fuentes al target `vita3k`.
    include/launcher_bridge.h         reenvíos a las JNI de upstream.
    src/runtime_bridge.cpp            prepareFrontend/init/loadGame
      (valida, no arranca: el arranque es SDL_main)/getFps/shutdown.
    src/install_bridge.cpp            install* / copyLicense / createLicense /
      findPkgZrif / convertRifToZrif.
    src/session_bridge.cpp            isAppRunning/requestAppQuit/isAppPaused/
      getRunningAppTitle/firmwareMask/refreshAppsList/pause/input.
app/build.gradle.kts                  cableado nativo CONDICIONAL: solo si
  existe src/main/cpp/vita3k/CMakeLists.txt -> ndkVersion 29,
  abiFilters arm64-v8a, externalNativeBuild, useLegacyPackaging.
  Sin árbol, el build es idéntico a Etapa 1 (sin NDK).
app/src/main/AndroidManifest.xml      EmbeddedGameActivity declarada
  (exported=false, singleTop, configChanges de upstream).
.gitignore                            excluye app/src/main/cpp/vita3k/.
app/src/main/java/.../launcher/Vita3KLauncher.kt
  launchImpl: decide() -> UseEmbedded (migra+init+valida+abre la
  Activity SDL) / UseEmbeddedPkg (instala .pkg con el núcleo y luego
  arranca) / UseExternal (fallback de siempre) / Blocked. Si el
  intento embebido falla, SIEMPRE se sigue con el externo. La rama
  EmbeddedCorePresent se eliminó (era un callejón sin salida; el
  tipo sigue existiendo por compatibilidad).
app/src/main/java/.../model/GameInstall.kt
  KDoc actualizado: el .pkg ya no es "solo Vita3K externo".
app/src/test/java/.../EmbeddedCoreLayoutTest.kt    (5 tests)
app/src/test/java/.../EmbeddedLaunchDecisionTest.kt (8 tests)
docs/etapa2/workflow-android-native.yml  BORRADOR de workflow con el
  job build-native (NO activo: no está en .github/workflows/).
docs/etapa2/GPL-NOTA.md               obligación GPLv2 al embeber.
```

## 4. Cambio de política vs el plan (firmware)

El plan (§3.2) decía: con núcleo presente y sin firmware local,
bloquear con una frase. Se cambió en la fundación: **nunca se
bloquea pudiendo jugar por fuera.** Sin firmware, `decide()`
devuelve `UseExternal` (Vita3K gestiona su propio firmware, con su
aviso). Solo si TAMPOCO está Vita3K externo, `launchImpl` bloquea
al final con `FIRMWARE_BLOCKED_EMBEDDED`. Racional: el usuario
pidió "que no tenga que hacer tanto"; bloquearle el juego cuando
existe una vía que funciona es peor.

## 5. Qué compila y qué NO

- **Compila (Etapa 1, sin cambios):** con el árbol ausente, el
  `build.gradle.kts` condicional NO activa el build nativo y el
  workflow actual sigue igual. La base remota `d66299eb` está en
  verde (run #25). El Kotlin nuevo no se ejecuta sin .so
  (`Vita3kCoreBridge.available` es false; `EmbeddedGameActivity`
  no se lanza; `decide()` devuelve `UseExternal`).
- **NO compilado (sin Gradle/SDK en esta VM):** el Kotlin nuevo,
  el C++ del adaptador y el CMake condicional. La primera
  compilación real es en GitHub Actions tras activar el workflow
  borrador. **No se afirma que nada de esto compile.**
- **Tests nuevos:** 13 tests unitarios (5 layout + 8 decisión),
  puros JVM, sin Android. Tampoco ejecutados aquí (sin Gradle);
  corren en CI con `testDebugUnitTest`.

## 6. Qué falta (en orden)

1. **Activar el workflow** (por navegador, el workflow NO se puede
   subir por API): copiar `docs/etapa2/workflow-android-native.yml`
   a `.github/workflows/android.yml` (o fusionar su job
   `build-native` en el actual) y hacer push de los archivos de
   esta fundación. Leer el log de la primera build nativa.
2. **Arreglar lo que diga la primera build** (lo esperable: algún
   submódulo que falte, vcpkg, la toolchain, o el enlace de SDL3).
   TODO-VERIFY: si `libvita3k.so` enlaza SDL3 como .so aparte
   (`libSDL3.so`), debe empaquetarse en el APK (llega solo por
   DT_NEEDED si está en jniLibs; si la carga falla con
   `UnsatisfiedLinkError`, añadir `"SDL3"` a `getLibraries()`).
3. **Medir** el tamaño de `libvita3k.so` y del APK (decenas de MB
   esperados; solo arm64-v8a).
4. **Probar en el teléfono (Helio G99):** importar el .pkg EU +
   work.bin -> instalar con el núcleo -> JUGAR embebido -> el juego
   arranca, FPS, pausa con el botón guía, salir limpio. Y la
   migración `ux0 -> vita/ux0` con una instalación de Etapa 1.
5. **Assets:** verificar que el núcleo encuentra `data/` y
   `shaders-builtin` (el workflow los copia a `assets/` como
   upstream; `initialize_session` pone `static_assets_path` vacío
   y hay que confirmar en dispositivo cómo los resuelve).
6. **Overlay táctil:** el layout táctil del launcher (Compose) y la
   vista SDL son dos mundos. El overlay nativo de Vita3K
   (`input_overlay.cpp`) se configura por `config.yml`; integrar
   el layout del launcher con él es el mayor trabajo restante.
   Mientras tanto: mando físico (SDL lo detecta solo) u overlay
   nativo.
7. **Menú de pausa con UI** (reanudar / salir / ajustes) y
   relanzamiento en proceso (`requestAppRelaunch`) para cambiar de
   Title ID sin recrear la Activity.
8. **GPL:** al distribuir el primer APK con el núcleo, añadir
   `COPYING.txt` (GPLv2 de Vita3K) + atribución (ver GPL-NOTA.md).
   Sin eso, NO distribuir el APK embebido.
9. **Medición automática de FPS** en la prueba de 30 s con
   `getFps()` (ya lee `EmuEnvState::fps`) y detección de logros.
10. **Opcional:** build type `reldebug` (RelWithDebInfo) como
    upstream si el `.so` Debug va lento en el teléfono.

## 7. Riesgos que siguen abiertos

- Tiempo de la primera build nativa en Actions (vcpkg + C++ grande;
  mitigado con caché binaria de vcpkg en el workflow borrador).
- C++23: el árbol exige C++23; NDK 29 lo soporta, pero es otro
  punto que solo la primera build confirma.
- Páginas de 16 KiB: los flags están en el CMake del adaptador y
  del stub; la build real debe mantenerlos.
- El `getLibraries()` devuelve solo `"vita3k"` (minúscula, el
  nombre real del target). Upstream devuelve `"Vita3K"` (mayúscula);
  no se sabe por qué les funciona: si la carga del .so falla en
  la primera build, es el primer sospechoso junto con SDL3.
