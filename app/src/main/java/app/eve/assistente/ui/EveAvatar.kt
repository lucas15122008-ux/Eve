package app.eve.assistente.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import app.eve.assistente.EveState
import kotlinx.coroutines.delay
import kotlin.random.Random

private val Violet = Color(0xFF7C5CFF)
private val Ink = Color(0xFF151515)

/** O contorno da folhinha de agenda, num quadro de 100 x 100 (mesmo desenho do ícone do app). */
private const val BODY =
    "M30 12C34 12 36 14 36 18V24H64V18C64 14 66 12 70 12C74 12 76 14 76 18V24H76C83 24 88 29 88 36V76" +
        "C88 83 83 88 76 88H24C17 88 12 83 12 76V36C12 29 17 24 24 24H24V18C24 14 26 12 30 12Z"

private const val EYE_Y = 56f
private const val EYE_LEFT_X = 41f
private const val EYE_RIGHT_X = 59f
private const val EYE_W = 6f
private const val EYE_H = 15f

/**
 * O rosto da Eve: uma folhinha de agenda violeta com olhos, desenhada direto pelo app
 * (sem página web, então aparece sempre, em qualquer tamanho e com o celular deitado).
 *
 * - Parada: respira, balança de leve e pisca de vez em quando.
 * - Ouvindo: olha para cima e cresce um pouco com a sua voz.
 * - Pensando: olhos menores, olhando para o lado.
 * - Falando: olhos felizes (^ ^), balançando no ritmo da fala.
 */
@Composable
fun EveAvatar(state: EveState, volume: Float, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val body = remember { PathParser().parsePathString(BODY).toPath() }

    val loop = rememberInfiniteTransition(label = "eve")
    val breath by loop.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600), RepeatMode.Reverse), label = "breath",
    )
    val sway by loop.animateFloat(
        initialValue = -1f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3400), RepeatMode.Reverse), label = "sway",
    )
    val bob by loop.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "bob",
    )

    // Piscar: de 2,5 a 5 segundos, fecha e abre os olhos rapidinho.
    val blink = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextLong(2500, 5000))
            blink.animateTo(0.1f, tween(70))
            blink.animateTo(1f, tween(110))
        }
    }

    val voice by animateFloatAsState(if (state == EveState.LISTENING) volume else 0f, label = "voice")
    val happy by animateFloatAsState(if (state == EveState.SPEAKING) 1f else 0f, tween(250), label = "happy")
    val eyeSize by animateFloatAsState(if (state == EveState.THINKING) 0.6f else 1f, tween(300), label = "eyeSize")
    val gazeX by animateFloatAsState(if (state == EveState.THINKING) -3f else 0f, tween(400), label = "gazeX")
    val gazeY by animateFloatAsState(
        when (state) {
            EveState.LISTENING -> -4f
            EveState.THINKING -> -3f
            else -> 0f
        },
        tween(400), label = "gazeY",
    )

    val grow = when (state) {
        EveState.LISTENING -> 1f + 0.12f * voice
        EveState.SPEAKING -> 1f + 0.03f * bob
        else -> 1f + 0.015f * breath
    }
    val tilt = when (state) {
        EveState.SPEAKING -> 4f * (bob - 0.5f)
        EveState.THINKING -> -5f
        else -> 3f * sway
    }

    Canvas(
        modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClickLabel = "Falar com a Eve",
            onClick = onClick,
        )
    ) {
        if (size.minDimension <= 0f) return@Canvas
        // O desenho ocupa ~90% do espaço, para caber o balanço sem cortar.
        val unit = size.minDimension / 100f * 0.9f
        val left = (size.width - 100f * unit) / 2f
        val top = (size.height - 100f * unit) / 2f

        translate(left, top) {
            scale(unit, unit, pivot = Offset.Zero) {
                rotate(tilt, pivot = Offset(50f, 60f)) {
                    scale(grow, grow, pivot = Offset(50f, 56f)) {
                        drawPath(body, Violet)
                        drawEyes(
                            open = blink.value * eyeSize,
                            happy = happy,
                            dx = gazeX,
                            dy = gazeY,
                        )
                    }
                }
            }
        }
    }
}

/** Olhos em "pílula"; quando feliz, viram arquinhos (^ ^). */
private fun DrawScope.drawEyes(open: Float, happy: Float, dx: Float, dy: Float) {
    val pillAlpha = 1f - happy
    if (pillAlpha > 0.01f) {
        val h = (EYE_H * open).coerceAtLeast(2f)
        for (x in floatArrayOf(EYE_LEFT_X, EYE_RIGHT_X)) {
            drawRoundRect(
                color = Ink.copy(alpha = pillAlpha),
                topLeft = Offset(x - EYE_W / 2f + dx, EYE_Y - h / 2f + dy),
                size = Size(EYE_W, h),
                cornerRadius = CornerRadius(EYE_W / 2f, EYE_W / 2f),
            )
        }
    }
    if (happy > 0.01f) {
        for (x in floatArrayOf(EYE_LEFT_X, EYE_RIGHT_X)) {
            drawArc(
                color = Ink.copy(alpha = happy),
                startAngle = 200f, sweepAngle = 140f, useCenter = false,
                topLeft = Offset(x - 6f + dx, EYE_Y - 5f + dy),
                size = Size(12f, 12f),
                style = Stroke(width = 3.4f, cap = StrokeCap.Round),
            )
        }
    }
}
