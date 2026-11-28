package com.aitts.v2.reader

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.aitts.v2.data.SecretStore
import com.aitts.v2.engine.cloud.CloudFileNode
import com.aitts.v2.engine.cloud.DriveFileItem
import com.aitts.v2.engine.cloud.GoogleDriveAuthConfig
import com.aitts.v2.engine.cloud.GoogleDriveClient
import com.aitts.v2.engine.cloud.GoogleDriveWriteOps
import com.aitts.v2.reader.model.BookRecord
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Manages encrypted Google Drive credentials, One-Tap Google Play Services auth,
 * fallback developer OAuth/Token connection, and full file-manager operations.
 */
class GoogleDriveRepository(
    context: Context,
    private val secretStore: SecretStore,
    private val driveClient: GoogleDriveClient,
    private val bookImportManager: BookImportManager
) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val configLock = Any()
    private val writeOps = GoogleDriveWriteOps(driveClient)
    private val loopbackAuth = GoogleDriveLoopbackAuth(driveClient)

    private val _authFlow = MutableStateFlow(loadConfigFromStore())
    val authFlow: StateFlow<GoogleDriveAuthConfig> = _authFlow.asStateFlow()

    fun buildPlayServicesAuthRequest(): AuthorizationRequest =
        AuthorizationRequest.Builder()
            .setRequestedScopes(listOf(Scope(GoogleDriveClient.DRIVE_SCOPE)))
            .build()

    suspend fun handlePlayServicesToken(accessToken: String): Result<GoogleDriveAuthConfig> {
        val token = accessToken.trim()
        if (token.isEmpty()) return Result.failure(IOException("Google 授权未返回有效 Access Token"))
        val about = driveClient.fetchUserAbout(token).getOrElse { return Result.failure(it) }
        val cfg = GoogleDriveAuthConfig(
            accessToken = token,
            expiresAtEpochMs = System.currentTimeMillis() + 50 * 60_000L,
            accountName = about.first,
            accountEmail = about.second
        )
        return runCatching { saveConfig(cfg); cfg }
    }

    suspend fun authorizeInBrowser(
        clientId: String,
        clientSecret: String,
        onLaunchUrl: (String) -> Unit
    ): Result<GoogleDriveAuthConfig> =
        loopbackAuth.authorizeInBrowser(clientId, clientSecret, onLaunchUrl, ::saveConfig)

    fun cancelPendingAuth() = loopbackAuth.cancelPending()

    fun completeManualBrowserCallback(pastedUrl: String): Boolean = loopbackAuth.completeManuallyFromUrl(pastedUrl)

    suspend fun connectWithTokens(
        clientId: String,
        clientSecret: String,
        refreshToken: String,
        accessToken: String
    ): Result<GoogleDriveAuthConfig> = withContext(Dispatchers.IO) {
        val cleanAccess = accessToken.trim()
        val cleanRefresh = refreshToken.trim()
        val cleanClientId = clientId.trim()
        val cleanSecret = clientSecret.trim()
        val initial = GoogleDriveAuthConfig(cleanClientId, cleanSecret, cleanAccess, cleanRefresh)
        val verified = when {
            cleanRefresh.isNotBlank() && cleanClientId.isNotBlank() ->
                driveClient.refreshAccessToken(initial).getOrElse { return@withContext Result.failure(it) }
            cleanAccess.isNotBlank() -> {
                val about = driveClient.fetchUserAbout(cleanAccess).getOrElse { return@withContext Result.failure(it) }
                initial.copy(accountName = about.first, accountEmail = about.second)
            }
            else -> return@withContext Result.failure(IOException("请提供有效的 Token"))
        }
        runCatching { saveConfig(verified); verified }
    }

    fun disconnect() {
        loopbackAuth.cancelPending()
        synchronized(configLock) {
            secretStore.removeSecret(SECRET_KEY)
            _authFlow.value = GoogleDriveAuthConfig()
        }
    }

    suspend fun listNodes(folderId: String, searchQuery: String, ebooksOnly: Boolean): Result<List<CloudFileNode>> =
        withDriveRetry { cfg ->
            runCatching {
                val all = mutableListOf<CloudFileNode>()
                var token: String? = null
                var pages = 0
                do {
                    val page = driveClient.listFiles(
                        config = cfg,
                        folderId = folderId,
                        searchQuery = searchQuery,
                        ebooksOnly = ebooksOnly,
                        pageToken = token,
                        onTokenRefreshed = { saveConfig(it) }
                    ).getOrThrow()
                    all.addAll(page.items.map { it.toCloudNode() })
                    token = page.nextPageToken?.takeIf { it.isNotBlank() }
                    pages++
                } while (token != null && pages < 20)
                all
            }
        }

    suspend fun createFolder(parentId: String, name: String): Result<DriveFileItem> =
        withDriveRetry { cfg -> writeOps.createFolder(cfg, parentId, name) { saveConfig(it) } }

    suspend fun renameItem(fileId: String, newName: String): Result<DriveFileItem> =
        withDriveRetry { cfg -> writeOps.renameFile(cfg, fileId, newName) { saveConfig(it) } }

    suspend fun deleteItem(fileId: String): Result<Unit> =
        withDriveRetry { cfg -> writeOps.trashFile(cfg, fileId) { saveConfig(it) } }

    suspend fun uploadFile(
        parentId: String,
        file: File,
        existingId: String? = null,
        remoteName: String = file.name
    ): Result<DriveFileItem> = withDriveRetry { cfg ->
        writeOps.uploadFile(cfg, parentId, remoteName, mimeForName(remoteName), file, existingId) { saveConfig(it) }
    }

    suspend fun downloadToFile(fileId: String, destFile: File, onProgress: (Long, Long) -> Unit = { _, _ -> }): Result<File> =
        withDriveRetry { cfg -> driveClient.downloadFile(cfg, fileId, destFile, onProgress = onProgress) { saveConfig(it) } }

    private suspend fun <T> withDriveRetry(block: suspend (GoogleDriveAuthConfig) -> Result<T>): Result<T> {
        var cfg = _authFlow.value
        val isPlayServices = cfg.clientId.isBlank() && cfg.refreshToken.isBlank() && cfg.accessToken.isNotBlank()
        val expiringSoon = cfg.expiresAtEpochMs > 0L && System.currentTimeMillis() + 60_000L >= cfg.expiresAtEpochMs
        if (isPlayServices && expiringSoon) {
            cfg = refreshPlayServicesSilent(cfg).getOrElse { return Result.failure(it) }
        }
        val first = block(cfg)
        if (first.isSuccess || !isPlayServices) return first
        val errMsg = first.exceptionOrNull()?.message.orEmpty()
        val authErr = errMsg.contains("401") || errMsg.contains("Unauthorized", true) || errMsg.contains("登录")
        if (!authErr) return first
        val refreshed = refreshPlayServicesSilent(cfg).getOrElse { return Result.failure(it) }
        return block(refreshed)
    }

    private suspend fun refreshPlayServicesSilent(previous: GoogleDriveAuthConfig): Result<GoogleDriveAuthConfig> =
        withContext(Dispatchers.IO) {
            val res = runCatching {
                Tasks.await(Identity.getAuthorizationClient(appContext).authorize(buildPlayServicesAuthRequest()), 20L, TimeUnit.SECONDS)
            }.getOrNull()
            val nextToken = res?.takeIf { !it.hasResolution() }?.accessToken?.trim().orEmpty()
            if (nextToken.isBlank()) {
                disconnect()
                return@withContext Result.failure(IOException("Google 授权已过期，请重新点击「一键授权登录」"))
            }
            val updated = previous.copy(accessToken = nextToken, expiresAtEpochMs = System.currentTimeMillis() + 50 * 60_000L)
            runCatching { saveConfig(updated); updated }
        }

    suspend fun downloadAndImport(
        item: CloudFileNode,
        onProgress: (Float) -> Unit
    ): Result<BookRecord> = withContext(Dispatchers.IO) {
        val dlDir = File(appContext.cacheDir, "drive_dl").apply { mkdirs() }
        val tmpFile = File(dlDir, "${UUID.randomUUID().toString().take(8)}_${item.name.take(32).replace(Regex("[^a-zA-Z0-9._-]"), "_")}")
        try {
            downloadToFile(item.id, tmpFile) { done, total ->
                val knownTotal = if (total > 0L) total else item.sizeBytes
                if (knownTotal > 0L) onProgress((done.toFloat() / knownTotal).coerceIn(0f, 1f))
            }.getOrThrow()
            tmpFile.inputStream().use { stream ->
                bookImportManager.importFromStream(item.name, stream, sourceSyncKey = "gdrive:${item.id}").getOrThrow()
            }.let { Result.success(it) }
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            tmpFile.delete()
        }
    }

    @Suppress("DEPRECATION")
    fun getSigningSha1(): String = runCatching {
        val pm = appContext.packageManager
        val sig = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(appContext.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo.apkContentsSigners.firstOrNull()
        } else {
            pm.getPackageInfo(appContext.packageName, PackageManager.GET_SIGNATURES).signatures.firstOrNull()
        } ?: return@runCatching "未知"
        MessageDigest.getInstance("SHA-1").digest(sig.toByteArray()).joinToString(":") { "%02X".format(it) }
    }.getOrDefault("未知")

    private fun DriveFileItem.toCloudNode() = CloudFileNode(
        id = id,
        name = name,
        path = id,
        isFolder = isFolder,
        sizeBytes = sizeBytes,
        mimeType = mimeType
    )

    private fun mimeForName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "epub" -> "application/epub+zip"
        "json" -> "application/json"
        "md", "markdown" -> "text/markdown"
        "html", "htm" -> "text/html"
        else -> "text/plain"
    }

    private fun saveConfig(config: GoogleDriveAuthConfig) {
        synchronized(configLock) {
            val saved = secretStore.saveSecretString(SECRET_KEY, json.encodeToString(config))
            if (!saved) throw IOException("无法加密保存 Google 网盘凭据")
            _authFlow.value = config
        }
    }

    private fun loadConfigFromStore(): GoogleDriveAuthConfig {
        val raw = secretStore.getSecretString(SECRET_KEY) ?: return GoogleDriveAuthConfig()
        return runCatching { json.decodeFromString<GoogleDriveAuthConfig>(raw) }.getOrDefault(GoogleDriveAuthConfig())
    }

    companion object {
        private const val SECRET_KEY = "google_drive_auth_v1"
    }
}
