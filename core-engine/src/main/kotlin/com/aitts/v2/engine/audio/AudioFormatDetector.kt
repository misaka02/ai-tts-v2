package com.aitts.v2.engine.audio

enum class DetectedAudioFormat {
    WAV,
    MP3,
    OGG,
    FLAC,
    AAC,
    RAW_PCM
}

object AudioFormatDetector {

    fun detect(data: ByteArray): DetectedAudioFormat {
        if (data.size < 4) return DetectedAudioFormat.RAW_PCM
        val magic = String(data, 0, 4, Charsets.US_ASCII)
        return when {
            isRiffWave(data, magic) -> DetectedAudioFormat.WAV
            magic.startsWith("ID3") -> DetectedAudioFormat.MP3
            magic == "OggS" -> DetectedAudioFormat.OGG
            magic == "fLaC" -> DetectedAudioFormat.FLAC
            else -> detectSyncFrame(data)
        }
    }

    private fun isRiffWave(data: ByteArray, magic: String): Boolean {
        if (data.size < 12 || magic != "RIFF") return false
        return String(data, 8, 4, Charsets.US_ASCII) == "WAVE"
    }

    private fun detectSyncFrame(data: ByteArray): DetectedAudioFormat {
        val header16 = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        return when {
            (header16 and 0xFFF6) == 0xFFF0 -> DetectedAudioFormat.AAC
            (header16 and 0xFFE0) == 0xFFE0 -> DetectedAudioFormat.MP3
            else -> DetectedAudioFormat.RAW_PCM
        }
    }

    fun isContainer(data: ByteArray): Boolean = detect(data) != DetectedAudioFormat.RAW_PCM
}
