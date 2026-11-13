package app.eve.assistente.ai

import kotlinx.coroutines.delay
import org.json.JSONObject
import java.time.LocalDate

/**
 * IA de mentira, só para os testes automáticos no emulador (não precisa de internet nem de chave).
 * Agenda -> mostra a agenda de hoje. Vendas -> mostra as vendas de exemplo da semana.
 * Perguntas/mensagens/reclamações -> mostra as pendências de exemplo. Senão, só responde.
 */
class DemoProvider : AiProvider {
    override val displayName = "Demonstração"

    override suspend fun reply(systemPrompt: String, history: List<ChatMessage>): String {
        delay(1500)
        val last = history.lastOrNull()?.text.orEmpty()
        val question = last.lowercase()
        val today = LocalDate.now()
        return when {
            "DADOS DO MERCADO LIVRE" in last -> {
                val total = Regex("""Total: (.+?)\.(?: Ticket|\n|$)""").find(last)?.groupValues?.get(1) ?: "algumas vendas"
                val top = Regex("""1\) \d+ un\. ([^(]+)\(""").find(last)?.groupValues?.get(1)?.trim()
                val sample = if ("VENDAS DE EXEMPLO" in last) "São vendas de exemplo: n" else "N"
                val fala = "${sample}os últimos 7 dias foram $total." + (top?.let { " O que mais saiu foi $it." } ?: "")
                JSONObject().put("fala", fala).put("humor", "animada").put("acoes", org.json.JSONArray()).toString()
            }
            "PENDÊNCIAS DO MERCADO LIVRE" in last -> {
                val q = Regex("""Perguntas sem resposta: (\d+)""").find(last)?.groupValues?.get(1) ?: "algumas"
                val m = Regex("""não lidas: (\d+)""").find(last)?.groupValues?.get(1) ?: "algumas"
                val sample = if ("DE EXEMPLO" in last) "São pendências de exemplo: v" else "V"
                val fala = "${sample}ocê tem $q perguntas sem resposta e $m mensagem para ler. Nenhuma reclamação aberta."
                JSONObject().put("fala", fala).put("humor", "preocupada").put("acoes", org.json.JSONArray()).toString()
            }
            "pergunta" in question || "pendência" in question || "reclama" in question || "mensage" in question ->
                """{"fala": "Vou olhar.", "acoes": [{"tipo": "mostrar_pendencias"}]}"""
            "venda" in question || "vendi" in question ->
                """{"fala": "Vou ver suas vendas.", "acoes": [{"tipo": "mostrar_vendas", "desde": "${today.minusDays(6)}", "ate": "$today"}]}"""
            "agenda" in question ->
                """{"fala": "Aqui está a sua agenda de hoje.", "acoes": [{"tipo": "mostrar_agenda", "dia": "$today"}]}"""
            else ->
                """{"fala": "Tudo bem, Lucas! Mudei para as 18 horas, ou seja, 6 da tarde.", "acoes": []}"""
        }
    }
}
