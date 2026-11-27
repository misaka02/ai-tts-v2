package com.aitts.v2.engine.cloud

import kotlinx.serialization.Serializable

@Serializable
data class GoogleDriveAuthConfig(
    val clientId: String = "",
    val clientSecret: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val expiresAtEpochMs: Long = 0L,
    val accountEmail: String = "",
    val accountName: String = ""
) {
    val isConnected: Boolean
        get() = accessToken.isNotBlank() || (refreshToken.isNotBlank() && clientId.isNotBlank())
}

@Serializable
data class DriveFileItem(
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long = 0L,
    val modifiedTime: String = ""
) {
    val isFolder: Boolean
        get() = mimeType == FOLDER_MIME_TYPE

    val isSupportedEbook: Boolean
        get() {
            if (isFolder) return false
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext in EBOOK_EXTENSIONS || mimeType in EBOOK_MIMES
        }

    companion object {
        const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
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

data class DriveFilePage(
    val items: List<DriveFileItem>,
    val nextPageToken: String? = null
)

data class PkcePair(
    val codeVerifier: String,
    val codeChallenge: String,
    val state: String
)
