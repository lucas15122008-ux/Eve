package app.eve.assistente.mercadolivre

// Testa a conversa da Eve com um servidor MCP "de mentira" que imita o JR Mercado Livre:
// respostas em JSON e em eventos (SSE), chave errada, sessão expirada, erro da ferramenta
// e, principalmente, que os dados do comprador nunca vão para a IA.
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.*

/** Servidorzinho HTTP só para os testes (o com.sun.net.httpserver não existe no Android). */
class MiniHttp(private val handler: (method: String, path: String, headers: Map<String, String>, body: String) -> Triple<Int, Map<String, String>, String>) {
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val pool = Executors.newFixedThreadPool(8)
    val port get() = socket.localPort

    init {
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (e: Exception) { break }
                pool.execute { serve(client) }
            }
        }
    }

    private fun readLine(input: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0 || b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun serve(client: Socket) = client.use {
        val input = client.getInputStream().buffered()
        val requestLine = readLine(input).split(" ") // POST /mcp HTTP/1.1
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input)
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val length = headers["content-length"]?.toInt() ?: 0
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(bytes, read, length - read)
            if (n < 0) break
            read += n
        }
        val (code, outHeaders, body) = handler(requestLine[0], requestLine.getOrElse(1) { "/" }, headers, String(bytes, Charsets.UTF_8))
        val out = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 $code X\r\n")
            outHeaders.forEach { (k, v) -> append("$k: $v\r\n") }
            append("Content-Length: ${out.size}\r\nConnection: close\r\n\r\n")
        }
        client.getOutputStream().apply { write(head.toByteArray(Charsets.ISO_8859_1)); write(out); flush() }
    }

    fun stop() { socket.close(); pool.shutdownNow() }
}

class FakeMcp(val sse: Boolean, val token: String = "segredo", @Volatile var expireOnce: Boolean = false, val toolError: Boolean = false) {
    // Login OAuth de mentira (igual ao do servidor de verdade: /register, /authorize, /token com PKCE).
    @Volatile var valid: Set<String> = setOf(token)
    val refreshes = AtomicInteger()
    private var challenge: String? = null
    private var issued = 0
    private val redirect = "app.eve.assistente://oauth"
    val calls = AtomicInteger()
    val inits = AtomicInteger()
    @Volatile var session = "sess-1"
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

    private val server: MiniHttp = MiniHttp { method, path, headers, raw ->
        when {
            path.startsWith("/.well-known/oauth-authorization-server") -> Triple(200, mapOf("Content-Type" to "application/json"),
                JSONObject().put("issuer", origin).put("authorization_endpoint", "$origin/authorize")
                    .put("token_endpoint", "$origin/token").put("registration_endpoint", "$origin/register").toString())
            path == "/register" && method == "POST" -> {
                val body = JSONObject(raw)
                assertEquals(redirect, body.getJSONArray("redirect_uris").getString(0))
                assertEquals("none", body.getString("token_endpoint_auth_method"))
                Triple(201, mapOf("Content-Type" to "application/json"), JSONObject().put("client_id", "cli-1").toString())
            }
            path.startsWith("/authorize") -> {
                val q = query(path.substringAfter('?'))
                assertEquals("cli-1", q["client_id"]); assertEquals(redirect, q["redirect_uri"])
                assertEquals("S256", q["code_challenge_method"]); assertEquals(url, q["resource"])
                challenge = q["code_challenge"]
                Triple(302, mapOf("Location" to "$redirect?code=codigo-1&state=${q["state"]}"), "")
            }
            path == "/token" && method == "POST" -> synchronized(this) {
                val q = query(raw)
                val ok = when (q["grant_type"]) {
                    "authorization_code" -> q["code"] == "codigo-1" && q["client_id"] == "cli-1" &&
                        McpAuth.challenge(q["code_verifier"].orEmpty()) == challenge
                    "refresh_token" -> (q["refresh_token"] == "ref-$issued").also { if (it) refreshes.incrementAndGet() }
                    else -> false
                }
                if (!ok) Triple(400, emptyMap(), """{"error":"invalid_grant"}""")
                else {
                    issued++
                    valid = valid + "tok-$issued"
                    Triple(200, mapOf("Content-Type" to "application/json"), JSONObject()
                        .put("access_token", "tok-$issued").put("refresh_token", "ref-$issued").put("expires_in", 3600).toString())
                }
            }
            else -> handle(headers, raw)
        }
    }
    private fun query(q: String) = q.split("&").filter { "=" in it }.associate {
        java.net.URLDecoder.decode(it.substringBefore("="), "UTF-8") to java.net.URLDecoder.decode(it.substringAfter("="), "UTF-8")
    }
    val origin: String get() = "http://127.0.0.1:${server.port}"
    val url: String get() = "http://127.0.0.1:${server.port}/mcp"
    fun stop() = server.stop()

    private fun handle(headers: Map<String, String>, raw: String): Triple<Int, Map<String, String>, String> {
        if (headers["authorization"]?.removePrefix("Bearer ") !in valid) return Triple(401, emptyMap(), "")
        val body = JSONObject(raw)
        val method = body.getString("method")
        if (!body.has("id")) return Triple(202, emptyMap(), "")
        val outHeaders = mutableMapOf<String, String>()
        if (method != "initialize") {
            if (headers["mcp-session-id"] != session) return Triple(400, emptyMap(), "")
            if (expireOnce) { expireOnce = false; session = "sess-2"; return Triple(404, emptyMap(), "") }
        }
        val result = when (method) {
            "initialize" -> {
                inits.incrementAndGet()
                outHeaders["Mcp-Session-Id"] = session
                JSONObject().put("protocolVersion", "2025-06-18").put("capabilities", JSONObject())
            }
            "tools/call" -> {
                calls.incrementAndGet()
                Thread.sleep(50)
                val p = body.getJSONObject("params")
                assertEquals("ml_vendas", p.getString("name"))
                val a = p.getJSONObject("arguments")
                if (toolError) {
                    JSONObject().put("isError", true)
                        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "token do ML expirou")))
                } else {
                    val from = OffsetDateTime.parse(a.getString("desde"))
                    val to = OffsetDateTime.parse(a.getString("ate"))
                    val match = orders.filter {
                        val t = OffsetDateTime.parse(it.getString("data")); !t.isBefore(from) && !t.isAfter(to)
                    }.sortedByDescending { it.getString("data") }
                    val off = a.getInt("offset"); val lim = a.getInt("limit")
                    assertTrue(lim <= 51)
                    val text = JSONObject().put("total", match.size).put("vendas", JSONArray(match.drop(off).take(lim))).toString()
                    JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
                }
            }
            else -> error("método $method")
        }
        val msg = JSONObject().put("jsonrpc", "2.0").put("id", body.get("id")).put("result", result).toString()
        return if (sse) {
            outHeaders["Content-Type"] = "text/event-stream"
            // um aviso antes da resposta, como servidores de verdade fazem
            val events = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}\n\n" +
                "event: message\ndata: $msg\n\n"
            Triple(200, outHeaders, events)
        } else {
            outHeaders["Content-Type"] = "application/json"
            Triple(200, outHeaders, msg)
        }
    }
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
            assertTrue("não aceitou o login" in e.message!!)
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

    class MemStore : AuthStore {
        override var server: String? = null
        override var clientId: String? = null
        override var accessToken: String? = null
        override var refreshToken: String? = null
        override var expiresAt: Long = 0
        override var pendingState: String? = null
        override var pendingVerifier: String? = null
    }

    /** Faz o papel do navegador: abre o /authorize e devolve para onde ele mandou voltar. */
    private fun browser(loginUrl: String): Map<String, String> {
        val conn = java.net.URL(loginUrl).openConnection() as java.net.HttpURLConnection
        conn.instanceFollowRedirects = false
        assertEquals(302, conn.responseCode)
        val location = conn.getHeaderField("Location")
        assertTrue(location.startsWith("app.eve.assistente://oauth?"), location)
        return location.substringAfter('?').split("&").associate { it.substringBefore("=") to it.substringAfter("=") }
    }

    @Test fun oauthLoginAndRefresh() {
        val fake = FakeMcp(false, token = "ninguem-tem")
        try {
            val store = MemStore()
            val auth = McpAuth(fake.url, store)
            assertFalse(auth.isLoggedIn)
            val back = browser(runBlocking { auth.startLogin() })
            runBlocking { auth.finishLogin(back["code"], back["state"], null) }
            assertTrue(auth.isLoggedIn)
            assertEquals("tok-1", store.accessToken)
            assertNull(store.pendingVerifier)

            val source = McpSalesSource(McpClient(fake.url) { rejected -> auth.token(rejected) }, sp)
            assertEquals(19, runBlocking { source.summary(to, to) }.count)

            // O servidor deixou de aceitar o token: a Eve renova sozinha e continua.
            fake.valid = emptySet()
            assertEquals(137, runBlocking { source.summary(from, to) }.count)
            assertEquals(1, fake.refreshes.get())
            assertEquals("tok-2", store.accessToken)
            assertEquals("ref-2", store.refreshToken)

            // Renovação recusada: pede para entrar de novo.
            fake.valid = emptySet()
            store.refreshToken = "velho"
            val e = assertFailsWith<McpLoginNeeded> { runBlocking { source.summary(to, to) } }
            assertTrue("Entrar no Mercado Livre" in e.message!!)
        } finally { fake.stop() }
    }

    @Test fun oauthRejectsWrongState() {
        val fake = FakeMcp(false)
        try {
            val auth = McpAuth(fake.url, MemStore())
            val back = browser(runBlocking { auth.startLogin() })
            assertFailsWith<McpException> { runBlocking { auth.finishLogin(back["code"], "outro-estado", null) } }
            assertFailsWith<McpException> { runBlocking { auth.finishLogin(null, null, "access_denied") } }
            assertFalse(auth.isLoggedIn)
        } finally { fake.stop() }
    }

    @Test fun notLoggedInAsksToLogin() {
        val fake = FakeMcp(false)
        try {
            val auth = McpAuth(fake.url, MemStore())
            val source = McpSalesSource(McpClient(fake.url) { rejected -> auth.token(rejected) }, sp)
            val e = assertFailsWith<McpLoginNeeded> { runBlocking { source.summary(to, to) } }
            assertEquals(McpClient.NEED_LOGIN, e.message)
        } finally { fake.stop() }
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
