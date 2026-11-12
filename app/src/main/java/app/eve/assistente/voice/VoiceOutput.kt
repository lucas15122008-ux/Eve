package app.eve.assistente.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** A voz da Eve, usando o leitor de texto (TTS) do próprio Android (grátis). */
class VoiceOutput(context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var ready = false
    private var onDone: (() -> Unit)? = null

    /** Velocidade da fala: 1.0 é o normal do Android, menor é mais devagar. */
    var speechRate: Float = DEFAULT_RATE

    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.forLanguageTag("pt-BR")
                ready = true
            }
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finish()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish()
            override fun onError(utteranceId: String?, errorCode: Int) = finish()
        })
    }

    private fun finish() {
        mainHandler.post {
            val callback = onDone
            onDone = null
            callback?.invoke()
        }
    }

    /** Fala o texto e chama [whenDone] quando terminar (ou na hora, se a voz não estiver pronta). */
    fun speak(text: String, whenDone: () -> Unit) {
        if (!ready) {
            whenDone()
            return
        }
        onDone = whenDone
        tts.setSpeechRate(speechRate)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "eve-${System.currentTimeMillis()}")
    }

    fun stop() {
        onDone = null
        tts.stop()
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }

    companion object {
        const val DEFAULT_RATE = 0.85f
    }
}
