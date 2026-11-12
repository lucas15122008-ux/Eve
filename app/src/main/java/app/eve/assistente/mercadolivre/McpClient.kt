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
class McpException(message: String) : Exception(message)

/**
 * Cliente MCP bem simples (transporte "Streamable HTTP"): é como a Eve conversa com o
 * servidor "JR Mercado Livre", o mesmo que o Claude usa. O servidor guarda o login do
 * Mercado Livre; a Eve só pede as ferramentas (ex.: ml_vendas) e recebe o resultado.
 *
 * O endereço e a chave ficam só nas configurações do celular, nunca no código.
 */
class McpClient(private val url: String, private val token: String) {
    private val ids = AtomicInteger(1)
    private val initLock = Mutex()
    @Volatile private var sessionId: String? = null
    @Volatile private var protocolVersion: String? = null
    @Volatile private var initialized = false

    /** Chama uma ferramenta do servidor e devolve o texto da resposta. */
    suspend fun callTool(name: String, arguments: JSONObject): String = withContext(Dispatchers.IO) {
        ensureInitialized()
        val response = try {
            request("tools/call", JSONObject().put("name", name).put("arguments", arguments))
        } catch (e: SessionExpired) {
            // O servidor esqueceu a sessão: começa de novo, uma vez.
            initLock.withLock { initialized = false; sessionId = null }
            ensureInitialized()
            request("tools/call", JSONObject().put("name", name).put("arguments", arguments))
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

    private suspend fun ensureInitialized() = initLock.withLock {
        if (initialized) return@withLock
        val params = JSONObject()
            .put("protocolVersion", PROTOCOL_VERSION)
            .put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "Eve").put("version", "0.3"))
        val response = request("initialize", params)
        protocolVersion = response.optJSONObject("result")?.optString("protocolVersion")?.ifBlank { null }
            ?: PROTOCOL_VERSION
        notify("notifications/initialized")
        initialized = true
    }

    private fun request(method: String, params: JSONObject): JSONObject {
        val id = ids.getAndIncrement()
        val body = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)
        val conn = open(body)
        try {
            val code = conn.responseCode
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

    private fun notify(method: String) {
        val conn = open(JSONObject().put("jsonrpc", "2.0").put("method", method))
        try {
            conn.responseCode // 202 Accepted; o conteúdo não importa.
        } finally {
            conn.disconnect()
        }
    }

    private fun open(body: JSONObject): HttpURLConnection {
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
        if (token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer ${token.trim()}")
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
        401, 403 -> "O servidor do Mercado Livre não aceitou a chave da Eve. Confira a chave nas configurações."
        404 -> "Não achei o servidor do Mercado Livre nesse endereço. Confira nas configurações."
        in 500..599 -> "O servidor do Mercado Livre está com problema agora. Tente de novo em instantes."
        else -> "O servidor do Mercado Livre respondeu com erro $code." +
            (detail.takeIf { it.isNotBlank() }?.let { " Detalhe: ${it.take(150)}" } ?: "")
    }

    private class SessionExpired : Exception()

    companion object {
        private const val PROTOCOL_VERSION = "2025-06-18"
    }
}
