package com.aitts.v2.reader

import android.content.Context
import androidx.compose.ui.graphics.toArgb
import com.aitts.v2.reader.model.BookSearchHit
import com.aitts.v2.reader.model.ReaderFontFamily
import com.aitts.v2.reader.model.ReaderPaperTheme
import com.aitts.v2.reader.model.ReaderReadingMode
import com.aitts.v2.reader.model.ReaderSettings
import com.aitts.v2.reader.model.ReaderTextAlign
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Color as ReadiumColor
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.TextAlign
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.search.search
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.toUrl
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.File

data class ReadiumTocItem(
    val index: Int,
    val title: String,
    val href: String,
    val depth: Int,
    val locatorJson: String
)

data class ReadiumSearchEntry(
    val hit: BookSearchHit,
    val locatorJson: String
)

/**
 * Wraps Readium Kotlin Toolkit 3.0.0 (`AssetRetriever` + `PublicationOpener`)
 * for industrial EPUB 2/3 rendering, nested TOC extraction, full-text search, and preference mapping.
 */
class ReadiumEngine(context: Context) {

    private val appContext = context.applicationContext
    private val httpClient = DefaultHttpClient()
    private val assetRetriever = AssetRetriever(appContext.contentResolver, httpClient)
    private val publicationOpener = PublicationOpener(
        publicationParser = DefaultPublicationParser(
            context = appContext,
            httpClient = httpClient,
            assetRetriever = assetRetriever,
            pdfFactory = null
        )
    )

    suspend fun openPublication(file: File): Result<Publication> = withContext(Dispatchers.IO) {
        val asset = assetRetriever.retrieve(file.toUrl()).getOrElse { err ->
            return@withContext Result.failure(IllegalStateException("EPUB 资源读取失败: ${err.message}"))
        }
        val publication = publicationOpener.open(asset, allowUserInteraction = false).getOrElse { err ->
            runCatching { asset.close() }
            return@withContext Result.failure(IllegalStateException("EPUB 解析失败: ${err.message}"))
        }
        Result.success(publication)
    }

    fun extractTocItems(publication: Publication): List<ReadiumTocItem> {
        val out = mutableListOf<ReadiumTocItem>()
        fun walk(links: List<Link>, depth: Int) {
            for (link in links) {
                val locator = publication.locatorFromLink(link)
                val title = link.title?.trim().orEmpty().ifBlank { "章节 ${out.size + 1}" }
                if (locator != null) {
                    val readingIdx = resolveChapterIndex(publication, locator)
                    out.add(
                        ReadiumTocItem(
                            index = readingIdx,
                            title = title,
                            href = locator.href.toString(),
                            depth = depth,
                            locatorJson = locator.toJSON().toString()
                        )
                    )
                }
                if (link.children.isNotEmpty()) walk(link.children, depth + 1)
            }
        }
        walk(publication.tableOfContents, depth = 0)
        if (out.isEmpty()) {
            walk(publication.readingOrder, depth = 0)
        }
        return out
    }

    suspend fun searchPublication(
        publication: Publication,
        query: String,
        maxResults: Int = 60
    ): List<ReadiumSearchEntry> = withContext(Dispatchers.IO) {
        val iterator = publication.search(query) ?: return@withContext emptyList()
        val results = mutableListOf<ReadiumSearchEntry>()
        runCatching {
            while (results.size < maxResults) {
                val page = iterator.next().getOrNull() ?: break
                if (page.locators.isEmpty()) break
                for (loc in page.locators) {
                    val chapIdx = resolveChapterIndex(publication, loc).coerceAtLeast(0)
                    val title = loc.title?.takeIf { it.isNotBlank() } ?: "第 ${chapIdx + 1} 节"
                    val before = loc.text.before.orEmpty().takeLast(24)
                    val hitText = loc.text.highlight.orEmpty()
                    val after = loc.text.after.orEmpty().take(32)
                    val snippet = "$before【$hitText】$after".trim()
                    results.add(
                        ReadiumSearchEntry(
                            hit = BookSearchHit(chapIdx, title, 0, snippet.ifBlank { query }),
                            locatorJson = loc.toJSON().toString()
                        )
                    )
                    if (results.size >= maxResults) break
                }
            }
        }
        runCatching { iterator.close() }
        results
    }

    fun resolveChapterIndex(publication: Publication, locator: Locator): Int {
        val targetPath = locator.href.toString().substringBefore('#')
        val idx = publication.readingOrder.indexOfFirst { link ->
            link.url().toString().substringBefore('#') == targetPath ||
                targetPath.endsWith(link.href.toString().substringBefore('#'))
        }
        return idx.coerceAtLeast(0)
    }

    fun locatorFromChapter(publication: Publication, chapterIndex: Int): Locator? {
        val link = publication.readingOrder.getOrNull(chapterIndex) ?: return null
        return publication.locatorFromLink(link)
    }

    fun locatorForProgression(publication: Publication, progression: Double): Locator? {
        val order = publication.readingOrder
        if (order.isEmpty()) return null
        val clamped = progression.coerceIn(0.0, 0.999)
        val idx = (clamped * order.size).toInt().coerceIn(0, order.lastIndex)
        return publication.locatorFromLink(order[idx])
    }

    fun parseLocator(locatorJson: String?): Locator? {
        if (locatorJson.isNullOrBlank()) return null
        return runCatching { Locator.fromJSON(JSONObject(locatorJson)) }.getOrNull()
    }

    fun buildPreferences(
        settings: ReaderSettings,
        appIsDark: Boolean,
        appBgArgb: Int,
        appTextArgb: Int
    ): EpubPreferences {
        val (theme, bg, fg) = when (settings.paperTheme) {
            ReaderPaperTheme.FOLLOW_APP -> Triple(
                if (appIsDark) Theme.DARK else Theme.LIGHT,
                ReadiumColor(appBgArgb),
                ReadiumColor(appTextArgb)
            )
            ReaderPaperTheme.NIGHT, ReaderPaperTheme.AMOLED -> Triple(
                Theme.DARK,
                ReadiumColor(settings.paperTheme.bgHex.toInt()),
                ReadiumColor(settings.paperTheme.textHex.toInt())
            )
            ReaderPaperTheme.CREAM, ReaderPaperTheme.JADE -> Triple(
                Theme.SEPIA,
                ReadiumColor(settings.paperTheme.bgHex.toInt()),
                ReadiumColor(settings.paperTheme.textHex.toInt())
            )
            ReaderPaperTheme.PAPER -> Triple(
                Theme.LIGHT,
                ReadiumColor(settings.paperTheme.bgHex.toInt()),
                ReadiumColor(settings.paperTheme.textHex.toInt())
            )
        }
        return EpubPreferences(
            theme = theme,
            backgroundColor = bg,
            textColor = fg,
            fontFamily = if (settings.fontFamily == ReaderFontFamily.SERIF) FontFamily.SERIF else FontFamily.SANS_SERIF,
            fontSize = (settings.fontSizeSp / 16.0).coerceIn(0.75, 2.2),
            lineHeight = settings.lineHeightMultiplier.toDouble().coerceIn(1.1, 2.5),
            pageMargins = (settings.horizontalPaddingDp / 20.0).coerceIn(0.4, 2.0),
            paragraphSpacing = (settings.paragraphSpacingDp / 16.0).coerceIn(0.0, 2.0),
            letterSpacing = settings.letterSpacingEm.toDouble().takeIf { it > 0.01 },
            scroll = settings.readingMode == ReaderReadingMode.SCROLL,
            publisherStyles = settings.publisherStyles,
            textAlign = if (settings.textAlign == ReaderTextAlign.JUSTIFY) TextAlign.JUSTIFY else TextAlign.START
        )
    }
}
