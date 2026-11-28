package com.aitts.v2.ui.reader

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aitts.v2.di.AppContainer
import com.aitts.v2.engine.cloud.CloudFileNode
import com.aitts.v2.engine.cloud.SyncDirection
import com.aitts.v2.reader.model.BookRecord
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class FileSourceTab(val label: String) {
    LOCAL("本地专有目录"),
    GOOGLE_DRIVE("Google 网盘"),
    WEBDAV("WebDAV 网盘")
}

enum class FileSortField(val label: String) {
    NAME("按名称"),
    SIZE("按大小"),
    MODIFIED("按修改时间")
}

data class FileBreadcrumb(val idOrPath: String, val label: String)

data class CloudFileManagerUiState(
    val source: FileSourceTab = FileSourceTab.LOCAL,
    val breadcrumbs: List<FileBreadcrumb> = listOf(FileBreadcrumb("", "专有目录")),
    val items: List<CloudFileNode> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val searchQuery: String = "",
    val ebooksOnly: Boolean = true,
    val sortField: FileSortField = FileSortField.NAME,
    val isLoading: Boolean = false,
    val isBusy: Boolean = false,
    val busyLabel: String = "",
    val errorMessage: String? = null,
    val statusMessage: String? = null,
    val driveConnected: Boolean = false,
    val driveAccount: String = "",
    val webDavConnected: Boolean = false,
    val webDavEndpoint: String = "",
    val dedicatedDirLabel: String = "",
    val showDevAuthSheet: Boolean = false,
    val isBrowserAuthWaiting: Boolean = false,
    val pendingAuthUrl: String = "",
    val pastedCallbackUrl: String = "",
    val devClientId: String = com.aitts.v2.BuildConfig.GDRIVE_DEFAULT_CLIENT_ID,
    val devClientSecret: String = "",
    val devAccessToken: String = "",
    val webDavUrlInput: String = "",
    val webDavUserInput: String = "",
    val webDavPassInput: String = "",
    val webDavRootInput: String = "/"
)

class CloudFileManagerViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(CloudFileManagerUiState())
    val uiState: StateFlow<CloudFileManagerUiState> = _uiState.asStateFlow()
    internal val auth = CloudAuthCoordinator(container, viewModelScope, _uiState)
    private var rawItems: List<CloudFileNode> = emptyList()
    private var listJob: Job? = null

    init {
        viewModelScope.launch {
            container.googleDrive.authFlow.collect { auth ->
                val account = auth.accountEmail.ifBlank { auth.accountName }.ifBlank { "Google 账号已连接" }
                _uiState.update {
                    it.copy(
                        driveConnected = auth.isConnected,
                        driveAccount = if (auth.isConnected) account else "",
                        devClientId = if (it.devClientId.isEmpty()) auth.clientId else it.devClientId
                    )
                }
                if (auth.isConnected && _uiState.value.source == FileSourceTab.GOOGLE_DRIVE) refresh()
            }
        }
        viewModelScope.launch {
            container.webDav.configFlow.collect { cfg ->
                _uiState.update {
                    it.copy(
                        webDavConnected = cfg.isConnected,
                        webDavEndpoint = cfg.baseUrl,
                        webDavUrlInput = if (it.webDavUrlInput.isEmpty()) cfg.baseUrl else it.webDavUrlInput,
                        webDavUserInput = if (it.webDavUserInput.isEmpty()) cfg.username else it.webDavUserInput,
                        webDavRootInput = if (it.webDavRootInput == "/" && cfg.rootPath.isNotBlank()) cfg.rootPath else it.webDavRootInput
                    )
                }
                if (cfg.isConnected && _uiState.value.source == FileSourceTab.WEBDAV) refresh()
            }
        }
        viewModelScope.launch {
            container.localSyncFolder.customTreeUriFlow.collect {
                _uiState.update { st -> st.copy(dedicatedDirLabel = container.localSyncFolder.dedicatedDisplayName()) }
                if (_uiState.value.source == FileSourceTab.LOCAL) refresh()
            }
        }
        _uiState.update { it.copy(dedicatedDirLabel = container.localSyncFolder.dedicatedDisplayName()) }
        refresh()
    }

    fun selectSource(tab: FileSourceTab) {
        val rootCrumb = when (tab) {
            FileSourceTab.LOCAL -> FileBreadcrumb("", "专有目录")
            FileSourceTab.GOOGLE_DRIVE -> FileBreadcrumb("root", "我的云端硬盘")
            FileSourceTab.WEBDAV -> FileBreadcrumb("", "WebDAV 根目录")
        }
        rawItems = emptyList()
        _uiState.update {
            it.copy(
                source = tab,
                breadcrumbs = listOf(rootCrumb),
                items = emptyList(),
                selectedIds = emptySet(),
                searchQuery = "",
                errorMessage = null
            )
        }
        refresh()
    }

    fun refresh() {
        listJob?.cancel()
        val snap = _uiState.value
        val curId = snap.breadcrumbs.lastOrNull()?.idOrPath.orEmpty()
        if (snap.source == FileSourceTab.GOOGLE_DRIVE && !snap.driveConnected) return
        if (snap.source == FileSourceTab.WEBDAV && !snap.webDavConnected) return
        _uiState.update { it.copy(isLoading = true, errorMessage = null, selectedIds = emptySet()) }

        listJob = viewModelScope.launch {
            val res = when (snap.source) {
                FileSourceTab.LOCAL -> container.localSyncFolder.listNodes(curId, snap.ebooksOnly)
                FileSourceTab.GOOGLE_DRIVE -> container.googleDrive.listNodes(curId.ifBlank { "root" }, snap.searchQuery, snap.ebooksOnly)
                FileSourceTab.WEBDAV -> container.webDav.listFiles(curId, snap.ebooksOnly)
            }
            if (_uiState.value.source != snap.source) return@launch
            res.fold(
                onSuccess = { raw ->
                    rawItems = raw
                    _uiState.update { it.copy(items = filterAndSort(raw, it), isLoading = false) }
                },
                onFailure = { err -> _uiState.update { it.copy(isLoading = false, errorMessage = err.message ?: "读取失败") } }
            )
        }
    }

    private fun filterAndSort(raw: List<CloudFileNode>, st: CloudFileManagerUiState): List<CloudFileNode> {
        val q = st.searchQuery.trim()
        val filtered = if (q.isEmpty() || st.source == FileSourceTab.GOOGLE_DRIVE) raw else raw.filter { it.name.contains(q, true) }
        val cmp = when (st.sortField) {
            FileSortField.NAME -> compareByDescending<CloudFileNode> { it.isFolder }.thenBy { it.name.lowercase() }
            FileSortField.SIZE -> compareByDescending<CloudFileNode> { it.isFolder }.thenByDescending { it.sizeBytes }
            FileSortField.MODIFIED -> compareByDescending<CloudFileNode> { it.isFolder }.thenByDescending { it.modifiedTimeMs }
        }
        return filtered.sortedWith(cmp)
    }

    fun openFolder(node: CloudFileNode) {
        if (!node.isFolder) return
        _uiState.update { it.copy(breadcrumbs = it.breadcrumbs + FileBreadcrumb(node.id, node.name), searchQuery = "") }
        refresh()
    }

    fun navigateToBreadcrumb(index: Int) {
        val cur = _uiState.value.breadcrumbs
        if (index !in cur.indices || index == cur.lastIndex) return
        _uiState.update { it.copy(breadcrumbs = cur.take(index + 1)) }
        refresh()
    }

    fun updateSearch(q: String) {
        _uiState.update { it.copy(searchQuery = q) }
        if (_uiState.value.source == FileSourceTab.GOOGLE_DRIVE) refresh()
        else _uiState.update { st -> st.copy(items = filterAndSort(rawItems, st)) }
    }

    fun toggleEbooksOnly() { _uiState.update { it.copy(ebooksOnly = !it.ebooksOnly) }; refresh() }
    fun setSortField(field: FileSortField) {
        _uiState.update { st -> st.copy(sortField = field, items = filterAndSort(rawItems, st.copy(sortField = field))) }
    }
    fun toggleSelect(nodeId: String) = _uiState.update { st ->
        st.copy(selectedIds = if (nodeId in st.selectedIds) st.selectedIds - nodeId else st.selectedIds + nodeId)
    }
    fun clearSelection() = _uiState.update { it.copy(selectedIds = emptySet()) }

    fun bindCustomSyncFolder(uri: Uri) {
        container.localSyncFolder.bindCustomTree(uri)
        _uiState.update { it.copy(statusMessage = "已绑定专属同步目录") }
    }

    fun importMultiViaSaf(uris: List<Uri>, onDone: (BookRecord?) -> Unit = {}) {
        if (uris.isEmpty()) return
        _uiState.update { it.copy(isBusy = true, busyLabel = "正在批量导入 ${uris.size} 本书籍...") }
        viewModelScope.launch {
            var count = 0
            var lastBook: BookRecord? = null
            for (u in uris) container.bookImportManager.importFromUri(u).onSuccess { count++; lastBook = it }
            _uiState.update { it.copy(isBusy = false, busyLabel = "", statusMessage = "成功导入 $count / ${uris.size} 本书籍") }
            if (uris.size == 1 && lastBook != null) onDone(lastBook)
        }
    }

    fun importFileNode(node: CloudFileNode, onImported: (String) -> Unit = {}) {
        if (node.isFolder || _uiState.value.isBusy) return
        val src = _uiState.value.source
        _uiState.update { it.copy(isBusy = true, busyLabel = "正在导入《${node.name}》...") }
        viewModelScope.launch {
            importNodeSingle(src, node).fold(
                onSuccess = { rec ->
                    _uiState.update { it.copy(isBusy = false, busyLabel = "", statusMessage = "已导入《${rec.title}》") }
                    onImported(rec.id)
                },
                onFailure = { err ->
                    _uiState.update { it.copy(isBusy = false, busyLabel = "", errorMessage = "导入失败: ${err.message}") }
                }
            )
        }
    }

    fun batchImportSelected() {
        val selected = _uiState.value.items.filter { it.id in _uiState.value.selectedIds && !it.isFolder }
        if (selected.isEmpty()) return
        val src = _uiState.value.source
        _uiState.update { it.copy(isBusy = true, busyLabel = "正在批量导入 ${selected.size} 项...") }
        viewModelScope.launch {
            var ok = 0
            for (node in selected) if (importNodeSingle(src, node).isSuccess) ok++
            _uiState.update { it.copy(isBusy = false, busyLabel = "", selectedIds = emptySet(), statusMessage = "已导入 $ok 本到书架") }
        }
    }

    private suspend fun importNodeSingle(src: FileSourceTab, node: CloudFileNode): Result<BookRecord> = when (src) {
        FileSourceTab.LOCAL -> runCatching { container.localSyncFolder.importNodeToBookshelf(node) }
        FileSourceTab.GOOGLE_DRIVE -> container.googleDrive.downloadAndImport(node) {}
        FileSourceTab.WEBDAV -> container.webDav.downloadAndImport(node) {}
    }

    fun createFolder(name: String) {
        val st = _uiState.value
        val parent = st.breadcrumbs.lastOrNull()?.idOrPath.orEmpty()
        viewModelScope.launch {
            val res = when (st.source) {
                FileSourceTab.LOCAL -> container.localSyncFolder.createFolder(parent, name)
                FileSourceTab.GOOGLE_DRIVE -> container.googleDrive.createFolder(parent.ifBlank { "root" }, name).map {}
                FileSourceTab.WEBDAV -> container.webDav.createFolder(parent, name)
            }
            res.fold(onSuccess = { refresh() }, onFailure = { err -> _uiState.update { it.copy(errorMessage = err.message) } })
        }
    }

    fun renameNode(node: CloudFileNode, newName: String) {
        val src = _uiState.value.source
        viewModelScope.launch {
            val res = when (src) {
                FileSourceTab.LOCAL -> container.localSyncFolder.renameNode(node, newName)
                FileSourceTab.GOOGLE_DRIVE -> container.googleDrive.renameItem(node.id, newName).map {}
                FileSourceTab.WEBDAV -> container.webDav.renameItem(node.path, newName)
            }
            res.fold(onSuccess = { refresh() }, onFailure = { err -> _uiState.update { it.copy(errorMessage = err.message) } })
        }
    }

    fun deleteNode(node: CloudFileNode) {
        val src = _uiState.value.source
        viewModelScope.launch {
            val res = when (src) {
                FileSourceTab.LOCAL -> container.localSyncFolder.deleteNode(node)
                FileSourceTab.GOOGLE_DRIVE -> container.googleDrive.deleteItem(node.id)
                FileSourceTab.WEBDAV -> container.webDav.deleteItem(node.path)
            }
            res.fold(onSuccess = { refresh() }, onFailure = { err -> _uiState.update { it.copy(errorMessage = err.message) } })
        }
    }

    fun runSync(direction: SyncDirection) {
        val src = _uiState.value.source
        _uiState.update { it.copy(isBusy = true, busyLabel = "准备同步...", errorMessage = null) }
        viewModelScope.launch {
            val res = when (src) {
                FileSourceTab.LOCAL -> container.syncEngine.syncLocalFolderOnly()
                FileSourceTab.GOOGLE_DRIVE -> container.syncEngine.syncWithGoogleDrive(direction) { p ->
                    _uiState.update { it.copy(busyLabel = p.stage) }
                }
                FileSourceTab.WEBDAV -> container.syncEngine.syncWithWebDav(direction) { p ->
                    _uiState.update { it.copy(busyLabel = p.stage) }
                }
            }
            res.fold(
                onSuccess = { sum ->
                    val summaryMsg = "同步完成：拉取 ${sum.pulledBooks}，推送 ${sum.pushedBooks}，" +
                        "入库 ${sum.importedToShelf}，笔记 ${sum.mergedStates}"
                    _uiState.update { it.copy(isBusy = false, busyLabel = "", statusMessage = summaryMsg) }
                    refresh()
                },
                onFailure = { err ->
                    _uiState.update { it.copy(isBusy = false, busyLabel = "", errorMessage = "同步失败: ${err.message}") }
                }
            )
        }
    }

    fun consumeStatusMessage() = _uiState.update { it.copy(statusMessage = null) }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = CloudFileManagerViewModel(container) as T
    }
}
