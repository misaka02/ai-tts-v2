package com.aitts.v2.reader

import android.content.Context
import com.aitts.v2.engine.cloud.SyncDirection
import com.aitts.v2.engine.cloud.SyncProgress
import com.aitts.v2.engine.cloud.SyncSummary
import com.aitts.v2.reader.model.BookRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * Bidirectional sync engine between:
 * 1) Dedicated Local Sync Folder <-> Bookshelf
 * 2) Dedicated Local Sync Folder <-> Cloud Storage (Google Drive / WebDAV) + reading state JSON
 */
class LibrarySyncEngine(
    context: Context,
    private val bookLibrary: BookLibraryRepository,
    private val localSync: LocalSyncFolderManager,
    private val googleDrive: GoogleDriveRepository,
    private val webDav: WebDavRepository
) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun syncLocalFolderOnly(): Result<SyncSummary> = runCatching {
        val imported = localSync.syncRootToBookshelf()
        SyncSummary(importedToShelf = imported)
    }

    suspend fun syncWithGoogleDrive(
        direction: SyncDirection,
        onProgress: (SyncProgress) -> Unit = {}
    ): Result<SyncSummary> = withContext(Dispatchers.IO) {
        runCatching {
            onProgress(SyncProgress("检查 Google Drive 同步目录..."))
            val syncFolderId = ensureDriveSyncFolder()
            val remoteItems = googleDrive.listNodes(syncFolderId, "", ebooksOnly = false).getOrThrow()
            val remoteBooks = remoteItems.filter { !it.isFolder && it.isSupportedEbook }
            val errors = mutableListOf<String>()

            if (direction != SyncDirection.PULL) mirrorBookshelfToDedicatedFolder()
            val localNodes = localSync.listNodes("", ebooksOnly = true).getOrThrow().filter { !it.isFolder && it.isSupportedEbook }
            val localNames = localNodes.map { it.name.lowercase() }.toSet()
            val remoteNames = remoteBooks.map { it.name.lowercase() }.toSet()

            var pulled = 0
            var pushed = 0
            if (direction != SyncDirection.PUSH) {
                for ((idx, r) in remoteBooks.withIndex()) {
                    if (r.name.lowercase() in localNames) continue
                    onProgress(SyncProgress("拉取 ${r.name}", idx + 1, remoteBooks.size))
                    val tmp = File(appContext.cacheDir, "sync_${r.name}")
                    try {
                        googleDrive.downloadToFile(r.id, tmp).getOrThrow()
                        localSync.writeTempFileIntoDedicatedRoot(r.name, tmp)
                        pulled++
                    } catch (t: Throwable) {
                        errors.add("拉取 ${r.name}: ${t.message}")
                    } finally {
                        tmp.delete()
                    }
                }
            }
            if (direction != SyncDirection.PULL) {
                for ((idx, l) in localNodes.withIndex()) {
                    if (l.name.lowercase() in remoteNames) continue
                    onProgress(SyncProgress("推送 ${l.name}", idx + 1, localNodes.size))
                    val tmp = File(appContext.cacheDir, "up_${l.name}")
                    try {
                        localSync.copyNodeToTempFile(l, tmp)
                        googleDrive.uploadFile(syncFolderId, tmp, remoteName = l.name).getOrThrow()
                        pushed++
                    } catch (t: Throwable) {
                        errors.add("推送 ${l.name}: ${t.message}")
                    } finally {
                        tmp.delete()
                    }
                }
            }

            onProgress(SyncProgress("导入新书并同步阅读进度..."))
            val imported = localSync.syncRootToBookshelf()
            var merged = 0
            val stateItem = remoteItems.firstOrNull { !it.isFolder && it.name == STATE_FILE }
            if (stateItem != null && direction != SyncDirection.PUSH) {
                val stateTmp = File(appContext.cacheDir, STATE_FILE)
                try {
                    googleDrive.downloadToFile(stateItem.id, stateTmp).getOrThrow()
                    val remoteRecords = json.decodeFromString<List<BookRecord>>(stateTmp.readText(Charsets.UTF_8))
                    merged = bookLibrary.mergeRemoteStates(remoteRecords)
                } finally {
                    stateTmp.delete()
                }
            }
            if (direction != SyncDirection.PULL) {
                val outState = File(appContext.cacheDir, STATE_FILE)
                try {
                    outState.writeText(json.encodeToString(bookLibrary.booksFlow.value), Charsets.UTF_8)
                    googleDrive.uploadFile(syncFolderId, outState, existingId = stateItem?.id, remoteName = STATE_FILE).getOrThrow()
                } finally {
                    outState.delete()
                }
            }
            if (errors.isNotEmpty()) throw IOException("同步部分失败: ${errors.take(3).joinToString("；")}")
            SyncSummary(pulled, pushed, imported, merged)
        }
    }

    suspend fun syncWithWebDav(
        direction: SyncDirection,
        onProgress: (SyncProgress) -> Unit = {}
    ): Result<SyncSummary> = withContext(Dispatchers.IO) {
        runCatching {
            onProgress(SyncProgress("检查 WebDAV 同步目录..."))
            webDav.createFolder("", SYNC_FOLDER_NAME).getOrThrow()
            val remoteItems = webDav.listFiles(SYNC_FOLDER_NAME, ebooksOnly = false).getOrThrow()
            val remoteBooks = remoteItems.filter { !it.isFolder && it.isSupportedEbook }
            val errors = mutableListOf<String>()

            if (direction != SyncDirection.PULL) mirrorBookshelfToDedicatedFolder()
            val localNodes = localSync.listNodes("", ebooksOnly = true).getOrThrow().filter { !it.isFolder && it.isSupportedEbook }
            val localNames = localNodes.map { it.name.lowercase() }.toSet()
            val remoteNames = remoteBooks.map { it.name.lowercase() }.toSet()

            var pulled = 0
            var pushed = 0
            if (direction != SyncDirection.PUSH) {
                for ((idx, r) in remoteBooks.withIndex()) {
                    if (r.name.lowercase() in localNames) continue
                    onProgress(SyncProgress("拉取 ${r.name}", idx + 1, remoteBooks.size))
                    val tmp = File(appContext.cacheDir, "dav_sync_${r.name}")
                    try {
                        webDav.downloadToFile(r.path, tmp).getOrThrow()
                        localSync.writeTempFileIntoDedicatedRoot(r.name, tmp)
                        pulled++
                    } catch (t: Throwable) {
                        errors.add("拉取 ${r.name}: ${t.message}")
                    } finally {
                        tmp.delete()
                    }
                }
            }
            if (direction != SyncDirection.PULL) {
                for ((idx, l) in localNodes.withIndex()) {
                    if (l.name.lowercase() in remoteNames) continue
                    onProgress(SyncProgress("推送 ${l.name}", idx + 1, localNodes.size))
                    val tmp = File(appContext.cacheDir, "dav_up_${l.name}")
                    try {
                        localSync.copyNodeToTempFile(l, tmp)
                        webDav.uploadFile("$SYNC_FOLDER_NAME/${l.name}", tmp).getOrThrow()
                        pushed++
                    } catch (t: Throwable) {
                        errors.add("推送 ${l.name}: ${t.message}")
                    } finally {
                        tmp.delete()
                    }
                }
            }

            onProgress(SyncProgress("导入新书并同步阅读进度..."))
            val imported = localSync.syncRootToBookshelf()
            var merged = 0
            val stateItem = remoteItems.firstOrNull { !it.isFolder && it.name == STATE_FILE }
            if (stateItem != null && direction != SyncDirection.PUSH) {
                val stateTmp = File(appContext.cacheDir, STATE_FILE)
                try {
                    webDav.downloadToFile(stateItem.path, stateTmp).getOrThrow()
                    val remoteRecords = json.decodeFromString<List<BookRecord>>(stateTmp.readText(Charsets.UTF_8))
                    merged = bookLibrary.mergeRemoteStates(remoteRecords)
                } finally {
                    stateTmp.delete()
                }
            }
            if (direction != SyncDirection.PULL) {
                val outState = File(appContext.cacheDir, STATE_FILE)
                try {
                    outState.writeText(json.encodeToString(bookLibrary.booksFlow.value), Charsets.UTF_8)
                    webDav.uploadFile("$SYNC_FOLDER_NAME/$STATE_FILE", outState).getOrThrow()
                } finally {
                    outState.delete()
                }
            }
            if (errors.isNotEmpty()) throw IOException("同步部分失败: ${errors.take(3).joinToString("；")}")
            SyncSummary(pulled, pushed, imported, merged)
        }
    }

    private suspend fun mirrorBookshelfToDedicatedFolder() {
        val existing = localSync.listNodes("", ebooksOnly = true).getOrThrow().map { it.name.lowercase() }.toSet()
        for (record in bookLibrary.booksFlow.value) {
            if (record.sourceSyncKey.startsWith("local:")) continue
            val ext = record.fileName.substringAfterLast('.', "txt").lowercase()
            val safeTitle = record.title.trim().replace(Regex("[/\\\\:*?\"<>|]"), "_").take(56).ifBlank { record.id }
            val targetName = "${safeTitle}_${record.id}.$ext"
            if (targetName.lowercase() in existing) continue
            val src = bookLibrary.bookFile(record)
            if (src.exists()) {
                localSync.writeTempFileIntoDedicatedRoot(targetName, src)
            }
        }
    }

    private suspend fun ensureDriveSyncFolder(): String {
        val rootItems = googleDrive.listNodes("root", SYNC_FOLDER_NAME, ebooksOnly = false).getOrThrow()
        val existing = rootItems.firstOrNull { it.isFolder && it.name == SYNC_FOLDER_NAME }
        if (existing != null) return existing.id
        return googleDrive.createFolder("root", SYNC_FOLDER_NAME).getOrThrow().id
    }

    companion object {
        private const val SYNC_FOLDER_NAME = "AI-TTS-Library"
        private const val STATE_FILE = "aitts_library_state.json"
    }
}
