# Parches del núcleo (ronda 0.2.70)

El workflow `build-native` clona el núcleo en el pin fijado (`VITA3K_PIN`,
`3e43bc80`) y aplica los parches de ESTE directorio con `git apply`
en el orden de la lista EXPLÍCITA del paso `Apply core patches (0.2.70)`
(no alfabético: el orden importa, ver el comentario de ese paso). Sin
ese paso, estos parches no se aplican y las claves `exp-*` que emite
el launcher se ignoran en silencio (el núcleo no las conoce y
`config.cpp` descarta las claves desconocidas: el juego sigue con el
comportamiento actual, sin romperse). Los 23 parches del top-level
son los de esa lista; `experimental/` nunca se aplica.

Reglas (ronda 11 / verificación Vita3K+ 2026-11-11):

- Un parche que no aplica limpio contra el pin se descarta, no se fuerza.
- Cada parche riesgoso va detrás de un interruptor OFF por defecto que
  consulta en tiempo de ejecución (clave `exp-*` en `config.yml`): con
  OFF, el comportamiento es el actual del pin, línea por línea.
- Nada de esto cambia el pin. Atribución GPL: los parches adaptan código
  de Vita3K+ (nckstwrt/Vita3K-Plus, GPL-2.0); la revisión de atribución
  pendiente (`docs/etapa2/GPL-NOTA.md`) debe añadir esa línea cuando se
  tome el primer parche. Estos archivos en el repo público ya publican
  las modificaciones.

## Parches activos (top-level)

La tabla sigue el orden EXACTO del workflow (1º–22º); el número de
posición es el que manda para los `git apply --check`.

| Archivo | Origen | Interruptor | Estado |
|---|---|---|---|
| `p3-jit-invalidate-on-module-reload.patch` | Vita3K+ `839735d6` (ronda 0.2.66): invalidación de la caché JIT al recargar un módulo | Ninguno (siempre activo) | 1º de 23 en el workflow. |
| `p5-eye-plane-mali-guard.patch` | Vita3K+ `d8aa6f2b` (ronda 0.2.66, fix #513): guarda del eye-plane en Mali (el recompilador nunca emite `gl_ClipDistance` en este pin) | Ninguno (siempre activo) | 2º de 23 en el workflow. |
| `hook1-present-counter.patch` | Propio (ronda 0.2.66, gancho nº 1 de instrumentación Fase P): contador de frames PRESENTADOS por el swapchain + timestamp del último (`presentKHR`) | Ninguno (instrumentación; la lee el puente JNI `nativeGetPresentedFrameCount` / `nativeGetLastPresentTimeNs`) | 3º de 23 en el workflow. |
| `g11-06-vsync-present-mode.patch` | Propio (ronda 0.2.66, G11-06): `v-sync` elige el present mode (ON = Fifo, OFF = Mailbox) | Ninguno (lee `v-sync` de la config) | 4º de 23 en el workflow. |
| `g11-03-deferred-unmap.patch` | Propio (ronda 0.2.66, G11-03): unmap diferido, sin `device.waitIdle()` en cada unmap | Ninguno (siempre activo) | 5º de 23 en el workflow. |
| `g11-09-pipeline-cache-save-on-exit.patch` | Propio (ronda 0.2.66, G11-09): la caché de pipelines se guarda al salir, no una vez por frame | Ninguno (siempre activo) | 6º de 23 en el workflow. |
| `c-01-texture-cache-vulkan.patch` | Propio (ronda 0.2.66, C-01): `texture-cache` con efecto real en Vulkan (OFF = re-subir cada textura, sin consultar la caché) | Ninguno (lee `texture-cache`) | 7º de 23 en el workflow. |
| `c-02-shader-cache-vulkan.patch` | Propio (ronda 0.2.66, C-02): `shader-cache` separa lectura y escritura de la caché en disco | Ninguno (lee `shader-cache`) | 8º de 23 en el workflow. |
| `aud-p2-1-audio-device-sample-frames.patch` | Propio (ronda 0.2.66, AUD-P2-1): clave nueva `audio-device-sample-frames` (default 512), copiada a `AudioState` antes de crear el adaptador | Ninguno (siempre activo; valor fijo 512) | 9º de 23 en el workflow. |
| `cpu-05-remove-cpu-pool-size.patch` | Propio (ronda 0.2.66, CPU-05): elimina `cpu-pool-size` de `config.h` | Ninguno (siempre activo) | 10º de 23 en el workflow. |
| `aud-p2-5-sdl-audio-drop.patch` | Propio (ronda 0.2.66, AUD-P2-5): búfer de descarte en SDL: descarta el audio acumulado al quedarse seco | Ninguno (siempre activo) | 11º de 23 en el workflow. |
| `aud-p2-3-ngs-system-init-error.patch` | Propio (ronda 0.2.66, AUD-P2-3): con `ngs-enable=false`, `sceNgsSystemInit` devuelve error en vez de OK sin sistema inicializado | Ninguno (siempre activo) | 12º de 23 en el workflow. |
| `aud-p2-6-audio-ms-log.patch` | Propio (ronda 0.2.66, AUD-P2-6): log periódico de ms de audio (contadores + `steady_clock`) | Ninguno (solo log) | 13º de 23 en el workflow. |
| `g11-05-shader-manifest-per-mask.patch` | Propio (ronda 0.2.66, G11-05): el manifiesto de shaders se versiona por máscara de features | Ninguno (siempre activo) | 14º de 23 en el workflow. |
| `r1-audio-sdl-exp.patch` | Vita3K+ `ff1515a` + `69757af` (audio SDL/Cubeb) + hunk de audio de `570cd24` | `exp-audio-sdl` (OFF) | Adaptado y rebasado sobre los 14 parches anteriores: `git apply --check` limpio DESPUÉS de ellos (no contra el pin solo: choca con aud-p2-1/aud-p2-5 en los mismos hunks). Iba ÚLTIMO en 0.2.66; desde 0.2.68 va antes de `load-p1-skip-shader-precompile.patch`. 15º de 23 en el workflow. |
| `aud2-p1-13-present-counter.patch` | Propio (ronda 0.2.70, contadores de presentación): cuenta frames presentados con fallback (superficie de presentación no RGBA8) frente a frames totales, con ratio en el log | Ninguno (contadores + log) | 16º de 23 en el workflow; `git apply --check` limpio DESPUÉS de los 15 parches anteriores. |
| `aud2-p2-1-batch-poll-sleep.patch` | Propio (ronda 0.2.70): sleep de 1 ms en el bucle de batches cuando no hay batch listo ni espera pendiente | Ninguno (siempre activo) | 17º de 23 en el workflow; `git apply --check` limpio DESPUÉS de los 16 parches anteriores. |
| `aud2-p2-3-staging-counter.patch` | Propio (ronda 0.2.70): contador + log cuando los 16 staging buffers se agotan dentro de una escena (submit parcial + espera a GPU a mitad de escena) | Ninguno (contadores + log) | 18º de 23 en el workflow; `git apply --check` limpio DESPUÉS de los 17 parches anteriores. |
| `load-p1-skip-shader-precompile.patch` | Propio (ronda 0.2.68, carga): clave nueva `skip-shader-precompile` + puerta en `prepare_game_launch_overlay` (app.cpp:281) | `skip-shader-precompile` (OFF por defecto; ON en los presets Mali/G99) | Rebasado sobre los 18 parches anteriores: `git apply --check` limpio DESPUÉS de ellos (sus hunks en config.h, state.h y settings.cpp van junto a los de aud-p2-1/r1). Va 19º de 23 en la lista del workflow (NO último: tras él van `aud2-p1-12-cubeb-timeout`, `aud2-p2-6-steady-clock`, `aud2-render-surface-format` y `aud3-mask-bit-disable`). |
| `aud2-p1-12-cubeb-timeout.patch` | Propio (ronda 0.2.70, P1-12): espera acotada en Cubeb `audio_output` (2x duración del búfer + descarte del búfer entrante) y `CUBEB_STATE_ERROR` marca el puerto parado | Ninguno (siempre activo, camino Cubeb) | `git apply --check` limpio DESPUÉS de los 19 parches del workflow (tras `load-p1`). 20º de 23. Sin claves nuevas. |
| `aud2-p2-6-steady-clock.patch` | Propio (ronda 0.2.70, 5-P2-6): pacing OFF con `steady_clock` en vez de `system_clock` | Ninguno (siempre activo) | `git apply --check` limpio DESPUÉS de los 19 parches del workflow (tras `load-p1`, tras `aud2-p1-12`). 21º de 23. Sin claves nuevas. |
| `aud2-render-surface-format.patch` | Propio (ronda 0.2.70, 9-P2-4/9-P2-3): fallo explícito si no hay formatos de superficie (sin swapchain posible) y formato sRGB como fallback | Ninguno (siempre activo) | `git apply --check` limpio DESPUÉS de los 19 parches del workflow (tras `load-p1`). 22º de 23 (ya no último: tras él va `aud3-mask-bit-disable.patch`). Sin claves nuevas. |
| `aud3-mask-bit-disable.patch` | Propio (tercera pasada, N-1): honra `disable-mask-bit` en Vulkan (`VKState::late_init`, espejo de OpenGL `gl/renderer.cpp:242`) | `disable-mask-bit` (OFF por defecto; interruptor por juego) | 23º de 23, ÚLTIMO. `git apply --check` limpio DESPUÉS de los 22 parches del workflow. Sin claves nuevas en el pin (la clave ya existe en `config.h:139`). |

### r1-audio-sdl-exp.patch — Audio SDL experimental (R1)

Qué cambia con ON: se salta el pacing del 50% de `AudioState::audio_output`
(ese sleep es el mecanismo de AUD-P1-1), el umbral SDL pasa a
`max(3x búfer, 40 ms)`, la espera se calibra contra lo que falta por sonar
(`play_end_us`, tope 2x la duración del búfer) con despertar por pull y el
anillo Cubeb crece para 40 ms de cola mínima. Con OFF, cada función conserva su código original
verbatim (el parche guarda ambas ramas).

Qué se dejó FUERA del fork a propósito (verificado contra el pin):

- El hunk de `spirv_recompiler.h` (`CURRENT_VERSION` 15 → 18): no es audio,
  es un bump de versión de shaders que invalidaría TODA la caché de shaders.
- Los hunks de Cubeb "silencio al quedarse seco" e `#include <cstring>`:
  upstream ya los añadió al pin después de la base del fork (el pin ya los
  tiene en `cubeb_audio.cpp:74` y `:21`).
- El hunk `"1024"` → `"512"` de `570cd24`: el pin ya tiene `"512"` (AUD-P2-1).
  El hint `ANDROID_LOW_LATENCY_AUDIO "1"` del mismo commit NO se toma desde
  la ronda 0.2.70 (5-P2-1): ya es el default de SDL desde 3.2.0 (el pin fija
  SDL 3.4.16) y no cambiaba nada.
- El lado joysticks de `570cd24` (`SDL_EVENT_JOYSTICK_ADDED` +
  `map_android_half_controllers`, ~60 líneas en `ctrl.cpp`): NO se toma.
  Podría duplicar un mando; el overlay usa su propio camino de entrada.

Cómo consulta el interruptor: la clave `exp-audio-sdl` se añade a
`CONFIG_INDIVIDUAL` (`config.h`): `config.cpp` la parsea sola y aparece
como `cfg.exp_audio_sdl`. `app_init.cpp` (`late_init`) la copia a
`AudioState::exp_audio_sdl` ANTES de `audio.init()` (el hint de baja
latencia se lee ahí); el resto del código la lee de `AudioState` (los
adaptadores la tienen como `state`). Cambiar el interruptor exige
relanzar (como cambiar de backend). Los `open_port` null-guards del fork
van SIN puerta: solo evitan un crash con backend muerto (no cambian el
comportamiento cuando todo está bien).

Prueba en teléfono (obligatoria antes de darlo por bueno): 5 min de juego
con SDL (default) y el interruptor ON: música/SFX sin chasquidos nuevos ni
retraso, menús y escenas con partículas; comparar con OFF en la misma zona.
Si cruje o se retrasa, OFF y se reporta (el parche se revierte entero).

### load-p1-skip-shader-precompile.patch — Carga rápida (ronda 0.2.68)

Qué cambia con ON: `prepare_game_launch_overlay` (app.cpp:281) no mete los
shaders conocidos en la cola de precompilado al arrancar. El precompilado es
serial en el hilo de render (pin batch.cpp:189-226, un programa detrás de
otro, sin frames presentados mientras tanto): en el teléfono del usuario
costaba T2 92-147 s (reporte 0.2.67) y, con el manifiesto ya grande tras
7,5 min de juego, el juego "ni siquiera entra" (regresión frente a 0.2.62).
Con la puerta, se espera que el juego entre en segundos (pendiente medir
T2 en el teléfono) y los shaders se compilan bajo demanda con la
compilación asíncrona de pipelines (3 hilos, ya activa):
sin bloqueo, solo objetos que aparecen unos frames más tarde. Con OFF, el
comportamiento es el upstream verbatim (precompila si `shader-cache` está
ON). La clave se añade a `CONFIG_INDIVIDUAL` (config.h) + campo en
`CurrentConfig` (state.h) + las dos copias en settings.cpp (regla de las
tres sedes de la ronda 0.2.66, run #515): `load_custom_config` y
`save_custom_config` la cubren solas por ser macro-generadas.

Por qué NO es un parche de paralelización: paralelizar el bucle de
batch.cpp exigiría arreglar las carreras que hoy no existen en serial
(`programs_count_pre_compiled` no atómico, `precompile_progress` int
plano, lectura del mapa `shaders` sin mutex en el camino `search_first`
de pipeline_cache.cpp) y tocar el renderizador GL además del Vulkan.
La puerta es el arreglo de menor riesgo con el mayor efecto en el
teléfono; la paralelización queda como trabajo futuro para el camino
con precompilado ON.

Prueba en teléfono (obligatoria antes de darlo por bueno): con el preset
G99 (carga rápida ON), JUGAR → la pantalla de carga del juego aparece en
segundos (T2 < 30 s) y el juego entra; jugar 5 min y comprobar que no hay
objetos invisibles permanentes ni tirones nuevos frente a OFF en la misma
zona. Si algo se ve mal, OFF y se reporta.
