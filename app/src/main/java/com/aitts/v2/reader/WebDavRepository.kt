package com.aitts.v2.reader

import android.content.Context
import com.aitts.v2.data.SecretStore
import com.aitts.v2.engine.cloud.CloudFileNode
import com.aitts.v2.engine.cloud.WebDavClient
import com.aitts.v2.engine.cloud.WebDavConfig
import com.aitts.v2.reader.model.BookRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Encrypted WebDAV credentials + file manager operations (Alist / Nextcloud / NAS).
 */
class WebDavRepository(
    context: Context,
    private val secretStore: SecretStore,
    private val client: WebDavClient,
    private val bookImportManager: BookImportManager
) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()

    private val _configFlow = MutableStateFlow(loadConfig())
    val configFlow: StateFlow<WebDavConfig> = _configFlow.asStateFlow()

    suspend fun saveAndConnect(config: WebDavConfig): Result<WebDavConfig> {
        val clean = config.copy(
            baseUrl = config.baseUrl.trim(),
            username = config.username.trim(),
            rootPath = config.rootPath.trim().ifBlank { "/" }
        )
        if (!clean.isConnected) return Result.failure(IOException("请输入完整的 WebDAV 地址 (http/https)"))
        client.testConnection(clean).getOrElse { return Result.failure(it) }
        synchronized(lock) {
            if (!secretStore.saveSecretString(SECRET_KEY, json.encodeToString(clean))) {
                return Result.failure(IOException("无法加密保存 WebDAV 凭据"))
            }
            _configFlow.value = clean
        }
        return Result.success(clean)
    }

    fun disconnect() {
        synchronized(lock) {
            secretStore.removeSecret(SECRET_KEY)
            _configFlow.value = WebDavConfig()
        }
    }

    suspend fun listFiles(relativePath: String, ebooksOnly: Boolean): Result<List<CloudFileNode>> =
        client.listFiles(_configFlow.value, relativePath, ebooksOnly)

    suspend fun createFolder(parentPath: String, name: String): Result<Unit> =
        client.createFolder(_configFlow.value, parentPath, name)

    suspend fun renameItem(itemPath: String, newName: String): Result<Unit> =
        client.renameItem(_configFlow.value, itemPath, newName)

    suspend fun deleteItem(itemPath: String): Result<Unit> =
        client.deleteItem(_configFlow.value, itemPath)

    suspend fun uploadFile(targetPath: String, file: File): Result<Unit> =
        client.uploadFile(_configFlow.value, targetPath, file)

    suspend fun downloadToFile(itemPath: String, destFile: File): Result<File> =
        client.downloadFile(_configFlow.value, itemPath, destFile)

    suspend fun uploadFromUri(parentPath: String, uri: android.net.Uri): Result<Unit> = withContext(Dispatchers.IO) {
        val rawName = resolveUriDisplayName(appContext, uri)
        val cleanName = rawName.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim('.').ifBlank { "upload.epub" }
        val upDir = File(appContext.cacheDir, "webdav_up").apply { mkdirs() }
        val safeTmp = cleanName.take(32).replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "upload.bin" }
        val tmp = File(upDir, "${UUID.randomUUID().toString().take(8)}_$safeTmp")
        try {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { out -> input.copyTo(out) }
            } ?: return@withContext Result.failure(IOException("无法读取待上传文件"))
            val cleanParent = parentPath.trim('/')
            val remotePath = if (cleanParent.isEmpty()) cleanName else "$cleanParent/$cleanName"
            uploadFile(remotePath, tmp)
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            tmp.delete()
        }
    }

    suspend fun downloadToLocalSyncFolder(node: CloudFileNode, localSync: LocalSyncFolderManager): Result<Unit> =
        withContext(Dispatchers.IO) {
            val dlDir = File(appContext.cacheDir, "webdav_dl").apply { mkdirs() }
            val safeTmp = node.name.take(32).replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "dl.bin" }
            val tmp = File(dlDir, "${UUID.randomUUID().toString().take(8)}_$safeTmp")
            try {
                downloadToFile(node.path, tmp).getOrThrow()
                localSync.writeTempFileIntoDedicatedRoot(node.name, tmp)
                Result.success(Unit)
            } catch (t: Throwable) {
                Result.failure(t)
            } finally {
                tmp.delete()
            }
        }

    suspend fun downloadAndImport(
        item: CloudFileNode,
        onProgress: (Float) -> Unit
    ): Result<BookRecord> = withContext(Dispatchers.IO) {
        val dlDir = File(appContext.cacheDir, "webdav_dl").apply { mkdirs() }
        val tmpFile = File(dlDir, "${UUID.randomUUID().toString().take(8)}_${item.name.take(32)}")
        try {
            client.downloadFile(_configFlow.value, item.path, tmpFile) { done, total ->
                val known = if (total > 0L) total else item.sizeBytes
                if (known > 0L) onProgress((done.toFloat() / known).coerceIn(0f, 1f))
            }.getOrThrow()
            tmpFile.inputStream().use { stream ->
                val syncKey = "webdav:${_configFlow.value.baseUrl.trimEnd('/')}/${item.path.trimStart('/')}"
                bookImportManager.importFromStream(item.name, stream, sourceSyncKey = syncKey).getOrThrow()
            }.let { Result.success(it) }
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            tmpFile.delete()
        }
    }

    private fun loadConfig(): WebDavConfig {
        val raw = secretStore.getSecretString(SECRET_KEY) ?: return WebDavConfig()
        return runCatching { json.decodeFromString<WebDavConfig>(raw) }.getOrDefault(WebDavConfig())
    }

    companion object {
        private const val SECRET_KEY = "webdav_config_v1"
    }
}
