package org.vita3k.emulator.data

/**
 * Un trofeo, tal como lo construye el JNI de upstream
 * (vita3k/android/jni/native_trophies.cpp).
 *
 * Campos EXACTOS que exige el JNI: constructor sin argumentos y estos
 * nombres y firmas (verificado en native_trophies.cpp:105-113:
 * npComId/name/detail/iconPath String, trophyId/grade int,
 * hidden/earned boolean, timestampSec long). El JNI crea el objeto
 * con el constructor vacío y luego escribe cada campo: por eso son
 * var con valor por defecto y no val del constructor.
 *
 * - trophyId es el id del TRP (000..N-1): NO es el número local
 *   1..20 del launcher. El mapeo a número local se hace por el nombre
 *   en inglés exacto (ver data/TrophySync.kt), nunca por trophyId.
 * - grade es el grado SceNpTrophyGrade de upstream: solo se guarda,
 *   no se usa para el mapeo ni para mostrar nada.
 * - timestampSec son segundos epoch del desbloqueo (0 si no hay
 *   marca de tiempo); el mapeo lo pasa a millis.
 *
 * Clase interna, sin UI.
 */
class NativeTrophy {
    var npComId: String = ""
    var trophyId: Int = 0
    var name: String = ""
    var detail: String = ""
    var grade: Int = 0
    var hidden: Boolean = false
    var earned: Boolean = false
    var timestampSec: Long = 0L
    var iconPath: String = ""
}
