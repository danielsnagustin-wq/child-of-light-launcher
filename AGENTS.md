# AGENTS.md — Child of Light Launcher

> Lee este archivo antes de tocar código. Está en español simple, a propósito.
> Si algo de aquí contradice al README, manda lo de aquí.

## Qué es esta app

Launcher de **un solo juego** para Android: Child of Light (versión PS Vita).
Tocas el icono y juegas. Sin lista de juegos, sin biblioteca de emulador.

- El usuario pone **su propia copia legal** del juego (versión europea
  **PCSB00598**), sus DLCs y su firmware. El APK sale vacío.
- El launcher importa, instala y guarda esa copia en el almacenamiento
  privado de la app, y lanza el juego con el núcleo emulador embebido.
- Ajustes completos (GPU, CPU, audio, rendimiento, controles y HUD,
  almacenamiento, diagnóstico) en 9 secciones con buscador. El
  firmware no es una sección de Ajustes: vive en Importar, junto al
  juego y los DLCs (hay un enlace "Firmware → Importar" en
  Almacenamiento, donde vive el estado de archivos).
- 20 logros locales. Varias sesiones con copias de guardado y
  auto-respaldo.

El usuario **no tiene PC**: todo se compila solo con GitHub Actions.
CI en verde **no** significa probado en el teléfono. Decirlo siempre así.
El objetivo es el teléfono (MediaTek Helio G99 / Mali-G57 MC2), donde el
juego corre muy bien.

## Reglas que no se rompen

1. **Nunca mencionar Vita3K en nada visible de la app.** Ni pantallas,
   ni mensajes, ni ajustes, ni reportes. Solo "Child of Light".
2. **El nombre del usuario no aparece en ninguna parte de la app.**
   Solo "Child of Light". Otra persona puede usar la app.
3. **Nada de amarillo ni dorado en ninguna parte.** Identidad: azul
   hielo #8CCBEB, azul lavanda #6578B8, lavanda #A99ACD, turquesa
   #75D6CE, plata #DCE8F5, fondo AMOLED #10131D.
4. **El firmware (PSVUPDAT.PUP) nunca entra al repo, al APK ni al
   chat.** Lo aporta el usuario y se importa solo en su teléfono.
   Tampoco entran el juego, los DLCs, las licencias ni claves.
5. **Cada ajuste hace algo real o se quita.** Nada de adorno. Verificar
   cada opción contra el núcleo real: no inventar nombres ni valores de
   `config.yml`.
6. **Nunca entregar un APK como "listo" con errores pendientes.**
   Después de cada run: causa exacta → arreglo → nuevo run, en bucle,
   error por error. Reportar por arreglo verificado (commit + run +
   artefactos). Primero que sea totalmente jugable en el teléfono, y
   solo entonces pensar en release.
7. **Leer el código antes de tocarlo.** No quitar funciones sin
   consultar. No sustituir implementaciones reales por simulaciones.
8. **Los logros solo se ganan jugando.** La fuente de verdad es el
   núcleo (su TROPHY.TRP). Nunca marcar logros a mano ni inventar
   desbloqueos. Si un logro no se puede ganar en el emulador, queda
   bloqueado.
9. **No mencionar el modelo del teléfono.** Solo "teléfono (MediaTek
   Helio G99 / Mali-G57 MC2)".

## Detalles que ya costaron horas (no repetir los errores)

- El núcleo real es **`libVita3K.so`** (K mayúscula). SDL3 va estática
  dentro; no hay `libSDL3.so` aparte.
- En `config.yml`, **`pref-path` es la ruta del vita_fs**: debe ser
  `<vitaRoot>/vita`, no `<vitaRoot>`. Si apunta mal, el juego da
  error -3 aunque esté instalado.
- DLCs: se activan con `psn-signed-in: 1` en `config.yml`. Las carpetas
  de DLC las nombra el núcleo con el Content ID sin sus primeros 20
  caracteres (nombre corto tras `_00-`): comparar por esa clave, no
  por el nombre del archivo `.pkg`.
- Modo 60 FPS: opcional, **OFF por defecto** (`fps-hack` del upstream).
  Puede dar 60 reales, doble velocidad o nada: solo lo decide el teléfono.
- La limpieza nunca borra partidas guardadas. Confirmar antes de
  eliminar. Validar antes de escribir `config.yml`.

## Cómo se compila (GitHub Actions, `.github/workflows/android.yml`)

Dos jobs:

- `build`: tests + APK sin núcleo.
  `gradle :app:testDebugUnitTest --stacktrace`,
  `gradle :app:assembleDebug --stacktrace`.
- `build-native`: APK **con núcleo** (NDK 29, vcpkg, Vita3K en commit
  fijado). Es el artefacto instalable: **~64 MB** (`native`).
  El artefacto pequeño (`apks`, ~20 MB) **no** tiene núcleo: no instalarlo.
- JDK 17 (temurin), Gradle 8.7. `minSdk 28, targetSdk 35, compileSdk 35`.
- Artefactos: `child-of-light-launcher-apks` (sin núcleo) y el nativo.
  Se instala el `app-debug.apk` del artefacto nativo, del run más
  reciente en verde.
- El APK release sale sin firmar: no distribuir como release hasta
  tener keystore en GitHub Secrets y revisada la atribución GPL.

## Subir cambios

- Los archivos de workflow (`.github/workflows/*`) y los binarios (PNG)
  **solo se suben por el navegador** (github.com web UI). La API de
  `push_files` da 403 con ellos.
- `push_files` exige `files` como array de `{path, content}` y `path`
  siempre relativo al repo, nunca la ruta local.
- Si un job nativo falla por **504 descargando dependencias de GitHub**,
  es infraestructura, no código: usar "Re-run failed jobs", sin nuevo
  commit.
- Verificar siempre el run del commit exacto pusheado antes de reportar.

## Referencias para no inventar

- Cómo resuelve cada problema el núcleo original: **Vita3K/Vita3K** en
  GitHub, y copia local fijada en `~/workspace/vita3k-pin/` (leer ahí
  antes de suponer cómo funciona algo del núcleo).
- Documentación al día de librerías: **Context7** (ver
  `~/workspace/custom-skills/context7-docs/SKILL.md` en la computadora
  de la asistente; sin clave, por API REST).
- Ejemplos oficiales de Google, clonados en local como consulta:
  `~/workspace/references/nowinandroid`,
  `~/workspace/references/compose-samples`,
  `~/workspace/references/architecture-samples`.
- Specs y checklist del proyecto: `.specify/` (fuera del repo, no se
  pushea). Flujo: constitución → spec → plan → tareas → implementar.
