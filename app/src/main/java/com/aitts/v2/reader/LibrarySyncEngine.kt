package com.aitts.v2.reader

import android.content.Context
import com.aitts.v2.engine.cloud.CloudFileNode
import com.aitts.v2.engine.cloud.SyncDeleteCandidate
import com.aitts.v2.engine.cloud.SyncDirection
import com.aitts.v2.engine.cloud.SyncManifest
import com.aitts.v2.engine.cloud.SyncPlan
import com.aitts.v2.engine.cloud.SyncPlanBuilder
import com.aitts.v2.engine.cloud.SyncProgress
import com.aitts.v2.engine.cloud.SyncSummary
import com.aitts.v2.reader.LibrarySyncSupport.Companion.DEFAULT_SYNC_FOLDER
import com.aitts.v2.reader.LibrarySyncSupport.Companion.KEY_DRIVE_ID
import com.aitts.v2.reader.LibrarySyncSupport.Companion.KEY_DRIVE_LABEL
import com.aitts.v2.reader.LibrarySyncSupport.Companion.KEY_WEBDAV_PATH
import com.aitts.v2.reader.LibrarySyncSupport.Companion.STATE_FILE
import com.aitts.v2.reader.LibrarySyncSupport.Companion.formatWebDavLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Two-phase bidirectional sync engine with configurable cloud sync folders and strict manual
 * deletion confirmation (no local or remote delete ever executes without explicit ID approval).
 */
class LibrarySyncEngine(
    context: Context,
    bookLibrary: BookLibraryRepository,
    private val localSync: LocalSyncFolderManager,
    private val googleDrive: GoogleDriveRepository,
    private val webDav: WebDavRepository
) {
    private val prefs = context.applicationContext.getSharedPreferences("cloud_sync_prefs", Context.MODE_PRIVATE)
    private val support = LibrarySyncSupport(context, prefs, bookLibrary, localSync, googleDrive)

    private val _driveFolderLabelFlow = MutableStateFlow(
        prefs.getString(KEY_DRIVE_LABEL, "")?.ifBlank { DEFAULT_SYNC_FOLDER } ?: DEFAULT_SYNC_FOLDER
    )
    val driveSyncFolderLabelFlow: StateFlow<String> = _driveFolderLabelFlow.asStateFlow()

    private val _webDavFolderLabelFlow = MutableStateFlow(formatWebDavLabel(prefs.getString(KEY_WEBDAV_PATH, "")))
    val webDavSyncFolderLabelFlow: StateFlow<String> = _webDavFolderLabelFlow.asStateFlow()

    fun setDriveSyncFolder(folderId: String, label: String) {
        val cleanLabel = label.trim().ifBlank { DEFAULT_SYNC_FOLDER }
        prefs.edit().putString(KEY_DRIVE_ID, folderId.trim()).putString(KEY_DRIVE_LABEL, cleanLabel).apply()
        _driveFolderLabelFlow.value = cleanLabel
    }

    fun setWebDavSyncFolder(path: String) {
        val raw = path.trim()
        val stored = if (raw == "/") "/" else raw.trim('/').ifBlank { DEFAULT_SYNC_FOLDER }
        prefs.edit().putString(KEY_WEBDAV_PATH, stored).apply()
        _webDavFolderLabelFlow.value = formatWebDavLabel(stored)
    }

    suspend fun syncLocalFolderOnly(): Result<SyncSummary> = runCatching {
        SyncSummary(importedToShelf = localSync.syncRootToBookshelf())
    }

    suspend fun prepareGoogleDriveSync(direction: SyncDirection): Result<SyncPlan> = withContext(Dispatchers.IO) {
        runCatching {
            val (folderId, folderLabel) = support.resolveDriveSyncTarget(_driveFolderLabelFlow.value.ifBlank { DEFAULT_SYNC_FOLDER })
            val remoteItems = googleDrive.listNodes(folderId, "", ebooksOnly = false).getOrThrow()
            buildPlan("Google Drive", folderId, folderLabel, direction, remoteItems, DRIVE_MANIFEST)
        }
    }

    suspend fun prepareWebDavSync(direction: SyncDirection): Result<SyncPlan> = withContext(Dispatchers.IO) {
        runCatching {
            val stored = prefs.getString(KEY_WEBDAV_PATH, "")?.trim().orEmpty().ifBlank { DEFAULT_SYNC_FOLDER }
            val listPath = if (stored == "/") "" else stored.trim('/')
            if (listPath.isNotEmpty()) webDav.createFolder("", listPath).getOrThrow()
            val remoteItems = webDav.listFiles(listPath, ebooksOnly = false).getOrThrow()
            buildPlan("WebDAV", listPath, formatWebDavLabel(stored), direction, remoteItems, WEBDAV_MANIFEST)
        }
    }

    suspend fun executeGoogleDriveSync(
        plan: SyncPlan,
        approvedLocalDeleteIds: Set<String>,
        approvedRemoteDeleteIds: Set<String>,
        onProgress: (SyncProgress) -> Unit = {}
    ): Result<SyncSummary> = executePlan(
        plan = plan,
        manifestFile = DRIVE_MANIFEST,
        approvedLocalIds = approvedLocalDeleteIds,
        approvedRemoteIds = approvedRemoteDeleteIds,
        onProgress = onProgress,
        remoteDelete = { cand -> googleDrive.deleteItem(cand.id) },
        remoteDownload = { node, tmp -> googleDrive.downloadToFile(node.id, tmp).map {} },
        remoteUpload = { name, file, existingId ->
            googleDrive.uploadFile(plan.remoteFolderIdOrPath, file, existingId = existingId, remoteName = name).map {}
        },
        remoteListAfter = { googleDrive.listNodes(plan.remoteFolderIdOrPath, "", ebooksOnly = true) }
    )

    suspend fun executeWebDavSync(
        plan: SyncPlan,
        approvedLocalDeleteIds: Set<String>,
        approvedRemoteDeleteIds: Set<String>,
        onProgress: (SyncProgress) -> Unit = {}
    ): Result<SyncSummary> = executePlan(
        plan = plan,
        manifestFile = WEBDAV_MANIFEST,
        approvedLocalIds = approvedLocalDeleteIds,
        approvedRemoteIds = approvedRemoteDeleteIds,
        onProgress = onProgress,
        remoteDelete = { cand -> webDav.deleteItem(cand.path) },
        remoteDownload = { node, tmp -> webDav.downloadToFile(node.path, tmp).map {} },
        remoteUpload = { name, file, _ ->
            val target = if (plan.remoteFolderIdOrPath.isEmpty()) name else "${plan.remoteFolderIdOrPath}/$name"
            webDav.uploadFile(target, file)
        },
        remoteListAfter = { webDav.listFiles(plan.remoteFolderIdOrPath, ebooksOnly = true) }
    )

    private suspend fun buildPlan(
        providerLabel: String,
        folderIdOrPath: String,
        folderLabel: String,
        direction: SyncDirection,
        remoteItems: List<CloudFileNode>,
        manifestFile: String
    ): SyncPlan {
        val remoteBooks = remoteItems.filter { !it.isFolder && it.isSupportedEbook }
        if (direction != SyncDirection.PULL) support.mirrorBookshelfToDedicatedFolder()
        val localNodes = listLocalEbooks()
        val manifest = support.loadManifest(manifestFile)
        val stateNode = remoteItems.firstOrNull { !it.isFolder && it.name == STATE_FILE }
        return SyncPlanBuilder.build(
            providerLabel = providerLabel,
            remoteFolderIdOrPath = folderIdOrPath,
            remoteFolderLabel = folderLabel,
            direction = direction,
            localFiles = localNodes,
            remoteFiles = remoteBooks,
            previousSyncedNames = manifest.syncedFileNames,
            stateNode = stateNode
        )
    }

    private suspend fun executePlan(
        plan: SyncPlan,
        manifestFile: String,
        approvedLocalIds: Set<String>,
        approvedRemoteIds: Set<String>,
        onProgress: (SyncProgress) -> Unit,
        remoteDelete: suspend (SyncDeleteCandidate) -> Result<Unit>,
        remoteDownload: suspend (CloudFileNode, File) -> Result<Unit>,
        remoteUpload: suspend (String, File, String?) -> Result<Unit>,
        remoteListAfter: suspend () -> Result<List<CloudFileNode>>
    ): Result<SyncSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val errors = mutableListOf<String>()
            val deletedLocal = deleteApprovedLocal(plan, approvedLocalIds, errors, onProgress)
            val deletedRemote = deleteApprovedRemote(plan, approvedRemoteIds, errors, onProgress, remoteDelete)
            val pulled = transferPull(plan, errors, onProgress, remoteDownload)
            val pushed = transferPush(plan, errors, onProgress, remoteUpload)

            onProgress(SyncProgress("导入新书并同步阅读进度..."))
            val imported = localSync.syncRootToBookshelf()
            val merged = support.syncStateFile(plan, errors, remoteDownload, remoteUpload)

            remoteListAfter().fold(
                onSuccess = { remoteItems ->
                    val nextLocal = listLocalEbooks().map { it.name }.toSet()
                    val nextRemote = remoteItems.filter { !it.isFolder && it.isSupportedEbook }.map { it.name }.toSet()
                    val names = SyncPlanBuilder.computeNextManifestNames(
                        plan, nextLocal, nextRemote, approvedLocalIds, approvedRemoteIds
                    )
                    runCatching { support.saveManifest(manifestFile, SyncManifest(names, System.currentTimeMillis())) }
                        .onFailure { err -> errors.add("保存同步记录失败: ${err.message}") }
                },
                onFailure = { err -> errors.add("刷新云端清单失败: ${err.message}") }
            )

            if (errors.isNotEmpty()) throw IOException("同步部分失败: ${errors.take(3).joinToString("；")}")
            SyncSummary(pulled, pushed, imported, merged, deletedLocal = deletedLocal, deletedRemote = deletedRemote)
        }
    }

    private suspend fun listLocalEbooks(): List<CloudFileNode> =
        localSync.listNodes("", ebooksOnly = true).getOrThrow().filter { !it.isFolder && it.isSupportedEbook }

    private suspend fun deleteApprovedLocal(
        plan: SyncPlan,
        approvedIds: Set<String>,
        errors: MutableList<String>,
        onProgress: (SyncProgress) -> Unit
    ): Int {
        val localById = listLocalEbooks().associateBy { it.id }
        var count = 0
        for (cand in plan.pendingLocalDeletes.filter { it.id in approvedIds }) {
            val matched = localById[cand.id]?.takeIf {
                it.name == cand.name && it.sizeBytes == cand.sizeBytes && it.modifiedTimeMs == cand.modifiedTimeMs
            }
            if (matched == null) {
                errors.add("跳过删除本地 ${cand.name}：文件已变更或已移除")
                continue
            }
            onProgress(SyncProgress("删除已确认的本地文件 ${cand.name}"))
            localSync.deleteNode(matched).onSuccess { count++ }.onFailure { errors.add("删除本地 ${cand.name}: ${it.message}") }
        }
        return count
    }

    private suspend fun deleteApprovedRemote(
        plan: SyncPlan,
        approvedIds: Set<String>,
        errors: MutableList<String>,
        onProgress: (SyncProgress) -> Unit,
        remoteDelete: suspend (SyncDeleteCandidate) -> Result<Unit>
    ): Int {
        var count = 0
        for (cand in plan.pendingRemoteDeletes.filter { it.id in approvedIds }) {
            onProgress(SyncProgress("删除已确认的云端文件 ${cand.name}"))
            remoteDelete(cand).onSuccess { count++ }.onFailure { errors.add("删除云端 ${cand.name}: ${it.message}") }
        }
        return count
    }

    private suspend fun transferPull(
        plan: SyncPlan,
        errors: MutableList<String>,
        onProgress: (SyncProgress) -> Unit,
        remoteDownload: suspend (CloudFileNode, File) -> Result<Unit>
    ): Int {
        var pulled = 0
        for ((idx, action) in plan.toPull.withIndex()) {
            val r = action.node
            onProgress(SyncProgress("拉取 ${r.name}", idx + 1, plan.toPull.size))
            val tmp = support.safeTempFile("pull", r.name)
            try {
                remoteDownload(r, tmp).getOrThrow()
                localSync.writeTempFileIntoDedicatedRoot(r.name, tmp)
                pulled++
            } catch (t: Throwable) {
                errors.add("拉取 ${r.name}: ${t.message}")
            } finally {
                tmp.delete()
            }
        }
        return pulled
    }

    private suspend fun transferPush(
        plan: SyncPlan,
        errors: MutableList<String>,
        onProgress: (SyncProgress) -> Unit,
        remoteUpload: suspend (String, File, String?) -> Result<Unit>
    ): Int {
        var pushed = 0
        for ((idx, action) in plan.toPush.withIndex()) {
            val l = action.node
            onProgress(SyncProgress("推送 ${l.name}", idx + 1, plan.toPush.size))
            val tmp = support.safeTempFile("push", l.name)
            try {
                localSync.copyNodeToTempFile(l, tmp)
                remoteUpload(l.name, tmp, null).getOrThrow()
                pushed++
            } catch (t: Throwable) {
                errors.add("推送 ${l.name}: ${t.message}")
            } finally {
                tmp.delete()
            }
        }
        return pushed
    }

    companion object {
        private const val DRIVE_MANIFEST = "gdrive_manifest.json"
        private const val WEBDAV_MANIFEST = "webdav_manifest.json"
    }
}
