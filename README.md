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

Online translation does **not** read an API key from the APK. The Android app
calls the Cloudflare Worker in `worker/`. Google Translate and DeepSeek keys
are Worker secrets.

1. Open the project in **Android Studio** (Neon / latest stable) and let Gradle
   sync resolve `compileSdk 35`, `AGP 8.5.2`, and Kotlin 2.0.x.
2. From `worker/`, install dependencies and deploy:

   ```
   npm install
   npx wrangler deploy
   ```

3. Put the provider keys on the Worker (they are not Gradle properties and
   they are not written into `BuildConfig`):

   ```
   npx wrangler secret put GOOGLE_API_KEY
   npx wrangler secret put DEEPSEEK_API_KEY
   ```

   Optional daily cap per IP (the code default is 200 when this is unset):

   ```
   npx wrangler secret put RATE_LIMIT_PER_DAY
   ```

4. Run on a device/emulator with:
   - Android 7.0 (API 24) or newer,
   - a working microphone and a TTS engine supporting Japanese/Korean
     (Google TTS is bundled with modern devices).

Offline mode (ML Kit) still works with no Worker secrets. The one-time course
purchase (`premium_unlock`) is confirmed by Play Billing. Server-side
confirmation needs the Play service account described in
[docs/SEGURIDAD_SETUP.md](docs/SEGURIDAD_SETUP.md). Until that secret is set,
`POST /verify-purchase` returns `play_verifier_not_configured` and the app
keeps the Play-Billing-only unlock.

## Permissions

Declared in `AndroidManifest.xml` and requested at runtime:

- `RECORD_AUDIO` — microphone capture for STT
- `INTERNET` + `ACCESS_NETWORK_STATE` — Worker calls and ML Kit model downloads

Auto Backup is off (`android:allowBackup="false"`), so course progress and the
purchase flag are not copied to the user's Google account.
