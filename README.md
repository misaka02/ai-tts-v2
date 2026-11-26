# AI-TTS Android v2

安卓系统级 AI 大模型与离线神经网络 TTS 语音引擎（Clean Architecture 重构版）。

## 模块结构

- `:core-model`：纯 Kotlin/JVM 不可变领域模型与预设目录（零 Android / OkHttp 依赖）。
- `:core-engine`：纯 Kotlin/JVM 核心引擎（严格 4 阶段文本管线、PCM16 帧对齐/重采样/Sonic 音频管线、原子 LRU 磁盘缓存、云端 Provider 矩阵、Sherpa-ONNX 模型下载校验、会话隔离合成编排器）。
- `:app`：Android 系统 `TextToSpeechService`、Android Keystore 密钥隔离存储、Jetpack DataStore、MediaStore 零权限导出、AudioTrack 流式试听、轻量悬浮字幕、单套 Material 3 UI。
  - `cloud` flavor：纯云端轻量版（不包含 C++ `.so`）。
  - `full` flavor：内置 `arm64-v8a` Sherpa-ONNX 离线推理库，支持 VITS / Matcha / Kokoro / ZipVoice 模型。

## 质量门禁

```bash
python scripts/check_architecture.py
./gradlew qualityGate
```
