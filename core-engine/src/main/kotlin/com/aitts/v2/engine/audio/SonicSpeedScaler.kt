package com.aitts.v2.engine.audio

/**
 * Clean Kotlin wrapper around Sonic for streaming and batch pitch-preserving time scaling.
 */
class SonicSpeedScaler(
    sampleRate: Int,
    channels: Int,
    speed: Float,
    pitch: Float = 1.0f
) {
    private val sonic = Sonic(sampleRate, channels).apply {
        this.speed = speed.coerceIn(0.25f, 3.0f)
        this.pitch = pitch.coerceIn(0.5f, 2.0f)
    }

    fun processChunk(pcm16: ByteArray): ByteArray {
        if (pcm16.isEmpty()) return ByteArray(0)
        sonic.writeBytesToStream(pcm16, pcm16.size)
        return drainAvailable()
    }

    fun flush(): ByteArray {
        sonic.flushStream()
        return drainAvailable()
    }

    private fun drainAvailable(): ByteArray {
        val availableBytes = sonic.samplesAvailable() * 2
        if (availableBytes <= 0) return ByteArray(0)
        val buf = ByteArray(availableBytes)
        val read = sonic.readBytesFromStream(buf, buf.size)
        return if (read > 0) buf.copyOf(read) else ByteArray(0)
    }

    companion object {
        fun scaleBatch(
            pcm16: ByteArray,
            sampleRate: Int,
            channels: Int,
            speed: Float,
            pitch: Float = 1.0f
        ): ByteArray {
            val noSpeed = kotlin.math.abs(speed - 1.0f) < 0.03f
            val noPitch = kotlin.math.abs(pitch - 1.0f) < 0.03f
            if (pcm16.isEmpty() || (noSpeed && noPitch)) return pcm16
            val scaler = SonicSpeedScaler(sampleRate, channels, speed, pitch)
            val head = scaler.processChunk(pcm16)
            val tail = scaler.flush()
            return if (tail.isEmpty()) head else head + tail
        }
    }
}
