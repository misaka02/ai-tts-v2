# AI-TTS v2 审计与从零重构交付报告 (REPORT)

## 1. 项目概览
- **旧工程**：`E:\antigravity\workspace\ai-tts-android`（分支 `feature/pulse-theme-v4` @ `0706aa1`）
- **新工程**：`E:\antigravity\workspace\ai-tts-v2`（分支 `main`，已发布至 `https://github.com/misaka02/ai-tts-v2`）
- **GitHub Release**：正式基线 [v2.0.0](https://github.com/misaka02/ai-tts-v2/releases/tag/v2.0.0)；已清理旧 Pre-release `v2.5.0-pre.1`，**唯一最新预发布 [v2.6.0-pre.1](https://github.com/misaka02/ai-tts-v2/releases/tag/v2.6.0-pre.1)**（Pre-release，阅读器安全居中热区与零抽搐菜单 + 《创约 某魔法的禁书目录 11》EPUB 3 全格式/注音/注脚弹窗与嵌入字体保真 + Google Drive SAF 置顶/Custom Tabs/`rclone.conf` 与 `token.json` 一键解析及草稿持久化，附带 `ai-tts-v2-v2.6.0-pre.1-cloud-r8-signed.apk` 与 `ai-tts-v2-v2.6.0-pre.1-full-r8-signed.apk`）。
- **全局规范持久化**：已向 `C:\Users\s1356\.gemini\GEMINI.md`、`C:\Users\s1356\.gemini\config\rules\AGENTS.md` 与 `C:\Users\s1356\.gemini\config\plugins\user-workspace-rules\rules\AGENTS.md` 写入“Android 编译默认交付 R8 压缩签名包 + 默认发布 GitHub Pre-release”规则。
- **当前最新验证与产物（2026-11-28 v2.6.0-pre.1 阅读器零抽搐/EPUB 3 全格式保真 + Google Drive 登录优化第 1 轮交付）**：
  - `python scripts/check_architecture.py`：退出码 `0`（`0 violations`）
  - `.\gradlew.bat qualityGate assembleR8Release --no-daemon`：退出码 `0`（`BUILD SUCCESSFUL in 3m 36s`，`0` Kotlin/Java 警告与错误，`Detekt` 0 issues，`Lint` 0 errors，**63/63 单测全部通过**：`core-model` 6、`core-engine` 38、`core-reader` 14、`app` 5；0 跳过、0 失败、0 错误）
  - `cloud` R8 APK：[app-cloud-release.apk](../app/build/outputs/apk/cloud/release/app-cloud-release.apk) — **6,599,300 字节**（约 **6.29 MiB**，SHA-256 `67452B0FD5191109BA41512F9415A27DFC0C7B316F49F02DB30DFA63562F1299`，`apksigner` 校验通过）
  - `full` R8 APK：[app-full-release.apk](../app/build/outputs/apk/full/release/app-full-release.apk) — **30,290,302 字节**（约 **28.89 MiB**，SHA-256 `35D5879C903D98EC25B19DF2E178D669C88EDC48FD2F1EE08C45971B7F5DC2D1`，`apksigner` 校验通过）
  - **Android `pixel_34` 使用真实 EPUB 《创约 某魔法的禁书目录 11》实机模拟器截图验收**（`0` 应用/Readium 崩溃）：
    - [01_chuangyue11_fxl_center_tap.png](screenshots/v2.6.0-pre.1/01_chuangyue11_fxl_center_tap.png)（FXL 封面居中点击呼出阅读栏，不误翻页不误弹灯箱）
    - [02_chuangyue11_toc_nested.png](screenshots/v2.6.0-pre.1/02_chuangyue11_toc_nested.png)（31 项多级嵌套目录与子章节跳转）
    - [03_chuangyue11_body_fidelity.png](screenshots/v2.6.0-pre.1/03_chuangyue11_body_fidelity.png)（《创约 11》第一章嵌入字体、悬挂标点、返回跳转前位置胶囊与精确百分比）
    - [04_chuangyue11_footnote_popup.png](screenshots/v2.6.0-pre.1/04_chuangyue11_footnote_popup.png)（点击 `㊟` 原地弹出 `注释说明` 弹窗并正常渲染 `<ruby>` 注音）
    - [05_reader_layout_overlay_clean.png](screenshots/v2.6.0-pre.1/05_reader_layout_overlay_clean.png)（打开排版设置面板自动隐藏上下栏、正文无抽搐）
    - [06_gdrive_auth_card_v26.png](screenshots/v2.6.0-pre.1/06_gdrive_auth_card_v26.png)（重设计 Google 网盘连接卡：SAF 主按钮置顶、`JSON / rclone` 导入与 Custom Tabs 授权）
  - **Qodo CLI 审查（v2.6.0-pre.1，达 2 轮上限）**：第 1 轮（`operation_id: ca81e178-78c5-4cc7-964c-4c9052e14fee`）检出 2 项 `introduced` `action_required` 发现（`51c49e40` `parseRcloneConf` 缺 `client_id` 校验与 `d60d017b` 空注脚锚点/跳转丢失 `#fragment`）并当轮修复；第 2 轮（`operation_id: a6637c71-551e-4bbb-9add-387b95bf1426`）确认第 1 轮两项已转 **`resolved`**、0 新增 `action_required`（仅 1 条极端同文件名后缀提示 `3897d404` `remediation_recommended` 记入已知边界）。
- **最新代码体量总计**（所有 `src/` 目录，含单元测试，不含 `build/`）：**170 个 `.kt` 文件 / 27,407 行**（另含 `Sonic.java`；`core-model/src` 14/1,416、`core-engine/src` 48/6,373、`core-reader/src` 14/2,480、`app/src` 94/17,138，全 `src/main` 文件严格 `<= 350` 行）

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
- [x] **内置阅读器与文件同步中心 emulator `pixel_34` 实机专项核验（已完成基础渲染与本地同步全链路）**：已在 `pixel_34` 安装 `app-cloud-release.apk` 并实机截图查验 `BooksSync` 同步导入、EPUB 2 `DOCTYPE` 中文 CSS/插图、EPUB 3 `<ruby>` 注音精排、GB18030 TXT 分页阅读及统一文件/网盘界面；WebDAV `PROPFIND`/`PUT`/`GET`、URL 编码与防路径穿越已在 `MockWebServer` 覆盖。
- [ ] **真机专项待在个人真实账号环境下补充**：
  - 在已登录 Google 账号且在 Google Cloud Console 绑定当前签名 SHA-1 (`95:96:54:25:F7:DC:C2:7B:0C:99:1D:94:68:DF:AB:BD:20:B0:97:A5`) 的真机上完成 Play Services `Identity.getAuthorizationClient` 一键同意与双向同步真机联调（或通过系统“网盘直选 SAF”零配置导入）。
  - 在个人 Nextcloud / Alist / 坚果云真实 WebDAV 服务端下完成双设备 `aitts_library_state.json` 跨设备进度/划线实时联调。
