# AI-TTS Android v2 Architecture Design

## 1. Module Boundaries (Compiler-Enforced)

- **`:core-model`** (`kotlin.jvm`, zero Android / OkHttp dependencies):
  - Immutable `@Serializable` domain models, `ProviderCapabilities`, `SpeedPolicy`, `ModelArch`, `PresetCatalog`.
- **`:core-engine`** (`kotlin.jvm`, zero Android dependencies):
  - `logging/`: thread-safe ring-buffer `SessionLogger`.
  - `network/`: `HttpClientManager` (rebuilds only on `NetworkConfig` diff, tracks calls per `sessionId`).
  - `text/`: strictly ordered 4-stage `TextPipeline` (Clean -> UserRules -> Segment/Role/Emotion -> Number/Acronym Normalization).
  - `audio/`: `WavCodec` (single source of truth), `PcmFrameAligner` (2-byte PCM16 frame boundary safety), `PcmResampler`, `SonicSpeedScaler`, stateful `AudioEnhancer`, `AudioPipeline`.
  - `cache/`: `AudioDiskCache` (read/write locked atomic LRU strictly honoring `maxCacheSizeMb`).
  - `provider/`: unified `TtsProvider` contract + `EdgeTtsProvider`, `MimoTtsProvider`, `GeminiTtsProvider`, `OpenAiCompatibleProvider` (covering OpenAI / SiliconFlow / StepFun / relays), `MiniMaxTtsProvider`, `DoubaoTtsProvider`, `FishAudioTtsProvider`, `AzureTtsProvider`, `CustomHttpProvider`.
  - `offline/`: honest `OfflineModelCatalog` (`ModelArch` enum) & `OfflineModelInstaller` (ZipSlip + integrity validation).
  - `synthesis/`: `SynthesisOrchestrator` (per-session `Job` map, sliding-window prefetch, single-layer retry, safe pre-first-byte fallback).
- **`:app`** (`android.application`, flavors `cloud` & `full`):
  - `data/`: Android Keystore AES-256-GCM `SecretStore`, Jetpack `DataStore` `AppPreferencesRepository`, `BackupManager` (desensitized by default).
  - `audio/`: `AndroidAudioDecoder` (`MediaExtractor` + `MediaCodec` handling `INFO_OUTPUT_FORMAT_CHANGED`), `StreamingAudioTrackPlayer` (live PCM streaming + RMS meter without `RECORD_AUDIO`), `MediaStoreAudioExporter` (zero storage permission).
  - `offline/`: `OfflineTtsBackend` isolated cleanly by `cloud` vs `full` product flavor (`System.loadLibrary("sherpa-onnx-jni")` on `full`, no reflection).
  - `service/`: `AiTextToSpeechService` (proper `AudioFocusRequest`, session-isolated `onStop`), `PlaybackNotificationController`, `FloatingSubtitleOverlay` (system dark/light + 5 novel paper presets, no `MediaProjection`), `SleepTimerController`.
  - `ui/`: single cohesive Material 3 UI with `ViewModel` + UDF, <= 350 lines per `.kt` file.

## 2. Static Quality & Hygiene Gates

1. `scripts/check_architecture.py`: enforces <= 350 lines per `src/main` `.kt` file, module import boundaries, zero banned APIs/permissions, mandatory `.use {}` on OkHttp `.execute()`.
2. `detekt`: zero issues allowed (`maxIssues: 0`).
3. `Android Lint`, `core-model:test`, `core-engine:test` (`MockWebServer`), `app:testCloudDebugUnitTest`.
4. Qodo CLI review before final handoff.
