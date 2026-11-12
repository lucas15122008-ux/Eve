package app.eve.assistente

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.eve.assistente.assistant.EveCard
import app.eve.assistente.assistant.ReminderScheduler
import app.eve.assistente.ui.EveCardView
import app.eve.assistente.ui.EveOrb
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
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Background, surface = Color(0xFF141A24))) {
                EveScreen(vm, hasMicPermission = {
                    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                })
            }
        }
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

    val card: EveCard? = vm.card
    val hasCard = card != null

    // A Eve "voa" do centro para o canto quando aparece uma tela personalizada.
    val orbSize by animateDpAsState(if (hasCard) 76.dp else 260.dp, spring(dampingRatio = 0.75f, stiffness = 180f), label = "orbSize")
    val biasX by animateFloatAsState(if (hasCard) -1f else 0f, spring(dampingRatio = 0.8f, stiffness = 180f), label = "biasX")
    val biasY by animateFloatAsState(if (hasCard) -1f else -0.3f, spring(dampingRatio = 0.8f, stiffness = 180f), label = "biasY")

    Box(
        Modifier
            .fillMaxSize()
            .background(Background)
            .safeDrawingPadding()
    ) {
        IconButton(
            onClick = { vm.showSettings = true },
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
        ) {
            Icon(Icons.Filled.Settings, contentDescription = "Configurações", tint = TextSoft)
        }

        EveOrb(
            state = vm.state,
            volume = vm.volume,
            modifier = Modifier
                .align(BiasAlignment(biasX, biasY))
                .padding(if (hasCard) 12.dp else 0.dp)
                .size(orbSize)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    if (vm.state == EveState.IDLE && !hasMicPermission()) {
                        askMic.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        vm.onOrbTapped()
                    }
                },
        )

        // Status ao lado da Eve pequena, no topo
        AnimatedVisibility(
            visible = hasCard,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart).padding(start = 100.dp, top = 40.dp, end = 56.dp),
        ) {
            Text(statusText(vm.state), color = TextSoft, fontSize = 15.sp)
        }

        // A tela personalizada no meio
        AnimatedVisibility(
            visible = hasCard,
            enter = fadeIn() + scaleIn(initialScale = 0.9f),
            exit = fadeOut() + scaleOut(targetScale = 0.9f),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 108.dp, start = 16.dp, end = 16.dp, bottom = 16.dp),
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

        // Conversa normal, com a Eve no centro
        AnimatedVisibility(
            visible = !hasCard,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(330.dp)
                    .padding(horizontal = 24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(statusText(vm.state), color = TextSoft, fontSize = 15.sp)
                Spacer(Modifier.height(20.dp))
                if (vm.heard.isNotBlank()) {
                    Text("“${vm.heard}”", color = TextSoft, fontSize = 16.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                }
                if (vm.reply.isNotBlank() && vm.state != EveState.LISTENING) {
                    Text(vm.reply, color = TextMain, fontSize = 19.sp, textAlign = TextAlign.Center, lineHeight = 26.sp)
                }
                vm.error?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = ErrorColor, fontSize = 15.sp, textAlign = TextAlign.Center)
                }
            }
        }
    }

    if (vm.showSettings) {
        SettingsDialog(
            settings = vm.settings,
            canDismiss = vm.settings.isConfigured,
            onSave = vm::saveSettings,
            onDismiss = { vm.showSettings = false },
            onClearConversation = vm::clearConversation,
        )
    }
}

private fun statusText(state: EveState) = when (state) {
    EveState.IDLE -> "Toque na Eve para falar"
    EveState.LISTENING -> "Ouvindo…"
    EveState.THINKING -> "Pensando…"
    EveState.SPEAKING -> "Falando… toque para interromper"
}
