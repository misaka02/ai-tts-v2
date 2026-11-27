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
