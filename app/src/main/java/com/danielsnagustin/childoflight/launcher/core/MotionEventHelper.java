package com.danielsnagustin.childoflight.launcher.core;

import android.view.InputDevice;
import android.view.MotionEvent;

/**
 * Puente Java para crear un MotionEvent de un solo puntero con su
 * pointerId real.
 *
 * Por qué existe este archivo: la llamada directa desde Kotlin a
 * MotionEvent.obtain(...) con matrices NO compila. El compilador
 * de Kotlin resuelve la sobrecarga de un solo puntero (x/y Float)
 * y rechaza las matrices con "Argument type mismatch", incluso
 * declaradas con su tipo explícito (runs #159 y #160).
 *
 * Por qué la llamada usa 14 argumentos y no 12: la sobrecarga de
 * 12 args (sin source ni flags) YA NO EXISTE en el SDK actual.
 * Verificado en el android.jar de API 34 (tabla de métodos: solo
 * hay obtains de 14 y 16 args con matrices) y en la documentación
 * oficial (la forma vigente es la de 14 args con source y flags,
 * recomendada como reemplazo desde API 15). Por eso el primer
 * puente, con 12 args, falló también en Java con "PointerProperties[]
 * cannot be converted to float" (run #161). La forma de 14 args
 * conserva el pointerId real del dedo: es lo que permite distinguir
 * en SDL el dedo de la zona vacía del resto de toques.
 */
public final class MotionEventHelper {
    private MotionEventHelper() {
    }

    public static MotionEvent obtainSinglePointer(
            long downTime,
            long eventTime,
            int action,
            int pointerId,
            float x,
            float y) {
        MotionEvent.PointerProperties properties = new MotionEvent.PointerProperties();
        properties.id = pointerId;
        properties.toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.x = x;
        coords.y = y;
        coords.pressure = 1f;
        coords.size = 1f;
        // Forma vigente de 14 args: ... deviceId, edgeFlags, source, flags.
        // source = SOURCE_TOUCHSCREEN: el evento sintético es un toque
        // de dedo en la pantalla, igual que el toque real que reemplaza.
        return MotionEvent.obtain(
                downTime, eventTime, action, 1,
                new MotionEvent.PointerProperties[]{properties},
                new MotionEvent.PointerCoords[]{coords},
                0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0);
    }
}
