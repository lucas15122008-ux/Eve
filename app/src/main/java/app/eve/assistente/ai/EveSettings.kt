package app.eve.assistente.ai

import android.content.Context

/**
 * Configurações da Eve, guardadas só no celular (nunca no código nem no GitHub).
 */
class EveSettings(context: Context) {
    private val prefs = context.getSharedPreferences("eve_settings", Context.MODE_PRIVATE)

    var provider: String
        get() = prefs.getString(KEY_PROVIDER, PROVIDER_GEMINI) ?: PROVIDER_GEMINI
        set(value) = prefs.edit().putString(KEY_PROVIDER, value).apply()

    var geminiApiKey: String
        get() = prefs.getString(KEY_GEMINI_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GEMINI_KEY, value.trim()).apply()

    var geminiModel: String
        get() = prefs.getString(KEY_GEMINI_MODEL, DEFAULT_GEMINI_MODEL) ?: DEFAULT_GEMINI_MODEL
        set(value) = prefs.edit().putString(KEY_GEMINI_MODEL, value.trim().ifEmpty { DEFAULT_GEMINI_MODEL }).apply()

    var userName: String
        get() = prefs.getString(KEY_USER_NAME, "Lucas") ?: "Lucas"
        set(value) = prefs.edit().putString(KEY_USER_NAME, value.trim()).apply()

    val isConfigured: Boolean
        get() = when (provider) {
            PROVIDER_GEMINI -> geminiApiKey.isNotBlank()
            else -> false
        }

    /** Monta o provedor de IA escolhido. É aqui que entram novos provedores (ex.: Claude). */
    fun createProvider(): AiProvider = when (provider) {
        PROVIDER_GEMINI -> GeminiProvider(apiKey = geminiApiKey, model = geminiModel)
        else -> throw AiException("Provedor de IA desconhecido: $provider")
    }

    companion object {
        const val PROVIDER_GEMINI = "gemini"
        const val DEFAULT_GEMINI_MODEL = "gemini-2.5-flash-lite"

        private const val KEY_PROVIDER = "provider"
        private const val KEY_GEMINI_KEY = "gemini_api_key"
        private const val KEY_GEMINI_MODEL = "gemini_model"
        private const val KEY_USER_NAME = "user_name"
    }
}
