package app.eve.assistente.ui

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import app.eve.assistente.EveState

/**
 * O rosto da Eve: uma folhinha de agenda violeta com olhos, que pisca e respira.
 *
 * O desenho e as animações vêm do open-bot-avatar (licença MIT), que roda numa
 * pequena página dentro do app (assets/eve-avatar/index.html), sem internet.
 *
 * - Parada: expressão normal, respirando.
 * - Ouvindo: olha para cima e cresce um pouco com a sua voz.
 * - Pensando: expressão "pensando".
 * - Falando: expressão feliz, balançando de leve.
 *
 * Os toques vão para [onClick] por uma camada invisível por cima do desenho.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EveAvatar(state: EveState, volume: Float, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val avatar = remember {
        AvatarWebView(WebView(context).apply {
            setBackgroundColor(AndroidColor.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            isFocusable = false
        })
    }

    DisposableEffect(avatar) {
        avatar.load()
        onDispose { avatar.web.destroy() }
    }

    LaunchedEffect(state) {
        avatar.set(
            when (state) {
                EveState.IDLE -> "{mood:'idle',lookAt:null}"
                EveState.LISTENING -> "{mood:'idle',lookAt:{x:0,y:-0.5}}"
                EveState.THINKING -> "{mood:'thinking',lookAt:null}"
                EveState.SPEAKING -> "{mood:'happy',lookAt:null}"
            }
        )
    }

    val loop = rememberInfiniteTransition(label = "eve")
    val wave by loop.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "wave",
    )
    val voice by animateFloatAsState(if (state == EveState.LISTENING) volume else 0f, label = "voice")
    val scale = when (state) {
        EveState.LISTENING -> 1f + 0.12f * voice
        EveState.SPEAKING -> 1f + 0.04f * wave
        else -> 1f
    }

    Box(modifier) {
        AndroidView(
            factory = { avatar.web },
            modifier = Modifier.fillMaxSize().scale(scale),
        )
        Box(
            Modifier
                .matchParentSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = "Falar com a Eve",
                    onClick = onClick,
                )
        )
    }
}

/** Guarda o último comando até a página terminar de carregar. */
private class AvatarWebView(val web: WebView) {
    private var ready = false
    private var pending: String? = null

    fun load() {
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                ready = true
                pending?.let { run(it) }
                pending = null
            }
        }
        web.loadUrl("file:///android_asset/eve-avatar/index.html")
    }

    fun set(options: String) {
        if (ready) run(options) else pending = options
    }

    private fun run(options: String) {
        web.evaluateJavascript("window.eve && eve.set($options)", null)
    }
}
