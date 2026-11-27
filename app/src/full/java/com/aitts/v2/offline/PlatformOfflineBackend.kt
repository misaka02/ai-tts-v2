package com.aitts.v2.offline

import android.content.Context
import com.aitts.v2.engine.audio.WavCodec
import com.aitts.v2.engine.offline.OfflineModelInstaller
import com.aitts.v2.engine.provider.ProviderSynthesisRequest
import com.aitts.v2.engine.provider.SynthesizedAudio
import com.aitts.v2.engine.provider.TtsProvider
import com.aitts.v2.model.AcousticMapper
import com.aitts.v2.model.ModelArch
import com.aitts.v2.model.OfflineCatalogData
import com.aitts.v2.model.OfflineModelSpec
import com.aitts.v2.model.ProviderCapabilities
import com.aitts.v2.model.ProviderCredentials
import com.aitts.v2.model.ProviderType
import com.aitts.v2.model.TtsProviderConfig
import com.aitts.v2.model.VoiceModel
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsZipVoiceModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

object PlatformOfflineBackend : OfflineTtsBackendFactory {
    override fun isNativeRuntimeBundled(): Boolean = true

    override fun createProvider(context: Context, installer: OfflineModelInstaller): TtsProvider {
        return SherpaOfflineProvider(installer)
    }
}

/**
 * Reflection-free Sherpa-ONNX offline TTS provider for the `full` arm64 flavor.
 * Supports configurable `numThreads` (1..4) and `silenceScale` (0.0..1.0) via `extraParams`.
 */
private class SherpaOfflineProvider(
    private val installer: OfflineModelInstaller
) : TtsProvider {

    override val type = ProviderType.OFFLINE_SHERPA
    override val capabilities = ProviderCapabilities(
        supportsNativePcmStreaming = false,
        supportsNativeSpeedInBatch = true
    )

    private val lock = Any()
    private var loadedConfigKey: String? = null
    private var currentTts: OfflineTts? = null

    override suspend fun fetchVoices(config: TtsProviderConfig, credentials: ProviderCredentials): List<VoiceModel> {
        val spec = OfflineCatalogData.findById(config.modelName)
            ?: installer.readInstalledSpec(config.modelName)
            ?: OfflineCatalogData.models.first()
        return spec.speakers.mapIndexed { idx, label -> VoiceModel("spk_$idx", label) }
    }

    override suspend fun fetchModels(config: TtsProviderConfig, credentials: ProviderCredentials): List<String> =
        OfflineCatalogData.models.map { it.id }

    override suspend fun synthesize(request: ProviderSynthesisRequest): Result<SynthesizedAudio> =
        withContext(Dispatchers.IO) {
            val modelId = request.config.modelName.ifBlank { OfflineCatalogData.models.first().id }
            val spec = OfflineCatalogData.findById(modelId)
                ?: installer.readInstalledSpec(modelId)
                ?: return@withContext Result.failure(IOException("未知的离线模型 ID: $modelId"))

            if (!installer.isModelInstalled(modelId)) {
                return@withContext Result.failure(
                    IOException("离线模型【${spec.name}】尚未下载 (${spec.sizeMb}MB)，请先在离线库中下载安装。")
                )
            }

            val threads = request.config.extraParams["numThreads"]?.toFloatOrNull()?.toInt()?.coerceIn(1, 4) ?: 2
            val silenceScale = request.config.extraParams["silenceScale"]?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.2f

            runCatching {
                ensureNativeLoaded()
                val tts = getOrInitTts(spec, threads, silenceScale)
                val sid = parseSpeakerId(request.config.voiceId)
                val safeSpeed = AcousticMapper.sherpaSpeed(request.config.speed)
                val generated = tts.generate(request.text, sid, safeSpeed)
                val samples = generated.samples
                if (samples.isEmpty()) throw IOException("Sherpa-ONNX 返回空音频采样")

                val pcmBytes = ByteArray(samples.size * 2)
                val bb = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
                for (sample in samples) {
                    bb.putShort((sample.coerceIn(-1.0f, 1.0f) * 32767.0f).toInt().toShort())
                }
                val wav = WavCodec.encodeToWav(pcmBytes, sampleRate = generated.sampleRate, channels = 1)
                SynthesizedAudio(bytes = wav, sampleRate = generated.sampleRate, isRawPcm = false)
            }
        }

    private fun getOrInitTts(spec: OfflineModelSpec, threads: Int, silenceScale: Float): OfflineTts = synchronized(lock) {
        val key = "${spec.id}|$threads|$silenceScale"
        if (currentTts != null && loadedConfigKey == key) return currentTts!!
        runCatching { currentTts?.release() }
        currentTts = null
        loadedConfigKey = null

        val dir = installer.getModelDir(spec.id)
        val allFiles = dir.walkTopDown().toList()
        val onnx = allFiles.firstOrNull { it.extension.equals("onnx", ignoreCase = true) }
            ?: throw IOException("未找到 .onnx 模型权重文件")
        val tokens = allFiles.firstOrNull { it.name.equals("tokens.txt", ignoreCase = true) }
            ?: throw IOException("未找到 tokens.txt 文件")
        val lexicon = allFiles.firstOrNull { it.name.equals("lexicon.txt", ignoreCase = true) }?.absolutePath.orEmpty()
        val dictDir = allFiles.firstOrNull { it.isDirectory && it.name.equals("dict", ignoreCase = true) }?.absolutePath.orEmpty()
        val espeakDir = allFiles
            .firstOrNull { it.isDirectory && it.name.equals("espeak-ng-data", ignoreCase = true) }
            ?.absolutePath.orEmpty()

        val modelCfg = buildModelConfig(spec, allFiles, onnx, tokens, lexicon, dictDir, espeakDir, threads)
        val fsts = allFiles.filter { it.extension.equals("fst", ignoreCase = true) }.joinToString(",") { it.absolutePath }
        val fars = allFiles.filter { it.extension.equals("far", ignoreCase = true) }.joinToString(",") { it.absolutePath }
        val ttsConfig = OfflineTtsConfig(
            model = modelCfg,
            ruleFsts = fsts,
            ruleFars = fars,
            maxNumSentences = 1,
            silenceScale = silenceScale
        )
        OfflineTts(null, ttsConfig).also {
            currentTts = it
            loadedConfigKey = key
        }
    }

    private fun buildModelConfig(
        spec: OfflineModelSpec,
        files: List<File>,
        onnx: File,
        tokens: File,
        lexicon: String,
        dictDir: String,
        espeakDir: String,
        threads: Int
    ): OfflineTtsModelConfig = when (spec.arch) {
        ModelArch.VITS -> OfflineTtsModelConfig(
            vits = OfflineTtsVitsModelConfig(
                model = onnx.absolutePath,
                lexicon = lexicon,
                tokens = tokens.absolutePath,
                dataDir = espeakDir,
                dictDir = dictDir
            ),
            numThreads = threads,
            provider = "cpu"
        )
        ModelArch.MATCHA -> {
            val acoustic = files.firstOrNull { it.name.contains("model", true) && it.extension.equals("onnx", true) } ?: onnx
            val vocoder = files.firstOrNull { it.name.contains("vocoder", true) || it.name.contains("hifi", true) } ?: onnx
            OfflineTtsModelConfig(
                matcha = OfflineTtsMatchaModelConfig(
                    acousticModel = acoustic.absolutePath,
                    vocoder = vocoder.absolutePath,
                    lexicon = lexicon,
                    tokens = tokens.absolutePath,
                    dataDir = espeakDir,
                    dictDir = dictDir
                ),
                numThreads = threads,
                provider = "cpu"
            )
        }
        ModelArch.KOKORO -> {
            val voices = files.firstOrNull { it.name.contains("voices", true) }?.absolutePath.orEmpty()
            OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = onnx.absolutePath,
                    voices = voices,
                    tokens = tokens.absolutePath,
                    dataDir = espeakDir,
                    lexicon = lexicon,
                    lang = "",
                    dictDir = dictDir
                ),
                numThreads = threads,
                provider = "cpu"
            )
        }
        ModelArch.ZIPVOICE -> {
            val enc = files.firstOrNull { it.name.contains("encoder", true) } ?: onnx
            val dec = files.firstOrNull { it.name.contains("decoder", true) } ?: onnx
            val voc = files.firstOrNull { it.name.contains("vocoder", true) } ?: onnx
            OfflineTtsModelConfig(
                zipvoice = OfflineTtsZipVoiceModelConfig(
                    tokens = tokens.absolutePath,
                    encoder = enc.absolutePath,
                    decoder = dec.absolutePath,
                    vocoder = voc.absolutePath,
                    dataDir = espeakDir,
                    lexicon = lexicon
                ),
                numThreads = threads,
                provider = "cpu"
            )
        }
    }

    private fun parseSpeakerId(voiceId: String): Int =
        voiceId.substringAfterLast("spk_", "").toIntOrNull()
            ?: voiceId.filter { it.isDigit() }.toIntOrNull()
            ?: 0

    companion object {
        @Volatile
        private var libLoaded = false

        fun ensureNativeLoaded() {
            if (libLoaded) return
            synchronized(this) {
                if (!libLoaded) {
                    System.loadLibrary("sherpa-onnx-jni")
                    libLoaded = true
                }
            }
        }
    }
}
