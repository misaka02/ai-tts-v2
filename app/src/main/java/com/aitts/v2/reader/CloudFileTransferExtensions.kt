package com.aitts.v2.reader

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.aitts.v2.engine.cloud.CloudFileNode
import com.aitts.v2.engine.cloud.DriveFileItem
import com.aitts.v2.reader.model.BookRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

internal suspend fun GoogleDriveRepository.uploadFromUri(parentId: String, uri: Uri): Result<DriveFileItem> =
    withContext(Dispatchers.IO) {
        val name = resolveUriDisplayName(appContext, uri)
        val upDir = File(appContext.cacheDir, "drive_up").apply { mkdirs() }
        val tmp = File(upDir, "${UUID.randomUUID().toString().take(8)}_${sanitizeTransferName(name)}")
        try {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { out -> input.copyTo(out) }
            } ?: return@withContext Result.failure(IOException("无法读取待上传文件"))
            uploadFile(parentId.ifBlank { "root" }, tmp, remoteName = name)
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            tmp.delete()
        }
    }

internal suspend fun GoogleDriveRepository.downloadBytes(fileId: String, maxBytes: Long = 8L * 1024 * 1024): Result<ByteArray> =
    withContext(Dispatchers.IO) {
        val tmpDir = File(appContext.cacheDir, "drive_mem").apply { mkdirs() }
        val tmp = File(tmpDir, "${UUID.randomUUID().toString().take(8)}.bin")
        try {
            downloadToFile(fileId, tmp, maxBytes = maxBytes).getOrThrow()
            Result.success(tmp.readBytes())
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            tmp.delete()
        }
    }

internal suspend fun GoogleDriveRepository.downloadToLocalSyncFolder(
    node: CloudFileNode,
    localSync: LocalSyncFolderManager
): Result<Unit> = withContext(Dispatchers.IO) {
    val dlDir = File(appContext.cacheDir, "drive_dl").apply { mkdirs() }
    val tmp = File(dlDir, "${UUID.randomUUID().toString().take(8)}_${sanitizeTransferName(node.name)}")
    try {
        downloadToFile(node.id, tmp).getOrThrow()
        localSync.writeTempFileIntoDedicatedRoot(node.name, tmp)
        Result.success(Unit)
    } catch (t: Throwable) {
        Result.failure(t)
    } finally {
        tmp.delete()
    }
}

internal suspend fun GoogleDriveRepository.downloadAndImport(
    item: CloudFileNode,
    onProgress: (Float) -> Unit
): Result<BookRecord> = withContext(Dispatchers.IO) {
    val dlDir = File(appContext.cacheDir, "drive_dl").apply { mkdirs() }
    val tmpFile = File(dlDir, "${UUID.randomUUID().toString().take(8)}_${sanitizeTransferName(item.name)}")
    try {
        downloadToFile(item.id, tmpFile) { done, total ->
            val knownTotal = if (total > 0L) total else item.sizeBytes
            if (knownTotal > 0L) onProgress((done.toFloat() / knownTotal).coerceIn(0f, 1f))
        }.getOrThrow()
        tmpFile.inputStream().use { stream ->
            bookImportManager.importFromStream(item.name, stream, sourceSyncKey = "gdrive:${item.id}").getOrThrow()
        }.let { Result.success(it) }
    } catch (t: Throwable) {
        Result.failure(t)
    } finally {
        tmpFile.delete()
    }
}

internal fun resolveUriDisplayName(context: Context, uri: Uri): String {
    val queried = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
    val fallback = uri.lastPathSegment?.substringAfterLast('/') ?: "upload_${System.currentTimeMillis()}.epub"
    return queried?.takeIf { it.isNotBlank() } ?: fallback
}

private fun sanitizeTransferName(name: String): String = name.take(36).replace(Regex("[^a-zA-Z0-9._-]"), "_")
