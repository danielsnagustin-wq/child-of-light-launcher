# Parches del núcleo (ronda 0.2.66)

El workflow `build-native` clona el núcleo en el pin fijado (`VITA3K_PIN`,
`3e43bc80`) y aplica los parches de ESTE directorio con `git apply`
en el orden de la lista EXPLÍCITA del paso `Apply core patches (0.2.66)`
(no alfabético: el orden importa, ver el comentario de ese paso). Sin
ese paso, estos parches no se aplican y las claves `exp-*` que emite
el launcher se ignoran en silencio (el núcleo no las conoce y
`config.cpp` descarta las claves desconocidas: el juego sigue con el
comportamiento actual, sin romperse). Los 16 parches del top-level
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

| Archivo | Origen | Interruptor | Estado |
|---|---|---|---|
| `r1-audio-sdl-exp.patch` | Vita3K+ `ff1515a` + `69757af` (audio SDL/Cubeb) + hunk de audio de `570cd24` | `exp-audio-sdl` (OFF) | Adaptado y rebasado sobre los 14 parches F2: `git apply --check` limpio DESPUÉS de ellos (no contra el pin solo: choca con aud-p2-1/aud-p2-5 en los mismos hunks). Iba ÚLTIMO en 0.2.66; desde 0.2.68 va antes de `load-p1-skip-shader-precompile.patch`. |
| `load-p1-skip-shader-precompile.patch` | Propio (ronda 0.2.68, carga): clave nueva `skip-shader-precompile` + puerta en `prepare_game_launch_overlay` (app.cpp:281) | `skip-shader-precompile` (OFF por defecto; ON en los presets Mali/G99) | Rebasado sobre los 15 parches: `git apply --check` limpio DESPUÉS de ellos (sus hunks en config.h, state.h y settings.cpp van junto a los de aud-p2-1/r1). Va ÚLTIMO en la lista del workflow. |

### r1-audio-sdl-exp.patch — Audio SDL experimental (R1)

Qué cambia con ON: se salta el pacing del 50% de `AudioState::audio_output`
(ese sleep es el mecanismo de AUD-P1-1), el umbral SDL pasa a
`max(3x búfer, 40 ms)`, la espera se calibra contra lo que falta por sonar
(`play_end_us`, tope 2x la duración del búfer) con despertar por pull, el
anillo Cubeb crece para 40 ms de cola mínima, y en Android se pide audio
de baja latencia. Con OFF, cada función conserva su código original
verbatim (el parche guarda ambas ramas).

Qué se dejó FUERA del fork a propósito (verificado contra el pin):

- El hunk de `spirv_recompiler.h` (`CURRENT_VERSION` 15 → 18): no es audio,
  es un bump de versión de shaders que invalidaría TODA la caché de shaders.
- Los hunks de Cubeb "silencio al quedarse seco" e `#include <cstring>`:
  upstream ya los añadió al pin después de la base del fork (el pin ya los
  tiene en `cubeb_audio.cpp:74` y `:21`).
- El hunk `"1024"` → `"512"` de `570cd24`: el pin ya tiene `"512"` (AUD-P2-1).
  Solo se toma el hint `ANDROID_LOW_LATENCY_AUDIO "1"`, dentro del mismo
  interruptor.
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
