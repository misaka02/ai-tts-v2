package com.aitts.v2.engine.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException

/**
 * Write and file-management operations for Google Drive v3
 * (create folder, rename, trash, multipart upload, and media overwrite).
 */
class GoogleDriveWriteOps(private val client: GoogleDriveClient) {

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    suspend fun createFolder(
        config: GoogleDriveAuthConfig,
        parentId: String,
        folderName: String,
        onTokenRefreshed: (GoogleDriveAuthConfig) -> Unit = {}
    ): Result<DriveFileItem> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanName = folderName.trim().ifBlank { throw IOException("文件夹名称不能为空") }
            val safeParent = parentId.trim().ifEmpty { "root" }
            if (safeParent != "root" && !GoogleDriveClient.DRIVE_ID_RE.matches(safeParent)) {
                throw IOException("无效的父目录 ID")
            }
            val meta = JsonObject(
                mapOf(
                    "name" to JsonPrimitive(cleanName),
                    "mimeType" to JsonPrimitive(DriveFileItem.FOLDER_MIME_TYPE),
                    "parents" to JsonArray(listOf(JsonPrimitive(safeParent)))
                )
            ).toString()
            val url = "${client.apiBaseUrl.trimEnd('/')}/drive/v3/files" +
                "?fields=id,name,mimeType,size,modifiedTime&supportsAllDrives=true"
            client.executeAuthorized(
                initialConfig = config,
                url = url,
                onTokenRefreshed = onTokenRefreshed,
                requestBuilder = { b -> b.post(meta.toRequestBody(jsonMedia)) }
            ) { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IOException("创建文件夹失败 (${resp.code}): ${client.extractErrorMessage(body)}")
                parseSingleItem(body)
            }
        }
    }

    suspend fun renameFile(
        config: GoogleDriveAuthConfig,
        fileId: String,
        newName: String,
        onTokenRefreshed: (GoogleDriveAuthConfig) -> Unit = {}
    ): Result<DriveFileItem> = withContext(Dispatchers.IO) {
        runCatching {
            if (!GoogleDriveClient.DRIVE_ID_RE.matches(fileId)) throw IOException("无效的文件 ID")
            val cleanName = newName.trim().ifBlank { throw IOException("新名称不能为空") }
            val bodyJson = JsonObject(mapOf("name" to JsonPrimitive(cleanName))).toString()
            val url = "${client.apiBaseUrl.trimEnd('/')}/drive/v3/files/$fileId" +
                "?fields=id,name,mimeType,size,modifiedTime&supportsAllDrives=true"
            client.executeAuthorized(
                initialConfig = config,
                url = url,
                onTokenRefreshed = onTokenRefreshed,
                requestBuilder = { b -> b.patch(bodyJson.toRequestBody(jsonMedia)) }
            ) { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IOException("重命名失败 (${resp.code}): ${client.extractErrorMessage(body)}")
                parseSingleItem(body)
            }
        }
    }

    suspend fun trashFile(
        config: GoogleDriveAuthConfig,
        fileId: String,
        onTokenRefreshed: (GoogleDriveAuthConfig) -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!GoogleDriveClient.DRIVE_ID_RE.matches(fileId)) throw IOException("无效的文件 ID")
            val bodyJson = JsonObject(mapOf("trashed" to JsonPrimitive(true))).toString()
            val url = "${client.apiBaseUrl.trimEnd('/')}/drive/v3/files/$fileId?supportsAllDrives=true"
            client.executeAuthorized(
                initialConfig = config,
                url = url,
                onTokenRefreshed = onTokenRefreshed,
                requestBuilder = { b -> b.patch(bodyJson.toRequestBody(jsonMedia)) }
            ) { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IOException("删除失败 (${resp.code}): ${client.extractErrorMessage(body)}")
            }
        }
    }

    suspend fun uploadFile(
        config: GoogleDriveAuthConfig,
        parentId: String,
        fileName: String,
        mimeType: String,
        file: File,
        existingFileId: String? = null,
        onTokenRefreshed: (GoogleDriveAuthConfig) -> Unit = {}
    ): Result<DriveFileItem> = withContext(Dispatchers.IO) {
        runCatching {
            if (!file.exists()) throw IOException("本地文件不存在: ${file.name}")
            val media = mimeType.toMediaType()
            val safeParent = parentId.trim().ifEmpty { "root" }
            val url = if (!existingFileId.isNullOrBlank()) {
                if (!GoogleDriveClient.DRIVE_ID_RE.matches(existingFileId)) throw IOException("无效的文件 ID")
                "${client.apiBaseUrl.trimEnd('/')}/upload/drive/v3/files/$existingFileId" +
                    "?uploadType=media&fields=id,name,mimeType,size,modifiedTime&supportsAllDrives=true"
            } else {
                "${client.apiBaseUrl.trimEnd('/')}/upload/drive/v3/files" +
                    "?uploadType=multipart&fields=id,name,mimeType,size,modifiedTime&supportsAllDrives=true"
            }
            val reqBody = if (!existingFileId.isNullOrBlank()) {
                file.asRequestBody(media)
            } else {
                val meta = JsonObject(
                    mapOf(
                        "name" to JsonPrimitive(fileName),
                        "parents" to JsonArray(listOf(JsonPrimitive(safeParent)))
                    )
                ).toString()
                MultipartBody.Builder()
                    .setType("multipart/related".toMediaType())
                    .addPart(meta.toRequestBody(jsonMedia))
                    .addPart(file.asRequestBody(media))
                    .build()
            }
            client.executeAuthorized(
                initialConfig = config,
                url = url,
                onTokenRefreshed = onTokenRefreshed,
                requestBuilder = { b -> if (!existingFileId.isNullOrBlank()) b.patch(reqBody) else b.post(reqBody) }
            ) { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IOException("上传失败 (${resp.code}): ${client.extractErrorMessage(body)}")
                parseSingleItem(body)
            }
        }
    }

    private fun parseSingleItem(body: String): DriveFileItem {
        val obj = client.json.parseToJsonElement(body).jsonObject
        return DriveFileItem(
            id = obj["id"]?.jsonPrimitive?.content.orEmpty(),
            name = obj["name"]?.jsonPrimitive?.content.orEmpty(),
            mimeType = obj["mimeType"]?.jsonPrimitive?.content.orEmpty(),
            sizeBytes = obj["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            modifiedTime = obj["modifiedTime"]?.jsonPrimitive?.content.orEmpty()
        )
    }
}
