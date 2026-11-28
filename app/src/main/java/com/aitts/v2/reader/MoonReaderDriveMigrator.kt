package com.aitts.v2.reader

import com.aitts.v2.engine.cloud.CloudFileNode
import com.aitts.v2.reader.moon.MoonBookMeta
import com.aitts.v2.reader.moon.MoonReaderBackupParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

data class MoonBookCandidate(
    val bookFile: CloudFileNode,
    val displayTitle: String,
    val author: String,
    val shelfGroup: String,
    val meta: MoonBookMeta?,
    val poFileId: String?,
    val anFileId: String?,
    val coverFileId: String?,
    val matchedLocalBookId: String?
) {
    val alreadyOnShelf: Boolean get() = matchedLocalBookId != null
    val hasProgress: Boolean get() = poFileId != null
    val hasNotes: Boolean get() = anFileId != null
}

data class MoonMigrationPreview(
    val sourceFolderId: String,
    val sourceFolderName: String,
    val moonFolderId: String,
    val candidates: List<MoonBookCandidate>,
    val unsupportedCount: Int
) {
    val groups: List<String>
        get() = candidates.mapNotNull { it.shelfGroup.takeIf(String::isNotBlank) }.distinct().sorted()
}

data class MoonMigrationResult(
    val importedNewBooks: Int,
    val mergedExistingBooks: Int,
    val progressApplied: Int,
    val notesImported: Int,
    val failedCount: Int
)

/**
 * Read-only Moon+ Reader (`.Moon+`) library discovery and inheritance migrator.
 * Imports supported books plus `.po` reading progress, `.an` highlights/notes, shelf categories,
 * and fallback `_2.png` covers into AI-TTS without altering `.Moon+`.
 */
class MoonReaderDriveMigrator(
    private val googleDrive: GoogleDriveRepository,
    private val bookLibrary: BookLibraryRepository
) {

    suspend fun scanMoonLibrary(
        currentFolderId: String,
        currentFolderName: String,
        onProgress: (String) -> Unit = {}
    ): Result<MoonMigrationPreview> = withContext(Dispatchers.IO) {
        runCatching {
            onProgress("正在定位静读天下 .Moon+ 备份目录...")
            val (booksFolderId, booksFolderName, moonFolderId) = resolveBooksAndMoonFolder(currentFolderId, currentFolderName)
            onProgress("正在读取云端书库与 .Moon+ 缓存索引...")
            val bookItems = googleDrive.listNodes(booksFolderId, "", ebooksOnly = false).getOrThrow()
            val moonTop = googleDrive.listNodes(moonFolderId, "", ebooksOnly = false).getOrThrow()
            val cacheFolder = moonTop.firstOrNull { it.isFolder && it.name.equals("Cache", true) }
            val coverFolder = moonTop.firstOrNull { it.isFolder && it.name.equals("Cover", true) }
            val syncFile = moonTop.firstOrNull { !it.isFolder && it.name.equals("books.sync", true) }

            val cacheItems = cacheFolder?.let { googleDrive.listNodes(it.id, "", ebooksOnly = false).getOrNull() }.orEmpty()
            val coverItems = coverFolder?.let { googleDrive.listNodes(it.id, "", ebooksOnly = false).getOrNull() }.orEmpty()
            val metaMap = loadMoonMetaMap(syncFile)

            val poByBookName = cacheItems.filter { !it.isFolder && it.name.endsWith(".po", true) }
                .associate { it.name.dropLast(3).lowercase() to it.id }
            val anByBookName = cacheItems.filter { !it.isFolder && it.name.endsWith(".an", true) }
                .associate { it.name.dropLast(3).lowercase() to it.id }
            val coverByBookName = coverItems.filter { !it.isFolder && it.name.endsWith("_2.png", true) }
                .associate { it.name.dropLast(6).lowercase() to it.id }

            val localBooks = bookLibrary.booksFlow.value
            val unsupported = bookItems.count { !it.isFolder && !it.isSupportedEbook }
            val candidates = bookItems.filter { !it.isFolder && it.isSupportedEbook }.map { node ->
                val lower = node.name.lowercase()
                val meta = metaMap[lower]
                val titleKey = meta?.bookName?.ifBlank { node.name.substringBeforeLast('.') }
                val matchedLocal = localBooks.firstOrNull { it.sourceSyncKey == "gdrive:${node.id}" }
                    ?: localBooks.firstOrNull { rec ->
                        !rec.sourceSyncKey.startsWith("gdrive:") &&
                            (rec.title.equals(titleKey, true) || rec.title.equals(node.name.substringBeforeLast('.'), true))
                    }
                MoonBookCandidate(
                    bookFile = node,
                    displayTitle = meta?.bookName?.takeIf { it.isNotBlank() } ?: node.name.substringBeforeLast('.'),
                    author = meta?.cleanAuthor.orEmpty(),
                    shelfGroup = meta?.shelfGroup.orEmpty(),
                    meta = meta,
                    poFileId = poByBookName[lower],
                    anFileId = anByBookName[lower],
                    coverFileId = coverByBookName[lower],
                    matchedLocalBookId = matchedLocal?.id
                )
            }
            MoonMigrationPreview(booksFolderId, booksFolderName, moonFolderId, candidates, unsupported)
        }
    }

    suspend fun importSelected(
        preview: MoonMigrationPreview,
        selectedFileIds: Set<String>,
        downloadMissingBooks: Boolean,
        onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }
    ): Result<MoonMigrationResult> = withContext(Dispatchers.IO) {
        runCatching {
            val targets = preview.candidates.filter { it.bookFile.id in selectedFileIds }
            var importedNew = 0
            var mergedExisting = 0
            var progressCount = 0
            var notesCount = 0
            var failed = 0

            for ((idx, item) in targets.withIndex()) {
                onProgress(idx + 1, targets.size, "正在继承《${item.displayTitle}》...")
                val targetBookId = resolveOrImportBook(item, downloadMissingBooks)
                if (targetBookId == null) {
                    if (downloadMissingBooks || item.alreadyOnShelf) failed++
                    continue
                }
                if (item.alreadyOnShelf) mergedExisting++ else importedNew++

                val pos = item.poFileId?.let { id ->
                    googleDrive.downloadBytes(id, maxBytes = 64 * 1024).getOrNull()
                        ?.toString(Charsets.UTF_8)
                        ?.let(MoonReaderBackupParser::parsePositionPo)
                }
                val anns = item.anFileId?.let { id ->
                    googleDrive.downloadBytes(id, maxBytes = 4 * 1024 * 1024).getOrNull()
                        ?.let(MoonReaderBackupParser::parseAnnotationsAn)
                }.orEmpty()
                val needsCover = bookLibrary.getBook(targetBookId)?.coverFileName.isNullOrBlank()
                val coverBytes = if (needsCover && item.coverFileId != null) {
                    googleDrive.downloadBytes(item.coverFileId, maxBytes = 4 * 1024 * 1024).getOrNull()
                } else {
                    null
                }
                if (pos != null) progressCount++
                if (anns.isNotEmpty()) notesCount += anns.size
                bookLibrary.applyMoonReaderMetadata(targetBookId, item.meta, pos, anns, coverBytes)
            }
            MoonMigrationResult(importedNew, mergedExisting, progressCount, notesCount, failed)
        }
    }

    private suspend fun resolveOrImportBook(item: MoonBookCandidate, downloadMissing: Boolean): String? {
        item.matchedLocalBookId?.let { return it }
        if (!downloadMissing) return null
        return googleDrive.downloadAndImport(item.bookFile) {}.getOrNull()?.id
    }

    private suspend fun loadMoonMetaMap(syncFile: CloudFileNode?): Map<String, MoonBookMeta> {
        if (syncFile == null) return emptyMap()
        val bytes = googleDrive.downloadBytes(syncFile.id, maxBytes = 8L * 1024 * 1024).getOrNull() ?: return emptyMap()
        return MoonReaderBackupParser.parseBooksSync(bytes).associateBy { it.filename.trim().lowercase() }
    }

    private suspend fun resolveBooksAndMoonFolder(
        currentFolderId: String,
        currentFolderName: String
    ): Triple<String, String, String> {
        val curId = currentFolderId.ifBlank { "root" }
        val curItems = googleDrive.listNodes(curId, "", ebooksOnly = false).getOrThrow()
        val moonInCurrent = curItems.firstOrNull { it.isFolder && it.name.equals(MOON_FOLDER, true) }
        if (moonInCurrent != null) {
            val label = if (curId == "root") "我的云端硬盘" else currentFolderName.ifBlank { curId }
            return Triple(curId, label, moonInCurrent.id)
        }
        val rootItems = if (curId == "root") curItems else googleDrive.listNodes("root", "", ebooksOnly = false).getOrThrow()
        val booksAtRoot = rootItems.firstOrNull { it.isFolder && it.name.equals("Books", true) }
        if (booksAtRoot != null) {
            val sub = googleDrive.listNodes(booksAtRoot.id, "", ebooksOnly = false).getOrThrow()
            sub.firstOrNull { it.isFolder && it.name.equals(MOON_FOLDER, true) }?.let { moon ->
                return Triple(booksAtRoot.id, booksAtRoot.name, moon.id)
            }
        }
        val moonAtRoot = rootItems.firstOrNull { it.isFolder && it.name.equals(MOON_FOLDER, true) }
        if (moonAtRoot != null) {
            val bookTarget = booksAtRoot ?: CloudFileNode("root", "我的云端硬盘", "root", true)
            return Triple(bookTarget.id, bookTarget.name, moonAtRoot.id)
        }
        throw IOException("未在当前目录或 /Books 下找到静读天下 `.Moon+` 备份目录，请先进入包含书籍与 `.Moon+` 的目录")
    }

    companion object {
        private const val MOON_FOLDER = ".Moon+"
    }
}
