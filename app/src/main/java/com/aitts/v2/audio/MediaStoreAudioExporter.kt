package com.aitts.v2.audio

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.aitts.v2.engine.audio.AudioFormatDetector
import com.aitts.v2.engine.audio.DetectedAudioFormat
import com.aitts.v2.engine.audio.WavCodec
import java.io.File
import java.io.IOException

/**
 * Scoped-storage compliant audio exporter using MediaStore.Downloads (API 29+)
 * or app-scoped external downloads directory (API 26..28), requiring zero storage permissions.
 */
object MediaStoreAudioExporter {

    fun exportToDownloads(
        context: Context,
        audioBytes: ByteArray,
        sampleRate: Int = 24000,
        prefix: String = "AITTS"
    ): Result<String> = runCatching {
        if (audioBytes.isEmpty()) throw IOException("音频数据为空")

        val format = AudioFormatDetector.detect(audioBytes)
        val (payload, ext, mime) = when (format) {
            DetectedAudioFormat.RAW_PCM -> Triple(
                WavCodec.encodeToWav(audioBytes, sampleRate = sampleRate, channels = 1),
                "wav",
                "audio/wav"
            )
            DetectedAudioFormat.WAV -> Triple(audioBytes, "wav", "audio/wav")
            DetectedAudioFormat.OGG -> Triple(audioBytes, "ogg", "audio/ogg")
            DetectedAudioFormat.FLAC -> Triple(audioBytes, "flac", "audio/flac")
            DetectedAudioFormat.AAC -> Triple(audioBytes, "aac", "audio/aac")
            DetectedAudioFormat.MP3 -> Triple(audioBytes, "mp3", "audio/mpeg")
        }

        val fileName = "${prefix}_${System.currentTimeMillis()}.$ext"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/AI-TTS")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("无法在系统 Downloads 创建文件")
            resolver.openOutputStream(uri)?.use { it.write(payload) }
                ?: throw IOException("无法打开输出流")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            "Downloads/AI-TTS/$fileName"
        } else {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            if (!dir.exists()) dir.mkdirs()
            val outFile = File(dir, fileName)
            outFile.writeBytes(payload)
            outFile.absolutePath
        }
    }
}
