package com.aitts.v2.engine.provider

import com.aitts.v2.engine.manifest.RemoteManifestRepository
import com.aitts.v2.engine.network.HttpClientManager
import com.aitts.v2.model.AcousticMapper
import com.aitts.v2.model.PresetVoices
import com.aitts.v2.model.ProviderCapabilities
import com.aitts.v2.model.ProviderCredentials
import com.aitts.v2.model.ProviderType
import com.aitts.v2.model.TtsProviderConfig
import com.aitts.v2.model.VoiceModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.regex.Pattern

class EdgeTtsProvider(
    private val httpManager: HttpClientManager,
    private val baseWssHost: String = "wss://speech.platform.bing.com",
    private val baseHttpHost: String = "https://speech.platform.bing.com",
    private val gecVersionProvider: () -> String = { RemoteManifestRepository.DEFAULT_EDGE_GEC }
) : TtsProvider {

    override val type = ProviderType.EDGE_TTS
    override val capabilities = ProviderCapabilities(
        supportsNativePcmStreaming = false,
        supportsNativeSpeedInBatch = true,
        supportsNativeSpeedInStream = true,
        supportsVoiceDiscovery = true
    )

    private val trustedToken = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private val winEpoch = 11644473600.0
    private val voicePattern = Pattern.compile("^([a-z]{2,})-([A-Z]{2,})-(.+Neural)$")

    override suspend fun fetchVoices(
        config: TtsProviderConfig,
        credentials: ProviderCredentials
    ): List<VoiceModel> = fetchVoicesInternal(config).first

    override suspend fun discoverCatalog(
        config: TtsProviderConfig,
        credentials: ProviderCredentials
    ): DiscoveryCatalog {
        val (voices, fromRemote) = fetchVoicesInternal(config)
        return DiscoveryCatalog(
            models = listOf("edge-neural"),
            voices = voices,
            resolvedEndpoint = resolveHttpRoot(config.baseUrl),
            fromRemote = fromRemote
        )
    }

    override suspend fun fetchModels(config: TtsProviderConfig, credentials: ProviderCredentials): List<String> =
        listOf("edge-neural")

    override suspend fun synthesize(request: ProviderSynthesisRequest): Result<SynthesizedAudio> =
        withContext(Dispatchers.IO) {
            val cfg = request.config
            val reqId = UUID.randomUUID().toString().replace("-", "")
            val connId = UUID.randomUUID().toString().replace("-", "")
            val muid = UUID.randomUUID().toString().replace("-", "").uppercase()
            val deferred = CompletableDeferred<ByteArray>()
            val audioStream = ByteArrayOutputStream()

            val gecVer = cfg.extraParams["gecVersion"]?.takeIf { it.isNotBlank() } ?: gecVersionProvider()
            val wssRoot = resolveWssRoot(cfg.baseUrl)
            val wssUrl = "$wssRoot/consumer/speech/synthesize/readaloud/edge/v1" +
                "?TrustedClientToken=$trustedToken&ConnectionId=$connId" +
                "&Sec-MS-GEC=${generateSecMsGec()}&Sec-MS-GEC-Version=$gecVer"

            val wsReq = Request.Builder()
                .url(wssUrl)
                .addHeader("Pragma", "no-cache")
                .addHeader("Cache-Control", "no-cache")
                .addHeader("User-Agent", EDGE_UA)
                .addHeader("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                .addHeader("Cookie", "muid=$muid;")
                .build()

            val ws = httpManager.client.newWebSocket(wsReq, createWsListener(cfg, reqId, request.text, audioStream, deferred))
            httpManager.registerWebSocket(request.sessionId, ws)
            try {
                val bytes = withTimeoutOrNull(25_000L) { deferred.await() }
                if (bytes != null && bytes.isNotEmpty()) {
                    Result.success(SynthesizedAudio(bytes, sampleRate = 24000, isRawPcm = false))
                } else {
                    Result.failure(IOException("Edge TTS synthesis timed out or returned empty audio"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                httpManager.unregisterWebSocket(request.sessionId, ws)
                runCatching { ws.cancel() }
            }
        }

    private suspend fun fetchVoicesInternal(config: TtsProviderConfig): Pair<List<VoiceModel>, Boolean> =
        withContext(Dispatchers.IO) {
            val host = resolveHttpRoot(config.baseUrl)
            val url = "$host/consumer/speech/synthesize/readaloud/voices/list?trustedclienttoken=$trustedToken"
            val req = Request.Builder().url(url).addHeader("User-Agent", EDGE_UA).build()
            var fromRemote = false
            val fetched = runCatching {
                HttpCallSupport.executeTracked(httpManager, "", req) { resp ->
                    if (!resp.isSuccessful) return@executeTracked emptyList()
                    val body = resp.body?.string().orEmpty()
                    val arr = HttpCallSupport.json.decodeFromString<JsonObject>("{\"voices\":$body}")["voices"]?.jsonArray
                        ?: return@executeTracked emptyList()
                    arr.mapNotNull { elem ->
                        val obj = elem.jsonObject
                        val shortName = obj["ShortName"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val friendly = obj["FriendlyName"]?.jsonPrimitive?.content ?: shortName
                        val gender = obj["Gender"]?.jsonPrimitive?.content ?: "Female"
                        val locale = obj["Locale"]?.jsonPrimitive?.content ?: "zh-CN"
                        VoiceModel(
                            id = shortName,
                            name = friendly.replace("Microsoft ", "").replace(" Online (Natural) - ", " (") + ")",
                            gender = gender,
                            locale = locale,
                            description = "$locale · $gender"
                        )
                    }.sortedByDescending { it.locale.startsWith("zh") }.also { if (it.isNotEmpty()) fromRemote = true }
                }
            }.getOrDefault(emptyList())
            fetched.ifEmpty { PresetVoices.edgeVoices } to fromRemote
        }

    private fun createWsListener(
        cfg: TtsProviderConfig,
        reqId: String,
        text: String,
        audioStream: ByteArrayOutputStream,
        deferred: CompletableDeferred<ByteArray>
    ): WebSocketListener = object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            val timestamp = formattedUtcDate()
            val rateStr = AcousticMapper.edgeSsmlRate(cfg.speed)
            val pitchStr = AcousticMapper.edgeSsmlPitchHz(cfg.pitch)
            val voiceName = formatEdgeVoiceName(cfg.voiceId.ifBlank { "zh-CN-XiaoxiaoNeural" })

            val configMsg = "X-Timestamp:$timestamp\r\nContent-Type:application/json; charset=utf-8\r\n" +
                "Path:speech.config\r\n\r\n" +
                "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":" +
                "{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"}," +
                "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n"
            ws.send(configMsg)

            val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='${detectLang(voiceName)}'>" +
                "<voice name='$voiceName'><prosody pitch='$pitchStr' rate='$rateStr' volume='+0%'>" +
                escapeXml(text) + "</prosody></voice></speak>"
            ws.send("X-RequestId:$reqId\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:${timestamp}Z\r\nPath:ssml\r\n\r\n$ssml")
        }

        override fun onMessage(ws: WebSocket, bytes: ByteString) {
            val data = bytes.toByteArray()
            if (data.size < 2) return
            val headerLen = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
            if (data.size > headerLen + 2) {
                val headerStr = String(data, 2, headerLen, Charsets.UTF_8)
                if (headerStr.contains("Path:audio")) {
                    audioStream.write(data, 2 + headerLen, data.size - 2 - headerLen)
                }
            }
        }

        override fun onMessage(ws: WebSocket, text: String) {
            if (text.contains("Path:turn.end")) {
                ws.close(1000, "Done")
                deferred.complete(audioStream.toByteArray())
            }
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            deferred.completeExceptionally(t)
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            if (!deferred.isCompleted) deferred.complete(audioStream.toByteArray())
        }
    }

    private fun resolveHttpRoot(raw: String): String {
        val clean = raw.trim().trimEnd('/')
        if (clean.isBlank()) return baseHttpHost
        return when {
            clean.startsWith("wss://") -> "https://" + clean.removePrefix("wss://")
            clean.startsWith("ws://") -> "http://" + clean.removePrefix("ws://")
            else -> clean
        }
    }

    private fun resolveWssRoot(raw: String): String {
        val clean = raw.trim().trimEnd('/')
        if (clean.isBlank()) return baseWssHost
        return when {
            clean.startsWith("https://") -> "wss://" + clean.removePrefix("https://")
            clean.startsWith("http://") -> "ws://" + clean.removePrefix("http://")
            else -> clean
        }
    }

    private fun formatEdgeVoiceName(voiceId: String): String {
        if (voiceId.startsWith("Microsoft Server Speech")) return voiceId
        val m = voicePattern.matcher(voiceId)
        if (m.find()) {
            val lang = m.group(1) ?: "zh"
            var region = m.group(2) ?: "CN"
            var name = m.group(3) ?: "XiaoxiaoNeural"
            if (name.contains("-")) {
                region = "$region-${name.substringBefore("-")}"
                name = name.substringAfter("-")
            }
            return "Microsoft Server Speech Text to Speech Voice ($lang-$region, $name)"
        }
        return "Microsoft Server Speech Text to Speech Voice (zh-CN, XiaoxiaoNeural)"
    }

    private fun detectLang(voiceName: String): String = when {
        voiceName.contains("zh-TW") -> "zh-TW"
        voiceName.contains("zh-HK") -> "zh-HK"
        voiceName.contains("ja-JP") -> "ja-JP"
        voiceName.contains("en-US") -> "en-US"
        voiceName.contains("en-GB") -> "en-GB"
        else -> "zh-CN"
    }

    private fun escapeXml(input: String): String =
        input.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")

    private fun formattedUtcDate(): String {
        val sdf = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    private fun generateSecMsGec(): String {
        var ticks = (System.currentTimeMillis() / 1000.0) + winEpoch
        ticks -= ticks % 300.0
        ticks *= 10_000_000.0
        val raw = "${ticks.toLong()}$trustedToken"
        val hash = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.US_ASCII))
        return hash.joinToString("") { "%02X".format(it) }
    }

    companion object {
        private const val EDGE_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"
    }
}
