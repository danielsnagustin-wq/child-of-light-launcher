# Parches experimentales — Fase 3 (NO se aplican en la compilación)

El workflow aplica SOLO el top-level (`patches/*.patch`), NUNCA este
directorio. Lo que hay aquí está preparado y documentado, con su puerta
de teléfono: cada parche se prueba UNO por uno, nunca mezclado con otro
cambio, y se revierte entero si rompe cualquiera de la matriz (§9 de la
verificación Vita3K+): arranca y renderiza (título + gameplay),
instalación única, DLCs + trofeos (20, español), controles del mockup,
español por defecto, presets (G99_QUALITY, MALI_BALANCED), máscara de
mapeo en Diagnóstico.

Los `.FORK-RAW.diff` son el diff exacto del commit del fork contra SU
padre (no contra nuestro pin): NO aplican limpios contra el pin 3e43bc80
(comprobado con `git apply --check`). Están aquí como referencia exacta
de lo que hizo el fork, no como parches aplicables. Los `cpu-*.patch`
SÍ aplican limpios contra el pin (verificados con `git apply --check`):
son puertos propios, escritos contra el código real del pin.

## Estado por parche

| Archivo | Qué es | Estado | Interruptor |
|---|---|---|---|
| `cpu-01-halt-on-yield-wfe.patch` | CPU-01: Yield/WFE paran el JIT + descanso 100 us | Listo para probar (aplica limpio) | No (experimento con puerta; ver protocolo) |
| `cpu-02-big-core-affinity.patch` | CPU-02: hilo principal a núcleos grandes | Listo para probar (aplica limpio) | No (experimento con puerta) |
| `cpu-04-jit-code-cache-cap.patch` | CPU-04: tope de caché JIT 64 MiB (parametrizable) | Listo para probar (aplica limpio) | No (experimento con puerta) |
| `cpu-06-unsafe-unfuse-fma-only.patch` | CPU-06: SOLO Unsafe_UnfuseFMA | Listo para probar (aplica limpio) | No (experimento con puerta) |
| `cpu-07-fastmem-exclusive-access.patch` | CPU-07: LDREX/STXR por fastmem | Listo para probar (aplica limpio) | No (experimento con puerta) |
| `r2-texture-budget-low-memory.FORK-RAW.diff` | R2 = fork P7 (`bb23ab9`) | NO portable tal cual (ver abajo) | NO creado |
| `r3-double-buffer-wrc3.FORK-RAW.diff` | R3 (`bd9acc1`) | NO portable tal cual (ver abajo) | NO creado |
| `r4-double-buffer-fifa15-mali.FORK-RAW.diff` | R4 (`a67e20e`) | NO portable tal cual (ver abajo) | NO creado |
| `r5-double-buffer-sync-nfs.FORK-RAW.diff` | R5 (`e530c4e`) | NO portable tal cual (ver abajo) | NO creado |
| `r6-gamepad-poll-fps-hack.FORK-RAW.diff` | R6 (`0ad4ee3`) | NO portable tal cual (ver abajo) | No (lo gobierna fps-hack, cuando se porte) |
| `r7-vblank-period-fps-hack.FORK-RAW.diff` | R7 (`bc5056c`) | NO portable tal cual (ver abajo) | No (lo gobierna fps-hack, cuando se porte) |
| `p12-accurate-thread-scheduling-part1/2.FORK-RAW.diff` | P12 = CPU-03 (`ffa287b4` + `5d25698c`) | NO cherry-pickeable (ver abajo) | NO creado |

## Por qué R2 no se adapta en esta ronda

- El diff crudo conflicta en 6 archivos (`mem.cpp`, `texture_cache.h`,
  `types.h`, `context.cpp`, `renderer.cpp`, `texture.cpp`) y 2 archivos
  NO EXISTEN en el pin: `vita3k/util/src/mem_snapshot.cpp` y
  `vita3k/util/include/util/mem_snapshot.h` (módulo `mem_diag` del fork,
  añadido por `53d78e23a`; el pin no tiene `low_memory_device()` ni
  `log_memory_snapshot`). Sin ese módulo, ni el presupuesto (128 MiB en
  4 GB) ni el `release_idle_textures` del trim se pueden portar.
- Además, la puerta necesitaría llevar el flag por dos subsistemas que
  hoy no ven la config: `MemState` (para el zeroing por páginas en
  `alloc_inner`) y `VKTextureCache` (para el presupuesto; se fijaría en
  `VKState::late_init`, que sí recibe `cfg`).
- Plan de portado (proyecto propio, no de esta ronda): (1) portar el
  módulo `mem_snapshot` (2 archivos nuevos + alta en el `CMakeLists`
  de `util`); (2) re-contextar los 9 archivos del diff al pin; (3) añadir
  la clave `exp-texture-cache` a `CONFIG_INDIVIDUAL` y el flag a `MemState`
  (fijado al inicializar la memoria desde `cfg`) y a `VKTextureCache`
  (fijado en `late_init`); (4) entonces sí, interruptor OFF por defecto
  en Ajustes → Rendimiento → Parches experimentales.

## Por qué R3–R5 no se adaptan en esta ronda

Los tres dependen de infraestructura Double Buffer del fork que NO existe
en el pin (verificado por grep en el pin completo):

- `gpu_read_sync_only`, `post_sync_x0/y0/width/height`,
  `memcpy_to_guest`, `surface_sync_internal_write`: 0 ocurrencias en el
  pin. R3 los usa en sus 6 regiones de conflicto; R5 usa `post_sync_*`
  en `context.cpp`.
- Esa infraestructura viene de los commits grandes de Double Buffer
  (`092f3db` analizador USSE +138, `cef5370` pipeline_cache + vkutil),
  excluidos en la verificación (§8) por radio excesivo y riesgo ALTO.
- Mantener los dos caminos (OFF = actual, ON = fork) exigiría además
  duplicar la lógica de `check_for_surface` / `perform_surface_sync`,
  el corazón del render: una adaptación a ciegas sin compilador local
  es justo lo que la regla "no forzar" prohíbe.
- Plan de portado (proyecto propio): portar primero `092f3db`+`cef5370`
  como base (o reescribir el copy-back del mirror contra el pin), y solo
  después R3+R4+R5 juntos con la clave `exp-double-buffer`. Sin síntoma
  previo de texturas corruptas en Double Buffer (hoy: ninguno), no hay
  prisa: el cambio barato es G11-01 (salir de Double Buffer a Page Table).

## Por qué R6/R7 no se incluyen como parches normales

El pin refactorizó justo esa zona después de la base del fork:

- `wait_vblank` ya no recibe `KernelState &kernel` (pin:
  `display/functions.h:29`, 4 parámetros) y el hilo de vblank del pin
  ya no usa el vector `vblank_wait_infos` del fork: usa la cola
  `display.vblank_waiters` (`wake_if` / `wait(..., { target_vcount },
  Deadline::max())`). No hay dónde colgar `wake_half_vblank_waiters`
  sin reescribir el mecanismo.
- R7 depende de R6 (su diff es contra el árbol con R6 aplicado).
- Matiz: R7 NO es puramente fps-hack: retoca el periodo base de vblank
  de 16,666 ms a 16,683333 ms también con fps-hack OFF (0,1%).
- Plan: re-implementar el despertar a medio vblank contra la cola
  `vblank_waiters` del pin (no cherry-pick). Cuando exista, no necesita
  interruptor propio: solo actúa con fps-hack ON.

## Por qué P12 (Accurate Thread Scheduling) exige proyecto propio

- Intentado `git cherry-pick --no-commit ffa287b4 5d25698c` sobre el pin
  3e43bc80: el PRIMER commit ya conflicta en 12+ archivos, incluidos el
  corazón del cambio (`kernel/src/thread.cpp` +296, `sync_primitives.cpp`,
  `cpu.cpp`, `dynarmic_cpu.cpp`, `kernel.cpp`, `mem.cpp`) y render
  (`surface_cache.*`, `context.cpp`, `screen_renderer.h`). El segundo
  commit ni se intentó. Coherente con el informe: 35 commits upstream en
  `thread.cpp` y 39 en `sync_primitives.cpp` desde la base.
- El fork lo pone por default ON (`config.h`: `accurate-thread-scheduling:
  true`); aquí iría con interruptor OFF por defecto + clave + UI, pero
  SIN cherry-pick limpio no hay nada que gobernar: no se crea el
  interruptor. Proyecto propio = rama fork + matriz de pruebas en teléfono.

## Protocolos de puerta de teléfono (uno por parche)

Regla general: medir ANTES (build sin el parche, mismo juego, misma zona,
30 s) y DESPUÉS (mismo recorrido). Solo un parche por intento. Si el
después es peor o aparece cualquier síntoma nuevo, revertir entero.

### CPU-01 — HaltExecution en Yield/WFE

- Qué medir: uso de CPU por hilo host durante 60 s de juego en la zona
  donde se nota lento (`adb shell top -H -p <pid>` o `dumpsys cpuinfo`;
  buscar el hilo guest al ~100% de un núcleo). FPS medio y tirones
  (benchmark de 30 s del launcher). Temperatura/batería si se puede.
- Antes: hilo al ~100% (un núcleo saturado), FPS bajo con tirones.
- Después (éxito): ese hilo baja claramente (<50% sostenido) SIN que el
  juego se vuelva más lento ni se congele; FPS igual o mejor.
- Fracaso / revertir: juego más lento, congelamientos nuevos, hilos que
  no despiertan (un Yield que nunca vuelve = deadlock; el parche usa
  `HALT_YIELD`, NUNCA `HALT_RETURN`: este último MATARÍA el hilo guest,
  ver `run_guest()`/`run_loop()` en `kernel/src/thread.cpp`).
- Log a buscar: nada específico; la señal es el %CPU por hilo.
- Parámetro: `kExpYieldSleep` (100 us) en `dynarmic_cpu.cpp`.

### CPU-02 — Afinidad del hilo principal a núcleos grandes

- Qué medir ANTES: `adb shell cat /proc/<pid>/task/*/status | grep -i
  cpus_allowed` (o `taskset -pc <tid>`) para cada hilo guest, y
  `top -H` por hilo: ¿el hilo principal (el de mayor %CPU) cae en
  núcleos 0-5 (A55, pequeños) o 6-7 (A76, grandes)?
- Solo aplicar si la medida muestra el hilo principal en núcleos pequeños
  (o saltando entre pequeños y grandes). Si ya está en 6-7, NO hace falta.
- Después (éxito): el hilo principal queda fijo en 6-7 (`Cpus_allowed`
  = `c0` = núcleos 6-7) y el FPS sube o se estabiliza.
- Fracaso / revertir: FPS igual o peor (EAS ya lo hacía bien), o
  thermal throttling antes (dos núcleos grandes saturados calientan más).
- Supuesto a verificar: en el G99 los núcleos grandes son los de número
  más alto (6-7). Si `Cpus_allowed` muestra otra cosa, el parche asume
  mal y se corrige el mapeo, no se insiste.

### CPU-04 — Tope de caché JIT (64 MiB)

- Qué medir ANTES: nº de hilos guest de CoL y RAM real en el teléfono
  (`dumpsys meminfo <paquete>` durante el juego; RSS y nº de hilos).
- Después (éxito): RSS baja o se estabiliza en sesiones largas, sin
  tirones nuevos al cambiar de zona (recompilación en caliente).
- Fracaso / revertir: tirones nuevos al entrar en zonas ya visitadas
  (la caché se llena y recompila) o stutter al cargar: el tope es
  demasiado bajo; probar 96 MiB antes de descartar.
- Parámetro: `kExpJitCodeCacheMiB` (64) en `dynarmic_cpu.cpp`. Default
  verificado del Dynarmic fijado (86458a0b, `config.h:239`): 128 MiB por
  instancia (esta vez SÍ verificado contra el fork fijado, no solo
  upstream: corrección Codex 5 resuelta con evidencia).

### CPU-06 — Unsafe_UnfuseFMA (solo)

- Qué medir: FPS en la misma zona, 30 s, antes/después; y CORRECTITUD:
  jugar 10 min (combate, partículas, menús): buscar artefactos visuales,
  física rara, números de daño incorrectos, cuelgues.
- Éxito: FPS sube sin ningún artefacto. Fracaso: igual (revertir, no hay
  ganancia) o cualquier artefacto (revertir de inmediato).
- NUNCA añadir `Unsafe_IgnoreGlobalMonitor`: CoL es multihilo y rompería
  LDREX/STXR (Dynarmic: data races y deadlocks). Tampoco ReducedErrorFP
  ni InaccurateNaN en este experimento.

### CPU-07 — fastmem_exclusive_access

- Qué medir: FPS antes/después en zona con muchos enemigos/efectos
  (muchos bloqueos guest). Y ESTABILIDAD: 15 min de juego buscando
  cuelgues en cargas, guardados y cambios de zona.
- Éxito: FPS igual o mejor, sin cuelgues. Fracaso: cualquier cuelgue
  nuevo en un bloqueo (revertir entero; el camino por callback sigue
  siendo el default seguro).
- Nota: solo actúa cuando hay fastmem de verdad (sin page table, con
  cpu-opt): con el default actual (Double Buffer) es el camino activo.

### R2 / R3–R5 / R6–R7 / P12

Sin protocolo aplicable: no hay parche aplicable que probar. El protocolo
se escribe cuando exista el portado (ver "Plan de portado" arriba en cada
caso). Para R2, la matriz de verificación cuando se porte: título +
gameplay con texturas correctas (portada, personajes, brillos), T1/T2 sin
empeorar, sin OOM en 4 GB, varias sesiones, `dumpsys meminfo` + log
`Texture cache budget`. Para P12: protocolo antes/después + logcat
`HANG WATCHDOG` = 0 liberaciones, y nunca por default.
