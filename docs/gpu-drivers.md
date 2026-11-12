# Drivers GPU — investigación 2026-11-07

Investigación local (sin SDK, sin teléfono, sin descargar binarios):
qué driver usa el launcher hoy por GPU, si se puede empaquetar un
driver personalizado "de raíz" y qué queda pendiente. Verificación
por revisión de código + búsqueda web de fuentes oficiales
(K11MCH1, Mesa, ARM). Nada de esto se probó en dispositivo real.

## Resumen por GPU

| GPU | Driver que usa el launcher hoy | ¿Driver personalizado empaquetable? | Fuente oficial + versión + licencia |
|---|---|---|---|
| Mali (incl. Mali-G57 MC2 del teléfono objetivo) | Driver Vulkan del sistema + presets 1x, Double Buffer, sin FXAA (G99 Recomendado / Mali Rendimiento / Mali Equilibrado) | **NO. No existe** un driver Mali empaquetable para Android en este núcleo | — (Panfrost es de Linux, ver abajo) |
| Adreno / Snapdragon | Driver del sistema por defecto; Turnip opcional si el usuario instala un .zip a mano (perfil Adreno Calidad / Turnip) | **SÍ, preparado sin binario**: zip en `assets/drivers/turnip/`, auto-instalación al detectar Adreno, selección por modelo, fallback al sistema | K11MCH1/AdrenoToolsDrivers, **Turnip v26.0.0 Revisión 8** (2026-01-08, Mesa 26.0.0-devel, base `e9a83d530cce`, Vulkan 1.4.335, 3.478.156 bytes, SHA-256 verificado 2026-11-11), licencia **MIT (Mesa)** |
| Otras (PowerVR, Xclipse, desconocida) | Driver del sistema, preset genérico 1x | NO (Turnip es solo Adreno; no hay equivalente) | — |

## TAREA 1 — Adreno / Turnip

### Qué hace hoy `TurnipDriverRepository.kt`

- Solo gestiona el ARCHIVO. `installDriverZip(uri, fileName)`
  copia el zip elegido por el usuario (selector SAF, `*/*`) a
  `<filesDir>/vita3k/drivers/turnip/` con copia a temporal +
  rename atómico (si la copia falla, el driver viejo no se pierde),
  guarda solo el último driver y registra nombre + tamaño en el log.
- **No descarga nada de ningún sitio.** No hay URL, ni catálogo,
  ni descarga en segundo plano. El usuario tiene que conseguir el
  zip por su cuenta.
- **No valida el contenido**: solo exige extensión `.zip` y
  sanea el nombre (basename, sin `/` ni `..`). Acepta cualquier
  zip, también uno que no sea un driver.
- **No extrae nada**: el zip se guarda tal cual; la carga real la
  haría el núcleo al arrancar.
- UI: Ajustes → Driver GPU (chips Sistema / Turnip + botón
  "Instalar / cambiar Turnip (.zip)" + "Limpiar caché de shaders"),
  visible SOLO en Adreno. En Mali la sección está oculta con el
  aviso de que Turnip solo funciona en Adreno.
- `LauncherViewModel.installTurnip()`: en Adreno activa Turnip y
  pone `customDriverName` = nombre del zip sin `.zip`; en Mali
  borra el zip y no persiste nada de Turnip (sigue el driver del sistema).
- `Vita3kConfigGenerator` emite `custom-driver-name: "<nombre>"`
  en config.yml solo si hay Turnip instalado; si no, `""`.
- `clearShaderCache()` borra `vita3k/shader_cache` y
  `vita3k/cache/shaders` (solo caché, nunca juego ni saves).

### Cómo carga el núcleo un driver Turnip (mecanismo)

- Formato del paquete (AdrenoTools, el que aceptan los emuladores
  Android): zip SIN descomprimir con `meta.json` en la raíz
  (nombre, versión, `libraryName`) y la librería Vulkan, en Turnip
  `libvulkan_freedreno.so`. Variantes documentadas: algunas builds
  usan `vulkan.adreno.so`, `libvulkan.so` bajo `vulkan/` o
  `metadata.json` en vez de `meta.json`; el formato exacto depende
  del constructor del zip.
- Mecanismo (libadrenotools, sin root): la app descomprime el
  paquete en su almacenamiento privado y su cargador Vulkan hace
  dlopen de esa librería solo para esa app, en vez del driver del
  sistema (`/vendor/lib64/hw/vulkan.adreno.so`). Cada app tiene que
  implementar la carga explícitamente; no es un ajuste del sistema.
- En este launcher la ruta es: zip en
  `<filesDir>/vita3k/drivers/turnip/` (copia del launcher) +
  instalación en el núcleo con `installCustomDriver` (puente
  `app/src/main/cpp/launcher/src/driver_bridge.cpp`, que reenvía a
  `native_drivers.cpp` de upstream) + clave `custom-driver-name`
  en config.yml (escrito de nuevo en cada lanzamiento por
  `Vita3KLauncher`).

### Lo verificado 2026-11-07 (pin `3e43bc80`)

- **El valor exacto de `custom-driver-name` es el stem del zip**
  (sin `.zip`, p. ej. `Turnip_v26.0.0_R8_Gmem`). Verificado en el
  árbol fijado: `native_drivers.cpp: installCustomDriver` deriva el
  nombre con `archive_path.filename().stem()` y extrae el zip en
  `<filesDir>/driver/<nombre>/`; `android_driver.cpp:
  resolve_custom_driver_library_path` busca ahí la carpeta con
  `meta.json` (clave `libraryName`) o `driver_name.txt`. El núcleo
  embebido (master) SÍ implementa la carga de drivers
  personalizados por esta vía (`renderer.cpp:
  resolve_vk_get_instance_proc_addr`); si el nombre no está
  instalado, avisa en el log y usa el driver del sistema. Por eso
  el launcher instala el driver en el núcleo al instalar el zip y
  al lanzar el juego (si no hay núcleo aún, el zip queda guardado
  y se instala al lanzar). "Turnip instalado" ahora significa "zip
  guardado + driver instalado en el núcleo + clave escrita"; la
  confirmación de que el driver cargó de verdad sigue siendo solo
  en teléfono (log del núcleo / diagnóstico GPU).
- Layout del zip R8 VERIFICADO (ronda 0.2.64, 2026-11-11): el asset
  `Turnip_v26.0.0_R8_Gmem.zip` de la release v26.0.0-rc08 trae
  `meta.json` en la raíz (305 bytes, libraryName
  `vulkan.ad07xx.so`, "Mesa Turnip driver v26.0.0 - R8 GMEM",
  driverVersion "Vulkan 1.4.335", minApi 27) + `vulkan.ad07xx.so`
  (17.159.433 bytes). La validación estricta (meta.json en raíz +
  .so con el libraryName que declara) coincide con este layout real;
  no hizo falta relajarla. SHA-256 registrado en
  `BundledTurnipDriver.EXPECTED_SHA256`:
  `b60bffe2844a271cd293011de307fe06d28549bd5b93d03749b7c9f39af4114b`
  (3.478.156 bytes). No hay Turnip K11MCH1 más nuevo que la R8: las
  releases posteriores (v840/v837, 2026-01-14) son drivers
  propietarios Qualcomm, no Turnip.

### Qué haría falta para llevar un Turnip de raíz

1. Binario: `Turnip_v26.0.0_R8_Gmem.zip` de la release v26.0.0 R8
   de K11MCH1/AdrenoToolsDrivers (8 ene 2026). 3.478.156 bytes, SHA-256
   `b60bffe2844a271cd293011de307fe06d28549bd5b93d03749b7c9f39af4114b`
   (verificado 2026-11-11). MIT (Mesa).
   **No descargado ni incluido en este trabajo.**
2. Antes de ponerlo: re-verificar la release vigente (la R8 ya no
   es la más reciente en general; p. ej. MrPurple publica T30, ago
   2026; whitebelyash v32, sep 2026). NO se cambia de constructor
   sin prueba en teléfono: la R8 sigue siendo la verificada. Si se
   cambia, calcular su SHA-256 y rellenarlo en
   `BundledTurnipDriver.EXPECTED_SHA256`.
3. Layout del zip ya confirmado (ver arriba): la validación no
   necesita ajuste.
4. Verificar el punto anterior (custom-driver-name + carga en el
   núcleo fijado) y luego probar en un teléfono Adreno real.

### Qué se dejó preparado en código (sin binario)

- `data/BundledTurnipDriver.kt` (nuevo, Kotlin puro, testeable en
  JVM): `ASSET_PATH = "drivers/turnip/Turnip_v26.0.0_R8_Gmem.zip"`,
  `EXPECTED_SHA256 = "b60bffe2844a271cd293011de307fe06d28549bd5b93d03749b7c9f39af4114b"`
  (verificado 2026-11-11 contra el asset de la release v26.0.0-rc08),
  `selectVariant(renderer)` (6xx y 725/730/735/740/750 →
  STANDARD_GMEM; 710/720/722, 8xx, A32, Mali, desconocido → null,
  sin auto-instalación), `validatePackage(zip)` (zip válido +
  meta.json + al menos un .so + libraryName presente en el zip +
  SHA-256 si está relleno), `sha256Hex`.
- `data/TurnipDriverRepository.kt`: `bundledAssetPresent()`,
  `installBundledAsset()` (copia assets → temporal → valida →
  confirma con rename atómico; si falta el zip o falla la
  validación no toca el driver instalado), `commitTempFile()`
  extraído de `installDriverZip()` (mismo comportamiento que
  antes) y `BundledInstall`.
- `ui/LauncherViewModel.kt`: `ensureBundledTurnipDriver()` (solo
  Adreno, solo si no hay driver instalado, silencioso si no hay
  nada que hacer; avisa solo al instalar con éxito) llamado al
  final de `detectGpu()` y en `init` si la GPU ya estaba guardada.
- `app/src/main/assets/drivers/turnip/README.md`: hueco documentado
  del binario (fuente, versión, tamaño, licencia, qué NO poner).
- `app/src/test/.../BundledTurnipDriverTest.kt`: 12 tests
  (selección por modelo, SHA-256, validación de paquetes).
- Sin cambios en textos visibles de la app (el único mensaje nuevo
  es el de éxito de la instalación incluida, sin mencionar el
  núcleo). Sin cambios en presets, fallback de Etapa 1 ni build.
- El workflow de CI copia assets del núcleo a `app/src/main/assets/`
  pero solo borra `data/` y `shaders-builtin/`; `drivers/` sobrevive.

### Especificidad por modelo

Turnip NO es un único archivo universal para todos los Adreno: un
build estándar cubre 6xx/7xx, pero hay variantes por generación
(tag a6xx en releases viejas, hacks 710/720 que se quitaron y
repusieron, build A8xx aparte y muy beta en la R8, edición A32
aparte) y dos sabores en la R8 (Gmem y Autotuner). Por eso la
selección es por modelo leído del renderer GLES y por defecto es
conservadora: ante la duda, no se instala nada y manda el sistema.
La variante incluida elegida es la estándar Gmem de la R8.

## TAREA 2 — Mali: no existe driver personalizado para Android

- **Panfrost es de Linux, no de Android.** Es el driver open-source
  de Mesa para Mali (OpenGL ES; PanVK para Vulkan), desarrollado con
  Collabora/ARM, y funciona con el driver de kernel Linux
  `panfrost.ko` / `panthor.ko` + Mesa en userspace + display por
  kmsro. Lo confirma la documentación de Mesa y de ARM: "Mali GPUs
  running Linux operating systems", "Developed to work within the
  Linux environment". En Android de serie el kernel lleva el DDK
  propietario de Mali y el driver Vulkan del fabricante; una app no
  puede sustituir el driver del sistema.
- **No hay un ".zip de driver Mali" equivalente a los de Turnip
  para este núcleo.** El mecanismo de driver personalizado del
  núcleo es solo Adreno/Turnip (AdrenoTools/libadrenotools cargan
  `libvulkan_freedreno.so`). No hay en el núcleo una vía para
  cargar un `libvulkan_panfrost.so`, y aunque alguien compilara
  PanVK para Android, sin el driver de kernel Panthor/Panfrost no
  podría hablar con la GPU. (Matiz: contenedores tipo GameNative/
  Winlator sí empaquetan PanVK con el mismo formato de zip para
  sus propios loaders; eso no es este launcher ni este núcleo, y
  no se traslada aquí. No se inventa un driver Mali.)
- **Qué hace el launcher en su lugar para Mali**: driver Vulkan del
  sistema (el del fabricante, el único disponible) + ajustes
  seguros: Vulkan 1x (960x544), Double Buffer (valor de los
  presets Mali; el núcleo honra Page Table/Native Buffer cuando su
  bit está en la máscara, no hay fallback forzado a Double Buffer),
  Bilinear (FSR queda como opción manual en Ajustes → GPU, ya no es
  el filtro de ningún preset desde 0.2.65),
  V-Sync OFF, async pipeline ON, high accuracy OFF, Surface Sync
  Disabled, SDL + NGS, y OpenGL como fallback manual
  si hay glitches. El preset por defecto en el teléfono objetivo
  es G99 Recomendado porque el usuario confirmó que el juego va
  muy bien así; no se prometen 60 fps en Mali.
- Consecuencia para el usuario: en su teléfono (Mali-G57 MC2) el
  trabajo de drivers NO cambia nada: no hay driver que poner "de
  raíz". La estabilidad/fluidez en Mali viene de los presets, del
  modo seguro y de la caché de shaders, no de un driver nuevo.

## Pendiente (para el coordinador / pasos posteriores)

1. ~~Verificar en el árbol fijado (pin `3e43bc80`, solo CI) o en
   teléfono Adreno: si el núcleo embebido carga drivers por
   `custom-driver-name` y qué valor exacto espera.~~ **VERIFICADO en
   0.2.64** (pin `native_drivers.cpp` + `android_driver.cpp`): el
   núcleo embebido sí carga drivers por `custom-driver-name` (el
   stem del zip). La confirmación de que el driver cargó de verdad
   sigue siendo solo en teléfono (log del núcleo / diagnóstico GPU,
   vía `getCustomDriverSupportInfo` desde 0.2.65).
2. Elegir la release Turnip vigente, descargar el zip, calcular
   SHA-256, rellenar `EXPECTED_SHA256`, poner el zip en
   `app/src/main/assets/drivers/turnip/` y confirmar su layout
   contra `validatePackage` (ya verificado: ver arriba).
3. CI: correr los tests nuevos (`BundledTurnipDriverTest`) y
   comprobar que el APK con el zip incluido crece ~3,5 MB y que el
   job nativo no se rompe por el asset nuevo.
4. Teléfono Adreno real: instalar, comprobar que el driver se
   activa (diagnóstico del núcleo / log) y que el juego no
   regresa (si regresa, "volver al driver del sistema" en
   Ajustes). En el teléfono Mali del usuario no hay nada que probar
   de drivers: solo confirmar que sigue todo igual (driver del
   sistema, sin mensaje de driver incluido).
5. No empaquetar nunca drivers Qualcomm propietarios (v615/v840)
   como si fueran libres.
