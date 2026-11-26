package com.aitts.v2.engine.logging

import com.aitts.v2.model.LogEntry
import com.aitts.v2.model.LogLevel
import com.aitts.v2.model.RequestLogSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Thread-safe bounded ring-buffer logger for synthesis diagnostics and session grouping.
 */
class EngineLogger(
    private val maxEntries: Int = 300,
    private val externalSink: ((String) -> Unit)? = null
) {
    private val lock = Any()
    private val buffer = ArrayDeque<LogEntry>(maxEntries)
    private val formatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    private val _entriesFlow = MutableStateFlow<List<LogEntry>>(emptyList())
    val entriesFlow: StateFlow<List<LogEntry>> = _entriesFlow.asStateFlow()

    fun log(
        level: LogLevel,
        tag: String,
        title: String,
        details: String? = null,
        sessionId: String? = null
    ) {
        val entry = LogEntry(
            timestamp = LocalTime.now().format(formatter),
            level = level,
            tag = tag,
            title = title,
            details = details,
            sessionId = sessionId?.takeIf { it.isNotBlank() }
        )
        synchronized(lock) {
            if (buffer.size >= maxEntries) {
                buffer.removeFirst()
            }
            buffer.addLast(entry)
            _entriesFlow.value = buffer.toList()
        }
        externalSink?.invoke(entry.formatLine())
    }

    fun info(tag: String, title: String, details: String? = null, sessionId: String? = null) =
        log(LogLevel.INFO, tag, title, details, sessionId)

    fun success(tag: String, title: String, details: String? = null, sessionId: String? = null) =
        log(LogLevel.SUCCESS, tag, title, details, sessionId)

    fun warn(tag: String, title: String, details: String? = null, sessionId: String? = null) =
        log(LogLevel.WARN, tag, title, details, sessionId)

    fun error(tag: String, title: String, details: String? = null, sessionId: String? = null) =
        log(LogLevel.ERROR, tag, title, details, sessionId)

    fun metric(tag: String, title: String, details: String? = null, sessionId: String? = null) =
        log(LogLevel.METRIC, tag, title, details, sessionId)

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _entriesFlow.value = emptyList()
        }
    }

    fun groupBySession(entries: List<LogEntry> = _entriesFlow.value): List<RequestLogSession> {
        if (entries.isEmpty()) return emptyList()
        val grouped = linkedMapOf<String, MutableList<LogEntry>>()
        for (entry in entries) {
            val key = entry.sessionId ?: "app"
            grouped.getOrPut(key) { mutableListOf() }.add(entry)
        }
        return grouped.entries.map { (sid, list) ->
            val first = list.first()
            val last = list.last()
            val hasError = list.any { it.level == LogLevel.ERROR }
            val isComplete = list.any { it.level == LogLevel.SUCCESS && (it.title.contains("完成") || it.title.contains("结束")) }
            val metric = list.lastOrNull { it.level == LogLevel.METRIC || !it.details.isNullOrBlank() }
            RequestLogSession(
                sessionId = sid,
                startTime = first.timestamp,
                endTime = last.timestamp,
                summaryTitle = first.title,
                subtitle = metric?.details ?: metric?.title,
                isComplete = isComplete,
                isError = hasError,
                entries = list
            )
        }.reversed()
    }
}
