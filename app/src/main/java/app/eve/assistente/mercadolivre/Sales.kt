package app.eve.assistente.mercadolivre

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.random.Random

/** Uma venda, já sem os dados do comprador (eles nunca saem daqui). */
data class Order(val id: Long, val at: LocalDateTime, val total: Double, val items: List<OrderItem>)
data class OrderItem(val quantity: Int, val title: String)

/** Uma barra do gráfico: um dia (ou um pedaço do dia). */
data class SalesBar(val label: String, val revenue: Double, val count: Int)
data class TopProduct(val title: String, val quantity: Int, val revenue: Double)

/** O resumo que vira gráfico na tela e números para a IA. */
data class SalesSummary(
    val from: LocalDate,
    val to: LocalDate,
    val bars: List<SalesBar>,
    val count: Int,
    val revenue: Double,
    val top: List<TopProduct>,
    /** true quando eram vendas demais e só as mais recentes foram somadas. */
    val partial: Boolean,
    /** true quando são vendas de exemplo (inventadas), não as do Mercado Livre de verdade. */
    val sample: Boolean = false,
) {
    val periodLabel: String
        get() = when {
            from == to && from == LocalDate.now() -> "Hoje"
            from == to && from == LocalDate.now().minusDays(1) -> "Ontem"
            from == to -> from.format(DAY_MONTH)
            else -> "${from.format(DAY_MONTH)} a ${to.format(DAY_MONTH)}"
        }

    /** Texto para a IA responder a pergunta. Só números e produtos, sem compradores. */
    fun forAi(): String = buildString {
        if (sample) appendLine("ATENÇÃO: SÃO VENDAS DE EXEMPLO, INVENTADAS, NÃO AS VENDAS REAIS. Diga isso na resposta.")
        appendLine("DADOS DO MERCADO LIVRE (vendas pagas, ${periodLabel.lowercase()}, de ${from.format(ISO)} a ${to.format(ISO)}):")
        appendLine("Total: $count vendas, ${money(revenue)}." + if (count > 0) " Ticket médio ${money(revenue / count)}." else "")
        if (partial) appendLine("Atenção: eram vendas demais; somei só as $count mais recentes do período.")
        if (bars.size > 1) {
            appendLine("Por período: " + bars.joinToString("; ") { "${it.label}: ${it.count} vendas, ${money(it.revenue)}" })
        }
        if (top.isNotEmpty()) {
            appendLine("Produtos mais vendidos: " + top.mapIndexed { i, p ->
                "${i + 1}) ${p.quantity} un. ${p.title} (${money(p.revenue)})"
            }.joinToString("; "))
        }
        append("Responda à pergunta do usuário só com esses dados, no mesmo formato JSON, com \"acoes\" vazia.")
    }

    companion object {
        private val PT_BR: Locale = Locale.forLanguageTag("pt-BR")
        private val DAY_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM")
        private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

        fun money(v: Double): String = NumberFormat.getCurrencyInstance(PT_BR).format(v).replace(' ', ' ')

        /** Soma as vendas por dia (ou por horário, se for um dia só) e acha os mais vendidos. */
        fun of(orders: List<Order>, from: LocalDate, to: LocalDate, partial: Boolean, sample: Boolean = false): SalesSummary {
            val days = ChronoUnit.DAYS.between(from, to).toInt() + 1
            val bars = when {
                days == 1 -> (0 until 24 step 3).map { h ->
                    val inBlock = orders.filter { it.at.hour in h until h + 3 }
                    SalesBar("${h}h", inBlock.sumOf { it.total }, inBlock.size)
                }
                days <= 31 -> (0 until days).map { i ->
                    val d = from.plusDays(i.toLong())
                    val inDay = orders.filter { it.at.toLocalDate() == d }
                    val label = if (days <= 7) d.format(DateTimeFormatter.ofPattern("EEE dd", PT_BR)).replace(".", "")
                    else d.dayOfMonth.toString()
                    SalesBar(label, inDay.sumOf { it.total }, inDay.size)
                }
                else -> (0 until days step 7).map { i ->
                    val start = from.plusDays(i.toLong())
                    val end = minOf(start.plusDays(6), to)
                    val inWeek = orders.filter { it.at.toLocalDate() in start..end }
                    SalesBar(start.format(DAY_MONTH), inWeek.sumOf { it.total }, inWeek.size)
                }
            }
            val top = orders.flatMap { o ->
                val units = o.items.sumOf { it.quantity }.coerceAtLeast(1)
                o.items.map { it to o.total * it.quantity / units }
            }
                .groupBy { it.first.title }
                .map { (title, list) -> TopProduct(title, list.sumOf { it.first.quantity }, list.sumOf { it.second }) }
                .sortedWith(compareByDescending<TopProduct> { it.quantity }.thenByDescending { it.revenue })
                .take(5)
            return SalesSummary(from, to, bars, orders.size, orders.sumOf { it.total }, top, partial, sample)
        }
    }
}

/** De onde vêm os dados do Mercado Livre: o servidor de verdade ou os dados de exemplo. */
interface SalesSource {
    suspend fun summary(from: LocalDate, to: LocalDate): SalesSummary

    /** Perguntas sem resposta, mensagens não lidas e reclamações abertas. */
    suspend fun pending(): MlPending
}

/** Vendas de verdade, pelo servidor MCP "JR Mercado Livre" (ferramenta ml_vendas). */
class McpSalesSource(private val client: McpClient, private val zone: ZoneId = ZoneId.systemDefault()) : SalesSource {

    override suspend fun summary(from: LocalDate, to: LocalDate): SalesSummary = coroutineScope {
        val since = from.atStartOfDay(zone).toOffsetDateTime().format(ML_DATE)
        val until = to.plusDays(1).atStartOfDay(zone).minusNanos(1_000_000).toOffsetDateTime().format(ML_DATE)

        suspend fun page(offset: Int): Pair<Int, List<Order>> {
            val args = JSONObject()
                .put("desde", since).put("ate", until).put("status", "paid")
                .put("limit", PAGE).put("offset", offset)
            val text = client.callTool("ml_vendas", args)
            val json = runCatching { JSONObject(text) }.getOrElse {
                throw McpException("O servidor do Mercado Livre devolveu as vendas num formato que eu não entendi.")
            }
            return json.optInt("total", -1) to parseOrders(json)
        }

        val (total, first) = page(0)
        val orders = first.toMutableList()
        var partial = false
        if (total > PAGE) {
            // Já sabemos quantas são: busca o resto em paralelo, 4 de cada vez.
            val wanted = minOf(total, MAX_ORDERS)
            partial = total > MAX_ORDERS
            val gate = Semaphore(4)
            val rest = (PAGE until wanted step PAGE).map { offset ->
                async { gate.withPermit { page(offset).second } }
            }.awaitAll()
            rest.forEach { orders += it }
        } else if (total < 0 && first.size == PAGE) {
            // O servidor não disse o total: vai página por página.
            var offset = PAGE
            while (offset < MAX_ORDERS) {
                val next = page(offset).second
                orders += next
                if (next.size < PAGE) break
                offset += PAGE
            }
            partial = offset >= MAX_ORDERS
        }
        SalesSummary.of(orders.distinctBy { it.id }, from, to, partial)
    }

    override suspend fun pending(): MlPending = coroutineScope {
        val questions = async { tool("ml_perguntas", JSONObject().put("status", "UNANSWERED"))?.let { PendingParser.parse(it, "UNANSWERED") } }
        val messages = async { tool("ml_mensagens_nao_lidas", JSONObject())?.let { PendingParser.parse(it) } }
        val claims = async { tool("ml_reclamacoes", JSONObject().put("status", "opened"))?.let { PendingParser.parse(it, "opened") } }
        val q = questions.await()
        val m = messages.await()
        val c = claims.await()
        if (q?.count == null && m?.count == null && c?.count == null) {
            throw McpException("Não consegui ler as perguntas, mensagens e reclamações do Mercado Livre agora. Tente de novo em instantes.")
        }
        val items = q?.items.orEmpty()
        MlPending(
            questions = q?.count,
            oldestQuestion = items.mapNotNull { PendingParser.date(it, zone) }.minOrNull(),
            questionItems = items.mapNotNull { PendingParser.title(it) }.distinct().take(3),
            messages = m?.count,
            claims = c?.count,
        )
    }

    /**
     * Chama uma ferramenta e devolve o texto, ou null se ela deu erro. Se o servidor recusar o
     * filtro (ex.: "status"), tenta de novo sem nada. Falta de login sobe, para a Eve avisar.
     */
    private suspend fun tool(name: String, args: JSONObject): String? = try {
        client.callTool(name, args)
    } catch (e: McpLoginNeeded) {
        throw e
    } catch (e: McpException) {
        if (args.length() == 0) null
        else try { client.callTool(name, JSONObject()) } catch (e2: McpLoginNeeded) { throw e2 } catch (e2: McpException) { null }
    }

    private fun parseOrders(json: JSONObject): List<Order> {
        val list = json.optJSONArray("vendas") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val v = list.optJSONObject(i) ?: return@mapNotNull null
            val at = runCatching { OffsetDateTime.parse(v.optString("data")) }.getOrNull()
                ?.atZoneSameInstant(zone)?.toLocalDateTime() ?: return@mapNotNull null
            val itens = v.optJSONArray("itens")
            val items = (0 until (itens?.length() ?: 0)).map { j -> parseItem(itens!!.optString(j)) }
            val total = v.optDouble("total", 0.0).takeIf { !it.isNaN() } ?: 0.0
            Order(v.optLong("id", i.toLong()), at, total, items)
        }
    }

    companion object {
        private const val PAGE = 50
        private const val MAX_ORDERS = 2000
        private val ML_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
        private val ITEM = Regex("""^\s*(\d+)\s*x\s+(.+?)(?:\s*\((MLB\d+)\))?\s*$""")

        /** "2x Filtro de Óleo Gol (MLB123)" -> 2, "Filtro de Óleo Gol". */
        fun parseItem(s: String): OrderItem {
            val m = ITEM.find(s) ?: return OrderItem(1, s.trim())
            return OrderItem(m.groupValues[1].toIntOrNull() ?: 1, m.groupValues[2].trim())
        }
    }
}

/** Vendas de exemplo (inventadas): para os testes no emulador e para ver as telas antes do login. */
class DemoSalesSource : SalesSource {
    override suspend fun summary(from: LocalDate, to: LocalDate): SalesSummary {
        delay(500)
        val products = listOf(
            "Filtro de óleo" to 22.0, "Pastilha de freio dianteira" to 89.9, "Bomba d'água" to 129.0,
            "Radiador" to 349.0, "Junta do cabeçote" to 64.5, "Amortecedor dianteiro" to 219.0,
        )
        val orders = mutableListOf<Order>()
        var id = 0L
        var d = from
        while (!d.isAfter(to)) {
            val rnd = Random(d.toEpochDay())
            repeat(8 + rnd.nextInt(14)) {
                val (title, price) = products[minOf(rnd.nextInt(products.size), rnd.nextInt(products.size))]
                val qty = if (rnd.nextInt(5) == 0) 2 else 1
                val at = d.atTime(7 + rnd.nextInt(15), rnd.nextInt(60))
                if (!at.isAfter(LocalDateTime.now())) orders += Order(id++, at, price * qty, listOf(OrderItem(qty, title)))
            }
            d = d.plusDays(1)
        }
        return SalesSummary.of(orders, from, to, partial = false, sample = true)
    }

    override suspend fun pending(): MlPending {
        delay(500)
        return MlPending(
            questions = 3,
            oldestQuestion = LocalDateTime.now().minusHours(2).minusMinutes(40),
            questionItems = listOf("Radiador", "Bomba d'água"),
            messages = 1,
            claims = 0,
            sample = true,
        )
    }
}
