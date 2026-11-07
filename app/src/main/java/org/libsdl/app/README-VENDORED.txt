<!--
  Pegamento Java de SDL3 para Android, vendorizado (Etapa 2).

  Origen: libsdl-org/SDL, commit fa2c02bb6e21974a89ea9824bc53c9932abe5f9c
  (SDL 3.4.16). Es EXACTAMENTE el commit fijado como submódulo
  external/sdl en Vita3K/Vita3K (master 3e43bc803a04c775737a3474e0d54f3e10ca61bf,
  verificado 2026-11-07): el Java y el .so nativo de SDL deben ser de la
  misma versión, o la interfaz Java<->nativa puede no coincidir.

  Licencia: zlib (ver SDL-LICENSE.txt junto a este archivo). SDL es
  software libre con licencia permisiva; no impone GPL al launcher.
  (La GPLv2 viene de Vita3K, no de SDL — ver docs/etapa2/GPL-NOTA.md.)

  NO EDITAR estos archivos a mano: si hay que actualizar SDL, se
  vuelve a copiar desde el commit fijado por Vita3K y se actualiza
  este archivo. La única clase que el launcher usa directamente es
  SDLActivity (ver core/EmbeddedGameActivity.kt, que la extiende).

  Por qué se vendoriza en vez de usar el submódulo: el launcher no
  puede traer submódulos con su pipeline de push por API (ver
  docs/etapa2/ETAPA2-ESTADO.md). El .so nativo de SDL3 lo aporta el
  build del núcleo Vita3K en CI (libvita3k.so lo enlaza); estos
  archivos son solo el lado Java.
-->
