package com.aitts.v2.ui.reader

import com.aitts.v2.reader.BookImageItem
import com.aitts.v2.reader.LoadedBookImage
import com.aitts.v2.reader.model.BookHighlight
import com.aitts.v2.reader.model.BookIndex
import com.aitts.v2.reader.model.BookRecord
import com.aitts.v2.reader.model.BookSearchHit
import com.aitts.v2.reader.model.ChapterContent
import com.aitts.v2.reader.model.ChapterRef
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

enum class ReaderSheet { TOC, SEARCH, APPEARANCE, TTS }

data class ReaderUiState(
    val book: BookRecord? = null,
    val index: BookIndex? = null,
    val chapter: ChapterContent? = null,
    val readiumPublication: Publication? = null,
    val readiumInitialLocator: Locator? = null,
    val readiumToc: List<ChapterRef> = emptyList(),
    val readiumPositions: List<Locator> = emptyList(),
    val bookImages: List<BookImageItem> = emptyList(),
    val activeLightboxImage: LoadedBookImage? = null,
    val activeLightboxIndex: Int = -1,
    val isExportingImages: Boolean = false,
    val imageExportProgress: String = "",
    val currentChapterIndex: Int = 0,
    val currentChapterTitle: String = "",
    val currentPositionIndex: Int = 0,
    val currentProgression: Float = 0f,
    val currentLocatorJson: String = "",
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val visibleBlockIndex: Int = 0,
    val pendingScrollBlockIndex: Int? = null,
    val pendingScrollCharOffset: Int = 0,
    val pendingLandOnLastPage: Boolean = false,
    val showChrome: Boolean = false,
    val activeSheet: ReaderSheet? = null,
    val editingHighlight: BookHighlight? = null,
    val selectedHighlightMenu: BookHighlight? = null,
    val longPressParagraph: Pair<Int, String>? = null,
    val searchQuery: String = "",
    val searchHits: List<BookSearchHit> = emptyList(),
    val searchLocators: List<String> = emptyList(),
    val isSearching: Boolean = false,
    val statusMessage: String? = null
)
