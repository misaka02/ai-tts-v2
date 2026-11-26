# AI-TTS Android v1 Code Audit Report

- **Target Repository**: `misaka02/ai-tts-android` (`feature/pulse-theme-v4` @ `0706aa1`, `main` @ `9a47e62`)
- **Audit Scope**: Full codebase review across build configs, service pipeline, audio DSP, providers, offline runtime, UI shells, and unit tests.

## Key Architectural & Reliability Findings

1. **Severe UI Duplication & Dead Code (~74.5% UI bloat)**:
   - 37,421 total lines with 26,927 lines in `ui/`, including 7,532+ lines of 0-reference dead screens (`ui/screens/*`, `FloatingMasterDock`) and 2 parallel active shells (`GoogleMaterialAppShell` vs `PulseAppShell` with 4 redundant hub variants).
   - Zero ViewModel / UDF layer; single Composable files up to 1,903 lines directly invoke network calls, `MediaPlayer`, and singletons.
2. **P0 Audio & Streaming Bugs**:
   - `OpenAiTtsProvider` and `SiliconFlowTtsProvider` emit raw TCP `read()` byte chunks into PCM16 streams without 2-byte frame alignment, causing byte-shift white noise on odd-byte reads.
   - `MiniMaxTtsProvider` streaming fallback calls batch `synthesize()` (MP3) and emits compressed MP3 bytes into the raw PCM stream callback.
   - `AudioDecoder` falls back to returning raw compressed MP3 bytes as PCM on `MediaCodec` error and ignores `INFO_OUTPUT_FORMAT_CHANGED`.
   - `TtsSynthesizer` silently swallows streaming failures without retry/fallback or `callback.error()`.
3. **P0 Offline Model Mislabelling & Crashes**:
   - `OfflineModelManager` mislabels `vits-melo-tts-zh_en` as GPT-SoVITS, `zipvoice` as ChatTTS, `matcha-baker` as CosyVoice / Yunyang, and Piper models as Microsoft Xiaoxiao/Yunxi/Xiaoyi.
   - `OfflineTtsProvider` detects model architecture via `modelId.contains("matcha" / "zipvoice")`, causing aliased models to initialize as VITS and crash native JNI; default provider `modelName` IDs in `PresetConfigs` do not exist in catalog.
   - Archive extraction swallows errors and still writes `model_ready_v2.flag`.
4. **P0 Android 14 Crash, Privacy & Resource Leaks**:
   - `ScreenColorSampler` invokes `MediaProjection` without foreground `mediaProjection` service notification (`SecurityException` on targetSdk 34).
   - Dangerous/unnecessary permissions: `MANAGE_EXTERNAL_STORAGE`, `RECORD_AUDIO`, and `BaseDexClassLoader` reflection injection.
   - 10 cloud providers leak OkHttp `ResponseBody` connections by calling `.execute()` without `.use {}`.
   - `ConfigDataStore.updateSettings` recreates `SharedHttpClient.instance` on every UI tweak, leaking dispatchers and breaking `cancelSession()`.
   - `TtsSynthesizer` uses a single shared `AtomicBoolean isStopped` across concurrent `onStop()` / `onSynthesizeText()` calls.
   - `ConfigDataStore` uses plaintext `SharedPreferences` with `allowBackup="true"`, leaks `secondaryApiKey` in `desensitize=true` exports, and sends Gemini keys in URL query params.
   - Text pipeline strips quotes and rewrites URLs before user rules and dialogue segmentation execute; `convertIntToChinese(100_000_000)` produces `"一亿万"`.
