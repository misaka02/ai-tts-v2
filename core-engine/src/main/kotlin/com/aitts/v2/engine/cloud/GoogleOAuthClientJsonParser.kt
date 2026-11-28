package com.aitts.v2.engine.cloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException

data class ParsedGoogleOAuthClient(
    val clientId: String,
    val clientSecret: String,
    val clientType: String,
    val refreshToken: String = "",
    val accessToken: String = "",
    val loopbackRedirectUri: String = ""
)

/**
 * Parses Google Drive credentials from:
 * 1) Google Cloud Console `client_secret_*.json` (`installed`)
 * 2) OAuth token JSON (`token.json` / `authorized_user`) containing `client_id` + `refresh_token`/`access_token`
 * 3) `rclone.conf` sections (`type = drive` with `client_id` / `token`)
 */
object GoogleOAuthClientJsonParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(rawJson: String): Result<ParsedGoogleOAuthClient> = runCatching {
        val text = rawJson.trim()
        if (text.isEmpty()) throw IOException("凭证文件内容为空")
        if (!text.startsWith("{")) {
            parseRcloneConf(text)?.let { return@runCatching it }
        }
        val root = runCatching { json.parseToJsonElement(text).jsonObject }
            .getOrElse { throw IOException("无法识别凭证格式，请导入 Google Cloud Desktop JSON、token.json 或 rclone.conf") }

        val (typeLabel, payload, loopbackUri) = pickPayloadObject(root)
        val clientId = payload["client_id"]?.jsonPrimitive?.content?.trim().orEmpty()
        val clientSecret = payload["client_secret"]?.jsonPrimitive?.content?.trim().orEmpty()
        val refreshToken = payload["refresh_token"]?.jsonPrimitive?.content?.trim().orEmpty()
        val accessToken = payload["access_token"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (clientId.isEmpty() && accessToken.isEmpty() && refreshToken.isEmpty()) {
            throw IOException("未在凭证文件中找到有效的 client_id 或 token 字段")
        }
        if (clientId.isEmpty() && refreshToken.isNotEmpty()) {
            throw IOException("检测到 refresh_token 但缺少对应的 client_id，请补充 OAuth Client ID")
        }
        ParsedGoogleOAuthClient(
            clientId = clientId,
            clientSecret = clientSecret,
            clientType = typeLabel,
            refreshToken = refreshToken,
            accessToken = accessToken,
            loopbackRedirectUri = loopbackUri
        )
    }

    private val LOOPBACK_URI_RE = Regex("^http://(127\\.0\\.0\\.1|localhost):(\\d+)(/.*)?$")

    private fun pickPayloadObject(root: JsonObject): Triple<String, JsonObject, String> {
        root["installed"]?.let { element ->
            return Triple("installed", element.jsonObject, "")
        }
        root["web"]?.let { element ->
            val webObj = element.jsonObject
            val uris = webObj["redirect_uris"]?.jsonArray.orEmpty()
                .mapNotNull { runCatching { it.jsonPrimitive.content.trim() }.getOrNull() }
            val matchedLoopback = uris.firstOrNull { uri ->
                val m = LOOPBACK_URI_RE.matchEntire(uri) ?: return@firstOrNull false
                val port = m.groupValues[2].toIntOrNull() ?: return@firstOrNull false
                port in 1..65535
            }
            if (matchedLoopback != null) {
                return Triple("web", webObj, matchedLoopback)
            }
            throw IOException(
                "检测到 Web 应用 (web) 凭证且未配置本机回环回调。\n" +
                    "请任选其一：\n" +
                    "1. 在 Google Cloud Console 新建「桌面应用 (Desktop app)」OAuth 客户端（推荐）；\n" +
                    "2. 或在该 Web 客户端的「已获授权的重定向 URI」中添加 http://127.0.0.1:53682/callback 后重新下载 JSON。"
            )
        }
        val label = if (root["refresh_token"] != null) "token_json" else "flat"
        return Triple(label, root, "")
    }

    private fun parseRcloneConf(raw: String): ParsedGoogleOAuthClient? {
        val sections = mutableListOf<MutableMap<String, String>>()
        var current: MutableMap<String, String>? = null
        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) continue
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                current = mutableMapOf<String, String>().also { sections.add(it) }
                continue
            }
            val eq = trimmed.indexOf('=')
            if (eq > 0 && current != null) {
                val k = trimmed.substring(0, eq).trim().lowercase()
                val v = trimmed.substring(eq + 1).trim()
                current[k] = v
            }
        }
        val driveSec = sections.firstOrNull { it["type"]?.equals("drive", ignoreCase = true) == true }
            ?: return null
        val cid = driveSec["client_id"].orEmpty().trim()
        val csec = driveSec["client_secret"].orEmpty().trim()
        val tokenObj = driveSec["token"]?.takeIf { it.startsWith("{") }?.let { tokRaw ->
            runCatching { json.parseToJsonElement(tokRaw).jsonObject }.getOrNull()
        }
        val access = tokenObj?.get("access_token")?.jsonPrimitive?.content?.trim().orEmpty()
        val refresh = tokenObj?.get("refresh_token")?.jsonPrimitive?.content?.trim().orEmpty()
        if (cid.isEmpty() && access.isEmpty() && refresh.isEmpty()) {
            throw IOException("rclone 配置中的 Google Drive 节点缺少 client_id 或 token")
        }
        if (cid.isEmpty() && refresh.isNotEmpty()) {
            throw IOException("检测到 rclone 配置包含 refresh_token 但缺少 client_id，请配置自定义 client_id 后重试")
        }
        return ParsedGoogleOAuthClient(
            clientId = cid,
            clientSecret = csec,
            clientType = "rclone",
            refreshToken = refresh,
            accessToken = access
        )
    }
}
