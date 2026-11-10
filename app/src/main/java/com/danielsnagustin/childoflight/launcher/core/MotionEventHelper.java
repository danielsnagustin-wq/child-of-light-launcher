package com.danielsnagustin.childoflight.launcher.core;

import android.view.MotionEvent;

/**
 * Puente Java para crear un MotionEvent de un solo puntero con su
 * pointerId real.
 *
 * Por qué existe este archivo: la llamada directa desde Kotlin a
 * MotionEvent.obtain(downTime, eventTime, action, pointerCount,
 * PointerProperties[], PointerCoords[], ...) NO compila. El
 * compilador de Kotlin resuelve la sobrecarga de un solo puntero
 * (x/y Float) y rechaza las matrices con "Argument type mismatch",
 * incluso declaradas con su tipo explícito (runs #159 y #160).
 * En Java la misma llamada resuelve la sobrecarga de 12 argumentos
 * sin ambigüedad, así que el puente la hace aquí y Kotlin solo lo
 * invoca. El pointerId real del dedo se conserva: es lo que permite
 * distinguir en SDL el dedo de la zona vacía del resto de toques.
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
        return MotionEvent.obtain(
                downTime, eventTime, action, 1,
                new MotionEvent.PointerProperties[]{properties},
                new MotionEvent.PointerCoords[]{coords},
                0, 0, 1f, 1f, 0, 0);
    }
}
