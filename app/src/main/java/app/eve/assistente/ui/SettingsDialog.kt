package app.eve.assistente.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun SettingsDialog(
    initialKey: String,
    initialModel: String,
    initialName: String,
    canDismiss: Boolean,
    onSave: (key: String, model: String, name: String) -> Unit,
    onDismiss: () -> Unit,
    onClearConversation: () -> Unit,
) {
    var key by remember { mutableStateOf(initialKey) }
    var model by remember { mutableStateOf(initialModel) }
    var name by remember { mutableStateOf(initialName) }

    AlertDialog(
        onDismissRequest = { if (canDismiss) onDismiss() },
        title = { Text("Configurações da Eve") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("IA: Google Gemini. Cole aqui a sua chave da API (ela fica guardada só neste celular).")
                OutlinedTextField(
                    value = key, onValueChange = { key = it },
                    label = { Text("Chave da API do Gemini") },
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
            TextButton(onClick = { onSave(key, model, name) }, enabled = key.isNotBlank()) { Text("Salvar") }
        },
        dismissButton = {
            if (canDismiss) TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}
