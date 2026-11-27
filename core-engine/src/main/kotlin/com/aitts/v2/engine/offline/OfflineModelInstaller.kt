package com.aitts.v2.engine.offline

import com.aitts.v2.engine.network.HttpClientManager
import com.aitts.v2.engine.provider.HttpCallSupport
import com.aitts.v2.model.OfflineCatalogData
import com.aitts.v2.model.OfflineModelSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Downloads, safely extracts (ZipSlip-guarded), and verifies Sherpa-ONNX offline model packs.
 */
class OfflineModelInstaller(
    private val modelsRootDir: File,
    private val httpManager: HttpClientManager
) {
    private val json = Json { ignoreUnknownKeys = true }

    init {
        if (!modelsRootDir.exists()) modelsRootDir.mkdirs()
    }

    fun getModelDir(modelId: String): File {
        val clean = sanitizeModelId(modelId)
            ?: throw IllegalArgumentException("非法的离线模型 ID: $modelId")
        val rootCanonical = modelsRootDir.canonicalFile
        val dir = File(rootCanonical, clean).canonicalFile
        if (dir.parentFile != rootCanonical) {
            throw SecurityException("离线模型目录越界: $modelId")
        }
        return dir
    }

    fun isModelInstalled(modelId: String): Boolean {
        val dir = runCatching { getModelDir(modelId) }.getOrNull() ?: return false
        val flag = File(dir, READY_FLAG)
        if (!flag.exists() || !dir.isDirectory) return false
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        val hasOnnx = files.any { it.extension.equals("onnx", ignoreCase = true) }
        val hasTokens = files.any { it.name.equals("tokens.txt", ignoreCase = true) }
        return hasOnnx && hasTokens
    }

    fun readInstalledSpec(modelId: String): OfflineModelSpec? {
        val dir = runCatching { getModelDir(modelId) }.getOrNull() ?: return null
        val file = File(dir, SPEC_FILE)
        if (!file.exists()) return null
        return runCatching { json.decodeFromString<OfflineModelSpec>(file.readText()) }.getOrNull()
    }

    fun listInstalledIds(): Set<String> =
        modelsRootDir.listFiles()?.asSequence()
            ?.filter { it.isDirectory && isModelInstalled(it.name) }
            ?.map { it.name }
            ?.toSet()
            .orEmpty()

    fun listInstalledSpecs(): List<OfflineModelSpec> =
        listInstalledIds().mapNotNull { id -> readInstalledSpec(id) ?: OfflineCatalogData.findById(id) }

    fun deleteModel(modelId: String): Boolean {
        val dir = runCatching { getModelDir(modelId) }.getOrNull() ?: return false
        return if (dir.exists()) dir.deleteRecursively() else true
    }

    suspend fun installModel(
        spec: OfflineModelSpec,
        preferMirror: Boolean = true,
        onProgress: (Int, String) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val targetDir = runCatching { getModelDir(spec.id) }.getOrElse { err ->
            return@withContext Result.failure(err)
        }
        if (targetDir.exists()) targetDir.deleteRecursively()
        targetDir.mkdirs()

        val candidates = if (preferMirror) {
            listOf(spec.mirrorUrl, spec.githubUrl)
        } else {
            listOf(spec.githubUrl, spec.mirrorUrl)
        }.filter { it.isNotBlank() }.distinct()
        val maxArchiveBytes = (spec.sizeMb.coerceAtLeast(1) * 4L * 1024L * 1024L)
            .coerceIn(256L * 1024L * 1024L, MAX_ARCHIVE_BYTES)

        val tempArchive = File(targetDir, "model.archive.tmp")
        try {
            downloadFromMirrors(candidates, tempArchive, maxArchiveBytes, onProgress)
            onProgress(88, "正在校验并解压模型权重...")
            val extracted = extractArchiveStrict(tempArchive, targetDir)
            if (extracted <= 0 || !hasRequiredModelFiles(targetDir)) {
                throw IOException("模型压缩包解压后缺少 .onnx 或 tokens.txt 文件")
            }
            tempArchive.delete()
            File(targetDir, SPEC_FILE).writeText(json.encodeToString(spec))
            File(targetDir, READY_FLAG).writeText("READY_V2|${spec.id}|${spec.arch.name}|files=$extracted")
            onProgress(100, "离线模型安装完成")
            Result.success(targetDir)
        } catch (e: Exception) {
            targetDir.deleteRecursively()
            Result.failure(e)
        }
    }

    /**
     * Extracts a local archive file directly (also used in unit tests for ZipSlip & validation).
     */
    fun extractArchiveStrict(archiveFile: File, outputDir: File): Int {
        if (!outputDir.exists()) outputDir.mkdirs()
        val tarRes = runCatching { extractTarBz2(archiveFile, outputDir) }
        if (tarRes.isSuccess && tarRes.getOrDefault(0) > 0) {
            return tarRes.getOrThrow()
        }
        if (tarRes.exceptionOrNull() is SecurityException) {
            throw tarRes.exceptionOrNull()!!
        }
        return extractZip(archiveFile, outputDir)
    }

    private suspend fun downloadFromMirrors(
        urls: List<String>,
        destFile: File,
        maxBytes: Long,
        onProgress: (Int, String) -> Unit
    ) {
        var lastErr: Exception? = null
        for ((idx, url) in urls.withIndex()) {
            val attempt = runCatching {
                onProgress(5, "正在连接下载源 (${idx + 1}/${urls.size})...")
                val req = Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "AI-TTS-Engine-V2")
                    .build()
                HttpCallSupport.executeTracked(httpManager, "", req) { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val body = resp.body ?: throw IOException("响应体为空")
                    val total = body.contentLength()
                    if (total > maxBytes) throw IOException("模型压缩包体积超出限制 (${total / (1024 * 1024)}MB)")
                    copyStreamWithProgress(body.byteStream(), destFile, total, maxBytes, onProgress)
                }
            }
            if (attempt.isSuccess) return
            lastErr = attempt.exceptionOrNull() as? Exception ?: IOException(attempt.exceptionOrNull()?.message)
            if (destFile.exists()) destFile.delete()
        }
        throw lastErr ?: IOException("全部下载源均不可用")
    }

    private suspend fun copyStreamWithProgress(
        input: java.io.InputStream,
        destFile: File,
        total: Long,
        maxBytes: Long,
        onProgress: (Int, String) -> Unit
    ) {
        input.use { src ->
            FileOutputStream(destFile).use { output ->
                copyBoundedLoop(src, output, total, maxBytes, onProgress)
            }
        }
    }

    private suspend fun copyBoundedLoop(
        src: java.io.InputStream,
        output: FileOutputStream,
        total: Long,
        maxBytes: Long,
        onProgress: (Int, String) -> Unit
    ) {
        val buf = ByteArray(128 * 1024)
        var readSum = 0L
        var lastPct = 5
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = src.read(buf)
            if (n == -1) break
            readSum += n
            if (readSum > maxBytes) throw IOException("下载体积超出安全上限")
            output.write(buf, 0, n)
            val pct = if (total > 0) ((readSum * 80) / total).toInt() + 5 else lastPct
            if (pct > lastPct) {
                lastPct = pct
                onProgress(pct, "正在下载: ${readSum / (1024 * 1024)}MB / ${total / (1024 * 1024)}MB")
            }
        }
    }

    private fun extractTarBz2(archiveFile: File, outputDir: File): Int {
        var count = 0
        var totalExtracted = 0L
        TarArchiveInputStream(
            BZip2CompressorInputStream(archiveFile.inputStream().buffered())
        ).use { tarIn ->
            while (true) {
                val entry = tarIn.nextEntry ?: break
                val written = writeArchiveEntry(outputDir, entry.name, entry.isDirectory) { out ->
                    totalExtracted += copyBounded(tarIn, out, MAX_EXTRACTED_BYTES - totalExtracted)
                }
                if (written) count++
            }
        }
        return count
    }

    private fun extractZip(archiveFile: File, outputDir: File): Int {
        var count = 0
        var totalExtracted = 0L
        ZipInputStream(archiveFile.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val written = writeArchiveEntry(outputDir, entry.name, entry.isDirectory) { out ->
                    totalExtracted += copyBounded(zis, out, MAX_EXTRACTED_BYTES - totalExtracted)
                }
                if (written) count++
            }
        }
        return count
    }

    private fun copyBounded(
        input: java.io.InputStream,
        output: FileOutputStream,
        remainingLimit: Long
    ): Long {
        val buf = ByteArray(32 * 1024)
        var written = 0L
        while (true) {
            val n = input.read(buf)
            if (n == -1) break
            written += n
            if (written > remainingLimit) throw IOException("解压总体积超出安全限制")
            output.write(buf, 0, n)
        }
        return written
    }

    private fun writeArchiveEntry(
        outputDir: File,
        entryName: String,
        isDirectory: Boolean,
        copyAction: (FileOutputStream) -> Unit
    ): Boolean {
        val rel = normalizeRelativeEntry(entryName)
        if (rel.isBlank()) return false
        val outFile = safeResolve(outputDir, rel)
        if (isDirectory) {
            outFile.mkdirs()
            return false
        }
        outFile.parentFile?.mkdirs()
        FileOutputStream(outFile).use(copyAction)
        return true
    }

    private fun normalizeRelativeEntry(rawName: String): String {
        val clean = rawName.replace('\\', '/')
        if (clean.contains("..")) return clean // let safeResolve catch traversal explicitly!
        return if (clean.contains("/")) clean.substringAfter("/") else clean
    }

    private fun safeResolve(outputDir: File, relativePath: String): File {
        val outFile = File(outputDir, relativePath)
        val rootCanonical = outputDir.canonicalPath + File.separator
        if (!outFile.canonicalPath.startsWith(rootCanonical)) {
            throw SecurityException("Blocked ZipSlip path traversal: $relativePath")
        }
        return outFile
    }

    private fun hasRequiredModelFiles(dir: File): Boolean {
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        return files.any { it.extension.equals("onnx", ignoreCase = true) } &&
            files.any { it.name.equals("tokens.txt", ignoreCase = true) }
    }

    fun getCatalog(): List<OfflineModelSpec> = OfflineCatalogData.models

    companion object {
        const val READY_FLAG = "model_ready_v2.flag"
        const val SPEC_FILE = "model_spec.json"
        private const val MAX_ARCHIVE_BYTES = 1024L * 1024L * 1024L
        private const val MAX_EXTRACTED_BYTES = 1536L * 1024L * 1024L
        private val SAFE_MODEL_ID_REGEX = Regex("^[a-zA-Z0-9._-]+$")

        fun sanitizeModelId(rawId: String): String? {
            val clean = rawId.trim()
            if (clean.isEmpty() || clean.contains("..") || !SAFE_MODEL_ID_REGEX.matches(clean)) return null
            return clean
        }
    }
}
