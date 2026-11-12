package app.eve.assistente.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.eve.assistente.ai.EveSettings
import app.eve.assistente.ai.ProviderId

@Composable
fun SettingsDialog(
    settings: EveSettings,
    canDismiss: Boolean,
    onSave: (provider: ProviderId, key: String, model: String, name: String) -> Unit,
    onDismiss: () -> Unit,
    onClearConversation: () -> Unit,
) {
    var provider by remember { mutableStateOf(settings.provider) }
    var key by remember { mutableStateOf(settings.apiKey(provider)) }
    var model by remember { mutableStateOf(settings.model(provider)) }
    var name by remember { mutableStateOf(settings.userName) }

    fun choose(p: ProviderId) {
        provider = p
        key = settings.apiKey(p)
        model = settings.model(p)
    }

    AlertDialog(
        onDismissRequest = { if (canDismiss) onDismiss() },
        title = { Text("Configurações da Eve") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Qual IA a Eve usa:")
                ProviderId.entries.forEach { p ->
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
                TextButton(onClick = onClearConversation) { Text("Apagar a conversa atual") }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(provider, key, model, name) }, enabled = key.isNotBlank()) { Text("Salvar") }
        },
        dismissButton = {
            if (canDismiss) TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}
