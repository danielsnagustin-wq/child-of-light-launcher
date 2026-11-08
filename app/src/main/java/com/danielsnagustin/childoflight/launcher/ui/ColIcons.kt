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
fun GamepadIcon(modifier: Modifier = Modifier, tint: Color = ColAccent) {
    Canvas(modifier = modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 2.dp.toPx()
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.08f, h * 0.3f),
            size = androidx.compose.ui.geometry.Size(w * 0.84f, h * 0.42f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.18f, h * 0.18f),
            style = Stroke(width = stroke),
        )
        drawCircle(color = tint, radius = 2.2.dp.toPx(), center = Offset(w * 0.32f, h * 0.47f))
        drawCircle(color = tint, radius = 2.2.dp.toPx(), center = Offset(w * 0.68f, h * 0.47f))
        drawLine(
            color = tint,
            start = Offset(w * 0.2f, h * 0.47f),
            end = Offset(w * 0.26f, h * 0.47f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.68f, h * 0.4f),
            end = Offset(w * 0.68f, h * 0.54f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
fun DlcIcon(modifier: Modifier = Modifier, tint: Color = ColAccent) {
    Canvas(modifier = modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 2.dp.toPx()
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.14f, h * 0.2f),
            size = androidx.compose.ui.geometry.Size(w * 0.72f, h * 0.6f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx(), 3.dp.toPx()),
            style = Stroke(width = stroke),
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.5f, h * 0.36f),
            end = Offset(w * 0.5f, h * 0.64f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.36f, h * 0.5f),
            end = Offset(w * 0.64f, h * 0.5f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
fun UpdateIcon(modifier: Modifier = Modifier, tint: Color = ColAccent) {
    Canvas(modifier = modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 2.dp.toPx()
        drawLine(
            color = tint,
            start = Offset(w * 0.5f, h * 0.18f),
            end = Offset(w * 0.5f, h * 0.68f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        val head = Path().apply {
            moveTo(w * 0.32f, h * 0.5f)
            lineTo(w * 0.5f, h * 0.7f)
            lineTo(w * 0.68f, h * 0.5f)
        }
        drawPath(head, color = tint, style = Stroke(width = stroke, cap = StrokeCap.Round))
        drawLine(
            color = tint,
            start = Offset(w * 0.2f, h * 0.82f),
            end = Offset(w * 0.8f, h * 0.82f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
fun FirmwareIcon(modifier: Modifier = Modifier, tint: Color = ColAccent) {
    Canvas(modifier = modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 2.dp.toPx()
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.3f, h * 0.3f),
            size = androidx.compose.ui.geometry.Size(w * 0.4f, h * 0.4f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx(), 2.dp.toPx()),
            style = Stroke(width = stroke),
        )
        drawCircle(color = tint, radius = 2.dp.toPx(), center = Offset(w * 0.5f, h * 0.5f))
        for (i in 0..2) {
            val x = w * (0.32f + 0.18f * i)
            drawLine(color = tint, start = Offset(x, h * 0.14f), end = Offset(x, h * 0.3f), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(color = tint, start = Offset(x, h * 0.7f), end = Offset(x, h * 0.86f), strokeWidth = stroke, cap = StrokeCap.Round)
        }
        for (i in 0..1) {
            val y = h * (0.38f + 0.24f * i)
            drawLine(color = tint, start = Offset(w * 0.14f, y), end = Offset(w * 0.3f, y), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(color = tint, start = Offset(w * 0.7f, y), end = Offset(w * 0.86f, y), strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }
}

@Composable
fun PerformanceIcon(modifier: Modifier = Modifier, tint: Color = ColAccent) {
    Canvas(modifier = modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 2.dp.toPx()
        drawArc(
            color = tint,
            startAngle = 135f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset(w * 0.12f, h * 0.12f),
            size = androidx.compose.ui.geometry.Size(w * 0.76f, h * 0.76f),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.5f, h * 0.5f),
            end = Offset(w * 0.72f, h * 0.32f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawCircle(color = tint, radius = 2.2.dp.toPx(), center = Offset(w * 0.5f, h * 0.5f))
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

@Composable
fun FolderIcon(modifier: Modifier = Modifier, tint: Color = ColAccent) {
    Canvas(modifier = modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 2.dp.toPx()
        val path = Path().apply {
            moveTo(w * 0.12f, h * 0.3f)
            lineTo(w * 0.42f, h * 0.3f)
            lineTo(w * 0.5f, h * 0.42f)
            lineTo(w * 0.88f, h * 0.42f)
            lineTo(w * 0.88f, h * 0.78f)
            lineTo(w * 0.12f, h * 0.78f)
            close()
        }
        drawPath(path, color = tint, style = Stroke(width = stroke))
    }
}

@Composable
fun FileIcon(modifier: Modifier = Modifier, tint: Color = ColAccent) {
    Canvas(modifier = modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 2.dp.toPx()
        val path = Path().apply {
            moveTo(w * 0.3f, h * 0.14f)
            lineTo(w * 0.62f, h * 0.14f)
            lineTo(w * 0.78f, h * 0.3f)
            lineTo(w * 0.78f, h * 0.86f)
            lineTo(w * 0.3f, h * 0.86f)
            close()
        }
        drawPath(path, color = tint, style = Stroke(width = stroke))
        drawLine(
            color = tint,
            start = Offset(w * 0.62f, h * 0.14f),
            end = Offset(w * 0.62f, h * 0.3f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.62f, h * 0.3f),
            end = Offset(w * 0.78f, h * 0.3f),
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
