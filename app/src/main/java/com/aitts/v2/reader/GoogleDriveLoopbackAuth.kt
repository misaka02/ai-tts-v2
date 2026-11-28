package com.aitts.v2.reader

import com.aitts.v2.engine.cloud.GoogleDriveAuthConfig
import com.aitts.v2.engine.cloud.GoogleDriveClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Browser loopback OAuth 2.0 PKCE helper with concurrent socket handling (avoiding Chrome
 * preconnect starvation), fixed Web-client loopback URI support, deep-link return, and manual fallback.
 */
internal class GoogleDriveLoopbackAuth(private val driveClient: GoogleDriveClient) {

    @Volatile
    private var activeSocket: ServerSocket? = null

    @Volatile
    private var pendingDeferred: CompletableDeferred<Map<String, String>>? = null

    @Volatile
    private var activeExpectedState: String = ""
    private val generationCounter = AtomicLong(0L)

    fun cancelPending() {
        generationCounter.incrementAndGet()
        activeExpectedState = ""
        pendingDeferred?.cancel()
        pendingDeferred = null
        val socket = activeSocket
        activeSocket = null
        runCatching { socket?.close() }
    }

    fun completeManuallyFromUrl(pastedInput: String): Boolean {
        val deferred = pendingDeferred ?: return false
        val trimmed = pastedInput.trim()
        if (trimmed.isEmpty()) return false
        if ('?' in trimmed || '=' in trimmed) {
            val query = trimmed.substringAfter('?', trimmed)
            val params = parseQuery(query).toMutableMap()
            if (!params.containsKey("code") && !params.containsKey("error")) return false
            if (!params.containsKey("state") && params.containsKey("code") && activeExpectedState.isNotBlank()) {
                params["state"] = activeExpectedState
            }
            return params.containsKey("state") && deferred.complete(params)
        }
        if (trimmed.length >= 12 && !trimmed.any { it.isWhitespace() } && activeExpectedState.isNotBlank()) {
            return deferred.complete(mapOf("code" to trimmed, "state" to activeExpectedState))
        }
        return false
    }

    suspend fun authorizeInBrowser(
        clientId: String,
        clientSecret: String,
        preferredRedirectUri: String = "",
        onLaunchUrl: (String) -> Unit,
        onPersistConfig: (GoogleDriveAuthConfig) -> Unit
    ): Result<GoogleDriveAuthConfig> = withContext(Dispatchers.IO) {
        val cleanId = clientId.trim()
        if (cleanId.isEmpty()) return@withContext Result.failure(IOException("请填写 OAuth Client ID"))
        cancelPending()
        val gen = generationCounter.incrementAndGet()
        val pkce = driveClient.generatePkce()
        activeExpectedState = pkce.state
        val deferred = CompletableDeferred<Map<String, String>>()
        pendingDeferred = deferred
        val targetSpec = parseLoopbackSpec(preferredRedirectUri)
        val executor = ThreadPoolExecutor(4, 8, 5L, TimeUnit.SECONDS, ArrayBlockingQueue(8))
        var server: ServerSocket? = null
        try {
            val loopbackAddr = InetAddress.getByName("127.0.0.1")
            server = if (targetSpec != null) {
                runCatching { ServerSocket(targetSpec.port, 16, loopbackAddr) }.getOrElse {
                    throw IOException("固定回调端口 ${targetSpec.port} 被占用或不可用：${it.message}")
                }
            } else {
                ServerSocket(0, 16, loopbackAddr)
            }.apply { soTimeout = AUTH_TIMEOUT_MS }
            activeSocket = server

            val redirectUri = targetSpec?.let { "http://${it.host}:${server.localPort}${it.path}" }
                ?: "http://127.0.0.1:${server.localPort}/callback"
            val expectedPath = targetSpec?.path?.ifBlank { "/" } ?: "/callback"
            val authUrl = driveClient.buildAuthorizationUrl(cleanId, redirectUri, pkce)
            withContext(Dispatchers.Main) { onLaunchUrl(authUrl) }

            val acceptServer = server
            val acceptThread = Thread {
                runCatching { awaitCallbacks(acceptServer, expectedPath, deferred, executor) }
                    .onFailure { err -> if (!deferred.isCompleted) deferred.completeExceptionally(err) }
            }.apply { isDaemon = true; start() }

            val params = deferred.await()
            runCatching { acceptServer.close() }
            runCatching { acceptThread.join(300L) }

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
            if (pendingDeferred === deferred) {
                activeExpectedState = ""
                pendingDeferred = null
            }
            if (activeSocket === server) activeSocket = null
            runCatching { server?.close() }
            runCatching { executor.shutdownNow() }
        }
    }

    private fun awaitCallbacks(
        server: ServerSocket,
        expectedPath: String,
        deferred: CompletableDeferred<Map<String, String>>,
        executor: ExecutorService
    ) {
        val deadline = System.currentTimeMillis() + AUTH_TIMEOUT_MS
        while (!deferred.isCompleted && !server.isClosed) {
            val remaining = (deadline - System.currentTimeMillis()).toInt()
            if (remaining <= 0) throw SocketTimeoutException("Google 授权回调等待超时")
            server.soTimeout = remaining
            val client = runCatching { server.accept() }.getOrNull() ?: continue
            runCatching {
                executor.execute { handleClientSocket(client, expectedPath, deferred) }
            }.onFailure {
                runCatching { client.close() }
            }
        }
    }

    private fun handleClientSocket(
        client: Socket,
        expectedPath: String,
        deferred: CompletableDeferred<Map<String, String>>
    ) {
        client.use { sock ->
            runCatching {
                sock.soTimeout = READ_TIMEOUT_MS
                val requestLine = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
                    .readLine().orEmpty()
                val pathPart = requestLine.split(' ').getOrNull(1).orEmpty()
                val reqPath = pathPart.substringBefore('?')
                val params = parseQuery(pathPart.substringAfter('?', ""))
                val validPath = reqPath == expectedPath || reqPath == "/callback" || reqPath == "/"
                val ok = validPath && params.containsKey("state") &&
                    (params.containsKey("code") || params.containsKey("error"))
                if (!ok) {
                    writeHtml(sock, "404 Not Found", "<h3>Ignored</h3>")
                    return
                }
                val okHtml = "<h3>授权成功</h3><p>已连接 Google Drive，正在返回 AI-TTS...</p>" +
                    "<p><a href=\"aitts://oauth-done\" style=\"display:inline-block;padding:10px 18px;" +
                    "background:#26537C;color:#fff;border-radius:8px;text-decoration:none;\">点击返回 AI-TTS</a></p>" +
                    "<script>setTimeout(function(){location.href='aitts://oauth-done';},150);</script>"
                writeHtml(sock, "200 OK", okHtml, autoReturn = true)
                deferred.complete(params)
            }
        }
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return runCatching {
            query.split('&').mapNotNull { pair ->
                val idx = pair.indexOf('=')
                if (idx <= 0) return@mapNotNull null
                URLDecoder.decode(pair.substring(0, idx), Charsets.UTF_8.name()) to
                    URLDecoder.decode(pair.substring(idx + 1), Charsets.UTF_8.name())
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun writeHtml(client: Socket, status: String, bodyHtml: String, autoReturn: Boolean = false) {
        val refreshMeta = if (autoReturn) "<meta http-equiv=\"refresh\" content=\"0;url=aitts://oauth-done\">" else ""
        val html = "<html><head><meta charset=\"utf-8\">$refreshMeta<title>AI-TTS</title></head>" +
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

    private data class LoopbackSpec(val host: String, val port: Int, val path: String)

    private fun parseLoopbackSpec(raw: String): LoopbackSpec? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        if (!uri.scheme.equals("http", ignoreCase = true)) return null
        val host = uri.host ?: return null
        if (host != "127.0.0.1" && !host.equals("localhost", ignoreCase = true)) return null
        if (uri.port !in 1..65535) return null
        val path = uri.path.orEmpty()
        return LoopbackSpec(host, uri.port, path)
    }

    companion object {
        private const val AUTH_TIMEOUT_MS = 180_000
        private const val READ_TIMEOUT_MS = 3_000
    }
}
