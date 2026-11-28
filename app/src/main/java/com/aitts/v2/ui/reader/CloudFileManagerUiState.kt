package com.aitts.v2.ui.reader

import com.aitts.v2.BuildConfig
import com.aitts.v2.engine.cloud.CloudFileNode
import com.aitts.v2.engine.cloud.SyncPlan
import com.aitts.v2.reader.MoonMigrationPreview

enum class FileSourceTab(val label: String) {
    LOCAL("本地"),
    GOOGLE_DRIVE("Google 网盘"),
    WEBDAV("WebDAV")
}

enum class FileSortField(val label: String) {
    NAME("名称"),
    SIZE("大小"),
    MODIFIED("修改时间")
}

data class FileBreadcrumb(val idOrPath: String, val label: String)

data class CloudFileManagerUiState(
    val source: FileSourceTab = FileSourceTab.LOCAL,
    val breadcrumbs: List<FileBreadcrumb> = listOf(FileBreadcrumb("", "专有目录")),
    val items: List<CloudFileNode> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val importedSyncKeys: Set<String> = emptySet(),
    val importedTitles: Set<String> = emptySet(),
    val searchQuery: String = "",
    val searchExpanded: Boolean = false,
    val ebooksOnly: Boolean = true,
    val sortField: FileSortField = FileSortField.NAME,
    val sortAscending: Boolean = true,
    val isLoading: Boolean = false,
    val isBusy: Boolean = false,
    val busyLabel: String = "",
    val busyProgress: Float? = null,
    val errorMessage: String? = null,
    val statusMessage: String? = null,
    val driveConnected: Boolean = false,
    val driveAccount: String = "",
    val webDavConnected: Boolean = false,
    val webDavEndpoint: String = "",
    val dedicatedDirLabel: String = "",
    val driveSyncFolderLabel: String = "AI-TTS-Library",
    val webDavSyncFolderLabel: String = "AI-TTS-Library",
    val showSyncOptionsSheet: Boolean = false,
    val showBatchDeleteConfirm: Boolean = false,
    val pendingSyncPlan: SyncPlan? = null,
    val moonPreview: MoonMigrationPreview? = null,
    val moveTargetNode: CloudFileNode? = null,
    val showDevAuthSheet: Boolean = false,
    val showPasteCredsSheet: Boolean = false,
    val isBrowserAuthWaiting: Boolean = false,
    val pendingAuthUrl: String = "",
    val pastedCallbackUrl: String = "",
    val devClientId: String = BuildConfig.GDRIVE_DEFAULT_CLIENT_ID,
    val devClientSecret: String = "",
    val devRedirectUri: String = "",
    val devRefreshToken: String = "",
    val devAccessToken: String = "",
    val webDavUrlInput: String = "",
    val webDavUserInput: String = "",
    val webDavPassInput: String = "",
    val webDavRootInput: String = "/"
)
