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

/**
 * Downloads, safely extracts (ZipSlip-guarded), and verifies Sherpa-ONNX offline model packs.
 */
class OfflineModelInstaller(
    private val modelsRootDir: File,
    private val httpManager: HttpClientManager
) {
    init {
        if (!modelsRootDir.exists()) modelsRootDir.mkdirs()
    }

    fun getModelDir(modelId: String): File = File(modelsRootDir, modelId)

    fun isModelInstalled(modelId: String): Boolean {
        val dir = getModelDir(modelId)
        val flag = File(dir, READY_FLAG)
        if (!flag.exists() || !dir.isDirectory) return false
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        val hasOnnx = files.any { it.extension.equals("onnx", ignoreCase = true) }
        val hasTokens = files.any { it.name.equals("tokens.txt", ignoreCase = true) }
        return hasOnnx && hasTokens
    }

    fun deleteModel(modelId: String): Boolean {
        val dir = getModelDir(modelId)
        return if (dir.exists()) dir.deleteRecursively() else true
    }

    suspend fun installModel(
        spec: OfflineModelSpec,
        preferMirror: Boolean = true,
        onProgress: (Int, String) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val targetDir = getModelDir(spec.id)
        if (targetDir.exists()) targetDir.deleteRecursively()
        targetDir.mkdirs()

        val candidates = if (preferMirror) {
            listOf(spec.mirrorUrl, spec.githubUrl)
        } else {
            listOf(spec.githubUrl, spec.mirrorUrl)
        }.filter { it.isNotBlank() }.distinct()

        val tempArchive = File(targetDir, "model.archive.tmp")
        try {
            downloadFromMirrors(candidates, tempArchive, onProgress)
            onProgress(88, "正在校验并解压模型权重...")
            val extracted = extractArchiveStrict(tempArchive, targetDir)
            if (extracted <= 0 || !hasRequiredModelFiles(targetDir)) {
                throw IOException("模型压缩包解压后缺少 .onnx 或 tokens.txt 文件")
            }
            tempArchive.delete()
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
                    body.byteStream().use { input ->
                        FileOutputStream(destFile).use { output ->
                            val buf = ByteArray(128 * 1024)
                            var readSum = 0L
                            var lastPct = 5
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buf)
                                if (n == -1) break
                                output.write(buf, 0, n)
                                readSum += n
                                if (total > 0) {
                                    val pct = ((readSum * 80) / total).toInt() + 5
                                    if (pct > lastPct) {
                                        lastPct = pct
                                        onProgress(pct, "正在下载: ${readSum / (1024 * 1024)}MB / ${total / (1024 * 1024)}MB")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (attempt.isSuccess) return
            lastErr = attempt.exceptionOrNull() as? Exception ?: IOException(attempt.exceptionOrNull()?.message)
            if (destFile.exists()) destFile.delete()
        }
        throw lastErr ?: IOException("全部下载源均不可用")
    }

    private fun extractTarBz2(archiveFile: File, outputDir: File): Int {
        var count = 0
        TarArchiveInputStream(
            BZip2CompressorInputStream(archiveFile.inputStream().buffered())
        ).use { tarIn ->
            while (true) {
                val entry = tarIn.nextEntry ?: break
                if (writeArchiveEntry(outputDir, entry.name, entry.isDirectory) { tarIn.copyTo(it) }) {
                    count++
                }
            }
        }
        return count
    }

    private fun extractZip(archiveFile: File, outputDir: File): Int {
        var count = 0
        ZipInputStream(archiveFile.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (writeArchiveEntry(outputDir, entry.name, entry.isDirectory) { zis.copyTo(it) }) {
                    count++
                }
            }
        }
        return count
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
    }
}
