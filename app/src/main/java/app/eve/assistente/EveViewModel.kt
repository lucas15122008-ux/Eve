package app.eve.assistente

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
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
import app.eve.assistente.mercadolivre.McpException
import app.eve.assistente.mercadolivre.SalesSource
import org.json.JSONArray
import org.json.JSONObject
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

    /** O humor do rosto da Eve (surpresa, triste...). Ouvindo e pensando sempre têm a mesma cara. */
    var mood by mutableStateOf(EveMood.NORMAL)
        private set
    private var lastActivity = System.currentTimeMillis()
    private var testMood = false

    private val history = mutableListOf<ChatMessage>()
    private var thinkingJob: Job? = null

    /** A conexão com o Mercado Livre é reaproveitada enquanto as configurações não mudam. */
    private var salesCache: Pair<String, SalesSource?>? = null

    /** Se a Eve já entrou no servidor do Mercado Livre (mostra nas configurações). */
    var mlLoggedIn by mutableStateOf(settings.mlAuth()?.isLoggedIn == true)
        private set
    var mlBusy by mutableStateOf(false)
        private set

    private fun salesSource(): SalesSource? {
        val key = listOf(settings.provider.key, settings.mlServerUrl, settings.mlSampleData).joinToString("|")
        salesCache?.let { if (it.first == key) return it.second }
        return settings.createSalesSource().also { salesCache = key to it }
    }

    /** O que uma pergunta rendeu: o que falar, o que mostrar e as mensagens que entram na conversa. */
    private class Turn(val speech: String, val card: EveCard?, val mood: String?, val messages: List<ChatMessage>)

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
                val turn = withContext(Dispatchers.IO) { think() }
                history += turn.messages
                trimHistory()
                reply = turn.speech
                card = turn.card
                mood = EveMood.fromId(turn.mood) ?: EveMood.NORMAL
                touch()
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
                mood = EveMood.TRISTE
                touch()
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
        val data = first.followUp ?: return Turn(first.speech, first.card, first.mood, listOf(firstMsg))

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
            ?: (first.card as? EveCard.Pending)?.let { brain.fallbackPendingSpeech(it.pending) }
            ?: first.speech
        val raw = second?.rawForHistory
            ?: JSONObject().put("fala", speech).put("acoes", JSONArray()).toString()
        // O humor vem da última rodada, a que já viu os números.
        return Turn(speech, second?.card ?: first.card, second?.mood, listOf(firstMsg, dataMsg, ChatMessage(Role.EVE, raw)))
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

    /**
     * "Entrar no Mercado Livre": guarda o endereço, registra a Eve no servidor e abre o navegador.
     * Depois do login, o navegador volta para a Eve e chega em [finishMlLogin].
     */
    fun startMlLogin(url: String) {
        settings.mlServerUrl = url
        salesCache = null
        val auth = settings.mlAuth() ?: run {
            error = "O endereço do servidor precisa começar com https://"
            return
        }
        mlBusy = true
        viewModelScope.launch {
            try {
                val loginUrl = auth.startLogin()
                getApplication<Application>().startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(loginUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: ActivityNotFoundException) {
                error = "Não achei um navegador neste celular para fazer o login."
            } catch (e: Exception) {
                error = (e as? McpException)?.message
                    ?: if (e is java.io.IOException) "Não consegui falar com o servidor do Mercado Livre. Confira a internet e o endereço."
                    else "Não consegui começar o login: ${e.message}"
            } finally {
                mlBusy = false
            }
        }
    }

    /** O navegador voltou para a Eve (app.eve.assistente://oauth?code=...&state=...). */
    fun finishMlLogin(uri: Uri) {
        val auth = settings.mlAuth() ?: return
        mlBusy = true
        viewModelScope.launch {
            try {
                auth.finishLogin(uri.getQueryParameter("code"), uri.getQueryParameter("state"), uri.getQueryParameter("error"))
                salesCache = null
                mlLoggedIn = true
                error = null
                say("Pronto, entrei no Mercado Livre! Pode perguntar das suas vendas.", EveMood.ANIMADA)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mlLoggedIn = auth.isLoggedIn
                error = (e as? McpException)?.message
                    ?: if (e is java.io.IOException) "Não consegui falar com o servidor do Mercado Livre para terminar o login."
                    else "O login não deu certo: ${e.message}"
                mood = EveMood.TRISTE
            } finally {
                mlBusy = false
            }
        }
    }

    fun mlLogout() {
        settings.mlAuth()?.logout()
        salesCache = null
        mlLoggedIn = false
    }

    /** A Eve fala algo que não veio de uma pergunta (ex.: depois do login). */
    private fun say(text: String, face: EveMood) {
        if (state != EveState.IDLE) return
        reply = text
        mood = face
        touch()
        state = EveState.SPEAKING
        voiceOut.speak(text) {
            if (state == EveState.SPEAKING) state = EveState.IDLE
        }
    }

    fun saveSettings(
        provider: ProviderId, apiKey: String, model: String, name: String, speechRate: Float, mlUrl: String,
        mlSample: Boolean,
    ) {
        settings.save(provider, apiKey, model)
        settings.mlSampleData = mlSample
        settings.userName = name
        settings.speechRate = speechRate
        if (mlUrl.trim() != settings.mlServerUrl) {
            settings.mlServerUrl = mlUrl
            salesCache = null
            mlLoggedIn = settings.mlAuth()?.isLoggedIn == true
        }
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
