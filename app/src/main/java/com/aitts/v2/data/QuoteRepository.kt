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

class QuoteRepository(private val httpManager: HttpClientManager) {

    @Serializable
    private data class HitokotoDto(
        val hitokoto: String = "",
        val from: String? = null,
        @SerialName("from_who") val fromWho: String? = null
    )

    private val localQuotes = listOf(
        QuoteItem(
            "“这柄天玄诛仙剑，乃是上古神魔遗留在凡间的至宝。”老者抚须长叹道。少年握紧剑柄，目光坚定：“前辈放心，我必以它荡平魔域！”",
            "小说剧场 · 仙侠对白"
        ),
        QuoteItem(
            "魔尊漠然冷笑道：“区区蝼蚁，也妄想与日月争辉？今日便让你们知晓，何为真正的力量！”",
            "小说剧场 · 情绪演播"
        ),
        QuoteItem(
            "曲曲折折的荷塘上面，弥望的是田田的叶子。叶子出水很高，像亭亭的舞女的裙。微风过处，送来缕缕清香，仿佛远处高楼上渺茫的歌声似的。",
            "《荷塘月色》 · 朱自清"
        ),
        QuoteItem(
            "全新发布的旗舰处理器采用 3nm 工艺制程，集成新一代 NPU 与 GPU 架构，AI 推理算力提升超过 45%，并支持 WiFi 6 与 1080P 输出。",
            "科技数码 · 混读测试"
        ),
        QuoteItem(
            "2026年春季航线调整后，第12号登机口将于14:30准时开放，票价¥480，准点率达到98.5%，客服热线13800138000。",
            "数字规范化测试"
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
                    .ifBlank { "在线语料" }
                QuoteItem(dto.hitokoto.trim(), src)
            }
        }.getOrElse { randomLocalQuote() }
    }
}
