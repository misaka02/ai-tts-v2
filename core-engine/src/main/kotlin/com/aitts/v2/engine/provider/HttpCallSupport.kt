package com.aitts.v2.engine.provider

import com.aitts.v2.engine.network.HttpClientManager
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response

/**
 * Shared OkHttp tracked call execution helper ensuring every Response is closed with `.use`
 * and registered against [sessionId] for instant cancellation.
 */
object HttpCallSupport {

    val json = Json { ignoreUnknownKeys = true }

    inline fun <T> executeTracked(
        httpManager: HttpClientManager,
        sessionId: String,
        request: Request,
        block: (Response) -> T
    ): T {
        val call = httpManager.client.newCall(request)
        httpManager.registerCall(sessionId, call)
        return try {
            call.execute().use { response ->
                block(response)
            }
        } finally {
            httpManager.unregisterCall(sessionId, call)
        }
    }
}
