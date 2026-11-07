# Child of Light Launcher — Etapa 1

Launcher de **un solo juego** para Android: Child of Light (PS Vita).
Tocas el icono, el launcher importa tu copia legal, guarda los ajustes
y lanza el juego. Sin biblioteca de emulador, sin lista de juegos.

> **Versión:** 0.1.0 (Etapa 1) · **App ID:** `com.danielsnagustin.childoflight.launcher`
> **minSdk 28 · targetSdk 35 · compileSdk 35** · Kotlin + Jetpack Compose
> **Teléfono objetivo:** Samsung Galaxy A17 4G SM-A175F (Helio G99, Mali-G57 MC2)

---

## Qué es

- Un **launcher** para un solo juego: Child of Light de PS Vita.
- El usuario pone **su propia copia legal** del juego + DLCs + update.
- El launcher la **instala/guarda como una aplicación normal** en la
  estructura real de Vita3K (`ux0/app/...`, `ux0/addcont/...`,
  `ux0/patch/...`, saves en `ux0/user/00/savedata/...`).
- Tiene **todos los ajustes** de rendimiento, calidad y escalado
  (GPU, CPU, audio, driver, overlay), guardados en DataStore, y
  genera el `config.yml` de Vita3K a partir de ellos.
- Controles táctiles personalizables por completo, 27 logros locales,
  diagnóstico de GPU, prueba de 30 segundos y reporte de bugs.

## Qué NO es

- **No es un port nativo.** Child of Light usa el motor UbiArt de
  Ubisoft, propietario y nunca publicado como código abierto. Sin el
  código de Ubisoft no existe port nativo posible.
- **No incluye el juego, ni firmware, ni claves, ni ROMs.** El APK
  sale vacío: hay que importar la copia del usuario.
- **No es Vita3K.** En Etapa 1 el núcleo Vita3K **no está embebido**:
  JUGAR escribe `config.yml` y abre Vita3K oficial si está instalado
  (`org.vita3k.emulator`). No se finge que el juego corre sin núcleo.
  El esqueleto JNI de Etapa 2 ya existe (ver abajo), pero sin `.so`
  no se activa.
- **El primer uso solo pide juego + DLCs, nada más.** El firmware
  nunca es un paso visible inicial: el launcher lo resuelve por dentro
  (usa el que ya tengas guardado o el de Vita3K) y solo existe como
  opción avanzada en Ajustes → Diagnóstico. Si falta de verdad, lo
  dice en una frase, sin pedirte que lo busques por internet.
- **No sincroniza logros con PSN / PlayStation Network ni Steam.**
  Los 27 logros son locales del launcher.
- **No hay platino.** El juego no tiene platino en PS4 (20 trofeos)
  ni en Vita (27 trofeos).

---

## Cómo importar juego + DLCs (paso a paso)

**El primer uso solo pide juego + DLCs, nada más.** No hay paso de
firmware en la pantalla principal ni en el primer arranque.

1. Instala el APK y abre el launcher. En el primer arranque detecta
   tu GPU y aplica el preset recomendado.
2. **Importar juego:** botón en la portada → elige tu `.zip` o `.vpk`
   (un `.vpk` es un zip). El launcher valida antes de instalar nada:
   - La carpeta principal del zip debe ser **PCSE00480 (US)** o
     **PCSB00598 (EU)**. Cualquier otro Title ID se rechaza con el
     motivo exacto.
   - Debe contener `<TITLE_ID>/eboot.bin`.
   - Se instala en `ux0/app/<TITLE_ID>/` (la carpeta DEBE llamarse
     como el Title ID, como exige Vita3K).
   - Si el juego trae su `TROPHY.TRP`, se anota; si no, se usa la
     lista bundled de 27 trofeos.
3. **Importar DLC:** elige el `.zip`/`.vpk` del DLC. Debe traer la
   ruta `<TITLE_ID>/addcont/<DLC_ID>/...` dentro del zip. Se instala
   en `ux0/addcont/<TITLE_ID>/<DLC_ID>/`.
4. **Importar update:** elige el `.zip`/`.vpk` con la carpeta
   `<TITLE_ID>` como principal. Se instala en `ux0/patch/<TITLE_ID>/`.
5. **Archivos `.pkg`:** no se pueden inspeccionar sin un parser de
   paquetes de Vita. Se aceptan solo si el nombre del archivo contiene
   el Title ID y se guardan en `import_pending/` con el aviso claro
   de que su instalación real la hace Vita3K (Etapa 2). **Nunca** se
   muestran como "juego instalado".
6. Pulsa **JUGAR**. Si falta el juego, el launcher lo dice con el
   motivo exacto en vez de fallar en silencio.

### Firmware (sin pedirlo nunca al inicio)

- El launcher **resuelve el firmware por dentro**: si ya tienes
  `PSVUPDAT.PUP` guardado o un `vs0/` extraído en el launcher, lo usa;
  si Vita3K externo ya tiene firmware instalado, usa el suyo al
  arrancar (el launcher no puede ver el almacenamiento privado de
  otra app, así que no lo comprueba: solo avisa con una frase si
  falta el local y lanza igual).
- Si falta de verdad, el launcher lo explica **en una sola frase**,
  sin pedirte que lo busques por internet.
- Importar firmware solo existe como **opción avanzada oculta** en
  Ajustes → Avanzado — Firmware (y su estado se ve en GPU y
  diagnóstico). Nunca es un paso obligatorio inicial.
- El launcher no incluye firmware, ni juego, ni claves, y no descarga
  firmware de internet.

## Estructura de archivos (dentro del almacenamiento privado de la app)

```
<filesDir>/vita3k/
  config.yml                  <- generado desde Ajustes
  launcher.log                <- log propio del launcher
  firmware/PSVUPDAT.PUP       <- tu firmware (solo opción avanzada)
  firmware/installed.json     <- marcador
  drivers/turnip/*.zip        <- driver Turnip (solo Adreno)
  ux0/app/PCSE00480/          <- juego (o PCSB00598)
  ux0/addcont/PCSE00480/<DLC>/
  ux0/patch/PCSE00480/
  ux0/user/00/savedata/PCSE00480/   <- savedata VIVA (sesión activa)
  ux0/user/00/trophy/PCSE00480/
  sessions/                   <- sesiones / partidas
    index.txt                 <- índice de sesiones
    active.txt                <- id de la sesión activa
    <id>/savedata/            <- copia de savedata de esa sesión
    <id>/achievements.txt     <- logros locales de esa sesión
    <id>/control-profile.txt  <- perfil de control de esa sesión
  import_pending/             <- .pkg guardados sin instalar
```

---

## Optimización Mali vs Snapdragon

El foco es el **Helio G99 / Mali-G57 MC2** del Galaxy A17: el usuario
confirmó que Child of Light **va muy bien** en ese teléfono. No es un
"ojalá funcione". Por eso el preset por defecto es G99 Recomendado
(1x, Frame Skip 0): se optimiza para mantener esa fluidez estable,
no para recortar de más por miedo a Mali en general.

La GPU se detecta al primer arranque (renderer GLES vía EGL + SoC +
features de Vulkan) y se guarda. El preset por defecto cambia según
la GPU detectada.

| | G99 / Mali-G57 — Recomendado | G99 — Calidad (probar 2x) | Mali genérico — Rendimiento | Snapdragon / Adreno — Rendimiento | Snapdragon / Adreno — Calidad / Turnip |
|---|---|---|---|---|---|
| Backend | Vulkan | Vulkan | Vulkan (fallback OpenGL si hay glitches) | Vulkan | Vulkan + Turnip |
| Resolución interna | 1x (960x544) | 2x (1920x1088) | 1x SIEMPRE | 1x | 2x (1920x1088) |
| Filtro | Bilinear | FSR | Bilinear | Bilinear | FXAA |
| Anisotrópico | 1x | 1x | 1x | 1x | 4x |
| V-Sync | OFF | OFF | OFF | OFF | ON |
| Async Pipeline | ON | ON | ON | ON | ON |
| Surface Sync | Disabled | Disabled | Disabled | Disabled | Disabled |
| Memory Mapping | Double Buffer | Double Buffer | Double Buffer FORZADO | Page Table | Page Table |
| Accurate Thread Scheduling | ON | ON | ON | ON | ON |
| JIT (cpu-opt) | ON | ON | ON | ON | ON |
| Frame limiter | 30 FPS | 30 FPS | 30 FPS | 30 FPS | 60 FPS |
| Frame Skip | 0 | 0 | 1 | 0 | 0 |
| Driver | Sistema | Sistema | Sistema | Sistema | Turnip |

**Regla de la prueba de 30 s (solo G99 Calidad):** si el FPS medio
baja de 28, el launcher vuelve a 1x automáticamente (si el ajuste
está activado) y lo registra en el historial de pruebas.

**Honestidad Mali:** en Mali hay cosas que definitivamente no
funcionan y es posible que nunca funcionen, pero Vita3K+ trae
arreglos para Mali (vertex fix, Double Buffer mejorado) y Child of
Light, al ser 2D UbiArt, es más ligero que un juego 3D: a 1x tiene
más chance. **No se prometen 60 fps en Mali.** En Mali nunca se
ofrece 2x/3x/4x como recomendado, solo como opción manual con
advertencia.

**Turnip:** driver Mesa open-source solo para Adreno. Se instala
desde Ajustes → Driver GPU (selector de zip, versión instalada,
limpiar caché de shaders al cambiar de driver). En Mali la sección
Turnip está **oculta** y se muestra el aviso "Turnip solo funciona
en Qualcomm Adreno, no en Mali" + la opción de probar OpenGL.

**Diagnóstico (pantalla GPU y diagnóstico):** SoC, GPU, versión
Vulkan, driver, versión del núcleo, log reciente, prueba de 30 s,
desactivar optimización de batería y "Reportar bug" (exporta log +
ajustes + device info en un `.txt`).

---

## Ajustes (todos funcionales, en DataStore)

- **GPU:** backend Vulkan/OpenGL · resolución 1x–4x · filtro
  Bilinear/Nearest/Bicubic/FXAA/FSR · anisotrópico 1x–16x · V-Sync ·
  Async Pipeline · High Accuracy · Surface Sync · Memory Mapping
  (Double Buffer / Page Table / Native Buffer / External Host / Off)
  · caché de texturas y de shaders.
- **CPU:** optimización JIT (cpu-opt) · FPS Hack 30→60 · retraso de
  carga de archivos.
- **Audio:** backend SDL/Cubeb · buffer 256/512/1024/2048 · volumen
  0–100% · NGS ON/OFF · botón **Corregir audio** (SDL + buffer 1024
  + NGS ON + limpiar caché de shaders + relanzar el juego).
- **Rendimiento:** límite de FPS 30/60/sin límite · Frame Skip 0/1/2 ·
  overlay de FPS · volver a 1x si <28 fps · modo rendimiento
  (pantalla encendida).
- **Driver GPU:** Sistema / Turnip (solo Adreno) + instalar Turnip +
  limpiar caché de shaders.
- **Corrección de errores:** Accurate Thread Scheduling · caché de
  shaders · cerrar apps en segundo plano antes de jugar ·
  **Modo seguro** (fuerza 1x, SDL, buffer 2048, Thread Scheduling ON,
  High Accuracy OFF).
- **Presets:** G99 Recomendado, G99 Calidad (2x), Mali Rendimiento,
  Mali Equilibrado, Adreno Rendimiento, Adreno Calidad/Turnip,
  Genérico. Botón "Ver config.yml generado" y "Restablecer".

### config.yml: qué es real y qué es TODO

El generador (`Vita3kConfigGenerator`) solo emite claves verificadas
en `vita3k/config/include/config/config.h` de Vita3K (master,
2026-11-07): `backend-renderer`, `resolution-multiplier` (float),
`screen-filter`, `v-sync`, `anisotropic-filtering`,
`async-pipeline-compilation`, `high-accuracy`,
`disable-surface-sync` (inverso de Surface Sync),
`memory-mapping` (solo `"double-buffer"` verificado como default),
`texture-cache`, `shader-cache`, `audio-backend`, `audio-volume`,
`ngs-enable`, `cpu-opt`, `fps-hack`, `file-loading-delay`,
`performance-overlay`, `custom-driver-name`, `pref-path`.

Los ajustes **sin** clave verificada (`frame limiter`,
`frame skipping`, `accurate thread scheduling`, `audio buffer size`,
strings de memory-mapping distintos de `double-buffer`, string
exacto de Cubeb) se escriben en el YAML como comentario
`TODO-VERIFY`, nunca como clave inventada. Los tests
(`Vita3kConfigGeneratorTest`) comprueban que esas claves no se
emiten como reales.

---

## Logros (27 trofeos de Vita)

La versión Vita tiene **27 trofeos** (la PS4 tiene 20; no confundir).
No hay platino en ninguna. Lista bundled exacta (GameFAQs Vita):

1. Starcatcher (secreto) · 2. Over the Moon (secreto) · 3. Sunrise
(secreto) · 4. Coronation (secreto, Silver) · 5. Comedy · 6. The Girl
and the Firefly · 7. Capilli Catch · 8. Sweet Sister · 9. Bolmus Bond ·
10. Kategida Kindred · 11. Piscean Promise · 12. Tragedy ·
13. Tenth Birthday · 14. Perfectionist · 15. Master Oculist ·
16. Scene Stealer · 17. Showstopper · 18. Through the Looking Glass ·
19. Winged Fury · 20. Explorer · 21. Perfect Sonnet · 22. Grassroots ·
23. General of Light · 24. Guardians of Lemuria · 25. Aurora Borealis ·
26. When Pigs Fly · 27. Golden Apple.

- Pantalla **Logros**: los 27 con progreso %, bloqueado/desbloqueado,
  fecha de desbloqueo. Los secretos se ocultan ("Trofeo secreto / ???")
  hasta desbloquearlos. Icono placeholder generado (vector propio,
  sin imágenes con copyright).
- **Through the Looking Glass** avisa que puede no desbloquearse sin
  la función online de Vita (enviar Oculi a un amigo).
- Al desbloquear uno: **popup + sonido** de notificación del sistema.
- Fuente de verdad: los datos de trofeos de Vita3K en
  `ux0/user/00/trophy/` se leen como referencia (¿existe la carpeta?,
  ¿qué archivos hay?, ¿vino `TROPHY.TRP` con el juego?). El formato
  TRP **no se decodifica en Etapa 1** y así se dice en la UI; el
  progreso que se muestra es el **local del launcher** (DataStore).
- Exportar/importar progreso de logros junto con los saves
  (pantalla Guardados).

## Audio y bugs (alcance del launcher)

El launcher **no puede reescribir el núcleo Vita3K**, pero deja
configuradas las mitigaciones conocidas:

- **Audio:** backend por defecto **SDL** (en Vita3K-Android v7 se
  puso SDL como default porque arregla ruido; cambiar a SDL puede
  arreglar issues restantes), alternativa **Cubeb** (Vita3K+ v1.3:
  cubeb y SDL ya no deberían crepitar/cortar). Buffer configurable
  (subirlo un paso si hay crackles), volumen, NGS ON, botón
  **Corregir audio** (SDL + buffer 1024 + limpiar caché + relanzar).
- **Rendimiento/bugs:** Accurate Thread Scheduling ON (default nuevo,
  evita freezes/cuelgues), shader cache ON siempre (el stutter de la
  primera partida se va cuando se llena la caché), Async Pipeline ON,
  1x en G99, cerrar apps en segundo plano, desactivar optimización
  de batería.
- **Bugs conocidos de Child of Light (Vita):** la versión Vita es
  casi perfecta; solo **stutter leve de FPS a veces**. El bug grave
  de PS3 por Uplay **no aplica a Vita** y no se inventan bugs de Vita
  que no existen.
- **Diagnóstico:** FPS (última prueba), backend, driver, versión del
  núcleo, log reciente, botón **Reportar bug** (exporta log + ajustes
  + device info).
- **Modo seguro** (Ajustes → Corrección de errores): 1x, SDL, buffer
  grande, Thread Scheduling ON, High Accuracy OFF. Para cuando algo
  falle.

---

## Personalizar todo y borrar lo que no se usa

### Controles táctiles (editor completo)

Cada botón se puede: **mover arrastrando** en la vista previa,
cambiar **tamaño**, **opacidad**, **color**, **mostrar/ocultar**,
**forma** (círculo/cuadrado) y **etiqueta**. Y **remapear** a
cualquier botón de Vita: Cruz, Círculo, Cuadrado, Triángulo, L, R,
Start, Select, D-Pad (4 direcciones), Stick izquierdo/derecho, L3/R3.
El stick izquierdo cambia entre **analógico / D-Pad / ambos**.
La zona **Igniculus** tiene tamaño, posición, **sensibilidad**,
activar/desactivar y modo **toque directo vs joystick**.

**Perfiles de control** (crear, duplicar, renombrar, borrar,
importar/exportar; todo en DataStore). Predefinidos:
- **Por defecto G99** — layout completo.
- **Una mano** — stick + A/B grandes, X/Y y Start ocultos.
- **Mando externo + táctil mínimo** — todo oculto salvo Igniculus
  y Start (para jugar con mando físico).

### Personalizar el launcher

Tema claro/oscuro/sistema, **color de acento**, **fondo de la
portada**, **tamaño de texto**, **idioma español/inglés** (traduce
portada, navegación y títulos de sección; las notas técnicas de
Vita3K permanecen en español en Etapa 1), mostrar/ocultar FPS y
logros en la portada, y **orden de secciones** (subir/bajar).
Botón **Restablecer toda la personalización**.

### Borrar lo que no se usa

- En el editor, cada botón tiene **papelera con confirmación**:
  borrado, desaparece del overlay y no estorba.
- **Ajustes → Limpiar no usados:** botones ocultos, perfiles sin
  usar, presets de GPU sin usar (se ocultan de la lista, no se borra
  código), driver Turnip instalado sin usar, DLCs (borra de
  `ux0/addcont/`, con confirmación uno a uno) y saves (solo saves).
  También hay **borrado en lote** con confirmación (DLCs y saves
  nunca entran en el lote: siempre uno a uno).
- Si borras **todos** los botones y usas mando físico: interruptor
  **"Ocultar controles táctiles si hay mando conectado"** (detecta
  el mando con `InputDevice` real). Con mando conectado, el overlay
  no aparece.

---

## Controles táctiles (layout por defecto G99)

Stick izquierdo para Aurora · A/B/X/Y · Start · zona táctil para
Igniculus (la luciérnaga). El layout se edita en la pantalla
Controles táctiles y se guarda por perfil en DataStore.

## Sesiones / partidas múltiples (nuevo juego sin perder lo anterior)

En Vita el guardado es una sola carpeta
(`ux0/user/00/savedata/<TITLE_ID>/`), así que el launcher gestiona
varias sesiones por separado. Cada sesión tiene: nombre, fecha de
creación, última vez jugado, tiempo jugado, capítulo/progreso
(manual, ver abajo) y su propia copia de savedata + progreso de
logros locales + perfil de control, en `sessions/<id>/`.

- **Pantalla Guardados → Sesiones / partidas:** lista de sesiones con
  cuál es la activa (★ ACTIVA).
- **Nueva sesión:** crea una sesión vacía nueva SIN borrar la
  anterior. Antes de crearla, hace un snapshot automático de la
  sesión actual (savedata + logros + controles) y luego la viva
  empieza vacía: nuevo juego desde cero, sin perder lo anterior.
- **Continuar:** carga la sesión seleccionada (restaura su savedata
  a la carpeta viva). **Cambiar sesión:** guarda la actual y carga
  la otra. Nunca se sobrescribe sin avisar.
- **Por sesión:** renombrar (+ capítulo), duplicar, exportar (.zip),
  importar (.zip), borrar (con confirmación; nunca la única sesión
  sin avisar: si solo queda una, se advierte antes de borrarla).
- **Seguridad:** antes de lanzar el juego, el launcher activa la
  sesión correcta (snapshot de la viva + restore de la activa). Al
  volver del juego, suma el tiempo jugado y guarda el snapshot de lo
  jugado. Si el juego intenta sobrescribir el save, la sesión
  anterior sigue guardada en `sessions/<sesión>/`.
- **Capítulo/progreso:** campo manual (lo escribe el usuario). Los
  saves de Vita son binarios y el launcher no decodifica el capítulo
  desde ellos; no se inventa un capítulo leído del save.
- **Borrar saves** borra también la copia guardada de la sesión
  activa (para que el borrado no "resucite" al lanzar). **Borrar
  datos del juego** borra juego + update + DLCs + saves + TODAS las
  sesiones.

## Guardados

Carpeta viva: `ux0/user/00/savedata/<TITLE_ID>/` (siempre la sesión
activa). Botones Exportar (zip por SAF) / Importar saves (.zip) /
Borrar datos del juego (con confirmación: borra juego + update +
DLCs + saves + sesiones). El progreso de logros se exporta/importa
junto con los saves.

---

## Cómo compilar (sin PC, por GitHub Actions)

1. Crea el repo en GitHub y sube esta carpeta (rama `main`).
2. Cada push dispara `.github/workflows/android.yml`:
   - JDK 17 + Gradle 8.7.
   - `gradle :app:testDebugUnitTest` (tests unitarios).
   - `gradle :app:assembleDebug` y `:app:assembleRelease`.
   - Artefactos: `app-debug.apk` y `app-release-unsigned.apk`
     (el release sin firmar; para firmar añade tus claves en
     GitHub Secrets).
3. Descarga el APK en la pestaña **Actions** del repo.

Compilación local (si tienes Android Studio): abre la carpeta,
sincroniza Gradle y ejecuta `:app:assembleDebug`. No hay `gradlew`
incluido (igual que en ultimate-gamepad-android): el workflow usa
el Gradle instalado por `gradle/actions/setup-gradle`.

---

## Etapa 2 (investigación + esqueleto, no funcional aún)

Documento completo: [`docs/etapa2/ETAPA2-PLAN.md`](docs/etapa2/ETAPA2-PLAN.md).

Verificado contra el código público de Vita3K (master, 2026-11-07)
y contra EmuCoreV (el fork con núcleo embebido en Android):

- Upstream **no** expone un `loadGame(titleId)` público: el arranque
  real es `SDL_main` con argv `{"-r", "<TITLE_ID>"}` y el bootstrap
  es `NativeLib.init(storagePath)`; las JNI de upstream están ligadas
  a `org.vita3k.emulator.NativeLib`, así que hace falta una capa
  adaptadora propia (modelo EmuCoreV: árbol Vita3K vendorizado +
  puente JNI en nuestro paquete).
- Esqueleto ya creado (sin romper Etapa 1, sin `.so` no se activa):
  `core/Vita3kCoreBridge.kt` (Kotlin, `init`/`loadGame`/`getFps`/
  `shutdown`, devuelve `NOT_IMPLEMENTED` sin núcleo),
  `app/src/main/cpp/CMakeLists.txt` + `Vita3kBridge.cpp` (stubs JNI
  `TODO-ETAPA2`), y `Vita3KLauncher` ya los usa con fallback a Vita3K
  externo.
- Siguiente paso concreto: añadir el submódulo Vita3K en
  `app/src/main/cpp/vita3k`, escribir el adaptador mínimo, cablear
  NDK 29 + CMake 3.22.1 + vcpkg en Actions y medir el tamaño del
  `.so`. Todo el detalle (archivos, riesgos, qué no se puede hacer
  en esta VM) está en el plan.

## Plan Etapa 2 (resto, pendiente, no en este APK)

1. **Embeber el núcleo Vita3K (.so):** ver sección Etapa 2 y
   `docs/etapa2/ETAPA2-PLAN.md`. Sin esto, JUGAR abre Vita3K
   externo si está instalado.
2. **Deep-link verificado** para arrancar un Title ID concreto en
   Vita3K externo (hoy se lanza el Intent MAIN sin extras: no hay
   deep-link documentado verificado).
3. **Extracción real del firmware** `.PUP` a `vs0/` (hoy solo se
   guarda el PUP + marcador; la extracción la hace Vita3K).
4. **Medición automática de FPS** en la prueba de 30 s (hoy el FPS
   se registra manualmente desde el overlay) y detección automática
   de desbloqueo de logros durante la partida (hoy se marca desde
   la pantalla de Logros o al importar progreso).
5. **Decodificar `TROPHY.TRP`** para validar/sincronizar el progreso
   de trofeos con Vita3K (hoy solo se lee su existencia).
6. **Parser de `.pkg`** para instalar paquetes sin depender del
   nombre del archivo.
7. Traducción completa ES/EN de las notas técnicas de ajustes.
8. Arrastrar con dos dedos / gestos en el editor táctil (hoy: un
   dedo, tamaño por slider).

## Licencia y legal

- El launcher es código propio (Kotlin/Compose). Vita3K es GPLv2;
  al embeber su núcleo en Etapa 2 habrá que cumplir la GPLv2
  (publicar el código del núcleo y sus modificaciones).
- Child of Light © Ubisoft. UbiArt es tecnología cerrada de Ubisoft.
  Este launcher no incluye ni distribuye el juego, sus assets, el
  firmware de Vita ni claves. El usuario debe aportar su copia legal.
