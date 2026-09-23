package ai.arena.mobet.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Arrays

sealed interface AudioInput {
    /** Explicit, user-initiated microphone session. Android owns the transient audio buffer. */
    data class Microphone(val onPartialTranscript: (String) -> Unit = {}) : AudioInput

    /** Mutable local audio for optional bundled engines. [clear] must run after every attempt. */
    class Pcm16(
        val samples: ByteArray,
        val sampleRateHz: Int,
        val channels: Int = 1
    ) : AudioInput {
        fun clear() = Arrays.fill(samples, 0)
    }
}

data class Transcript(
    val text: String,
    val confidence: Double?,
    val engine: String,
    /** Engines never mark their own output reviewed; only an explicit UI action can do that. */
    val reviewed: Boolean = false
)

interface VoiceEngine {
    val name: String
    val isAvailable: Boolean
    val storesAudio: Boolean get() = false
    suspend fun transcribe(audio: AudioInput): Transcript
}

class VoiceUnavailableException(message: String) : IllegalStateException(message)

/** Android platform recognizer, accepted only when an on-device service is advertised. */
class AndroidOnDeviceVoiceEngine(private val context: Context) : VoiceEngine {
    override val name = "Android on-device SpeechRecognizer"
    override val isAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    override suspend fun transcribe(audio: AudioInput): Transcript {
        require(audio is AudioInput.Microphone) { "$name accepts microphone input only" }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !isAvailable) {
            throw VoiceUnavailableException("On-device speech recognition is unavailable")
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) throw SecurityException("Microphone permission is required")

        return suspendCancellableCoroutine { continuation ->
            val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            var finished = false
            fun close(cancel: Boolean = false) {
                if (!finished) {
                    finished = true
                    if (cancel) recognizer.cancel()
                    recognizer.destroy()
                }
            }
            fun fail(message: String) {
                if (finished || !continuation.isActive) return
                close()
                continuation.resumeWith(Result.failure(VoiceUnavailableException(message)))
            }
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle) {
                    if (!continuation.isActive) { close(); return }
                    val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty().trim()
                    val confidence = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                        ?.firstOrNull()?.takeIf { it >= 0f }?.toDouble()
                    close()
                    if (text.isBlank()) continuation.resumeWith(
                        Result.failure(VoiceUnavailableException("No speech recognized"))
                    ) else continuation.resumeWith(Result.success(Transcript(text, confidence, name)))
                }

                override fun onPartialResults(partialResults: Bundle) {
                    val partial = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty().trim()
                    if (partial.isNotBlank()) audio.onPartialTranscript(partial.take(500))
                }

                override fun onError(error: Int) = fail(
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                            "No speech recognized"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                            "Microphone permission missing"
                        else -> "On-device recognition unavailable right now"
                    }
                )
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            continuation.invokeOnCancellation { close(cancel = true) }
            recognizer.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            )
        }
    }
}

/** Explicit fail-closed engine used when no local recognizer or model pack is installed. */
class UnavailableVoiceEngine(
    override val name: String = "No offline voice engine",
    private val reason: String = "No offline voice engine is available"
) : VoiceEngine {
    override val isAvailable = false
    override suspend fun transcribe(audio: AudioInput): Transcript {
        if (audio is AudioInput.Pcm16) audio.clear()
        throw VoiceUnavailableException(reason)
    }
}

/**
 * Adapter for an optional Whisper/Vosk-style local model pack. Audio is always zeroed in finally,
 * including cancellation and backend failure, and the backend receives no Context or network API.
 */
class LocalPcmVoiceEngine(
    override val name: String,
    override val isAvailable: Boolean,
    private val backend: suspend (samples: ByteArray, sampleRateHz: Int, channels: Int) -> String
) : VoiceEngine {
    override suspend fun transcribe(audio: AudioInput): Transcript {
        require(audio is AudioInput.Pcm16) { "$name requires local PCM16 input" }
        return try {
            if (!isAvailable) throw VoiceUnavailableException("$name model pack is unavailable")
            val text = backend(audio.samples, audio.sampleRateHz, audio.channels).trim()
            if (text.isBlank()) throw VoiceUnavailableException("No speech recognized")
            Transcript(text, null, name)
        } finally {
            audio.clear()
        }
    }
}

object VoiceEngines {
    fun select(context: Context, optionalLocalEngine: VoiceEngine? = null): VoiceEngine {
        val android = AndroidOnDeviceVoiceEngine(context)
        return when {
            android.isAvailable -> android
            optionalLocalEngine?.isAvailable == true -> optionalLocalEngine
            else -> UnavailableVoiceEngine()
        }
    }
}
