package app.eve.assistente

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.eve.assistente.ai.ProviderId
import app.eve.assistente.assistant.ReminderScheduler
import app.eve.assistente.ui.EveAvatar
import app.eve.assistente.ui.EveCardView
import app.eve.assistente.ui.SettingsDialog

private val Background = Color(0xFF0B0F17)
private val TextMain = Color(0xFFE8F1F5)
private val TextSoft = Color(0xFF8FA3B3)
private val ErrorColor = Color(0xFFFF8A80)

class MainActivity : ComponentActivity() {

    private val vm: EveViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ReminderScheduler.ensureChannel(this)
        handleTestIntent(intent)
        handleLoginReturn(intent)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Background, surface = Color(0xFF141A24))) {
                EveScreen(vm, hasMicPermission = {
                    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleTestIntent(intent)
        handleLoginReturn(intent)
    }

    /** O navegador voltou do login do Mercado Livre. */
    private fun handleLoginReturn(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "app.eve.assistente" && uri.host == "oauth") {
            intent.data = null
            vm.finishMlLogin(uri)
        }
    }

    /**
     * Nos testes do emulador, uma pergunta chega pelo "intent" em vez da voz.
     * Só vale com a IA de demonstração, para outro app não conseguir mandar perguntas para a sua IA.
     */
    private fun handleTestIntent(intent: Intent?) {
        if (vm.settings.provider != ProviderId.DEMO) return
        intent?.getStringExtra(EXTRA_TEST_QUESTION)?.let { vm.askText(it) }
        intent?.removeExtra(EXTRA_TEST_QUESTION)
        intent?.getStringExtra(EXTRA_TEST_MOOD)?.let { vm.setTestMood(it) }
        intent?.removeExtra(EXTRA_TEST_MOOD)
    }

    companion object {
        const val EXTRA_TEST_QUESTION = "eve_teste_pergunta"
        const val EXTRA_TEST_MOOD = "eve_teste_humor"
    }
}

/** Permissões do passo 2: agenda e notificações dos lembretes. */
private fun assistantPermissions(): Array<String> = buildList {
    add(Manifest.permission.READ_CALENDAR)
    add(Manifest.permission.WRITE_CALENDAR)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

@Composable
private fun EveScreen(vm: EveViewModel, hasMicPermission: () -> Boolean) {
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.onOrbTapped() else vm.onMicPermissionDenied()
    }
    val askAssistant = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    LaunchedEffect(Unit) { askAssistant.launch(assistantPermissions()) }

    val onEveTapped = {
        if (vm.state == EveState.IDLE && !hasMicPermission()) {
            askMic.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            vm.onOrbTapped()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Background)
            .safeDrawingPadding()
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val landscape = maxWidth > maxHeight
            val hasCard = vm.card != null
            val bigFace = if (landscape) (maxHeight - 64.dp).coerceIn(120.dp, 220.dp) else 240.dp
            val smallFace = 76.dp

            // A Eve "voa" para o canto quando aparece uma tela personalizada.
            // Ela fica sempre no mesmo lugar da árvore da tela, então não recarrega ao girar o celular.
            val anim = spring<Float>(dampingRatio = 0.8f, stiffness = 180f)
            val faceSize by animateDpAsState(if (hasCard) smallFace else bigFace, spring(dampingRatio = 0.75f, stiffness = 180f), label = "faceSize")
            val biasX by animateFloatAsState(if (hasCard || landscape) -1f else 0f, anim, label = "biasX")
            val biasY by animateFloatAsState(
                when {
                    hasCard -> -1f
                    landscape -> 0f
                    else -> -0.35f
                },
                anim, label = "biasY",
            )
            val padStart by animateDpAsState(
                when {
                    hasCard -> 12.dp
                    landscape -> 24.dp
                    else -> 0.dp
                },
                label = "padStart",
            )
            val padTop by animateDpAsState(if (hasCard) 12.dp else 0.dp, label = "padTop")

            EveAvatar(
                state = vm.state,
                volume = vm.volume,
                mood = vm.mood,
                onClick = onEveTapped,
                modifier = Modifier
                    .align(BiasAlignment(biasX, biasY))
                    .padding(start = padStart, top = padTop)
                    .size(faceSize),
            )

            // Conversa normal: embaixo (em pé) ou à direita (deitado)
            AnimatedVisibility(
                visible = !hasCard,
                enter = fadeIn(), exit = fadeOut(),
                modifier = if (landscape) {
                    Modifier.fillMaxSize().padding(start = bigFace + 48.dp, end = 56.dp, top = 16.dp, bottom = 16.dp)
                } else {
                    Modifier.align(Alignment.BottomCenter).height(maxHeight * 0.42f).padding(horizontal = 24.dp)
                },
            ) {
                Conversation(vm, centered = true)
            }

            // Estado ("Ouvindo…") ao lado da Eve pequena
            AnimatedVisibility(
                visible = hasCard,
                enter = fadeIn(), exit = fadeOut(),
                modifier = if (landscape) {
                    Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 96.dp).width(100.dp)
                } else {
                    Modifier.align(Alignment.TopStart).padding(start = 100.dp, top = 40.dp, end = 56.dp)
                },
            ) {
                Text(statusText(vm.state), color = TextSoft, fontSize = 14.sp)
            }

            // A tela personalizada no meio
            AnimatedVisibility(
                visible = hasCard,
                enter = fadeIn() + scaleIn(initialScale = 0.9f),
                exit = fadeOut() + scaleOut(targetScale = 0.9f),
                modifier = if (landscape) {
                    Modifier.fillMaxSize().padding(start = 120.dp, end = 56.dp, top = 16.dp, bottom = 16.dp)
                } else {
                    Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 108.dp, bottom = 16.dp)
                },
            ) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (vm.reply.isNotBlank()) {
                        Text(vm.reply, color = TextMain, fontSize = 17.sp, lineHeight = 24.sp)
                        Spacer(Modifier.height(16.dp))
                    }
                    vm.card?.let { EveCardView(it, onClose = vm::closeCard) }
                    vm.error?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(it, color = ErrorColor, fontSize = 15.sp)
                    }
                }
            }
        }

        // Por último, para ficar por cima de tudo.
        IconButton(
            onClick = { vm.showSettings = true },
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
        ) {
            Icon(Icons.Filled.Settings, contentDescription = "Configurações", tint = TextSoft)
        }
    }

    if (vm.showSettings) {
        SettingsDialog(
            settings = vm.settings,
            canDismiss = vm.settings.isConfigured,
            onSave = vm::saveSettings,
            mlLoggedIn = vm.mlLoggedIn,
            mlBusy = vm.mlBusy,
            onMlLogin = vm::startMlLogin,
            onMlLogout = vm::mlLogout,
            onDismiss = { vm.showSettings = false },
            onClearConversation = vm::clearConversation,
        )
    }
}

/** Estado, o que você falou, a resposta da Eve e erros, com rolagem. */
@Composable
private fun Conversation(vm: EveViewModel, centered: Boolean) {
    val align = if (centered) TextAlign.Center else TextAlign.Start
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(statusText(vm.state), color = TextSoft, fontSize = 15.sp, textAlign = align)
        Spacer(Modifier.height(16.dp))
        if (vm.heard.isNotBlank()) {
            Text("“${vm.heard}”", color = TextSoft, fontSize = 16.sp, textAlign = align)
            Spacer(Modifier.height(12.dp))
        }
        if (vm.reply.isNotBlank() && vm.state != EveState.LISTENING) {
            Text(vm.reply, color = TextMain, fontSize = 19.sp, textAlign = align, lineHeight = 26.sp)
        }
        vm.error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = ErrorColor, fontSize = 15.sp, textAlign = align)
        }
    }
}

private fun statusText(state: EveState) = when (state) {
    EveState.IDLE -> "Toque na Eve para falar"
    EveState.LISTENING -> "Ouvindo…"
    EveState.THINKING -> "Pensando…"
    EveState.SPEAKING -> "Falando… toque para interromper"
}
