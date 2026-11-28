package com.aitts.v2.ui.reader

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aitts.v2.di.AppContainer
import com.aitts.v2.engine.cloud.CloudFileNode
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CloudFileManagerViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(CloudFileManagerUiState())
    val uiState: StateFlow<CloudFileManagerUiState> = _uiState.asStateFlow()
    internal val auth = CloudAuthCoordinator(container, viewModelScope, _uiState)
    internal val sync = CloudSyncCoordinator(container, viewModelScope, _uiState) { refresh() }
    private var rawItems: List<CloudFileNode> = emptyList()
    private var listJob: Job? = null

    init {
        val (draftId, draftSec, draftRedir) = container.googleDrive.loadDraftClientCredentials()
        if (draftId.isNotBlank() || draftSec.isNotBlank() || draftRedir.isNotBlank()) {
            _uiState.update {
                it.copy(devClientId = draftId.ifBlank { it.devClientId }, devClientSecret = draftSec, devRedirectUri = draftRedir)
            }
        }
        observeStreams()
        _uiState.update { it.copy(dedicatedDirLabel = container.localSyncFolder.dedicatedDisplayName()) }
        refresh()
    }

    private fun observeStreams() {
        viewModelScope.launch {
            container.googleDrive.authFlow.collect { authCfg ->
                val account = authCfg.accountEmail.ifBlank { authCfg.accountName }.ifBlank { "Google 账号已连接" }
                _uiState.update {
                    it.copy(
                        driveConnected = authCfg.isConnected,
                        driveAccount = if (authCfg.isConnected) account else "",
                        devClientId = if (it.devClientId.isEmpty()) authCfg.clientId else it.devClientId
                    )
                }
                if (authCfg.isConnected && _uiState.value.source == FileSourceTab.GOOGLE_DRIVE) refresh()
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
        viewModelScope.launch {
            container.syncEngine.driveSyncFolderLabelFlow.collect { label ->
                _uiState.update { it.copy(driveSyncFolderLabel = label) }
            }
        }
        viewModelScope.launch {
            container.syncEngine.webDavSyncFolderLabelFlow.collect { label ->
                _uiState.update { it.copy(webDavSyncFolderLabel = label) }
            }
        }
        viewModelScope.launch {
            container.bookLibrary.booksFlow.collect { books ->
                val keys = books.mapNotNull { it.sourceSyncKey.takeIf(String::isNotBlank) }.toSet()
                val titles = books.map { it.title.trim().lowercase() }.toSet()
                _uiState.update { it.copy(importedSyncKeys = keys, importedTitles = titles) }
            }
        }
    }

    fun selectSource(tab: FileSourceTab) {
        val rootCrumb = when (tab) {
            FileSourceTab.LOCAL -> FileBreadcrumb("", "专有目录")
            FileSourceTab.GOOGLE_DRIVE -> FileBreadcrumb("root", "我的云端硬盘")
            FileSourceTab.WEBDAV -> FileBreadcrumb("", "根目录")
        }
        rawItems = emptyList()
        _uiState.update {
            it.copy(
                source = tab,
                breadcrumbs = listOf(rootCrumb),
                items = emptyList(),
                selectedIds = emptySet(),
                searchQuery = "",
                searchExpanded = false,
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
        val inner: Comparator<CloudFileNode> = when (st.sortField) {
            FileSortField.NAME -> if (st.sortAscending) compareBy { it.name.lowercase() } else compareByDescending { it.name.lowercase() }
            FileSortField.SIZE -> if (st.sortAscending) compareBy { it.sizeBytes } else compareByDescending { it.sizeBytes }
            FileSortField.MODIFIED -> if (st.sortAscending) compareBy { it.modifiedTimeMs } else compareByDescending { it.modifiedTimeMs }
        }
        return filtered.sortedWith(compareByDescending<CloudFileNode> { it.isFolder }.then(inner))
    }

    fun isNodeImported(node: CloudFileNode): Boolean {
        if (node.isFolder) return false
        val st = _uiState.value
        val key = when (st.source) {
            FileSourceTab.GOOGLE_DRIVE -> "gdrive:${node.id}"
            FileSourceTab.WEBDAV -> "webdav:${st.webDavEndpoint.trimEnd('/')}/${node.path.trimStart('/')}"
            FileSourceTab.LOCAL -> ""
        }
        if (key.isNotEmpty() && key in st.importedSyncKeys) return true
        val stem = node.name.substringBeforeLast('.').trim().lowercase()
        return stem.isNotEmpty() && stem in st.importedTitles
    }

    fun openFolder(node: CloudFileNode) {
        if (!node.isFolder) return
        val key = if (_uiState.value.source == FileSourceTab.LOCAL) node.path else node.id
        _uiState.update {
            it.copy(breadcrumbs = it.breadcrumbs + FileBreadcrumb(key, node.name), searchQuery = "", searchExpanded = false)
        }
        refresh()
    }

    fun navigateUp() {
        val cur = _uiState.value.breadcrumbs
        if (cur.size > 1) navigateToBreadcrumb(cur.lastIndex - 1)
    }

    fun navigateToBreadcrumb(index: Int) {
        val cur = _uiState.value.breadcrumbs
        if (index !in cur.indices || index == cur.lastIndex) return
        _uiState.update { it.copy(breadcrumbs = cur.take(index + 1), searchQuery = "", searchExpanded = false) }
        refresh()
    }

    fun toggleSearchExpanded() {
        val next = !_uiState.value.searchExpanded
        _uiState.update { it.copy(searchExpanded = next, searchQuery = if (next) it.searchQuery else "") }
        if (!next && _uiState.value.source == FileSourceTab.GOOGLE_DRIVE) refresh()
        else if (!next) _uiState.update { st -> st.copy(items = filterAndSort(rawItems, st)) }
    }

    fun updateSearch(q: String) {
        _uiState.update { it.copy(searchQuery = q) }
        if (_uiState.value.source == FileSourceTab.GOOGLE_DRIVE) refresh()
        else _uiState.update { st -> st.copy(items = filterAndSort(rawItems, st)) }
    }

    fun toggleEbooksOnly() { _uiState.update { it.copy(ebooksOnly = !it.ebooksOnly) }; refresh() }

    fun setSortField(field: FileSortField) {
        _uiState.update { st ->
            val nextAsc = if (st.sortField == field) !st.sortAscending else true
            val next = st.copy(sortField = field, sortAscending = nextAsc)
            next.copy(items = filterAndSort(rawItems, next))
        }
    }

    fun toggleSelect(nodeId: String) = _uiState.update { st ->
        st.copy(selectedIds = if (nodeId in st.selectedIds) st.selectedIds - nodeId else st.selectedIds + nodeId)
    }

    fun selectAllVisible() = _uiState.update { st -> st.copy(selectedIds = st.items.map { it.id }.toSet()) }
    fun clearSelection() = _uiState.update { it.copy(selectedIds = emptySet()) }

    fun bindCustomSyncFolder(uri: Uri) {
        container.localSyncFolder.bindCustomTree(uri)
        _uiState.update { it.copy(statusMessage = "已绑定专属同步目录") }
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

    fun consumeStatusMessage() = _uiState.update { it.copy(statusMessage = null) }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = CloudFileManagerViewModel(container) as T
    }
}
