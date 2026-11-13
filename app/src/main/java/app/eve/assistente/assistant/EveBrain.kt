package app.eve.assistente.assistant

import android.content.Context
import app.eve.assistente.mercadolivre.DemoSalesSource
import app.eve.assistente.mercadolivre.McpClient
import app.eve.assistente.mercadolivre.McpException
import app.eve.assistente.mercadolivre.McpLoginNeeded
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
- {"tipo": "cancelar_lembrete", "id": número}
- {"tipo": "criar_evento", "titulo": "...", "inicio": "AAAA-MM-DDTHH:MM", "fim": "AAAA-MM-DDTHH:MM", "local": "opcional"}
- {"tipo": "mostrar_agenda", "dia": "AAAA-MM-DD"}
- {"tipo": "mostrar_lembretes"}
- {"tipo": "mostrar_vendas", "desde": "AAAA-MM-DD", "ate": "AAAA-MM-DD"}

Regras:
- Lembrete é um aviso no celular ("me lembra de..."). Evento é um compromisso na agenda ("marca uma reunião...").
- Calcule datas e horas a partir de agora. "Daqui a 10 minutos" = agora + 10 minutos. Sem hora dita para um lembrete, pergunte a hora antes de criar.
- Evento sem hora de fim dura 1 hora.
- Quando perguntarem o que tem num dia, responda pelos dados abaixo e use "mostrar_agenda" para esse dia.
- Quando perguntarem pelos lembretes, use "mostrar_lembretes".
- Para cancelar, use o id que aparece na lista de lembretes abaixo.
- Nunca invente compromissos que não estão nos dados.
- Mercado Livre (loja JR Auto Peças): para perguntas sobre vendas, faturamento, quantos pedidos ou produtos mais vendidos, use "mostrar_vendas" com o período (as duas datas incluídas; "hoje" = desde e até hoje; "essa semana" = últimos 7 dias; no máximo 90 dias). Nessa hora diga só algo curto como "Vou ver suas vendas"; os números chegam numa próxima mensagem e aí você responde.
- Nunca invente números de vendas.
${salesStatus()}

Agora é ${weekdayDate(now.toLocalDate())}, ${now.format(HM)} (${now.format(ISO_MIN)}).

${remindersContext()}

${calendarContext(now.toLocalDate())}
        """.trimIndent()
    }

    private fun salesStatus(): String = when (salesSource()) {
        is DemoSalesSource -> "- O Mercado Livre está no modo de exemplo: os números são inventados. Sempre diga que são vendas de exemplo."
        null -> "- O Mercado Livre ainda não está conectado na Eve: se pedirem vendas, use \"mostrar_vendas\" mesmo assim (a Eve explica como conectar)."
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
        val to = today.plusDays(8).atStartOfDay(zone).toInstant().toEpochMilli()
        val events = calendar.events(from, to, limit = 40)
        if (events.isEmpty()) return "Agenda dos próximos 7 dias: nada marcado."
        return "Agenda dos próximos 7 dias:\n" + events.joinToString("\n") { e -> "- " + describe(e) }
    }

    private fun describe(e: CalendarEvent): String {
        val s = toLocal(e.startMillis)
        val time = if (e.allDay) "dia inteiro" else "${s.format(HM)} a ${toLocal(e.endMillis).format(HM)}"
        return "${shortDate(s.toLocalDate())} $time: ${e.title}" + (e.location?.let { " ($it)" } ?: "")
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
        var salesFailed = false
        val actions = json.optJSONArray("acoes") ?: JSONArray()
        for (i in 0 until actions.length()) {
            val a = actions.optJSONObject(i) ?: continue
            try {
                if (a.optString("tipo") == "mostrar_vendas") {
                    if (!allowData || followUp != null) continue
                    val summary = sales(a, problems)
                    if (summary == null) {
                        salesFailed = true
                        continue
                    }
                    card = EveCard.Sales(summary)
                    followUp = summary.forAi()
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
            // Se as vendas não vieram, o "vou ver suas vendas" da IA não faz sentido: fala só o problema.
            if (!salesFailed) append(cleanText(json.optString("fala")))
            problems.distinct().forEach { append(" ").append(it) }
        }.trim().ifEmpty { "Pronto." }
        val mood = json.optString("humor").takeIf { it.isNotBlank() }
        return BrainResult(speech, card, json.toString(), mood, followUp)
    }

    /** Busca as vendas do período pedido no Mercado Livre. */
    private suspend fun sales(a: JSONObject, problems: MutableList<String>): SalesSummary? {
        val source = salesSource()
        if (source == null) {
            problems += "O Mercado Livre ainda não está conectado. Coloque o endereço do servidor JR Mercado Livre nas configurações da Eve. $SAMPLE_HINT"
            return null
        }
        val today = LocalDate.now(zone)
        fun day(key: String) = runCatching { LocalDate.parse(a.optString(key).take(10)) }.getOrNull()
        val to = (day("ate") ?: today).coerceAtMost(today)
        val from = (day("desde") ?: to).coerceAtMost(to).coerceAtLeast(to.minusDays(89))
        return try {
            source.summary(from, to)
        } catch (e: McpLoginNeeded) {
            problems += (e.message ?: McpClient.NEED_LOGIN) + " " + SAMPLE_HINT
            null
        } catch (e: McpException) {
            problems += e.message ?: "Não consegui ver as vendas agora."
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
            "cancelar_lembrete" -> {
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
            "mostrar_agenda" -> {
                val day = runCatching { LocalDate.parse(a.optString("dia").take(10)) }.getOrNull()
                    ?: LocalDate.now(zone)
                agendaCard(day)
            }
            "mostrar_lembretes" -> EveCard.Reminders(reminderItems())
            else -> null
        }
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
