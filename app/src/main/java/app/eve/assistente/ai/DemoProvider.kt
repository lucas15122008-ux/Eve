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
            // Lembretes e compromissos: criar, alterar e excluir (o id vem das listas nas instruções).
            "me lembra" in question ->
                """{"fala": "Combinado, vou te lembrar.", "acoes": [{"tipo": "criar_lembrete", "quando": "${java.time.LocalDateTime.now().plusHours(2).withSecond(0).withNano(0)}", "texto": "Tirar o lixo"}]}"""
            "muda o lembrete" in question -> {
                val id = Regex("""- id (\d+): .*Tirar o lixo""").find(systemPrompt)?.groupValues?.get(1) ?: "0"
                """{"fala": "Pronto, mudei o lembrete.", "humor": "piscadinha", "acoes": [{"tipo": "alterar_lembrete", "id": $id, "quando": "${today.plusDays(1)}T07:00", "texto": "Tirar o lixo antes de sair"}]}"""
            }
            "marca uma reuni" in question ->
                """{"fala": "Marquei a reunião para amanhã às 15.", "acoes": [{"tipo": "criar_evento", "titulo": "Reunião com fornecedor", "inicio": "${today.plusDays(1)}T15:00", "fim": "${today.plusDays(1)}T16:00"}]}"""
            "muda a reuni" in question -> {
                val id = Regex("""- id (\d+): [^\n]*Reunião com fornecedor""").find(systemPrompt)?.groupValues?.get(1) ?: "0"
                """{"fala": "Mudei a reunião para as 16 horas.", "acoes": [{"tipo": "alterar_evento", "id": $id, "inicio": "${today.plusDays(1)}T16:00", "local": "Loja"}]}"""
            }
            "desmarca a reuni" in question -> {
                val id = Regex("""- id (\d+): [^\n]*Reunião com fornecedor""").find(systemPrompt)?.groupValues?.get(1) ?: "0"
                """{"fala": "Pronto, desmarquei a reunião.", "acoes": [{"tipo": "excluir_evento", "id": $id}]}"""
            }
            "agenda" in question ->
                """{"fala": "Aqui está a sua agenda de hoje.", "acoes": [{"tipo": "mostrar_agenda", "dia": "$today"}]}"""
            else ->
                """{"fala": "Tudo bem, Lucas! Mudei para as 18 horas, ou seja, 6 da tarde.", "acoes": []}"""
        }
    }
}
