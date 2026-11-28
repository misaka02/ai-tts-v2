package com.aitts.v2.reader

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.aitts.v2.engine.cloud.CloudFileNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Manages the dedicated local library sync directory:
 * defaults to `getExternalFilesDir("BooksSync")` and optionally supports any user-picked SAF tree.
 */
class LocalSyncFolderManager(
    context: Context,
    private val bookImportManager: BookImportManager
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("local_sync_folder", Context.MODE_PRIVATE)
    val defaultSyncDir: File = (appContext.getExternalFilesDir("BooksSync") ?: File(appContext.filesDir, "BooksSync"))
        .apply { mkdirs() }

    private val _treeUriFlow = MutableStateFlow(prefs.getString(KEY_TREE_URI, "")?.takeIf { it.isNotBlank() }?.let(Uri::parse))
    val customTreeUriFlow: StateFlow<Uri?> = _treeUriFlow.asStateFlow()

    fun dedicatedDisplayName(): String =
        _treeUriFlow.value?.lastPathSegment?.substringAfterLast(':')?.ifBlank { "自定义同步目录" }
            ?: "专属同步目录 (${defaultSyncDir.name})"

    fun bindCustomTree(uri: Uri) {
        runCatching {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            appContext.contentResolver.takePersistableUriPermission(uri, flags)
        }
        prefs.edit().putString(KEY_TREE_URI, uri.toString()).apply()
        _treeUriFlow.value = uri
    }

    fun resetToDefaultDir() {
        prefs.edit().remove(KEY_TREE_URI).apply()
        _treeUriFlow.value = null
    }

    suspend fun listNodes(relativePath: String, ebooksOnly: Boolean): Result<List<CloudFileNode>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val treeUri = _treeUriFlow.value
                val raw = if (treeUri != null) {
                    listSafDir(treeUri, relativePath)
                } else {
                    listLocalDir(relativePath)
                }
                raw.filter { !ebooksOnly || it.isFolder || it.isSupportedEbook }
                    .sortedWith(compareByDescending<CloudFileNode> { it.isFolder }.thenBy { it.name.lowercase() })
            }
        }

    suspend fun createFolder(parentPath: String, name: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val clean = sanitizeName(name)
            val treeUri = _treeUriFlow.value
            if (treeUri != null) {
                val parent = resolveSafFolder(treeUri, parentPath) ?: throw IOException("目录不可访问")
                parent.createDirectory(clean) ?: throw IOException("创建目录失败")
            } else {
                val target = File(resolveLocalDir(parentPath), clean)
                if (!target.exists() && !target.mkdirs()) throw IOException("创建目录失败")
            }
            Unit
        }
    }

    suspend fun renameNode(node: CloudFileNode, newName: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val clean = sanitizeName(newName)
            val treeUri = _treeUriFlow.value
            if (treeUri != null) {
                val doc = DocumentFile.fromSingleUri(appContext, Uri.parse(node.id))
                    ?: resolveSafChild(treeUri, node.path)
                    ?: throw IOException("文件不可访问")
                if (!doc.renameTo(clean)) throw IOException("重命名失败")
            } else {
                val src = resolveSafeLocalFile(node.path)
                val dest = File(src.parentFile, clean)
                if (!src.renameTo(dest)) throw IOException("重命名失败")
            }
        }
    }

    suspend fun deleteNode(node: CloudFileNode): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val treeUri = _treeUriFlow.value
            if (treeUri != null) {
                val doc = DocumentFile.fromSingleUri(appContext, Uri.parse(node.id))
                    ?: resolveSafChild(treeUri, node.path)
                    ?: throw IOException("文件不可访问")
                if (!doc.delete()) throw IOException("删除失败")
            } else {
                val target = resolveSafeLocalFile(node.path)
                if (!target.deleteRecursively()) throw IOException("删除失败")
            }
        }
    }

    suspend fun importNodeToBookshelf(node: CloudFileNode) = withContext(Dispatchers.IO) {
        val treeUri = _treeUriFlow.value
        val key = "local:${_treeUriFlow.value?.toString() ?: "default"}:${node.path}:${node.sizeBytes}:${node.modifiedTimeMs}"
        if (treeUri != null) {
            val doc = DocumentFile.fromSingleUri(appContext, Uri.parse(node.id))
                ?: resolveSafChild(treeUri, node.path)
                ?: throw IOException("无法打开文件")
            bookImportManager.importFromUri(doc.uri, sourceSyncKey = key).getOrThrow()
        } else {
            val file = resolveSafeLocalFile(node.path)
            file.inputStream().use { input ->
                bookImportManager.importFromStream(node.name, input, sourceSyncKey = key).getOrThrow()
            }
        }
    }

    suspend fun writeTempFileIntoDedicatedRoot(fileName: String, sourceFile: File) = withContext(Dispatchers.IO) {
        val clean = sanitizeName(fileName)
        val treeUri = _treeUriFlow.value
        if (treeUri != null) {
            val root = DocumentFile.fromTreeUri(appContext, treeUri) ?: throw IOException("同步目录不可写")
            val existing = root.findFile(clean)
            existing?.delete()
            val created = root.createFile("application/octet-stream", clean) ?: throw IOException("写入同步目录失败")
            appContext.contentResolver.openOutputStream(created.uri)?.use { out ->
                sourceFile.inputStream().use { it.copyTo(out) }
            } ?: throw IOException("无法写入目标文件")
        } else {
            val dest = File(defaultSyncDir, clean)
            sourceFile.copyTo(dest, overwrite = true)
        }
    }

    suspend fun writeUriIntoFolder(relativePath: String, uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val rawName = runCatching {
                appContext.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "upload.epub"
            val clean = sanitizeName(rawName)
            val stage = File(appContext.cacheDir, "local_up_${System.nanoTime()}.bin")
            try {
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    stage.outputStream().use { input.copyTo(it) }
                } ?: throw IOException("无法读取源文件")
                val treeUri = _treeUriFlow.value
                if (treeUri != null) {
                    val folder = resolveSafFolder(treeUri, relativePath) ?: throw IOException("目标目录不可写")
                    val created = folder.createFile("application/octet-stream", "tmp_${System.nanoTime()}_$clean")
                        ?: throw IOException("创建文件失败")
                    appContext.contentResolver.openOutputStream(created.uri)?.use { out ->
                        stage.inputStream().use { it.copyTo(out) }
                    } ?: run { created.delete(); throw IOException("无法写入目标文件") }
                    folder.findFile(clean)?.delete()
                    if (!created.renameTo(clean)) {
                        created.delete()
                        throw IOException("重命名上传文件失败")
                    }
                } else {
                    val dest = File(resolveLocalDir(relativePath), clean)
                    stage.copyTo(dest, overwrite = true)
                }
            } finally {
                stage.delete()
            }
            Unit
        }
    }

    suspend fun copyNodeToTempFile(node: CloudFileNode, outFile: File): File = withContext(Dispatchers.IO) {
        val treeUri = _treeUriFlow.value
        if (treeUri != null) {
            val doc = DocumentFile.fromSingleUri(appContext, Uri.parse(node.id))
                ?: resolveSafChild(treeUri, node.path)
                ?: throw IOException("文件不可访问")
            appContext.contentResolver.openInputStream(doc.uri)?.use { input ->
                outFile.outputStream().use { input.copyTo(it) }
            } ?: throw IOException("读取本地文件失败")
        } else {
            resolveSafeLocalFile(node.path).copyTo(outFile, overwrite = true)
        }
        outFile
    }

    suspend fun syncRootToBookshelf(): Int = withContext(Dispatchers.IO) {
        val nodes = listNodes("", ebooksOnly = true).getOrThrow()
        var imported = 0
        val failed = mutableListOf<String>()
        for (node in nodes) {
            if (!node.isFolder && node.isSupportedEbook) {
                runCatching { importNodeToBookshelf(node) }
                    .onSuccess { imported++ }
                    .onFailure { failed.add("${node.name}: ${it.message ?: "导入失败"}") }
            }
        }
        if (failed.isNotEmpty()) throw IOException("同步导入部分失败: ${failed.take(3).joinToString("；")}")
        imported
    }

    private fun listLocalDir(relativePath: String): List<CloudFileNode> {
        val dir = resolveLocalDir(relativePath)
        return dir.listFiles().orEmpty().map { f ->
            val rel = f.relativeTo(defaultSyncDir).invariantSeparatorsPath
            CloudFileNode(rel, f.name, rel, f.isDirectory, if (f.isFile) f.length() else 0L, f.lastModified())
        }
    }

    private fun listSafDir(treeUri: Uri, relativePath: String): List<CloudFileNode> {
        val folder = resolveSafFolder(treeUri, relativePath) ?: return emptyList()
        return folder.listFiles().mapNotNull { doc ->
            val name = doc.name ?: return@mapNotNull null
            val rel = listOf(relativePath.trim('/'), name).filter { it.isNotEmpty() }.joinToString("/")
            CloudFileNode(doc.uri.toString(), name, rel, doc.isDirectory, doc.length(), doc.lastModified(), doc.type.orEmpty())
        }
    }

    private fun resolveLocalDir(relativePath: String): File {
        val clean = relativePath.trim('/').replace("..", "")
        val dir = if (clean.isEmpty()) defaultSyncDir else File(defaultSyncDir, clean)
        if (!dir.canonicalPath.startsWith(defaultSyncDir.canonicalPath)) throw IOException("非法目录路径")
        return dir.apply { mkdirs() }
    }

    private fun resolveSafeLocalFile(relativePath: String): File {
        val file = File(defaultSyncDir, relativePath.trim('/').replace("..", ""))
        if (!file.canonicalPath.startsWith(defaultSyncDir.canonicalPath)) throw IOException("非法文件路径")
        return file
    }

    private fun resolveSafFolder(treeUri: Uri, relativePath: String): DocumentFile? {
        var cur = DocumentFile.fromTreeUri(appContext, treeUri) ?: return null
        for (seg in relativePath.split('/').filter { it.isNotBlank() && it != "." && it != ".." }) {
            cur = cur.findFile(seg)?.takeIf { it.isDirectory } ?: return null
        }
        return cur
    }

    private fun resolveSafChild(treeUri: Uri, relativePath: String): DocumentFile? {
        val parentPath = relativePath.substringBeforeLast('/', "")
        val name = relativePath.substringAfterLast('/')
        return resolveSafFolder(treeUri, parentPath)?.findFile(name)
    }

    private fun sanitizeName(name: String): String {
        val clean = name.trim().replace(Regex("[/\\\\:*?\"<>|]"), "_")
        if (clean.isBlank() || clean == "." || clean == "..") throw IOException("文件名无效")
        return clean
    }

    companion object {
        private const val KEY_TREE_URI = "custom_tree_uri"
    }
}
