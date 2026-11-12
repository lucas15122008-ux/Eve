package app.eve.assistente.ai

/** Quem falou cada mensagem da conversa. */
enum class Role { USER, EVE }

data class ChatMessage(val role: Role, val text: String)

/**
 * A "tomada" de IA da Eve.
 *
 * Todo provedor (Gemini, Claude, ...) implementa esta interface, então trocar de IA
 * é só mudar a configuração, sem mexer no resto do app.
 */
interface AiProvider {
    /** Nome para mostrar na tela de configurações. */
    val displayName: String

    /**
     * Recebe as instruções da Eve e o histórico da conversa (a última mensagem é a do usuário)
     * e devolve a resposta em texto.
     */
    suspend fun reply(systemPrompt: String, history: List<ChatMessage>): String
}

class AiException(message: String) : Exception(message)
