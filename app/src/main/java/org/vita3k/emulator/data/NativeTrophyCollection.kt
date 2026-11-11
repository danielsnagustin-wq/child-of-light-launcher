package org.vita3k.emulator.data

/**
 * Colección de trofeos de un juego, tal como la construye el JNI de
 * upstream (vita3k/android/jni/native_trophies.cpp).
 *
 * Campos EXACTOS que exige el JNI: constructor sin argumentos y estos
 * nombres y firmas (verificado en native_trophies.cpp:85-95:
 * npComId/title/iconPath String, totalCount/unlockedCount/
 * platinumUnlockedCount/goldUnlockedCount/silverUnlockedCount/
 * bronzeUnlockedCount/progressPercent int, trophies NativeTrophy[]).
 * El JNI crea el objeto con el constructor vacío y luego escribe cada
 * campo con SetObjectField/SetIntField: por eso son var con valor por
 * defecto y no val del constructor.
 *
 * Clase interna, sin UI: el núcleo solo se nombra aquí porque el JNI
 * exige este paquete y esta clase; nada de esto se ve en la app.
 */
class NativeTrophyCollection {
    var npComId: String = ""
    var title: String = ""
    var iconPath: String = ""
    var totalCount: Int = 0
    var unlockedCount: Int = 0
    var platinumUnlockedCount: Int = 0
    var goldUnlockedCount: Int = 0
    var silverUnlockedCount: Int = 0
    var bronzeUnlockedCount: Int = 0
    var progressPercent: Int = 0
    var trophies: Array<NativeTrophy> = emptyArray()
}
