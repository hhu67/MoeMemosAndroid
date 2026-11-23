package me.mudkip.moememos.util

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import timber.log.Timber

/**
 * Live, best-effort transcription while recording, using the system speech recognizer.
 *
 * The recognizer ends a session after a pause in speech, so sessions are restarted until [stop]
 * and their final results are joined. Whether it hears the microphone while MediaRecorder also
 * records depends on the device; the transcript saved with the memo prefers the server result.
 * All methods must be called on the main thread.
 */
class LiveTranscriber(private val context: Context) {
    val isAvailable: Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    private val committed = StringBuilder()
    private var partial by mutableStateOf("")
    private var committedText by mutableStateOf("")

    /** Final results so far plus the phrase currently being recognized. */
    val text: String get() = listOf(committedText, partial).filter { it.isNotBlank() }.joinToString(" ")

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            commit(results)
            restart()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
        }

        override fun onError(error: Int) {
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                listening = false
                return
            }
            commitPartial()
            // If pause() was called, do not restart
            if (!listening) return
            // "No match" and timeouts just mean silence; keep listening.
            handler.postDelayed({ if (listening) restart() }, if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 500 else 100)
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    fun start() {
        if (!isAvailable || listening) return
        listening = true
        recognizer = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }
            .onFailure { Timber.w(it) }
            .getOrNull()
            ?.also { it.setRecognitionListener(listener) }
        listen()
    }

    fun pause() {
        if (!listening) return
        listening = false
        handler.removeCallbacksAndMessages(null)
        commitPartial()
        runCatching { recognizer?.cancel() }
    }

    fun resume() {
        if (!isAvailable || listening) return
        if (recognizer == null) {
            recognizer = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }
                .onFailure { Timber.w(it) }
                .getOrNull()
                ?.also { it.setRecognitionListener(listener) }
        }
        if (recognizer == null) return
        listening = true
        listen()
    }

    fun stop() {
        pause()
    }

    fun destroy() {
        stop()
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun restart() {
        if (listening) listen()
    }

    private fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, context.resources.configuration.locales[0].toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
        }
        runCatching { recognizer?.startListening(intent) }.onFailure { Timber.w(it) }
    }

    private fun commit(results: Bundle?) {
        val best = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        partial = ""
        append(best)
    }

    private fun commitPartial() {
        val pending = partial
        partial = ""
        append(pending)
    }

    private fun append(phrase: String?) {
        if (phrase.isNullOrBlank()) return
        if (committed.isNotEmpty()) committed.append(' ')
        committed.append(phrase.trim())
        committedText = committed.toString()
    }
}
