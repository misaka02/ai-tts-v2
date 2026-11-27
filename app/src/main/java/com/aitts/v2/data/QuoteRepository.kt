package com.aitts.v2.data

import com.aitts.v2.engine.network.HttpClientManager
import com.aitts.v2.engine.provider.HttpCallSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Request

data class QuoteItem(
    val text: String,
    val source: String
)

/**
 * Quote repository supporting online Hitokoto (一言) fetching
 * and clean practical local test samples.
 */
class QuoteRepository(private val httpManager: HttpClientManager) {

    @Serializable
    private data class HitokotoDto(
        val hitokoto: String = "",
        val from: String? = null,
        @SerialName("from_who") val fromWho: String? = null
    )

    private val localQuotes = listOf(
        QuoteItem(
            "曲曲折折的荷塘上面，弥望的是田田的叶子。叶子出水很高，像亭亭的舞女的裙。微风过处，送来缕缕清香，仿佛远处高楼上渺茫的歌声似的。",
            "《荷塘月色》 · 朱自清"
        ),
        QuoteItem(
            "全新架构将文本分段、语音合成与流式播放解耦，在保证音质还原的同时，大幅降低了长文听书的首包响应延迟。",
            "自然散文 · 清晰度测试"
        ),
        QuoteItem(
            "2026年春季航线调整后，第12号登机口将于14:30准时开放，票价¥480，准点率达到98.5%，支持Wi-Fi与1080p多媒体播报。",
            "数字与中英混读样文"
        )
    )

    fun randomLocalQuote(): QuoteItem = localQuotes.random()

    suspend fun fetchOnlineQuote(): QuoteItem = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://v1.hitokoto.cn/?c=d&c=e&c=h&c=i&c=k&encode=json")
            .addHeader("User-Agent", "AI-TTS-Engine-V2")
            .build()
        runCatching {
            HttpCallSupport.executeTracked(httpManager, "", req) { resp ->
                if (!resp.isSuccessful) return@executeTracked randomLocalQuote()
                val dto = HttpCallSupport.json.decodeFromString<HitokotoDto>(resp.body?.string().orEmpty())
                if (dto.hitokoto.isBlank()) return@executeTracked randomLocalQuote()
                val src = listOfNotNull(
                    dto.from?.takeIf { it.isNotBlank() }?.let { "《$it》" },
                    dto.fromWho?.takeIf { it.isNotBlank() }
                )
                    .joinToString(" · ")
                    .ifBlank { "一言 · 在线语料" }
                QuoteItem(dto.hitokoto.trim(), src)
            }
        }.getOrElse { randomLocalQuote() }
    }
}
