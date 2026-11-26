package com.aitts.v2.engine.network

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.ResponseBody
import java.io.BufferedReader

/**
 * Reusable Server-Sent Events (SSE) payload reader with cooperative coroutine cancellation.
 */
object SseReader {

    suspend fun readDataEvents(
        body: ResponseBody,
        onEvent: suspend (String) -> Unit
    ): Boolean {
        var sawSseFrame = false
        body.byteStream().bufferedReader(Charsets.UTF_8).use { reader: BufferedReader ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val line = reader.readLine() ?: break
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith(":")) continue
                if (trimmed == "data: [DONE]" || trimmed == "[DONE]") {
                    sawSseFrame = true
                    break
                }
                if (trimmed.startsWith("data:")) {
                    sawSseFrame = true
                    val payload = trimmed.substring(5).trim()
                    if (payload.isNotBlank() && payload != "[DONE]") {
                        onEvent(payload)
                    }
                }
            }
        }
        return sawSseFrame
    }
}
