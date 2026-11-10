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
 */
fun resolveLaunchArguments(appStartParameters: Array<String>?, titleId: String): Array<String> {
    if (appStartParameters != null && appStartParameters.isNotEmpty()) {
        return appStartParameters.copyOf()
    }
    if (titleId.isEmpty()) return emptyArray()
    return arrayOf("-r", titleId)
}
