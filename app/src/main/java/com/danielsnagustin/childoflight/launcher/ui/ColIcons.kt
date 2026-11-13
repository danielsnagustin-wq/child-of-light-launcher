package com.danielsnagustin.childoflight.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Iconos vectoriales del launcher (Fase 2, Ola 1): dibujados con
 * Canvas, sin emojis y sin texto, para que todos los iconos tengan
 * el mismo trazo y el mismo azul hielo. Sustituyen a los glifos de texto
 * ("▣", "🏆", "▶", "⬇") que se usaban antes en la Home.
 */

@Composable
fun PlayIcon(modifier: Modifier = Modifier, tint: Color = ColAccent) {
    Canvas(modifier = modifier.size(24.dp)) {
        val path = Path().apply {
            moveTo(size.width * 0.32f, size.height * 0.2f)
            lineTo(size.width * 0.78f, size.height * 0.5f)
            lineTo(size.width * 0.32f, size.height * 0.8f)
            close()
        }
        drawPath(path, color = tint)
    }
}

@Composable
fun TrophyIcon(modifier: Modifier = Modifier, tint: Color = ColAccent) {
    Canvas(modifier = modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 2.dp.toPx()
        drawArc(
            color = tint,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.28f, h * 0.14f),
            size = androidx.compose.ui.geometry.Size(w * 0.44f, h * 0.44f),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.28f, h * 0.36f),
            end = Offset(w * 0.16f, h * 0.36f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.72f, h * 0.36f),
            end = Offset(w * 0.84f, h * 0.36f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.5f, h * 0.58f),
            end = Offset(w * 0.5f, h * 0.72f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.32f, h * 0.82f),
            end = Offset(w * 0.68f, h * 0.82f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/** Icono de "ajustes" (dos deslizadores): líneas grises, pomos azul hielo. Antes vivía en HomeScreen. */
@Composable
fun SlidersIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 2.5.dp.toPx()
        val knob = 4.5.dp.toPx()
        drawLine(
            color = ColMutedText,
            start = Offset(0f, h * 0.32f),
            end = Offset(w, h * 0.32f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = ColMutedText,
            start = Offset(0f, h * 0.68f),
            end = Offset(w, h * 0.68f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawCircle(color = ColAccent, radius = knob, center = Offset(w * 0.32f, h * 0.32f))
        drawCircle(color = ColAccent, radius = knob, center = Offset(w * 0.68f, h * 0.68f))
    }
}
