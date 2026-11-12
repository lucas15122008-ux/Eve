package app.eve.assistente.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import app.eve.assistente.EveState

private val Core = Color(0xFFE8FFFB)
private val Mid = Color(0xFF5EE6D0)
private val Outer = Color(0xFF3FA9F5)

/**
 * O "rosto" da Eve: um núcleo de luz que respira quando está parado,
 * pulsa com a sua voz quando está ouvindo, gira um anel quando está pensando
 * e ondula quando está falando. Visual provisório, o design vem depois.
 */
@Composable
fun EveOrb(state: EveState, volume: Float, modifier: Modifier = Modifier) {
    val loop = rememberInfiniteTransition(label = "orb")
    val breath by loop.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600), RepeatMode.Reverse), label = "breath",
    )
    val spin by loop.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "spin",
    )
    val wave by loop.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "wave",
    )
    val voice by animateFloatAsState(if (state == EveState.LISTENING) volume else 0f, label = "voice")

    val scale = when (state) {
        EveState.IDLE -> 0.92f + 0.05f * breath
        EveState.LISTENING -> 0.95f + 0.18f * voice
        EveState.THINKING -> 0.9f
        EveState.SPEAKING -> 0.95f + 0.08f * wave
    }

    Canvas(modifier) {
        val radius = size.minDimension / 2f * 0.62f * scale
        val c = Offset(size.width / 2f, size.height / 2f)

        // Brilho em volta
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Mid.copy(alpha = 0.35f), Outer.copy(alpha = 0.12f), Color.Transparent),
                center = c, radius = radius * 1.7f,
            ),
            radius = radius * 1.7f, center = c,
        )
        // Núcleo
        drawCircle(
            brush = Brush.radialGradient(listOf(Core, Mid, Outer), center = c, radius = radius),
            radius = radius, center = c,
        )

        when (state) {
            EveState.THINKING -> rotate(spin, c) {
                drawArc(
                    color = Core.copy(alpha = 0.9f),
                    startAngle = 0f, sweepAngle = 110f, useCenter = false,
                    topLeft = Offset(c.x - radius * 1.25f, c.y - radius * 1.25f),
                    size = androidx.compose.ui.geometry.Size(radius * 2.5f, radius * 2.5f),
                    style = Stroke(width = radius * 0.06f, cap = StrokeCap.Round),
                )
            }
            EveState.LISTENING, EveState.SPEAKING -> drawCircle(
                color = Mid.copy(alpha = 0.5f),
                radius = radius * (1.18f + 0.12f * if (state == EveState.LISTENING) voice else wave),
                center = c,
                style = Stroke(width = radius * 0.03f),
            )
            EveState.IDLE -> drawCircle(
                color = Mid.copy(alpha = 0.25f + 0.15f * breath),
                radius = radius * 1.18f,
                center = c,
                style = Stroke(width = radius * 0.02f),
            )
        }
    }
}
