package com.aitts.v2.reader

import android.content.Context
import android.content.SharedPreferences
import com.aitts.v2.engine.cloud.CloudFileNode
import com.aitts.v2.engine.cloud.SyncDirection
import com.aitts.v2.engine.cloud.SyncManifest
import com.aitts.v2.engine.cloud.SyncPlan
import com.aitts.v2.reader.model.BookRecord
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Shared support helpers for `LibrarySyncEngine`: temp file path sanitization,
 * sync manifest JSON persistence, Drive folder resolution, and reading-state sync.
 */
internal class LibrarySyncSupport(
    context: Context,
    private val prefs: SharedPreferences,
    private val bookLibrary: BookLibraryRepository,
    private val localSync: LocalSyncFolderManager,
    private val googleDrive: GoogleDriveRepository
) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val manifestDir = File(appContext.filesDir, "sync").apply { mkdirs() }
    private val syncTmpDir = File(appContext.cacheDir, "sync_tmp").apply { mkdirs() }

    fun safeTempFile(prefix: String, remoteName: String): File {
        val clean = remoteName.replace(Regex("[^a-zA-Z0-9._-]"), "_").trim('.').take(36).ifBlank { "item.bin" }
        val file = File(syncTmpDir, "${prefix}_${UUID.randomUUID().toString().take(8)}_$clean")
        check(file.canonicalPath.startsWith(syncTmpDir.canonicalPath)) { "Unsafe temp path" }
        return file
    }

    suspend fun mirrorBookshelfToDedicatedFolder() {
        val existing = localSync.listNodes("", ebooksOnly = true).getOrThrow().map { it.name.lowercase() }.toSet()
        for (record in bookLibrary.booksFlow.value) {
            if (record.sourceSyncKey.startsWith("local:")) continue
            val ext = record.fileName.substringAfterLast('.', "txt").lowercase()
            val safeTitle = record.title.trim().replace(Regex("[/\\\\:*?\"<>|]"), "_").take(56).ifBlank { record.id }
            val targetName = "${safeTitle}_${record.id}.$ext"
            if (targetName.lowercase() in existing) continue
            val src = bookLibrary.bookFile(record)
            if (src.exists()) localSync.writeTempFileIntoDedicatedRoot(targetName, src)
        }
    }

    suspend fun resolveDriveSyncTarget(defaultLabel: String): Pair<String, String> {
        val savedId = prefs.getString(KEY_DRIVE_ID, "").orEmpty().trim()
        if (savedId.isNotBlank()) return savedId to defaultLabel
        val rootItems = googleDrive.listNodes("root", DEFAULT_SYNC_FOLDER, ebooksOnly = false).getOrThrow()
        val existing = rootItems.firstOrNull { it.isFolder && it.name == DEFAULT_SYNC_FOLDER }
        val id = existing?.id ?: googleDrive.createFolder("root", DEFAULT_SYNC_FOLDER).getOrThrow().id
        return id to DEFAULT_SYNC_FOLDER
    }

    suspend fun syncStateFile(
        plan: SyncPlan,
        errors: MutableList<String>,
        remoteDownload: suspend (CloudFileNode, File) -> Result<Unit>,
        remoteUpload: suspend (String, File, String?) -> Result<Unit>
    ): Int {
        var merged = 0
        val stateNode = plan.stateNode
        if (stateNode != null && plan.direction != SyncDirection.PUSH) {
            val tmp = safeTempFile("state_in", STATE_FILE)
            val downloadRes = runCatching {
                remoteDownload(stateNode, tmp).getOrThrow()
                bookLibrary.mergeRemoteStates(json.decodeFromString<List<BookRecord>>(tmp.readText(Charsets.UTF_8)))
            }
            tmp.delete()
            downloadRes.fold(
                onSuccess = { merged = it },
                onFailure = { err ->
                    errors.add("读取云端阅读进度失败: ${err.message}")
                    return 0
                }
            )
        }
        if (plan.direction != SyncDirection.PULL) {
            val out = safeTempFile("state_out", STATE_FILE)
            runCatching {
                out.writeText(json.encodeToString(bookLibrary.booksFlow.value), Charsets.UTF_8)
                remoteUpload(STATE_FILE, out, stateNode?.id).getOrThrow()
            }.onFailure { err -> errors.add("上传阅读进度失败: ${err.message}") }
            out.delete()
        }
        return merged
    }

    fun loadManifest(name: String): SyncManifest {
        val file = File(manifestDir, name)
        if (!file.exists()) return SyncManifest()
        return runCatching { json.decodeFromString<SyncManifest>(file.readText(Charsets.UTF_8)) }.getOrDefault(SyncManifest())
    }

    fun saveManifest(name: String, manifest: SyncManifest) {
        val file = File(manifestDir, name)
        file.writeText(json.encodeToString(manifest), Charsets.UTF_8)
    }

    companion object {
        const val DEFAULT_SYNC_FOLDER = "AI-TTS-Library"
        const val STATE_FILE = "aitts_library_state.json"
        const val KEY_DRIVE_ID = "drive_sync_folder_id"
        const val KEY_DRIVE_LABEL = "drive_sync_folder_label"
        const val KEY_WEBDAV_PATH = "webdav_sync_folder_path"

        fun formatWebDavLabel(raw: String?): String = when (val clean = raw?.trim().orEmpty()) {
            "/" -> "WebDAV 根目录"
            "" -> DEFAULT_SYNC_FOLDER
            else -> clean.trim('/')
        }
    }
}
