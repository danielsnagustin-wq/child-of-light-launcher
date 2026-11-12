# Driver Turnip incluido (Adreno) — hueco para el binario

Esta carpeta es donde iría el driver Turnip incluido "de raíz" en la
APK. **Ahora mismo está vacía a propósito: NO hay ningún binario.**
Sin binario, el launcher sigue usando el driver del sistema y ningún
flujo cambia (la instalación automática es no-op).

## Archivo esperado

```
Turnip_v26.0.0_R8_Gmem.zip
```

- Fuente: release **v26.0.0 Revisión 8** (tag v26.0.0-rc08) de
  `K11MCH1/AdrenoToolsDrivers` en GitHub (publicada 2026-01-08,
  Mesa 26.0.0-devel, commit base `e9a83d530cce`, Vulkan 1.4.335).
- Tamaño: 3.478.156 bytes. SHA-256 (verificado 2026-11-11):
  `b60bffe2844a271cd293011de307fe06d28549bd5b93d03749b7c9f39af4114b`.
- Licencia del código: **MIT (Mesa)**. El zip es una compilación
  comunitaria de ese código; no es un binario propietario.
- Formato VERIFICADO (2026-11-11): en la raíz del zip, `meta.json`
  (305 bytes, libraryName `vulkan.ad07xx.so`, "Mesa Turnip driver
  v26.0.0 - R8 GMEM", driverVersion "Vulkan 1.4.335", minApi 27) +
  `vulkan.ad07xx.so` (17.159.433 bytes).
  **No descomprimir el zip**: se empaqueta tal cual en assets.

## Antes de poner el binario

1. Re-verificar cuál es la release vigente de K11MCH1 (la R8 es de
   enero de 2026; otros constructores como MrPurple publican
   revisiones más nuevas). NO se cambia de constructor sin prueba en
   teléfono. Si se elige otra revisión, hay que actualizar
   `ASSET_PATH` / `EXPECTED_FILE_NAME` / `EXPECTED_SHA256` en
   `BundledTurnipDriver.kt` y este README.
2. El SHA-256 ya está rellenado en `BundledTurnipDriver.kt`
   (verificado 2026-11-11 contra el asset de la release v26.0.0-rc08).
3. Layout real del zip ya confirmado (ver arriba): la validación de
   `BundledTurnipDriver.validatePackage` coincide con él.
4. Comprobar el tamaño del APK: el zip suma ~3,5 MB al APK.

## Lo que NO se pone aquí

- Drivers **Qualcomm** (v615, v837, v840…) del mismo repo: son
  binarios propietarios extraídos de otros dispositivos, no código
  Mesa, y no se pueden redistribuir como driver libre.
- Ningún driver de Mali: no existe un .zip equivalente para Mali
  (ver `docs/gpu-drivers.md`). Turnip es solo Adreno.
- El zip incluido solo se auto-instala en Adreno 6xx y 7xx altos
  (ver `BundledTurnipDriver.selectVariant`); en 8xx, 710/720, A32
  y Mali no se instala nada automáticamente.

## Nota de build

El workflow de CI copia los assets del núcleo a
`app/src/main/assets/` (carpetas `data/` y `shaders-builtin/`) pero
no borra esta carpeta `drivers/`, así que el zip incluido sobrevive
al build nativo. El job `build` (sin núcleo) también empaqueta
assets, así que el zip estaría en ambas APKs.
