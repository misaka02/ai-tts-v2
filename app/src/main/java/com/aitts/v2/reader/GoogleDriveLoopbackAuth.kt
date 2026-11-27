package com.aitts.v2.reader

import com.aitts.v2.engine.cloud.GoogleDriveAuthConfig
import com.aitts.v2.engine.cloud.GoogleDriveClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicLong

/**
 * Fallback developer loopback OAuth 2.0 PKCE helper for environments without Google Play Services.
 */
internal class GoogleDriveLoopbackAuth(private val driveClient: GoogleDriveClient) {

    @Volatile
    private var activeSocket: ServerSocket? = null
    private val generationCounter = AtomicLong(0L)

    fun currentGeneration(): Long = generationCounter.get()

    fun cancelPending() {
        generationCounter.incrementAndGet()
        val socket = activeSocket
        activeSocket = null
        runCatching { socket?.close() }
    }

    suspend fun authorizeInBrowser(
        clientId: String,
        clientSecret: String,
        onLaunchUrl: (String) -> Unit,
        onPersistConfig: (GoogleDriveAuthConfig) -> Unit
    ): Result<GoogleDriveAuthConfig> = withContext(Dispatchers.IO) {
        val cleanId = clientId.trim()
        if (cleanId.isEmpty()) return@withContext Result.failure(IOException("请填写 OAuth Client ID"))
        cancelPending()
        val gen = generationCounter.incrementAndGet()
        val pkce = driveClient.generatePkce()
        var server: ServerSocket? = null
        try {
            server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply {
                soTimeout = AUTH_TIMEOUT_MS
            }
            activeSocket = server
            val redirectUri = "http://127.0.0.1:${server.localPort}/callback"
            val authUrl = driveClient.buildAuthorizationUrl(cleanId, redirectUri, pkce)
            withContext(Dispatchers.Main) { onLaunchUrl(authUrl) }

            val params = awaitCallback(server)
            if (gen != generationCounter.get()) throw IOException("登录请求已取消")
            val err = params["error"]
            if (!err.isNullOrBlank()) throw IOException("Google 拒绝授权: $err")
            if (params["state"] != pkce.state) throw IOException("OAuth 状态校验失败，请重试")
            val code = params["code"]?.takeIf { it.isNotBlank() } ?: throw IOException("未收到授权码 code")

            val exchanged = driveClient.exchangeAuthCode(
                clientId = cleanId,
                clientSecret = clientSecret.trim(),
                code = code,
                codeVerifier = pkce.codeVerifier,
                redirectUri = redirectUri
            ).getOrThrow()
            if (gen != generationCounter.get()) throw IOException("登录请求已取消")
            onPersistConfig(exchanged)
            Result.success(exchanged)
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            if (activeSocket === server) activeSocket = null
            runCatching { server?.close() }
        }
    }

    private fun awaitCallback(server: ServerSocket): Map<String, String> {
        val deadline = System.currentTimeMillis() + AUTH_TIMEOUT_MS
        while (true) {
            val remaining = (deadline - System.currentTimeMillis()).toInt()
            if (remaining <= 0) throw SocketTimeoutException("Google 授权回调等待超时")
            server.soTimeout = remaining
            server.accept().use { client ->
                client.soTimeout = READ_TIMEOUT_MS
                val requestLine = runCatching {
                    BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8)).readLine()
                }.getOrNull().orEmpty()
                val pathPart = requestLine.split(' ').getOrNull(1).orEmpty()
                val params = parseQuery(pathPart.substringAfter('?', ""))
                val ok = pathPart.substringBefore('?') == "/callback" &&
                    params.containsKey("state") &&
                    (params.containsKey("code") || params.containsKey("error"))
                if (!ok) {
                    runCatching { writeHtml(client, "404 Not Found", "<h3>Ignored</h3>") }
                    return@use
                }
                writeHtml(client, "200 OK", "<h3>授权完成</h3><p>已连接 Google Drive，请切回 AI-TTS。</p>")
                return params
            }
        }
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split('&').mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            URLDecoder.decode(pair.substring(0, idx), Charsets.UTF_8.name()) to
                URLDecoder.decode(pair.substring(idx + 1), Charsets.UTF_8.name())
        }.toMap()
    }

    private fun writeHtml(client: java.net.Socket, status: String, bodyHtml: String) {
        val html = "<html><head><meta charset=\"utf-8\"><title>AI-TTS</title></head>" +
            "<body style=\"font-family:sans-serif;padding:24px;\">$bodyHtml</body></html>"
        val bytes = html.toByteArray(Charsets.UTF_8)
        val hdr = "HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        client.getOutputStream().apply {
            write(hdr.toByteArray(Charsets.US_ASCII))
            write(bytes)
            flush()
        }
    }

    companion object {
        private const val AUTH_TIMEOUT_MS = 180_000
        private const val READ_TIMEOUT_MS = 10_000
    }
}
