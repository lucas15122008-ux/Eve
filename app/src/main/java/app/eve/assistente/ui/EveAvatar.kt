package app.eve.assistente.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import app.eve.assistente.EveState
import kotlinx.coroutines.delay
import kotlin.math.sin
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
 * Humores da Eve. Aparecem quando ela está parada ou falando;
 * ouvindo e pensando têm sempre a mesma cara.
 */
enum class EveMood(val id: String) {
    NORMAL("normal"),
    SURPRESA("surpresa"),       // olhos bem abertos e boquinha em "o"
    PREOCUPADA("preocupada"),   // sobrancelhas inclinadas
    SONOLENTA("sonolenta"),     // olhos meio fechados, "z" flutuando
    PISCADINHA("piscadinha"),   // um olho fechado
    TRISTE("triste"),           // olhos caídos e boquinha triste
    ANIMADA("animada"),         // olhos felizes, pulando
    CONFUSA("confusa");         // um olho maior que o outro, cabeça de lado

    companion object {
        fun fromId(id: String?): EveMood? = entries.firstOrNull { it.id == id?.trim()?.lowercase() }
    }
}

/** Como o rosto deve ficar; cada número é animado até o valor novo. */
private data class Face(
    val openL: Float = 1f,      // altura do olho esquerdo (1 = normal)
    val openR: Float = 1f,
    val wide: Float = 1f,       // largura dos olhos
    val happyL: Float = 0f,     // olho esquerdo vira arquinho (^)
    val happyR: Float = 0f,
    val gazeX: Float = 0f,
    val gazeY: Float = 0f,
    val worried: Float = 0f,    // sobrancelhas de preocupação
    val raised: Float = 0f,     // uma sobrancelha levantada (confusa)
    val mouthO: Float = 0f,     // boquinha em "o"
    val frown: Float = 0f,      // boquinha triste
    val lids: Float = 0f,       // olhos meio fechados (sono)
    val tilt: Float = 0f,       // cabeça inclinada (graus)
    val sink: Float = 0f,       // abaixa o corpo (triste)
    val jump: Float = 0f,       // pulinhos (animada)
    val zzz: Float = 0f,        // "z" do sono
)

private fun faceFor(state: EveState, mood: EveMood): Face = when (state) {
    EveState.LISTENING -> Face(gazeY = -4f)
    EveState.THINKING -> Face(openL = 0.6f, openR = 0.6f, gazeX = -3f, gazeY = -3f, tilt = -5f)
    EveState.IDLE, EveState.SPEAKING -> when (mood) {
        EveMood.NORMAL ->
            if (state == EveState.SPEAKING) Face(happyL = 1f, happyR = 1f) else Face()
        EveMood.SURPRESA -> Face(openL = 1.25f, openR = 1.25f, wide = 1.25f, gazeY = -2f, mouthO = 1f)
        EveMood.PREOCUPADA -> Face(openL = 0.85f, openR = 0.85f, gazeY = 1f, worried = 1f, tilt = -3f)
        EveMood.SONOLENTA -> Face(lids = 1f, gazeY = 2f, tilt = 7f, zzz = 1f)
        EveMood.PISCADINHA -> Face(happyR = 1f, tilt = 6f)
        EveMood.TRISTE -> Face(openL = 0.7f, openR = 0.7f, gazeY = 3f, worried = 0.7f, frown = 1f, sink = 1f)
        EveMood.ANIMADA -> Face(happyL = 1f, happyR = 1f, jump = 1f, mouthO = 0.6f)
        EveMood.CONFUSA -> Face(openL = 1.1f, openR = 0.6f, gazeX = 2f, raised = 1f, tilt = 10f)
    }
}

/**
 * O rosto da Eve: uma folhinha de agenda violeta com olhos, desenhada direto pelo app
 * (sem página web, então aparece sempre, em qualquer tamanho e com o celular deitado).
 *
 * - Parada: respira, balança de leve e pisca de vez em quando.
 * - Ouvindo: olha para cima e cresce um pouco com a sua voz.
 * - Pensando: olhos menores, olhando para o lado.
 * - Falando: olhos felizes (^ ^), balançando no ritmo da fala.
 * - Humores ([EveMood]): surpresa, preocupada, sonolenta, piscadinha, triste, animada e confusa.
 */
@Composable
fun EveAvatar(
    state: EveState,
    volume: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    mood: EveMood = EveMood.NORMAL,
) {
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
    // Fase contínua de 0 a 1 para os "z" subirem e para os pulinhos.
    val phase by loop.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "phase",
    )

    // Piscar: de 2,5 a 5 segundos, fecha e abre os olhos rapidinho (com sono, pisca devagar).
    val blink = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextLong(2500, 5000))
            blink.animateTo(0.1f, tween(70))
            blink.animateTo(1f, tween(110))
        }
    }

    val f = faceFor(state, mood)
    val t = tween<Float>(350)
    val openL by animateFloatAsState(f.openL, t, label = "openL")
    val openR by animateFloatAsState(f.openR, t, label = "openR")
    val wide by animateFloatAsState(f.wide, t, label = "wide")
    val happyL by animateFloatAsState(f.happyL, tween(250), label = "happyL")
    val happyR by animateFloatAsState(f.happyR, tween(250), label = "happyR")
    val gazeX by animateFloatAsState(f.gazeX, tween(400), label = "gazeX")
    val gazeY by animateFloatAsState(f.gazeY, tween(400), label = "gazeY")
    val worried by animateFloatAsState(f.worried, t, label = "worried")
    val raised by animateFloatAsState(f.raised, t, label = "raised")
    val mouthO by animateFloatAsState(f.mouthO, t, label = "mouthO")
    val frown by animateFloatAsState(f.frown, t, label = "frown")
    val lids by animateFloatAsState(f.lids, tween(500), label = "lids")
    val tiltBase by animateFloatAsState(f.tilt, tween(500), label = "tilt")
    val sink by animateFloatAsState(f.sink, tween(600), label = "sink")
    val jump by animateFloatAsState(f.jump, t, label = "jump")
    val zzz by animateFloatAsState(f.zzz, tween(600), label = "zzz")

    val voice by animateFloatAsState(if (state == EveState.LISTENING) volume else 0f, label = "voice")

    val grow = when (state) {
        EveState.LISTENING -> 1f + 0.12f * voice
        EveState.SPEAKING -> 1f + 0.03f * bob
        else -> 1f + 0.015f * breath * (1f + 1.5f * lids)  // com sono, respira mais fundo
    }
    // Balanço: falando acompanha a fala; com sono ou triste, quase parada.
    val calm = 1f - 0.7f * maxOf(lids, sink)
    val tilt = tiltBase + when (state) {
        EveState.SPEAKING -> 4f * (bob - 0.5f)
        EveState.THINKING -> 0f
        else -> 3f * sway * calm
    }
    // Pulinhos da animada: dois saltos por ciclo.
    val hop = jump * -6f * kotlin.math.abs(sin(phase * 2f * Math.PI.toFloat()))

    Canvas(
        modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClickLabel = "Falar com a Eve",
            onClick = onClick,
        )
    ) {
        if (size.minDimension <= 0f) return@Canvas
        // O desenho ocupa ~86% do espaço, para caber o balanço, os pulos e os "z" sem cortar.
        val unit = size.minDimension / 100f * 0.86f
        val left = (size.width - 100f * unit) / 2f
        val top = (size.height - 100f * unit) / 2f

        translate(left, top) {
            scale(unit, unit, pivot = Offset.Zero) {
                translate(0f, hop + 3f * sink) {
                    rotate(tilt, pivot = Offset(50f, 60f)) {
                        // Triste: o corpo "murcha" um pouco.
                        scale(grow, grow * (1f - 0.04f * sink), pivot = Offset(50f, 88f)) {
                            drawPath(body, Violet)
                            val b = blink.value
                            drawEyes(
                                openL = b * openL, openR = b * openR, wide = wide,
                                happyL = happyL, happyR = happyR,
                                dx = gazeX, dy = gazeY, lids = lids,
                            )
                            drawBrows(worried, raised, gazeY)
                            drawMouth(mouthO, frown)
                        }
                    }
                }
                if (zzz > 0.01f) drawZzz(zzz, phase)
            }
        }
    }
}

/** Olhos em "pílula"; viram arquinhos (^) quando felizes ou risquinhos quando com sono. */
private fun DrawScope.drawEyes(
    openL: Float, openR: Float, wide: Float,
    happyL: Float, happyR: Float,
    dx: Float, dy: Float, lids: Float,
) {
    val eyes = listOf(Triple(EYE_LEFT_X, openL, happyL), Triple(EYE_RIGHT_X, openR, happyR))
    for ((x, open, happy) in eyes) {
        val cx = x + dx
        val cy = EYE_Y + dy
        val pill = (1f - happy) * (1f - lids)
        if (pill > 0.01f) {
            val w = EYE_W * wide
            val h = (EYE_H * open).coerceAtLeast(2f)
            drawRoundRect(
                color = Ink.copy(alpha = pill),
                topLeft = Offset(cx - w / 2f, cy - h / 2f),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2f, w / 2f),
            )
        }
        if (happy > 0.01f) {
            drawArc(
                color = Ink.copy(alpha = happy),
                startAngle = 200f, sweepAngle = 140f, useCenter = false,
                topLeft = Offset(cx - 6f, cy - 5f),
                size = Size(12f, 12f),
                style = Stroke(width = 3.4f, cap = StrokeCap.Round),
            )
        }
        if (lids > 0.01f) {
            // Olho meio fechado: uma pílula deitada e baixinha.
            drawRoundRect(
                color = Ink.copy(alpha = lids * (1f - happy)),
                topLeft = Offset(cx - 4.5f, cy + 1f),
                size = Size(9f, 3.6f),
                cornerRadius = CornerRadius(1.8f, 1.8f),
            )
        }
    }
}

/** Sobrancelhas: preocupada (pontas de dentro levantadas) ou uma só levantada (confusa). */
private fun DrawScope.drawBrows(worried: Float, raised: Float, dy: Float) {
    val stroke = Stroke(width = 2.8f, cap = StrokeCap.Round)
    if (worried > 0.01f) {
        val c = Ink.copy(alpha = worried)
        val y = 44f + dy
        drawLine(c, Offset(36f, y), Offset(45f, y - 4f * worried), strokeWidth = stroke.width, cap = StrokeCap.Round)
        drawLine(c, Offset(64f, y), Offset(55f, y - 4f * worried), strokeWidth = stroke.width, cap = StrokeCap.Round)
    }
    if (raised > 0.01f) {
        val path = Path().apply {
            moveTo(36f, 43f + dy)
            quadraticBezierTo(41f, 37f + dy, 46f, 41f + dy)
        }
        drawPath(path, Ink.copy(alpha = raised), style = stroke)
    }
}

/** Boquinha em "o" (surpresa) ou triste (curvinha para baixo). */
private fun DrawScope.drawMouth(o: Float, frown: Float) {
    if (o > 0.01f) {
        drawCircle(Ink.copy(alpha = o), radius = 2.2f + 1.3f * o, center = Offset(50f, 73f))
    }
    if (frown > 0.01f) {
        val path = Path().apply {
            moveTo(44f, 76f)
            quadraticBezierTo(50f, 70f, 56f, 76f)
        }
        drawPath(path, Ink.copy(alpha = frown), style = Stroke(width = 2.8f, cap = StrokeCap.Round))
    }
}

/** Dois "z" subindo e sumindo acima da folhinha (sono). */
private fun DrawScope.drawZzz(alpha: Float, phase: Float) {
    for (i in 0..1) {
        val p = (phase + i * 0.5f) % 1f
        val size = 6f + 3f * p
        val x = 80f + 6f * p
        val y = 18f - 14f * p
        val fade = alpha * (if (p < 0.2f) p / 0.2f else 1f - (p - 0.2f) / 0.8f)
        val z = Path().apply {
            moveTo(x, y)
            lineTo(x + size, y)
            lineTo(x, y + size)
            lineTo(x + size, y + size)
        }
        drawPath(
            z, Violet.copy(alpha = fade),
            style = Stroke(width = 2.4f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
