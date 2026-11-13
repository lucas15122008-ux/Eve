package app.eve.assistente.mercadolivre

import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * O que está esperando você no Mercado Livre: perguntas sem resposta, mensagens não lidas e
 * reclamações abertas. Um número null quer dizer "não consegui ler".
 * Para a IA vão só os números e os nomes dos seus anúncios, nunca o texto ou o nome do comprador.
 */
data class MlPending(
    val questions: Int?,
    /** Quando chegou a pergunta mais antiga ainda sem resposta. */
    val oldestQuestion: LocalDateTime?,
    /** Os anúncios que têm pergunta esperando (no máximo 3). */
    val questionItems: List<String>,
    val messages: Int?,
    val claims: Int?,
    /** true quando são pendências de exemplo (inventadas). */
    val sample: Boolean = false,
) {
    /** "a mais antiga há 3 horas", ou null se não se sabe. */
    fun oldestLabel(now: LocalDateTime = LocalDateTime.now()): String? =
        oldestQuestion?.let { "a mais antiga há ${ago(it, now)}" }

    /** Texto para a IA responder a pergunta. */
    fun forAi(now: LocalDateTime = LocalDateTime.now()): String = buildString {
        if (sample) appendLine("ATENÇÃO: SÃO PENDÊNCIAS DE EXEMPLO, INVENTADAS, NÃO AS REAIS. Diga isso na resposta.")
        appendLine("PENDÊNCIAS DO MERCADO LIVRE (agora):")
        val q = questions
        if (q == null) appendLine("Perguntas sem resposta: não consegui ler.")
        else appendLine("Perguntas sem resposta: $q" + listOfNotNull(
            oldestLabel(now)?.takeIf { q > 0 },
            questionItems.takeIf { q > 0 && it.isNotEmpty() }?.let { "anúncios: " + it.joinToString(", ") },
        ).joinToString("; ").let { if (it.isEmpty()) "." else " ($it)." })
        appendLine("Mensagens de compradores não lidas: " + (messages?.toString() ?: "não consegui ler") + ".")
        appendLine("Reclamações abertas: " + (claims?.toString() ?: "não consegui ler") + ".")
        append(
            "Responda à pergunta do usuário só com esses dados, no mesmo formato JSON, com \"acoes\" vazia. " +
                "A Eve ainda não responde perguntas nem mensagens dos compradores: se pedirem, diga para responder pelo app do Mercado Livre."
        )
    }

    companion object {
        /** "5 minutos", "3 horas", "2 dias". */
        fun ago(from: LocalDateTime, now: LocalDateTime): String {
            val min = Duration.between(from, now).toMinutes().coerceAtLeast(0)
            return when {
                min < 1 -> "menos de um minuto"
                min < 60 -> plural(min, "minuto", "minutos")
                min < 48 * 60 -> plural(min / 60, "hora", "horas")
                else -> plural(min / (24 * 60), "dia", "dias")
            }
        }

        private fun plural(n: Long, one: String, many: String) = "$n ${if (n == 1L) one else many}"
    }
}

/**
 * Lê as respostas das ferramentas de perguntas, mensagens e reclamações sem depender do formato
 * exato: conta a lista que vier (ou usa o "total"), e procura datas e títulos pelos nomes comuns.
 */
object PendingParser {
    class Parsed(val count: Int?, val items: List<JSONObject>)

    private val NOTHING = Regex("""(?i)\b(nenhum|nenhuma|não há|nao ha|sem (perguntas|mensagens|reclama))""")
    private val DATE_KEYS = listOf("data", "date_created", "date", "data_criacao", "criada_em", "created", "last_updated")
    private val TITLE_KEYS = listOf("titulo", "title", "item_title", "anuncio_titulo", "produto")

    /**
     * [status]: só conta os itens com esse status, quando os itens trazem o campo "status"
     * (ex.: "UNANSWERED" nas perguntas), caso o servidor tenha mandado as respondidas junto.
     */
    fun parse(text: String, status: String? = null): Parsed {
        val t = text.trim()
        val (total, list) = when {
            t.startsWith("[") -> null to runCatching { JSONArray(t) }.getOrNull()
            t.startsWith("{") -> {
                val o = runCatching { JSONObject(t) }.getOrNull() ?: return Parsed(null, emptyList())
                val total = o.optInt("total", -1).takeIf { it >= 0 }
                    ?: o.optJSONObject("paging")?.optInt("total", -1)?.takeIf { it >= 0 }
                total to firstArray(o)
            }
            NOTHING.containsMatchIn(t) -> return Parsed(0, emptyList())
            else -> return Parsed(null, emptyList())
        }
        val items = (0 until (list?.length() ?: 0)).mapNotNull { list!!.optJSONObject(it) }
        if (status != null && items.isNotEmpty() && items.all { it.has("status") }) {
            val kept = items.filter { it.optString("status").equals(status, ignoreCase = true) }
            // Se o total do servidor é maior que a lista, ele cortou: usa o total quando nada foi filtrado.
            return Parsed(if (kept.size == items.size) (total ?: kept.size) else kept.size, kept)
        }
        return Parsed(total ?: list?.length(), items)
    }

    /** A lista principal da resposta: a primeira que achar, de preferência com um nome conhecido. */
    private fun firstArray(o: JSONObject): JSONArray? {
        for (k in listOf("perguntas", "questions", "mensagens", "conversas", "messages", "reclamacoes", "claims", "results", "data")) {
            o.optJSONArray(k)?.let { return it }
        }
        return o.keys().asSequence().firstNotNullOfOrNull { o.optJSONArray(it) }
    }

    fun date(item: JSONObject, zone: ZoneId): LocalDateTime? = DATE_KEYS.firstNotNullOfOrNull { k ->
        val s = item.optString(k).ifBlank { null } ?: return@firstNotNullOfOrNull null
        runCatching { OffsetDateTime.parse(s).atZoneSameInstant(zone).toLocalDateTime() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(s.take(19)) }.getOrNull()
    }

    /** Título do anúncio, se a resposta trouxer (às vezes vem dentro de "anuncio" ou "item"). */
    fun title(item: JSONObject): String? {
        TITLE_KEYS.firstNotNullOfOrNull { item.optString(it).ifBlank { null } }?.let { return it.trim() }
        for (k in listOf("anuncio", "item")) {
            item.optJSONObject(k)?.let { return title(it) }
            item.optString(k).takeIf { it.isNotBlank() && !it.trim().startsWith("MLB") }?.let { return it.trim() }
        }
        return null
    }
}
