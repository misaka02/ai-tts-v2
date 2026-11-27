package com.aitts.v2.engine.cloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException

data class ParsedGoogleOAuthClient(
    val clientId: String,
    val clientSecret: String,
    val clientType: String
)

/**
 * Parses Google Cloud Console OAuth 2.0 `client_secret_*.json` downloads.
 * Rejects `web` clients upfront because loopback PKCE requires a Desktop/native (`installed`) client.
 */
object GoogleOAuthClientJsonParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(rawJson: String): Result<ParsedGoogleOAuthClient> = runCatching {
        val text = rawJson.trim()
        if (text.isEmpty()) throw IOException("JSON 文件为空")
        val root = runCatching { json.parseToJsonElement(text).jsonObject }
            .getOrElse { throw IOException("JSON 格式无效，请导入从 Google Cloud 下载的 OAuth 凭证文件") }

        val (typeLabel, payload) = pickPayloadObject(root)
        val clientId = payload["client_id"]?.jsonPrimitive?.content?.trim().orEmpty()
        val clientSecret = payload["client_secret"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (clientId.isEmpty()) {
            throw IOException("未在凭证文件中找到有效的 client_id 字段")
        }
        ParsedGoogleOAuthClient(
            clientId = clientId,
            clientSecret = clientSecret,
            clientType = typeLabel
        )
    }

    private fun pickPayloadObject(root: JsonObject): Pair<String, JsonObject> {
        root["installed"]?.let { element ->
            return "installed" to element.jsonObject
        }
        if (root["web"] != null) {
            throw IOException(
                "检测到 Web 应用 (web) 凭证：Google 不允许 Web 凭证使用随机本地回环端口。\n" +
                    "请在 Google Cloud Console 改为创建「桌面应用 (Desktop app)」OAuth 客户端并下载对应 JSON。"
            )
        }
        return "flat" to root
    }
}
