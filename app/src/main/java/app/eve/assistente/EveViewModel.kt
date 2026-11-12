package app.eve.assistente

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.eve.assistente.ai.AiException
import app.eve.assistente.ai.ChatMessage
import app.eve.assistente.ai.EveSettings
import app.eve.assistente.ai.ProviderId
import app.eve.assistente.ai.Role
import app.eve.assistente.assistant.EveBrain
import app.eve.assistente.assistant.EveCard
import app.eve.assistente.mercadolivre.SalesSource
import org.json.JSONArray
import org.json.JSONObject
import app.eve.assistente.voice.VoiceInput
import app.eve.assistente.voice.VoiceOutput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class EveState { IDLE, LISTENING, THINKING, SPEAKING }

class EveViewModel(app: Application) : AndroidViewModel(app) {

    val settings = EveSettings(app)
    private val voiceIn = VoiceInput(app)
    private val voiceOut = VoiceOutput(app).also { it.speechRate = settings.speechRate }
    private val brain = EveBrain(app, ::salesSource)

    var state by mutableStateOf(EveState.IDLE)
        private set
    var volume by mutableFloatStateOf(0f)
        private set
    /** O que você falou (ou está falando). */
    var heard by mutableStateOf("")
        private set
    /** A última resposta da Eve. */
    var reply by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var showSettings by mutableStateOf(!settings.isConfigured)
    /** A "tela personalizada" que aparece no meio (agenda, lembretes...). Null = Eve no centro. */
    var card by mutableStateOf<EveCard?>(null)
        private set

    private val history = mutableListOf<ChatMessage>()
    private var thinkingJob: Job? = null

    /** A conexão com o Mercado Livre é reaproveitada enquanto as configurações não mudam. */
    private var salesCache: Pair<String, SalesSource?>? = null

    private fun salesSource(): SalesSource? {
        val key = listOf(settings.provider.key, settings.mlServerUrl, settings.mlServerToken).joinToString("|")
        salesCache?.let { if (it.first == key) return it.second }
        return settings.createSalesSource().also { salesCache = key to it }
    }

    /** O que uma pergunta rendeu: o que falar, o que mostrar e as mensagens que entram na conversa. */
    private class Turn(val speech: String, val card: EveCard?, val messages: List<ChatMessage>)

    /** Toque na Eve: começa a ouvir, ou interrompe o que ela estiver fazendo. */
    fun onOrbTapped() {
        when (state) {
            EveState.IDLE -> startListening()
            EveState.LISTENING -> { voiceIn.stop(); state = EveState.IDLE; volume = 0f }
            EveState.THINKING -> { thinkingJob?.cancel(); state = EveState.IDLE }
            EveState.SPEAKING -> { voiceOut.stop(); startListening() }
        }
    }

    private fun startListening() {
        if (!settings.isConfigured) {
            showSettings = true
            return
        }
        if (!voiceIn.isAvailable) {
            error = "Este celular não tem reconhecimento de voz. Instale o app Google."
            return
        }
        error = null
        heard = ""
        state = EveState.LISTENING
        voiceIn.start(object : VoiceInput.Listener {
            override fun onReady() {}
            override fun onPartial(text: String) { heard = text }
            override fun onVolume(level: Float) { volume = level }
            override fun onResult(text: String) {
                volume = 0f
                heard = text
                voiceIn.stop()
                ask(text)
            }
            override fun onError(message: String) {
                volume = 0f
                voiceIn.stop()
                error = message
                state = EveState.IDLE
            }
        })
    }

    private fun ask(text: String) {
        state = EveState.THINKING
        history += ChatMessage(Role.USER, text)
        trimHistory()
        thinkingJob = viewModelScope.launch {
            try {
                val turn = withContext(Dispatchers.IO) { think() }
                history += turn.messages
                trimHistory()
                reply = turn.speech
                card = turn.card
                state = EveState.SPEAKING
                voiceOut.speak(turn.speech) {
                    if (state == EveState.SPEAKING) state = EveState.IDLE
                }
            } catch (e: CancellationException) {
                history.removeLastOrNull()
                throw e
            } catch (e: Exception) {
                history.removeLastOrNull()
                error = when (e) {
                    is AiException -> e.message
                    is java.io.IOException -> "Sem conexão com a internet."
                    else -> "Algo deu errado: ${e.message}"
                }
                state = EveState.IDLE
            }
        }
    }

    /**
     * Pergunta para a IA. Se ela pedir dados (ex.: vendas do Mercado Livre), a Eve busca,
     * mostra o gráfico e manda os números de volta para a IA responder.
     */
    private suspend fun think(): Turn {
        val provider = settings.createProvider()
        val prompt = brain.systemPrompt(settings.userName)
        val first = brain.handle(provider.reply(prompt, history.toList()))
        val firstMsg = ChatMessage(Role.EVE, first.rawForHistory)
        val data = first.followUp ?: return Turn(first.speech, first.card, listOf(firstMsg))

        val dataMsg = ChatMessage(Role.USER, data)
        val second = try {
            brain.handle(provider.reply(prompt, history + firstMsg + dataMsg), allowData = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val speech = second?.speech
            ?: (first.card as? EveCard.Sales)?.let { brain.fallbackSalesSpeech(it.summary) }
            ?: first.speech
        val raw = second?.rawForHistory
            ?: JSONObject().put("fala", speech).put("acoes", JSONArray()).toString()
        return Turn(speech, second?.card ?: first.card, listOf(firstMsg, dataMsg, ChatMessage(Role.EVE, raw)))
    }

    /** Fecha a tela personalizada e a Eve volta para o centro. */
    fun closeCard() {
        card = null
    }

    private fun trimHistory() {
        // Mantém só as últimas mensagens para a conversa continuar rápida e barata.
        while (history.size > MAX_HISTORY) history.removeAt(0)
        // O Gemini exige que a conversa comece por uma mensagem do usuário.
        while (history.isNotEmpty() && history.first().role != Role.USER) history.removeAt(0)
    }

    fun clearConversation() {
        history.clear()
        heard = ""
        reply = ""
        error = null
        card = null
    }

    fun saveSettings(
        provider: ProviderId, apiKey: String, model: String, name: String, speechRate: Float,
        mlUrl: String, mlToken: String,
    ) {
        settings.save(provider, apiKey, model)
        settings.userName = name
        settings.speechRate = speechRate
        settings.mlServerUrl = mlUrl
        settings.mlServerToken = mlToken
        voiceOut.speechRate = speechRate
        showSettings = false
        error = null
    }

    /** Faz uma pergunta por texto, sem usar o microfone (usado nos testes do emulador). */
    fun askText(text: String) {
        if (state != EveState.IDLE || text.isBlank()) return
        error = null
        heard = text
        ask(text)
    }

    fun onMicPermissionDenied() {
        error = "Sem a permissão do microfone eu não consigo te ouvir."
    }

    override fun onCleared() {
        voiceIn.stop()
        voiceOut.shutdown()
    }

    companion object {
        private const val MAX_HISTORY = 20
    }
}
