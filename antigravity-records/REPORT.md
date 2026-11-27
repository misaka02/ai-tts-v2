# AI-TTS v2 审计与从零重构交付报告 (REPORT)

## 1. 项目概览
- **旧工程**：`E:\antigravity\workspace\ai-tts-android`（分支 `feature/pulse-theme-v4` @ `0706aa1`）
- **新工程**：`E:\antigravity\workspace\ai-tts-v2`（分支 `main`，已发布至 `https://github.com/misaka02/ai-tts-v2`）
- **GitHub Release**：正式基线 [v2.0.0](https://github.com/misaka02/ai-tts-v2/releases/tag/v2.0.0)；前序预发布 [v2.1.0-pre.1](https://github.com/misaka02/ai-tts-v2/releases/tag/v2.1.0-pre.1)、[v2.1.0-pre.2](https://github.com/misaka02/ai-tts-v2/releases/tag/v2.1.0-pre.2)、[v2.1.0-pre.3](https://github.com/misaka02/ai-tts-v2/releases/tag/v2.1.0-pre.3)；**最新预发布 [v2.2.0-pre.1](https://github.com/misaka02/ai-tts-v2/releases/tag/v2.2.0-pre.1)**（Pre-release，新增纯本地多格式电子书阅读器 `:core-reader` 与原生 TTS 段落同步高亮听书，附带 `ai-tts-v2-cloud-v2.2.0-pre.1.apk` 与 `ai-tts-v2-full-arm64-v2.2.0-pre.1.apk`）。
- **全局规范持久化**：已向 `C:\Users\s1356\.gemini\GEMINI.md`、`C:\Users\s1356\.gemini\config\rules\AGENTS.md` 与 `C:\Users\s1356\.gemini\config\plugins\user-workspace-rules\rules\AGENTS.md` 写入“Android 编译默认交付 R8 压缩签名包 + 默认发布 GitHub Pre-release”规则。
- **当前最新验证与产物（2026-11-27 v2.2.0-pre.1 阅读器+听书一体化版）**：
  - `python scripts/check_architecture.py`：退出码 `0`（`0 violations`）
  - `.\gradlew.bat qualityGate assembleR8Release --no-daemon`：退出码 `0`（`BUILD SUCCESSFUL in 3m 6s`，0 编译警告，`Detekt` 0 issues，`Lint` 0 errors，**45/45 单测全部通过**）
  - `cloud` R8 APK：[app-cloud-release.apk](../app/build/outputs/apk/cloud/release/app-cloud-release.apk) — **1,926,801 字节**（约 **1.84 MiB**，SHA-256 `991DD7B382B94CAC2904BBE52FBAFCB9C163E5335D8DFFB5BF42694DA2B44569`，`apksigner` 校验通过）
  - `full` R8 APK：[app-full-release.apk](../app/build/outputs/apk/full/release/app-full-release.apk) — **25,600,883 字节**（约 **24.41 MiB**，SHA-256 `C7843A7489D48D686699453F595EB1D7E084D9824FCF15DF809E8472BEC8ED34`，`apksigner` 校验通过）
  - **Qodo CLI 审查**：已完成第 1 轮（`ea3b9209`）与第 2 轮增量复核（`85a5fa1d`，第 1 轮全部 11 项引入问题均确认 `resolved`），并修复第 2 轮指出的 `autoNextChapterOnTts` 分支判断、Markdown/TXT 长行 UTF-8 字符边界与 UTF-16LE/BE `+2` 换行偏移问题，达 2 轮硬上限后全量复验通过。
- **最新代码体量总计**（所有 `src/` 目录，含单元测试，不含 `build/`）：**123 个 `.kt` 文件，共 17,854 行**（另含 `Sonic.java`）
  - `:core-model/src`：14 个文件，1,416 行
  - `:core-engine/src`：39 个文件，5,101 行（另含 `Sonic.java`）
  - `:core-reader/src`：13 个文件，1,939 行
  - `:app/src`（`main` + `cloud` + `full` + `test`）：57 个文件，9,398 行

## 2. 架构与核心改进对照

| 维度 | v1 旧仓主要缺陷 | v2 重构方案与落地 |
| :--- | :--- | :--- |
| 模块解耦 | UI / 服务 / 引擎 / 文本 / HTTP 混杂在单模块中 | `:core-model` → `:core-engine`（零 `android.*` 依赖）→ `:app`，由 `scripts/check_architecture.py` 自动校验 |
| 文件体量 | 存在单文件上千行“上帝类”与深层嵌套 | 全量 `src/main/*.kt` 严格控制在 350 行以内，`Detekt` (`maxIssues: 0`) 强制门禁 |
| 密钥安全 | API Key 明文存储且随分享口令/备份泄漏 | `TtsProviderConfig` 与 `ProviderCredentials` 分离；凭证加密落盘至 Android Keystore (`SecretStore`)，导出与分享口令默认剔除密钥 |
| 离线引擎 | 反射强绑 `sherpa-onnx` 且单一架构臃肿包体 | 新增 `cloud` / `full` 双 Flavor：`cloud` 剔除 `.so`；`full` 直接编译期链接 `sherpa-onnx-classes.jar` 与 `libsherpa-onnx-jni.so` |
| 悬浮字幕 | `MediaProjection` 截屏取色权限重、易触发系统录屏告警 | 彻底移除 `MediaProjection`，保留系统深浅色自适应 + 5 套纸张预设 + 描边对比度与拖拽/锁定/自定义器 |
| 云端引擎 | OpenAI / SiliconFlow / StepFun 多套重复样板代码 | 统一收敛为 `OpenAiCompatibleProvider`，结合 `ProviderCapabilities` 元数据驱动界面配置 |

## 3. 自动化质量门禁与构建实测结果

在最后一次代码修改之后，通过轻量子代理在 `E:\antigravity\workspace\ai-tts-v2` 完整执行验证套件并全部通过：

1. **架构边界脚本**：`python scripts/check_architecture.py`
   - 结果：**PASS**（退出码 `0`，`0 violations`）
2. **静态检查与单元测试**：`.\gradlew.bat detekt :core-model:test :core-engine:test :app:testCloudDebugUnitTest :app:lintCloudDebug :app:assembleCloudDebug :app:assembleFullDebug --no-daemon`
   - 结果：**BUILD SUCCESSFUL**（退出码 `0`，耗时 `54s`，0 错误 / 0 编译警告）
   - `:core-model:test`：4 passed, 0 failed, 0 skipped
   - `:core-engine:test`：19 passed, 0 failed, 0 skipped
   - `:app:testCloudDebugUnitTest`：2 passed, 0 failed, 0 skipped
   - **单元测试汇总**：**25/25 通过**
   - `Detekt`：0 weighted issues（`maxIssues: 0`）
   - `Android Lint (cloudDebug)`：0 errors
3. **双风味 APK 产物对比**：
   - **Debug 包**：
     - `cloud` 纯净版：[app-cloud-debug.apk](../app/build/outputs/apk/cloud/debug/app-cloud-debug.apk) — **18,318,498 字节**（约 17.47 MiB）
     - `full` 全量离线版：[app-full-debug.apk](../app/build/outputs/apk/full/debug/app-full-debug.apk) — **42,041,691 字节**（约 40.09 MiB）
   - **R8 Release 压缩包**（`.\gradlew.bat assembleR8Release --no-daemon`，退出码 `0`，`BUILD SUCCESSFUL in 2m 18s`，经 `apksigner.bat verify --print-certs` 验证 APK Signature Scheme v2 通过，默认采用 `Android Debug` 签名兜底可直接安装）：
     - `cloud` R8 版：[app-cloud-release.apk](../app/build/outputs/apk/cloud/release/app-cloud-release.apk) — **1,778,797 字节**（约 **1.70 MiB**，较 Debug 版压缩 **90.29%**）；映射表 [cloudRelease/mapping.txt](../app/build/outputs/mapping/cloudRelease/mapping.txt)（`32,688,192` 字节）
     - `full` R8 版：[app-full-release.apk](../app/build/outputs/apk/full/release/app-full-release.apk) — **25,469,263 字节**（约 **24.29 MiB**，较 Debug 版压缩 **39.42%**，内含 `23,647,720` 字节的 `libsherpa-onnx-jni.so`）；映射表 [fullRelease/mapping.txt](../app/build/outputs/mapping/fullRelease/mapping.txt)（`33,148,328` 字节）

## 4. Qodo Review 与本地插件使用说明
- **Qodo CLI (`2.7.1`)**：已完成登录态核验并执行 `qodo.exe review --json --context-file -`。由于 `ai-tts-v2` 当前为纯本地仓库、尚未配置 GitHub `origin` 远程与追踪分支，云端对比受阻：
  - 精确报错：`could not determine the repository — run inside a git repo with an origin remote, or pass --repo <repository>`（`trace_id: c54d15bf9b95ab30bca3d3c4999dcb7d`）。
  - **后续触发方式**：将 `ai-tts-v2` 推送至 GitHub 仓库（配置 `origin` 与远程基准分支）后，再次运行 `C:\Users\s1356\.qodo\bin\qodo.exe review` 即可完成云端 Diff 审查。
- **本地插件状态**：已启用 `Detekt`、`Android Lint` 与架构守护脚本 `check_architecture.py` 作为离线确定性门禁；`sonar` CLI 与 SonarQube MCP 未在本机安装。

## 5. 未验证边界与实机验收清单
> 当前所有逻辑均通过 JVM 单元测试（含 Mock 服务端测试）、Android Lint、Detekt 与双风味 APK 编译打包，**尚未在真机或 Android 模拟器上执行交互与播放实机测试**。建议后续上机重点核验：

- [ ] 系统设置 → 无障碍/文字转语音输出中切换 **AI-TTS v2** 为默认引擎并触发朗读。
- [ ] 阅读器应用（如微信读书 / 开源阅读 Legado）长文连续朗读的流式首包延迟与断句自然度。
- [ ] `Edge TTS` 免费节点及各商业模型节点（OpenAI 兼容 / MiMo / Gemini / MiniMax / 豆包 / Fish / Azure）真实 Key 鉴权与合成。
- [ ] 锁屏与后台长时朗读时的音频焦点抢占、通知栏停止/字幕开关按钮及定时关闭倒计时。
- [ ] 悬浮字幕的覆盖层权限引导、纸张主题切换、拖拽位置持久化与锁定穿透手势。
- [ ] `full` 变体中 Sherpa-ONNX 离线包下载解压（`.tar.bz2` / `.zip`）及离线发音人加载合成。
- [ ] 配置备份导出（不含敏感凭证与含凭证选项）及口令导入兼容性。
- [ ] **内置阅读器实机专项核验**：SAF 与文件管理器 `ACTION_VIEW` 导入超长 GBK/UTF-16 TXT、EPUB 2/3（含封面、插图、假名注音 `<ruby>`）、Markdown 与 HTML；验证长按段落直接起读、阅读器高亮与自动滚屏、`自动连播下一章` 开关及跨章节后台播放尾音排空效果（若单个 Markdown 章节超过 512KB 且恰好在 512KB 边界被超长围栏代码块跨越，理论上有可能拆分代码围栏上下文）。
