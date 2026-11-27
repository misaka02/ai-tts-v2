# AI-TTS v2 重构工程日志 (WORKLOG)

- **工作区**: `E:\antigravity\workspace\ai-tts-v2`
- **基线提交**: `main` @ `650ded0`
- **审计来源**: `E:\antigravity\workspace\ai-tts-android` (`feature/pulse-theme-v4` @ `0706aa1`)

## 1. 已完成的阶段与工程动作

### 阶段 A：v1 全面审计与需求/架构规划
1. 完成旧仓 `ai-tts-android` 代码全量审计，形成 [AUDIT_REPORT.md](./AUDIT_REPORT.md) 与 [ARCHITECTURE.md](./ARCHITECTURE.md)。
2. 明确并落实核心架构决策：
   - **彻底三模块分层**：`:core-model`（纯 Kotlin 领域模型）→ `:core-engine`（零 `android.*` 依赖的纯 Kotlin 合成/文本/音频/缓存/Provider 引擎）→ `:app`（Android Platform、TTS Service、Compose UI、Keystore/DataStore）。
   - **清理高风险/失效设计**：移除 `MediaProjection` 屏幕取色链路；移除离线引擎的反射桥接，改用 `cloud`/`full` 双 Gradle Flavor 编译期隔离；将 API Key/Token 从明文偏好与口令分享中剥离，统一写入 Android Keystore (`SecretStore`)。
   - **合并重复实现**：将 OpenAI / SiliconFlow / StepFun 等合并为统一的 `OpenAiCompatibleProvider`；按能力目录 `ProviderCapabilities` 驱动编辑页与能力裁剪。

### 阶段 B：基础设施与静态质量门禁搭建
1. 初始化独立 Git 仓库（`main` 分支），配置 `gradle/libs.versions.toml`（AGP `8.4.2`、Kotlin `2.0.0`、Detekt `1.23.6`）。
2. 配置严格代码规范门禁：
   - [config/detekt/detekt.yml](../config/detekt/detekt.yml)：`maxIssues: 0`、`MaxLineLength: 140`、`LongMethod: 80`。
   - [scripts/check_architecture.py](../scripts/check_architecture.py)：校验 `:core-model` 与 `:core-engine` 不含 `android.*` 依赖、禁止未注解的静态 `GlobalScope`/`OkHttpClient` 泄漏、`src/main` 单 `.kt` 文件不超过 350 行等结构约束。
   - 根 [build.gradle.kts](../build.gradle.kts) 注册 `verifyArchitecture` 与 `qualityGate` 任务。

### 阶段 C：模块实现与问题修复
1. **`:core-model`（11 个 Kotlin 文件，1,027 行）**
   - 拆分 `ProviderType`、`ProviderCapabilities`、`TtsProviderConfig`、`ProviderCredentials`、`VoiceAndRules`、`SegmentModels`、`GlobalSettings`、`RuntimeAndBackupModels`、`PresetVoices`、`PresetCatalog`、`OfflineCatalogData`。
   - 为避免与本机 JDK 21 工具链探测冲突，统一使用 `JavaVersion.VERSION_17` + `JvmTarget.JVM_17`。
2. **`:core-engine`（37 个 Kotlin 文件，4,017 行 + `Sonic.java`）**
   - 文本管道：`TextCleanerAndRules`、`DialogueAnalyzer`、`DialogueSegmenter`、`ChineseNumberNormalizer`、`AcronymNormalizer`、`TextPipeline`。
   - 音频管道：`Sonic.java`（剥离原 UTF-8 BOM）、`AudioFormatDetector`、`WavCodec`、`PcmFrameAligner`、`PcmResampler`、`SonicSpeedScaler`、`AudioEnhancer`、`AudioPipeline`。
   - 网络、缓存与离线下载：`HttpClientManager`、`SseReader`、`AudioDiskCache`、`OfflineModelInstaller`（修复 `extractTarBz2` 嵌套深度以通过 Detekt `NestedBlockDepth`）。
   - 云端引擎与编排：`EdgeTtsProvider`、`OpenAiCompatibleProvider`、`MimoTtsProvider`、`GeminiTtsProvider`、`MiniMaxTtsProvider`、`DoubaoTtsProvider`、`FishAudioTtsProvider`、`AzureTtsProvider`、`CustomHttpProvider`、`TtsProviderRegistry`、`SynthesisOrchestrator`。
3. **`:app`（39 个 Kotlin 文件，4,845 行）**
   - 数据与安全：`SecretStore`（AES-256-GCM）、`AppPreferencesRepository`（DataStore）、`BackupManager`、`QuoteRepository`。
   - 音频与离线平台：`AndroidAudioDecoder`、`StreamingAudioTrackPlayer`、`MediaStoreAudioExporter`、`PlatformOfflineBackend`（`cloud` 空实现 / `full` Sherpa-ONNX JNI）。
   - 服务与字幕：`AiTextToSpeechService`（使用 `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)` 修复 Android 14 Lint `UnspecifiedRegisterReceiverFlag`）、`PlaybackNotificationController`、`SleepTimerController`、`SubtitleTouchHandler`、`FloatingSubtitleOverlay`。
   - UI 页面：拆分大文件为短小组件（`SpeechHubScreen`/`HubSheets`、`ProvidersScreen`/`ProviderEditScreen`/`ProviderEditSections`/`OfflineModelsSheet`、`RulesScreen`/`RuleDialogs`、`SettingsScreen`/`SettingsDialogs`/`SubtitleCustomizerSheet`/`LogViewerSheet`），替换 `SettingsViewModel` 中的作用域兜底为 `viewModelScope`，替换 `Icons.AutoMirrored.Filled.Sort` 消除弃用编译警告。

## 2. 验证结果记录（最终代码修改后实测）
1. `python scripts/check_architecture.py`：退出码 `0`，输出 `Architecture check PASSED (0 violations).`。
2. `.\gradlew.bat detekt :core-model:test :core-engine:test :app:testCloudDebugUnitTest :app:lintCloudDebug :app:assembleCloudDebug :app:assembleFullDebug --no-daemon`：退出码 `0`（`BUILD SUCCESSFUL in 54s`）。
   - `:core-model:test`：`tests=4, failures=0, errors=0, skipped=0`
   - `:core-engine:test`：`tests=19, failures=0, errors=0, skipped=0`
   - `:app:testCloudDebugUnitTest`：`tests=2, failures=0, errors=0, skipped=0`
   - **测试总计**：`25` 项测试全部通过（0 失败 / 0 错误 / 0 跳过）。
   - `:detekt` 与 `:app:lintCloudDebug`：0 报错。
3. **APK 产物**：
   - `app/build/outputs/apk/cloud/debug/app-cloud-debug.apk`：`18,318,498` 字节（约 17.47 MiB）
   - `app/build/outputs/apk/full/debug/app-full-debug.apk`：`42,041,691` 字节（约 40.09 MiB）

## 3. 插件审查执行记录
- **Qodo CLI (`2.7.1`)**：在 commit `650ded0` 后执行 `C:\Users\s1356\.qodo\bin\qodo.exe review --json --context-file -`，因本本地新建仓库尚未配置 `origin` 远程追踪分支而阻塞，返回：
  `{"error":{"message":"could not determine the repository — run inside a git repo with an `origin` remote, or pass --repo <repository>","trace_id":"c54d15bf9b95ab30bca3d3c4999dcb7d"}}`
- **SonarQube**：本机未安装 `sonar` CLI 且未连接 SonarQube MCP 服务，已使用本地 `Detekt` + `Android Lint` + `check_architecture.py` 替代静态检查。

## 4. R8 Release 压缩与签名配置记录（2026-11-27）
1. **构建配置完善**：
   - 在 [app/build.gradle.kts](../app/build.gradle.kts) 中加入基于环境变量（`AITTS_KEYSTORE_FILE` / `AITTS_KEYSTORE_PASSWORD` / `AITTS_KEY_ALIAS` / `AITTS_KEY_PASSWORD`）的 `release` 签名配置与 `debug` 签名兜底，确保 `isMinifyEnabled = true`、`isShrinkResources = true` 产出的 Release APK 可直接安装。
   - 在 [app/proguard-rules.pro](../app/proguard-rules.pro) 中补充 R8 Full Mode 下的 `kotlinx.serialization` 序列化规则、Sherpa-ONNX 保留规则与 `commons-compress` 未使用可选编解码器 `-dontwarn`。
   - 在根 [build.gradle.kts](../build.gradle.kts) 添加聚合任务 `assembleR8Release`，并在 [README.md](../README.md) 记录构建命令。
2. **R8 构建与签名核验结果**：
   - `.\gradlew.bat assembleR8Release --no-daemon`：退出码 `0`（`BUILD SUCCESSFUL in 2m 18s`，`105 actionable tasks`）。
   - `python scripts/check_architecture.py`：退出码 `0`（`0 violations`）。
   - **`cloud` R8 Release APK**：`app/build/outputs/apk/cloud/release/app-cloud-release.apk` — `1,778,797` 字节（约 `1.70 MiB`，较 `cloudDebug` 的 `18,318,498` 字节缩减约 `90.29%`）。
   - **`full` R8 Release APK**：`app/build/outputs/apk/full/release/app-full-release.apk` — `25,469,263` 字节（约 `24.29 MiB`，较 `fullDebug` 的 `42,041,691` 字节缩减约 `39.42%`，含 `23,647,720` 字节的 `lib/arm64-v8a/libsherpa-onnx-jni.so`）。
   - **R8 Mapping 文件**：
     - `app/build/outputs/mapping/cloudRelease/mapping.txt`：`32,688,192` 字节
     - `app/build/outputs/mapping/fullRelease/mapping.txt`：`33,148,328` 字节
   - **`apksigner.bat verify --print-certs`（build-tools `36.0.0`）**：双包均验证通过（退出码 `0`，APK Signature Scheme v2，Signer: `CN=Android Debug, O=Android, C=US`）。
