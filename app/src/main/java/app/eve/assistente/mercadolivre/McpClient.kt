package app.eve.assistente.mercadolivre

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/** Erro com uma frase que dá para mostrar (e falar) para o usuário. */
open class McpException(message: String) : Exception(message)

/** O servidor pediu login (ou recusou o que a Eve tinha). */
class McpLoginNeeded(message: String) : McpException(message)

/**
 * Cliente MCP bem simples (transporte "Streamable HTTP"): é como a Eve conversa com o
 * servidor "JR Mercado Livre", o mesmo que o Claude usa. O servidor guarda o login do
 * Mercado Livre; a Eve só pede as ferramentas (ex.: ml_vendas) e recebe o resultado.
 *
 * [auth] entrega o "crachá" (token) do login. Recebe o token que o servidor acabou de recusar
 * (ou null) e devolve um token válido, renovando se precisar, ou null se a Eve ainda não entrou.
 */
class McpClient(
    private val url: String,
    private val auth: (suspend (rejected: String?) -> String?)? = null,
) {
    /** Para servidores com uma chave fixa (e para os testes). */
    constructor(url: String, token: String) : this(url, { _: String? -> token.ifBlank { null } })

    private val ids = AtomicInteger(1)
    private val initLock = Mutex()
    @Volatile private var sessionId: String? = null
    @Volatile private var protocolVersion: String? = null
    @Volatile private var initialized = false

    /** Chama uma ferramenta do servidor e devolve o texto da resposta. */
    suspend fun callTool(name: String, arguments: JSONObject): String = withContext(Dispatchers.IO) {
        val params = JSONObject().put("name", name).put("arguments", arguments)
        val response = authed { token ->
            ensureInitialized(token)
            try {
                request("tools/call", params, token)
            } catch (e: SessionExpired) {
                // O servidor esqueceu a sessão: começa de novo, uma vez.
                initLock.withLock { initialized = false; sessionId = null }
                ensureInitialized(token)
                request("tools/call", params, token)
            }
        }
        val result = response.optJSONObject("result")
            ?: throw McpException("O servidor do Mercado Livre respondeu de um jeito que eu não entendi.")
        val text = buildString {
            val content = result.optJSONArray("content")
            for (i in 0 until (content?.length() ?: 0)) {
                val part = content!!.optJSONObject(i) ?: continue
                if (part.optString("type") == "text") append(part.optString("text"))
            }
        }
        if (result.optBoolean("isError")) {
            throw McpException("O servidor do Mercado Livre deu erro: ${text.take(200)}")
        }
        text
    }

    /** Roda [block] com o token; se o servidor recusar, pede um token novo (renovado) e tenta mais uma vez. */
    private suspend fun <T> authed(block: suspend (token: String?) -> T): T {
        val token = auth?.invoke(null)
        try {
            return block(token)
        } catch (e: Unauthorized) {
            val fresh = auth?.invoke(token)
            if (fresh == null) throw McpLoginNeeded(if (token == null) NEED_LOGIN else REJECTED)
            if (fresh == token) throw McpLoginNeeded(REJECTED)
            initLock.withLock { initialized = false; sessionId = null }
            try {
                return block(fresh)
            } catch (e2: Unauthorized) {
                throw McpLoginNeeded(REJECTED)
            }
        }
    }

    private suspend fun ensureInitialized(token: String?) = initLock.withLock {
        if (initialized) return@withLock
        val params = JSONObject()
            .put("protocolVersion", PROTOCOL_VERSION)
            .put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "Eve").put("version", "0.3"))
        val response = request("initialize", params, token)
        protocolVersion = response.optJSONObject("result")?.optString("protocolVersion")?.ifBlank { null }
            ?: PROTOCOL_VERSION
        notify("notifications/initialized", token)
        initialized = true
    }

    private fun request(method: String, params: JSONObject, token: String?): JSONObject {
        val id = ids.getAndIncrement()
        val body = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)
        val conn = open(body, token)
        try {
            val code = conn.responseCode
            if (code == 401) throw Unauthorized()
            if (code == 404 && sessionId != null && method != "initialize") throw SessionExpired()
            if (code !in 200..299) {
                val detail = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                throw McpException(friendlyError(code, detail))
            }
            conn.getHeaderField("Mcp-Session-Id")?.let { sessionId = it }
            val type = conn.contentType.orEmpty()
            val reader = conn.inputStream.bufferedReader(Charsets.UTF_8)
            val message = reader.use {
                if (type.contains("text/event-stream")) readEventStream(it, id) else JSONObject(it.readText())
            }
            message.optJSONObject("error")?.let { err ->
                throw McpException("O servidor do Mercado Livre deu erro: ${err.optString("message").take(200)}")
            }
            return message
        } finally {
            conn.disconnect()
        }
    }

    private fun notify(method: String, token: String?) {
        val conn = open(JSONObject().put("jsonrpc", "2.0").put("method", method), token)
        try {
            conn.responseCode // 202 Accepted; o conteúdo não importa.
        } finally {
            conn.disconnect()
        }
    }

    private fun open(body: JSONObject, token: String?): HttpURLConnection {
        val conn = try {
            URL(url.trim()).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw McpException("O endereço do servidor do Mercado Livre parece errado. Confira nas configurações.")
        }
        conn.requestMethod = "POST"
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.setRequestProperty("Accept", "application/json, text/event-stream")
        if (!token.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer ${token.trim()}")
        sessionId?.let { conn.setRequestProperty("Mcp-Session-Id", it) }
        protocolVersion?.let { conn.setRequestProperty("MCP-Protocol-Version", it) }
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        return conn
    }

    /** Lê a resposta em "eventos" (SSE) até achar a que responde ao nosso pedido. */
    private fun readEventStream(reader: BufferedReader, id: Int): JSONObject {
        val data = StringBuilder()
        while (true) {
            val line = reader.readLine()
            if (line == null || line.isEmpty()) {
                if (data.isNotEmpty()) {
                    val msg = runCatching { JSONObject(data.toString()) }.getOrNull()
                    data.clear()
                    if (msg != null && msg.optInt("id", -1) == id && (msg.has("result") || msg.has("error"))) return msg
                }
                if (line == null) break
            } else if (line.startsWith("data:")) {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.removePrefix("data:").trimStart())
            }
        }
        throw McpException("O servidor do Mercado Livre não terminou a resposta. Tente de novo.")
    }

    private fun friendlyError(code: Int, detail: String): String = when (code) {
        403 -> "O servidor do Mercado Livre não deixou a Eve fazer isso."
        404 -> "Não achei o servidor do Mercado Livre nesse endereço. Confira nas configurações."
        in 500..599 -> "O servidor do Mercado Livre está com problema agora. Tente de novo em instantes."
        else -> "O servidor do Mercado Livre respondeu com erro $code." +
            (detail.takeIf { it.isNotBlank() }?.let { " Detalhe: ${it.take(150)}" } ?: "")
    }

    private class SessionExpired : Exception()
    private class Unauthorized : Exception()

    companion object {
        private const val PROTOCOL_VERSION = "2025-06-18"
        const val NEED_LOGIN =
            "A Eve ainda não entrou no servidor do Mercado Livre. Nas configurações, toque em \"Entrar no Mercado Livre\"."
        const val REJECTED =
            "O servidor do Mercado Livre não aceitou o login da Eve. Nas configurações, toque em \"Entrar no Mercado Livre\" de novo."
    }
}
