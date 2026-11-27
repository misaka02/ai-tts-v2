package com.aitts.v2.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aitts.v2.di.AppContainer
import com.aitts.v2.engine.cloud.DriveFileItem
import com.aitts.v2.engine.cloud.GoogleDriveAuthConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DriveFolderNode(val id: String, val name: String)

data class GoogleDriveUiState(
    val authTab: Int = 0,
    val clientIdInput: String = "",
    val clientSecretInput: String = "",
    val refreshTokenInput: String = "",
    val accessTokenInput: String = "",
    val isAuthorizing: Boolean = false,
    val isLoading: Boolean = false,
    val downloadingFileId: String? = null,
    val downloadProgress: Float = 0f,
    val folderStack: List<DriveFolderNode> = listOf(DriveFolderNode("root", "我的云端硬盘")),
    val searchQuery: String = "",
    val ebooksOnly: Boolean = true,
    val items: List<DriveFileItem> = emptyList(),
    val nextPageToken: String? = null,
    val statusMessage: String? = null
)

class GoogleDriveViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(
        GoogleDriveUiState(
            clientIdInput = container.googleDrive.authFlow.value.clientId,
            clientSecretInput = container.googleDrive.authFlow.value.clientSecret
        )
    )
    val uiState: StateFlow<GoogleDriveUiState> = _uiState.asStateFlow()

    val authFlow: StateFlow<GoogleDriveAuthConfig> = container.googleDrive.authFlow
    val existingBookNamesFlow: StateFlow<Set<String>> = container.bookLibrary.booksFlow
        .map { list -> list.map { it.title.lowercase() }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    private var listJob: Job? = null

    init {
        if (container.googleDrive.authFlow.value.isConnected) {
            refreshCurrentFolder()
        }
    }

    fun selectAuthTab(tab: Int) {
        _uiState.update { it.copy(authTab = tab) }
    }

    fun updateCredentialInputs(
        clientId: String? = null,
        clientSecret: String? = null,
        refreshToken: String? = null,
        accessToken: String? = null
    ) {
        _uiState.update {
            it.copy(
                clientIdInput = clientId ?: it.clientIdInput,
                clientSecretInput = clientSecret ?: it.clientSecretInput,
                refreshTokenInput = refreshToken ?: it.refreshTokenInput,
                accessTokenInput = accessToken ?: it.accessTokenInput
            )
        }
    }

    fun startBrowserOAuth(onLaunchUrl: (String) -> Unit) {
        if (_uiState.value.isAuthorizing) return
        val clientId = _uiState.value.clientIdInput
        val secret = _uiState.value.clientSecretInput
        _uiState.update { it.copy(isAuthorizing = true) }
        viewModelScope.launch {
            val res = container.googleDrive.authorizeInBrowser(clientId, secret, onLaunchUrl)
            _uiState.update { it.copy(isAuthorizing = false) }
            res.fold(
                onSuccess = { cfg ->
                    val who = cfg.accountEmail.ifBlank { "已授权" }
                    _uiState.update { it.copy(statusMessage = "Google 网盘已连接 ($who)") }
                    refreshCurrentFolder()
                },
                onFailure = { err ->
                    _uiState.update { it.copy(statusMessage = err.message ?: "授权失败") }
                }
            )
        }
    }

    fun cancelBrowserOAuth() {
        container.googleDrive.cancelPendingAuth()
        _uiState.update { it.copy(isAuthorizing = false, statusMessage = "已取消授权等待") }
    }

    fun connectWithTokens() {
        val state = _uiState.value
        _uiState.update { it.copy(isAuthorizing = true) }
        viewModelScope.launch {
            val res = container.googleDrive.connectDirect(
                clientId = state.clientIdInput,
                clientSecret = state.clientSecretInput,
                refreshToken = state.refreshTokenInput,
                accessToken = state.accessTokenInput
            )
            _uiState.update { it.copy(isAuthorizing = false) }
            res.fold(
                onSuccess = {
                    _uiState.update { it.copy(statusMessage = "Google 网盘令牌验证成功") }
                    refreshCurrentFolder()
                },
                onFailure = { err ->
                    _uiState.update { it.copy(statusMessage = err.message ?: "令牌验证失败") }
                }
            )
        }
    }

    fun disconnect() {
        listJob?.cancel()
        listJob = null
        container.googleDrive.disconnect()
        _uiState.update {
            it.copy(
                isLoading = false,
                folderStack = listOf(DriveFolderNode("root", "我的云端硬盘")),
                items = emptyList(),
                nextPageToken = null,
                statusMessage = "已断开 Google 网盘"
            )
        }
    }

    fun refreshCurrentFolder(appendNextPage: Boolean = false) {
        val state = _uiState.value
        if (!container.googleDrive.authFlow.value.isConnected) return
        val folder = state.folderStack.lastOrNull()?.id ?: "root"
        val query = state.searchQuery
        val ebooksOnly = state.ebooksOnly
        val token = if (appendNextPage) state.nextPageToken else null
        if (appendNextPage && token == null) return

        listJob?.cancel()
        _uiState.update { it.copy(isLoading = true) }
        listJob = viewModelScope.launch {
            val res = container.googleDrive.listFiles(folder, query, ebooksOnly, token)
            val latest = _uiState.value
            val stillMatching = container.googleDrive.authFlow.value.isConnected &&
                (latest.folderStack.lastOrNull()?.id ?: "root") == folder &&
                latest.searchQuery == query &&
                latest.ebooksOnly == ebooksOnly
            if (!stillMatching) return@launch
            res.fold(
                onSuccess = { page ->
                    _uiState.update { cur ->
                        cur.copy(
                            isLoading = false,
                            items = if (appendNextPage) cur.items + page.items else page.items,
                            nextPageToken = page.nextPageToken
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(isLoading = false, statusMessage = err.message ?: "加载网盘目录失败")
                    }
                }
            )
        }
    }

    fun openFolder(folder: DriveFileItem) {
        _uiState.update {
            it.copy(folderStack = it.folderStack + DriveFolderNode(folder.id, folder.name), searchQuery = "")
        }
        refreshCurrentFolder()
    }

    fun navigateUpFolder() {
        val stack = _uiState.value.folderStack
        if (stack.size <= 1) return
        _uiState.update { it.copy(folderStack = stack.dropLast(1)) }
        refreshCurrentFolder()
    }

    fun updateSearch(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        refreshCurrentFolder()
    }

    fun toggleEbooksOnly() {
        _uiState.update { it.copy(ebooksOnly = !it.ebooksOnly) }
        refreshCurrentFolder()
    }

    fun downloadAndImport(item: DriveFileItem, onImported: (String) -> Unit) {
        if (_uiState.value.downloadingFileId != null) return
        _uiState.update { it.copy(downloadingFileId = item.id, downloadProgress = 0f) }
        viewModelScope.launch {
            val res = container.googleDrive.downloadAndImport(item) { p ->
                _uiState.update { it.copy(downloadProgress = p) }
            }
            _uiState.update { it.copy(downloadingFileId = null, downloadProgress = 0f) }
            res.fold(
                onSuccess = { record ->
                    _uiState.update { it.copy(statusMessage = "已导入《${record.title}》") }
                    onImported(record.id)
                },
                onFailure = { err ->
                    _uiState.update { it.copy(statusMessage = "导入失败: ${err.message}") }
                }
            )
        }
    }

    fun consumeStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    override fun onCleared() {
        container.googleDrive.cancelPendingAuth()
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = GoogleDriveViewModel(container) as T
    }
}
