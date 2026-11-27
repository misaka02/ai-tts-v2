package com.aitts.v2.ui.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.webkit.JavascriptInterface
import android.widget.Toast
import com.aitts.v2.reader.model.ReaderTapZoneMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import java.net.URI

private const val MENU_COPY = 1000
private const val MENU_HIGHLIGHT_YELLOW = 1001
private const val MENU_HIGHLIGHT_BLUE = 1002
private const val MENU_HIGHLIGHT_GREEN = 1003
private const val MENU_HIGHLIGHT_PINK = 1004
private const val MENU_NOTE = 1005
private const val MENU_SPEAK = 1006
private const val MENU_SEARCH = 1007

internal const val IMAGE_TAP_JS = """
(function(){
  if (window.__aiTtsImgWired) return;
  window.__aiTtsImgWired = true;
  document.addEventListener('click', function(ev) {
    if (!window.AiTtsImageBridge) return;
    var t = ev.target;
    var img = t && t.closest ? (t.closest('img') || t.closest('image')) : null;
    if (!img) return;
    var rect = img.getBoundingClientRect ? img.getBoundingClientRect() : null;
    if (rect && rect.width > 0 && rect.height > 0 && rect.width < 32 && rect.height < 32) return;
    var src = img.getAttribute('src') || img.getAttribute('href') || img.getAttribute('xlink:href') || '';
    if (!src) return;
    ev.preventDefault();
    ev.stopPropagation();
    window.AiTtsImageBridge.onImageTap(src);
  }, true);
})();
"""

internal class ReadiumImageBridge(
    private val baseHref: String,
    private val onResolved: (String) -> Unit
) {
    @JavascriptInterface
    fun onImageTap(rawSrc: String) {
        val clean = rawSrc.trim()
        if (clean.isEmpty() || clean.startsWith("data:", ignoreCase = true)) return
        onResolved(resolveHref(baseHref, clean))
    }

    private fun resolveHref(base: String, target: String): String {
        val stripped = target.removePrefix("https://readium/").removePrefix("http://readium/")
        if (target.startsWith("https://readium/") || target.startsWith("http://readium/")) {
            return stripped.trimStart('/')
        }
        return runCatching {
            val normBase = "/" + base.trimStart('/')
            URI(normBase).resolve(stripped).path.trimStart('/')
        }.getOrDefault(stripped.trimStart('/'))
    }
}

internal fun handleEpubTapZone(
    mode: ReaderTapZoneMode,
    fx: Float,
    nav: EpubNavigatorFragment,
    onCenter: () -> Unit
): Boolean = when (mode) {
    ReaderTapZoneMode.STANDARD -> when {
        fx < 0.28f -> nav.goBackward(animated = true)
        fx > 0.72f -> nav.goForward(animated = true)
        else -> { onCenter(); true }
    }
    ReaderTapZoneMode.LEFT_HAND -> when {
        fx < 0.28f -> nav.goForward(animated = true)
        fx > 0.72f -> nav.goBackward(animated = true)
        else -> { onCenter(); true }
    }
    ReaderTapZoneMode.ALL_NEXT -> nav.goForward(animated = true)
    ReaderTapZoneMode.SWIPE_ONLY -> { onCenter(); true }
}

internal fun buildSelectionActionCallback(
    context: Context,
    scope: CoroutineScope,
    navigatorProvider: () -> EpubNavigatorFragment?,
    onHighlight: (String, String, Long, Boolean) -> Unit,
    onSpeak: (String, String) -> Unit,
    onSearch: (String) -> Unit
): ActionMode.Callback = object : ActionMode.Callback {
    private val customIds = setOf(
        MENU_COPY, MENU_HIGHLIGHT_YELLOW, MENU_HIGHLIGHT_BLUE,
        MENU_HIGHLIGHT_GREEN, MENU_HIGHLIGHT_PINK, MENU_NOTE, MENU_SPEAK, MENU_SEARCH
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
            when (item.itemId) {
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
            }
        }
        return true
    }

    override fun onDestroyActionMode(mode: ActionMode) = Unit
}
