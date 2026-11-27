package com.aitts.v2.reader

import android.content.Context
import com.aitts.v2.data.SecretStore
import com.aitts.v2.engine.cloud.DriveFileItem
import com.aitts.v2.engine.cloud.DriveFilePage
import com.aitts.v2.engine.cloud.GoogleDriveAuthConfig
import com.aitts.v2.engine.cloud.GoogleDriveClient
import com.aitts.v2.reader.model.BookRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Coordinates encrypted Google Drive credential persistence, loopback PKCE OAuth,
 * directory browsing, and streaming download-to-bookshelf import.
 */
class GoogleDriveRepository(
    context: Context,
    private val secretStore: SecretStore,
    private val driveClient: GoogleDriveClient,
    private val bookImportManager: BookImportManager
) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val configLock = Any()

    @Volatile
    private var activeAuthSocket: ServerSocket? = null
    private val authGeneration = AtomicLong(0L)

    private val _authFlow = MutableStateFlow(loadConfigFromStore())
    val authFlow: StateFlow<GoogleDriveAuthConfig> = _authFlow.asStateFlow()

    suspend fun authorizeInBrowser(
        clientId: String,
        clientSecret: String,
        onLaunchUrl: (String) -> Unit
    ): Result<GoogleDriveAuthConfig> = withContext(Dispatchers.IO) {
        val cleanClientId = clientId.trim()
        if (cleanClientId.isEmpty()) {
            return@withContext Result.failure(IOException("请先填写 Google OAuth Client ID"))
        }
        cancelPendingAuth()
        val generation = authGeneration.incrementAndGet()
        val pkce = driveClient.generatePkce()
        var server: ServerSocket? = null
        try {
            server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply {
                soTimeout = AUTH_TIMEOUT_MS
            }
            activeAuthSocket = server
            val redirectUri = "http://127.0.0.1:${server.localPort}/callback"
            val authUrl = driveClient.buildAuthorizationUrl(cleanClientId, redirectUri, pkce)
            withContext(Dispatchers.Main) { onLaunchUrl(authUrl) }

            val queryParams = awaitLoopbackCallback(server)
            if (generation != authGeneration.get()) throw IOException("登录请求已取消")
            val err = queryParams["error"]
            if (!err.isNullOrBlank()) throw IOException("Google 拒绝授权: $err")
            if (queryParams["state"] != pkce.state) throw IOException("OAuth 状态校验失败，请重试")
            val code = queryParams["code"]?.takeIf { it.isNotBlank() }
                ?: throw IOException("未收到授权码 code")

            val exchanged = driveClient.exchangeAuthCode(
                clientId = cleanClientId,
                clientSecret = clientSecret.trim(),
                code = code,
                codeVerifier = pkce.codeVerifier,
                redirectUri = redirectUri
            ).getOrThrow()
            if (generation != authGeneration.get()) throw IOException("登录请求已取消")
            saveConfig(exchanged)
            Result.success(exchanged)
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            if (activeAuthSocket === server) activeAuthSocket = null
            runCatching { server?.close() }
        }
    }

    fun cancelPendingAuth() {
        authGeneration.incrementAndGet()
        val socket = activeAuthSocket
        activeAuthSocket = null
        runCatching { socket?.close() }
    }

    suspend fun connectDirect(
        clientId: String,
        clientSecret: String,
        refreshToken: String,
        accessToken: String
    ): Result<GoogleDriveAuthConfig> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanAccess = accessToken.trim()
            val cleanRefresh = refreshToken.trim()
            val cleanId = clientId.trim()
            val cleanSecret = clientSecret.trim()

            val verified = if (cleanRefresh.isNotEmpty() && cleanId.isNotEmpty()) {
                val seed = GoogleDriveAuthConfig(
                    clientId = cleanId,
                    clientSecret = cleanSecret,
                    refreshToken = cleanRefresh
                )
                driveClient.refreshAccessToken(seed).getOrThrow()
            } else if (cleanAccess.isNotEmpty()) {
                val (name, email) = driveClient.fetchUserAbout(cleanAccess).getOrThrow()
                GoogleDriveAuthConfig(
                    clientId = cleanId,
                    clientSecret = cleanSecret,
                    accessToken = cleanAccess,
                    refreshToken = cleanRefresh,
                    accountName = name,
                    accountEmail = email
                )
            } else {
                throw IOException("请提供有效的 Access Token 或 (Client ID + Refresh Token)")
            }
            saveConfig(verified)
            verified
        }
    }

    fun disconnect() {
        cancelPendingAuth()
        synchronized(configLock) {
            secretStore.saveSecretString(SECRET_KEY, "")
            _authFlow.value = GoogleDriveAuthConfig()
        }
    }

    suspend fun listFiles(
        folderId: String,
        searchQuery: String,
        ebooksOnly: Boolean,
        pageToken: String? = null
    ): Result<DriveFilePage> {
        return driveClient.listFiles(
            config = _authFlow.value,
            folderId = folderId,
            searchQuery = searchQuery,
            ebooksOnly = ebooksOnly,
            pageToken = pageToken,
            onTokenRefreshed = { saveConfig(it) }
        )
    }

    suspend fun downloadAndImport(
        item: DriveFileItem,
        onProgress: (Float) -> Unit
    ): Result<BookRecord> = withContext(Dispatchers.IO) {
        val dlDir = File(appContext.cacheDir, "drive_dl").apply { mkdirs() }
        val tmpFile = File(dlDir, "${UUID.randomUUID().toString().take(8)}_${item.name.take(32).replace(Regex("[^a-zA-Z0-9._-]"), "_")}")
        try {
            driveClient.downloadFile(
                config = _authFlow.value,
                fileId = item.id,
                outputFile = tmpFile,
                onProgress = { done, total ->
                    val knownTotal = if (total > 0L) total else item.sizeBytes
                    if (knownTotal > 0L) {
                        onProgress((done.toFloat() / knownTotal).coerceIn(0f, 1f))
                    }
                },
                onTokenRefreshed = { saveConfig(it) }
            ).getOrThrow()
            tmpFile.inputStream().use { stream ->
                bookImportManager.importFromStream(item.name, stream).getOrThrow()
            }.let { Result.success(it) }
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            tmpFile.delete()
        }
    }

    private fun awaitLoopbackCallback(server: ServerSocket): Map<String, String> {
        val deadlineMs = System.currentTimeMillis() + AUTH_TIMEOUT_MS
        while (true) {
            val remaining = (deadlineMs - System.currentTimeMillis()).toInt()
            if (remaining <= 0) throw java.net.SocketTimeoutException("Google 授权回调等待超时")
            server.soTimeout = remaining
            server.accept().use { client ->
                client.soTimeout = CALLBACK_READ_TIMEOUT_MS
                val requestLine = runCatching {
                    BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8)).readLine()
                }.getOrNull().orEmpty()
                val pathPart = requestLine.split(' ').getOrNull(1).orEmpty()
                val params = parseQueryParams(pathPart.substringAfter('?', ""))
                val hasAuthResult = params.containsKey("state") && (params.containsKey("code") || params.containsKey("error"))
                if (pathPart.substringBefore('?') != "/callback" || !hasAuthResult) {
                    runCatching { writeLoopbackResponse(client, "404 Not Found", "<h3>Ignored</h3>") }
                    return@use
                }
                writeLoopbackResponse(
                    client,
                    "200 OK",
                    "<h3>授权完成</h3><p>已成功连接 Google Drive，请切回 AI-TTS 查看授权状态。</p>"
                )
                return params
            }
        }
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split('&').mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val k = URLDecoder.decode(pair.substring(0, idx), Charsets.UTF_8.name())
            val v = URLDecoder.decode(pair.substring(idx + 1), Charsets.UTF_8.name())
            k to v
        }.toMap()
    }

    private fun writeLoopbackResponse(client: java.net.Socket, status: String, bodyHtml: String) {
        val html = """
            <html><head><meta charset="utf-8"><title>AI-TTS</title></head>
            <body style="font-family:sans-serif;padding:24px;">$bodyHtml</body></html>
        """.trimIndent().toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${html.size}\r\nConnection: close\r\n\r\n"
        client.getOutputStream().apply {
            write(header.toByteArray(Charsets.US_ASCII))
            write(html)
            flush()
        }
    }

    private fun saveConfig(config: GoogleDriveAuthConfig) {
        synchronized(configLock) {
            val saved = secretStore.saveSecretString(SECRET_KEY, json.encodeToString(config))
            if (!saved) throw IOException("无法将 Google 网盘凭据加密写入设备存储")
            _authFlow.value = config
        }
    }

    private fun loadConfigFromStore(): GoogleDriveAuthConfig {
        val raw = secretStore.getSecretString(SECRET_KEY) ?: return GoogleDriveAuthConfig()
        return runCatching { json.decodeFromString<GoogleDriveAuthConfig>(raw) }
            .getOrDefault(GoogleDriveAuthConfig())
    }

    companion object {
        private const val SECRET_KEY = "google_drive_auth_v1"
        private const val AUTH_TIMEOUT_MS = 180_000
        private const val CALLBACK_READ_TIMEOUT_MS = 10_000
    }
}
