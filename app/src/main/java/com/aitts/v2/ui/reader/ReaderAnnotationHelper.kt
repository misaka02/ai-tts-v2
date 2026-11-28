package com.aitts.v2.ui.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.aitts.v2.di.AppContainer
import com.aitts.v2.reader.BookNotesExporter
import com.aitts.v2.reader.addHighlight
import com.aitts.v2.reader.model.BookHighlight
import com.aitts.v2.reader.removeHighlight
import com.aitts.v2.reader.updateHighlightColor
import com.aitts.v2.reader.updateHighlightNote
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/**
 * Coordinates bookmarks, EPUB highlights, notes, Markdown note export, and plain-text copy.
 */
internal class ReaderAnnotationHelper(
    private val container: AppContainer,
    private val bookId: String,
    private val state: MutableStateFlow<ReaderUiState>
) {
    fun toggleBookmark(overrideSnippet: String? = null, overrideBlock: Int? = null) {
        val st = state.value
        val blkIdx = overrideBlock ?: st.visibleBlockIndex
        val snippet = overrideSnippet ?: if (st.readiumPublication != null) {
            container.readiumEngine.parseLocator(st.currentLocatorJson)?.text?.highlight?.ifBlank { null }
                ?: st.currentChapterTitle
        } else {
            val blk = st.chapter?.blocks?.firstOrNull { it.index >= blkIdx && !it.speakableText.isNullOrBlank() }
            blk?.speakableText ?: st.chapter?.title ?: return
        }
        val added = container.bookLibrary.toggleBookmark(
            bookId, st.currentChapterIndex, blkIdx, st.currentChapterTitle, snippet, st.currentLocatorJson
        )
        state.update { it.copy(statusMessage = if (added) "已添加书签" else "已取消当前位置书签") }
    }

    fun removeBookmark(id: String) = container.bookLibrary.removeBookmark(bookId, id)

    fun createHighlight(quote: String, locatorJson: String, colorHex: Long, openNote: Boolean) {
        val item = BookHighlight(
            id = UUID.randomUUID().toString().take(8),
            chapterIndex = state.value.currentChapterIndex,
            locatorJson = locatorJson,
            quote = quote.take(300),
            colorHex = colorHex,
            createdAt = System.currentTimeMillis()
        )
        container.bookLibrary.addHighlight(bookId, item)
        state.update {
            it.copy(
                editingHighlight = if (openNote) item else null,
                statusMessage = if (openNote) null else "已添加划线高亮"
            )
        }
    }

    fun handleHighlightAction(
        editNoteTarget: BookHighlight? = null,
        saveId: String? = null,
        saveNote: String = "",
        removeId: String? = null,
        tappedId: String? = null,
        colorUpdate: Pair<String, Long>? = null,
        dismissMenu: Boolean = false
    ) {
        when {
            tappedId != null -> {
                val found = state.value.book?.highlights?.firstOrNull { it.id == tappedId }
                state.update { it.copy(selectedHighlightMenu = found) }
            }
            dismissMenu -> state.update { it.copy(selectedHighlightMenu = null) }
            colorUpdate != null -> {
                container.bookLibrary.updateHighlightColor(bookId, colorUpdate.first, colorUpdate.second)
                state.update { it.copy(selectedHighlightMenu = null, statusMessage = "已更换高亮颜色") }
            }
            removeId != null -> {
                container.bookLibrary.removeHighlight(bookId, removeId)
                state.update { it.copy(selectedHighlightMenu = null, statusMessage = "已删除划线") }
            }
            saveId != null -> {
                container.bookLibrary.updateHighlightNote(bookId, saveId, saveNote)
                state.update { it.copy(editingHighlight = null, selectedHighlightMenu = null, statusMessage = "笔记已保存") }
            }
            else -> state.update { it.copy(editingHighlight = editNoteTarget, selectedHighlightMenu = null) }
        }
    }

    fun copyPlainText(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("摘录", text))
        state.update { it.copy(statusMessage = "已复制到剪贴板") }
    }

    fun exportNotesToClipboard(context: Context) {
        val book = state.value.book ?: return
        BookNotesExporter.copyMarkdown(context, book)
        state.update { it.copy(statusMessage = "已复制《${book.title}》Markdown 笔记") }
    }

    fun shareNotes(context: Context) {
        val book = state.value.book ?: return
        val intent = BookNotesExporter.buildShareIntent(book)
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
