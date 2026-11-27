package com.aitts.v2.ui.reader

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aitts.v2.di.AppContainer
import com.aitts.v2.reader.ReaderTtsState
import com.aitts.v2.reader.model.BookRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BookshelfUiState(
    val books: List<BookRecord> = emptyList(),
    val searchQuery: String = "",
    val isImporting: Boolean = false,
    val statusMessage: String? = null
)

class BookshelfViewModel(private val container: AppContainer) : ViewModel() {

    private val searchQueryFlow = MutableStateFlow("")
    private val isImportingFlow = MutableStateFlow(false)
    private val statusMessageFlow = MutableStateFlow<String?>(null)

    val ttsStateFlow: StateFlow<ReaderTtsState> = container.readerTts.stateFlow
    val isDriveConnectedFlow: StateFlow<Boolean> = container.googleDrive.authFlow
        .combine(searchQueryFlow) { auth, _ -> auth.isConnected }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), container.googleDrive.authFlow.value.isConnected)

    val uiState: StateFlow<BookshelfUiState> = combine(
        container.bookLibrary.booksFlow,
        searchQueryFlow,
        isImportingFlow,
        statusMessageFlow
    ) { books, query, importing, msg ->
        val trimmed = query.trim()
        val filtered = if (trimmed.isEmpty()) {
            books
        } else {
            books.filter {
                it.title.contains(trimmed, ignoreCase = true) ||
                    it.author.contains(trimmed, ignoreCase = true)
            }
        }
        BookshelfUiState(
            books = filtered,
            searchQuery = query,
            isImporting = importing,
            statusMessage = msg
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BookshelfUiState())

    fun updateSearchQuery(query: String) {
        searchQueryFlow.value = query
    }

    fun consumeStatusMessage() {
        statusMessageFlow.value = null
    }

    fun importBook(uri: Uri, onImported: (String) -> Unit = {}) {
        if (isImportingFlow.value) return
        isImportingFlow.value = true
        viewModelScope.launch {
            val res = container.bookImportManager.importFromUri(uri)
            isImportingFlow.value = false
            res.fold(
                onSuccess = { record ->
                    statusMessageFlow.value = "已导入《${record.title}》"
                    onImported(record.id)
                },
                onFailure = { err ->
                    statusMessageFlow.value = "导入失败: ${err.message ?: "不支持的格式或文件损坏"}"
                }
            )
        }
    }

    fun deleteBook(bookId: String) {
        if (container.readerTts.stateFlow.value.bookId == bookId) {
            container.readerTts.stop()
        }
        container.bookLibrary.deleteBook(bookId)
        statusMessageFlow.update { "已从书架移除" }
    }

    fun stopTts() {
        container.readerTts.stop()
    }

    suspend fun loadCover(record: BookRecord): Bitmap? = container.epubImageLoader.loadCover(record)

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = BookshelfViewModel(container) as T
    }
}
