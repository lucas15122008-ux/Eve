package app.eve.assistente.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Provedor de IA do Google (Gemini), chamado pela API REST oficial. */
class GeminiProvider(
    private val apiKey: String,
    private val model: String,
) : AiProvider {

    override val displayName = "Google Gemini ($model)"

    override suspend fun reply(systemPrompt: String, history: List<ChatMessage>): String =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
                put("contents", JSONArray().apply {
                    history.forEach { msg ->
                        put(
                            JSONObject()
                                .put("role", if (msg.role == Role.USER) "user" else "model")
                                .put("parts", JSONArray().put(JSONObject().put("text", msg.text)))
                        )
                    }
                })
                put("generationConfig", JSONObject().put("maxOutputTokens", 1024))
            }

            val modelPath = URLEncoder.encode(model.trim(), "UTF-8")
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$modelPath:generateContent")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 60_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("x-goog-api-key", apiKey.trim())
            }

            try {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

                if (code !in 200..299) {
                    val apiMessage = runCatching {
                        JSONObject(text).getJSONObject("error").getString("message")
                    }.getOrNull()
                    throw AiException(friendlyError(code, apiMessage))
                }
                parseReply(text)
            } finally {
                conn.disconnect()
            }
        }

    private fun parseReply(json: String): String {
        val root = JSONObject(json)
        val candidates = root.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            throw AiException("A IA não devolveu resposta. Tente perguntar de outro jeito.")
        }
        val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
            ?: throw AiException("A IA não devolveu resposta. Tente perguntar de outro jeito.")
        val reply = buildString {
            for (i in 0 until parts.length()) {
                append(parts.getJSONObject(i).optString("text"))
            }
        }.trim()
        if (reply.isEmpty()) throw AiException("A IA respondeu em branco. Tente de novo.")
        return reply
    }

    private fun friendlyError(code: Int, apiMessage: String?): String = when (code) {
        400, 403 -> "A chave da API parece inválida. Confira nas configurações."
        404 -> "O modelo \"$model\" não foi encontrado. Confira o nome nas configurações."
        429 -> "Limite de uso da IA atingido por agora. Espere um pouco e tente de novo."
        in 500..599 -> "O servidor da IA está com problema. Tente de novo em instantes."
        else -> "Erro da IA ($code)" + (apiMessage?.let { ": $it" } ?: "")
    }
}
