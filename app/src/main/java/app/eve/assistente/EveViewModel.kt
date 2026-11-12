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
import app.eve.assistente.ui.EveMood
import app.eve.assistente.voice.VoiceInput
import app.eve.assistente.voice.VoiceOutput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime

enum class EveState { IDLE, LISTENING, THINKING, SPEAKING }

class EveViewModel(app: Application) : AndroidViewModel(app) {

    val settings = EveSettings(app)
    private val voiceIn = VoiceInput(app)
    private val voiceOut = VoiceOutput(app).also { it.speechRate = settings.speechRate }
    private val brain = EveBrain(app)

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

    /** O humor do rosto da Eve (surpresa, triste...). Ouvindo e pensando sempre têm a mesma cara. */
    var mood by mutableStateOf(EveMood.NORMAL)
        private set
    private var lastActivity = System.currentTimeMillis()
    private var testMood = false

    private val history = mutableListOf<ChatMessage>()
    private var thinkingJob: Job? = null

    init {
        // Sono: parada há 2 minutos, ou de noite (22h às 6h), a Eve fica sonolenta.
        // Um humor "de momento" (surpresa, piscadinha...) volta ao normal depois de 1 minuto.
        viewModelScope.launch {
            while (true) {
                delay(15_000)
                if (state != EveState.IDLE || testMood) continue
                val idleMs = System.currentTimeMillis() - lastActivity
                val hour = LocalTime.now().hour
                val night = hour >= 22 || hour < 6
                if (idleMs > 120_000 || (night && idleMs > 20_000)) {
                    if (mood == EveMood.NORMAL || idleMs > 60_000) mood = EveMood.SONOLENTA
                } else if (idleMs > 60_000 && mood != EveMood.NORMAL && mood != EveMood.TRISTE) {
                    mood = EveMood.NORMAL
                }
            }
        }
    }

    private fun touch() {
        lastActivity = System.currentTimeMillis()
        testMood = false
    }

    /** Toque na Eve: começa a ouvir, ou interrompe o que ela estiver fazendo. */
    fun onOrbTapped() {
        touch()
        mood = EveMood.NORMAL
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
                touch()
                mood = if (message.startsWith("Não entendi") || message.startsWith("Não ouvi")) EveMood.CONFUSA else EveMood.TRISTE
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
                val result = withContext(Dispatchers.IO) {
                    val raw = settings.createProvider().reply(brain.systemPrompt(settings.userName), history.toList())
                    brain.handle(raw)
                }
                history += ChatMessage(Role.EVE, result.rawForHistory)
                reply = result.speech
                card = result.card
                mood = EveMood.fromId(result.mood) ?: EveMood.NORMAL
                touch()
                state = EveState.SPEAKING
                voiceOut.speak(result.speech) {
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
                mood = EveMood.TRISTE
                touch()
                state = EveState.IDLE
            }
        }
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

    fun saveSettings(provider: ProviderId, apiKey: String, model: String, name: String, speechRate: Float) {
        settings.save(provider, apiKey, model)
        settings.userName = name
        settings.speechRate = speechRate
        voiceOut.speechRate = speechRate
        showSettings = false
        error = null
    }

    /** Faz uma pergunta por texto, sem usar o microfone (usado nos testes do emulador). */
    fun askText(text: String) {
        if (state != EveState.IDLE || text.isBlank()) return
        touch()
        mood = EveMood.NORMAL
        error = null
        heard = text
        ask(text)
    }

    /** Mostra um humor direto, sem IA (usado nos prints do emulador). */
    fun setTestMood(id: String) {
        mood = EveMood.fromId(id) ?: return
        lastActivity = System.currentTimeMillis()
        testMood = true
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
