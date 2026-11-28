package com.aitts.v2.ui.reader

import android.net.Uri
import com.aitts.v2.di.AppContainer
import com.aitts.v2.engine.cloud.CloudFileNode
import com.aitts.v2.engine.cloud.SyncDirection
import com.aitts.v2.engine.cloud.SyncPlan
import com.aitts.v2.engine.cloud.SyncSummary
import com.aitts.v2.reader.downloadAndImport
import com.aitts.v2.reader.downloadToLocalSyncFolder
import com.aitts.v2.reader.model.BookRecord
import com.aitts.v2.reader.uploadFromUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Coordinates two-phase safe sync (mandatory confirmation before deletions), custom sync root binding,
 * Moon+ Reader (`.Moon+`) scan & inheritance, batch file operations, and cloud uploads.
 */
internal class CloudSyncCoordinator(
    private val container: AppContainer,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<CloudFileManagerUiState>,
    private val onRefresh: () -> Unit
) {
    fun setSyncOptionsSheet(visible: Boolean) = state.update { it.copy(showSyncOptionsSheet = visible) }

    fun setCurrentFolderAsSyncRoot() {
        val st = state.value
        val cur = st.breadcrumbs.lastOrNull() ?: return
        when (st.source) {
            FileSourceTab.GOOGLE_DRIVE -> {
                val id = cur.idOrPath.ifBlank { "root" }
                val label = cur.label.ifBlank { "我的云端硬盘" }
                container.syncEngine.setDriveSyncFolder(id, label)
                state.update { it.copy(showSyncOptionsSheet = false, statusMessage = "已将「$label」设为 Google 网盘同步目录") }
            }
            FileSourceTab.WEBDAV -> {
                val path = cur.idOrPath.trim('/').ifBlank { "/" }
                container.syncEngine.setWebDavSyncFolder(path)
                val shown = if (path == "/") "WebDAV 根目录" else path
                state.update { it.copy(showSyncOptionsSheet = false, statusMessage = "已将「$shown」设为 WebDAV 同步目录") }
            }
            FileSourceTab.LOCAL -> Unit
        }
    }

    fun startSyncFlow(direction: SyncDirection) {
        val src = state.value.source
        state.update {
            it.copy(showSyncOptionsSheet = false, isBusy = true, busyProgress = null, busyLabel = "正在比对同步差异...", errorMessage = null)
        }
        scope.launch {
            if (src == FileSourceTab.LOCAL) {
                finishSyncResult(container.syncEngine.syncLocalFolderOnly())
                return@launch
            }
            val planRes = if (src == FileSourceTab.GOOGLE_DRIVE) {
                container.syncEngine.prepareGoogleDriveSync(direction)
            } else {
                container.syncEngine.prepareWebDavSync(direction)
            }
            planRes.fold(
                onSuccess = { plan ->
                    if (plan.hasDeletions) {
                        state.update { it.copy(isBusy = false, busyLabel = "", pendingSyncPlan = plan) }
                    } else {
                        executeSyncPlan(plan, emptySet(), emptySet())
                    }
                },
                onFailure = { err ->
                    state.update { it.copy(isBusy = false, busyLabel = "", errorMessage = "同步准备失败: ${err.message}") }
                }
            )
        }
    }

    fun confirmSyncPlan(approvedLocalIds: Set<String>, approvedRemoteIds: Set<String>) {
        val plan = state.value.pendingSyncPlan ?: return
        state.update {
            it.copy(pendingSyncPlan = null, isBusy = true, busyProgress = null, busyLabel = "正在按确认项执行同步...", errorMessage = null)
        }
        scope.launch { executeSyncPlan(plan, approvedLocalIds, approvedRemoteIds) }
    }

    fun dismissPendingSyncPlan() = state.update { it.copy(pendingSyncPlan = null, statusMessage = "已取消同步") }

    private suspend fun executeSyncPlan(plan: SyncPlan, approvedLocalIds: Set<String>, approvedRemoteIds: Set<String>) {
        val res = if (plan.providerLabel == "Google Drive") {
            container.syncEngine.executeGoogleDriveSync(plan, approvedLocalIds, approvedRemoteIds) { p ->
                val frac = if (p.total > 0) (p.current.toFloat() / p.total).coerceIn(0f, 1f) else null
                state.update { it.copy(busyLabel = p.stage, busyProgress = frac) }
            }
        } else {
            container.syncEngine.executeWebDavSync(plan, approvedLocalIds, approvedRemoteIds) { p ->
                val frac = if (p.total > 0) (p.current.toFloat() / p.total).coerceIn(0f, 1f) else null
                state.update { it.copy(busyLabel = p.stage, busyProgress = frac) }
            }
        }
        finishSyncResult(res)
    }

    private fun finishSyncResult(res: Result<SyncSummary>) {
        res.fold(
            onSuccess = { s ->
                val delPart = if (s.deletedLocal + s.deletedRemote > 0) "，确认删除 ${s.deletedLocal + s.deletedRemote}" else ""
                val msg = "同步完成：拉取 ${s.pulledBooks}，推送 ${s.pushedBooks}，入库 ${s.importedToShelf}，进度/笔记 ${s.mergedStates}$delPart"
                state.update { it.copy(isBusy = false, busyLabel = "", busyProgress = null, statusMessage = msg) }
                onRefresh()
            },
            onFailure = { err ->
                state.update { it.copy(isBusy = false, busyLabel = "", busyProgress = null, errorMessage = "同步失败: ${err.message}") }
            }
        )
    }

    fun scanMoonReaderLibrary(folderIdOverride: String? = null, folderNameOverride: String? = null) {
        val st = state.value
        val cur = st.breadcrumbs.lastOrNull()
        val targetId = folderIdOverride ?: cur?.idOrPath.orEmpty().ifBlank { "root" }
        val targetName = folderNameOverride ?: cur?.label.orEmpty()
        state.update {
            it.copy(isBusy = true, busyProgress = null, busyLabel = "正在扫描静读天下 .Moon+ 备份...", errorMessage = null)
        }
        scope.launch {
            container.moonMigrator.scanMoonLibrary(targetId, targetName) { msg ->
                state.update { it.copy(busyLabel = msg) }
            }.fold(
                onSuccess = { preview ->
                    state.update { it.copy(isBusy = false, busyLabel = "", moonPreview = preview) }
                },
                onFailure = { err ->
                    state.update { it.copy(isBusy = false, busyLabel = "", errorMessage = err.message ?: "未找到 .Moon+ 备份") }
                }
            )
        }
    }

    fun executeMoonImport(selectedFileIds: Set<String>, downloadMissingBooks: Boolean, alsoSetAsSyncFolder: Boolean) {
        val preview = state.value.moonPreview ?: return
        if (selectedFileIds.isEmpty()) return
        state.update {
            it.copy(moonPreview = null, isBusy = true, busyProgress = 0f, busyLabel = "准备继承静读天下书库...")
        }
        scope.launch {
            if (alsoSetAsSyncFolder) {
                container.syncEngine.setDriveSyncFolder(preview.sourceFolderId, preview.sourceFolderName)
            }
            container.moonMigrator.importSelected(preview, selectedFileIds, downloadMissingBooks) { done, total, stage ->
                val frac = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
                state.update { it.copy(busyLabel = "$stage ($done/$total)", busyProgress = frac) }
            }.fold(
                onSuccess = { r ->
                    val msg = "已继承静读天下：新导入 ${r.importedNewBooks} 本，合并进度/笔记 ${r.mergedExistingBooks} 本" +
                        "（含 ${r.progressApplied} 处进度、${r.notesImported} 条高亮/书签）"
                    state.update { it.copy(isBusy = false, busyLabel = "", busyProgress = null, statusMessage = msg) }
                    onRefresh()
                },
                onFailure = { err ->
                    state.update { it.copy(isBusy = false, busyLabel = "", busyProgress = null, errorMessage = "继承失败: ${err.message}") }
                }
            )
        }
    }

    fun dismissMoonPreview() = state.update { it.copy(moonPreview = null) }

    fun uploadFilesToCurrentFolder(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val st = state.value
        val curId = st.breadcrumbs.lastOrNull()?.idOrPath.orEmpty()
        state.update { it.copy(isBusy = true, busyProgress = 0f, busyLabel = "正在上传 ${uris.size} 个文件...") }
        scope.launch {
            var ok = 0
            for ((idx, uri) in uris.withIndex()) {
                state.update { it.copy(busyLabel = "上传文件 (${idx + 1}/${uris.size})...", busyProgress = idx.toFloat() / uris.size) }
                val res = when (st.source) {
                    FileSourceTab.LOCAL -> container.localSyncFolder.writeUriIntoFolder(curId, uri)
                    FileSourceTab.GOOGLE_DRIVE -> container.googleDrive.uploadFromUri(curId.ifBlank { "root" }, uri).map {}
                    FileSourceTab.WEBDAV -> container.webDav.uploadFromUri(curId, uri)
                }
                if (res.isSuccess) ok++
            }
            state.update { it.copy(isBusy = false, busyLabel = "", busyProgress = null, statusMessage = "已上传 $ok / ${uris.size} 个文件") }
            onRefresh()
        }
    }

    fun downloadNodeToLocalSync(node: CloudFileNode) {
        val src = state.value.source
        if (src == FileSourceTab.LOCAL || node.isFolder) return
        state.update { it.copy(isBusy = true, busyProgress = null, busyLabel = "正在下载《${node.name}》到本地同步目录...") }
        scope.launch {
            val res = if (src == FileSourceTab.GOOGLE_DRIVE) {
                container.googleDrive.downloadToLocalSyncFolder(node, container.localSyncFolder)
            } else {
                container.webDav.downloadToLocalSyncFolder(node, container.localSyncFolder)
            }
            res.fold(
                onSuccess = { state.update { it.copy(isBusy = false, busyLabel = "", statusMessage = "已保存到本地同步目录：${node.name}") } },
                onFailure = { err -> state.update { it.copy(isBusy = false, busyLabel = "", errorMessage = "下载失败: ${err.message}") } }
            )
        }
    }

    fun openMoveDialog(node: CloudFileNode?) = state.update { it.copy(moveTargetNode = node) }

    fun moveDriveNodeToFolder(targetFolder: CloudFileNode) {
        val node = state.value.moveTargetNode ?: return
        val parentId = state.value.breadcrumbs.lastOrNull()?.idOrPath.orEmpty().ifBlank { "root" }
        state.update { it.copy(moveTargetNode = null, isBusy = true, busyLabel = "正在移动《${node.name}》...") }
        scope.launch {
            container.googleDrive.moveItem(node.id, parentId, targetFolder.id).fold(
                onSuccess = {
                    state.update { it.copy(isBusy = false, busyLabel = "", statusMessage = "已移动到「${targetFolder.name}」") }
                    onRefresh()
                },
                onFailure = { err -> state.update { it.copy(isBusy = false, busyLabel = "", errorMessage = "移动失败: ${err.message}") } }
            )
        }
    }

    fun setBatchDeleteConfirm(visible: Boolean) = state.update { it.copy(showBatchDeleteConfirm = visible) }

    fun confirmBatchDelete() {
        val st = state.value
        val targets = st.items.filter { it.id in st.selectedIds }
        state.update { it.copy(showBatchDeleteConfirm = false, isBusy = true, busyLabel = "正在删除 ${targets.size} 项...") }
        scope.launch {
            var ok = 0
            for (node in targets) {
                val res = when (st.source) {
                    FileSourceTab.LOCAL -> container.localSyncFolder.deleteNode(node)
                    FileSourceTab.GOOGLE_DRIVE -> container.googleDrive.deleteItem(node.id)
                    FileSourceTab.WEBDAV -> container.webDav.deleteItem(node.path)
                }
                if (res.isSuccess) ok++
            }
            state.update {
                it.copy(isBusy = false, busyLabel = "", selectedIds = emptySet(), statusMessage = "已删除 $ok / ${targets.size} 项")
            }
            onRefresh()
        }
    }

    fun importMultiViaSaf(uris: List<Uri>, onDone: (BookRecord?) -> Unit = {}) {
        if (uris.isEmpty()) return
        state.update { it.copy(isBusy = true, busyLabel = "正在批量导入 ${uris.size} 本书籍...") }
        scope.launch {
            var count = 0
            var lastBook: BookRecord? = null
            for (u in uris) container.bookImportManager.importFromUri(u).onSuccess { count++; lastBook = it }
            state.update { it.copy(isBusy = false, busyLabel = "", statusMessage = "成功导入 $count / ${uris.size} 本书籍") }
            if (uris.size == 1 && lastBook != null) onDone(lastBook)
        }
    }

    fun importFileNode(node: CloudFileNode, onImported: (String) -> Unit = {}) {
        if (node.isFolder || state.value.isBusy) return
        val src = state.value.source
        state.update { it.copy(isBusy = true, busyProgress = 0f, busyLabel = "正在导入《${node.name}》...") }
        scope.launch {
            importNodeSingle(src, node) { p -> state.update { it.copy(busyProgress = p) } }.fold(
                onSuccess = { rec ->
                    state.update { it.copy(isBusy = false, busyLabel = "", busyProgress = null, statusMessage = "已导入《${rec.title}》") }
                    onImported(rec.id)
                },
                onFailure = { err ->
                    state.update { it.copy(isBusy = false, busyLabel = "", busyProgress = null, errorMessage = "导入失败: ${err.message}") }
                }
            )
        }
    }

    fun batchImportSelected() {
        val selected = state.value.items.filter { it.id in state.value.selectedIds && !it.isFolder }
        if (selected.isEmpty()) return
        val src = state.value.source
        state.update { it.copy(isBusy = true, busyProgress = 0f, busyLabel = "正在批量导入 ${selected.size} 项...") }
        scope.launch {
            var ok = 0
            for ((idx, node) in selected.withIndex()) {
                val frac = idx.toFloat() / selected.size
                state.update { it.copy(busyLabel = "导入 (${idx + 1}/${selected.size})：${node.name}", busyProgress = frac) }
                if (importNodeSingle(src, node).isSuccess) ok++
            }
            state.update {
                it.copy(isBusy = false, busyLabel = "", busyProgress = null, selectedIds = emptySet(), statusMessage = "已导入 $ok 本到书架")
            }
        }
    }

    private suspend fun importNodeSingle(
        src: FileSourceTab,
        node: CloudFileNode,
        onProgress: (Float) -> Unit = {}
    ): Result<BookRecord> = when (src) {
        FileSourceTab.LOCAL -> runCatching { container.localSyncFolder.importNodeToBookshelf(node) }
        FileSourceTab.GOOGLE_DRIVE -> container.googleDrive.downloadAndImport(node, onProgress)
        FileSourceTab.WEBDAV -> container.webDav.downloadAndImport(node, onProgress)
    }
}
