# Traductor de Voz → Japones Romaji / Coreano Fonetico

Single-module Android app for **real-time voice-to-voice translation** from
Spanish into either **Japanese Romaji** or **Spanish-phonetic Korean**, powered
by the **DeepSeek API** with an **ML Kit offline fallback**.

## Features

- **Speech-to-Text (STT):** Native `SpeechRecognizer` tuned for Spanish
  (`es-MX` / `es-ES`) with live partial results.
- **Translation (online):** DeepSeek `deepseek-chat` at `temperature: 0.1`,
  returning strict JSON (`mainTranslation` + `alternatives`).
  - Japanese → main translation and alternatives rendered in **Romaji only**.
  - Korean → main translation and alternatives rendered in **Spanish phonetic
    pronunciation** (e.g. `An-nyeong-ha-se-yo`).
- **Translation (offline):** ML Kit (`com.google.mlkit:translate`) on-device
  translation with an on-demand model download flow + progress indicator.
- **Text-to-Speech (TTS):** Native `TextToSpeech` configured for `Locale.JAPAN` /
  `Locale.KOREA`, **auto-plays** each translation as soon as it arrives.
- **Modern dark Material3 UI** with language chips, offline toggle, download
  button, live status (`Listo` / `Escuchando…` / `Traduciendo…` / `Reproduciendo`),
  a large result box (30sp main text + alternatives), and a circular mic button.
- Single unified `StateFlow` UI state in `MainViewModel`.

## Tech stack

- Kotlin + Jetpack Compose (Material3, dark color scheme)
- Ktor Client (CIO) + `kotlinx.serialization` JSON
- AndroidX Lifecycle/ViewModel (single `StateFlow`)
- ML Kit Translation `com.google.mlkit:translate:17.0.3`
- Native `SpeechRecognizer` + `TextToSpeech`

## Architecture

```
com.arnold.voicetranslator
├── MainActivity.kt            # Compose UI + runtime permission handling
├── ui
│   ├── MainViewModel.kt       # StateFlow orchestration of the whole pipeline
│   └── state
│       └── TranslatorUiState.kt
├── audio
│   ├── SpeechRecognitionManager.kt   # native STT (Spanish)
│   └── TtsManager.kt                 # native TTS (Japanese / Korean)
└── data
    ├── model                  # DeepSeek DTOs, TargetLanguage, TranslationResult
    ├── remote
    │   ├── DeepSeekApiClient.kt      # Ktor CIO HTTP client
    │   └── DeepSeekPromptBuilder.kt  # dynamic system prompts
    └── offline
        └── MlKitOfflineTranslator.kt # ML Kit translation + model download
```

## Setup

1. Open the project in **Android Studio** (Neon / latest stable) and let Gradle
   sync resolve `compileSdk 35`, `AGP 8.5.2`, and Kotlin 2.0.x.
2. **Provide your DeepSeek API key** so online translation works. Add it to
   `local.properties` (do not commit it):

   ```
   deepseek.apiKey=sk-xxxxxxxxxxxxxxxx`
   ```

   If no key is provided, the button still works but will show a clear
   "no API key configured" message; you can still use **offline mode**.

3. Run on a device/emulator with:
   - Android 7.0 (API 24) or newer,
   - a working microphone and a TTS engine supporting Japanese/Korean
     (Google TTS is bundled with modern devices).

## Permissions

Declared in `AndroidManifest.xml` and requested at runtime:

- `RECORD_AUDIO` — microphone capture for STT
- `INTERNET` + `ACCESS_NETWORK_STATE` — DeepSeek calls and ML Kit model downloads

## API key for release builds

The key is compiled into `BuildConfig.DEEPSEEK_API_KEY` from the
`deepseek.apiKey` Gradle property. For production, consider moving key loading
to a secure backend or runtime config instead of embedding it in the binary.
