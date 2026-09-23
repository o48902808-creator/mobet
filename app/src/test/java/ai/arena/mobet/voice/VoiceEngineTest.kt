package ai.arena.mobet.voice

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceEngineTest {
    @Test fun localEngineClearsAudioAfterSuccess() = runBlocking {
        val audio = AudioInput.Pcm16(byteArrayOf(1, 2, 3, 4), 16_000)
        val engine = LocalPcmVoiceEngine("Vosk local", true) { samples, rate, channels ->
            assertEquals(16_000, rate)
            assertEquals(1, channels)
            assertTrue(samples.any { it.toInt() != 0 })
            "turn on dark mode"
        }
        val transcript = engine.transcribe(audio)
        assertEquals("turn on dark mode", transcript.text)
        assertFalse(transcript.reviewed)
        assertTrue(audio.samples.all { it.toInt() == 0 })
    }

    @Test fun localEngineClearsAudioAfterBackendFailure() = runBlocking {
        val audio = AudioInput.Pcm16(byteArrayOf(9, 8, 7), 16_000)
        val engine = LocalPcmVoiceEngine("Whisper local", true) { _, _, _ -> error("model failed") }
        assertTrue(runCatching { engine.transcribe(audio) }.isFailure)
        assertTrue(audio.samples.all { it.toInt() == 0 })
    }

    @Test fun unavailableModelNeverCallsBackendAndClearsInput() = runBlocking {
        val audio = AudioInput.Pcm16(byteArrayOf(1), 16_000)
        var invoked = false
        val engine = LocalPcmVoiceEngine("Optional pack", false) { _, _, _ -> invoked = true; "x" }
        assertTrue(runCatching { engine.transcribe(audio) }.isFailure)
        assertFalse(invoked)
        assertTrue(audio.samples.all { it.toInt() == 0 })
    }

    @Test fun unavailableEngineFailsClosed() = runBlocking {
        val result = runCatching {
            UnavailableVoiceEngine().transcribe(AudioInput.Microphone())
        }
        assertTrue(result.isFailure)
    }
}
