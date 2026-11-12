package app.eve.assistente.ai

import kotlinx.coroutines.delay
import java.time.LocalDate

/**
 * IA de mentira, só para os testes automáticos no emulador (não precisa de internet nem de chave).
 * Se a pergunta falar de agenda, pede para mostrar a agenda de hoje; senão, só responde.
 */
class DemoProvider : AiProvider {
    override val displayName = "Demonstração"

    override suspend fun reply(systemPrompt: String, history: List<ChatMessage>): String {
        delay(1500)
        val question = history.lastOrNull()?.text.orEmpty().lowercase()
        return if ("agenda" in question) {
            """{"fala": "Aqui está a sua agenda de hoje.", "acoes": [{"tipo": "mostrar_agenda", "dia": "${LocalDate.now()}"}]}"""
        } else {
            """{"fala": "Tudo bem, Lucas! Mudei para as 18 horas, ou seja, 6 da tarde.", "acoes": []}"""
        }
    }
}
