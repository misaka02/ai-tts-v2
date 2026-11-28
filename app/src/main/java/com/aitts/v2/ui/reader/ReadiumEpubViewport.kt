@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.aitts.v2.ui.reader

import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import com.aitts.v2.reader.ReadiumCssOverrideInjector
import com.aitts.v2.reader.ReadiumEngine
import com.aitts.v2.reader.model.BookHighlight
import com.aitts.v2.reader.model.ReaderPageAnimation
import com.aitts.v2.reader.model.ReaderReadingMode
import com.aitts.v2.reader.model.ReaderSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import java.io.File

/**
 * Hosts Readium 3.0.0 `EpubNavigatorFragment` inside Compose with CSS/FontFace override injection,
 * multi-chapter JS image tap bridge, interactive highlights, auto-scroll, and running bars.
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
    chapterTitle: String,
    positionIndex: Int,
    positionsTotal: Int,
    totalProgression: Float,
    isTtsActive: Boolean,
    activeTtsSentence: String,
    autoScrollActive: Boolean,
    autoScrollPaused: Boolean,
    pageTurnEvents: Flow<Int>,
    goToLocatorEvents: Flow<Locator>,
    onLocatorChanged: (chapterIdx: Int, locatorJson: String, totalProg: Float, title: String, posIdx: Int) -> Unit,
    onTapCenter: () -> Unit,
    onCreateHighlight: (quote: String, locatorJson: String, colorHex: Long, openNoteDialog: Boolean) -> Unit,
    onHighlightTapped: (highlightId: String) -> Unit,
    onImageTapped: (resourcePath: String) -> Unit,
    onSpeakSelection: (quote: String, locatorJson: String) -> Unit,
    onSearchSelection: (quote: String) -> Unit
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity ?: return
    val scope = rememberCoroutineScope()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val containerId = remember(bookId) { View.generateViewId() }
    val appIsDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val appBgArgb = MaterialTheme.colorScheme.background.toArgb()
    val appTextArgb = MaterialTheme.colorScheme.onBackground.toArgb()

    val currentSettings by rememberUpdatedState(readerSettings)
    val currentColors by rememberUpdatedState(colors)
    val currentTapCenter by rememberUpdatedState(onTapCenter)
    val currentLocatorCb by rememberUpdatedState(onLocatorChanged)
    val currentCreateHighlight by rememberUpdatedState(onCreateHighlight)
    val currentHighlightTap by rememberUpdatedState(onHighlightTapped)
    val currentImageTap by rememberUpdatedState(onImageTapped)
    val currentSpeakSelection by rememberUpdatedState(onSpeakSelection)
    val currentSearchSelection by rememberUpdatedState(onSearchSelection)

    var navigatorRef by remember(bookId, publication) { mutableStateOf<EpubNavigatorFragment?>(null) }
    var containerViewRef by remember(bookId, publication) { mutableStateOf<FragmentContainerView?>(null) }
    var latestLocator by remember(bookId, publication) { mutableStateOf(initialLocator) }
    var currentHref by remember(bookId, publication) { mutableStateOf("") }

    var debouncedSettings by remember { mutableStateOf(readerSettings) }
    LaunchedEffect(readerSettings) {
        delay(90L)
        debouncedSettings = readerSettings
    }

    val prefs = remember(debouncedSettings, appIsDark, appBgArgb, appTextArgb) {
        readiumEngine.buildPreferences(debouncedSettings, appIsDark, appBgArgb, appTextArgb)
    }

    fun buildRuntimeJs(st: ReaderSettings, cl: ResolvedReaderColors): String {
        val customPath = st.customFontPath.trim()
        val hasCustom = customPath.isNotBlank() && File(customPath).isFile
        val css = ReadiumCssOverrideInjector.buildOverrideCss(
            settings = st,
            resolvedTextHex = toHexRgb(cl.text.toArgb()),
            resolvedBgHex = toHexRgb(cl.background.toArgb()),
            hasCustomFont = hasCustom
        )
        return ReadiumCssOverrideInjector.buildInjectScript(
            css = css,
            hasCustomFont = hasCustom,
            fontToken = customPath,
            scriptConvertMode = st.scriptConvert.code
        )
    }

    suspend fun syncWebView(nav: EpubNavigatorFragment) {
        runCatching { nav.evaluateJavascript(IMAGE_TAP_JS) }
        runCatching { nav.evaluateJavascript(buildRuntimeJs(currentSettings, currentColors)) }
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        if (readerSettings.showHeaderBar) {
            ReaderMiniHeaderBar(
                chapterTitle.ifBlank { publication.metadata.title.orEmpty() },
                readerSettings.horizontalPaddingDp,
                colors.secondaryText
            )
        }
        AndroidView(
            factory = { ctx -> FragmentContainerView(ctx).apply { id = containerId; containerViewRef = this } },
            modifier = Modifier.fillMaxWidth().weight(1f).padding(vertical = readerSettings.verticalPaddingDp.coerceIn(4f, 40f).dp)
        )
        if (readerSettings.showFooterBar) {
            val leftLabel = if (positionsTotal > 0 && positionIndex > 0) "第 $positionIndex/$positionsTotal 位置" else "EPUB 精排"
            val pct = (totalProgression * 100f).toInt().coerceIn(0, 100)
            val rightLabel = if (isTtsActive) "朗读中 · $pct%" else "全书 $pct%"
            ReaderMiniFooterBar(
                leftLabel = leftLabel,
                rightLabel = rightLabel,
                showBatteryTime = readerSettings.showBatteryTime,
                horizontalPaddingDp = readerSettings.horizontalPaddingDp,
                textColor = colors.secondaryText,
                accentColor = colors.accent,
                highlightRight = isTtsActive
            )
        }
    }

    DisposableEffect(bookId, publication, containerViewRef) {
        val hostView = containerViewRef ?: return@DisposableEffect onDispose {}
        val fm = activity.supportFragmentManager
        val fragmentTag = "readium_epub_$bookId"
        val navigatorHolder = arrayOfNulls<EpubNavigatorFragment>(1)

        val selectionCallback = buildSelectionActionCallback(
            context = context,
            scope = scope,
            navigatorProvider = { navigatorHolder[0] },
            onHighlight = { q, loc, color, openNote -> currentCreateHighlight(q, loc, color, openNote) },
            onSpeak = { q, loc -> currentSpeakSelection(q, loc) },
            onSearch = { q -> currentSearchSelection(q) }
        )

        val navConfig = EpubNavigatorFragment.Configuration(
            selectionActionModeCallback = selectionCallback,
            shouldApplyInsetsPadding = false
        ).apply {
            registerJavascriptInterface("AiTtsReaderBridge") { link ->
                ReadiumJsBridge(
                    baseHref = link.href.toString(),
                    customFontPathProvider = { currentSettings.customFontPath },
                    onImageResolved = { resolved -> mainHandler.post { currentImageTap(resolved) } }
                )
            }
        }

        val paginationListener = object : EpubNavigatorFragment.PaginationListener {
            override fun onPageLoaded() {
                navigatorHolder[0]?.let { nav -> scope.launch { syncWebView(nav) } }
            }

            override fun onPageChanged(pageIndex: Int, totalPages: Int, locator: Locator) = Unit
        }

        val startLoc = latestLocator ?: initialLocator
        val fragFactory = EpubNavigatorFactory(publication).createFragmentFactory(
            initialLocator = startLoc,
            initialPreferences = prefs,
            paginationListener = paginationListener,
            configuration = navConfig
        )
        val frag = fragFactory.instantiate(activity.classLoader, EpubNavigatorFragment::class.java.name) as EpubNavigatorFragment
        navigatorHolder[0] = frag

        val existing = fm.findFragmentByTag(fragmentTag)
        fm.beginTransaction().apply {
            if (existing != null) remove(existing)
            replace(hostView.id, frag, fragmentTag)
        }.commitNowAllowingStateLoss()

        frag.addInputListener(object : InputListener {
            override fun onTap(event: TapEvent): Boolean {
                val width = hostView.width.takeIf { it > 0 } ?: return false
                return handleEpubTapZone(currentSettings, event.point.x / width.toFloat(), frag) { currentTapCenter() }
            }
        })
        frag.addDecorationListener(
            "user-highlights",
            object : DecorableNavigator.Listener {
                override fun onDecorationActivated(event: DecorableNavigator.OnActivatedEvent): Boolean {
                    currentHighlightTap(event.decoration.id)
                    return true
                }
            }
        )
        navigatorRef = frag

        onDispose {
            navigatorRef = null
            if (!fm.isStateSaved) {
                fm.findFragmentByTag(fragmentTag)?.let { fm.beginTransaction().remove(it).commitAllowingStateLoss() }
            }
        }
    }

    LaunchedEffect(navigatorRef, prefs) {
        val nav = navigatorRef ?: return@LaunchedEffect
        nav.submitPreferences(prefs)
        delay(80L)
        syncWebView(nav)
    }

    LaunchedEffect(navigatorRef, debouncedSettings, colors, currentHref) {
        val nav = navigatorRef ?: return@LaunchedEffect
        delay(40L)
        syncWebView(nav)
    }

    LaunchedEffect(navigatorRef) {
        val nav = navigatorRef ?: return@LaunchedEffect
        nav.currentLocator.collect { loc ->
            latestLocator = loc
            currentHref = loc.href.toString().substringBefore('#')
            val chapIdx = readiumEngine.resolveChapterIndex(publication, loc)
            val totalProg = (loc.locations.totalProgression ?: 0.0).toFloat()
            val posIdx = loc.locations.position ?: 0
            val title = loc.title?.takeIf { it.isNotBlank() }
                ?: publication.readingOrder.getOrNull(chapIdx)?.title ?: "第 ${chapIdx + 1} 章"
            currentLocatorCb(chapIdx, loc.toJSON().toString(), totalProg, title, posIdx)
        }
    }

    LaunchedEffect(navigatorRef, highlights) {
        val nav = navigatorRef ?: return@LaunchedEffect
        val decorations = highlights.mapNotNull { h ->
            val loc = readiumEngine.parseLocator(h.locatorJson) ?: return@mapNotNull null
            Decoration(id = h.id, locator = loc, style = Decoration.Style.Highlight(tint = h.colorHex.toInt(), isActive = true))
        }
        nav.applyDecorations(decorations, group = "user-highlights")
    }

    LaunchedEffect(navigatorRef, activeTtsSentence, readerSettings.autoScrollOnTts) {
        val nav = navigatorRef ?: return@LaunchedEffect
        val snippet = activeTtsSentence.trim().take(36)
        if (snippet.isBlank()) { nav.applyDecorations(emptyList(), group = "tts-active"); return@LaunchedEffect }
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
            if (readerSettings.autoScrollOnTts) nav.go(matchLoc, animated = true)
        }
    }

    LaunchedEffect(navigatorRef, autoScrollActive, autoScrollPaused, readerSettings.autoScrollSpeed, readerSettings.readingMode) {
        val nav = navigatorRef ?: return@LaunchedEffect
        if (!autoScrollActive || autoScrollPaused) return@LaunchedEffect
        val spd = readerSettings.autoScrollSpeed.coerceIn(1, 10)
        while (true) {
            if (readerSettings.readingMode == ReaderReadingMode.SCROLL) {
                delay(45L)
                runCatching { nav.evaluateJavascript("window.scrollBy(0, ${spd + 1});") }
            } else {
                val intervalMs = (14_000L - spd * 1100L).coerceIn(2500L, 13_000L)
                delay(intervalMs)
                val anim = readerSettings.pageAnimation == ReaderPageAnimation.SLIDE
                nav.goForward(animated = anim)
            }
        }
    }

    LaunchedEffect(navigatorRef, pageTurnEvents) {
        val nav = navigatorRef ?: return@LaunchedEffect
        pageTurnEvents.collect { delta ->
            val anim = currentSettings.pageAnimation == ReaderPageAnimation.SLIDE
            if (delta < 0) nav.goBackward(animated = anim) else nav.goForward(animated = anim)
        }
    }

    LaunchedEffect(navigatorRef, goToLocatorEvents) {
        val nav = navigatorRef ?: return@LaunchedEffect
        goToLocatorEvents.collect { loc ->
            nav.go(loc, animated = currentSettings.pageAnimation == ReaderPageAnimation.SLIDE)
        }
    }
}
