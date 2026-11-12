package app.eve.assistente.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.eve.assistente.ai.EveSettings
import app.eve.assistente.ai.ProviderId

@Composable
fun SettingsDialog(
    settings: EveSettings,
    canDismiss: Boolean,
    onSave: (provider: ProviderId, key: String, model: String, name: String, speechRate: Float, mlUrl: String) -> Unit,
    mlLoggedIn: Boolean,
    mlBusy: Boolean,
    onMlLogin: (url: String) -> Unit,
    onMlLogout: () -> Unit,
    onDismiss: () -> Unit,
    onClearConversation: () -> Unit,
) {
    var provider by remember { mutableStateOf(settings.provider) }
    var key by remember { mutableStateOf(settings.apiKey(provider)) }
    var model by remember { mutableStateOf(settings.model(provider)) }
    var name by remember { mutableStateOf(settings.userName) }
    var rate by remember { mutableStateOf(settings.speechRate) }
    var mlUrl by remember { mutableStateOf(settings.mlServerUrl) }
    val mlUrlOk = mlUrl.isBlank() || mlUrl.trim().startsWith("https://")

    fun choose(p: ProviderId) {
        provider = p
        key = settings.apiKey(p)
        model = settings.model(p)
    }

    AlertDialog(
        onDismissRequest = { if (canDismiss) onDismiss() },
        title = {
            Column {
                Text("Configurações da Eve")
                Text("Versão ${appVersion()}", style = MaterialTheme.typography.bodySmall)
            }
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Qual IA a Eve usa:")
                ProviderId.visible.forEach { p ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = provider == p, onClick = { choose(p) }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = provider == p, onClick = { choose(p) })
                        Text(p.label)
                    }
                }
                Text("Pegue a chave em ${provider.keyHint} e cole aqui. Ela fica guardada só neste celular.")
                OutlinedTextField(
                    value = key, onValueChange = { key = it },
                    label = { Text("Chave da API (${provider.label})") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = model, onValueChange = { model = it },
                    label = { Text("Modelo") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Seu nome") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Velocidade da voz: ${rateLabel(rate)}")
                Slider(
                    value = rate, onValueChange = { rate = it },
                    valueRange = 0.6f..1.2f, steps = 11,
                )

                val urlHint: (@Composable () -> Unit)? =
                    if (mlUrlOk) null else { { Text("O endereço precisa começar com https://") } }
                Text("Mercado Livre", style = MaterialTheme.typography.titleSmall)
                Text("Endereço do servidor JR Mercado Livre (o mesmo conector do Claude). Fica guardado só neste celular.")
                OutlinedTextField(
                    value = mlUrl, onValueChange = { mlUrl = it },
                    label = { Text("Endereço do servidor (https://...)") },
                    singleLine = true,
                    isError = !mlUrlOk,
                    supportingText = urlHint,
                    modifier = Modifier.fillMaxWidth(),
                )
                val sameUrl = mlUrl.trim() == settings.mlServerUrl
                Text(
                    when {
                        mlBusy -> "Conectando…"
                        mlLoggedIn && sameUrl -> "Conectada ao Mercado Livre ✓"
                        else -> "Ainda não entrou no servidor."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onMlLogin(mlUrl.trim()) },
                        enabled = !mlBusy && mlUrl.trim().startsWith("https://"),
                    ) { Text(if (mlLoggedIn && sameUrl) "Entrar de novo" else "Entrar no Mercado Livre") }
                    if (mlLoggedIn && sameUrl) {
                        TextButton(onClick = onMlLogout, enabled = !mlBusy) { Text("Sair") }
                    }
                }
                Text(
                    "O login abre no navegador, igual ao que você fez no Claude, e depois volta para a Eve.",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = onClearConversation) { Text("Apagar a conversa atual") }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(provider, key, model, name, rate, mlUrl) },
                enabled = key.isNotBlank() && mlUrlOk,
            ) { Text("Salvar") }
        },
        dismissButton = {
            if (canDismiss) TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}

/** A versão instalada (ex.: 0.2.11), para saber se o celular está com a versão nova. */
@Composable
private fun appVersion(): String {
    val context = LocalContext.current
    return runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
        .getOrNull() ?: "?"
}

private fun rateLabel(rate: Float): String = when {
    rate < 0.75f -> "bem devagar"
    rate < 0.95f -> "devagar"
    rate < 1.05f -> "normal"
    else -> "rápida"
}
