# Nota GPLv2 — embeber Vita3K (Etapa 2)

Vita3K es **GPLv2** (`COPYING.txt` en la raíz de Vita3K/Vita3K,
dictada en gran parte por Unicorn). SDL3 es licencia **zlib**
(permisiva, sin copyleft). Al embeber el núcleo Vita3K dentro del
launcher, el trabajo combinado debe cumplir la GPLv2.

## Qué hay que hacer ANTES de distribuir el primer APK con núcleo

1. **Añadir `COPYING.txt`** (el texto GPLv2 de Vita3K) a la raíz
   del repo del launcher. Se copia de
   `https://github.com/Vita3K/Vita3K/blob/master/COPYING.txt`
   (o del clon fijado en CI).
2. **Atribución en el README**: que el launcher incluye el núcleo
   Vita3K (GPLv2), con enlace al repo de Vita3K y al commit fijado
   (`3e43bc803a04c775737a3474e0d54f3e10ca61bf`, ver
   `ETAPA2-ESTADO.md`), y que el código del núcleo + la capa
   adaptadora (`app/src/main/cpp/launcher/`) están en este repo.
3. **Código correspondiente disponible**: el repo ya es público y
   contiene la capa adaptadora; el árbol Vita3K que se compila es
   el commit fijado (el workflow lo clona de upstream). No
   distribuir nunca un APK con el núcleo sin que el código
   correspondiente esté publicado.
4. **Modificaciones**: si se parchea el árbol Vita3K (no previsto
   por ahora: el adaptador vive fuera del árbol), esas
   modificaciones también deben publicarse.

## Qué NO impone GPL

- El launcher (Kotlin/Compose) es código propio; la GPLv2 cubre el
  trabajo combinado al distribuir el APK con el núcleo. Publicar el
  código del núcleo + adaptador cumple la obligación; no obliga a
  cambiar la licencia del resto del launcher, pero el APK
  distribuido como conjunto es GPLv2.
- El juego, el firmware y las claves NUNCA se incluyen: el usuario
  aporta su copia legal. La GPL no cambia eso.

## Estado actual (2026-11-07)

No hay ningún APK con núcleo distribuido: la fundación está solo
en local y el núcleo no está embebido. Esta nota es preventiva:
cuando se active el workflow nativo y el primer APK con núcleo
salga de CI, los puntos 1-2 deben estar hechos ANTES de entregar
ese APK al usuario.
