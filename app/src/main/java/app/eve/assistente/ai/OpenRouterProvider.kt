package app.eve.assistente.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Provedor OpenRouter: um "shopping" de IAs com uma chave só
 * (inclui modelos grátis, como o nvidia/nemotron-3.5-super-vl:free).
 */
class OpenRouterProvider(
    private val apiKey: String,
    private val model: String,
) : AiProvider {

    override val displayName = "OpenRouter ($model)"

    override suspend fun reply(systemPrompt: String, history: List<ChatMessage>): String =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("model", model.trim())
                put("max_tokens", 1024)
                put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", systemPrompt))
                    history.forEach { msg ->
                        put(
                            JSONObject()
                                .put("role", if (msg.role == Role.USER) "user" else "assistant")
                                .put("content", msg.text)
                        )
                    }
                })
            }

            val conn = (URL("https://openrouter.ai/api/v1/chat/completions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 90_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
                setRequestProperty("X-Title", "Eve")
            }

            try {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

                // A OpenRouter às vezes devolve erro dentro de uma resposta 200.
                val apiMessage = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }
                    .getOrNull()?.takeIf { it.isNotBlank() }
                if (code !in 200..299 || apiMessage != null) {
                    throw AiException(friendlyError(code, apiMessage))
                }
                parseReply(text)
            } finally {
                conn.disconnect()
            }
        }

    private fun parseReply(json: String): String {
        val choices = JSONObject(json).optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            throw AiException("A IA não devolveu resposta. Tente perguntar de outro jeito.")
        }
        val reply = choices.getJSONObject(0).optJSONObject("message")?.optString("content").orEmpty().trim()
        if (reply.isEmpty()) throw AiException("A IA respondeu em branco. Tente de novo.")
        return reply
    }

    private fun friendlyError(code: Int, apiMessage: String?): String {
        val base = when (code) {
            401, 403 -> "A chave da OpenRouter não foi aceita. Confira se colou a chave inteira (começa com sk-or-)."
            402 -> "A OpenRouter pediu crédito para esse modelo. Use um modelo terminado em :free."
            404 -> "Modelo \"$model\" não disponível. Se for um modelo :free, ative os modelos grátis em openrouter.ai/settings/privacy."
            429 -> "Limite do modelo grátis atingido por agora. Espere um pouco e tente de novo."
            in 500..599 -> "O servidor da IA está com problema. Tente de novo em instantes."
            else -> "Erro da IA ($code)."
        }
        return if (apiMessage != null) "$base\nDetalhe: $apiMessage" else base
    }
}
