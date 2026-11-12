package app.eve.assistente

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.eve.assistente.ui.EveAvatar
import app.eve.assistente.ui.SettingsDialog

private val Background = Color(0xFF0B0F17)
private val TextMain = Color(0xFFE8F1F5)
private val TextSoft = Color(0xFF8FA3B3)
private val ErrorColor = Color(0xFFFF8A80)

class MainActivity : ComponentActivity() {

    private val vm: EveViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

@Composable
private fun EveScreen(vm: EveViewModel, hasMicPermission: () -> Boolean) {
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.onOrbTapped() else vm.onMicPermissionDenied()
    }

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

        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            EveAvatar(
                state = vm.state,
                volume = vm.volume,
                onClick = {
                    if (vm.state == EveState.IDLE && !hasMicPermission()) {
                        askMic.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        vm.onOrbTapped()
                    }
                },
                modifier = Modifier.size(240.dp),
            )

            Text(
                text = statusText(vm.state),
                color = TextSoft,
                fontSize = 15.sp,
                modifier = Modifier.padding(top = 8.dp),
            )

            Spacer(Modifier.height(24.dp))

            Column(
                Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
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
