# Offline voice engine boundary

Voice transcription implements a single fail-closed interface:

```kotlin
interface VoiceEngine {
    val name: String
    val isAvailable: Boolean
    val storesAudio: Boolean
    suspend fun transcribe(audio: AudioInput): Transcript
}
```

Current implementations are:

- `AndroidOnDeviceVoiceEngine` — microphone input through Android's explicitly on-device
  recognizer on API 31+;
- `LocalPcmVoiceEngine` — adapter for an optional Whisper/Vosk model pack with no Android or
  network authority; its mutable PCM buffer is zeroed in `finally` after success, failure,
  unavailability, or cancellation;
- `UnavailableVoiceEngine` — explicit fail-closed fallback, never a cloud recognizer.

The platform engine requires an explicit runtime microphone grant and a user tap for each session.
It uses `createOnDeviceSpeechRecognizer` and `EXTRA_PREFER_OFFLINE`; it never calls the generic
recognizer factory. Android owns its transient microphone buffer, and Mobet destroys the recognizer
on result, error, cancellation, dialog dismissal, or activity destruction.

A transcript is immutable, starts with `reviewed=false`, and is inserted into the editable goal
field. Dictation never starts execution. The user must inspect the transcript, provide separate
completion evidence, and press **Start run**; all normal planning, confirmation, policy, package,
and evidence gates remain authoritative. Transcript contents are not copied into diagnostics or
the audit ledger.

Bundled local models remain optional because of APK size, memory, battery, and cold-start cost. A
future model pack must connect through `LocalPcmVoiceEngine` or an equivalent implementation that
preserves buffer clearing and cannot add network fallback.
