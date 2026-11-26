package com.aitts.v2.engine.network

import com.aitts.v2.model.GlobalSettings
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class NetworkConfig(
    val proxyEnabled: Boolean = false,
    val proxyType: String = "HTTP",
    val proxyHost: String = "127.0.0.1",
    val proxyPort: Int = 7890,
    val connectTimeoutSeconds: Int = 15,
    val readTimeoutSeconds: Int = 60
) {
    companion object {
        fun fromSettings(settings: GlobalSettings) = NetworkConfig(
            proxyEnabled = settings.proxyEnabled,
            proxyType = settings.proxyType.uppercase(),
            proxyHost = settings.proxyHost.trim(),
            proxyPort = settings.proxyPort,
            connectTimeoutSeconds = settings.connectTimeoutSeconds.coerceIn(3, 60),
            readTimeoutSeconds = settings.readTimeoutSeconds.coerceIn(10, 180)
        )
    }
}

/**
 * Shared OkHttp manager that only rebuilds the client when [NetworkConfig] actually changes
 * and tracks in-flight HTTP calls and WebSockets per sessionId for exact cancellation.
 */
class HttpClientManager(initialConfig: NetworkConfig = NetworkConfig()) {

    private val connectionPool = ConnectionPool(16, 5, TimeUnit.MINUTES)
    private val configLock = Any()
    private val sessionCalls = ConcurrentHashMap<String, MutableSet<Call>>()
    private val sessionSockets = ConcurrentHashMap<String, MutableSet<WebSocket>>()

    @Volatile
    private var currentConfig: NetworkConfig = initialConfig

    @Volatile
    var client: OkHttpClient = buildClient(initialConfig)
        private set

    fun updateIfChanged(config: NetworkConfig): Boolean {
        if (config == currentConfig) return false
        synchronized(configLock) {
            if (config == currentConfig) return false
            currentConfig = config
            client = buildClient(config)
            return true
        }
    }

    fun registerCall(sessionId: String, call: Call) {
        if (sessionId.isBlank()) return
        sessionCalls.computeIfAbsent(sessionId) {
            Collections.newSetFromMap(ConcurrentHashMap())
        }.add(call)
    }

    fun unregisterCall(sessionId: String, call: Call) {
        if (sessionId.isBlank()) return
        sessionCalls[sessionId]?.remove(call)
        if (sessionCalls[sessionId]?.isEmpty() == true) {
            sessionCalls.remove(sessionId)
        }
    }

    fun registerWebSocket(sessionId: String, ws: WebSocket) {
        if (sessionId.isBlank()) return
        sessionSockets.computeIfAbsent(sessionId) {
            Collections.newSetFromMap(ConcurrentHashMap())
        }.add(ws)
    }

    fun unregisterWebSocket(sessionId: String, ws: WebSocket) {
        if (sessionId.isBlank()) return
        sessionSockets[sessionId]?.remove(ws)
        if (sessionSockets[sessionId]?.isEmpty() == true) {
            sessionSockets.remove(sessionId)
        }
    }

    fun cancelSession(sessionId: String) {
        if (sessionId.isBlank()) return
        sessionCalls.remove(sessionId)?.forEach { call ->
            runCatching { call.cancel() }
        }
        sessionSockets.remove(sessionId)?.forEach { ws ->
            runCatching { ws.cancel() }
        }
    }

    private fun buildClient(config: NetworkConfig): OkHttpClient {
        val dispatcher = Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 16
        }
        val builder = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(connectionPool)
            .connectTimeout(config.connectTimeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(config.readTimeoutSeconds.toLong(), TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

        if (config.proxyEnabled && config.proxyHost.isNotBlank() && config.proxyPort in 1..65535) {
            val type = if (config.proxyType.equals("SOCKS", ignoreCase = true)) {
                Proxy.Type.SOCKS
            } else {
                Proxy.Type.HTTP
            }
            runCatching {
                builder.proxy(Proxy(type, InetSocketAddress(config.proxyHost, config.proxyPort)))
            }
        }
        return builder.build()
    }
}
