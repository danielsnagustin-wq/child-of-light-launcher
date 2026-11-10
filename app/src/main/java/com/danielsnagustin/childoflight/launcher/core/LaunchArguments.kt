package com.danielsnagustin.childoflight.launcher.core

/**
 * argv de SDL_main para EmbeddedGameActivity (R4-P2-14).
 *
 * El oficial (Emulator.getArguments, DEX) devuelve el extra
 * AppStartParameters (String[]) si existe, y si no {"-r", titleId}.
 * Nada en nuestro flujo envía AppStartParameters hoy, así que es
 * solo robustez: un lanzador externo (o un Intent con ese extra)
 * puede pasar sus propios argumentos y se respetan tal cual.
 *
 * Función pura (sin Intent ni Activity) para poder testearla en JVM:
 * EmbeddedGameActivity.getArguments() solo lee el Intent y delega
 * aquí. Un extra presente pero vacío se trata como ausente: caer al
 * {"-r", titleId} es más útil que arrancar SDL_main sin argumentos.
 *
 * R5-A3-8: el extra solo se respeta tal cual si trae "-r". SDL_main
 * (main_android.cpp) recorre argv buscando "-r" y sin Title ID
 * devuelve -1 en silencio: un extra sin "-r" con Title ID conocido
 * se sustituye por {"-r", titleId}. Sin Title ID no hay nada con
 * qué sustituirlo y el extra se respeta tal cual (paridad con el
 * oficial). El aviso queda en el log del launcher: lo registra
 * EmbeddedGameActivity.getArguments(), el único llamador, porque
 * esta función es pura y android.util.Log es un stub en los tests
 * JVM (llamarlo aquí rompería los tests de esta función).
 *
 * R5-A1-17: un titleId en blanco (" ") se trata como ausente, igual
 * que uno vacío: nunca produce ["-r", " "].
 */
fun resolveLaunchArguments(appStartParameters: Array<String>?, titleId: String): Array<String> {
    if (appStartParameters != null && appStartParameters.isNotEmpty()) {
        if ("-r" in appStartParameters) return appStartParameters.copyOf()
        if (titleId.isNotBlank()) return arrayOf("-r", titleId)
        return appStartParameters.copyOf()
    }
    if (titleId.isBlank()) return emptyArray()
    return arrayOf("-r", titleId)
}
