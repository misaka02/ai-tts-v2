package com.aitts.v2.engine.cloud

import com.aitts.v2.engine.network.HttpClientManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Pure-JVM Google Drive v3 + OAuth 2.0 PKCE client built on HttpClientManager
 * so user HTTP/SOCKS proxy settings automatically apply to Drive requests.
 */
class GoogleDriveClient(
    internal val httpManager: HttpClientManager,
    private val authBaseUrl: String = DEFAULT_AUTH_URL,
    private val tokenUrl: String = DEFAULT_TOKEN_URL,
    internal val apiBaseUrl: String = DEFAULT_API_URL
) {
    internal val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()

    fun generatePkce(): PkcePair {
        val verifierBytes = ByteArray(32)
        random.nextBytes(verifierBytes)
        val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierBytes)
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        val stateBytes = ByteArray(16)
        random.nextBytes(stateBytes)
        val state = Base64.getUrlEncoder().withoutPadding().encodeToString(stateBytes)
        return PkcePair(codeVerifier = verifier, codeChallenge = challenge, state = state)
    }

    fun buildAuthorizationUrl(clientId: String, redirectUri: String, pkce: PkcePair): String {
        return authBaseUrl.toHttpUrl().newBuilder()
            .addQueryParameter("client_id", clientId.trim())
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("scope", DRIVE_READONLY_SCOPE)
            .addQueryParameter("access_type", "offline")
            .addQueryParameter("prompt", "consent")
            .addQueryParameter("code_challenge", pkce.codeChallenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("state", pkce.state)
            .build()
            .toString()
    }

    suspend fun exchangeAuthCode(
        clientId: String,
        clientSecret: String,
        code: String,
        codeVerifier: String,
        redirectUri: String
    ): Result<GoogleDriveAuthConfig> = withContext(Dispatchers.IO) {
        runCatching {
            val formBuilder = FormBody.Builder()
                .add("client_id", clientId.trim())
                .add("code", code.trim())
                .add("code_verifier", codeVerifier)
                .add("redirect_uri", redirectUri)
                .add("grant_type", "authorization_code")
            if (clientSecret.isNotBlank()) formBuilder.add("client_secret", clientSecret.trim())

            val tokenObj = executeTokenRequest(formBuilder.build())
            val baseConfig = GoogleDriveAuthConfig(
                clientId = clientId.trim(),
                clientSecret = clientSecret.trim(),
                accessToken = tokenObj["access_token"]?.jsonPrimitive?.content.orEmpty(),
                refreshToken = tokenObj["refresh_token"]?.jsonPrimitive?.content.orEmpty(),
                expiresAtEpochMs = computeExpiry(tokenObj)
            )
            if (baseConfig.accessToken.isBlank()) throw IOException("未返回 access_token")
            enrichWithUserAbout(baseConfig)
        }
    }

    suspend fun refreshAccessToken(config: GoogleDriveAuthConfig): Result<GoogleDriveAuthConfig> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (config.clientId.isBlank() || config.refreshToken.isBlank()) {
                    throw IOException("缺少 Client ID 或 Refresh Token，无法刷新令牌")
                }
                val formBuilder = FormBody.Builder()
                    .add("client_id", config.clientId.trim())
                    .add("refresh_token", config.refreshToken.trim())
                    .add("grant_type", "refresh_token")
                if (config.clientSecret.isNotBlank()) formBuilder.add("client_secret", config.clientSecret.trim())

                val tokenObj = executeTokenRequest(formBuilder.build())
                val newAccess = tokenObj["access_token"]?.jsonPrimitive?.content.orEmpty()
                if (newAccess.isBlank()) throw IOException("刷新令牌未返回新的 access_token")
                val newRefresh = tokenObj["refresh_token"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: config.refreshToken
                val updated = config.copy(
                    accessToken = newAccess,
                    refreshToken = newRefresh,
                    expiresAtEpochMs = computeExpiry(tokenObj)
                )
                if (updated.accountEmail.isBlank()) enrichWithUserAbout(updated) else updated
            }
        }

    suspend fun fetchUserAbout(accessToken: String): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "${apiBaseUrl.trimEnd('/')}/drive/v3/about".toHttpUrl().newBuilder()
                .addQueryParameter("fields", "user(displayName,emailAddress)")
                .build()
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${accessToken.trim()}")
                .build()
            httpManager.client.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IOException("Google Drive 校验失败 (${resp.code}): ${extractErrorMessage(bodyStr)}")
                val userObj = json.parseToJsonElement(bodyStr).jsonObject["user"]?.jsonObject
                val name = userObj?.get("displayName")?.jsonPrimitive?.content.orEmpty()
                val email = userObj?.get("emailAddress")?.jsonPrimitive?.content.orEmpty()
                name to email
            }
        }
    }

    suspend fun listFiles(
        config: GoogleDriveAuthConfig,
        folderId: String = "root",
        searchQuery: String = "",
        ebooksOnly: Boolean = true,
        pageToken: String? = null,
        onTokenRefreshed: (GoogleDriveAuthConfig) -> Unit = {}
    ): Result<DriveFilePage> = withContext(Dispatchers.IO) {
        runCatching {
            val safeFolder = folderId.trim().ifEmpty { "root" }
            if (safeFolder != "root" && !DRIVE_ID_RE.matches(safeFolder)) {
                throw IOException("无效的 Google Drive 目录 ID")
            }
            val qStr = buildDriveQuery(safeFolder, searchQuery.trim())
            val url = "${apiBaseUrl.trimEnd('/')}/drive/v3/files".toHttpUrl().newBuilder()
                .addQueryParameter("q", qStr)
                .addQueryParameter("fields", "nextPageToken,files(id,name,mimeType,size,modifiedTime)")
                .addQueryParameter("orderBy", "folder,modifiedTime desc")
                .addQueryParameter("pageSize", "80")
                .addQueryParameter("includeItemsFromAllDrives", "true")
                .addQueryParameter("supportsAllDrives", "true")
                .apply { if (!pageToken.isNullOrBlank()) addQueryParameter("pageToken", pageToken) }
                .build()
                .toString()

            executeAuthorized(config, url, onTokenRefreshed) { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IOException("读取网盘目录失败 (${resp.code}): ${extractErrorMessage(bodyStr)}")
                parseFilePage(bodyStr, ebooksOnly)
            }
        }
    }

    suspend fun downloadFile(
        config: GoogleDriveAuthConfig,
        fileId: String,
        outputFile: File,
        maxBytes: Long = 200L * 1024 * 1024,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        onTokenRefreshed: (GoogleDriveAuthConfig) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            if (!DRIVE_ID_RE.matches(fileId)) throw IOException("无效的文件 ID")
            val url = "${apiBaseUrl.trimEnd('/')}/drive/v3/files/$fileId?alt=media&supportsAllDrives=true"
            executeAuthorized(config, url, onTokenRefreshed) { resp ->
                if (!resp.isSuccessful) {
                    val errText = resp.body?.string().orEmpty()
                    throw IOException("下载文件失败 (${resp.code}): ${extractErrorMessage(errText)}")
                }
                val body = resp.body ?: throw IOException("空响应体")
                val totalLen = body.contentLength()
                outputFile.parentFile?.mkdirs()
                val tmp = File(outputFile.parentFile, "${outputFile.name}.tmp")
                try {
                    body.byteStream().use { input ->
                        tmp.outputStream().use { out ->
                            copyWithProgress(input, out, maxBytes, totalLen, onProgress)
                        }
                    }
                    if (!tmp.renameTo(outputFile)) {
                        tmp.copyTo(outputFile, overwrite = true)
                        tmp.delete()
                    }
                } catch (t: Throwable) {
                    tmp.delete()
                    throw t
                }
                outputFile
            }
        }
    }

    internal suspend fun <T> executeAuthorized(
        initialConfig: GoogleDriveAuthConfig,
        url: String,
        onTokenRefreshed: (GoogleDriveAuthConfig) -> Unit,
        requestBuilder: (Request.Builder) -> Request.Builder = { it },
        handler: suspend (Response) -> T
    ): T {
        var active = initialConfig
        val canRefresh = active.refreshToken.isNotBlank() && active.clientId.isNotBlank()
        val expiredSoon = active.expiresAtEpochMs > 0L && System.currentTimeMillis() + 60_000L >= active.expiresAtEpochMs
        val needsPreRefresh = active.accessToken.isBlank() || expiredSoon
        if (needsPreRefresh && canRefresh) {
            active = refreshAccessToken(active).getOrThrow()
            onTokenRefreshed(active)
        }
        if (active.accessToken.isBlank()) throw IOException("未连接 Google 网盘或 Access Token 无效")

        val req1 = requestBuilder(Request.Builder().url(url))
            .header("Authorization", "Bearer ${active.accessToken}")
            .build()
        httpManager.client.newCall(req1).execute().use { resp ->
            if (resp.code != 401 || active.refreshToken.isBlank() || active.clientId.isBlank()) {
                return handler(resp)
            }
        }
        val refreshed = refreshAccessToken(active).getOrThrow()
        onTokenRefreshed(refreshed)
        val req2 = requestBuilder(Request.Builder().url(url))
            .header("Authorization", "Bearer ${refreshed.accessToken}")
            .build()
        httpManager.client.newCall(req2).execute().use { retryResp ->
            return handler(retryResp)
        }
    }

    private fun executeTokenRequest(formBody: FormBody): JsonObject {
        val req = Request.Builder().url(tokenUrl).post(formBody).build()
        httpManager.client.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("OAuth 认证失败 (${resp.code}): ${extractErrorMessage(bodyStr)}")
            }
            return json.parseToJsonElement(bodyStr).jsonObject
        }
    }

    private suspend fun enrichWithUserAbout(config: GoogleDriveAuthConfig): GoogleDriveAuthConfig {
        val about = fetchUserAbout(config.accessToken).getOrNull() ?: return config
        return config.copy(accountName = about.first, accountEmail = about.second)
    }

    private fun computeExpiry(tokenObj: JsonObject): Long {
        val sec = tokenObj["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600L
        return System.currentTimeMillis() + (sec * 1000L)
    }

    private fun buildDriveQuery(folderId: String, searchQuery: String): String {
        val parts = mutableListOf("trashed = false")
        if (searchQuery.isEmpty() || folderId != "root") {
            parts.add("'$folderId' in parents")
        }
        if (searchQuery.isNotEmpty()) {
            val escaped = searchQuery.replace("\\", "\\\\").replace("'", "\\'")
            parts.add("name contains '$escaped'")
        }
        return parts.joinToString(" and ")
    }

    private fun parseFilePage(bodyStr: String, ebooksOnly: Boolean): DriveFilePage {
        val root = json.parseToJsonElement(bodyStr).jsonObject
        val nextToken = root["nextPageToken"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val items = root["files"]?.jsonArray.orEmpty().mapNotNull { el ->
            val obj = el.jsonObject
            val id = obj["id"]?.jsonPrimitive?.content.orEmpty()
            val name = obj["name"]?.jsonPrimitive?.content.orEmpty()
            val mime = obj["mimeType"]?.jsonPrimitive?.content.orEmpty()
            if (id.isBlank() || name.isBlank()) return@mapNotNull null
            DriveFileItem(
                id = id,
                name = name,
                mimeType = mime,
                sizeBytes = obj["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                modifiedTime = obj["modifiedTime"]?.jsonPrimitive?.content.orEmpty()
            )
        }.filter { !ebooksOnly || it.isFolder || it.isSupportedEbook }
        return DriveFilePage(items = items, nextPageToken = nextToken)
    }

    private suspend fun copyWithProgress(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        maxBytes: Long,
        totalLen: Long,
        onProgress: (Long, Long) -> Unit
    ) {
        val buf = ByteArray(16 * 1024)
        var downloaded = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buf)
            if (count <= 0) break
            downloaded += count
            if (downloaded > maxBytes) {
                throw IOException("文件超出 ${maxBytes / (1024 * 1024)} MB 大小上限")
            }
            output.write(buf, 0, count)
            onProgress(downloaded, totalLen)
        }
        output.flush()
    }

    internal fun extractErrorMessage(body: String): String {
        return runCatching {
            val obj = json.parseToJsonElement(body).jsonObject
            obj["error_description"]?.jsonPrimitive?.content
                ?: obj["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                ?: obj["error"]?.jsonPrimitive?.content
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: body.take(180)
    }

    companion object {
        const val DEFAULT_AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
        const val DEFAULT_TOKEN_URL = "https://oauth2.googleapis.com/token"
        const val DEFAULT_API_URL = "https://www.googleapis.com"
        const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive"
        private const val DRIVE_READONLY_SCOPE = DRIVE_SCOPE
        internal val DRIVE_ID_RE = Regex("^[a-zA-Z0-9._-]+$")
    }
}
