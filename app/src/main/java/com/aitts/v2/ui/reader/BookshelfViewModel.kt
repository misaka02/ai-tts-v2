package com.aitts.v2.ui.reader

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aitts.v2.di.AppContainer
import com.aitts.v2.reader.ReaderTtsState
import com.aitts.v2.reader.model.BookRecord
import com.aitts.v2.reader.togglePin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class BookshelfSort(val label: String) {
    RECENT("最近阅读"),
    TITLE("按书名"),
    ADDED("导入时间"),
    PROGRESS("阅读进度")
}

data class BookshelfUiState(
    val books: List<BookRecord> = emptyList(),
    val searchQuery: String = "",
    val sort: BookshelfSort = BookshelfSort.RECENT,
    val selectedIds: Set<String> = emptySet(),
    val isImporting: Boolean = false,
    val statusMessage: String? = null
)

class BookshelfViewModel(private val container: AppContainer) : ViewModel() {

    private val searchQueryFlow = MutableStateFlow("")
    private val sortFlow = MutableStateFlow(BookshelfSort.RECENT)
    private val selectedIdsFlow = MutableStateFlow<Set<String>>(emptySet())
    private val isImportingFlow = MutableStateFlow(false)
    private val statusMessageFlow = MutableStateFlow<String?>(null)

    val ttsStateFlow: StateFlow<ReaderTtsState> = container.readerTts.stateFlow
    val hasCloudConnectedFlow: StateFlow<Boolean> = combine(
        container.googleDrive.authFlow,
        container.webDav.configFlow
    ) { drive, dav -> drive.isConnected || dav.isConnected }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val uiState: StateFlow<BookshelfUiState> = combine(
        container.bookLibrary.booksFlow,
        searchQueryFlow,
        sortFlow,
        selectedIdsFlow,
        isImportingFlow
    ) { books, query, sort, selected, importing ->
        val trimmed = query.trim()
        val filtered = if (trimmed.isEmpty()) books else {
            books.filter { it.title.contains(trimmed, true) || it.author.contains(trimmed, true) }
        }
        val sorted = when (sort) {
            BookshelfSort.RECENT -> filtered.sortedWith(
                compareByDescending<BookRecord> { it.isPinned }.thenByDescending { maxOf(it.lastReadAt, it.addedAt) }
            )
            BookshelfSort.TITLE -> filtered.sortedWith(
                compareByDescending<BookRecord> { it.isPinned }.thenBy { it.title.lowercase() }
            )
            BookshelfSort.ADDED -> filtered.sortedWith(
                compareByDescending<BookRecord> { it.isPinned }.thenByDescending { it.addedAt }
            )
            BookshelfSort.PROGRESS -> filtered.sortedWith(
                compareByDescending<BookRecord> { it.isPinned }.thenByDescending { it.progress.progressPercent }
            )
        }
        BookshelfUiState(sorted, query, sort, selected, importing, statusMessageFlow.value)
    }.combine(statusMessageFlow) { st, msg -> st.copy(statusMessage = msg) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BookshelfUiState())

    fun updateSearchQuery(query: String) { searchQueryFlow.value = query }
    fun updateSort(sort: BookshelfSort) { sortFlow.value = sort }
    fun consumeStatusMessage() { statusMessageFlow.value = null }

    fun toggleSelect(bookId: String) {
        selectedIdsFlow.update { cur -> if (bookId in cur) cur - bookId else cur + bookId }
    }

    fun clearSelection() { selectedIdsFlow.value = emptySet() }

    fun togglePin(bookId: String) = container.bookLibrary.togglePin(bookId)

    fun importBook(uri: Uri, onImported: (String) -> Unit = {}) {
        if (isImportingFlow.value) return
        isImportingFlow.value = true
        viewModelScope.launch {
            val res = container.bookImportManager.importFromUri(uri)
            isImportingFlow.value = false
            res.fold(
                onSuccess = { record -> statusMessageFlow.value = "已导入《${record.title}》"; onImported(record.id) },
                onFailure = { err -> statusMessageFlow.value = "导入失败: ${err.message ?: "不支持的格式或文件损坏"}" }
            )
        }
    }

    fun syncDedicatedFolder() {
        if (isImportingFlow.value) return
        isImportingFlow.value = true
        viewModelScope.launch {
            val res = container.syncEngine.syncLocalFolderOnly()
            isImportingFlow.value = false
            res.fold(
                onSuccess = { sum -> statusMessageFlow.value = "专属目录扫描完成，新导入 ${sum.importedToShelf} 本" },
                onFailure = { err -> statusMessageFlow.value = "同步失败: ${err.message}" }
            )
        }
    }

    fun deleteSelectedBooks() {
        val ids = selectedIdsFlow.value
        if (ids.isEmpty()) return
        if (container.readerTts.stateFlow.value.bookId in ids) container.readerTts.stop()
        container.bookLibrary.deleteBooks(ids)
        selectedIdsFlow.value = emptySet()
        statusMessageFlow.value = "已删除 ${ids.size} 本书籍"
    }

    fun deleteBook(bookId: String) {
        if (container.readerTts.stateFlow.value.bookId == bookId) container.readerTts.stop()
        container.bookLibrary.deleteBooks(setOf(bookId))
        statusMessageFlow.update { "已从书架移除" }
    }

    fun stopTts() = container.readerTts.stop()
    suspend fun loadCover(record: BookRecord): Bitmap? = container.epubImageLoader.loadCover(record)

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = BookshelfViewModel(container) as T
    }
}
