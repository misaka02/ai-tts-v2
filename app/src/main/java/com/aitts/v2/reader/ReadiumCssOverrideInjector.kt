package com.aitts.v2.reader

import com.aitts.v2.reader.model.ReaderFontFamily
import com.aitts.v2.reader.model.ReaderImageFilter
import com.aitts.v2.reader.model.ReaderSettings
import com.aitts.v2.reader.model.ReaderTextAlign
import org.json.JSONObject

/**
 * Generates idempotent CSS + JS overrides injected into Readium WebViews to enforce typography controls
 * that ReadiumCSS (`cjk-horizontal` / base) ignores or locks behind built-in conditions.
 */
object ReadiumCssOverrideInjector {

    fun buildOverrideCss(
        settings: ReaderSettings,
        resolvedTextHex: String,
        resolvedBgHex: String,
        hasCustomFont: Boolean
    ): String {
        val sb = StringBuilder()
        val fontStack = resolveFontStack(settings.fontFamily, hasCustomFont)
        val weight = settings.fontWeight.coerceIn(300, 700)
        val headingWeight = (weight + 200).coerceIn(600, 900)

        sb.append("html, body { background-color: $resolvedBgHex !important; color: $resolvedTextHex !important; }\n")
        sb.append("h1, h2, h3, h4, h5, h6 { color: $resolvedTextHex !important; font-weight: $headingWeight !important; }\n")
        sb.append("body, p, div, li, span, dd { font-weight: $weight !important; }\n")

        if (hasCustomFont || !settings.publisherStyles) {
            sb.append("body, p, div, li, span, h1, h2, h3, h4 { font-family: $fontStack !important; }\n")
        }

        if (!settings.publisherStyles) {
            appendBodyTypographyCss(sb, settings)
        }
        appendLayoutAndFilterCss(sb, settings)
        return sb.toString()
    }

    private fun appendBodyTypographyCss(sb: StringBuilder, settings: ReaderSettings) {
        val lh = "%.2f".format(java.util.Locale.US, settings.lineHeightMultiplier.coerceIn(1.1f, 2.5f))
        sb.append("body, p, div, li { line-height: $lh !important; }\n")
        if (settings.letterSpacingEm > 0.005f) {
            val ls = "%.2f".format(java.util.Locale.US, settings.letterSpacingEm.coerceIn(0f, 0.5f))
            sb.append("body, p, div, li, span { letter-spacing: ${ls}em !important; }\n")
        }
        if (settings.wordSpacingEm > 0.005f) {
            val ws = "%.2f".format(java.util.Locale.US, settings.wordSpacingEm.coerceIn(0f, 0.6f))
            sb.append("body, p, div, li, span { word-spacing: ${ws}em !important; }\n")
        }
        val indent = "%.1f".format(java.util.Locale.US, settings.effectiveIndentEm().coerceIn(0f, 3f))
        val pSpace = settings.paragraphSpacingDp.toInt().coerceIn(0, 40)
        sb.append("p { text-indent: ${indent}em !important; margin-top: ${pSpace}px !important; margin-bottom: ${pSpace}px !important; }\n")

        val alignCss = when (settings.textAlign) {
            ReaderTextAlign.START -> "left"
            ReaderTextAlign.JUSTIFY -> "justify"
            ReaderTextAlign.CENTER -> "center"
            ReaderTextAlign.AUTO -> null
        }
        if (alignCss != null) {
            sb.append("body, p, div { text-align: $alignCss !important; }\n")
        }
        if (settings.hyphens) {
            sb.append("body, p, div { -webkit-hyphens: auto !important; hyphens: auto !important; }\n")
        }
    }

    private fun appendLayoutAndFilterCss(sb: StringBuilder, settings: ReaderSettings) {
        if (settings.verticalText) {
            sb.append("html, body { writing-mode: vertical-rl !important; -webkit-writing-mode: vertical-rl !important; }\n")
        }
        when (settings.nightImageFilter) {
            ReaderImageFilter.DARKEN ->
                sb.append("img, svg image { -webkit-filter: brightness(0.76) !important; filter: brightness(0.76) !important; }\n")
            ReaderImageFilter.INVERT ->
                sb.append("img, svg image { -webkit-filter: invert(100%) !important; filter: invert(100%) !important; }\n")
            ReaderImageFilter.NONE -> Unit
        }
    }

    private fun resolveFontStack(family: ReaderFontFamily, hasCustomFont: Boolean): String {
        if (hasCustomFont) return "\"AiTtsCustomFont\", \"Noto Sans CJK SC\", sans-serif"
        return when (family) {
            ReaderFontFamily.SERIF -> "\"Noto Serif CJK SC\", \"Source Han Serif SC\", \"Songti SC\", \"STSong\", serif"
            ReaderFontFamily.SANS -> "\"Noto Sans CJK SC\", \"PingFang SC\", \"MiSans\", \"HarmonyOS Sans SC\", sans-serif"
            ReaderFontFamily.MONOSPACE -> "\"Noto Sans Mono CJK SC\", \"JetBrains Mono\", monospace"
        }
    }

    fun buildInjectScript(
        css: String,
        hasCustomFont: Boolean,
        fontToken: String,
        scriptConvertMode: Int
    ): String {
        val quotedCss = JSONObject.quote(css)
        val quotedToken = JSONObject.quote(fontToken)
        return """
        (function() {
          var styleId = 'aitts-user-override';
          var st = document.getElementById(styleId);
          if (!st) {
            st = document.createElement('style');
            st.id = styleId;
            (document.head || document.documentElement).appendChild(st);
          }
          st.textContent = $quotedCss;

          var needCustom = ${if (hasCustomFont) "true" else "false"};
          var token = $quotedToken;
          if (needCustom && window.__aittsFontToken !== token && window.AiTtsReaderBridge && window.AiTtsReaderBridge.getCustomFontBase64) {
            try {
              var b64 = window.AiTtsReaderBridge.getCustomFontBase64();
              if (b64 && b64.length > 0) {
                var bin = atob(b64);
                var bytes = new Uint8Array(bin.length);
                for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
                var face = new FontFace('AiTtsCustomFont', bytes);
                face.load().then(function(loaded) {
                  document.fonts.add(loaded);
                  window.__aittsFontToken = token;
                });
              }
            } catch (e) {}
          }

          var mode = $scriptConvertMode;
          if (window.__aittsScriptMode !== mode && document.body) {
            window.__aittsScriptMode = mode;
            if (window.AiTtsReaderBridge && window.AiTtsReaderBridge.convertScript) {
              var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
              var node;
              while ((node = walker.nextNode())) {
                if (node.parentNode && (node.parentNode.tagName === 'SCRIPT' || node.parentNode.tagName === 'STYLE')) continue;
                if (node.__aittsOrig === undefined) node.__aittsOrig = node.nodeValue;
                if (mode === 0) {
                  node.nodeValue = node.__aittsOrig;
                } else {
                  node.nodeValue = window.AiTtsReaderBridge.convertScript(node.__aittsOrig, mode);
                }
              }
            }
          }
        })();
        """.trimIndent()
    }
}
