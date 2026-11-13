package app.eve.assistente.assistant

import android.content.Context
import app.eve.assistente.mercadolivre.DemoSalesSource
import app.eve.assistente.mercadolivre.McpClient
import app.eve.assistente.mercadolivre.McpException
import app.eve.assistente.mercadolivre.McpLoginNeeded
import app.eve.assistente.mercadolivre.MlPending
import app.eve.assistente.mercadolivre.SalesSource
import app.eve.assistente.mercadolivre.SalesSummary
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Uma linha das telas de agenda/lembretes. */
data class AgendaItem(val time: String, val title: String, val isReminder: Boolean, val detail: String? = null)

/** O que a Eve mostra no meio da tela enquanto o ícone dela vai para o canto. */
sealed class EveCard {
    data class Agenda(val title: String, val items: List<AgendaItem>) : EveCard()
    data class Reminders(val items: List<AgendaItem>) : EveCard()
    data class Done(val title: String, val item: AgendaItem) : EveCard()
    data class Sales(val summary: SalesSummary) : EveCard()
    data class Pending(val pending: MlPending) : EveCard()
}

/**
 * Resultado de uma resposta da IA depois de executar as ações.
 * [followUp] vem preenchido quando a Eve buscou dados (ex.: vendas) e a IA precisa ver os números
 * para responder: aí a pergunta volta para a IA com esses dados.
 */
data class BrainResult(
    val speech: String,
    val card: EveCard?,
    val rawForHistory: String,
    /** Humor que a IA escolheu para o rosto da Eve (ex.: "surpresa"), ou null. */
    val mood: String? = null,
    val followUp: String? = null,
)

/**
 * O "cérebro" da Eve: monta as instruções para a IA (com hora, lembretes e agenda)
 * e transforma a resposta da IA em fala + ações (criar lembrete, criar evento, mostrar agenda...).
 *
 * Funciona com qualquer provedor de IA, porque só depende de texto em JSON.
 */
class EveBrain(context: Context, private val salesSource: () -> SalesSource?) {
    private val ctx = context.applicationContext
    private val reminders = ReminderStore(ctx)
    private val calendar = CalendarRepo(ctx)
    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun systemPrompt(userName: String): String {
        val now = LocalDateTime.now(zone)
        val name = userName.ifBlank { "o usuário" }
        return """
Você é a Eve, assistente pessoal de $name, rodando no celular dele.
Fale sempre em português do Brasil, de forma natural, simpática e direta, como numa conversa por voz.

FORMATO OBRIGATÓRIO: responda SEMPRE e SOMENTE com um objeto JSON, sem nada antes ou depois:
{"fala": "o que você vai dizer em voz alta", "humor": "normal", "acoes": [ ... ]}

"fala" será lida em voz alta: sem markdown, listas, emojis ou símbolos. Prefira uma a três frases.
"humor" é a cara que você faz ao falar: normal, surpresa, preocupada, sonolenta, piscadinha, triste, animada ou confusa.
Use surpresa para novidades boas inesperadas, animada para conquistas e boas notícias, preocupada para atrasos e problemas,
triste para notícias ruins, confusa quando não entendeu o pedido, piscadinha quando terminou uma tarefa, sonolenta para boa noite. Na dúvida, normal.
"acoes" é uma lista (pode ser vazia) com estas opções:
- {"tipo": "criar_lembrete", "quando": "AAAA-MM-DDTHH:MM", "texto": "do que lembrar"}
- {"tipo": "alterar_lembrete", "id": número, "quando": "AAAA-MM-DDTHH:MM (só se mudar)", "texto": "só se mudar"}
- {"tipo": "cancelar_lembrete", "id": número}
- {"tipo": "criar_evento", "titulo": "...", "inicio": "AAAA-MM-DDTHH:MM", "fim": "AAAA-MM-DDTHH:MM", "local": "opcional"}
- {"tipo": "alterar_evento", "id": número, "titulo": "só se mudar", "inicio": "só se mudar", "fim": "só se mudar", "local": "só se mudar"}
- {"tipo": "excluir_evento", "id": número}
- {"tipo": "mostrar_agenda", "dia": "AAAA-MM-DD"}
- {"tipo": "mostrar_lembretes"}
- {"tipo": "mostrar_vendas", "desde": "AAAA-MM-DD", "ate": "AAAA-MM-DD"}
- {"tipo": "mostrar_pendencias"}

Regras:
- Lembrete é um aviso no celular ("me lembra de..."). Evento é um compromisso na agenda ("marca uma reunião...").
- Calcule datas e horas a partir de agora. "Daqui a 10 minutos" = agora + 10 minutos. Sem hora dita para um lembrete, pergunte a hora antes de criar.
- Evento sem hora de fim dura 1 hora.
- Quando perguntarem o que tem num dia, responda pelos dados abaixo e use "mostrar_agenda" para esse dia.
- Quando perguntarem pelos lembretes, use "mostrar_lembretes".
- Para alterar, cancelar ou excluir, use o id que aparece nas listas de lembretes e de agenda abaixo. Nunca invente um id.
- Ao alterar, mande só o que muda. Se mudar só a hora de início de um compromisso, não mande "fim": a Eve mantém a duração.
- "Desmarca", "cancela" ou "apaga" um compromisso = "excluir_evento". Só exclua quando o pedido for claro. Se houver mais de um que combine (ex.: duas reuniões), pergunte qual antes.
- Compromissos marcados como "se repete" ou "só leitura" a Eve não consegue mudar: diga para mudar pelo app de agenda.
- Nunca invente compromissos que não estão nos dados.
- Mercado Livre (loja JR Auto Peças): para perguntas sobre vendas, faturamento, quantos pedidos ou produtos mais vendidos, use "mostrar_vendas" com o período (as duas datas incluídas; "hoje" = desde e até hoje; "essa semana" = últimos 7 dias; no máximo 90 dias). Nessa hora diga só algo curto como "Vou ver suas vendas"; os números chegam numa próxima mensagem e aí você responde.
- Para perguntas sem resposta, mensagens de compradores, reclamações ou "o que tem para fazer no Mercado Livre", use "mostrar_pendencias" e diga só algo curto como "Vou olhar". Os números chegam numa próxima mensagem.
- Nunca invente números de vendas, perguntas, mensagens ou reclamações.
${salesStatus()}

Agora é ${weekdayDate(now.toLocalDate())}, ${now.format(HM)} (${now.format(ISO_MIN)}).

${remindersContext()}

${calendarContext(now.toLocalDate())}
        """.trimIndent()
    }

    private fun salesStatus(): String = when (salesSource()) {
        is DemoSalesSource -> "- O Mercado Livre está no modo de exemplo: os números são inventados. Sempre diga que são de exemplo."
        null -> "- O Mercado Livre ainda não está conectado na Eve: se pedirem vendas ou pendências, use a ação mesmo assim (a Eve explica como conectar)."
        else -> "- O Mercado Livre está conectado."
    }

    private fun remindersContext(): String {
        val list = reminders.all()
        if (list.isEmpty()) return "Lembretes pendentes: nenhum."
        return "Lembretes pendentes:\n" + list.joinToString("\n") { r ->
            val t = toLocal(r.atMillis)
            "- id ${r.id}: ${shortDate(t.toLocalDate())} ${t.format(HM)}: ${r.text}"
        }
    }

    private fun calendarContext(today: LocalDate): String {
        if (!calendar.canRead) return "Agenda: sem permissão para ler a agenda do celular."
        val from = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = today.plusDays(15).atStartOfDay(zone).toInstant().toEpochMilli()
        val events = calendar.events(from, to, limit = 60)
        if (events.isEmpty()) return "Agenda dos próximos 14 dias: nada marcado."
        return "Agenda dos próximos 14 dias:\n" + events.joinToString("\n") { e -> "- id ${e.id}: " + describe(e) }
    }

    private fun describe(e: CalendarEvent): String {
        val s = toLocal(e.startMillis)
        val time = if (e.allDay) "dia inteiro" else "${s.format(HM)} a ${toLocal(e.endMillis).format(HM)}"
        val flags = listOfNotNull(
            "se repete".takeIf { e.recurring },
            "só leitura".takeIf { !e.editable },
        ).joinToString(", ").let { if (it.isEmpty()) "" else " [$it]" }
        return "${shortDate(s.toLocalDate())} $time: ${e.title}" + (e.location?.let { " ($it)" } ?: "") + flags
    }

    /**
     * Lê a resposta da IA, executa as ações e devolve o que falar e o que mostrar.
     * [allowData] = false na segunda rodada (depois dos dados), para a IA não pedir os dados de novo.
     */
    suspend fun handle(rawReply: String, allowData: Boolean = true): BrainResult {
        val json = extractJson(rawReply)
            ?: return BrainResult(speech = cleanText(rawReply), card = null, rawForHistory = rawReply)

        val problems = mutableListOf<String>()
        var card: EveCard? = null
        var followUp: String? = null
        var dataFailed = false
        val actions = json.optJSONArray("acoes") ?: JSONArray()
        for (i in 0 until actions.length()) {
            val a = actions.optJSONObject(i) ?: continue
            try {
                val tipo = a.optString("tipo")
                if (tipo == "mostrar_vendas" || tipo == "mostrar_pendencias") {
                    if (!allowData || followUp != null) continue
                    val data = if (tipo == "mostrar_vendas") sales(a, problems)?.let { EveCard.Sales(it) to it.forAi() }
                    else fetch(problems) { it.pending() }?.let { EveCard.Pending(it) to it.forAi() }
                    if (data == null) {
                        dataFailed = true
                        continue
                    }
                    card = data.first
                    followUp = data.second
                } else {
                    runAction(a, problems)?.let { card = it }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                problems += "Não consegui fazer uma das coisas que você pediu."
            }
        }

        val speech = buildString {
            // Se os dados não vieram, o "vou ver suas vendas" da IA não faz sentido: fala só o problema.
            if (!dataFailed) append(cleanText(json.optString("fala")))
            problems.distinct().forEach { append(" ").append(it) }
        }.trim().ifEmpty { "Pronto." }
        val mood = json.optString("humor").takeIf { it.isNotBlank() }
        return BrainResult(speech, card, json.toString(), mood, followUp)
    }

    /** Busca as vendas do período pedido no Mercado Livre. */
    private suspend fun sales(a: JSONObject, problems: MutableList<String>): SalesSummary? {
        val today = LocalDate.now(zone)
        fun day(key: String) = runCatching { LocalDate.parse(a.optString(key).take(10)) }.getOrNull()
        val to = (day("ate") ?: today).coerceAtMost(today)
        val from = (day("desde") ?: to).coerceAtMost(to).coerceAtLeast(to.minusDays(89))
        return fetch(problems) { it.summary(from, to) }
    }

    /** Busca dados no Mercado Livre; se não der, explica o porquê em [problems] e devolve null. */
    private suspend fun <T> fetch(problems: MutableList<String>, block: suspend (SalesSource) -> T): T? {
        val source = salesSource()
        if (source == null) {
            problems += "O Mercado Livre ainda não está conectado. Coloque o endereço do servidor JR Mercado Livre nas configurações da Eve. $SAMPLE_HINT"
            return null
        }
        return try {
            block(source)
        } catch (e: McpLoginNeeded) {
            problems += (e.message ?: McpClient.NEED_LOGIN) + " " + SAMPLE_HINT
            null
        } catch (e: McpException) {
            problems += e.message ?: "Não consegui ver o Mercado Livre agora."
            null
        } catch (e: java.io.IOException) {
            problems += "Não consegui falar com o servidor do Mercado Livre. Confira a internet e o endereço nas configurações."
            null
        }
    }

    /** Se a IA falhar na segunda rodada, a Eve fala os números ela mesma. */
    fun fallbackSalesSpeech(s: SalesSummary): String {
        val period = when {
            s.periodLabel == "Hoje" || s.periodLabel == "Ontem" -> s.periodLabel
            s.from == s.to -> "No dia ${s.periodLabel}"
            else -> "De ${s.periodLabel}"
        }
        val prefix = if (s.sample) "Vendas de exemplo, não são reais. " else ""
        if (s.count == 0) return "$prefix$period não teve nenhuma venda paga."
        return "$prefix$period foram ${s.count} vendas, somando ${SalesSummary.money(s.revenue)}."
    }

    /** Se a IA falhar na segunda rodada, a Eve fala as pendências ela mesma. */
    fun fallbackPendingSpeech(p: MlPending): String {
        fun n(v: Int?, one: String, many: String) = when (v) {
            null -> null
            0 -> "nenhuma $one"
            1 -> "1 $one"
            else -> "$v $many"
        }
        val parts = listOfNotNull(
            n(p.questions, "pergunta sem resposta", "perguntas sem resposta"),
            n(p.messages, "mensagem não lida", "mensagens não lidas"),
            n(p.claims, "reclamação aberta", "reclamações abertas"),
        )
        val prefix = if (p.sample) "Pendências de exemplo, não são reais. " else ""
        if (parts.isEmpty()) return prefix + "Não consegui ler as pendências do Mercado Livre agora."
        return prefix + "No Mercado Livre você tem " + when (parts.size) {
            1 -> parts[0]
            else -> parts.dropLast(1).joinToString(", ") + " e " + parts.last()
        } + "."
    }

    private fun runAction(a: JSONObject, problems: MutableList<String>): EveCard? {
        return when (a.optString("tipo")) {
            "criar_lembrete" -> {
                val at = parseDateTime(a.optString("quando"))
                val text = a.optString("texto").trim()
                if (at == null || text.isEmpty()) {
                    problems += "Não entendi a hora do lembrete."
                    return null
                }
                val millis = at.atZone(zone).toInstant().toEpochMilli()
                if (millis <= System.currentTimeMillis()) {
                    problems += "Esse horário já passou, então não criei o lembrete."
                    return null
                }
                val r = reminders.add(millis, text)
                ReminderScheduler.schedule(ctx, r)
                EveCard.Done("Lembrete criado", AgendaItem(whenLabel(at), text, isReminder = true))
            }
            "alterar_lembrete" -> {
                val id = a.optInt("id", -1)
                val newText = a.optString("texto").trim().ifEmpty { null }
                val rawWhen = a.optString("quando").trim()
                val at = if (rawWhen.isEmpty()) null else parseDateTime(rawWhen)
                if (rawWhen.isNotEmpty() && at == null) {
                    problems += "Não entendi a nova hora do lembrete."
                    return null
                }
                val millis = at?.atZone(zone)?.toInstant()?.toEpochMilli()
                if (millis != null && millis <= System.currentTimeMillis()) {
                    problems += "Esse horário já passou, então não mudei o lembrete."
                    return null
                }
                if (millis == null && newText == null) {
                    problems += "Não entendi o que mudar no lembrete."
                    return null
                }
                val r = reminders.update(id, millis, newText)
                if (r == null) {
                    problems += "Não achei esse lembrete."
                    null
                } else {
                    // Mesmo id = o Android troca o alarme antigo pelo novo.
                    ReminderScheduler.schedule(ctx, r)
                    EveCard.Done("Lembrete alterado", AgendaItem(whenLabel(toLocal(r.atMillis)), r.text, isReminder = true))
                }
            }
            "cancelar_lembrete", "excluir_lembrete" -> {
                val removed = reminders.remove(a.optInt("id", -1))
                if (removed == null) {
                    problems += "Não achei esse lembrete."
                    null
                } else {
                    ReminderScheduler.cancel(ctx, removed)
                    EveCard.Reminders(reminderItems())
                }
            }
            "criar_evento" -> {
                val start = parseDateTime(a.optString("inicio"))
                val title = a.optString("titulo").trim()
                if (start == null || title.isEmpty()) {
                    problems += "Não entendi o dia ou a hora do compromisso."
                    return null
                }
                val end = parseDateTime(a.optString("fim"))?.takeIf { it.isAfter(start) } ?: start.plusHours(1)
                val location = a.optString("local").trim().ifEmpty { null }
                val ok = calendar.insert(
                    title,
                    start.atZone(zone).toInstant().toEpochMilli(),
                    end.atZone(zone).toInstant().toEpochMilli(),
                    location,
                )
                if (!ok) {
                    problems += if (!calendar.canWrite) "Preciso da permissão da agenda para marcar compromissos."
                    else "Não achei uma agenda no celular para salvar o compromisso."
                    null
                } else {
                    EveCard.Done(
                        "Compromisso marcado",
                        AgendaItem(whenLabel(start), title, isReminder = false, detail = location),
                    )
                }
            }
            "alterar_evento" -> {
                val id = a.optLong("id", -1)
                fun time(key: String): Long? = parseDateTime(a.optString(key))?.atZone(zone)?.toInstant()?.toEpochMilli()
                val start = time("inicio")
                val end = time("fim")
                val title = a.optString("titulo").trim().ifEmpty { null }
                val location = a.optString("local").trim().ifEmpty { null }
                if (start == null && end == null && title == null && location == null) {
                    problems += "Não entendi o que mudar no compromisso."
                    return null
                }
                val (result, event) = calendar.update(id, title, start, end, location)
                if (result != EditResult.OK || event == null) {
                    problems += editProblem(result, "alterar")
                    null
                } else {
                    EveCard.Done("Compromisso alterado", eventItem(event))
                }
            }
            "excluir_evento" -> {
                val (result, event) = calendar.delete(a.optLong("id", -1))
                if (result != EditResult.OK || event == null) {
                    problems += editProblem(result, "excluir")
                    null
                } else {
                    EveCard.Done("Compromisso excluído", eventItem(event))
                }
            }
            "mostrar_agenda" -> {
                val day = runCatching { LocalDate.parse(a.optString("dia").take(10)) }.getOrNull()
                    ?: LocalDate.now(zone)
                agendaCard(day)
            }
            "mostrar_lembretes" -> EveCard.Reminders(reminderItems())
            else -> null
        }
    }

    private fun eventItem(e: CalendarEvent): AgendaItem {
        val label = if (e.allDay) whenLabel(toLocal(e.startMillis)).substringBefore(",") + ", dia todo"
        else whenLabel(toLocal(e.startMillis))
        return AgendaItem(label, e.title, isReminder = false, detail = e.location)
    }

    private fun editProblem(r: EditResult, verb: String): String = when (r) {
        EditResult.NO_PERMISSION -> "Preciso da permissão da agenda para $verb compromissos."
        EditResult.NOT_FOUND -> "Não achei esse compromisso na agenda."
        EditResult.RECURRING -> "Esse compromisso se repete, então é melhor $verb pelo app de agenda."
        EditResult.READ_ONLY -> "Essa agenda é só de leitura, não consigo $verb esse compromisso."
        else -> "Não consegui $verb o compromisso."
    }

    private fun agendaCard(day: LocalDate): EveCard.Agenda {
        val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val items = mutableListOf<Pair<Long, AgendaItem>>()
        calendar.events(from, to).forEach { e ->
            val time = if (e.allDay) "Dia todo" else toLocal(e.startMillis).format(HM)
            items += e.startMillis to AgendaItem(time, e.title, isReminder = false, detail = e.location)
        }
        reminders.all().filter { it.atMillis in from until to }.forEach { r ->
            items += r.atMillis to AgendaItem(toLocal(r.atMillis).format(HM), r.text, isReminder = true)
        }
        val title = weekdayDate(day).replaceFirstChar { it.uppercase() }
        return EveCard.Agenda(title, items.sortedBy { it.first }.map { it.second })
    }

    private fun reminderItems(): List<AgendaItem> = reminders.all().map { r ->
        AgendaItem(whenLabel(toLocal(r.atMillis)), r.text, isReminder = true)
    }

    // ---------- ajudantes ----------

    private fun toLocal(millis: Long): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone)

    private fun parseDateTime(s: String?): LocalDateTime? {
        if (s.isNullOrBlank()) return null
        val clean = s.trim().replace(' ', 'T').take(16)
        return runCatching { LocalDateTime.parse(clean) }.getOrNull()
    }

    private fun whenLabel(t: LocalDateTime): String {
        val today = LocalDate.now(zone)
        val day = when (t.toLocalDate()) {
            today -> "Hoje"
            today.plusDays(1) -> "Amanhã"
            else -> shortDate(t.toLocalDate()).replaceFirstChar { it.uppercase() }
        }
        return "$day, ${t.format(HM)}"
    }

    private fun weekdayDate(d: LocalDate) = d.format(DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM", PT_BR))
    private fun shortDate(d: LocalDate) = d.format(DateTimeFormatter.ofPattern("EEE dd/MM", PT_BR))

    companion object {
        private val PT_BR: Locale = Locale.forLanguageTag("pt-BR")
        private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        private val ISO_MIN: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

        /** Enquanto o login não dá certo, dá para ver as telas com números inventados. */
        const val SAMPLE_HINT = "Enquanto isso, dá para ligar \"Usar vendas de exemplo\" nas configurações para ver como fica."

        /** Acha o JSON na resposta, mesmo que a IA tenha colocado texto ou ``` em volta. */
        fun extractJson(raw: String): JSONObject? {
            val text = raw.replace(Regex("(?s)<think>.*?</think>"), "")
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            return runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull()
                ?.takeIf { it.has("fala") || it.has("acoes") }
        }

        /** Tira símbolos que soariam estranhos na voz. */
        fun cleanText(s: String): String = s
            .replace(Regex("(?s)<think>.*?</think>"), "")
            .replace(Regex("[*#_`]"), "")
            .trim()
    }
}
