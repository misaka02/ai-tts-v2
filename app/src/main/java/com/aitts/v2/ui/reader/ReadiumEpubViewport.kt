package com.aitts.v2.ui.reader

import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.compose.material3.MaterialTheme
import com.aitts.v2.reader.ReadiumEngine
import com.aitts.v2.reader.model.BookHighlight
import com.aitts.v2.reader.model.ReaderSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

private const val MENU_HIGHLIGHT_YELLOW = 1001
private const val MENU_HIGHLIGHT_BLUE = 1002
private const val MENU_NOTE = 1003
private const val MENU_SPEAK = 1004
private const val MENU_SEARCH = 1005

/**
 * Hosts Readium 3.0.0 `EpubNavigatorFragment` inside Compose for standards-compliant
 * EPUB 2/3 layout, CSS styling, tables, ruby furigana, text selection highlights, and TTS sync.
 */
@Composable
fun ReadiumEpubViewport(
    bookId: String,
    publication: Publication,
    readiumEngine: ReadiumEngine,
    initialLocator: Locator?,
    readerSettings: ReaderSettings,
    colors: ResolvedReaderColors,
    highlights: List<BookHighlight>,
    activeTtsSentence: String,
    pageTurnEvents: Flow<Int>,
    goToLocatorEvents: Flow<Locator>,
    onLocatorChanged: (chapterIdx: Int, locatorJson: String, totalProgression: Float, chapterTitle: String) -> Unit,
    onTapCenter: () -> Unit,
    onCreateHighlight: (quote: String, locatorJson: String, colorHex: Long, openNoteDialog: Boolean) -> Unit,
    onSpeakSelection: (quote: String, locatorJson: String) -> Unit,
    onSearchSelection: (quote: String) -> Unit
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity ?: return
    val scope = rememberCoroutineScope()
    val containerId = remember(bookId) { View.generateViewId() }
    val appIsDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val appBgArgb = MaterialTheme.colorScheme.background.toArgb()
    val appTextArgb = MaterialTheme.colorScheme.onBackground.toArgb()

    val currentTapCenter by rememberUpdatedState(onTapCenter)
    val currentLocatorCb by rememberUpdatedState(onLocatorChanged)
    val currentCreateHighlight by rememberUpdatedState(onCreateHighlight)
    val currentSpeakSelection by rememberUpdatedState(onSpeakSelection)
    val currentSearchSelection by rememberUpdatedState(onSearchSelection)

    var navigatorRef by remember(bookId, publication) { mutableStateOf<EpubNavigatorFragment?>(null) }
    var containerViewRef by remember(bookId, publication) { mutableStateOf<FragmentContainerView?>(null) }

    val prefs = remember(readerSettings, appIsDark, appBgArgb, appTextArgb) {
        readiumEngine.buildPreferences(readerSettings, appIsDark, appBgArgb, appTextArgb)
    }

    AndroidView(
        factory = { ctx ->
            FragmentContainerView(ctx).apply {
                id = containerId
                containerViewRef = this
            }
        },
        modifier = Modifier.fillMaxSize()
    )

    DisposableEffect(bookId, publication, containerViewRef) {
        val hostView = containerViewRef ?: return@DisposableEffect onDispose {}
        val fm = activity.supportFragmentManager
        val fragmentTag = "readium_epub_$bookId"
        val navigatorHolder = arrayOfNulls<EpubNavigatorFragment>(1)

        val selectionCallback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(Menu.NONE, MENU_HIGHLIGHT_YELLOW, 0, "黄高亮")
                menu.add(Menu.NONE, MENU_HIGHLIGHT_BLUE, 1, "蓝高亮")
                menu.add(Menu.NONE, MENU_NOTE, 2, "写笔记")
                menu.add(Menu.NONE, MENU_SPEAK, 3, "从此朗读")
                menu.add(Menu.NONE, MENU_SEARCH, 4, "全书检索")
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                val nav = navigatorHolder[0] ?: return false
                if (item.itemId !in listOf(MENU_HIGHLIGHT_YELLOW, MENU_HIGHLIGHT_BLUE, MENU_NOTE, MENU_SPEAK, MENU_SEARCH)) {
                    return false
                }
                scope.launch {
                    val sel = nav.currentSelection() ?: return@launch
                    val quote = sel.locator.text.highlight.orEmpty().trim()
                    val locJson = sel.locator.toJSON().toString()
                    nav.clearSelection()
                    mode.finish()
                    when (item.itemId) {
                        MENU_HIGHLIGHT_YELLOW -> currentCreateHighlight(quote, locJson, 0xFFFFD54FL, false)
                        MENU_HIGHLIGHT_BLUE -> currentCreateHighlight(quote, locJson, 0xFF81D4FAL, false)
                        MENU_NOTE -> currentCreateHighlight(quote, locJson, 0xFFFFD54FL, true)
                        MENU_SPEAK -> currentSpeakSelection(quote, locJson)
                        MENU_SEARCH -> if (quote.isNotBlank()) currentSearchSelection(quote)
                    }
                }
                return true
            }

            override fun onDestroyActionMode(mode: ActionMode) = Unit
        }

        val factory = EpubNavigatorFactory(publication = publication)
        val fragFactory = factory.createFragmentFactory(
            initialLocator = initialLocator,
            initialPreferences = prefs,
            configuration = EpubNavigatorFragment.Configuration(
                selectionActionModeCallback = selectionCallback
            )
        )
        val frag = fragFactory.instantiate(activity.classLoader, EpubNavigatorFragment::class.java.name)
            as EpubNavigatorFragment
        navigatorHolder[0] = frag

        val existing = fm.findFragmentByTag(fragmentTag)
        fm.beginTransaction().apply {
            if (existing != null) remove(existing)
            replace(hostView.id, frag, fragmentTag)
        }.commitNowAllowingStateLoss()

        frag.addInputListener(object : InputListener {
            override fun onTap(event: TapEvent): Boolean {
                val width = hostView.width.takeIf { it > 0 } ?: return false
                val fx = event.point.x / width.toFloat()
                if (fx in 0.28f..0.72f) {
                    currentTapCenter()
                    return true
                }
                return false
            }
        })
        frag.addInputListener(DirectionalNavigationAdapter(frag, animatedTransition = true))
        navigatorRef = frag

        onDispose {
            navigatorRef = null
            if (!fm.isStateSaved) {
                fm.findFragmentByTag(fragmentTag)?.let {
                    fm.beginTransaction().remove(it).commitAllowingStateLoss()
                }
            }
        }
    }

    LaunchedEffect(navigatorRef, prefs) {
        navigatorRef?.submitPreferences(prefs)
    }

    LaunchedEffect(navigatorRef) {
        val nav = navigatorRef ?: return@LaunchedEffect
        nav.currentLocator.collect { loc ->
            val chapIdx = readiumEngine.resolveChapterIndex(publication, loc)
            val totalProg = (loc.locations.totalProgression ?: 0.0).toFloat()
            val title = loc.title?.takeIf { it.isNotBlank() }
                ?: publication.readingOrder.getOrNull(chapIdx)?.title
                ?: "第 ${chapIdx + 1} 章"
            currentLocatorCb(chapIdx, loc.toJSON().toString(), totalProg, title)
        }
    }

    LaunchedEffect(navigatorRef, highlights) {
        val nav = navigatorRef ?: return@LaunchedEffect
        val decorations = highlights.mapNotNull { h ->
            val loc = readiumEngine.parseLocator(h.locatorJson) ?: return@mapNotNull null
            Decoration(
                id = h.id,
                locator = loc,
                style = Decoration.Style.Highlight(tint = h.colorHex.toInt())
            )
        }
        nav.applyDecorations(decorations, group = "user-highlights")
    }

    LaunchedEffect(navigatorRef, activeTtsSentence, readerSettings.autoScrollOnTts) {
        val nav = navigatorRef ?: return@LaunchedEffect
        val snippet = activeTtsSentence.trim().take(36)
        if (snippet.isBlank()) {
            nav.applyDecorations(emptyList(), group = "tts-active")
            return@LaunchedEffect
        }
        val hits = readiumEngine.searchPublication(publication, snippet, maxResults = 3)
        val curChap = readiumEngine.resolveChapterIndex(publication, nav.currentLocator.value)
        val matchLoc = hits.firstOrNull { it.hit.chapterIndex == curChap }?.let {
            readiumEngine.parseLocator(it.locatorJson)
        } ?: hits.firstOrNull()?.let { readiumEngine.parseLocator(it.locatorJson) }
        if (matchLoc != null) {
            nav.applyDecorations(
                listOf(Decoration("tts-now", matchLoc, Decoration.Style.Highlight(tint = colors.accent.toArgb()))),
                group = "tts-active"
            )
            if (readerSettings.autoScrollOnTts) {
                nav.go(matchLoc, animated = true)
            }
        }
    }

    LaunchedEffect(navigatorRef, pageTurnEvents) {
        val nav = navigatorRef ?: return@LaunchedEffect
        pageTurnEvents.collect { delta ->
            if (delta < 0) nav.goBackward(animated = true) else nav.goForward(animated = true)
        }
    }

    LaunchedEffect(navigatorRef, goToLocatorEvents) {
        val nav = navigatorRef ?: return@LaunchedEffect
        goToLocatorEvents.collect { loc ->
            nav.go(loc, animated = true)
        }
    }
}
