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
