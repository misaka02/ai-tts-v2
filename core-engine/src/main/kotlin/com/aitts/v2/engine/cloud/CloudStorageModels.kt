package com.aitts.v2.engine.cloud

import kotlinx.serialization.Serializable

@Serializable
data class CloudFileNode(
    val id: String,
    val name: String,
    val path: String,
    val isFolder: Boolean,
    val sizeBytes: Long = 0L,
    val modifiedTimeMs: Long = 0L,
    val mimeType: String = ""
) {
    val isSupportedEbook: Boolean
        get() {
            if (isFolder) return false
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext in EBOOK_EXTENSIONS || mimeType in EBOOK_MIMES
        }

    companion object {
        private val EBOOK_EXTENSIONS = setOf("epub", "txt", "text", "md", "markdown", "html", "htm", "xhtml")
        private val EBOOK_MIMES = setOf(
            "application/epub+zip",
            "text/plain",
            "text/markdown",
            "text/html",
            "application/xhtml+xml"
        )
    }
}

@Serializable
data class WebDavConfig(
    val baseUrl: String = "",
    val username: String = "",
    val password: String = "",
    val rootPath: String = "/"
) {
    val isConnected: Boolean
        get() = baseUrl.trim().startsWith("http", ignoreCase = true)
}

enum class SyncDirection(val label: String) {
    PULL("从云端拉取到本地"),
    PUSH("从本地推送到云端"),
    TWO_WAY("双向智能同步")
}

data class SyncProgress(
    val stage: String,
    val current: Int = 0,
    val total: Int = 0
)

data class SyncSummary(
    val pulledBooks: Int = 0,
    val pushedBooks: Int = 0,
    val importedToShelf: Int = 0,
    val mergedStates: Int = 0,
    val skipped: Int = 0
)
