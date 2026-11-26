package com.aitts.v2.engine.cache

import com.aitts.v2.model.TtsProviderConfig
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock

/**
 * Atomic, read/write-locked LRU audio disk cache honoring [maxCacheSizeMb].
 */
class AudioDiskCache(private val cacheDir: File) {

    private val rwLock = ReentrantReadWriteLock()
    private val writeCounter = AtomicInteger(0)

    init {
        if (!cacheDir.exists()) cacheDir.mkdirs()
    }

    fun generateKey(config: TtsProviderConfig, text: String): String {
        val raw = listOf(
            config.id,
            config.type.name,
            config.modelName,
            config.voiceId,
            config.promptInstruction.trim(),
            config.sampleRate.toString(),
            config.audioFormat,
            "%.2f".format(config.speed),
            "%.2f".format(config.pitch),
            text.trim()
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun get(config: TtsProviderConfig, text: String): ByteArray? = getByKey(generateKey(config, text))

    fun put(config: TtsProviderConfig, text: String, bytes: ByteArray, maxCacheSizeMb: Int) {
        putByKey(generateKey(config, text), bytes, maxCacheSizeMb)
    }

    fun getByKey(key: String): ByteArray? {
        val file = File(cacheDir, "$key.bin")
        return rwLock.readLock().withLock {
            if (!file.exists() || file.length() <= 0L) return null
            runCatching {
                val now = System.currentTimeMillis()
                if (now - file.lastModified() > 600_000L) {
                    file.setLastModified(now)
                }
                file.readBytes()
            }.getOrNull()
        }
    }

    fun putByKey(key: String, data: ByteArray, maxCacheSizeMb: Int) {
        if (data.isEmpty() || maxCacheSizeMb <= 0) return
        val tempFile = File(cacheDir, "$key.tmp_${System.nanoTime()}")
        val targetFile = File(cacheDir, "$key.bin")

        runCatching {
            FileOutputStream(tempFile).use { it.write(data) }
            rwLock.writeLock().withLock {
                if (targetFile.exists()) targetFile.delete()
                if (!tempFile.renameTo(targetFile)) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                }
                if (writeCounter.incrementAndGet() % 10 == 0 || totalSizeBytes() > maxCacheSizeMb.toLong() * 1024 * 1024) {
                    trimIfNeededLocked(maxCacheSizeMb)
                }
            }
        }.onFailure {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    fun getStats(): Pair<Int, Float> = rwLock.readLock().withLock {
        val files = cacheDir.listFiles { f -> f.isFile && f.name.endsWith(".bin") } ?: emptyArray()
        val bytes = files.sumOf { it.length() }
        files.size to (bytes.toFloat() / (1024f * 1024f))
    }

    fun clearAll() = rwLock.writeLock().withLock {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    private fun totalSizeBytes(): Long {
        val files = cacheDir.listFiles { f -> f.isFile && f.name.endsWith(".bin") } ?: return 0L
        return files.sumOf { it.length() }
    }

    private fun trimIfNeededLocked(maxCacheSizeMb: Int) {
        val maxBytes = maxCacheSizeMb.toLong() * 1024L * 1024L
        val files = cacheDir.listFiles { f -> f.isFile && f.name.endsWith(".bin") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return

        val targetBytes = (maxBytes * 0.8).toLong()
        for (file in files.sortedBy { it.lastModified() }) {
            val len = file.length()
            if (file.delete()) {
                total -= len
                if (total <= targetBytes) break
            }
        }
    }
}
