package app.eve.assistente.mercadolivre

// Testa a conversa da Eve com um servidor MCP "de mentira" que imita o JR Mercado Livre:
// respostas em JSON e em eventos (SSE), chave errada, sessão expirada, erro da ferramenta
// e, principalmente, que os dados do comprador nunca vão para a IA.
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class FakeMcp(val sse: Boolean, val token: String = "segredo", var expireOnce: Boolean = false, val toolError: Boolean = false) {
    val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val calls = AtomicInteger()
    val inits = AtomicInteger()
    val maxConcurrent = AtomicInteger()
    private val current = AtomicInteger()
    var session = "sess-1"
    val zone = ZoneOffset.ofHours(-4) // como o ML devolve
    // 137 vendas de 06/11 a 12/11 (horário de Brasília), com comprador (que a Eve tem que descartar)
    val orders: List<JSONObject> = (0 until 137).map { i ->
        val local = LocalDate.of(2026, 11, 6).plusDays((i % 7).toLong()).atTime(8 + i % 12, i % 60)
        val at = local.atZone(ZoneId.of("America/Sao_Paulo")).withZoneSameInstant(zone).toOffsetDateTime()
        JSONObject().put("id", 1000L + i).put("data", at.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")))
            .put("status", "paid").put("total", 10.0 + i % 5)
            .put("comprador", JSONObject().put("id", 99).put("nickname", "COMPRADOR_SECRETO"))
            .put("itens", JSONArray().put(if (i % 3 == 0) "2x Filtro de Óleo Gol (MLB111)" else "1x Pastilha Freio Palio (MLB222)"))
    }

    init {
        server.createContext("/mcp") { ex ->
            val now = current.incrementAndGet()
            maxConcurrent.updateAndGet { maxOf(it, now) }
            try {
                val auth = ex.requestHeaders.getFirst("Authorization")
                if (auth != "Bearer $token") { ex.sendResponseHeaders(401, -1); return@createContext }
                val body = JSONObject(ex.requestBody.readBytes().toString(Charsets.UTF_8))
                val method = body.getString("method")
                if (!body.has("id")) { ex.sendResponseHeaders(202, -1); return@createContext }
                if (method != "initialize") {
                    if (ex.requestHeaders.getFirst("Mcp-Session-Id") != session) { ex.sendResponseHeaders(400, -1); return@createContext }
                    if (expireOnce) { expireOnce = false; session = "sess-2"; ex.sendResponseHeaders(404, -1); return@createContext }
                }
                val result = when (method) {
                    "initialize" -> { inits.incrementAndGet(); ex.responseHeaders.add("Mcp-Session-Id", session)
                        JSONObject().put("protocolVersion", "2025-06-18").put("capabilities", JSONObject()) }
                    "tools/call" -> {
                        calls.incrementAndGet()
                        Thread.sleep(50)
                        val p = body.getJSONObject("params")
                        assertEquals("ml_vendas", p.getString("name"))
                        val a = p.getJSONObject("arguments")
                        if (toolError) JSONObject().put("isError", true).put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "token do ML expirou")))
                        else {
                            val from = OffsetDateTime.parse(a.getString("desde")); val to = OffsetDateTime.parse(a.getString("ate"))
                            val match = orders.filter { val t = OffsetDateTime.parse(it.getString("data")); !t.isBefore(from) && !t.isAfter(to) }
                                .sortedByDescending { it.getString("data") }
                            val off = a.getInt("offset"); val lim = a.getInt("limit")
                            assertTrue(lim <= 51)
                            val page = match.drop(off).take(lim)
                            val text = JSONObject().put("total", match.size).put("vendas", JSONArray(page)).toString()
                            JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
                        }
                    }
                    else -> error("método $method")
                }
                val msg = JSONObject().put("jsonrpc", "2.0").put("id", body.get("id")).put("result", result).toString()
                val out = if (sse) {
                    ex.responseHeaders.add("Content-Type", "text/event-stream")
                    // um aviso antes da resposta, e a resposta quebrada em duas linhas data:
                    "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}\n\n" +
                        "event: message\ndata: ${msg}\n\n"
                } else {
                    ex.responseHeaders.add("Content-Type", "application/json"); msg
                }
                val bytes = out.toByteArray(Charsets.UTF_8)
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            } finally { current.decrementAndGet(); ex.close() }
        }
        server.executor = java.util.concurrent.Executors.newFixedThreadPool(8)
        server.start()
    }
    val url get() = "http://127.0.0.1:${server.address.port}/mcp"
    fun stop() = server.stop(0)
}

class McpTest {
    private val sp = ZoneId.of("America/Sao_Paulo")
    private val from = LocalDate.of(2026, 11, 6)
    private val to = LocalDate.of(2026, 11, 12)

    private fun check(sse: Boolean) {
        val fake = FakeMcp(sse)
        try {
            val s = runBlocking { McpSalesSource(McpClient(fake.url, "segredo"), sp).summary(from, to) }
            assertEquals(137, s.count)
            assertEquals(fake.orders.sumOf { it.getDouble("total") }, s.revenue, 0.001)
            assertEquals(7, s.bars.size)
            assertEquals(137, s.bars.sumOf { it.count })
            assertEquals(20, s.bars[0].count) // dia 06: i%7==0 -> 20 vendas
            assertEquals(3, fake.calls.get()) // 50 + 50 + 37
            assertEquals(1, fake.inits.get())
            assertFalse(s.partial)
            val ai = s.forAi()
            println(ai)
            assertFalse("COMPRADOR" in ai, "dados do comprador não podem ir para a IA")
            assertTrue("Filtro de Óleo Gol" in ai)
            assertFalse("MLB111" in ai)
            assertEquals("Filtro de Óleo Gol", s.top[0].title)
            assertEquals(92, s.top[0].quantity) // 46 pedidos x 2
            assertEquals("Pastilha Freio Palio", s.top[1].title)
            assertEquals(91, s.top[1].quantity)
        } finally { fake.stop() }
    }

    @Test fun jsonResponses() = check(sse = false)
    @Test fun sseResponses() = check(sse = true)

    @Test fun wrongToken() {
        val fake = FakeMcp(false)
        try {
            val e = assertFailsWith<McpException> { runBlocking { McpSalesSource(McpClient(fake.url, "errado"), sp).summary(from, to) } }
            println(e.message)
            assertTrue("não aceitou a chave" in e.message!!)
        } finally { fake.stop() }
    }

    @Test fun sessionExpiredReconnects() {
        val fake = FakeMcp(false, expireOnce = true)
        try {
            val s = runBlocking { McpSalesSource(McpClient(fake.url, "segredo"), sp).summary(to, to) }
            assertEquals(2, fake.inits.get())
            assertEquals(19, s.count) // dia 12: i%7==6 -> 19 vendas
            assertEquals(8, s.bars.size) // um dia só: blocos de 3 horas
            println("um dia: ${s.count} vendas, barras ${s.bars}")
        } finally { fake.stop() }
    }

    @Test fun toolErrorBecomesMessage() {
        val fake = FakeMcp(true, toolError = true)
        try {
            val e = assertFailsWith<McpException> { runBlocking { McpSalesSource(McpClient(fake.url, "segredo"), sp).summary(from, to) } }
            println(e.message)
            assertTrue("token do ML expirou" in e.message!!)
        } finally { fake.stop() }
    }

    @Test fun badAddress() {
        val e = assertFailsWith<Exception> { runBlocking { McpSalesSource(McpClient("http://127.0.0.1:1/mcp", "x"), sp).summary(from, to) } }
        println("endereço sem servidor -> ${e::class.simpleName}: ${e.message}")
        assertTrue(e is java.io.IOException)
    }

    @Test fun parseItem() {
        assertEquals(OrderItem(2, "Filtro de Óleo Gol"), McpSalesSource.parseItem("2x Filtro de Óleo Gol (MLB111)"))
        assertEquals(OrderItem(1, "Guarnição Flange Tampa Anel Combustível Astra Corsa Vectra"),
            McpSalesSource.parseItem("1x Guarnição Flange Tampa Anel Combustível Astra Corsa Vectra (MLB5308330078)"))
        assertEquals(OrderItem(1, "Sem quantidade"), McpSalesSource.parseItem("Sem quantidade"))
    }

    @Test fun demoAndMoney() {
        val s = runBlocking { DemoSalesSource().summary(LocalDate.now().minusDays(6), LocalDate.now()) }
        println(s.forAi())
        assertTrue(s.count > 0)
        assertEquals("R$ 1.234,50", SalesSummary.money(1234.5))
    }
}
