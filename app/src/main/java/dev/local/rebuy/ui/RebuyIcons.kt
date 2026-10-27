package dev.local.rebuy.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal enum class RebuyIconKind {
    Basket, Calendar, History, Settings, Plus, More, Link, Check, ArrowRight, ExternalLink, Bell,
}

private val rebuyIconStroke = Stroke(width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)

@Composable
internal fun RebuyIcon(
    kind: RebuyIconKind,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = null,
) {
    val description = contentDescription
    val accessibility = if (description == null) Modifier else Modifier.semantics {
        this.contentDescription = description
    }
    Canvas(modifier.then(accessibility).size(24.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            when (kind) {
                RebuyIconKind.Basket -> drawBasket(tint)
                RebuyIconKind.Calendar -> drawCalendar(tint)
                RebuyIconKind.History -> drawHistory(tint)
                RebuyIconKind.Settings -> drawSettings(tint)
                RebuyIconKind.Plus -> {
                    segment(tint, 12f, 5f, 12f, 19f)
                    segment(tint, 5f, 12f, 19f, 12f)
                }
                RebuyIconKind.More -> {
                    listOf(5f, 12f, 19f).forEach { drawCircle(tint, 1.35f, Offset(it, 12f)) }
                }
                RebuyIconKind.Link -> drawLink(tint)
                RebuyIconKind.Check -> strokePath(tint) {
                    moveTo(5f, 12.5f)
                    lineTo(9.5f, 17f)
                    lineTo(19f, 7f)
                }
                RebuyIconKind.ArrowRight -> {
                    segment(tint, 4f, 12f, 19f, 12f)
                    strokePath(tint) {
                        moveTo(13f, 6f)
                        lineTo(19f, 12f)
                        lineTo(13f, 18f)
                    }
                }
                RebuyIconKind.ExternalLink -> drawExternalLink(tint)
                RebuyIconKind.Bell -> drawBell(tint)
            }
        }
    }
}

private fun DrawScope.drawBasket(color: Color) {
    strokePath(color) {
        moveTo(4.5f, 9.5f)
        lineTo(6.1f, 18.5f)
        quadraticTo(6.3f, 20f, 7.8f, 20f)
        lineTo(16.2f, 20f)
        quadraticTo(17.7f, 20f, 17.9f, 18.5f)
        lineTo(19.5f, 9.5f)
    }
    segment(color, 3.5f, 9.5f, 20.5f, 9.5f)
    segment(color, 7f, 9.5f, 10f, 4f)
    segment(color, 14f, 4f, 17f, 9.5f)
    segment(color, 9f, 13f, 9.7f, 17f)
    segment(color, 15f, 13f, 14.3f, 17f)
}

private fun DrawScope.drawCalendar(color: Color) {
    drawRoundRect(color, Offset(4f, 5f), Size(16f, 15f), CornerRadius(2f), style = rebuyIconStroke)
    segment(color, 4f, 10f, 20f, 10f)
    segment(color, 8f, 3f, 8f, 7f)
    segment(color, 16f, 3f, 16f, 7f)
    listOf(Offset(8f, 14f), Offset(12f, 14f), Offset(16f, 14f), Offset(8f, 17f))
        .forEach { drawCircle(color, 0.9f, it) }
}

private fun DrawScope.drawHistory(color: Color) {
    drawArc(color, -65f, 305f, false, Offset(4f, 4f), Size(16f, 16f), style = rebuyIconStroke)
    strokePath(color) {
        moveTo(3f, 4f)
        lineTo(3f, 9f)
        lineTo(8f, 9f)
    }
    strokePath(color) {
        moveTo(12f, 7.5f)
        lineTo(12f, 12f)
        lineTo(15.5f, 14f)
    }
}

private fun DrawScope.drawSettings(color: Color) {
    strokePath(color) {
        repeat(32) { index ->
            val radius = if (index % 4 in 1..2) 9.2f else 7.5f
            val angle = index * PI / 16 - PI / 2
            val x = 12f + cos(angle).toFloat() * radius
            val y = 12f + sin(angle).toFloat() * radius
            if (index == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
    drawCircle(color, 2.9f, Offset(12f, 12f), style = rebuyIconStroke)
}

private fun DrawScope.drawLink(color: Color) {
    strokePath(color) {
        moveTo(11f, 8f)
        lineTo(13.5f, 5.5f)
        cubicTo(17f, 2f, 22f, 7f, 18.5f, 10.5f)
        lineTo(16f, 13f)
    }
    strokePath(color) {
        moveTo(8f, 11f)
        lineTo(5.5f, 13.5f)
        cubicTo(2f, 17f, 7f, 22f, 10.5f, 18.5f)
        lineTo(13f, 16f)
    }
    segment(color, 8.5f, 15.5f, 15.5f, 8.5f)
}

private fun DrawScope.drawExternalLink(color: Color) {
    strokePath(color) {
        moveTo(9f, 4f)
        lineTo(6f, 4f)
        quadraticTo(4f, 4f, 4f, 6f)
        lineTo(4f, 18f)
        quadraticTo(4f, 20f, 6f, 20f)
        lineTo(18f, 20f)
        quadraticTo(20f, 20f, 20f, 18f)
        lineTo(20f, 15f)
    }
    strokePath(color) {
        moveTo(13f, 4f)
        lineTo(20f, 4f)
        lineTo(20f, 11f)
    }
    segment(color, 20f, 4f, 11f, 13f)
}

private fun DrawScope.drawBell(color: Color) {
    strokePath(color) {
        moveTo(6f, 16f)
        lineTo(6.8f, 8.5f)
        cubicTo(7.2f, 3f, 16.8f, 3f, 17.2f, 8.5f)
        lineTo(18f, 16f)
        lineTo(20f, 18f)
        lineTo(4f, 18f)
        close()
    }
    strokePath(color) {
        moveTo(10f, 21f)
        quadraticTo(12f, 22.5f, 14f, 21f)
    }
    segment(color, 12f, 2f, 12f, 4f)
}

private fun DrawScope.segment(color: Color, x1: Float, y1: Float, x2: Float, y2: Float) {
    drawLine(color, Offset(x1, y1), Offset(x2, y2), strokeWidth = 1.8f, cap = StrokeCap.Round)
}

private fun DrawScope.strokePath(color: Color, content: Path.() -> Unit) {
    drawPath(Path().apply(content), color, style = rebuyIconStroke)
}
