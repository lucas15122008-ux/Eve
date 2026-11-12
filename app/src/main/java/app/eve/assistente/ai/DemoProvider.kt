package app.eve.assistente.ai

import kotlinx.coroutines.delay
import org.json.JSONObject
import java.time.LocalDate

/**
 * IA de mentira, só para os testes automáticos no emulador (não precisa de internet nem de chave).
 * Agenda -> mostra a agenda de hoje. Vendas -> mostra as vendas de exemplo da semana. Senão, só responde.
 */
class DemoProvider : AiProvider {
    override val displayName = "Demonstração"

    override suspend fun reply(systemPrompt: String, history: List<ChatMessage>): String {
        delay(1500)
        val last = history.lastOrNull()?.text.orEmpty()
        val question = last.lowercase()
        val today = LocalDate.now()
        return when {
            last.startsWith("DADOS DO MERCADO LIVRE") -> {
                val total = Regex("""Total: (.+?)\.(?: Ticket|\n|$)""").find(last)?.groupValues?.get(1) ?: "algumas vendas"
                val top = Regex("""1\) \d+ un\. ([^(]+)\(""").find(last)?.groupValues?.get(1)?.trim()
                val fala = "Nos últimos 7 dias foram $total." + (top?.let { " O que mais saiu foi $it." } ?: "")
                JSONObject().put("fala", fala).put("humor", "animada").put("acoes", org.json.JSONArray()).toString()
            }
            "venda" in question || "vendi" in question ->
                """{"fala": "Vou ver suas vendas.", "acoes": [{"tipo": "mostrar_vendas", "desde": "${today.minusDays(6)}", "ate": "$today"}]}"""
            "agenda" in question ->
                """{"fala": "Aqui está a sua agenda de hoje.", "acoes": [{"tipo": "mostrar_agenda", "dia": "$today"}]}"""
            else ->
                """{"fala": "Tudo bem, Lucas! Mudei para as 18 horas, ou seja, 6 da tarde.", "acoes": []}"""
        }
    }
}
