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

## 5. 全局规则持久化与 GitHub 上线发布（2026-11-27）
1. **全局 R8 规则持久化**：已将「Android 编译默认交付 R8 压缩签名包」硬性规范写入 `C:\Users\s1356\.gemini\GEMINI.md`（第 4 条）、`C:\Users\s1356\.gemini\config\rules\AGENTS.md`（第 6 条）及 `C:\Users\s1356\.gemini\config\plugins\user-workspace-rules\rules\AGENTS.md`（第 6 条）。
2. **GitHub Actions CI 更新**：更新 [.github/workflows/ci.yml](../.github/workflows/ci.yml)，将默认构建任务升级为 `./gradlew assembleR8Release --no-daemon` 并上传 R8 Release 工件。
3. **GitHub 仓库创建与 v2.0.0 发布**：
   - 新建公开仓库 `https://github.com/misaka02/ai-tts-v2` 并推送 `main` 分支。
   - 推送标签 `v2.0.0` 并发布 GitHub Release：`https://github.com/misaka02/ai-tts-v2/releases/tag/v2.0.0`，已上传附件 `ai-tts-v2-cloud-v2.0.0.apk`（`1,778,797` 字节）与 `ai-tts-v2-full-arm64-v2.0.0.apk`（`25,469,263` 字节）。

## 6. 纸墨听书 UI 重设计与全功能缺陷修复（2026-11-27）
1. **底层功能缺陷全量核查与修复**：
   - **流式历史漏记** ([SynthesisOrchestrator.kt](../core-engine/src/main/kotlin/com/aitts/v2/engine/synthesis/SynthesisOrchestrator.kt))：`streamOrFallback` 缓存命中与原生流式成功返回时补全 `SynthesisHelpers.recordHistory` 调用，并在 [SynthesisOrchestratorTest.kt](../core-engine/src/test/kotlin/com/aitts/v2/engine/synthesis/SynthesisOrchestratorTest.kt) 增加单测。
   - **系统 TTS 多音色路由** ([AiTextToSpeechService.kt](../app/src/main/java/com/aitts/v2/service/AiTextToSpeechService.kt))：新增 `resolveProviderForRequest(request.voiceName)` 按配置 ID/名称或 Edge 音色名路由，不再忽略外部应用传入音色。
   - **睡眠定时与界面状态同步** ([SleepTimerController.kt](../app/src/main/java/com/aitts/v2/service/SleepTimerController.kt)、[SpeechHubViewModel.kt](../app/src/main/java/com/aitts/v2/ui/hub/SpeechHubViewModel.kt))：增加 `expiredFlow` 自动中止应用内试听与播报。
   - **悬浮字幕坐标与关闭同步** ([GlobalSettings.kt](../core-model/src/main/kotlin/com/aitts/v2/model/GlobalSettings.kt)、[SubtitleTouchHandler.kt](../app/src/main/java/com/aitts/v2/service/SubtitleTouchHandler.kt)、[FloatingSubtitleOverlay.kt](../app/src/main/java/com/aitts/v2/service/FloatingSubtitleOverlay.kt)、[AppContainer.kt](../app/src/main/java/com/aitts/v2/di/AppContainer.kt))：持久化拖拽纵坐标 `floatingSubtitleY`（旧配置缺省 `-1` 向后兼容），关闭弹窗回调同步偏好开关，App 内试听同步推送逐段字幕。
   - **删除首选引擎自动接替 & Keystore 容错** ([AppPreferencesRepository.kt](../app/src/main/java/com/aitts/v2/data/AppPreferencesRepository.kt)、[SecretStore.kt](../app/src/main/java/com/aitts/v2/data/SecretStore.kt))。
   - **引擎试听效果对齐 & 实时权限状态** ([ProvidersViewModel.kt](../app/src/main/java/com/aitts/v2/ui/providers/ProvidersViewModel.kt)、[PermissionHelper.kt](../app/src/main/java/com/aitts/v2/permission/PermissionHelper.kt)、[SettingsViewModel.kt](../app/src/main/java/com/aitts/v2/ui/settings/SettingsViewModel.kt))：补齐客户端 Sonic 变速与 `AudioEnhancer` 预览增强、增加 `tts_default_synth` 默认 TTS 引擎检测及页面 `ON_RESUME` 自动刷新。
2. **「纸墨听书 (Editorial Ink)」全套 UI 重设计**：
   - 新增设计系统与组件：[Theme.kt](../app/src/main/java/com/aitts/v2/ui/theme/Theme.kt)、[InkComponents.kt](../app/src/main/java/com/aitts/v2/ui/theme/InkComponents.kt)、[PillTone.kt](../app/src/main/java/com/aitts/v2/ui/theme/PillTone.kt)、定制底栏 [AiTtsAppRoot.kt](../app/src/main/java/com/aitts/v2/ui/navigation/AiTtsAppRoot.kt)。
   - 四页与二级弹窗重制：[SpeechHubScreen.kt](../app/src/main/java/com/aitts/v2/ui/hub/SpeechHubScreen.kt)、[SpeechHubCards.kt](../app/src/main/java/com/aitts/v2/ui/hub/SpeechHubCards.kt)、[VoiceTunerSheet.kt](../app/src/main/java/com/aitts/v2/ui/hub/VoiceTunerSheet.kt)、[ProvidersScreen.kt](../app/src/main/java/com/aitts/v2/ui/providers/ProvidersScreen.kt)、[ProviderEditScreen.kt](../app/src/main/java/com/aitts/v2/ui/providers/ProviderEditScreen.kt)（中文类型标签、密钥显隐遮罩、可用模型/音色双选择列表、离线库直达）、[RulesScreen.kt](../app/src/main/java/com/aitts/v2/ui/rules/RulesScreen.kt)（规范化开关实时联动）、[RuleDialogs.kt](../app/src/main/java/com/aitts/v2/ui/rules/RuleDialogs.kt)（正则语法实时校验）、[SettingsScreen.kt](../app/src/main/java/com/aitts/v2/ui/settings/SettingsScreen.kt)、[SettingsSections.kt](../app/src/main/java/com/aitts/v2/ui/settings/SettingsSections.kt)、[SettingsDialogs.kt](../app/src/main/java/com/aitts/v2/ui/settings/SettingsDialogs.kt)（补全 SAF JSON 文件导入导出与网络超时配置）、[SubtitleCustomizerSheet.kt](../app/src/main/java/com/aitts/v2/ui/settings/SubtitleCustomizerSheet.kt)（内置实时视觉预览卡片）。
3. **Qodo CLI 审查与修复闭环**：
   - 调用 `C:\Users\s1356\.qodo\bin\qodo.exe review --target main --json --context-file -` 针对 `origin/main`（`05853ebcf`）完整审查（`233,022B` 差异），返回 `4` 条发现：
     - 已修复 3 条有效缺陷：`ProvidersViewModel.stopPreview()` 退出未复位 `isTestingSingle`、`InkWaveformCanvas` 闲置状态无谓持续动画、`InkStatusPill` 深色模式状态文字对比度不足。
     - 独立核实 1 条为误报：`FloatingSubtitleOverlay.kt` 关闭按钮已携带 `EXTRA_FORCE_DISABLE = true`，接收端不会反转重开。
4. **最终门禁与 R8 产物核验（全部修改后实测）**：
   - `python scripts/check_architecture.py`：退出码 `0`（`0 violations`）。
   - `.\gradlew.bat detekt :core-model:test :core-engine:test :app:testCloudDebugUnitTest :app:lintCloudDebug assembleR8Release --no-daemon`：退出码 `0`（`BUILD SUCCESSFUL in 2m 1s`，`27/27` 单元测试全部通过：`:core-model:test` `5`、`:core-engine:test` `20`、`:app:testCloudDebugUnitTest` `2`）。
   - `app/build/outputs/apk/cloud/release/app-cloud-release.apk`：`1,827,949` 字节（约 `1.74 MiB`），`apksigner.bat` 校验通过（退出码 `0`）。
   - `app/build/outputs/apk/full/release/app-full-release.apk`：`25,502,031` 字节（约 `24.32 MiB`），`apksigner.bat` 校验通过（退出码 `0`）。
5. **Pre-release 自动发布规则持久化与 `v2.1.0-pre.1` 发布**：
   - 将「每次完成 Android 编译验收后默认发布 GitHub Pre-release（仅明确要求时才发正式版）」写入全局 `GEMINI.md` 与两份 `AGENTS.md`。
   - 更新 [.github/workflows/ci.yml](../.github/workflows/ci.yml) 支持连字符预发布标签自动标记 `prerelease: true`。
   - 推送标签 `v2.1.0-pre.1` 并发布 GitHub Pre-release：`https://github.com/misaka02/ai-tts-v2/releases/tag/v2.1.0-pre.1`（`isPrerelease: true`，附带 `ai-tts-v2-cloud-v2.1.0-pre.1.apk` 与 `ai-tts-v2-full-arm64-v2.1.0-pre.1.apk`）。

## 7. 精简重构、传统/AI引擎差异化与上游实时同步 (`v2.1.0-pre.2`, 2026-11-27)
1. **清理负优化与华而不实功能**：
   - 删除关键词猜情感/对话分段与双声部配音：`DialogueAnalyzer.kt`、`AcronymNormalizer.kt`、`DialogueSegmenter.kt` 与 `DualRole`/`dialogueVoiceId`/`volume`/`tags`。
   - 移除 4 套伪 DSP 音效预设（`CLARITY`/`WARM`/`NIGHT_PROTECT`/`CUSTOM` tanh 染色），保留无损直通 + 静音裁剪 + 睡眠定时淡出。
   - 移除 50 条写盘朗读历史、`aitts://` 口令分享、默认错别字多音字表（保留可手动开启的中文数字规范化，默认 `false`）及 13 个未配置预置节点，初始仅保留 1 个开箱即用的 `Edge TTS (晓晓)`。
   - 保留用户指定的 **一言 (`QuoteRepository.fetchOnlineQuote`)** 联网试听与 **极简系统自适应深浅色悬浮字幕**（[FloatingSubtitleOverlay.kt](../app/src/main/java/com/aitts/v2/service/FloatingSubtitleOverlay.kt)，移除 6 套纸张主题与色板，保留纵坐标拖拽持久化、单击展开、位置锁定及透明度/字号/对齐微调）。
2. **可扩展契约与传统 vs 大模型差异化调控**：
   - `:core-model` 新增 [ProviderDescriptor.kt](../core-model/src/main/kotlin/com/aitts/v2/model/ProviderDescriptor.kt)（`EngineCategory.TRADITIONAL` vs `LLM_GENERATIVE` 与 `ProviderParamSpec` 元数据目录）、[ProviderTemplates.kt](../core-model/src/main/kotlin/com/aitts/v2/model/ProviderTemplates.kt) 与 [SentenceSegment.kt](../core-model/src/main/kotlin/com/aitts/v2/model/SentenceSegment.kt)；[VoiceAndRules.kt](../core-model/src/main/kotlin/com/aitts/v2/model/VoiceAndRules.kt) 为 `VoiceModel` 增加 `styles`。
   - `:core-engine` 新增可扩展契约 [PipelineContracts.kt](../core-engine/src/main/kotlin/com/aitts/v2/engine/pipeline/PipelineContracts.kt)（`TextProcessor` / `AudioProcessor`）与 [TextSegmenter.kt](../core-engine/src/main/kotlin/com/aitts/v2/engine/text/TextSegmenter.kt)。
   - 传统引擎参数落地：Edge 实时 `/voices/list` 与 `gecVersion` 热更；Azure 实时拉取 `StyleList` 与 SSML `<mstts:express-as style="..." styledegree="...">`；豆包 `emotion`；Fish 动态路由 `/model?page_size=30` 与 `latency`；Sherpa 线程数（1..4）与 `silenceScale`；Custom HTTP 自定义 Headers 与一键 GPT-SoVITS/CosyVoice 模板。
   - AI 大模型参数落地：OpenAI 兼容 `instructions` 与 `/models` + `/audio/voice/list` 自动发现；MiniMax `POST /v1/get_voice`（含复刻/系统音色）与 `voice_setting.emotion`；Gemini / MiMo 原生提示词与 SSE 低延迟流式控制；按 `TtsProvider` 类型隔离硅基流动与 OpenAI 模板主机。
3. **上游清单实时同步与 4 项实用能力补齐**：
   - 新增 [catalog/manifest.json](../catalog/manifest.json)、[RemoteManifestRepository.kt](../core-engine/src/main/kotlin/com/aitts/v2/engine/manifest/RemoteManifestRepository.kt)（含 HTTPS + 官方域名白名单校验、离线模型安全 ID 与大小过滤、`ghfast.top` 镜像回退）与 24h 按凭据指纹隔离的 [UpstreamDiscovery.kt](../core-engine/src/main/kotlin/com/aitts/v2/engine/provider/UpstreamDiscovery.kt)（`DiscoveryCatalog` / `DiscoveryDiskCache`）。
   - 新增外部分享/划词“AI 朗读”（[MainActivity.kt](../app/src/main/java/com/aitts/v2/ui/MainActivity.kt) `ACTION_SEND` / `ACTION_PROCESS_TEXT` + [AppContainer.kt](../app/src/main/java/com/aitts/v2/di/AppContainer.kt) 缓冲 `Channel` + [AiTtsAppRoot.kt](../app/src/main/java/com/aitts/v2/ui/navigation/AiTtsAppRoot.kt) 自动跳转听书台）、系统官方 `TextToSpeech` 端到端自检（[SystemTtsSelfTester.kt](../app/src/main/java/com/aitts/v2/service/SystemTtsSelfTester.kt)）、通知栏快捷磁贴（[AiTtsQuickTileService.kt](../app/src/main/java/com/aitts/v2/service/AiTtsQuickTileService.kt)），以及声库卡片一键试听、模板弹窗创建与离线模型下载后一键启用。
4. **Qodo CLI 两轮审查与全部门禁验证（上限 2 轮已收口）**：
   - **Qodo 第 1 轮**（`operation_id: 96d4532c-93d3-4d26-a8fe-fa73d52756a4`，`origin/main` @ `7164fa1f6`）：识别 6 项新增有效问题（离线清单目录穿越校验、`model_spec.json` 动态离线模型元信息持久化与读取、`DiscoveryDiskCache` 按凭据指纹隔离、`ACTION_SEND` 富文本 `CharSequence` 兼容、全局缓冲 `Channel` 防并发丢请求及外部传入自动切回听书台），当轮全量修复并确认 4 项基线历史条目已解决。
   - **Qodo 第 2 轮增量复核**（`operation_id: def01fd9-bbd7-4d27-899c-c1b198ffa487`）：确认第 1 轮全部 6 项问题均为 `resolved`；随后批量修复第 2 轮指出的 6 项新细节（`RemoteManifestRepository` 官方域名白名单校验防止密钥外泄、`ProvidersViewModel` 保留硅基流动兼容端点且展示已装脱离清单离线模型、`OfflineModelInstaller` 下载/解压安全体积上限防爆盘、`ProviderEditScreen` 切换类型完整复位残留字段、`SettingsViewModel` 异步动作 `finally` 复位忙状态）并新增单测覆盖。
   - **最终离线全量门禁与 R8 打包实测**（全部修改完成后通过 `flash` 轻量子代理执行）：
     - `python scripts/check_architecture.py`：退出码 `0`（`0 violations`，90 个 `.kt` 文件 / 11,785 行）。
     - `.\gradlew.bat detekt :core-model:test :core-engine:test :app:testCloudDebugUnitTest :app:lintCloudDebug assembleR8Release --no-daemon`：退出码 `0`（`BUILD SUCCESSFUL in 1m 56s`），**34/34 单元测试全部通过**（`:core-model:test` 5、`:core-engine:test` 27、`:app:testCloudDebugUnitTest` 2），`Detekt` 0 issues，`Lint` 0 errors。
     - `cloud` R8 Release APK：`app/build/outputs/apk/cloud/release/app-cloud-release.apk` — `1,828,249` 字节（约 `1.74 MiB`），`apksigner.bat` 校验通过（退出码 `0`）。
     - `full` R8 Release APK：`app/build/outputs/apk/full/release/app-full-release.apk` — `25,502,331` 字节（约 `24.32 MiB`），`apksigner.bat` 校验通过（退出码 `0`）。

## 8. 全部 10 引擎声学规范统一与 Sonic 音调/残差补速对齐 (`v2.1.0-pre.3`, 2026-11-27)
1. **建立单一声学事实来源 ([EngineAcousticSpec.kt](../core-model/src/main/kotlin/com/aitts/v2/model/EngineAcousticSpec.kt))**：
   - 将所有 10 个引擎的原生语速区间、原生音调区间与单位语义集中建模于 `ProviderDescriptor.acousticSpec`，并由 `AcousticMapper` 统一映射到各家上游协议边界：
     - **Edge TTS**：语速 `0.5..2.0` (`-50%..+100%`)，音调改为规范的 `-50Hz..+50Hz`（对称映射到 `0.5..1.5`）。
     - **Azure Speech**：语速支持完整 `0.5..3.0` (`-50%..+200%`)，音调 `0.5..1.5` 钳位至 `-50%..+50%`。
     - **MiniMax Speech-02**：语速钳位 `0.5..2.0`；音调 `0.5..1.5` 对称映射为官方整数半音 `-12..+12`（修复此前低音仅到 `-6` 的不对称问题）。
     - **豆包 / 火山引擎**：`speed_ratio` 钳位 `0.5..2.0`；对 `*_bigtts` 大模型音色自动省略 `pitch_ratio`，标准音色钳位 `0.5..1.5`。
     - **OpenAI 兼容**：`speed` 钳位 `0.25..4.0`；对 `tts-1` / `tts-1-hd` 自动省略 `instructions`。
     - **Fish Audio / Sherpa-ONNX**：分别通过 `fishSpeed(0.5..2.0)` 与 `sherpaSpeed(0.5..2.5)` 精确钳位。
     - **Gemini / 小米 MiMo**：统一移除硬编码语速/音调英文/中文提示词注入及 MiMo 批处理冲突的 `audio.speed` 字段，仅保留用户 `promptInstruction`，精确语速与音调统一走客户端 Sonic。
2. **Sonic 残差倍速与客户端音调接管**：
   - 在 `SonicSpeedScaler.kt` 中启用 `Sonic.setPitch`，配合 `AcousticMapper.resolveSpeed` 将目标语速/音调拆分为「上游合法参数 + 本地 Sonic 残差补足」（如 MiniMax/Fish 请求 `2.5x` 时上游发 `2.0x`、Sonic 补 `1.25x`；Gemini/MiMo/豆包 `bigtts` 在系统朗读传入 `pitch` 时自动由 Sonic 无感接管），同步接入 [AudioPipeline.kt](../core-engine/src/main/kotlin/com/aitts/v2/engine/audio/AudioPipeline.kt)、[SynthesisOrchestrator.kt](../core-engine/src/main/kotlin/com/aitts/v2/engine/synthesis/SynthesisOrchestrator.kt) 与 [ProvidersViewModel.kt](../app/src/main/java/com/aitts/v2/ui/providers/ProvidersViewModel.kt)。
3. **调音 UI 严格遵循引擎能力**：
   - [VoiceTunerSheet.kt](../app/src/main/java/com/aitts/v2/ui/hub/VoiceTunerSheet.kt) 与 [ProviderEditScreen.kt](../app/src/main/java/com/aitts/v2/ui/providers/ProviderEditScreen.kt) 全面读取 `descriptor.acousticSpec` 与 `AcousticMapper.supportsNativePitch(type, voiceId)`：动态放开 Azure `3.0x` 与 OpenAI/Custom `4.0x` 保存上限、按引擎真实范围渲染音调条、隐藏豆包 `bigtts` 与纯大模型无效原生音调及非流式开关，并附带清晰机制说明。
4. **Qodo CLI 审查与最终验证闭环（`origin/main` @ `1c1398df2`）**：
   - **Qodo 审查**（`operation_id: 43ac1053-0ad7-49be-a4d8-d2e666e7ff80`）：0 项阻塞（`action_required`），识别出 3 项新增改进建议（`Gemini/MiMo` 系统音调失联、`ProviderEditScreen` 截断 Azure `2.6-3.0x` 语速、豆包 `bigtts` 音调滑块无实际效果），已全部完成架构级修复并新增对应单测覆盖。
   - **最终全量门禁与 R8 打包实测**（全部修改后经 `flash` 子代理执行，退出码 `0`）：
     - `python scripts/check_architecture.py`：退出码 `0`（`0 violations`，91 个 `.kt` 文件 / 11,981 行）。
     - `.\gradlew.bat detekt :core-model:test :core-engine:test :app:testCloudDebugUnitTest :app:lintCloudDebug assembleR8Release --no-daemon`：退出码 `0`（`BUILD SUCCESSFUL in 3m 7s`，**37/37 单元测试全部通过**：`:core-model:test` 6、`:core-engine:test` 29、`:app:testCloudDebugUnitTest` 2；`Detekt` 0 issues；`Lint` 0 errors）。
     - `cloud` R8 APK：`app/build/outputs/apk/cloud/release/app-cloud-release.apk` — `1,828,249` 字节（约 `1.74 MiB`），`apksigner` 校验通过（退出码 `0`）。
     - `full` R8 APK：`app/build/outputs/apk/full/release/app-full-release.apk` — `25,502,331` 字节（约 `24.32 MiB`），`apksigner` 校验通过（退出码 `0`）。


## 9. 内置电子书阅读器与原生 TTS 精准同步听书 (`v2.2.0-pre.1`, 2026-11-27)
1. **新增纯 JVM 模块 `:core-reader`（零 `android.*`/`okhttp3.*` 依赖）**：
   - 建立可扩展解析接口 [BookFormatParser.kt](../core-reader/src/main/kotlin/com/aitts/v2/reader/parser/BookFormatParser.kt) 与 `BookFormatRegistry`，并纳入 [settings.gradle.kts](../settings.gradle.kts)、根 [build.gradle.kts](../build.gradle.kts)（`detekt` + `:core-reader:test`）与 [check_architecture.py](../scripts/check_architecture.py) 守护门禁（全模块 `src/main/*.kt` 保持 `<= 350` 行）。
   - **TXT 流式大文件引擎**：[CharsetDetector.kt](../core-reader/src/main/kotlin/com/aitts/v2/reader/parser/CharsetDetector.kt) 自动识别 UTF-8（含/无 BOM）、UTF-16LE/BE 与 GB18030 并支持受限流式 UTF-8 转码；[TxtBookParser.kt](../core-reader/src/main/kotlin/com/aitts/v2/reader/parser/TxtBookParser.kt) 追踪精确字节偏移识别中英文正文章节标题、对无章节/超长章按 24KB 换行安全切分虚拟分片（兼容 UTF-16LE/BE `+2` 换行偏移与 UTF-8 字符边界兜底），并通过 `RandomAccessFile` 实现 O(1) 章节加载。
   - **EPUB 2/3 解析引擎**：[EpubXmlSupport.kt](../core-reader/src/main/kotlin/com/aitts/v2/reader/parser/EpubXmlSupport.kt) 防护 XXE/DTD 与 `..` 路径穿越（Zip Slip），兼容 EPUB 3 Nav 与 EPUB 2 NCX 目录；[EpubHtmlParser.kt](../core-reader/src/main/kotlin/com/aitts/v2/reader/parser/EpubHtmlParser.kt) 为纯 Kotlin 容错块级解析器，剥离 `<rt>/<rp>` 日文假名注音与 `<script>/<style>` 噪点，保留标题/引用/分隔线/内嵌图（含 `data:image/`）与 `<br>` 段落；[EpubBookParser.kt](../core-reader/src/main/kotlin/com/aitts/v2/reader/parser/EpubBookParser.kt) 建立 O(1) 小写 ZIP 条目索引表并限定单章 4MB / 单资源 12MB 边界读取。
   - **Markdown & HTML 解析**：[MarkdownBookParser.kt](../core-reader/src/main/kotlin/com/aitts/v2/reader/parser/MarkdownBookParser.kt) 支持围栏代码块过滤、精确字节偏移定位及 512KB 超长章节拆分；[HtmlBookParser.kt](../core-reader/src/main/kotlin/com/aitts/v2/reader/parser/HtmlBookParser.kt) 支持引言前缀章节保留、32MB 安全上限报错（不静默截断）与 `data:image/*;base64` 内嵌图读取。
2. **TTS 引擎零漂移段落同步契约**：
   - 最小化向后兼容扩展 [SentenceSegment.kt](../core-model/src/main/kotlin/com/aitts/v2/model/SentenceSegment.kt)（`sourceIndex: Int = -1`）与 [SynthesisOrchestrator.kt](../core-engine/src/main/kotlin/com/aitts/v2/engine/synthesis/SynthesisOrchestrator.kt)（`SynthesisInput.preSegmented` + `SynthesisSink.onSegmentMeta`），复用既有滑窗预取、音频缓存、失败重试/降级与 Sonic 残差倍速/音调链。
3. **Android 书架、沉浸阅读 UI 与外部联动**：
   - [BookLibraryRepository.kt](../app/src/main/java/com/aitts/v2/reader/BookLibraryRepository.kt)、[BookImportManager.kt](../app/src/main/java/com/aitts/v2/reader/BookImportManager.kt)、[EpubImageLoader.kt](../app/src/main/java/com/aitts/v2/reader/EpubImageLoader.kt)（12MB LRU 下采样加载封面与插图）及 [ReaderTtsController.kt](../app/src/main/java/com/aitts/v2/reader/ReaderTtsController.kt)（原子代际隔离 `cancelAndJoin`、章节自动连播开关及与听书台互斥；并在 [StreamingAudioTrackPlayer.kt](../app/src/main/java/com/aitts/v2/audio/StreamingAudioTrackPlayer.kt) 按 `playbackHeadPosition` 排空尾音防截断）。
   - [BookshelfScreen.kt](../app/src/main/java/com/aitts/v2/ui/reader/BookshelfScreen.kt)、[ReaderScreen.kt](../app/src/main/java/com/aitts/v2/ui/reader/ReaderScreen.kt)、[ReaderContentViewport.kt](../app/src/main/java/com/aitts/v2/ui/reader/ReaderContentViewport.kt)、[ReaderChromeAndToc.kt](../app/src/main/java/com/aitts/v2/ui/reader/ReaderChromeAndToc.kt)、[ReaderSheets.kt](../app/src/main/java/com/aitts/v2/ui/reader/ReaderSheets.kt)：支持 6 套纸张/暗夜主题、全套排版调节、目录/书签/全书搜索、长按段落起读、听书高亮自动滚屏、系统状态栏深浅图标自适应及 `ACTION_VIEW`（EPUB/TXT/MD/HTML）直接唤起打开。
   - [proguard-rules.pro](../app/proguard-rules.pro) 补充 `com.aitts.v2.reader.model.**` R8 序列化保留规则。
4. **Qodo CLI 两轮审查与最终全量验收（已达 2 轮止损上限）**：
   - **Qodo 第 1 轮**（`operation_id: ea3b9209-574e-4442-82db-12b002ba958f`）：针对新增部分提出 11 项改进（`AudioTrack` 尾音截断、并发切换会话竞争、手动切章被听书状态回扯、HTML 8MB/Markdown 4MB 静默截断、UTF-16 章节字节错位、Markdown 代码块被识别为章节、HTML 前言丢失与内嵌图、EPUB ZIP 线性扫描、Intent 缺少 `text/html`），当轮全部修复并验证通过。
   - **Qodo 第 2 轮增量复核**（`operation_id: 85a5fa1d-1431-4087-a3ca-4741983fb7a9`）：确认第 1 轮全部 11 项均已 `resolved`，并指出 4 项新增边界细节。随后修复其中 3 项有效问题（`ReaderTtsController` 严格判断 `autoNextChapterOnTts` 开关、`MarkdownBookParser.snapLineEnd` 兜底 UTF-8 字符边界回退、`TxtBookParser` UTF-16LE/BE 换行 `+2` 字节对齐与 UTF-8 回退）；理论级极小概率边缘备注：若单个 Markdown 章节超过 512KB 且恰好在 512KB 边界存在跨越分片的围栏代码块，分片上下文不会跨片延续（已记入验收边界）。
   - **最终全量门禁与 R8 打包实测**（全部修改完成后由 `flash` 子代理执行，退出码 `0`）：
     - `python scripts/check_architecture.py`：退出码 `0`（`0 violations`；全仓 `src/` 含单测共 `123` 个 `.kt` 文件 / `17,854` 行）。
     - `.\gradlew.bat qualityGate assembleR8Release --no-daemon`：退出码 `0`（`BUILD SUCCESSFUL in 3m 6s`），**0 编译警告**，`Detekt` 0 issues，`Lint` 0 errors，**45/45 单元测试全部通过**（`:core-model:test` `6`、`:core-engine:test` `30`、`:core-reader:test` `7`、`:app:testCloudDebugUnitTest` `2`）。
     - `cloud` R8 APK：`app/build/outputs/apk/cloud/release/app-cloud-release.apk` — `1,926,801` 字节（SHA-256 `991DD7B382B94CAC2904BBE52FBAFCB9C163E5335D8DFFB5BF42694DA2B44569`），`apksigner` 校验通过（退出码 `0`）。
     - `full` R8 APK：`app/build/outputs/apk/full/release/app-full-release.apk` — `25,600,883` 字节（SHA-256 `C7843A7489D48D686699453F595EB1D7E084D9824FCF15DF809E8472BEC8ED34`），`apksigner` 校验通过（退出码 `0`）。


## 10. 2026-11-27 v2.2.0-pre.2 阅读器编码乱码根治、真分页横向翻页重构与 Google Drive 网盘接入

针对 `v2.2.0-pre.1` 实测反馈（阅读器文字乱码、缺少真实横向翻页、需要连接个人 Google Drive 浏览导入电子书），按以下模块完成重构、修复与两轮 Qodo 审查闭环：

1. **电子书编码乱码根治与二进制误导入拦截（`:core-reader` & `:app`）**：
   - [CharsetDetector.kt](../core-reader/src/main/kotlin/com/aitts/v2/reader/parser/CharsetDetector.kt)：新增显式 `isTruncatedSample` 参数，避免整章完整缓冲因“不足 64KB”被拒绝尾字节；新增 XML `<?xml encoding=...?>` 与 HTML `<meta charset=...>` 编码声明识别（`gb2312`/`gbk` 统一映射至 `GB18030`）、无 BOM UTF-16 奇偶零字节启发式探测、`looksLikeBinary` 魔数与非打印字符检测，并将 `transcodeToUtf8` 改为 `OutputStreamWriter` + `BoundedCountingOutputStream` 流式编码，杜绝跨块代理对拆碎产生替换符。
   - [EpubBookParser.kt](../core-reader/src/main/kotlin/com/aitts/v2/reader/parser/EpubBookParser.kt)、[EpubXmlSupport.kt](../core-reader/src/main/kotlin/com/aitts/v2/reader/parser/EpubXmlSupport.kt)、[HtmlBookParser.kt](../core-reader/src/main/kotlin/com/aitts/v2/reader/parser/HtmlBookParser.kt)、[TxtBookParser.kt](../core-reader/src/main/kotlin/com/aitts/v2/reader/parser/TxtBookParser.kt)：全章文本与导航 XML/HTML 统一走完整缓冲 `detectMarkup` 判定，TXT 文件编码探测传参 `isTruncatedSample = fileLen > sample.size` 防止 UTF-8 多字节汉字在 4KB/8KB 采样截断时误降级回 `GB18030`。
   - [BookImportManager.kt](../app/src/main/java/com/aitts/v2/reader/BookImportManager.kt)：抽离共享 `importFromStream`，在导入前探测 4KB 文件头校验 EPUB ZIP 容器或拦截 PDF/二进制误判，并用 `try/catch` 统一兜底 ContentProvider 异常以免书架导入态卡死。
2. **像素级真分页横向翻页阅读器（`PAGED` 默认模式 + `SCROLL` 双模切换）**：
   - [ReaderModels.kt](../core-reader/src/main/kotlin/com/aitts/v2/reader/model/ReaderModels.kt) & [ReaderSettings.kt](../core-reader/src/main/kotlin/com/aitts/v2/reader/model/ReaderSettings.kt)：新增 `ReaderReadingMode`（`PAGED` / `SCROLL`）、`volumeKeyPaging = true` 及 `ReadingProgress.charOffset: Int = 0` 页内字符偏移持久化。
   - [ChapterPaginator.kt](../app/src/main/java/com/aitts/v2/ui/reader/ChapterPaginator.kt)：基于 Compose `TextMeasurer` 按屏幕真实像素高度切分 `PageBlockSlice`（记录精确 `startCharOffset`/`endCharOffset`，对齐插图+图注预留高度，断页保留完整字符间隔）。
   - [PagedReaderViewport.kt](../app/src/main/java/com/aitts/v2/ui/reader/PagedReaderViewport.kt) & [ReaderBlockItemView.kt](../app/src/main/java/com/aitts/v2/ui/reader/ReaderBlockItemView.kt)：以 `HorizontalPager` 实现左右滑动翻页、三区点击（左 30% 上一页 / 中间栏开关 / 右 30% 下一页）、边缘跨章（回上一章末页定位）、音量键翻页、字号/排版变更位置锚定以及听书高亮自动翻页不回退进度。
   - [ReaderViewModel.kt](../app/src/main/java/com/aitts/v2/ui/reader/ReaderViewModel.kt)、[ReaderScreen.kt](../app/src/main/java/com/aitts/v2/ui/reader/ReaderScreen.kt)、[ReaderContentViewport.kt](../app/src/main/java/com/aitts/v2/ui/reader/ReaderContentViewport.kt)、[ReaderSheets.kt](../app/src/main/java/com/aitts/v2/ui/reader/ReaderSheets.kt)：完成双向位置同步、硬件音量键预览拦截及排版面板翻页模式/音量键开关。
3. **Google Drive 网盘连接与直导电子书（`:core-engine` & `:app`）**：
   - [GoogleDriveModels.kt](../core-engine/src/main/kotlin/com/aitts/v2/engine/cloud/GoogleDriveModels.kt) & [GoogleDriveClient.kt](../core-engine/src/main/kotlin/com/aitts/v2/engine/cloud/GoogleDriveClient.kt)：纯 JVM OkHttp 实现 OAuth 2.0 PKCE + Drive REST v3（复用 `HttpClientManager` 全局代理设置），支持 401 自动刷新 Token、目录限定搜索与受限临时下载。
   - [SecretStore.kt](../app/src/main/java/com/aitts/v2/data/SecretStore.kt) & [proguard-rules.pro](../app/proguard-rules.pro)：新增加密凭证字符串读写方法与 `com.aitts.v2.engine.cloud.**` R8 保留规则。
   - [GoogleDriveRepository.kt](../app/src/main/java/com/aitts/v2/reader/GoogleDriveRepository.kt)、[GoogleDriveViewModel.kt](../app/src/main/java/com/aitts/v2/ui/reader/GoogleDriveViewModel.kt)、[GoogleDriveScreen.kt](../app/src/main/java/com/aitts/v2/ui/reader/GoogleDriveScreen.kt)、[GoogleDriveConnectCard.kt](../app/src/main/java/com/aitts/v2/ui/reader/GoogleDriveConnectCard.kt)：提供“OAuth 浏览器环回 PKCE 登录”与“Token 直连”双通道、多连接竞态隔离与 `/callback` 非目标本地请求过滤、面包屑导航、搜索/仅看电子书过滤及直导书架；书架页与导航接入“网盘”入口。
4. **Qodo CLI 两轮审查与最终全量验收（已达 2 轮止损上限）**：
   - **Qodo 第 1 轮**（`operation_id: d40c9d8f-811b-49e0-a206-2512813d44bc`）：指出 11 项引入问题（文本点击区域覆盖、字号调整位置偏移、图片高度超算、旧搜索覆盖、OAuth 取消竞态与缺少 `soTimeout`、非根目录搜索范围、凭证保存静默忽略失败、流打开异常逃逸、听书进度回退、`transcodeToUtf8` 跨块代理对替换），当轮全部修复并通过 `flash` 子代理 49/49 门禁。
   - **Qodo 第 2 轮增量复核**（`operation_id: b378303a-82d3-4a0d-9744-2d0ffd10b18f`）：确认第 1 轮 11 项全部 `resolved`，并修复第 2 轮新增的全部 4 项发现（`ReadingProgress.charOffset` 长段落续页恢复、`placeTextSlices` 断页空白保留、`awaitLoopbackCallback` `/callback` 校验循环过滤干扰请求、`goToChapter` 回退上章末页进度保存）。
   - **最终全量门禁与 R8 打包实测**（全部修改完成后由 `flash` 子代理执行，退出码 `0`）：
     - `python scripts/check_architecture.py`：退出码 `0`（`0 violations`；全仓 `src/` 含单测共 `133` 个 `.kt` 文件 / `20,188` 行：`core-model/src` 14/1,416、`core-engine/src` 42/5,587、`core-reader/src` 13/2,150、`app/src` 64/11,035）。
     - `.\gradlew.bat qualityGate assembleR8Release --no-daemon`：退出码 `0`（`BUILD SUCCESSFUL in 3m 49s`），**0 编译警告**，`Detekt` 0 issues，`Lint` 0 errors，**49/49 单元测试全部通过**（`:core-model:test` `6`、`:core-engine:test` `31`、`:core-reader:test` `10`、`:app:testCloudDebugUnitTest` `2`）。
     - `cloud` R8 APK：`app/build/outputs/apk/cloud/release/app-cloud-release.apk` — `1,992,337` 字节（SHA-256 `066F3C253D213A844F1A97D06612457F021769D45E4FFD3A86CC5B25C4B2A3F4`），`apksigner` 校验通过（退出码 `0`）。
     - `full` R8 APK：`app/build/outputs/apk/full/release/app-full-release.apk` — `25,666,419` 字节（SHA-256 `19990E0AEC4C09CC660FFE6A4E33953972E322908960C3061DB7D2584B9897B5`），`apksigner` 校验通过（退出码 `0`）。



## 11. 2026-11-28 v2.3.0-pre.1 Readium 3.0 工业级 EPUB 阅读器重构、统一文件管理/本地双向同步及真机模拟器实测

针对 `v2.2.0-pre.2` 用户反馈（EPUB 解析与翻页体验不完善、Google Drive 登录繁琐、需要无感本地文件管理器与专有目录同步、仓库 Pre-release 仅保留最新一版），完成全面工业级架构重构、模拟器实测与两轮 Qodo 审查闭环：

1. **Readium Kotlin Toolkit 3.0.0 工业级 EPUB 引擎与 `Jsoup` 索引解析（`:core-reader` & `:app`）**：
   - 引入 `readium-shared` / `readium-streamer` / `readium-navigator` `3.0.0` + `jsoup` `1.18.3` + `coreLibraryDesugaringEnabled`，将 `MainActivity` 升级为 `FragmentActivity` 宿主。
   - 新增 [ReadiumEngine.kt](../app/src/main/java/com/aitts/v2/reader/ReadiumEngine.kt) 与 [ReadiumEpubViewport.kt](../app/src/main/java/com/aitts/v2/ui/reader/ReadiumEpubViewport.kt)，实现完整 EPUB 2/3 CSS 排版、日语假名 `<ruby>` 注音、插图展示、嵌套目录跳转、`ActionMode` 长按划词高亮（黄/蓝）/笔记/全书检索/从当前句朗读，以及 TTS 句级高亮联动。
   - 重构 [EpubXmlSupport.kt](../core-reader/src/main/kotlin/com/aitts/v2/reader/parser/EpubXmlSupport.kt)（移除阻断 EPUB 2 `<!DOCTYPE ncx/html>` 的 `disallow-doctype-decl`，并兼容 Android Harmony `DocumentBuilderFactoryImpl` 对 `isXIncludeAware` 抛 `UnsupportedOperationException` 的实机缺陷，同时保留 XXE 防护与嵌套层级提取）及 [EpubHtmlParser.kt](../core-reader/src/main/kotlin/com/aitts/v2/reader/parser/EpubHtmlParser.kt)（全面切换 `Jsoup` DOM 解析，保留标题/引用/列表/表格/SVG 与 `<img>`）。

2. **阅读器全功能体验补齐（`:app` & `:core-reader`）**：
   - 补齐底部阅读进度滑块拖动跳转（`ReaderChromeAndToc`）、三页签Sheet（`目录` / `书签` / `划线笔记`）与笔记弹窗（`ReaderSearchAndNoteSheets`）、自定义亮度与系统亮度切换 / 屏幕常亮 / 沉浸全屏 `Window` 联动（`ReaderScreen`）、出版商原版样式切换 / 文本对齐 / 字间距与 `.ttf/.otf` 自定义字体导入（`ReaderSheets` / `BookLibraryExtensions`）、插图双指缩放全屏灯箱（`ReaderImageLightbox`），以及书架搜索/排序/置顶/分类与多选批量删除（`BookshelfScreen` / `BookshelfItemCard`）。

3. **无感统一文件管理器、专有本地同步目录与双向同步（`:core-engine` & `:app`）**：
   - 新增 [CloudStorageModels.kt](../core-engine/src/main/kotlin/com/aitts/v2/engine/cloud/CloudStorageModels.kt)、[GoogleDriveWriteOps.kt](../core-engine/src/main/kotlin/com/aitts/v2/engine/cloud/GoogleDriveWriteOps.kt) 与完整 WebDAV 客户端 [WebDavClient.kt](../core-engine/src/main/kotlin/com/aitts/v2/engine/cloud/WebDavClient.kt)（支持 `PROPFIND`/`MKCOL`/`MOVE`/`DELETE`/`GET`/`PUT`，以及 OkHttp `addPathSegment` 保留字符编码、父子路径包含校验、`displayname` 防路径穿越与 Harmony XML 保护）。
   - 重构统一三栏文件与网盘中心（[CloudFileManagerScreen.kt](../app/src/main/java/com/aitts/v2/ui/reader/CloudFileManagerScreen.kt)、[CloudFileManagerViewModel.kt](../app/src/main/java/com/aitts/v2/ui/reader/CloudFileManagerViewModel.kt)、[CloudFileManagerSheets.kt](../app/src/main/java/com/aitts/v2/ui/reader/CloudFileManagerSheets.kt)、[CloudAuthCoordinator.kt](../app/src/main/java/com/aitts/v2/ui/reader/CloudAuthCoordinator.kt)）：
     - **本地专有目录**（[LocalSyncFolderManager.kt](../app/src/main/java/com/aitts/v2/reader/LocalSyncFolderManager.kt)）：默认 `BooksSync`，支持 SAF `OpenDocumentTree` 切换绑定与建目录/重命名/删除/一键扫描导入。
     - **Google 网盘**：Play Services `Identity.getAuthorizationClient` 一键授权（含 `expiresAt` 预检查与 401 静默续期重试、签名 SHA-1 诊断提示）、零配置 SAF 批量多选直导与手动 Token 高级折叠入口，并扩展 `listNodes` 支持 20 页分页拉取。
     - **WebDAV 网盘**（[WebDavRepository.kt](../app/src/main/java/com/aitts/v2/reader/WebDavRepository.kt)）与 **双向智能同步引擎**（[LibrarySyncEngine.kt](../app/src/main/java/com/aitts/v2/reader/LibrarySyncEngine.kt)）：支持拉取/推送/双向同步书籍并合并 `aitts_library_state.json` 进度/书签/划线笔记。

4. **Android AVD `pixel_34` 实机验证与 8 张渲染截图留档**：
   - 使用 R8 压缩签名包 `app-cloud-release.apk` 在 `pixel_34` 实机部署验证：不仅捕获并修复了 `EpubXmlSupport.kt` 在 Android Harmony XML 解析器下的 `isXIncludeAware` 崩溃盲区，还端到端验证并人工逐张查验了 `antigravity-records/screenshots/v2.3.0-pre.1/` 下全部 8 张真实截图（`01_bookshelf.png`、`02_epub2_readium.png`、`03a_reader_chrome.png`、`03b_reader_toc_sheet.png`、`04_epub3_ruby.png`、`05_gbk_txt_paged.png`、`06a_file_local.png`、`06b_file_gdrive.png`），确认 EPUB 2 DOCTYPE 中文、EPUB 3 Ruby 注音、GB18030 TXT 分页与网盘中心均零乱码、零崩溃（`logcat *:E` 0 应用/Readium 异常）。

5. **Qodo CLI 两轮审查与最终全量门禁复验（已达 2 轮止损上限）**：
   - **Qodo 第 1 轮**（`operation_id: b5807904-bd52-4dc8-9896-d414ffd444c1` / `review_id: 2f4732e2-073a-445b-bc28-1b2caf955227`）：提出 13 项有效发现（升级目录兼容性恢复 `books`、同步顺序先导入后合并状态、显性汇报传输与状态读写失败、Google Drive 上传去 `up_` 前缀、书架直导书籍镜像到同步目录、`listNodes` 分页、Play Services 过期静默刷新、`sourceSyncKey` 唯一化、WebDAV 保留字符编码与防目录穿越、书签位置精确高亮、Readium 目录索引对齐与 EPUB 3 顶级缩进归零），全部当轮修复并通过 `flash` 子代理 53/53 门禁。
   - **Qodo 第 2 轮**（`operation_id: 4c65a39b-e0fb-456c-8c9a-5db5fe69b149` / `review_id: 22947f4b-edff-446b-86db-5b708466e369`，达 2 轮上限）：确认第 1 轮 12 项直接标 `resolved`（`a2b2a400` 已恢复 `books` 根目录），并修复第 2 轮全部 4 项新增缺陷（`LibrarySyncEngine` 拉取方向条件 `direction != PUSH` 与书架镜像采用 `${safeTitle}_${record.id}.ext` 避免覆盖 `source.epub`、`BookLibraryExtensions` 同书名+格式合并状态、`CloudFileManagerViewModel` 保留 `rawItems` 搜索源、`ReaderViewModel` 初次打开写入 `currentLocatorJson`）。
   - **最终全量门禁与 R8 打包实测**（全部修改完成后经 `flash` 子代理执行，退出码 `0`）：
     - `python scripts/check_architecture.py`：退出码 `0`（`0 violations`；全仓 `src/` 含单测共 `152` 个 `.kt` 文件 / `23,288` 行：`core-model/src` 14/1,416、`core-engine/src` 46/6,190、`core-reader/src` 13/2,275、`app/src` 79/13,407）。
     - `.\gradlew.bat qualityGate assembleR8Release --no-daemon`：退出码 `0`（`BUILD SUCCESSFUL in 3m 35s`），`Detekt` 0 issues，`Lint` 0 errors，**53/53 单元测试全部通过**（`:core-model:test` `6`、`:core-engine:test` `34`、`:core-reader:test` `11`、`:app:testCloudDebugUnitTest` `2`）。
     - `cloud` R8 APK：`app/build/outputs/apk/cloud/release/app-cloud-release.apk` — `6,484,476` 字节（SHA-256 `7F5EC09B3C0050DFC0902FFCBCFACA3C1B8ECC1CBE42DC0E121D2F5729832214`），`apksigner` 校验通过（退出码 `0`）。
     - `full` R8 APK：`app/build/outputs/apk/full/release/app-full-release.apk` — `30,175,478` 字节（SHA-256 `E6C542D13635663A1143D522B3051743D45BF75BB8849BE2CD3F06679D306995`），`apksigner` 校验通过（退出码 `0`）。

6. **GitHub Release 清理与单 Pre-release 发布**：
   - 删除所有历史旧版 Pre-release 与标签（`v2.1.0-pre.1`、`v2.1.0-pre.2`、`v2.1.0-pre.3`、`v2.2.0-pre.1`、`v2.2.0-pre.2`），仅保留正式基线 `v2.0.0` 与最新预发布 **`v2.3.0-pre.1`**（附带签名 R8 `ai-tts-v2-cloud-v2.3.0-pre.1.apk` 与 `ai-tts-v2-full-arm64-v2.3.0-pre.1.apk`）。
