package com.aitts.v2.ui.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.webkit.JavascriptInterface
import android.widget.Toast
import com.aitts.v2.reader.ChineseScriptConverter
import com.aitts.v2.reader.model.ReaderPageAnimation
import com.aitts.v2.reader.model.ReaderReadingMode
import com.aitts.v2.reader.model.ReaderScriptConvert
import com.aitts.v2.reader.model.ReaderSettings
import com.aitts.v2.reader.model.ReaderTapZoneMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import java.io.File
import java.net.URI

private const val MENU_COPY = 1000
private const val MENU_HIGHLIGHT_YELLOW = 1001
private const val MENU_HIGHLIGHT_BLUE = 1002
private const val MENU_HIGHLIGHT_GREEN = 1003
private const val MENU_HIGHLIGHT_PINK = 1004
private const val MENU_NOTE = 1005
private const val MENU_SPEAK = 1006
private const val MENU_SEARCH = 1007
private const val MENU_LOOKUP = 1008
private const val MAX_CUSTOM_FONT_BYTES = 24L * 1024 * 1024

internal const val READER_INTERACTION_JS = """
(function(){
  if (window.__aiTtsImgWired) return;
  window.__aiTtsImgWired = true;
  document.addEventListener('click', function(ev) {
    var bridge = window.AiTtsReaderBridge;
    if (!bridge) return;
    var t = ev.target;
    var link = t && t.closest ? t.closest('a') : null;
    if (link && bridge.onFootnoteTap) {
      var epType = link.getAttribute('epub:type') || '';
      if (epType.indexOf('noteref') >= 0 || link.classList.contains('noteref')) {
        var href = link.getAttribute('href') || '';
        if (href) {
          ev.preventDefault();
          ev.stopPropagation();
          bridge.onFootnoteTap(href);
          return;
        }
      }
    }
    if (!bridge.onImageTap) return;
    var bodyCls = document.body ? document.body.classList : null;
    if (bodyCls && (bodyCls.contains('p-cover') || bodyCls.contains('p-image'))) return;
    var img = t && t.closest ? (t.closest('img') || t.closest('image')) : null;
    if (!img) return;
    var rect = img.getBoundingClientRect ? img.getBoundingClientRect() : null;
    if (rect && rect.width > 0 && rect.height > 0) {
      if (rect.width < 32 && rect.height < 32) return;
      if (rect.width >= window.innerWidth * 0.82 && rect.height >= window.innerHeight * 0.55) return;
    }
    var src = img.getAttribute('src') || img.getAttribute('href') || img.getAttribute('xlink:href') || '';
    if (!src) return;
    ev.preventDefault();
    ev.stopPropagation();
    bridge.onImageTap(src);
  }, true);
})();
"""

internal class ReadiumJsBridge(
    private val baseHref: String,
    private val customFontPathProvider: () -> String,
    private val onImageResolved: (String) -> Unit,
    private val onFootnoteResolved: (String) -> Unit = {}
) {
    @Volatile
    private var cachedFontPath: String = ""

    @Volatile
    private var cachedFontBase64: String = ""

    @JavascriptInterface
    fun onImageTap(rawSrc: String) {
        val clean = rawSrc.trim()
        if (clean.isEmpty() || clean.startsWith("data:", ignoreCase = true)) return
        onImageResolved(resolveHref(baseHref, clean, keepFragment = false))
    }

    @JavascriptInterface
    fun onFootnoteTap(rawHref: String) {
        val clean = rawHref.trim()
        if (clean.isEmpty()) return
        onFootnoteResolved(resolveHref(baseHref, clean, keepFragment = true))
    }

    @JavascriptInterface
    fun getCustomFontBase64(): String {
        val path = customFontPathProvider().trim()
        if (path.isEmpty()) return ""
        if (path == cachedFontPath && cachedFontBase64.isNotEmpty()) return cachedFontBase64
        return runCatching {
            val file = File(path)
            if (!file.isFile || file.length() > MAX_CUSTOM_FONT_BYTES) return ""
            val encoded = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
            cachedFontPath = path
            cachedFontBase64 = encoded
            encoded
        }.getOrDefault("")
    }

    @JavascriptInterface
    fun convertScript(text: String, modeCode: Int): String {
        val mode = ReaderScriptConvert.entries.firstOrNull { it.code == modeCode } ?: ReaderScriptConvert.NONE
        return ChineseScriptConverter.convert(text, mode)
    }

    private fun resolveHref(base: String, target: String, keepFragment: Boolean): String {
        val frag = if (keepFragment) target.substringAfter('#', "") else ""
        val cleanTarget = target.substringBefore('#')
        val stripped = cleanTarget.removePrefix("https://readium/").removePrefix("http://readium/")
        val resolvedPath = if (cleanTarget.startsWith("https://readium/") || cleanTarget.startsWith("http://readium/")) {
            stripped.trimStart('/')
        } else {
            runCatching {
                val normBase = "/" + base.substringBefore('#').trimStart('/')
                URI(normBase).resolve(stripped).path.trimStart('/')
            }.getOrDefault(stripped.trimStart('/'))
        }
        return if (keepFragment && frag.isNotBlank()) "$resolvedPath#$frag" else resolvedPath
    }
}

internal fun handleEpubTapZone(
    settings: ReaderSettings,
    fx: Float,
    fy: Float,
    showChrome: Boolean,
    isRtl: Boolean,
    nav: EpubNavigatorFragment,
    onCenter: () -> Unit
): Boolean {
    if (showChrome || settings.readingMode == ReaderReadingMode.SCROLL) {
        onCenter()
        return true
    }
    if (fx in 0.28f..0.72f && fy in 0.20f..0.80f) {
        onCenter()
        return true
    }
    val anim = settings.pageAnimation == ReaderPageAnimation.SLIDE
    fun turnVisualLeft(): Boolean = if (isRtl) nav.goForward(animated = anim) else nav.goBackward(animated = anim)
    fun turnVisualRight(): Boolean = if (isRtl) nav.goBackward(animated = anim) else nav.goForward(animated = anim)

    return when (settings.tapZoneMode) {
        ReaderTapZoneMode.STANDARD -> when {
            fx < 0.28f -> turnVisualLeft()
            fx > 0.72f -> turnVisualRight()
            else -> { onCenter(); true }
        }
        ReaderTapZoneMode.LEFT_HAND -> when {
            fx < 0.28f -> turnVisualRight()
            fx > 0.72f -> turnVisualLeft()
            else -> { onCenter(); true }
        }
        ReaderTapZoneMode.ALL_NEXT -> nav.goForward(animated = anim)
        ReaderTapZoneMode.SWIPE_ONLY -> { onCenter(); true }
    }
}

internal fun toHexRgb(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

internal fun buildSelectionActionCallback(
    context: Context,
    scope: CoroutineScope,
    navigatorProvider: () -> EpubNavigatorFragment?,
    onHighlight: (String, String, Long, Boolean) -> Unit,
    onSpeak: (String, String) -> Unit,
    onSearch: (String) -> Unit
): ActionMode.Callback = object : ActionMode.Callback {
    private val customIds = setOf(
        MENU_COPY, MENU_HIGHLIGHT_YELLOW, MENU_HIGHLIGHT_BLUE, MENU_HIGHLIGHT_GREEN,
        MENU_HIGHLIGHT_PINK, MENU_NOTE, MENU_SPEAK, MENU_SEARCH, MENU_LOOKUP
    )

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_COPY, 0, "复制")
        menu.add(Menu.NONE, MENU_HIGHLIGHT_YELLOW, 1, "黄高亮")
        menu.add(Menu.NONE, MENU_HIGHLIGHT_BLUE, 2, "蓝高亮")
        menu.add(Menu.NONE, MENU_HIGHLIGHT_GREEN, 3, "绿高亮")
        menu.add(Menu.NONE, MENU_HIGHLIGHT_PINK, 4, "粉高亮")
        menu.add(Menu.NONE, MENU_NOTE, 5, "写笔记")
        menu.add(Menu.NONE, MENU_SPEAK, 6, "从此朗读")
        menu.add(Menu.NONE, MENU_SEARCH, 7, "全书检索")
        menu.add(Menu.NONE, MENU_LOOKUP, 8, "查词翻译")
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        val nav = navigatorProvider() ?: return false
        if (item.itemId !in customIds) return false
        scope.launch {
            val sel = nav.currentSelection() ?: return@launch
            val quote = sel.locator.text.highlight.orEmpty().trim()
            val locJson = sel.locator.toJSON().toString()
            nav.clearSelection()
            mode.finish()
            handleMenuChoice(context, item.itemId, quote, locJson, onHighlight, onSpeak, onSearch)
        }
        return true
    }

    override fun onDestroyActionMode(mode: ActionMode) = Unit
}

private fun handleMenuChoice(
    context: Context,
    itemId: Int,
    quote: String,
    locJson: String,
    onHighlight: (String, String, Long, Boolean) -> Unit,
    onSpeak: (String, String) -> Unit,
    onSearch: (String) -> Unit
) {
    when (itemId) {
        MENU_COPY -> {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("摘录", quote))
            Toast.makeText(context, "已复制选中文字", Toast.LENGTH_SHORT).show()
        }
        MENU_HIGHLIGHT_YELLOW -> onHighlight(quote, locJson, 0xFFFFD54FL, false)
        MENU_HIGHLIGHT_BLUE -> onHighlight(quote, locJson, 0xFF81D4FAL, false)
        MENU_HIGHLIGHT_GREEN -> onHighlight(quote, locJson, 0xFFA5D6A7L, false)
        MENU_HIGHLIGHT_PINK -> onHighlight(quote, locJson, 0xFFF48FB1L, false)
        MENU_NOTE -> onHighlight(quote, locJson, 0xFFFFD54FL, true)
        MENU_SPEAK -> onSpeak(quote, locJson)
        MENU_SEARCH -> if (quote.isNotBlank()) onSearch(quote)
        MENU_LOOKUP -> if (quote.isNotBlank()) openLookup(context, quote)
    }
}

private fun openLookup(context: Context, query: String) {
    val trimmed = query.take(80)
    val processIntent = Intent(Intent.ACTION_PROCESS_TEXT).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_PROCESS_TEXT, trimmed)
        putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (processIntent.resolveActivity(context.packageManager) != null) {
        runCatching { context.startActivity(processIntent); return }
    }
    val url = "https://cn.bing.com/dict/search?q=" + Uri.encode(trimmed)
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
