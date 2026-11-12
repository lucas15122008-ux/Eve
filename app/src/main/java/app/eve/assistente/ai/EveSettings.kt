package app.eve.assistente.ai

import android.content.Context

/** Os provedores de IA que a Eve sabe usar. */
enum class ProviderId(val key: String, val label: String, val defaultModel: String, val keyHint: String) {
    GEMINI("gemini", "Google Gemini", "gemini-2.5-flash-lite", "aistudio.google.com/apikey"),
    OPENROUTER("openrouter", "OpenRouter", "nvidia/nemotron-3.5-super-vl:free", "openrouter.ai/keys"),

    /** Só para os testes automáticos no emulador: responde sem internet. Não aparece nas configurações. */
    DEMO("demo", "Demonstração", "demo", "");

    companion object {
        fun from(key: String?) = entries.firstOrNull { it.key == key } ?: GEMINI

        /** Os que aparecem nas configurações. */
        val visible get() = entries.filter { it != DEMO }
    }
}

/**
 * Configurações da Eve, guardadas só no celular (nunca no código nem no GitHub).
 * Cada provedor tem a sua própria chave e o seu modelo.
 */
class EveSettings(context: Context) {
    private val prefs = context.getSharedPreferences("eve_settings", Context.MODE_PRIVATE)

    var provider: ProviderId
        get() = ProviderId.from(prefs.getString(KEY_PROVIDER, null))
        set(value) = prefs.edit().putString(KEY_PROVIDER, value.key).apply()

    fun apiKey(p: ProviderId): String = prefs.getString(keyOf(p), "") ?: ""
    fun model(p: ProviderId): String = prefs.getString(modelOf(p), null)?.ifBlank { null } ?: p.defaultModel

    fun save(p: ProviderId, apiKey: String, model: String) {
        prefs.edit()
            .putString(KEY_PROVIDER, p.key)
            .putString(keyOf(p), apiKey.trim())
            .putString(modelOf(p), model.trim().ifEmpty { p.defaultModel })
            .apply()
    }

    var userName: String
        get() = prefs.getString(KEY_USER_NAME, "Lucas") ?: "Lucas"
        set(value) = prefs.edit().putString(KEY_USER_NAME, value.trim()).apply()

    /** Velocidade da voz da Eve (0.6 = bem devagar, 1.2 = rápida). */
    var speechRate: Float
        get() = prefs.getFloat(KEY_SPEECH_RATE, 0.85f)
        set(value) = prefs.edit().putFloat(KEY_SPEECH_RATE, value.coerceIn(0.5f, 1.5f)).apply()

    val isConfigured: Boolean
        get() = apiKey(provider).isNotBlank()

    /** Monta o provedor de IA escolhido. É aqui que entram novos provedores (ex.: Claude). */
    fun createProvider(): AiProvider {
        val p = provider
        return when (p) {
            ProviderId.GEMINI -> GeminiProvider(apiKey = apiKey(p), model = model(p))
            ProviderId.OPENROUTER -> OpenRouterProvider(apiKey = apiKey(p), model = model(p))
            ProviderId.DEMO -> DemoProvider()
        }
    }

    // A versão 0.1 guardava a chave do Gemini como "gemini_api_key"; os nomes continuam iguais.
    private fun keyOf(p: ProviderId) = "${p.key}_api_key"
    private fun modelOf(p: ProviderId) = "${p.key}_model"

    companion object {
        private const val KEY_PROVIDER = "provider"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_SPEECH_RATE = "speech_rate"
    }
}
