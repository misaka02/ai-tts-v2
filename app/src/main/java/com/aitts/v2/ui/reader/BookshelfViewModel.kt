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
import com.aitts.v2.reader.updateBookMetadata
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
    val categories: List<String> = emptyList(),
    val selectedCategory: String = "",
    val isGridMode: Boolean = false,
    val searchQuery: String = "",
    val sort: BookshelfSort = BookshelfSort.RECENT,
    val selectedIds: Set<String> = emptySet(),
    val isImporting: Boolean = false,
    val statusMessage: String? = null
)

class BookshelfViewModel(private val container: AppContainer) : ViewModel() {

    private val searchQueryFlow = MutableStateFlow("")
    private val categoryFlow = MutableStateFlow("")
    private val gridModeFlow = MutableStateFlow(false)
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

    private val viewOptionsFlow = combine(searchQueryFlow, categoryFlow, gridModeFlow, sortFlow) { q, cat, grid, sort ->
        ViewFilterOpts(q, cat, grid, sort)
    }

    val uiState: StateFlow<BookshelfUiState> = combine(
        container.bookLibrary.booksFlow,
        viewOptionsFlow,
        selectedIdsFlow,
        isImportingFlow,
        statusMessageFlow
    ) { books, opts, selected, importing, statusMsg ->
        val cats = books.map { it.category.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
        val hasUncategorized = books.any { it.category.isBlank() } && cats.isNotEmpty()
        val allCats = if (hasUncategorized) cats + "未分类" else cats
        val trimmed = opts.query.trim()
        val filtered = books.filter { b ->
            val matchQuery = trimmed.isEmpty() || b.title.contains(trimmed, true) || b.author.contains(trimmed, true)
            val matchCat = when (opts.category) {
                "" -> true
                "未分类" -> b.category.isBlank()
                else -> b.category == opts.category
            }
            matchQuery && matchCat
        }
        val sorted = sortBooks(filtered, opts.sort)
        BookshelfUiState(
            books = sorted,
            categories = allCats,
            selectedCategory = opts.category,
            isGridMode = opts.gridMode,
            searchQuery = opts.query,
            sort = opts.sort,
            selectedIds = selected,
            isImporting = importing,
            statusMessage = statusMsg
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BookshelfUiState())

    private fun sortBooks(filtered: List<BookRecord>, sort: BookshelfSort): List<BookRecord> = when (sort) {
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

    fun updateSearchQuery(query: String) { searchQueryFlow.value = query }
    fun selectCategory(cat: String) { categoryFlow.value = cat }
    fun toggleGridMode() { gridModeFlow.update { !it } }
    fun updateSort(sort: BookshelfSort) { sortFlow.value = sort }
    fun consumeStatusMessage() { statusMessageFlow.value = null }

    fun toggleSelect(bookId: String) {
        selectedIdsFlow.update { cur -> if (bookId in cur) cur - bookId else cur + bookId }
    }

    fun clearSelection() { selectedIdsFlow.value = emptySet() }
    fun togglePin(bookId: String) = container.bookLibrary.togglePin(bookId)

    fun updateBookInfo(bookId: String, title: String, author: String, category: String) {
        container.bookLibrary.updateBookMetadata(bookId, title, author, category)
        statusMessageFlow.value = "已更新书籍信息"
    }

    fun importBooks(uris: List<Uri>, onSingleImported: (String) -> Unit = {}) {
        if (uris.isEmpty() || isImportingFlow.value) return
        isImportingFlow.value = true
        viewModelScope.launch {
            var okCount = 0
            var lastId: String? = null
            var lastTitle = ""
            var lastErr: String? = null
            for (uri in uris) {
                container.bookImportManager.importFromUri(uri).fold(
                    onSuccess = { rec -> okCount++; lastId = rec.id; lastTitle = rec.title },
                    onFailure = { err -> lastErr = err.message }
                )
            }
            isImportingFlow.value = false
            if (uris.size == 1 && lastId != null) {
                statusMessageFlow.value = "已导入《$lastTitle》"
                onSingleImported(lastId)
            } else if (okCount > 0) {
                statusMessageFlow.value = "成功导入 $okCount / ${uris.size} 本书籍"
            } else {
                statusMessageFlow.value = "导入失败: ${lastErr ?: "不支持的格式或文件损坏"}"
            }
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

    private data class ViewFilterOpts(
        val query: String,
        val category: String,
        val gridMode: Boolean,
        val sort: BookshelfSort
    )

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = BookshelfViewModel(container) as T
    }
}
